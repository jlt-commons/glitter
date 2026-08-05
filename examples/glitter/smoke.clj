(ns glitter.smoke
  "Automated smoke test: mount the counter, run the GTK loop briefly via
  auto-quit-ms, and confirm no exception escapes. Run via `jolt smoke`."
  (:require [glitter.app :as app]
            [glitter.core :as core]
            [glitter.gtk :as gtk]))

(defonce state (atom {:count 0}))

(defn view [{:keys [count]}]
  [:box {:spacing 12} [:label {:label (str "Count: " count)}]])

(core/set-dispatch! (fn [_ _] nil))

(defn -main [& _]
  (app/run (fn [window] (gtk/mount! window view state))
           :title "glitter smoke" :width 200 :height 100 :app-id "glitter.smoke"
           :auto-quit-ms 500)
  (println :smoke-ok))
