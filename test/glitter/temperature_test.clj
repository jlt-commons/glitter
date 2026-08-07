(ns glitter.temperature-test
  (:require [clojure.test :refer [deftest is testing]]
            [glitter.temperature :as temperature]))

(deftest fahrenheit->celsius-test
  (testing "Converts to Celsius"
    (is (= 0.0 (temperature/fahrenheit->celsius 32)))
    (is (= 50.0 (temperature/fahrenheit->celsius 122)))
    (is (= 100.0 (temperature/fahrenheit->celsius 212)))))

(deftest celsius->fahrenheit-test
  (testing "Converts to Fahrenheit"
    (is (= 32.0 (temperature/celsius->fahrenheit 0)))
    (is (= 122.0 (temperature/celsius->fahrenheit 50)))
    (is (= 212.0 (temperature/celsius->fahrenheit 100)))))

(deftest set-temperature-test
  (testing "Sets temperature and calculates Fahrenheit"
    (is (= [[:effect/assoc-in [:celsius] 50]
            [:effect/assoc-in [:fahrenheit] 122.0]]
           (temperature/set-temperature {:celsius 50}))))

  (testing "Sets temperature and calculates Celsius"
    (is (= [[:effect/assoc-in [:celsius] 100.0]
            [:effect/assoc-in [:fahrenheit] 212]]
           (temperature/set-temperature {:fahrenheit 212})))))

(deftest set-temperature-invalid-input-test
  (testing "Both fields nil (failed parse) is a no-op"
    (is (= [] (temperature/set-temperature {:celsius nil}))))

  (testing "Neither key present is a no-op"
    (is (= [] (temperature/set-temperature {})))))
