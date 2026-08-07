(ns glitter.hiccup-test
  (:require [clojure.test :refer [deftest is testing]]
            [glitter.hiccup :as h]))

(deftest hiccup-test
  (testing "hiccup?"
    (is (true? (h/hiccup? [:div "hello"])))
    (is (false? (h/hiccup? "just a string")))
    (is (false? (h/hiccup? [1 2 3]))))

  (testing "update-attrs merges into the attrs map"
    (is (= [:div {:class "a"
                  :id "x"} "hi"]
           (h/update-attrs [:div {:class "a"} "hi"] assoc :id "x"))))

  (testing "set-attr sets a single attribute"
    (is (= [:div {:id "x"} "hi"]
           (h/set-attr [:div "hi"] :id "x")))))
