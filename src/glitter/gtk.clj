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
  [<child atom> ...] :handlers {<event keyword> <signal connection id>}},
  mirroring glimmer.core's own instance-tree pattern. IMemory keys off the
  el atom itself (already a stable Clojure identity), not the raw pointer."
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

    ;; No GTK CSS wiring in v1 — glimmer.widget has no :style/:class vocabulary
    ;; to fork from (see spec's V1 scope boundaries). Hiccup :style/:class
    ;; props are accepted (diffed, calling these) but currently inert.
    (set-style [_ _el _k _v] nil)
    (remove-style [_ _el _k] nil)
    (add-class [_ _el _cn] nil)
    (remove-class [_ _el _cn] nil)

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
      ;; Disconnect any existing connection for this event FIRST — found
      ;; live-verified during review: glitter.core's update-event-listeners
      ;; calls set-event-handler whenever the handler VALUE changes between
      ;; renders, but only calls remove-event-handler when the
      ;; :glitter.event/* OPTIONS change — these are different conditions.
      ;; Without this guard, a handler whose data changes (but whose opts
      ;; don't) leaves the old GTK signal connection orphaned (still live,
      ;; still fires) while only the new id is tracked — an unbounded
      ;; per-render handler leak.
      (when-let [id (get-in @el [:handlers event])]
        (g/g-signal-handler-disconnect (ptr el) id)
        (swap! el update :handlers dissoc event))
      (when-let [signal (w/signal-name (keyword (str "on-" (name event))))]
        (let [cb (jolt.ffi/foreign-callable
                  (fn [src-widget _data] (handler {:glitter/node el :glitter/gtk-widget src-widget}))
                  [:pointer :pointer] :void :collect-safe)
              id (g/g-signal-connect-data (ptr el) signal cb jolt.ffi/null jolt.ffi/null g/CONNECT-DEFAULT)]
          (w/retain-callable! cb)
          (swap! el assoc-in [:handlers event] id)))
      nil)

    (remove-event-handler [_ el event _opt]
      (when-let [id (get-in @el [:handlers event])]
        (g/g-signal-handler-disconnect (ptr el) id)
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
