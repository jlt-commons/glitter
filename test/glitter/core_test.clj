(ns glitter.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [glitter.core :as core]
            [glitter.test-renderer :as tr]))

(defn- render
  ([hiccup] (render {:tag-name "body" :children []} hiccup nil))
  ([el hiccup vdom]
   (let [r (tr/renderer)
         result (core/reconcile r (atom el) hiccup vdom)]
     result)))

(deftest builds-nodes-test
  (testing "Builds nodes — mirrors replicant core_test.cljc's 'Builds nodes'"
    (let [r (tr/renderer)
          el (atom {:tag-name "body" :children []})]
      (core/reconcile r el [:h1 {} "Hello world"])
      (is (= [[:create-element "h1"]
              [:create-text-node "Hello world"]
              [:append-child "Hello world" :to "h1"]
              [:append-child "h1" :to "body"]]
             (tr/events r))))))

(deftest moves-keyed-nodes-test
  (testing "Moves keyed nodes — mirrors replicant core_test.cljc's exact scenario
            (already manually probe-verified against real upstream replicant.core
            during this project's brainstorming phase)"
    (let [r (tr/renderer)
          el (atom {:tag-name "ul" :children []})
          result1 (core/reconcile r el
                                   [:ul {}
                                    [:li {:glitter/key "0"} "Item #1"]
                                    [:li {:glitter/key "1"} "Item #2"]
                                    [:li {:glitter/key "2"} "Item #3"]])]
      (let [_ (do (reset! (:log (meta r)) []) nil)
            result2 (core/reconcile r el
                                     [:ul {}
                                      [:li {:glitter/key "2"} "Item #3"]
                                      [:li {:glitter/key "0"} "Item #1"]
                                      [:li {:glitter/key "1"} "Item #2"]]
                                     (:vdom result1))]
        (is (= [[:insert-before "li" "li" :in "ul"]]
               (tr/events r)))))))

(deftest lifecycle-on-mount-test
  (testing "Triggers on-mount on first mount"
    (let [r (tr/renderer)
          el (atom {:tag-name "body" :children []})
          calls (atom [])]
      (core/reconcile r el
                       [:div {:glitter/on-mount (fn [e] (swap! calls conj (:glitter/life-cycle e)))}])
      (is (= [:glitter.life-cycle/mount] @calls)))))

(deftest build-event-map-node-fix-test
  (testing "build-event-map surfaces :glitter/node from a map-shaped event
            (the deliberate deviation from the verbatim replicant.core port —
            upstream's :clj branch hardcodes node to nil since it never runs
            live event dispatch outside a browser)"
    (is (= "the-widget"
           (:glitter/node (core/build-event-map {:glitter/node "the-widget"}))))))
