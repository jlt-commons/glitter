(ns glitter.alias-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [glitter.alias :as alias]))

;; glitter.alias/aliases is a process-global registry, and glitter.gtk/mount!
;; merges it into every reconcile. Registering into it from a test leaks into
;; anything that runs afterwards in the same process, so snapshot and restore.
;; A named var (not an inline fn) so registry-is-restored-after-fixture-
;; teardown below can invoke it directly, rather than relying on deftest
;; execution order to observe its cleanup.
(defn- with-clean-registry [t]
  (let [snapshot @alias/aliases]
    (try (t)
         (finally (reset! alias/aliases snapshot)))))

(use-fixtures :each with-clean-registry)

(deftest expand-1-test
  (testing "Expands first level of aliases via the global registry"
    (alias/register! ::greeting (fn [attrs _children] [:label attrs (str "Hello, " (:name attrs))]))
    (is (= [:label {:name "World"} "Hello, World"]
           (alias/expand-1 [::greeting {:name "World"}])))))

(deftest registry-is-restored-after-fixture-teardown
  (testing "the fixture genuinely restores the registry after its wrapped
            thunk returns — self-contained, not dependent on running after
            another deftest that happens to register the same key"
    (with-clean-registry
      (fn []
        (alias/register! ::self-contained-check (fn [_ _] [:label {} "x"]))
        (is (some? (get @alias/aliases ::self-contained-check))
            "sanity: registration took effect inside the fixture's thunk")))
    (is (nil? (get @alias/aliases ::self-contained-check))
        "the fixture's finally restored the pre-registration snapshot once the thunk returned")))
