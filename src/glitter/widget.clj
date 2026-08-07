(ns glitter.widget
  "Hiccup -> GTK4 widgets. A data-driven registry maps hiccup tags to widget
  constructors, prop maps to GTK setter calls, and :on-* event keys to GTK
  signals wired through foreign-callable. This layer creates/patches widgets and
  manages container children; the reconciler in glitter.core drives when.

  Lifecycle: create! builds a widget (constructs it, applies props, connects any
  :on-* handlers). apply-props! re-applies the prop map to an existing widget on
  re-render.

  Note on signals: this namespace was forked from glimmer, whose Reagent-style
  model connects each signal ONCE at mount and lets the handler close over a
  reactive cell, so the first render's closure stays correct for the widget's
  life. glitter does NOT work that way. Its handlers are data, not closures,
  and glitter.core's diff calls IRender/set-event-handler again whenever the
  handler DATA changes between renders — so glitter.gtk connects and
  disconnects signals itself (via signal-name + signal-value-fn +
  g_signal_handler_disconnect) and never routes reconciler-driven events
  through connect-signals! at all. connect-signals! remains for create!'s
  direct-props path and for extensions."
  (:require [clojure.string :as str]
            [glitter.ffi :as g]
            [glitter.genum :as genum]
            [hiccup2.core :as hiccup]
            [jolt.ffi :as ffi]))

;; --- value marshalling -------------------------------------------------------
(defn- ->bool [x] (if x 1 0))

;; Pointers are plain machine addresses under jolt.ffi (0 for NULL, never a
;; Clojure nil) — but a getter that legitimately found nothing (an empty
;; GtkCenterBox slot, a GtkListBoxRow's own parent lookup with nothing
;; selected) can surface either. Used by :center-box's slot-occupancy checks
;; and :list-box's row/selection lookups.
(defn- ptr-null? [p] (or (nil? p) (zero? p)))

(defn escape-markup
  "Escape `&`, `<`, `>` so `s` can be embedded safely inside Pango markup passed
  to a label's `:markup` prop. `&` is escaped first so the angle-bracket escapes
  are not themselves re-encoded."
  ^String [^String s]
  (-> s
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

;; --- Pango markup from hiccup data ------------------------------------------
;; Pango's text-attribute markup is a small XML subset (b, i, span, a, ...),
;; NOT HTML. Hiccup serializes vectors to a string and escapes content/attrs,
;; but it is HTML-flavoured — it will happily emit <div>, <br>, or a typo'd span
;; attribute, which gtk_label_set_markup then can't parse (the label falls back
;; to raw text or emits a GTK warning). So we validate the hiccup *data* against
;; Pango's vocabulary before handing it to hiccup for serialization: a bad
;; fragment fails loudly at the call site instead of rendering silently wrong.
;;
;; Attribute names mirror Pango's own (underscores: :font_family, :letter_spacing),
;; so what you write is exactly what Pango parses.
(def ^:private pango-tags
  "Pango markup vocabulary: tag -> the set of attributes it accepts, or nil when
  the tag takes no attributes."
  {:span #{:font_desc :font_family :face :size :style :weight :variant :stretch
           :foreground :background :alpha :underline :underline_color :rise
           :strikethrough :strikethrough_color :fallback :lang :letter_spacing
           :show :line_height :allow_breaks :insert_hyphens :text_transform
           :gravity :gravity_hint :overline :overline_color}
   :a    #{:href}
   :b nil :big nil :i nil :mark nil :s nil :small nil :sub nil :sup nil :tt nil
   :u nil})

(defn- markup-element? [form] (and (vector? form) (keyword? (first form))))

(declare markup-validate!)

(defn- markup-validate-element! [form]
  (let [tag     (first form)
        body    (rest form)
        attrs?  (map? (first body))
        attrs   (if attrs? (first body) nil)
        children (if attrs? (rest body) body)]
    (if-not (contains? pango-tags tag)
      (throw (ex-info (str "glitter/markup: :" (name tag) " is not a Pango tag")
                      {:tag tag})))
    (let [allowed (get pango-tags tag)]
      (when (and attrs (seq attrs))
        (if (nil? allowed)
          (throw (ex-info (str "glitter/markup: :" (name tag) " takes no attributes")
                          {:tag tag :attrs (keys attrs)}))
          (doseq [k (keys attrs)]
            (when-not (contains? allowed k)
              (throw (ex-info (str "glitter/markup: :" (name k)
                                   " is not a :" (name tag) " attribute")
                              {:tag tag :attr k}))))))
      (run! markup-validate! children))))

(defn- markup-validate! [form]
  (cond
    (markup-element? form)  (markup-validate-element! form)
    (sequential? form)      (run! markup-validate! form)
    :else                   nil))

