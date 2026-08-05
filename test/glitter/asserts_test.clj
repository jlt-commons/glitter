(ns glitter.asserts-test
  (:require [clojure.test :refer [deftest is testing]]
            [glitter.asserts :as asserts]))

(deftest assert-valid-id-test
  (testing "does not throw for a normal id (relies on :glitter/asserts?
            defaulting true via glitter.env/dev? — if that default ever
            changes, this assertion macro compiles away to nil and this
            test silently stops checking anything; see glitter.env.clj)"
    (is (nil? (asserts/assert-non-empty-id "div" "div#main")))))
