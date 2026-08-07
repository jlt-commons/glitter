(ns glitter.list-box-reorder-smoke
  "Automated smoke against the LIVE GTK tree pinning a real, previously-
  undiscovered bug found while prototyping examples/glitter/crud.clj:
  list-box-reorder-child!/flow-box-reorder-child! reused a DANGLING
  pointer.

  Root cause, verified live and by reading GTK source directly (not
  assumed): gtk_list_box_remove/gtk_flow_box_remove dispose the now-
  unreferenced wrapping GtkListBoxRow/GtkFlowBoxChild immediately, and
  BOTH of those wrappers' own dispose handlers unparent their OWN child
  (confirmed via gtk_list_box_row_dispose's and
  gtk_flow_box_child_dispose's actual C bodies: `g_clear_pointer
  (&priv->child, gtk_widget_unparent)`) — with nothing else in glitter
  holding an independent reference, that FINALIZES the child too.
  reorder-child! removes the OLD row/wrapper then tries to reuse the SAME
  child pointer for the reinsert — a use-after-dispose, surfacing as
  `gtk_list_box_insert: assertion 'GTK_IS_WIDGET (child)' failed` plus
  cascading assertion failures on whatever else still held that pointer.

  docs/guide/limitations.md had already flagged this exact path (a
  same-key REPOSITION, not a tag-swap or plain append/remove) as
  'implemented but not previously live-verified' — this smoke is that
  verification. Fixed via a g-object-ref-sink/g-object-unref bracket
  around the remove-then-reinsert in both functions: g-object-ref-sink
  takes an extra reference BEFORE the row/wrapper (and therefore its
  child) would otherwise be disposed, keeping the child alive across the
  gap; g-object-unref releases that extra reference once the child is
  safely re-parented into its NEW row/wrapper.

  Mirrors examples/glitter/keyed.clj's own ground-truth discipline: reads
  the REORDERED sequence back via gtk_widget_get_first_child/
  get_next_sibling on the live GTK tree, not glitter's own :children
  bookkeeping — proving the real gtk_list_box_insert/gtk_flow_box_insert
  calls landed correctly, not just that the algorithm's decisions were
  correct in the abstract. Unlike keyed.clj (which reorders a plain
  :box), this drives the SAME reorder through :list-box AND :flow-box —
  the two container kinds whose remove path disposes a WRAPPING widget
  around the reordered child, which is exactly what :box's plain
  gtk_widget_unparent (no wrapper) never had to contend with."
  (:require [glitter.app :as app]
            [glitter.core :as core]
            [glitter.ffi :as g]
            [glitter.gtk :as gtk]))

(defonce state (atom {:list-order ["a" "b" "c"] :flow-order ["x" "y" "z"]}))

(def list-labels {"a" "List A" "b" "List B" "c" "List C"})
(def flow-labels {"x" "Flow X" "y" "Flow Y" "z" "Flow Z"})

(defn view [{:keys [list-order flow-order]}]
  [:vbox {:spacing 8}
   (into [:list-box {}]
         (for [k list-order] [:label {:glitter/key k :label (list-labels k)}]))
   (into [:flow-box {}]
         (for [k flow-order] [:label {:glitter/key k :label (flow-labels k)}]))])

(core/set-dispatch! (fn [_ _] nil))

(defn- child-labels
  "Walk `container`'s LIVE GTK children (each auto-wrapped in a row/
  GtkFlowBoxChild) and read back the wrapped label's actual text — the
  ground truth, independent of any Clojure-side bookkeeping."
  [container]
  (loop [child (g/gtk-widget-get-first-child container) acc []]
    (if (or (nil? child) (zero? child))
      acc
      (recur (g/gtk-widget-get-next-sibling child)
             (conj acc (g/gtk-label-get-text (g/gtk-widget-get-first-child child)))))))

(defn -main [& _]
  (let [results (atom {})]
    (app/run
     (fn [window]
       (gtk/mount! window view state)
       ;; :window is a single-child container — the mounted [:vbox ...]
       ;; is window's ONE child; :list-box and :flow-box are its two
       ;; children in sibling order.
       (let [box (g/gtk-widget-get-first-child window)
             list-box (g/gtk-widget-get-first-child box)
             flow-box (g/gtk-widget-get-next-sibling list-box)]
         (swap! results assoc
                :list-on-mount (child-labels list-box)
                :flow-on-mount (child-labels flow-box))
         ;; Reorder BOTH containers' children by their SAME keys, no
         ;; add/remove — glitter.core's reconciler recognizes each
         ;; :glitter/key as the SAME logical child, just needing a new
         ;; position, and dispatches to reorder-child!, not a
         ;; remove+recreate.
         (reset! state {:list-order ["c" "a" "b"] :flow-order ["z" "x" "y"]})
         (swap! results assoc
                :list-after-reorder (child-labels list-box)
                :flow-after-reorder (child-labels flow-box))))
     :title "glitter list-box/flow-box reorder smoke" :width 280 :height 200
     :app-id "glitter.list-box-reorder-smoke" :auto-quit-ms 500)
    (println :results (pr-str @results))
    (when-not (and (= ["List A" "List B" "List C"] (:list-on-mount @results))
                   (= ["Flow X" "Flow Y" "Flow Z"] (:flow-on-mount @results))
                   (= ["List C" "List A" "List B"] (:list-after-reorder @results))
                   (= ["Flow Z" "Flow X" "Flow Y"] (:flow-after-reorder @results)))
      (println :FAIL "see :results above")
      ;; Direct call — see test_runner.clj/exit for why a resolve-guarded
      ;; version of this silently never exits.
      (System/exit 1))))