(defn markup
  "Render hiccup `form` to a Pango markup string for a label's :markup prop.

  [:span {:foreground \"#8e939d\"} \"Nothing to do yet\"]
  [:b [:i \"bold italic\"]]

  Serialization (escaping, seq expansion) is delegated to hiccup; the data is
  first validated against Pango's tag/attribute vocabulary, so an HTML-only tag
  (:div, :br) or a typo'd attribute (:forground) throws here rather than
  producing markup gtk_label_set_markup can't parse. Pango attribute names use
  underscores (:font_family, :letter_spacing) to match Pango's own spelling."
  [form]
  (markup-validate! form)
  (str (hiccup/html form)))

(defn markup-string
  "Coerce a label's :markup prop to a Pango markup string. A string passes through
  as-is (already markup); anything else is treated as hiccup and rendered via
  `markup` — so a [:label {:markup [:span ...]}] is validated and has its text
  escaped instead of the caller hand-rolling the XML."
  [m]
  (if (string? m) m (markup m)))

;; Resolve a property that may be a GEnum nick keyword (:start, :fill) OR a raw
;; integer. genum/enum returns nil when the type isn't live yet or the nick is
;; unknown; in that case fall back to the raw value (so callers can still pass
;; an explicit int). Applied in :apply, where the widget already exists and its
;; enum type is registered — so the common path resolves the nick.
(defn- ->enum [type-name x]
  (or (genum/enum type-name x) x))

;; GtkOrientation nicks are :horizontal / :vertical. Used by box & separator.
(defn- ->orientation [x] (->enum "GtkOrientation" x))

;; --- tag aliases (sugar) -----------------------------------------------------
;; :hbox / :vbox are both GtkBox; the difference is orientation. normalize-tag
;; maps them to the :box spec, and with-orientation injects the matching
;; :orientation so a bare [:hbox ...] actually lays out horizontally (the box
;; ctor builds vertical by default and :apply corrects it). An explicit
;; :orientation in props always wins.
(def ^:private aliases {:hbox :box :vbox :box})

(def ^:private tag-orientation {:hbox :horizontal :vbox :vertical})

(defn- normalize-tag [tag] (get aliases tag tag))

(defn with-orientation
  "Inject the orientation implied by an :hbox/:vbox tag into its props, unless the
  caller already set :orientation. A no-op for any other tag."
  [tag props]
  (if-let [o (tag-orientation tag)]
    (if (contains? props :orientation) props (assoc props :orientation o))
    props))

;; --- signal registry ---------------------------------------------------------
;; event keyword -> GTK signal name. MOST handlers have the GTK signature
;; void(widget, user_data), and our callable ignores both args and invokes
;; the captured jolt handler, so one table covers every widget type of that
;; shape — see signal-callable-shape below for the (currently one) exception.
;; An atom so third-party extensions can add events via register-signal!
;; without editing this ns; :on-value-changed (scale's slider-drag signal)
;; and :on-state-set (switch's interaction signal) are first-party entries
;; here, same as the original four — see signal-value below for their
;; value-fns.
(def signals
  (atom {:on-click            "clicked"
         :on-change           "changed"
         :on-activate         "activate"
         :on-toggled          "toggled"
         :on-value-changed    "value-changed"
         :on-state-set        "state-set"
         :on-row-selected     "row-selected"
         :on-row-activated    "row-activated"
         :on-search-changed   "search-changed"
         :on-expanded         "notify::expanded"
         :on-position-changed "notify::position"}))

;; --- widget specs ------------------------------------------------------------
;; Each spec: {:ctor (fn [props] widget-ptr) :apply (fn [widget props]) :container (#{:box :window :none})}
;; The two suppressing setters live further down, beside the `suppressing` atom
;; they read; the :apply closures below call them, so declare them here. A
;; reference to a name that isn't interned yet is a compile error, in a nested
;; closure as much as at the top level.
(declare set-entry-text! set-checkbutton-active! set-scale-value! set-toggle-button-active! set-switch-active!
         set-spin-button-value! set-expander-expanded! set-paned-position!)

(defn- window-spec []
  {:ctor    (fn [_] (g/gtk-window-new))
   :apply   (fn [w p]
              (when (:title p) (g/gtk-window-set-title w (:title p)))
              (when (or (:width p) (:height p))
                (g/gtk-window-set-default-size w (or (:width p) -1) (or (:height p) -1)))
              (g/gtk-widget-set-visible w (->bool (not (false? (:visible p))))))
   :container :window})

(defn- box-spec []
  {:ctor    (fn [p]
              ;; construct vertical by default; the real orientation is set in
              ;; :apply, by which point the box exists and GtkOrientation is
              ;; registered (the box installs the orientation property).
              (g/gtk-box-new 1 (or (:spacing p) 0)))
   :apply   (fn [w p]
              (when (contains? p :spacing)     (g/gtk-box-set-spacing w (:spacing p)))
              (when (contains? p :homogeneous) (g/gtk-box-set-homogeneous w (->bool (:homogeneous p))))
              (when (contains? p :orientation) (g/gtk-orientable-set-orientation w (->orientation (:orientation p)))))
   :container :box})

(defn- center-box-spec []
  ;; Three fixed NAMED slots (start/center/end), not an ordered append list
  ;; like :box. See center-box-append-child!/center-box-remove-child!/
  ;; center-box-replace-child!/center-box-insert-after! (below, beside the
  ;; other container-management fns) and their :center-box case-branch
  ;; entries in append-child!/remove-child!/replace-child!/
  ;; insert-child-after! — this spec itself needs no special handling
  ;; beyond the ctor: no signal, no props beyond the universal GtkWidget
  ;; ones. KNOWN V1 GAP: do not swap a slot's hiccup TAG (e.g. :label ->
  ;; :button) while all 3 slots are occupied — see
  ;; center-box-insert-after!'s docstring for the mechanism.
  {:ctor  (fn [_] (g/gtk-center-box-new))
   :apply (fn [_ _])
   :container :center-box})

(defn- paned-spec []
  ;; Two fixed NAMED slots (start/end) — like :center-box's three but
  ;; simpler, and inheriting the SAME structural v1 gap :center-box has
  ;; (see paned-insert-after!'s docstring below): do not swap a slot's
  ;; hiccup TAG while both slots are occupied. GtkPaned implements
  ;; GtkOrientable (confirmed against gtk/gtkpaned.c), so :orientation
  ;; uses the same construct-with-a-safe-default-then-correct-via-:apply
  ;; pattern :box/:separator already use — the raw int 0 is
  ;; GTK_ORIENTATION_HORIZONTAL, same verified constant scale-spec/
  ;; box-spec already rely on. :position (the divider's pixel offset)
  ;; goes through set-paned-position! (suppressing-guard setter) and is
  ;; watched via "notify::position" — another free reuse of the
  ;; 3-arg-void callable shape.
  {:ctor  (fn [_] (g/gtk-paned-new 0))
   :apply (fn [w p]
            (when (contains? p :orientation) (g/gtk-orientable-set-orientation w (->orientation (:orientation p))))
            (when (contains? p :position)    (set-paned-position! w (:position p))))
   :container :paned})

(defn- button-spec []
  {:ctor    (fn [p] (if (:label p) (g/gtk-button-new-with-label (:label p)) (g/gtk-button-new)))
   :apply   (fn [w p]
              (when (contains? p :label)   (g/gtk-button-set-label w (:label p)))
              (when (:tooltip p)           (g/gtk-widget-set-tooltip-text w (:tooltip p)))
              (when (contains? p :sensitive) (g/gtk-widget-set-sensitive w (->bool (:sensitive p)))))
   :container :none})

(defn- link-button-spec []
  ;; GtkLinkButton extends GtkButton — reuses :on-click -> "clicked"
  ;; verbatim (no new signal wiring, same free-reuse story as
  ;; :toggle-button's "toggled") and gtk-button-set-label/tooltip/sensitive
  ;; directly, since those are inherited GtkButton/GtkWidget methods.
  {:ctor  (fn [p] (if (:label p)
                    (g/gtk-link-button-new-with-label (or (:uri p) "") (:label p))
                    (g/gtk-link-button-new (or (:uri p) ""))))
   :apply (fn [w p]
            (when (contains? p :uri)       (g/gtk-link-button-set-uri w (:uri p)))
            (when (contains? p :label)     (g/gtk-button-set-label w (:label p)))
            (when (:tooltip p)             (g/gtk-widget-set-tooltip-text w (:tooltip p)))
            (when (contains? p :sensitive) (g/gtk-widget-set-sensitive w (->bool (:sensitive p)))))
   :container :none})

(defn- label-spec []
  {:ctor  (fn [p] (g/gtk-label-new (or (:label p) (:text p) "")))
   :apply (fn [w p]
            (when (contains? p :label)  (g/gtk-label-set-label w (:label p)))
            (when (contains? p :text)   (g/gtk-label-set-text w (:text p)))
            (when (contains? p :markup) (g/gtk-label-set-markup w (markup-string (:markup p))))
            (when (contains? p :xalign) (g/gtk-label-set-xalign w (:xalign p)))
            (when (contains? p :wrap)   (g/gtk-label-set-wrap w (->bool (:wrap p))))
            (when (contains? p :width-chars)     (g/gtk-label-set-width-chars w (:width-chars p)))
            (when (contains? p :max-width-chars) (g/gtk-label-set-max-width-chars w (:max-width-chars p)))
            (when (contains? p :lines)   (g/gtk-label-set-lines w (:lines p)))
            (when (contains? p :ellipsize) (g/gtk-label-set-ellipsize w (->enum "PangoEllipsizeMode" (:ellipsize p)))))
   :container :none})

(defn- entry-spec []
  {:ctor  (fn [_] (g/gtk-entry-new))
   :apply (fn [w p]
            (when (contains? p :text)        (set-entry-text! w (:text p)))
            (when (contains? p :placeholder) (g/gtk-editable-set-placeholder-text w (:placeholder p)))
            (when (contains? p :sensitive)   (g/gtk-widget-set-sensitive w (->bool (:sensitive p)))))
   :container :none})

(defn- password-entry-spec []
  ;; GtkPasswordEntry implements GtkEditable via a delegate (confirmed
  ;; against gtk/gtkpasswordentry.c — see ffi.clj's own comment) — reuses
  ;; set-entry-text! and :entry's "changed" signal entry verbatim, same
  ;; free-reuse story as :toggle-button/:link-button. Only
  ;; :show-peek-icon (the reveal-text toggle button) is new.
  {:ctor  (fn [_] (g/gtk-password-entry-new))
   :apply (fn [w p]
            (when (contains? p :text)           (set-entry-text! w (:text p)))
            (when (contains? p :placeholder)    (g/gtk-editable-set-placeholder-text w (:placeholder p)))
            (when (contains? p :show-peek-icon) (g/gtk-password-entry-set-show-peek-icon w (->bool (:show-peek-icon p))))
            (when (contains? p :sensitive)      (g/gtk-widget-set-sensitive w (->bool (:sensitive p)))))
   :container :none})

(defn- search-entry-spec []
  ;; Same GtkEditable-delegate reuse as :password-entry, plus its own
  ;; genuinely new :on-search-changed signal (debounced by :search-delay
  ;; ms — see ffi.clj's own comment).
  {:ctor  (fn [_] (g/gtk-search-entry-new))
   :apply (fn [w p]
            (when (contains? p :text)          (set-entry-text! w (:text p)))
            (when (contains? p :placeholder)   (g/gtk-editable-set-placeholder-text w (:placeholder p)))
            (when (contains? p :search-delay)  (g/gtk-search-entry-set-search-delay w (:search-delay p)))
            (when (contains? p :sensitive)     (g/gtk-widget-set-sensitive w (->bool (:sensitive p)))))
   :container :none})

(defn- checkbutton-spec []
  {:ctor  (fn [p] (if (:label p)
                    (g/gtk-checkbutton-new-with-label (:label p))
                    (g/gtk-checkbutton-new)))
   :apply (fn [w p]
            (when (contains? p :active) (set-checkbutton-active! w (:active p))))
   :container :none})

(defn- toggle-button-spec []
  ;; A pressable, stays-down button — same :active concept as checkbutton,
  ;; styled as a button. Its "toggled" signal already has an entry in
  ;; `signals` (:on-toggled), so :on {:toggled ...} in hiccup Just Works with
  ;; no new signal wiring — only the ctor/set/get-active calls are new.
  {:ctor  (fn [p] (if (:label p)
                    (g/gtk-toggle-button-new-with-label (:label p))
                    (g/gtk-toggle-button-new)))
   :apply (fn [w p]
            (when (contains? p :active)    (set-toggle-button-active! w (:active p)))
            (when (contains? p :label)     (g/gtk-button-set-label w (:label p)))
            (when (:tooltip p)             (g/gtk-widget-set-tooltip-text w (:tooltip p)))
            (when (contains? p :sensitive) (g/gtk-widget-set-sensitive w (->bool (:sensitive p)))))
   :container :none})

(defn- separator-spec []
  {:ctor  (fn [_] (g/gtk-separator-new 0))   ; horizontal default; :apply sets the real orientation
   :apply (fn [w p] (when (contains? p :orientation) (g/gtk-orientable-set-orientation w (->orientation (:orientation p)))))
   :container :none})

(defn- frame-spec []
  {:ctor     (fn [p] (g/gtk-frame-new (or (:label p) "")))
   :apply    (fn [w p] (when (contains? p :label) (g/gtk-frame-set-label w (or (:label p) ""))))
   :container :frame})

(defn- scrolled-spec []
  ;; A single-child viewport. Built with natural-size propagation OFF so the
  ;; child scrolls inside the allotted area instead of forcing the window bigger.
  {:ctor     (fn [_]
               (let [sw (g/gtk-scrolled-window-new ffi/null ffi/null)]
                 (g/gtk-scrolled-window-set-propagate-natural-height sw 0)
                 (g/gtk-scrolled-window-set-propagate-natural-width sw 0)
                 sw))
   :apply    (fn [_ _])
   :container :scrolled})

(defn- revealer-spec []
  ;; Single-child container (gtk_revealer_set_child) — same container
  ;; strategy as :frame/:scrolled, so no new container-management logic
  ;; beyond a one-line case entry in append-child!/remove-child!/
  ;; replace-child! below. No signal: :reveal-child directly drives the
  ;; animated show/hide. :transition-type/:transition-duration applied
  ;; BEFORE :reveal-child (same ordering concern as :scale/:level-bar
  ;; re-ranging before setting :value) so the first reveal already uses the
  ;; caller's transition settings, not GTK's defaults. :transition-type
  ;; resolves a GtkRevealerTransitionType nick (:crossfade, :slide-right,
  ;; :slide-up, ...) the same way :halign/:valign resolve GtkAlign, via
  ;; glitter.genum's runtime GEnum lookup — no hardcoded nick table here.
  {:ctor  (fn [_] (g/gtk-revealer-new))
   :apply (fn [w p]
            (when (contains? p :transition-type)
              (g/gtk-revealer-set-transition-type w (->enum "GtkRevealerTransitionType" (:transition-type p))))
            (when (contains? p :transition-duration)
              (g/gtk-revealer-set-transition-duration w (:transition-duration p)))
            (when (contains? p :reveal-child)
              (g/gtk-revealer-set-reveal-child w (->bool (:reveal-child p)))))
   :container :revealer})

(defn- expander-spec []
  ;; Single-child container (gtk_expander_set_child) — same strategy as
  ;; :frame/:scrolled/:revealer. No dedicated interaction signal of its
  ;; own (confirmed: no g_signal_new in gtk/gtkexpander.c) — :on-expanded
  ;; watches "notify::expanded" instead, reusing the 3-arg-void callable
  ;; shape already generalized for :list-box (see ffi.clj's own comment
  ;; and glitter.gtk/set-event-handler).
  {:ctor  (fn [p] (g/gtk-expander-new (or (:label p) "")))
   :apply (fn [w p]
            (when (contains? p :label)    (g/gtk-expander-set-label w (or (:label p) "")))
            (when (contains? p :expanded) (set-expander-expanded! w (:expanded p))))
   :container :expander})

(defn- scale-spec []
  ;; gtk_scale_new_with_range needs orientation + an initial min/max/step at
  ;; construction time — unlike box/separator, there's no cheap "construct
  ;; then correct orientation later" split, because min/max/step aren't
  ;; GtkOrientable-style properties re-settable after the fact independent of
  ;; the initial range. Construct horizontal by default (same
  ;; construct-before-GtkOrientation-is-registered concern as box/separator —
  ;; the raw int 0 is GTK_ORIENTATION_HORIZONTAL, verified against gtk/
  ;; gtkenums.h) with :min/:max/:step falling back to a plain 0-100-by-1
  ;; range if the caller doesn't set them; :apply then re-ranges via
  ;; gtk_range_set_range/set_increments on every render if they change, same
  ;; as box's :apply corrects orientation once GtkOrientation is live.
  {:ctor  (fn [p]
            (g/gtk-scale-new-with-range 0
                                        (double (or (:min p) 0))
                                        (double (or (:max p) 100))
                                        (double (or (:step p) 1))))
   :apply (fn [w p]
            (when (contains? p :orientation)
              (g/gtk-orientable-set-orientation w (->orientation (:orientation p))))
            (when (or (contains? p :min) (contains? p :max))
              (g/gtk-range-set-range w (double (or (:min p) 0)) (double (or (:max p) 100))))
            (when (contains? p :step)
              (g/gtk-range-set-increments w (double (:step p)) (double (:step p))))
            (when (contains? p :value)       (set-scale-value! w (:value p)))
            (when (contains? p :digits)      (g/gtk-scale-set-digits w (:digits p)))
            (when (contains? p :draw-value)  (g/gtk-scale-set-draw-value w (->bool (:draw-value p)))))
   :container :none})

(defn- spin-button-spec []
  ;; gtk_spin_button_new_with_range needs min/max/step at construction time,
  ;; same "no cheap construct-then-correct-later split" shape as :scale.
  ;; Falls back to a plain 0-100-by-1 range if the caller doesn't set them;
  ;; :apply re-ranges via gtk_spin_button_set_range/set_increments on every
  ;; render if they change, same ordering-before-:value concern as :scale's
  ;; :apply. Its "value-changed" signal already has an entry in `signals`
  ;; (:on-value-changed, shared with :scale — confirmed via
  ;; gtk/gtkspinbutton.c's g_signal_new that GtkSpinButton's real signal
  ;; name is the exact same string GtkScale/GtkRange already uses) — see
  ;; signal-value's [tag signal]-keyed table below for why that shared name
  ;; needing a DIFFERENT getter per widget type doesn't collide.
  {:ctor  (fn [p]
            (g/gtk-spin-button-new-with-range
             (double (or (:min p) 0))
             (double (or (:max p) 100))
             (double (or (:step p) 1))))
   :apply (fn [w p]
            (when (or (contains? p :min) (contains? p :max))
              (g/gtk-spin-button-set-range w (double (or (:min p) 0)) (double (or (:max p) 100))))
            (when (contains? p :step)
              (g/gtk-spin-button-set-increments w (double (:step p)) (double (:step p))))
            (when (contains? p :value)     (set-spin-button-value! w (:value p)))
            (when (contains? p :digits)    (g/gtk-spin-button-set-digits w (:digits p)))
            (when (contains? p :sensitive) (g/gtk-widget-set-sensitive w (->bool (:sensitive p)))))
   :container :none})

(defn- spinner-spec []
  ;; Display-only — driven entirely by :spinning, no signal to wire.
  {:ctor  (fn [_] (g/gtk-spinner-new))
   :apply (fn [w p] (when (contains? p :spinning) (g/gtk-spinner-set-spinning w (->bool (:spinning p)))))
   :container :none})

(defn- progress-bar-spec []
  ;; Display-only — driven by :fraction/:text/:show-text, no signal to wire.
  {:ctor  (fn [_] (g/gtk-progress-bar-new))
   :apply (fn [w p]
            (when (contains? p :fraction)  (g/gtk-progress-bar-set-fraction w (double (:fraction p))))
            (when (contains? p :text)      (g/gtk-progress-bar-set-text w (:text p)))
            (when (contains? p :show-text) (g/gtk-progress-bar-set-show-text w (->bool (:show-text p)))))
   :container :none})

(defn- image-spec []
  ;; Display-only — driven by :icon-name/:file/:pixel-size, no signal to wire.
  ;; GtkImage's storage type switches automatically per whichever setter ran
  ;; last, so :ctor picking whichever of :icon-name/:file is present (falling
  ;; back to the empty constructor) and :apply re-applying either key on a
  ;; later render both just work with no explicit "clear the old one" step.
  {:ctor  (fn [p] (cond
                    (:icon-name p) (g/gtk-image-new-from-icon-name (:icon-name p))
                    (:file p)      (g/gtk-image-new-from-file (:file p))
                    :else          (g/gtk-image-new)))
   :apply (fn [w p]
            (when (contains? p :icon-name)  (g/gtk-image-set-from-icon-name w (:icon-name p)))
            (when (contains? p :file)       (g/gtk-image-set-from-file w (:file p)))
            (when (contains? p :pixel-size) (g/gtk-image-set-pixel-size w (:pixel-size p))))
   :container :none})

(defn- level-bar-spec []
  ;; Display-only — driven by :value/:min-value/:max-value/:inverted, no
  ;; signal to wire. Min/max applied BEFORE value (same ordering concern as
  ;; scale-spec re-ranging before setting :value) so a caller-supplied range
  ;; is live before the value that's meant to land inside it. :mode
  ;; (continuous vs. discrete segments) is out of scope for v1, matching
  ;; progress-bar's minimal-viable-display-widget scope.
  {:ctor  (fn [_] (g/gtk-level-bar-new))
   :apply (fn [w p]
            (when (contains? p :min-value) (g/gtk-level-bar-set-min-value w (double (:min-value p))))
            (when (contains? p :max-value) (g/gtk-level-bar-set-max-value w (double (:max-value p))))
            (when (contains? p :value)     (g/gtk-level-bar-set-value w (double (:value p))))
            (when (contains? p :inverted)  (g/gtk-level-bar-set-inverted w (->bool (:inverted p)))))
   :container :none})

(defn- list-box-spec []
  ;; A selectable-row list container. See the :list-box case-branches in
  ;; append-child!/remove-child!/replace-child! below for the GTK
  ;; row-wrapping mechanics, and signal-value's [tag signal]-keyed entries
  ;; for :on-row-selected/:on-row-activated's value-fn. No :apply props
  ;; beyond the universal GtkWidget ones — GTK4's default :selection-mode
  ;; :single + :activate-single-click true (both left unchanged) is the
  ;; only mode v1 supports correctly; :selection-mode :none is a documented
  ;; v1 gap (see gtk-widget-layer.md). v1 also does not support keyed
  ;; reorder or mid-list positional insert (see the :list-box container
  ;; functions' own docstrings).
  {:ctor  (fn [_] (g/gtk-list-box-new))
   :apply (fn [_ _])
   :container :list-box})

(defn- switch-spec []
  ;; The widget signal-callable-shape (below) exists FOR: "state-set" isn't
  ;; the uniform void(widget, user_data) shape, so it needs a registered
  ;; entry there or glitter.gtk's set-event-handler would build the wrong
  ;; callable signature. See signal-callable-shape's own docstring.
  {:ctor  (fn [_] (g/gtk-switch-new))
   :apply (fn [w p] (when (contains? p :active) (set-switch-active! w (:active p))))
   :container :none})

;; hiccup tag -> widget spec. An atom so extensions register new widget types
;; via register-widget! without editing this ns.
(def specs
  (atom {:window         (window-spec)
         :box            (box-spec)
         :center-box     (center-box-spec)
         :paned          (paned-spec)
         :button         (button-spec)
         :link-button    (link-button-spec)
         :label          (label-spec)
         :entry          (entry-spec)
         :password-entry (password-entry-spec)
         :search-entry   (search-entry-spec)
         :checkbutton    (checkbutton-spec)
         :toggle-button  (toggle-button-spec)
         :switch         (switch-spec)
         :separator      (separator-spec)
         :frame          (frame-spec)
         :scrolled       (scrolled-spec)
         :revealer       (revealer-spec)
         :expander       (expander-spec)
         :scale          (scale-spec)
         :spin-button    (spin-button-spec)
         :spinner        (spinner-spec)
         :progress-bar   (progress-bar-spec)
         :image          (image-spec)
         :level-bar      (level-bar-spec)
         :list-box       (list-box-spec)}))

(defn register-widget!
  "Register a widget spec under hiccup `tag`. A spec is
  {:ctor (fn [props] widget) :apply (fn [widget props]) :container kw
   :connect (fn [widget props])?}. :container is :none for a leaf, or :box /
   :window / :frame / :scrolled to reuse an existing child-management strategy.
  The optional :connect runs at create! time after the generic :on-* wiring, for
  widgets whose signals don't fit the uniform void(widget,data) shape (e.g. a
  GtkGLArea's realize/render/resize)."
  [tag spec] (swap! specs assoc tag spec) nil)

(defn- spec-for
  "The widget spec registered for hiccup `tag`, or nil. Prefer spec-for! at any
  call site that is about to dereference the result."
  [tag]
  (@specs (normalize-tag tag)))

(defn- spec-for!
  "Like spec-for, but throws a named error instead of letting a nil spec
  surface as `class nil cannot be cast to class clojure.lang.IFn` several
  frames later, with no mention of the offending tag. Tag typos are the most
  common authoring error in a hiccup library, and glitter.core's own
  undefined-alias fallback (render-default-alias) emits a `:div` — a DOM tag
  no GTK backend can have — so this path is reachable from the library's own
  error recovery, not just from user typos."
  [tag]
  (or (spec-for tag)
      (throw (ex-info (str "glitter/widget: no widget registered for hiccup tag "
                           tag ". Registered tags: "
                           (str/join ", " (sort (map str (keys @specs))))
                           ". Register one with glitter.widget/register-widget!.")
                      {:tag tag :registered (set (keys @specs))}))))

(defn container-kind
  "How a tag holds children: :box (ordered append/remove), :window (single child),
  or :none (leaf)."
  [tag] (:container (spec-for tag)))

;; --- callable registry (keep signal callbacks alive for the widget's life) ----
;; foreign-callable retains its closure until free-callable, but we also hold
;; strong references here as a belt-and-suspenders against any GC edge.
(def ^:private callables (atom #{}))

(defn retain-callable!
  "Hold a strong reference to a foreign-callable pointer for the process lifetime
  so it isn't collected while C (GTK) still holds only the raw pointer."
  [cb] (swap! callables conj cb) cb)

(defn release-callable!
  "Drop a reference previously held by `retain-callable!`, allowing collection
  once C no longer holds the pointer. Pair with one-shot foreign-callables (e.g.
  a g_idle_add source that returns FALSE) so a long-lived REPL session doesn't
  accumulate one retained closure per re-render."
  [cb] (swap! callables disj cb) cb)

;; Widgets whose signal we are currently firing ourselves (via a programmatic
;; setter — gtk_editable_set_text, gtk_check_button_set_active). connect-signals
;; gates handlers on this set, so a programmatic prop change can't feed back into
;; a reset!/re-render loop. GTK emits the signal synchronously during the setter,
;; so a plain conj-around-call-disj brackets exactly the spurious emission.
(def ^:private suppressing (atom #{}))

(defn- set-entry-text!
  "Set an entry's text, but only when it differs from the current text, and while
  suppressing the :on-change handler for the synchronous 'changed' emission this
  causes. Avoids the set_text -> on-change -> reset! -> re-render -> set_text loop."
  [widget text]
  (when (and (some? text) (not= text (g/gtk-editable-get-text widget)))
    (swap! suppressing conj widget)
    (g/gtk-editable-set-text widget text)
    (swap! suppressing disj widget)))

(defn- set-checkbutton-active!
  "Set a checkbutton's active state, but only when it differs from the widget's
  current state, and while suppressing the :on-toggled handler for the synchronous
  'toggled' emission gtk_check_button_set_active causes. Without this, a bulk
  update (e.g. 'complete all' flipping every task's :done) would re-render each
  row, set_active would fire 'toggled', and the row's handler would flip the task
  straight back."
  [widget active?]
  (let [target (->bool active?)]
    (when (not= target (g/gtk-checkbutton-get-active widget))
      (swap! suppressing conj widget)
      (g/gtk-checkbutton-set-active widget target)
      (swap! suppressing disj widget))))

(defn- set-toggle-button-active!
  "Set a toggle button's active state, but only when it differs from the
  widget's current state, and while suppressing the :on-toggled handler for
  the synchronous 'toggled' emission gtk_toggle_button_set_active causes.
  Same set-compare-suppress shape as set-checkbutton-active! — GtkToggleButton
  is a separate GTK4 class (not related to GtkCheckButton pre-GTK4 relation
  removed), so it needs its own pair of FFI calls, but the loop-prevention
  concern is identical."
  [widget active?]
  (let [target (->bool active?)]
    (when (not= target (g/gtk-toggle-button-get-active widget))
      (swap! suppressing conj widget)
      (g/gtk-toggle-button-set-active widget target)
      (swap! suppressing disj widget))))

(defn- set-switch-active!
  "Set a switch's active state, but only when it differs from the widget's
  current state, and while suppressing the :on-state-set handler for the
  synchronous 'state-set' emission gtk_switch_set_active causes (verified
  live: unlike GtkButton's activate path, GtkSwitch's own source sets
  self->is_active THEN synchronously emits state-set — no animation
  timeout involved). Same set-compare-suppress shape as
  set-checkbutton-active!/set-toggle-button-active!."
  [widget active?]
  (let [target (->bool active?)]
    (when (not= target (g/gtk-switch-get-active widget))
      (swap! suppressing conj widget)
      (g/gtk-switch-set-active widget target)
      (swap! suppressing disj widget))))

(defn- set-scale-value!
  "Set a scale's value, but only when it differs from the widget's current
  value, and while suppressing the :on-value-changed handler for the
  synchronous 'value-changed' emission gtk_range_set_value causes. Same
  set-compare-suppress shape as set-entry-text!/set-checkbutton-active! —
  without it, feeding the reconciled :value back on every render would loop
  set_value -> value-changed -> dispatch -> re-render -> set_value."
  [widget value]
  (when (and (some? value) (not= (double value) (g/gtk-range-get-value widget)))
    (swap! suppressing conj widget)
    (g/gtk-range-set-value widget (double value))
    (swap! suppressing disj widget)))

(defn- set-spin-button-value!
  "Set a spin button's value, but only when it differs from the widget's
  current value, and while suppressing the :on-value-changed handler for
  the synchronous 'value-changed' emission gtk_spin_button_set_value
  causes. Same set-compare-suppress shape as set-scale-value! —
  GtkSpinButton is a separate GTK4 class from GtkScale/GtkRange (its own
  get/set-value pair, not inherited), but the loop-prevention concern is
  identical."
  [widget value]
  (when (and (some? value) (not= (double value) (g/gtk-spin-button-get-value widget)))
    (swap! suppressing conj widget)
    (g/gtk-spin-button-set-value widget (double value))
    (swap! suppressing disj widget)))

(defn- set-expander-expanded!
  "Set an expander's expanded state, but only when it differs from the
  widget's current state, and while suppressing the :on-expanded handler
  for the synchronous 'notify::expanded' emission gtk_expander_set_expanded
  causes (GObject property-change notification is always synchronous when
  a setter actually changes the value). Same set-compare-suppress shape
  as set-scale-value!/set-spin-button-value!/etc."
  [widget expanded?]
  (let [target (->bool expanded?)]
    (when (not= target (g/gtk-expander-get-expanded widget))
      (swap! suppressing conj widget)
      (g/gtk-expander-set-expanded widget target)
      (swap! suppressing disj widget))))

(defn- set-paned-position!
  "Set a paned's divider position, but only when it differs from the
  widget's current position, and while suppressing the
  :on-position-changed handler for the synchronous 'notify::position'
  emission gtk_paned_set_position causes. Same set-compare-suppress shape
  as every other value-bearing widget's programmatic setter here."
  [widget position]
  (when (and (some? position) (not= (int position) (g/gtk-paned-get-position widget)))
    (swap! suppressing conj widget)
    (g/gtk-paned-set-position widget (int position))
    (swap! suppressing disj widget)))

(defn- list-box-selected-index
  "The currently selected row's index, or nil if none — read via
  gtk_list_box_get_selected_row -> gtk_list_box_row_get_index AFTER the
  signal fires, same 're-read the widget's own state, ignore the raw
  signal argument' pattern as every other value-fn here. Backs both
  'row-selected' and 'row-activated' — verified against
  gtk/gtklistbox.c's gtk_list_box_select_and_activate_full, which selects
  a row BEFORE activating it (activate-single-click defaults to TRUE), so
  by the time either signal's handler runs, get_selected_row already
  reflects the right row even for row-activated."
  [widget]
  (let [row (g/gtk-list-box-get-selected-row widget)]
    (when-not (ptr-null? row) (g/gtk-list-box-row-get-index row))))

;; Which [tag gtk-signal-name] pairs carry a value the handler wants:
;; [tag signal] -> (fn [widget] value). An atom so a third-party extension
;; can register a value-bearing signal without editing this table.
;;
;; Keyed by [tag signal], NOT bare signal name — GTK signal names are not
;; unique per MEANING across widget types. "value-changed" is a case in
;; point: both :scale (GtkScale/GtkRange) and :spin-button (GtkSpinButton)
;; emit it, but each needs a DIFFERENT getter (gtk_range_get_value vs.
;; gtk_spin_button_get_value) — confirmed against gtk/gtkspinbutton.c's
;; g_signal_new that GtkSpinButton's "value-changed" is the exact same
;; string GtkRange already uses. A value-fn keyed by bare signal name
;; would have :spin-button's registration silently clobber :scale's (or
;; vice versa, depending on load order) the moment both widgets existed
;; in the same table — found via source-level verification WHILE adding
;; :spin-button, before it ever shipped and broke :scale. See
;; gtk-widget-layer.md's "generalizing signal-value by tag" section.
;;
;; "state-set" (:switch) reads gtk_switch_get_active AFTER the signal
;; fires, not from the signal's own second argument — verified live
;; against gtk/gtkswitch.c's gtk_switch_set_active: self->is_active is set
;; BEFORE g_signal_emit(STATE_SET) runs, so by the time any handler sees
;; the signal, the getter already reflects the new value. A plain
;; (fn [widget]) value-fn works here exactly like every other signal's,
;; with no need to read the raw signal argument the callable receives.
;; "row-selected"/"row-activated" (:list-box) share list-box-selected-index
;; above, for the same reason.
(def ^:private signal-value
  (atom {[:entry "changed"]                 (fn [widget] (g/gtk-editable-get-text widget))
         [:password-entry "changed"]        (fn [widget] (g/gtk-editable-get-text widget))
         [:search-entry "changed"]          (fn [widget] (g/gtk-editable-get-text widget))
         [:search-entry "search-changed"]   (fn [widget] (g/gtk-editable-get-text widget))
         [:scale "value-changed"]           (fn [widget] (g/gtk-range-get-value widget))
         [:spin-button "value-changed"]     (fn [widget] (g/gtk-spin-button-get-value widget))
         [:switch "state-set"]              (fn [widget] (g/gtk-switch-get-active widget))
         [:list-box "row-selected"]         list-box-selected-index
         [:list-box "row-activated"]        list-box-selected-index
         [:expander "notify::expanded"]     (fn [widget] (g/gtk-expander-get-expanded widget))
         [:paned "notify::position"]        (fn [widget] (g/gtk-paned-get-position widget))}))

;; Almost every GTK signal glitter connects has the uniform
;; void(widget, user_data) shape glitter.gtk's set-event-handler builds by
;; default. GtkSwitch's "state-set" doesn't: its real C signature is
;; gboolean (*)(GtkSwitch*, gboolean, gpointer) — 3 args, non-void return —
;; confirmed against gtk/gtkswitch.c's g_signal_new call, not assumed.
;; GtkListBox's "row-selected"/"row-activated" don't either: their real C
;; signature is void(GtkListBox*, GtkListBoxRow*, gpointer) — 3 args, VOID
;; return this time (a third, distinct shape from "state-set"'s) —
;; likewise confirmed against gtk/gtklistbox.c's g_signal_new calls.
;;
;; Unlike `signals`/`signal-value` above, this is NOT a data table a
;; third-party extension can register into: jolt's `foreign-callable` /
;; `__ccallable` is a compile-time special form, and argtypes/rettype must
;; be literal at the call site — verified live (twice, isolated in a
;; throwaway namespace before touching this file) that passing them as a
;; let-bound local, even holding the exact literal value, throws "Don't
;; know how to create ISeq from: clojure.lang.Symbol" at compile time. A
;; data-driven `{gtk-signal {:argtypes [...] ...}}` table that
;; `set-event-handler` looks up at runtime and splices into one generic
;; `foreign-callable` call — the first design tried here — cannot work.
;; glitter.gtk/set-event-handler instead branches explicitly on `signal`
;; and calls `foreign-callable` at separate literal call sites: the
;; default 2-arg-void one, one for `"state-set"`, and one for
;; `"row-selected"`/`"row-activated"`. Adding a FOURTH non-default shape
;; means adding a fourth literal call site there, by hand — there is no
;; generic extension point for this, and `register-signal!` intentionally
;; has no `shape` parameter, because it could never actually be honored.

(defn register-signal!
  "Register an :on-* event key -> GTK signal name for widget type `tag`.
  With `value-fn` (a widget -> value fn) the handler is called with that
  value instead of zero args — used by value-bearing widgets (e.g.
  :scale's slider, see signal-value above). `tag` is required even when
  value-fn is nil, so glitter.gtk's set-event-handler can look the
  value-fn up by [tag gtk-signal] rather than bare signal name — see
  signal-value's own docstring for why that matters (more than one widget
  type can emit the same GTK signal name with a different meaning). Lets
  extensions add widget events without editing glitter.widget."
  ([tag event gtk-signal] (register-signal! tag event gtk-signal nil))
  ([tag event gtk-signal value-fn]
   (swap! signals assoc event gtk-signal)
   (when value-fn (swap! signal-value assoc [tag gtk-signal] value-fn))
   nil))

(defn signal-name
  "The GTK signal name registered for hiccup event key `event` (e.g.
  :on-click -> \"clicked\"), or nil if unregistered. New relative to the
  glimmer original:
  glimmer's connect-signals! looks the signal name up internally and never
  exposes it, because it connects+wires in one call and never needs to
  disconnect later. glitter.gtk's IRender/set-event-handler +
  remove-event-handler connect and disconnect as two separate calls
  (Replicant's diff calls set-event-handler again whenever the handler *data*
  changes between renders, not just on unmount), so it needs the raw signal
  name to call
  g_signal_connect_data / g_signal_handler_disconnect itself rather than going
  through connect-signals!, which discards the connection id."
  [event]
  (@signals event))

(defn suppressing?
  "True if `widget` currently has its signal emission suppressed — a
  programmatic setter's own synchronous echo (see the `suppressing` set
  above, set/cleared by set-entry-text!/set-checkbutton-active!). New
  relative to the glimmer original: glimmer only ever wires this guard
  through connect-signals!; glitter.gtk connects its own signals directly
  (it needs the raw connection id connect-signals! doesn't expose, to
  support real per-event disconnect) but still needs to see this guard to
  avoid dispatching spurious programmatic-setter-triggered events — found
  live-verified during the final whole-branch review."
  [widget]
  (contains? @suppressing widget))

(defn signal-value-fn
  "The `(fn [widget]) -> value` registered for widget type `tag` emitting
  GTK signal name `signal` (e.g. :entry + \"changed\" -> reads the entry's
  current text via gtk_editable_get_text), or nil if this [tag signal]
  combination carries no extracted value. See register-signal!'s optional
  value-fn arg and the signal-value table above."
  [tag signal]
  (@signal-value [tag signal]))

(defn connect-signals!
  "For every :on-* key in `props`, wrap its handler in a :collect-safe
  foreign-callable (GTK fires it from the blocking g_application_run loop) and
  connect it to the matching GTK signal on `widget` of type `tag`. Connected
  once at mount.

  Handlers are called with zero args — except :on-change, whose handler receives
  the entry's current text (read via gtk_editable_get_text)."
  [tag widget props]
  (doseq [[event handler] props]
    (when-let [signal (@signals event)]
      (let [value-fn (@signal-value [tag signal])
            cb (ffi/foreign-callable
                (fn [src-widget _data]
                   ;; skip emissions we triggered ourselves via a programmatic
                   ;; setter (see `suppressing`) so they can't loop back.
                  (when-not (contains? @suppressing src-widget)
                    (if value-fn (handler (value-fn src-widget)) (handler))))
                [:pointer :pointer] :void :collect-safe)]
        (swap! callables conj cb)
        (g/g-signal-connect-data widget signal cb ffi/null ffi/null g/CONNECT-DEFAULT)))))

;; --- universal GtkWidget props (apply to every widget, every tag) ------------
;; Margins, alignment, expand — GtkWidget-level props that aren't specific to any
;; tag, so they're applied to all of them in addition to the tag's own :apply.
;; :halign/:valign take a GtkAlign nick (:start :end :center :fill :baseline...)
;; resolved at runtime by glitter.genum — no constant table.
(defn apply-widget-props!
  [widget props]
  (when-let [m (:margin props)]
    (g/gtk-widget-set-margin-start widget m)
    (g/gtk-widget-set-margin-end widget m)
    (g/gtk-widget-set-margin-top widget m)
    (g/gtk-widget-set-margin-bottom widget m))
  (when-let [m (:margin-start props)]   (g/gtk-widget-set-margin-start widget m))
  (when-let [m (:margin-end props)]     (g/gtk-widget-set-margin-end widget m))
  (when-let [m (:margin-top props)]     (g/gtk-widget-set-margin-top widget m))
  (when-let [m (:margin-bottom props)]  (g/gtk-widget-set-margin-bottom widget m))
  (when-let [a (:halign props)] (g/gtk-widget-set-halign widget (->enum "GtkAlign" a)))
  (when-let [a (:valign props)] (g/gtk-widget-set-valign widget (->enum "GtkAlign" a)))
  (when (contains? props :hexpand) (g/gtk-widget-set-hexpand widget (->bool (:hexpand props))))
  (when (contains? props :vexpand) (g/gtk-widget-set-vexpand widget (->bool (:vexpand props))))
  (when-let [w (:width-request props)]  (g/gtk-widget-set-size-request widget (int w) -1))
  (when-let [h (:height-request props)] (g/gtk-widget-set-size-request widget -1 (int h))))

;; --- public create / patch ---------------------------------------------------
(defn create!
  "Construct a fresh GTK widget for `tag`, apply `props`, and connect any :on-*
  handlers. Returns the widget pointer. Note: children are NOT added here — the
  reconciler appends them so it can reuse existing children across renders."
  [tag props]
  (let [props (with-orientation tag props)
        s (spec-for! tag)
        widget ((:ctor s) props)]
    ((:apply s) widget props)
    (apply-widget-props! widget props)
    (connect-signals! tag widget props)
    ;; widgets whose signals don't fit the uniform void(widget,data) shape wire
    ;; them here (e.g. :gl-area's realize/render/resize). Runs once at mount.
    (when-let [connect (:connect s)] (connect widget props))
    widget))

(defn apply-props!
  "Re-apply the prop map to an existing widget (re-render path). Skips :on-*
  keys (glitter.gtk owns signal lifecycle — see the ns docstring) and keys
  whose value is nil. An explicit `false` IS applied: `some?`, not truthiness,
  is the filter, because GTK booleans have no absent state.

  Only keys PRESENT in `props` are touched — absent keys are never reset to
  defaults, so this is safe to call with a single-key partial map like
  {:label \"new text\"}, which is exactly how glitter.gtk's set-attribute
  uses it."
  [tag widget props]
  (let [applied (into {} (filter (fn [[k v]] (and (not (@signals k)) (some? v)))
                                 (with-orientation tag props)))]
    ((:apply (spec-for! tag)) widget applied)
    (apply-widget-props! widget applied)))

(defn show!
  "Make a widget visible. GTK4 widgets default to visible, but setting it
  explicitly is harmless and makes :visible false work."
  [widget props]
  (g/gtk-widget-set-visible widget (->bool (not (false? (:visible props))))))

;; --- container child management ----------------------------------------------
;; :center-box helpers — GtkCenterBox has three independently addressable
;; NAMED slots (start/center/end), not an ordered append list like :box.
;; Slot occupancy is queried LIVE via the getters (ptr-null? = empty) rather
;; than tracked separately here, so these stay correct even if something
;; outside glitter ever mutates the center box directly.
(defn- center-box-append-child!
  "Place `child` into the first EMPTY slot, in start -> center -> end
  order. A 4th+ child is silently dropped — same 'documented v1 scope
  limit' shape as :level-bar's :mode or :progress-bar's minimal display
  scope: GtkCenterBox only ever has three slots, full stop."
  [parent child]
  (cond
    (ptr-null? (g/gtk-center-box-get-start-widget parent))  (g/gtk-center-box-set-start-widget parent child)
    (ptr-null? (g/gtk-center-box-get-center-widget parent)) (g/gtk-center-box-set-center-widget parent child)
    (ptr-null? (g/gtk-center-box-get-end-widget parent))    (g/gtk-center-box-set-end-widget parent child)
    :else nil))

(defn- center-box-slot-setter
  "Which gtk_center_box_set_*_widget fn currently holds `child` in
  `parent`, or nil if `child` occupies no slot."
  [parent child]
  (cond
    (= child (g/gtk-center-box-get-start-widget parent))  g/gtk-center-box-set-start-widget
    (= child (g/gtk-center-box-get-center-widget parent)) g/gtk-center-box-set-center-widget
    (= child (g/gtk-center-box-get-end-widget parent))    g/gtk-center-box-set-end-widget
    :else nil))

(defn- center-box-remove-child! [parent child]
  (when-let [setter (center-box-slot-setter parent child)] (setter parent ffi/null)))

(defn- center-box-replace-child! [parent old-child new-child]
  (when-let [setter (center-box-slot-setter parent old-child)] (setter parent new-child)))

(defn- center-box-insert-after!
  "Insert `child` immediately after `sibling` in slot order
  (start < center < end) — the only ordering a 3-fixed-named-slot
  container can meaningfully express. `sibling` is nil (insert as the
  first child) or the tracked PREVIOUS sibling's own GTK widget pointer;
  when it's nil, or occupies no recognized slot, this behaves like a
  fresh append (first empty slot). REQUIRED, not merely a nice-to-have:
  found live that glitter.core's reconciler calls this — not
  replace-child! — even for a plain, non-keyed same-position TAG SWAP
  (e.g. a :label becoming a :button at center-box's 2nd child): it
  inserts the NEW node first, then removes the OLD one as a second,
  separate step. glitter.gtk's own IRender/insert-before updates its
  :children BOOKKEEPING unconditionally, regardless of whether the
  underlying GTK call actually did anything — so leaving this a no-op
  (this project's ORIGINAL v1 scope call, since reverted) desyncs that
  bookkeeping from live GTK state, and the reconciler's NEXT step (which
  removes 'whatever is tracked at position N') ends up removing the
  WRONG child. Caught only by a live re-render smoke, not by a plain
  append-only one — see gtk-widget-layer.md.

  KNOWN V1 GAP, found live and NOT fully fixable at this layer: when the
  target slot is ALREADY occupied (all 3 slots full, tag-swapping one of
  them), this overwrites it directly — gtk_center_box_set_*_widget
  unparents whatever was there before, and since nothing else in glitter
  holds a reference, GTK finalizes it immediately (unlike :box, which has
  genuine transient capacity for 'old and new both present at once').
  glitter.gtk's :children bookkeeping, however, is generic across every
  container kind and models this insert as ADDITIVE (temporarily 4
  tracked entries, matching what :box's own arbitrary-capacity list can
  really do) — for :center-box specifically that model is briefly WRONG,
  and the reconciler's subsequent per-child reconcile of the trailing
  sibling can index against the wrong (already-finalized) tracked entry,
  corrupting an unrelated third slot. Verified live: swapping center-box's
  MIDDLE child's tag while all 3 slots are full reliably corrupts the END
  slot (a GTK_IS_LABEL assertion failure reading it back). No same-slot
  tag swap when all 3 slots are already occupied — change props instead
  of tags, or nest a stable wrapper tag so the type change happens one
  level down where the general :box-shaped reconciliation already handles
  it correctly. See gtk-widget-layer.md for the full trace."
  [parent child sibling]
  (cond
    (ptr-null? sibling) (center-box-append-child! parent child)
    (= sibling (g/gtk-center-box-get-start-widget parent))  (g/gtk-center-box-set-center-widget parent child)
    (= sibling (g/gtk-center-box-get-center-widget parent)) (g/gtk-center-box-set-end-widget parent child)
    :else nil))

;; :paned helpers — two independently addressable NAMED slots (start/end),
;; the same "query occupancy live via the getters" shape as :center-box's
;; three, just simpler. Applies the SAME structural v1 gap from the start
;; (see paned-insert-after!'s docstring) rather than discovering it live a
;; second time.
(defn- paned-append-child!
  "Place `child` into the first EMPTY slot, start then end. A 3rd+ child
  is silently dropped — GtkPaned only ever has two slots, full stop."
  [parent child]
  (cond
    (ptr-null? (g/gtk-paned-get-start-child parent)) (g/gtk-paned-set-start-child parent child)
    (ptr-null? (g/gtk-paned-get-end-child parent))   (g/gtk-paned-set-end-child parent child)
    :else nil))

(defn- paned-slot-setter
  "Which gtk_paned_set_*_child fn currently holds `child` in `parent`, or
  nil if `child` occupies no slot."
  [parent child]
  (cond
    (= child (g/gtk-paned-get-start-child parent)) g/gtk-paned-set-start-child
    (= child (g/gtk-paned-get-end-child parent))   g/gtk-paned-set-end-child
    :else nil))

(defn- paned-remove-child! [parent child]
  (when-let [setter (paned-slot-setter parent child)] (setter parent ffi/null)))

(defn- paned-replace-child! [parent old-child new-child]
  (when-let [setter (paned-slot-setter parent old-child)] (setter parent new-child)))

(defn- paned-insert-after!
  "Insert `child` immediately after `sibling` in slot order (start < end)
  — the only ordering a 2-fixed-named-slot container can meaningfully
  express. `sibling` nil, or occupying no recognized slot, behaves like a
  fresh append (first empty slot).

  KNOWN V1 GAP, same ROOT CAUSE as :center-box's (see
  center-box-insert-after!'s docstring for the full trace) but a
  DIFFERENT, live-verified SYMPTOM — worth getting precise rather than
  assuming the two match, since GtkPaned only has 2 slots, not 3:

  - Swapping the LAST slot's tag while both are full (sibling = the
    OTHER, unchanged slot's widget) happens to land correctly: the new
    child overwrites the occupied end slot directly (gtk_paned_set_end_child
    unparents + GTK finalizes the old occupant immediately, same as
    :center-box), but unlike :center-box there is no THIRD slot after it
    for the reconciler's stale post-insert bookkeeping to corrupt into —
    verified live, no assertion failure, correct final state.
  - Swapping the FIRST slot's tag while both are full (sibling = nil,
    since it's the first child) fails differently: paned-append-child!'s
    'first empty slot' search finds NEITHER slot empty (both still
    occupied at insert time) and silently no-ops — the new child is
    created but never attached anywhere. The reconciler's later removal
    of the OLD first-slot child then leaves that slot genuinely EMPTY,
    not holding either widget. Verified live: both
    gtk_paned_get_start_child and reading back the swapped widget fail
    GTK_IS_BUTTON assertions afterward.

  Either way: no same-slot tag swap when both slots are already occupied
  — change props instead of tags, or nest a stable wrapper tag one level
  down so the type change happens where :box-shaped reconciliation
  already handles it correctly. Applied here from the start rather than
  re-discovered live, informed by the :center-box investigation — but
  the exact failure shape still needed live verification, not assumption."
  [parent child sibling]
  (cond
    (ptr-null? sibling) (paned-append-child! parent child)
    (= sibling (g/gtk-paned-get-start-child parent)) (g/gtk-paned-set-end-child parent child)
    :else nil))

;; :list-box helpers. gtk_list_box_append/insert auto-wrap a plain child in
;; a GtkListBoxRow (confirmed against gtk/gtklistbox.c's own bodies), so
;; APPENDING/INSERTING takes the child widget directly. gtk_list_box_remove
;; does NOT follow the same shape, despite its doc comment reading almost
;; identically to gtk_box_remove's ("the child to remove") — its actual
;; implementation requires the argument to already BE a GtkListBoxRow (or a
;; registered header widget), and warns "Tried to remove non-child" and
;; no-ops otherwise. Found live: the doc comment alone doesn't say this: the
;; behavior only surfaced by reading gtk_list_box_remove's C body directly,
;; and only became visible at all by actually running a re-render that
;; exercises remove/replace against live GTK (a plain append-only smoke
;; would never have hit this path). gtk_widget_get_parent recovers the
;; wrapping row from the child widget glitter tracks.
(defn- list-box-row-of
  "The GtkListBoxRow wrapping `child` in a :list-box, or nil if `child`
  isn't currently parented (already removed, or never inserted)."
  [child]
  (let [row (g/gtk-widget-get-parent child)]
    (when-not (ptr-null? row) row)))

;; gtk_list_box_remove has an incidental-signal gotcha found live: removing
;; the CURRENTLY SELECTED row fires a real, synchronous "row-selected"
;; emission with a NULL row argument (GTK's own deselection notice — see
;; gtk/gtklistbox.c's gtk_list_box_unselect_row_internal/select_row_internal
;; paths). Unlike glitter's OWN programmatic setters (set-switch-active! and
;; friends), this signal comes from a GTK-internal side effect of a
;; CONTAINER operation, not from something set-event-handler's suppressing
;; guard was written to intercept — and left unsuppressed it dispatches a
;; spurious deselection straight through to the app, which live-verified
;; can synchronously re-enter core/reconcile mid-reconcile (mount!'s watcher
;; runs inline when already on the GTK main thread — see AGENTS.md
;; convention #6) and trip a GTK_IS_WIDGET assertion on a widget the outer,
;; still-in-progress reconcile call hasn't finished processing yet.
;; suppressing conj/disj on `parent` (the list-box itself — "row-selected"'s
;; src-widget arg, per its real C signature) around the remove call silences
;; it, exactly mirroring the set-*-active! family's own suppress-then-mutate
;; shape, just applied to a container op instead of a value setter.
(defn- list-box-remove-child! [parent child]
  (when-let [row (list-box-row-of child)]
    (swap! suppressing conj parent)
    (g/gtk-list-box-remove parent row)
    (swap! suppressing disj parent)))

(defn- list-box-replace-child!
  "Swap `old-child` for `new-child` at the SAME position. Captures
  old-child's row + row index via gtk_widget_get_parent BEFORE removing it
  — removal invalidates both afterward, same 'capture before you mutate'
  concern replace-child!'s :box branch already has for its prev-sibling
  capture — then re-inserts new-child (auto-wrapped into a FRESH row) at
  that same, still-valid slot. Safe even though gtk_list_box_insert clamps
  out-of-range positions to 'append' (confirmed via its own doc comment):
  this always inserts into the exact slot just vacated, which by
  construction is never out of range. Suppresses on `parent` around the
  remove call for the same incidental-deselection-signal reason
  list-box-remove-child! does."
  [parent old-child new-child]
  (let [row (list-box-row-of old-child)
        idx (when row (g/gtk-list-box-row-get-index row))]
    (when row
      (swap! suppressing conj parent)
      (g/gtk-list-box-remove parent row)
      (swap! suppressing disj parent))
    (g/gtk-list-box-insert parent new-child (or idx -1))))

(defn- list-box-index-after
  "Convert `sibling` (nil, or the tracked PREVIOUS sibling's own GTK WIDGET
  pointer — not its wrapping row) into the list index gtk_list_box_insert
  wants: one past sibling's live ROW index, or 0 (front) if sibling is
  nil. Always re-reads sibling's row index at CALL time, never caches it
  — reorder-child! below relies on that freshness to stay correct after
  it removes `child`'s own old row first."
  [sibling]
  (if (ptr-null? sibling)
    0
    (inc (g/gtk-list-box-row-get-index (g/gtk-widget-get-parent sibling)))))

(defn- list-box-insert-after! [parent child sibling]
  (g/gtk-list-box-insert parent child (list-box-index-after sibling)))

(defn- list-box-reorder-child!
  "Move an ALREADY-parented `child` to sit immediately after `sibling`.
  Removes child's OLD row FIRST, then computes the target index — doing
  it in this order (not the reverse) means list-box-index-after re-reads
  sibling's row index AFTER child's removal has potentially shifted it,
  so the target is always correct post-removal, never stale. Suppresses on
  `parent` around the remove call — same incidental
  deselect-fires-row-selected reason list-box-remove-child! does."
  [parent child sibling]
  (when-let [row (list-box-row-of child)]
    (swap! suppressing conj parent)
    (g/gtk-list-box-remove parent row)
    (swap! suppressing disj parent))
  (list-box-insert-after! parent child sibling))

(defn append-child!
  "Add `child` to the end of `parent`. Dispatches on the parent's container kind."
  [parent-tag parent child]
  (case (container-kind parent-tag)
    :box        (g/gtk-box-append parent child)
    :window     (g/gtk-window-set-child parent child)
    :frame      (g/gtk-frame-set-child parent child)
    :scrolled   (g/gtk-scrolled-window-set-child parent child)
    :revealer   (g/gtk-revealer-set-child parent child)
    :expander   (g/gtk-expander-set-child parent child)
    :center-box (center-box-append-child! parent child)
    :paned      (paned-append-child! parent child)
    :list-box   (g/gtk-list-box-append parent child)
    nil))

(defn remove-child!
  "Remove `child` from `parent`."
  [parent-tag parent child]
  (case (container-kind parent-tag)
    :box        (g/gtk-box-remove parent child)
    :window     (g/gtk-window-set-child parent ffi/null)
    :frame      (g/gtk-frame-set-child parent ffi/null)
    :scrolled   (g/gtk-scrolled-window-set-child parent ffi/null)
    :revealer   (g/gtk-revealer-set-child parent ffi/null)
    :expander   (g/gtk-expander-set-child parent ffi/null)
    :center-box (center-box-remove-child! parent child)
    :paned      (paned-remove-child! parent child)
    :list-box   (list-box-remove-child! parent child)
    nil))

(defn replace-child!
  "Replace `old-child` with `new-child` at the same position in `parent`.
  For :box, gtk_box_append always inserts at the END — found live-verified
  during the final whole-branch review that replacing a non-final child
  silently relocated it there, desyncing every consumer's positional
  tracking. Capture old-child's current previous sibling BEFORE removing
  it (removal loses that information), then insert new-child at that same
  anchor via gtk_box_insert_child_after."
  [parent-tag parent old-child new-child]
  (case (container-kind parent-tag)
    :box        (let [prev (g/gtk-widget-get-prev-sibling old-child)
                      prev (when-not (or (nil? prev) (zero? prev)) prev)]
                  (g/gtk-box-remove parent old-child)
                  (g/gtk-box-insert-child-after parent new-child (or prev ffi/null)))
    :window     (g/gtk-window-set-child parent new-child)
    :frame      (g/gtk-frame-set-child parent new-child)
    :scrolled   (g/gtk-scrolled-window-set-child parent new-child)
    :revealer   (g/gtk-revealer-set-child parent new-child)
    :expander   (g/gtk-expander-set-child parent new-child)
    :center-box (center-box-replace-child! parent old-child new-child)
    :paned      (paned-replace-child! parent old-child new-child)
    :list-box   (list-box-replace-child! parent old-child new-child)
    nil))

(defn reorder-child!
  "Move `child` to sit immediately after `sibling` (nil = move to first position)
  within `parent`. GtkBox and GtkListBox both support real positional
  reordering (list-box via list-box-reorder-child! above, computing a
  fresh live index rather than the sibling-pointer approach GtkBox's own
  API uses). The single-child containers (window/frame/scrolled/revealer/
  expander) no-op because they only ever have one child. :center-box/
  :paned no-op too, but as a genuinely STRUCTURAL limit, not an avoided
  one: their slots are fixed NAMED identities (start/center/end, or
  start/end), not positions — 'move this widget to sit after that one'
  has no well-defined meaning when every slot already has a name. Used by
  the keyed reconciler to fix widget order after reuse/create when
  survivors were reordered or a new item must precede an existing one."
  [parent-tag parent child sibling]
  (case (container-kind parent-tag)
    :box      (g/gtk-box-reorder-child-after parent child (or sibling ffi/null))
    :list-box (list-box-reorder-child! parent child sibling)
    nil))

(defn insert-child-after!
  "Insert `child` into `parent` immediately after `sibling` (nil = insert as the
  first child). GtkBox, GtkCenterBox (via center-box-insert-after! above),
  GtkPaned (via paned-insert-after! above), and GtkListBox (via
  list-box-insert-after! above) all support this; the single-child
  containers (window/frame/scrolled/revealer/expander) no-op because they
  only ever have one child. This is NOT an optional nicety for
  :center-box/:paned/:list-box — found live that glitter.core's
  reconciler calls THIS fn (not replace-child!) even for a plain,
  non-keyed, same-position TAG SWAP (e.g. a :label becoming a :button at
  some fixed child index): it inserts the new node first, then removes
  the old one as a separate step. Leaving this a no-op for a container
  (this project's ORIGINAL v1 scope call for :center-box/:list-box, since
  reverted) desyncs glitter.gtk's own :children bookkeeping — updated
  unconditionally by IRender/insert-before regardless of whether the
  underlying GTK call did anything — from live GTK state, and the
  reconciler's subsequent removal step (which removes 'whatever is
  tracked at position N') ends up removing the WRONG child. Caught only
  by a live re-render smoke, not by a plain append-only one — see
  gtk-widget-layer.md. New relative to the glimmer original: glimmer's
  own reconciler only ever appends (reorder-child! moves an EXISTING
  child), but glitter.gtk's IRender/insert-before needs a genuine
  positional insertion of a NEW child (glimmer never needed this because
  Reagent-style positional reconciliation never inserts into the middle
  of a live child list)."
  [parent-tag parent child sibling]
  (case (container-kind parent-tag)
    :box        (g/gtk-box-insert-child-after parent child (or sibling ffi/null))
    :center-box (center-box-insert-after! parent child sibling)
    :paned      (paned-insert-after! parent child sibling)
    :list-box   (list-box-insert-after! parent child sibling)
    nil))
