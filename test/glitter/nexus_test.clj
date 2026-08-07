(ns glitter.nexus-test
  (:require [clojure.test :refer [deftest is testing]]
            [glitter.nexus :as nexus]))

(deftest action?-test
  (testing "a keyword-first vector is an action"
    (is (true? (nexus/action? [:effect/assoc-in [:a] 1]))))
  (testing "not a vector"
    (is (false? (nexus/action? '(:effect/assoc-in [:a] 1)))))
  (testing "not keyword-first"
    (is (false? (nexus/action? ["not-a-keyword"])))))

(deftest pure-effect-dispatch-test
  (testing "a registered effect mutates the system atom"
    (let [system (atom {})
          config {:nexus/effects
                  {:effect/assoc-in (fn [_ system path v] (swap! system assoc-in path v))}}]
      (nexus/dispatch config system nil [[:effect/assoc-in [:draft] "hello"]])
      (is (= {:draft "hello"} @system)))))

(deftest placeholder-interpolation-test
  (testing "a placeholder in action data resolves from dispatch-data"
    (let [system (atom {})
          config {:nexus/effects
                  {:effect/assoc-in (fn [_ system path v] (swap! system assoc-in path v))}
                  :nexus/placeholders
                  {:test/value (fn [dispatch-data] (:v dispatch-data))}}]
      (nexus/dispatch config system {:v 42} [[:effect/assoc-in [:x] [:test/value]]])
      (is (= {:x 42} @system))))
  (testing "nested placeholders resolve inside-out"
    (let [system (atom {})
          config {:nexus/effects
                  {:effect/assoc-in (fn [_ system path v] (swap! system assoc-in path v))}
                  :nexus/placeholders
                  {:test/value (fn [dispatch-data] (:v dispatch-data))
                   :fmt/keyword (fn [_ s] (keyword s))}}]
      (nexus/dispatch config system {:v "roundtrip"}
                      [[:effect/assoc-in [:type] [:fmt/keyword [:test/value]]]])
      (is (= {:type :roundtrip} @system)))))

(deftest action-expansion-test
  (testing "an action reads state and expands into effects"
    (let [system (atom {:tasks []
                        :draft "buy milk"})
          config {:nexus/effects
                  {:effect/assoc-in (fn [_ system path v] (swap! system assoc-in path v))}
                  :nexus/actions
                  {:action/add-task
                   (fn [{:keys [draft tasks]}]
                     (if (seq draft)
                       [[:effect/assoc-in [:tasks] (conj tasks {:text draft})]
                        [:effect/assoc-in [:draft] ""]]
                       []))}
                  :nexus/system->state (fn [store] @store)}]
      (nexus/dispatch config system nil [[:action/add-task]])
      (is (= {:tasks [{:text "buy milk"}]
              :draft ""} @system))))
  (testing "an action returning no actions is a no-op"
    (let [system (atom {:tasks []
                        :draft ""})
          config {:nexus/effects
                  {:effect/assoc-in (fn [_ system path v] (swap! system assoc-in path v))}
                  :nexus/actions
                  {:action/add-task
                   (fn [{:keys [draft tasks]}]
                     (if (seq draft)
                       [[:effect/assoc-in [:tasks] (conj tasks {:text draft})]]
                       []))}
                  :nexus/system->state (fn [store] @store)}]
      (nexus/dispatch config system nil [[:action/add-task]])
      (is (= {:tasks []
              :draft ""} @system)))))

(deftest on-error-test
  (testing "an effect that throws is captured, not propagated, and reaches :nexus/on-error"
    (let [system (atom {})
          errors (atom [])
          config {:nexus/effects
                  {:effect/boom (fn [& _] (throw (ex-info "boom" {})))}
                  :nexus/on-error (fn [_ctx error] (swap! errors conj error))}]
      (nexus/dispatch config system nil [[:effect/boom]])
      (is (= 1 (count @errors)))
      (is (= "boom" (.getMessage ^Exception (:err (first @errors))))))))
