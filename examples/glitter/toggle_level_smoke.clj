(ns glitter.toggle-level-smoke
  "Automated smoke against the LIVE GTK tree for :toggle-button and
  :level-bar — the fourth and fifth widget additions after :scale,
  :spinner/:progress-bar/:image.

  :toggle-button reuses the existing :on-toggled -> \"toggled\" signal
  entry verbatim (the same one :checkbutton uses) — this smoke is what
  proves that reuse actually works end-to-end for a second, unrelated GTK4
  widget class, not just checkbutton. Covers the same three things
  scale_smoke.clj established the pattern for: a real click (simulated via
  a direct FFI gtk_toggle_button_set_active call, bypassing
  set-toggle-button-active! so the actual \"toggled\" signal fires) reaches
  *dispatch*; a subsequent programmatic state change pushes the new value
  back onto the widget via set-toggle-button-active!; and that programmatic
  push does NOT cause a second, spurious dispatch (the suppressing guard).

  :level-bar is display-only (no signal) — same shape as
  leaf_widgets_smoke.clj's trio, pinning construction + re-render against
  real GTK state via gtk_level_bar_get_value."
  (:require [glitter.app :as app]
            [glitter.core :as core]
            [glitter.ffi :as g]
            [glitter.gtk :as gtk]))

(defonce state (atom {:pressed false :level 0.3}))
(defonce dispatch-count (atom 0))

(defn view [{:keys [pressed level]}]
  [:box {:spacing 4}
   [:toggle-button {:label "mute" :active pressed :on {:toggled [[:action/toggle]]}}]
   [:level-bar {:min-value 0 :max-value 1 :value level}]])

(defn execute-actions [_event actions]
  (doseq [[kind] actions]
    (case kind
      :action/toggle (do (swap! dispatch-count inc) (swap! state update :pressed not))
      nil)))

(core/set-dispatch! execute-actions)

(defn -main [& _]
  (let [results (atom {})]
    (app/run
     (fn [window]
       (gtk/mount! window view state)
       ;; :window is a single-child container — the mounted [:box ...] is
       ;; window's ONE child; :toggle-button and :level-bar are box's two
       ;; children in sibling order.
       (let [box (g/gtk-widget-get-first-child window)
             toggle (g/gtk-widget-get-first-child box)
             level (g/gtk-widget-get-next-sibling toggle)]
         (swap! results assoc
                :toggle-active-on-mount (pos? (g/gtk-toggle-button-get-active toggle))
                :level-value-on-mount (g/gtk-level-bar-get-value level))
         (g/gtk-toggle-button-set-active toggle 1)
         (swap! results assoc
                :state-pressed-after-click (:pressed @state)
                :dispatch-count-after-click @dispatch-count)
         (reset! state {:pressed false :level 0.8})
         (swap! results assoc
                :toggle-active-after-programmatic (pos? (g/gtk-toggle-button-get-active toggle))
                :dispatch-count-after-programmatic @dispatch-count
                :level-value-after-update (g/gtk-level-bar-get-value level))))
     :title "glitter toggle+level smoke" :width 320 :height 100
     :app-id "glitter.toggle-level-smoke" :auto-quit-ms 500)
    (println :results (pr-str @results))
    (when-not (and (false? (:toggle-active-on-mount @results))
                   (= 0.3 (:level-value-on-mount @results))
                   (true? (:state-pressed-after-click @results))
                   (= 1 (:dispatch-count-after-click @results))
                   (false? (:toggle-active-after-programmatic @results))
                   (= 1 (:dispatch-count-after-programmatic @results))
                   (= 0.8 (:level-value-after-update @results)))
      (println :FAIL "see :results above")
      ;; Direct call — see test_runner.clj/exit for why a resolve-guarded
      ;; version of this silently never exits.
      (System/exit 1))))
