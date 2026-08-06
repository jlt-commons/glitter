(ns glitter.leaf-widgets-smoke
  "Automated smoke against the LIVE GTK tree for the three simplest widgets
  in the set: :spinner, :progress-bar, :image. All three are display-only —
  no signal to wire, driven entirely by props re-applied on every render —
  so unlike scale_smoke.clj/class_smoke.clj this pins construction +
  re-render only, reading each widget's actual GTK state back (not
  glitter's own bookkeeping) via gtk_spinner_get_spinning/
  gtk_progress_bar_get_fraction/gtk_image_get_icon_name."
  (:require [glitter.app :as app]
            [glitter.core :as core]
            [glitter.ffi :as g]
            [glitter.gtk :as gtk]))

(defonce state (atom {:spinning true :fraction 0.25 :icon-name "edit-find-symbolic"}))

(defn view [{:keys [spinning fraction icon-name]}]
  [:box {:spacing 4}
   [:spinner {:spinning spinning}]
   [:progress-bar {:fraction fraction :text "loading" :show-text true}]
   [:image {:icon-name icon-name :pixel-size 32}]])

(core/set-dispatch! (fn [_ _] nil))

(defn -main [& _]
  (let [results (atom {})]
    (app/run
     (fn [window]
       (gtk/mount! window view state)
       ;; :window is a single-child container — the mounted [:box ...] is
       ;; window's ONE child; spinner/progress-bar/image are box's three
       ;; children in sibling order.
       (let [box (g/gtk-widget-get-first-child window)
             spinner (g/gtk-widget-get-first-child box)
             progress (g/gtk-widget-get-next-sibling spinner)
             image (g/gtk-widget-get-next-sibling progress)]
         (swap! results assoc
                :spinning-on-mount (pos? (g/gtk-spinner-get-spinning spinner))
                :fraction-on-mount (g/gtk-progress-bar-get-fraction progress)
                :icon-on-mount (g/gtk-image-get-icon-name image))
         (reset! state {:spinning false :fraction 0.75 :icon-name "edit-delete-symbolic"})
         (swap! results assoc
                :spinning-after-update (pos? (g/gtk-spinner-get-spinning spinner))
                :fraction-after-update (g/gtk-progress-bar-get-fraction progress)
                :icon-after-update (g/gtk-image-get-icon-name image))))
     :title "glitter leaf widgets smoke" :width 320 :height 100
     :app-id "glitter.leaf-widgets-smoke" :auto-quit-ms 500)
    (println :results (pr-str @results))
    (when-not (and (true? (:spinning-on-mount @results))
                   (= 0.25 (:fraction-on-mount @results))
                   (= "edit-find-symbolic" (:icon-on-mount @results))
                   (false? (:spinning-after-update @results))
                   (= 0.75 (:fraction-after-update @results))
                   (= "edit-delete-symbolic" (:icon-after-update @results)))
      (println :FAIL "see :results above")
      ;; Direct call — see test_runner.clj/exit for why a resolve-guarded
      ;; version of this silently never exits.
      (System/exit 1))))
