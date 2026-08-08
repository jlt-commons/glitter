(ns glitter.timer-test
  (:require [clojure.test :refer [deftest is testing]]
            [glitter.timer :as timer]))

(deftest format-seconds-test
  (testing "Whole seconds render as an int"
    (is (= 0 (timer/format-seconds 0)))
    (is (= 5 (timer/format-seconds 5))))

  (testing "A value whose tenths digit is 0 after ported truncation still
  renders as an int (14.031s truncates to 14.0 tenths, not 14.1)"
    (is (= 14 (timer/format-seconds 14.031))))

  (testing "A genuinely fractional tenths value renders as a float"
    (is (= 37.5 (timer/format-seconds 37.5)))))

(deftest get-view-state-test
  (testing "Uses sane defaults"
    (is (= {:pct 0
            :elapsed "0s"
            :duration 20}
           (timer/get-view-state {}))))

  (testing "Uses set duration"
    (is (= {:pct 0
            :elapsed "0s"
            :duration 15}
           (timer/get-view-state {:duration 15}))))

  (testing "Calculates elapsed from started (nanoseconds; 5000000000ns = 5s)"
    (is (= {:pct 50
            :elapsed "5s"
            :duration 10}
           (timer/get-view-state {:now 5000000000
                                  :started 0
                                  :duration 10}))))

  (testing "Calculates elapsed in tenths of a second (14031000000ns = 14.031s)"
    (is (= {:pct 70
            :elapsed "14s"
            :duration 20}
           (timer/get-view-state {:now 14031000000
                                  :started 0
                                  :duration 20}))))

  (testing "Stops when elapsed = duration (64667000000ns = 64.667s, capped to 10s)"
    (is (= {:pct 100
            :elapsed "10s"
            :duration 10}
           (timer/get-view-state {:now 64667000000
                                  :started 0
                                  :duration 10}))))

  (testing "Duration 0 (the :scale's own :min) does not throw — a bare
  int 0 divides by an integer 0 downstream unless coerced to a double
  first; a plain double 0.0 was always safe. The returned :duration
  stays the original int 0, not the internally-coerced double — (=
  0 0.0) is false under Jolt, unlike JVM Clojure"
    (is (= {:pct 0
            :elapsed "0s"
            :duration 0}
           (timer/get-view-state {:duration 0})))))
