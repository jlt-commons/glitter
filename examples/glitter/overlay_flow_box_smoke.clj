(ns glitter.overlay-flow-box-smoke
  "Automated smoke against the LIVE GTK tree for :overlay and :flow-box —
  round 8's two new container/list widgets (:aspect-frame and :calendar,
  the round's quick-win and new-complexity-class additions, get their own
  aspect_frame_calendar_smoke.clj).

  :overlay is a genuinely different shape from every other multi-child
  container here: :box is an ordered append-list, :center-box/:paned are
  fixed NAMED slots queried live via per-slot getters — GtkOverlay has
  exactly ONE queryable slot (gtk_overlay_get_child, the main content)
  and an UNBOUNDED set of floating overlay children with NO enumeration
  getter at all (confirmed: no \"get overlays\" function in
  gtk/gtkoverlay.h). glitter.widget's overlay-* container functions treat
  the FIRST hiccup child as main and every subsequent child as an
  overlay unconditionally. Since GTK exposes no way to enumerate current
  overlays, this smoke verifies removal by reusing the GENERIC widget-
  tree walk (gtk_widget_get_first_child/get_next_sibling) every other
  structural smoke here already uses — overlay children ARE real GTK
  widget-tree children, just laid out specially by GtkOverlay's own
  layout manager, so counting them this way works without any
  overlay-specific enumeration API.

  :flow-box is a flowing/wrapping sibling to :list-box: append/insert
  auto-wrap a plain child in a GtkFlowBoxChild, the identical shape
  :list-box's GtkListBoxRow auto-wrap uses (confirmed against
  gtk/gtkflowbox.c's gtk_flow_box_insert body) — but its remove does NOT
  share :list-box's gtk_list_box_remove gotcha: gtk_flow_box_remove's C
  body explicitly accepts either the wrapped GtkFlowBoxChild or the
  plain inner widget, auto-unwrapping internally (confirmed by reading
  it directly, not assumed just because the widgets are siblings) — a
  genuine, verified DIFFERENCE from :list-box, not an assumption that
  lessons transfer automatically between structurally similar widgets.
  \"child-activated\" reuses the same 3-arg-void callable shape
  :list-box/:expander/:paned already generalized, another free reuse.
  v1 deliberately wires :on-child-activated with NO value-fn (see
  flow-box-spec's own docstring) — this smoke only proves the tag-swap/
  container-management path, not a dispatched activation value."
  (:require [glitter.app :as app]
            [glitter.core :as core]
            [glitter.ffi :as g]
            [glitter.gtk :as gtk]))

(defonce state (atom {:swap-flow1? false :drop-overlay? false}))

(defn view [{:keys [swap-flow1? drop-overlay?]}]
  [:box {:spacing 4}
   (into [:overlay [:label {:label "main"}]]
         (if drop-overlay? [] [[:label {:label "overlay1"}]]))
   [:flow-box {:on {:child-activated [[:action/activated]]}}
    [:label {:label "flow0"}]
    (if swap-flow1? [:button {:label "flow1-swapped"}] [:label {:label "flow1"}])
    [:label {:label "flow2"}]]])

(core/set-dispatch! (fn [_ _] nil))

(defn- count-children
  "Generic widget-tree child count — GTK exposes no overlay-specific
  enumeration API, so this is the only way to verify overlay-child
  removal actually happened."
  [w]
  (loop [c (g/gtk-widget-get-first-child w) n 0]
    (if (or (nil? c) (zero? c)) n (recur (g/gtk-widget-get-next-sibling c) (inc n)))))

(defn -main [& _]
  (let [results (atom {})]
    (app/run
     (fn [window]
       (gtk/mount! window view state)
       ;; :window is a single-child container — the mounted [:box ...] is
       ;; window's ONE child; :overlay and :flow-box are box's two
       ;; children in sibling order.
       (let [box (g/gtk-widget-get-first-child window)
             overlay (g/gtk-widget-get-first-child box)
             flow-box (g/gtk-widget-get-next-sibling overlay)]
         (swap! results assoc
                :overlay-main-on-mount (g/gtk-label-get-text (g/gtk-overlay-get-child overlay))
                :overlay-child-count-on-mount (count-children overlay)
                :flow0-on-mount (g/gtk-label-get-text (g/gtk-widget-get-first-child
                                                       (g/gtk-widget-get-first-child flow-box))))
         ;; Re-render: swap flow-box's MIDDLE child's tag (:label ->
         ;; :button) — exercises flow-box-insert-after!/flow-box's own
         ;; remove path, and proves siblings stay untouched.
         (swap! state assoc :swap-flow1? true)
         (let [flow0 (g/gtk-widget-get-first-child flow-box)
               flow1 (g/gtk-widget-get-next-sibling flow0)
               flow2 (g/gtk-widget-get-next-sibling flow1)]
           (swap! results assoc
                  :flow0-after-swap (g/gtk-label-get-text (g/gtk-widget-get-first-child flow0))
                  :flow1-after-swap (g/gtk-button-get-label (g/gtk-widget-get-first-child flow1))
                  :flow2-after-swap (g/gtk-label-get-text (g/gtk-widget-get-first-child flow2))))
         ;; Re-render: drop the overlay child entirely (pure removal) —
         ;; main content must stay untouched, and the actual GTK child
         ;; count must drop by exactly one.
         (swap! state assoc :drop-overlay? true)
         (swap! results assoc
                :overlay-main-after-drop (g/gtk-label-get-text (g/gtk-overlay-get-child overlay))
                :overlay-child-count-after-drop (count-children overlay))))
     :title "glitter overlay+flow-box smoke" :width 320 :height 140
     :app-id "glitter.overlay-flow-box-smoke" :auto-quit-ms 500)
    (println :results (pr-str @results))
    (when-not (and (= "main" (:overlay-main-on-mount @results))
                   (= 2 (:overlay-child-count-on-mount @results))
                   (= "flow0" (:flow0-on-mount @results))
                   (= "flow0" (:flow0-after-swap @results))
                   (= "flow1-swapped" (:flow1-after-swap @results))
                   (= "flow2" (:flow2-after-swap @results))
                   (= "main" (:overlay-main-after-drop @results))
                   (= 1 (:overlay-child-count-after-drop @results)))
      (println :FAIL "see :results above")
      ;; Direct call — see test_runner.clj/exit for why a resolve-guarded
      ;; version of this silently never exits.
      (System/exit 1))))
