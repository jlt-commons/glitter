(ns glitter.alias-test
  (:require [clojure.test :refer [deftest is testing]]
            [glitter.alias :as alias]))

(deftest expand-1-test
  (testing "Expands first level of aliases"
    (alias/register! ::greeting (fn [attrs _children] [:label attrs (str "Hello, " (:name attrs))]))
    (is (= [:label {:name "World"} "Hello, World"]
           (alias/expand-1 [::greeting {:name "World"}])))))
