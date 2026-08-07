(ns glitter.nexus.registry-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [glitter.nexus.registry :as nxr]))

(use-fixtures :each
  (fn [t]
    (reset! nxr/!registry {})
    (t)))

(deftest register-and-dispatch-test
  (testing "registered effects/placeholders are used by registry/dispatch"
    (nxr/register-effect! :effect/assoc-in
                          (fn [_ system path v] (swap! system assoc-in path v)))
    (nxr/register-placeholder! :test/value
                               (fn [dispatch-data] (:v dispatch-data)))
    (let [system (atom {})]
      (nxr/dispatch system {:v "typed"} [[:effect/assoc-in [:draft] [:test/value]]])
      (is (= {:draft "typed"} @system)))))

(deftest register-action-and-expansion-alias-test
  (testing "register-action! and register-expansion! both land under :nexus/expansions"
    (nxr/register-effect! :effect/assoc-in
                          (fn [_ system path v] (swap! system assoc-in path v)))
    (nxr/register-system->state! deref)
    (nxr/register-action! :action/a (fn [_state] [[:effect/assoc-in [:a] 1]]))
    (nxr/register-expansion! :action/b (fn [_state] [[:effect/assoc-in [:b] 2]]))
    (let [system (atom {})]
      (nxr/dispatch system nil [[:action/a] [:action/b]])
      (is (= {:a 1
              :b 2} @system)))))

(deftest on-error-test
  (testing "on-error registers :nexus/on-error"
    (nxr/register-effect! :effect/boom (fn [& _] (throw (ex-info "boom" {}))))
    (let [errors (atom [])]
      (nxr/on-error (fn [_ctx error] (swap! errors conj error)))
      (nxr/dispatch (atom {}) nil [[:effect/boom]])
      (is (= 1 (count @errors))))))
