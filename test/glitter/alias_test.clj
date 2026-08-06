(ns glitter.alias-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [glitter.alias :as alias]))

;; glitter.alias/aliases is a process-global registry, and glitter.gtk/mount!
;; merges it into every reconcile. Registering into it from a test leaks into
;; anything that runs afterwards in the same process, so snapshot and restore.
(use-fixtures :each
  (fn [t]
    (let [snapshot @alias/aliases]
      (try (t)
           (finally (reset! alias/aliases snapshot))))))

(deftest expand-1-test
  (testing "Expands first level of aliases via the global registry"
    (alias/register! ::greeting (fn [attrs _children] [:label attrs (str "Hello, " (:name attrs))]))
    (is (= [:label {:name "World"} "Hello, World"]
           (alias/expand-1 [::greeting {:name "World"}])))))

(deftest registry-is-restored-between-tests
  (testing "the fixture really does undo the previous deftest's register!"
    (is (nil? (get @alias/aliases ::greeting)))))
