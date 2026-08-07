(ns glitter.test-renderer
  "A fake, in-memory IRender/IMemory implementation for headless reconciler
  tests — no live GTK display required. Conceptually mirrors replicant's
  mutation_log.cljc (log of every protocol call, plus a snapshot tree), but
  implemented via `reify` instead of `:extend-via-metadata`, which does not
  dispatch correctly under Jolt (verified during this project's design phase:
  requiring replicant.mutation-log and calling its renderer throws \"No
  method create-element in replicant.protocols/IRender\").

  Ships in src/, not test/, so applications built on glitter can reuse this
  renderer for their own tests, not just glitter's internal suite."
  (:require [glitter.protocols :as proto]))

(defn renderer
  "A fresh fake renderer. Every element is a plain atom holding
  {:tag-name <string> :children [<child atom> ...]} (or {:text <string>} for
  text nodes); `log` accumulates every protocol call as a vector, e.g.
  [:create-element \"button\"], [:append-child \"button\" :to \"box\"]."
  []
  (let [log (atom [])
        memory (atom {})
        impl (reify
               proto/IRender
               (attached? [_ _el] true)

               (create-text-node [_ text]
                 (swap! log conj [:create-text-node text])
                 (atom {:text text}))

               (create-element [_ tag-name _options]
                 (swap! log conj [:create-element tag-name])
                 (atom {:tag-name tag-name
                        :children []}))

               (set-style [_ el k v] (swap! log conj [:set-style (:tag-name @el) k v]) nil)
               (remove-style [_ el k] (swap! log conj [:remove-style (:tag-name @el) k]) nil)
               (add-class [_ el cn] (swap! log conj [:add-class (:tag-name @el) cn]) nil)
               (remove-class [_ el cn] (swap! log conj [:remove-class (:tag-name @el) cn]) nil)

               (set-attribute [_ el a v _opt]
                 (swap! log conj [:set-attribute (:tag-name @el) a v])
                 (swap! el assoc a v)
                 nil)
               (remove-attribute [_ el a]
                 (swap! log conj [:remove-attribute (:tag-name @el) a])
                 (swap! el dissoc a)
                 nil)

               (set-event-handler [_ el event _handler _opt]
                 (swap! log conj [:set-event-handler (:tag-name @el) event])
                 nil)
               (remove-event-handler [_ el event _opt]
                 (swap! log conj [:remove-event-handler (:tag-name @el) event])
                 nil)

               (insert-before [_ el child-node reference-node]
                 (swap! log conj [:insert-before (or (:tag-name @child-node) (:text @child-node))
                                  (or (:tag-name @reference-node) (:text @reference-node))
                                  :in (:tag-name @el)])
                 ;; Remove child-node from wherever it currently sits first
                 ;; (a no-op if it wasn't tracked yet — the fresh-insert
                 ;; case), then re-splice immediately before reference-node.
                 ;; Found live-verified during the final whole-branch
                 ;; review: without this, a keyed move left a stale
                 ;; duplicate entry in :children instead of relocating it —
                 ;; the same class of bug as glitter.gtk's insert-before had
                 ;; before Task 10's review fixed it there. Mirrors that fix.
                 (swap! el update :children
                        (fn [cs]
                          (let [without (vec (remove #(= % child-node) cs))
                                idx (.indexOf without reference-node)]
                            (into (conj (subvec without 0 idx) child-node) (subvec without idx)))))
                 nil)

               (append-child [_ el child-node]
                 (swap! log conj [:append-child (or (:tag-name @child-node) (:text @child-node))
                                  :to (:tag-name @el)])
                 (swap! el update :children conj child-node)
                 nil)

               (remove-child [_ el child-node]
                 (swap! log conj [:remove-child (or (:tag-name @child-node) (:text @child-node))
                                  :from (:tag-name @el)])
                 (swap! el update :children (fn [cs] (into [] (remove #(= % child-node) cs))))
                 nil)

               (on-transition-end [_ _el f] (f) nil)

               (replace-child [_ el insert-child replace-child]
                 (swap! log conj [:replace-child (:tag-name @el)])
                 (swap! el update :children (fn [cs] (mapv #(if (= % replace-child) insert-child %) cs)))
                 nil)

               (remove-all-children [_ el]
                 (swap! log conj [:remove-all-children :from (:tag-name @el)])
                 (swap! el assoc :children [])
                 nil)

               (get-child [_ el idx] (nth (:children @el) idx nil))
               (next-frame [_ f] (f) nil)

               proto/IMemory
               (remember [_ node data] (swap! memory assoc node data) nil)
               (recall [_ node] (get @memory node)))]
    (with-meta impl {:log log
                     :memory memory})))

(defn events
  "The accumulated call log from a renderer built by `renderer`, as a plain
  vector of event tuples (already in the readable [:event-name ...] shape —
  no separate `summarize` post-processing step needed, unlike replicant's
  test-helper, because entries are constructed pre-formatted above)."
  [r]
  @(:log (meta r)))

(defn reset-events!
  "Clear renderer instance `r`'s accumulated log in place — useful between
  two sequential `core/reconcile` calls against the same renderer/el when a
  test wants to inspect only the second call's mutations (e.g. isolating a
  reorder's effects from the preceding mount's)."
  [r]
  (reset! (:log (meta r)) []))
