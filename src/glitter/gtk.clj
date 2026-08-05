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
    (remove-attribute [_ el a]
      (w/apply-props! (:tag @el) (ptr el) {(keyword a) nil})
      nil)

    (set-event-handler [_ el event handler _opt]
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
      (let [cs (:children @el)
            idx (.indexOf cs reference-node)
            prev-sibling (when (pos? idx) (ptr (nth cs (dec idx))))]
        (w/insert-child-after! (:tag @el) (ptr el) (ptr child-node) prev-sibling))
      (swap! el update :children
             (fn [cs] (let [idx (.indexOf cs reference-node)]
                        (into (conj (subvec cs 0 idx) child-node) (subvec cs idx)))))
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
    (add-watch state-atom ::render (fn [_ _ _ state] (render! state)))
    nil))
