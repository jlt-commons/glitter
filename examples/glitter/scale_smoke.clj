(ns glitter.scale-smoke
  "Automated :scale smoke against the LIVE GTK tree — pins glitter's first
  custom value-bearing signal (:on-value-changed -> \"value-changed\",
  registered by glitter.widget for GtkScale/GtkRange; entry's built-in
  :change is the only other one). Verifies three things a scratch probe
  confirmed manually before this was written:

  1. A real user-driven value change (simulated via a direct FFI
     gtk_range_set_value call — exactly what a live slider drag does
     internally) reaches *dispatch* with the correct double at
     (get-in event [:glitter/dom-event :glitter/value]).
  2. A state-driven re-render pushes the new value back onto the live
     widget via glitter.widget's set-scale-value!.
  3. That programmatic push does NOT cause a second, spurious dispatch —
     the same suppressing guard set-entry-text!/set-checkbutton-active!
     use, verified end-to-end here for :scale specifically. Without it, an
     unrelated state change that happens to touch :volume would loop
     set_value -> value-changed -> dispatch -> re-render -> set_value."
  (:require [glitter.app :as app]
            [glitter.core :as core]
            [glitter.ffi :as g]
            [glitter.gtk :as gtk]))

(defonce state (atom {:volume 30}))
(defonce dispatch-count (atom 0))

(defn view [{:keys [volume]}]
  [:box {:spacing 4}
   [:scale {:min 0 :max 100 :step 5 :value volume :digits 0
            :on {:value-changed [[:action/set-volume]]}}]])

(defn execute-actions [event actions]
  (doseq [[kind] actions]
    (case kind
      :action/set-volume
      (do (swap! dispatch-count inc)
          (swap! state assoc :volume (get-in event [:glitter/dom-event :glitter/value])))
      nil)))

(core/set-dispatch! execute-actions)

(defn -main [& _]
  (let [results (atom {})]
    (app/run
     (fn [window]
       (gtk/mount! window view state)
       ;; :window is a single-child container — the mounted [:box ...] is
       ;; window's ONE child, and :scale is box's one child in turn.
       (let [scale (g/gtk-widget-get-first-child (g/gtk-widget-get-first-child window))]
         (swap! results assoc :initial-value (g/gtk-range-get-value scale))
         ;; Simulate a live drag directly via FFI, bypassing set-scale-value!
         ;; so the real "value-changed" signal fires exactly as it would from
         ;; a user's pointer.
         (g/gtk-range-set-value scale 77.0)
         (swap! results assoc
                :state-after-drag (:volume @state)
                :dispatch-count-after-drag @dispatch-count)
         ;; Now change state from elsewhere in the (hypothetical) app and
         ;; confirm the widget follows, with no spurious extra dispatch.
         (reset! state {:volume 10})
         (swap! results assoc
                :widget-value-after-programmatic-set (g/gtk-range-get-value scale)
                :dispatch-count-after-programmatic-set @dispatch-count)))
     :title "glitter scale smoke" :width 320 :height 120 :app-id "glitter.scale-smoke"
     :auto-quit-ms 500)
    (println :results (pr-str @results))
    (when-not (and (= 30.0 (:initial-value @results))
                   (= 77.0 (:state-after-drag @results))
                   (= 1 (:dispatch-count-after-drag @results))
                   (= 10.0 (:widget-value-after-programmatic-set @results))
                   (= 1 (:dispatch-count-after-programmatic-set @results)))
      (println :FAIL "see :results above")
      ;; Direct call — see test_runner.clj/exit for why a resolve-guarded
      ;; version of this silently never exits.
      (System/exit 1))))
