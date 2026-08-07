(ns glitter.gtk
  "The IRender/IMemory implementation of glitter.core's backend seam,
  targeting real GTK4 widgets via glitter.widget/glitter.ffi, plus the
  state-atom + action-dispatch mount/render wiring (Replicant's
  state-atom.md pattern, adapted to a GTK root window instead of
  document.body).

  IRender's `el`/child-node values are small tracking atoms — not raw GTK
  widget pointers — because GTK4 has no O(1) indexed-child-lookup API
  (gtk_widget_get_first_child/get_next_sibling is an O(n) walk). Each atom
  holds {:tag <hiccup tag keyword> :widget <GTK widget pointer> :children
  [<child atom> ...] :handlers {<event keyword> {:id <signal connection id>
  :cb <retained foreign-callable>}}} — :cb alongside :id (added during the
  final whole-branch review) so disconnect can also release-callable! the
  callable, not just disconnect the signal — mirroring glimmer.core's own
  instance-tree pattern. IMemory keys off the el atom itself (already a
  stable Clojure identity), not the raw pointer."
  (:require [glitter.alias :as alias]
            [glitter.app :as app]
            [glitter.core :as core]
            [glitter.ffi :as g]
            [glitter.protocols :as proto]
            [glitter.widget :as w]
            [jolt.ffi]))

(defn- ptr [el] (:widget @el))

(defonce ^:private memory (atom {}))

