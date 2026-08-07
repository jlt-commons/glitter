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

(deftest parse-number-test
  (testing "Parses valid numeric strings"
    (is (= 100.0 (temperature/parse-number "100")))
    (is (= 37.5 (temperature/parse-number "37.5")))
    (is (= -17.5 (temperature/parse-number "-17.5"))))

  (testing "Rejects blank, non-numeric, and nil input"
    (is (nil? (temperature/parse-number "")))
    (is (nil? (temperature/parse-number "   ")))
    (is (nil? (temperature/parse-number "abc")))
    (is (nil? (temperature/parse-number nil))))

  (testing "Rejects non-finite doubles Double/parseDouble accepts without throwing"
    (is (nil? (temperature/parse-number "Infinity")))
    (is (nil? (temperature/parse-number "-Infinity")))
    (is (nil? (temperature/parse-number "NaN")))
    (is (nil? (temperature/parse-number "1e400")))))

(deftest format-number-test
  (testing "Whole-number doubles render without a trailing .0"
    (is (= "100" (temperature/format-number 100.0)))
    (is (= "0" (temperature/format-number 0.0))))

  (testing "Non-whole doubles render via str"
    (is (= "37.5" (temperature/format-number 37.5))))

  (testing "A finite-but-huge double (parse-number accepts it — see its own
  docstring, which only rejects NaN/Infinity) does not crash the (long n)
  probe, falling back to str's own rendering instead"
    (is (= "1.0E300" (temperature/format-number 1.0E300)))))
