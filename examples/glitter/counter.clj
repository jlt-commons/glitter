(ns glitter.counter
  "A state-atom counter — the canonical Replicant-style demo over GTK4.
  Contrast with glimmer's examples/glimmer/counter.clj: there, local state
  lives in a component-scoped ratom and a click closure calls swap! directly.
  Here, ALL state lives in one top-level atom; the view is a pure function of
  it; and click handlers are DATA (:on {:click [[:action/dec]]}}) dispatched
  through a single global handler, never closures."
  (:require [glitter.app :as app]
            [glitter.core :as core]
            [glitter.gtk :as gtk]))

(defonce state (atom {:count 0}))

(defn view [{:keys [count]}]
  [:box {:spacing 12}
   [:label {:label (str "Count: " count)}]
   [:box {:spacing 8}
    [:button {:label "− 1" :on {:click [[:action/dec]]}}]
    [:button {:label "+ 1" :on {:click [[:action/inc]]}}]
    [:button {:label "reset" :on {:click [[:action/reset]]}}]]])

(defn execute-actions [_event actions]
  (doseq [[kind] actions]
    (case kind
      :action/inc (swap! state update :count inc)
      :action/dec (swap! state update :count dec)
      :action/reset (swap! state assoc :count 0)
      nil)))

(core/set-dispatch! execute-actions)

(defn -main [& _]
  (app/run (fn [window] (gtk/mount! window view state))
           :title "glitter counter" :width 320 :height 160 :app-id "glitter.counter"))