(defn renderer
  "A fresh IRender/IMemory implementation over glitter.widget. One instance
  is enough for the life of an app; `mount!` builds one per mounted window."
  []
  (reify proto/IRender
    (create-element [_ tag-name options]
      (let [tag (keyword tag-name)
            widget (w/create! tag (or options {}))]
        (atom {:tag tag :widget widget :children [] :handlers {}})))

    (create-text-node [_ text]
      ;; GTK has no text-node primitive; a bare string/number hiccup child
      ;; becomes its own :label widget (mirrors glimmer's leaf convention —
      ;; see the design spec's "Text/leaf content has no DOM equivalent").
      (let [widget (w/create! :label {:label text})]
        (atom {:tag :label :widget widget :children [] :handlers {} :text text})))

    (attached? [_ _el] true)

    ;; :class wired to real GTK CSS classes (gtk_widget_add/remove_css_class)
    ;; — glitter.core's get-classes already normalizes every :class
    ;; representation (keyword, symbol, string, or a collection of those) to
    ;; a plain string before this is ever called, so cn passes straight
    ;; through with no marshalling needed. GTK4 ships built-in classes
    ;; ("suggested-action", "destructive-action", "flat", "pill", ...) that
    ;; work with zero app-provided CSS.
    ;;
    ;; :style still has no GTK equivalent — unlike DOM's element.style.color
    ;; = ... (an inline per-element property), GTK4 styling is exclusively
    ;; class-based, matched against CSS rules loaded through a
    ;; GtkCssProvider. Synthesizing an inline :style prop into a real GTK
    ;; effect would mean generating a unique class name + CSS rule text per
    ;; widget and loading it through a provider — real design work, out of
    ;; scope here. Hiccup :style props are still accepted (diffed, calling
    ;; these) but remain inert.
    (set-style [_ _el _k _v] nil)
    (remove-style [_ _el _k] nil)
    (add-class [_ el cn] (g/gtk-widget-add-css-class (ptr el) cn) nil)
    (remove-class [_ el cn] (g/gtk-widget-remove-css-class (ptr el) cn) nil)

    (set-attribute [_ el a v _opt]
      (w/apply-props! (:tag @el) (ptr el) {(keyword a) v})
      nil)
    ;; KNOWN V1 LIMITATION, found live-verified during review: this is a
    ;; no-op. w/apply-props! (glitter.widget) filters out any key whose
    ;; value is nil before it ever reaches a widget's :apply closure —
    ;; so {(keyword a) nil} always reduces to {} and the underlying GTK
    ;; property is left completely untouched. Confirmed against real
    ;; GTK state: set a checkbutton's :active true, then "remove" it —
    ;; gtk_check_button_get_active still returns 1. GTK has no generic
    ;; "unset a property" API the way DOM/CSS does (removeAttribute has
    ;; an obvious browser meaning; there's no GTK equivalent), so
    ;; there's no clean mechanical fix at this layer — a real fix would
    ;; need per-widget-type default values designed into glitter.widget's
    ;; :apply closures, out of scope for v1 (matches this project's
    ;; existing "no CSS wiring" / "no animations" v1 boundaries —
    ;; human-approved: document, don't fix). Setting an attribute to a
    ;; NEW value always works correctly; removing it entirely so it
    ;; reverts to a type default does not.
    (remove-attribute [_ el a]
      (w/apply-props! (:tag @el) (ptr el) {(keyword a) nil})
      nil)

    (set-event-handler [_ el event handler _opt]
      ;; Disconnect any existing connection for this event FIRST (handler
      ;; DATA can change between renders without :glitter.event/* OPTIONS
      ;; changing — glitter.core's update-event-listeners calls
      ;; set-event-handler on the former but only remove-event-handler on
      ;; the latter). Release its callable too — found live-verified
      ;; during the final whole-branch review that disconnecting the
      ;; signal alone (already fixed) still left the foreign-callable
      ;; pinned in glitter.widget's retain set forever, an unbounded leak
      ;; for any handler whose data changes across renders.
      (when-let [{:keys [id cb]} (get-in @el [:handlers event])]
        (g/g-signal-handler-disconnect (ptr el) id)
        (w/release-callable! cb)
        (swap! el update :handlers dissoc event))
      (when-let [signal (w/signal-name (keyword (str "on-" (name event))))]
        (let [value-fn (w/signal-value-fn (:tag @el) signal)
              ;; Dispatches through `handler`, shared by every callable
              ;; shape below regardless of how many raw args GTK actually
              ;; passes — every value-bearing signal's value-fn re-reads
              ;; the widget's own property AFTER the signal fires
              ;; (verified live, per-signal, that the property already
              ;; reflects the new value by then — see e.g. "state-set"'s
              ;; value-fn in glitter.widget), so no signal needs its own
              ;; raw argument threaded through here. value-fn is looked up
              ;; by [(:tag @el) signal], not bare signal name — GTK signal
              ;; names aren't unique per meaning across widget types (e.g.
              ;; :scale and :spin-button both emit "value-changed" but read
              ;; back through different getters); see glitter.widget's
              ;; signal-value docstring.
              ;;
              ;; Skip emissions triggered by our OWN programmatic setters
              ;; (set-entry-text!/set-checkbutton-active!/etc.) — found
              ;; live-verified during the final whole-branch review that
              ;; connecting directly (bypassing glitter.widget/
              ;; connect-signals!, which we can't use since it doesn't
              ;; expose the connection id real disconnect needs) meant
              ;; this guard was never consulted, so a purely programmatic
              ;; value change fired a real dispatch the user never
              ;; triggered.
              dispatch! (fn [src-widget]
                          (when-not (w/suppressing? src-widget)
                            (handler (cond-> {:glitter/node el :glitter/gtk-widget src-widget}
                                       value-fn (assoc :glitter/value (value-fn src-widget))))))
              ;; Almost every GTK signal glitter connects is
              ;; void(widget, user_data), covered by the default branch.
              ;; GtkSwitch's "state-set" doesn't fit: its real C signature
              ;; is gboolean(GtkSwitch*, gboolean, gpointer) — 3 args,
              ;; non-void return (confirmed against gtk/gtkswitch.c's
              ;; g_signal_new call, not assumed) — so it needs its own
              ;; foreign-callable call with a literal 3-arg fn and literal
              ;; [:pointer :int :pointer]/:int argtypes/rettype.
              ;; GtkListBox's "row-selected"/"row-activated" don't fit
              ;; either: their real C signature is
              ;; void(GtkListBox*, GtkListBoxRow*, gpointer) — 3 args, VOID
              ;; return this time (a THIRD, distinct shape from
              ;; "state-set"'s) — confirmed against gtk/gtklistbox.c's
              ;; g_signal_new calls. The row argument itself is ignored
              ;; (`_row` below): the value-fn re-reads the box's own
              ;; get_selected_row after the signal fires, same pattern as
              ;; every other value-bearing signal here. GObject property-
              ;; notify signals ("notify::expanded" for :expander,
              ;; "notify::position" for :paned — neither GtkExpander nor
              ;; GtkPaned has a dedicated interaction signal of its own)
              ;; share this EXACT shape too — void(GObject*, GParamSpec*,
              ;; gpointer) is the standard "notify" signature, and a
              ;; GParamSpec* is just another :pointer under FFI. So does
              ;; GtkFlowBox's "child-activated" — void(GtkFlowBox*,
              ;; GtkFlowBoxChild*, gpointer), confirmed against
              ;; gtk/gtkflowbox.c's g_signal_new call. All four reuse this
              ;; one branch for free, no new literal call site needed.
              ;;
              ;; GtkScaleButton's "value-changed" is a FIFTH shape:
              ;; void(GtkScaleButton*, double, gpointer) — 3 args like
              ;; "state-set"'s, but a :double in the middle slot instead
              ;; of :pointer/:int (confirmed against gtk/gtkscalebutton.c's
              ;; g_signal_new call). It ALSO happens to share the exact
              ;; GTK signal NAME ("value-changed") :scale/:spin-button
              ;; already use with the DEFAULT 2-arg-void shape — the
              ;; FIRST case here where signal name alone can't determine
              ;; callable shape; this branch checks the widget's OWN tag
              ;; too, not just `signal`. The raw double argument is
              ;; ignored (`_value` below): verified against
              ;; gtk/gtkscalebutton.c's cb_scale_value_changed that
              ;; gtk_scale_button_get_value already reflects the new
              ;; value by the time the button's own "value-changed"
              ;; fires (it reads the same GtkAdjustment the internal
              ;; slider already updated), so the usual re-read-via-getter
              ;; value-fn still applies here.
              ;;
              ;; GtkNotebook's "switch-page" is a SIXTH shape —
              ;; void(GtkNotebook*, GtkWidget* page, guint page_num,
              ;; gpointer), confirmed against gtk/gtknotebook.c's
              ;; g_signal_new call — 4 args this time. Unlike every signal
              ;; above, it CANNOT reuse the shared dispatch!/value-fn
              ;; path: gtk_notebook_switch_page (the fn that emits this
              ;; signal) only READS notebook->cur_page; the actual
              ;; cur_page = page assignment happens in
              ;; gtk_notebook_real_switch_page — the signal's OWN DEFAULT
              ;; CLASS HANDLER, registered G_SIGNAL_RUN_LAST, which runs
              ;; AFTER user-connected handlers like this one. Re-reading
              ;; gtk_notebook_get_current_page() the way every other
              ;; signal here re-reads its property would return the
              ;; STALE previous page — confirmed by reading the C source,
              ;; not assumed from the pattern holding everywhere else.
              ;; So this branch reads `page-num` directly from its OWN
              ;; raw signal argument and builds the dispatched event map
              ;; inline, bypassing `value-fn`/`dispatch!` entirely — the
              ;; first (and so far only) signal here that needs this.
              ;;
              ;; This can't be collapsed into one data-driven call: jolt's
              ;; foreign-callable/__ccallable is a compile-time special
              ;; form — verified live (twice, isolated from this codebase)
              ;; that passing argtypes/rettype as a let-bound local (even
              ;; holding the exact literal value) throws "Don't know how
              ;; to create ISeq from: clojure.lang.Symbol" at compile
              ;; time. Every distinct callable shape needs its own literal
              ;; call site; add a new `signal` branch here for the next
              ;; one, don't try to generalize further.
              cb (cond
                   (= signal "state-set")
                   (jolt.ffi/foreign-callable
                    (fn [src-widget _state _data] (dispatch! src-widget) 0)
                    [:pointer :int :pointer] :int :collect-safe)

                   (#{"row-selected" "row-activated" "notify::expanded" "notify::position" "child-activated"} signal)
                   (jolt.ffi/foreign-callable
                    (fn [src-widget _pspec-or-row _data] (dispatch! src-widget))
                    [:pointer :pointer :pointer] :void :collect-safe)

                   (and (= signal "value-changed") (= (:tag @el) :scale-button))
                   (jolt.ffi/foreign-callable
                    (fn [src-widget _value _data] (dispatch! src-widget))
                    [:pointer :double :pointer] :void :collect-safe)

                   (= signal "switch-page")
                   (jolt.ffi/foreign-callable
                    (fn [src-widget _page page-num _data]
                      (when-not (w/suppressing? src-widget)
                        (handler {:glitter/node el :glitter/gtk-widget src-widget :glitter/value page-num})))
                    [:pointer :pointer :uint :pointer] :void :collect-safe)

                   :else
                   (jolt.ffi/foreign-callable
                    (fn [src-widget _data] (dispatch! src-widget))
                    [:pointer :pointer] :void :collect-safe))
              id (g/g-signal-connect-data (ptr el) signal cb jolt.ffi/null jolt.ffi/null g/CONNECT-DEFAULT)]
          (w/retain-callable! cb)
          (swap! el assoc-in [:handlers event] {:id id :cb cb})))
      nil)

    (remove-event-handler [_ el event _opt]
      (when-let [{:keys [id cb]} (get-in @el [:handlers event])]
        (g/g-signal-handler-disconnect (ptr el) id)
        (w/release-callable! cb)
        (swap! el update :handlers dissoc event))
      nil)

    (insert-before [_ el child-node reference-node]
      ;; insert-before is called for TWO different cases, mirroring DOM's
      ;; own insertBefore auto-move semantics: (a) a genuinely new,
      ;; never-yet-parented child, and (b) repositioning a child that's
      ;; ALREADY a child of el (keyed reconciliation moving an existing
      ;; row). GTK does not unify these the way DOM does — found live-
      ;; verified during review: gtk_box_insert_child_after asserts its
      ;; child arg is UNPARENTED (gtk_widget_get_parent(child) == NULL)
      ;; and throws a GTK-CRITICAL + silently no-ops when called on an
      ;; already-parented child, which is exactly what a keyed reorder
      ;; does. GTK's real API for repositioning an EXISTING child is
      ;; gtk_box_reorder_child_after (glimmer's own reorder-child!,
      ;; already proven correct for this exact case) — so branch on
      ;; whether child-node is already tracked in el's :children.
      (let [cs (:children @el)
            idx (.indexOf cs reference-node)
            prev-sibling (when (pos? idx) (ptr (nth cs (dec idx))))]
        (if (some #(= % child-node) cs)
          (w/reorder-child! (:tag @el) (ptr el) (ptr child-node) prev-sibling)
          (w/insert-child-after! (:tag @el) (ptr el) (ptr child-node) prev-sibling)))
      ;; Bookkeeping: remove child-node from wherever it currently sits
      ;; (a no-op if it wasn't tracked yet — the fresh-insert case), then
      ;; re-splice it immediately before reference-node. This single
      ;; formula is correct for both cases: GTK repositioning one child
      ;; never changes any OTHER child's tree position, so reference-
      ;; node's neighbor (prev-sibling, computed above from the
      ;; PRE-removal cs) stays a valid physical anchor regardless of
      ;; where child-node itself used to be.
      (swap! el update :children
             (fn [cs]
               (let [without (vec (remove #(= % child-node) cs))
                     idx (.indexOf without reference-node)]
                 (into (conj (subvec without 0 idx) child-node) (subvec without idx)))))
      nil)

    (append-child [_ el child-node]
      (w/append-child! (:tag @el) (ptr el) (ptr child-node))
      (swap! el update :children conj child-node)
      nil)

    (remove-child [_ el child-node]
      (w/remove-child! (:tag @el) (ptr el) (ptr child-node))
      (swap! el update :children (fn [cs] (into [] (remove #(= % child-node) cs))))
      nil)

    ;; No animation in v1 — fire the callback immediately, synchronously.
    (on-transition-end [_ _el f] (f) nil)

    (replace-child [_ el insert-child replace-child]
      (w/replace-child! (:tag @el) (ptr el) (ptr replace-child) (ptr insert-child))
      (swap! el update :children
             (fn [cs] (mapv #(if (= % replace-child) insert-child %) cs)))
      nil)

    (remove-all-children [_ el]
      (doseq [c (:children @el)] (w/remove-child! (:tag @el) (ptr el) (ptr c)))
      (swap! el assoc :children [])
      nil)

    (get-child [_ el idx] (nth (:children @el) idx nil))

    ;; Currently unexercised in v1: glitter.core only calls next-frame to
    ;; defer hook-calling when :replicant/mounting attrs were applied, and no
    ;; v1 hiccup sets them (see spec's V1 scope boundaries) — still wired
    ;; correctly via glitter.app/on-gui for forward-compatibility.
    (next-frame [_ f] (app/on-gui f) nil)

    ;; IMemory, folded into the same reify form as IRender rather than a
    ;; separate composed implementation. A single reify form can implement
    ;; multiple protocols directly — this is the correction for a bug found
    ;; during Task 5's review: an earlier draft tried to compose IRender and
    ;; IMemory via with-meta (the exact :extend-via-metadata mechanism
    ;; already proven broken under Jolt during brainstorming) by merging
    ;; `(meta r)` from a reify instance — which doesn't even work on its own
    ;; terms, since a plain `reify` result carries no method table in its
    ;; metadata at all (that's a with-meta-wrapped-map idiom, not how reify
    ;; dispatch works). Keyed off the el atom itself (already a stable
    ;; Clojure identity) rather than the raw GTK pointer, sidestepping any
    ;; question of whether Jolt FFI pointers hash/compare correctly as map
    ;; keys.
    proto/IMemory
    (remember [_ node data] (swap! memory assoc node data) nil)
    (recall [_ node] (get @memory node))))

(defn mount!
  "Mount `view` (a `state -> hiccup` pure function) into `window` (a GTK
  window widget pointer from glitter.app/run's on-activate callback), watching
  `state-atom` and re-reconciling on every change — Replicant's state-atom
  pattern. Registered aliases (glitter.alias/get-registered-aliases) are
  merged into every reconcile call automatically."
  [window view state-atom]
  (let [r (renderer)
        root-el (atom {:tag :window :widget window :children [] :handlers {}})
        vdom (atom nil)
        render! (fn [state]
                  (reset! vdom (:vdom (core/reconcile r root-el (view state) @vdom
                                                      {:aliases (alias/get-registered-aliases)}))))]
    (render! @state-atom)
    (add-watch state-atom ::render (fn [_ _ _ state] (app/on-gui (fn [] (render! state)))))
    nil))
