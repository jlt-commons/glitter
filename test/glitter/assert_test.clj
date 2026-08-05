(ns glitter.assert-test
  (:require [clojure.test :refer [deftest is testing]]
            [glitter.env :as env]
            [glitter.errors :as errors]))

(env/configure! :glitter/catch-exceptions? true)

(deftest with-error-handling-test
  (testing "runs body normally when it doesn't throw"
    (is (= 42 (errors/with-error-handling "test" {} (+ 40 2)))))

  (testing "catches an exception and returns nil instead of crashing"
    (is (nil? (errors/with-error-handling "test" {}
                (throw (ex-info "boom" {})))))))
