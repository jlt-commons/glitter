(ns glitter.nexus.action-log-test
  (:require [clojure.test :refer [deftest is testing]]
            [glitter.nexus :as nexus]
            [glitter.nexus.action-log :as action-log]))

(deftest pure-effect-dispatch-logs-one-entry-test
  (testing "a single-effect dispatch produces one chronology entry with one action"
    (let [log (action-log/create-log)
          system (atom {})
          config (action-log/install-logger
                  {:nexus/effects
                   {:effect/assoc-in (fn [_ system path v] (swap! system assoc-in path v))}}
                  log)]
      (nexus/dispatch config system nil [[:effect/assoc-in [:draft] "hi"]])
      (is (= {:draft "hi"} @system))
      (is (= 1 (count (:chronology @log))))
      (let [entry-id (first (:chronology @log))
            entry (get-in @log [:entries entry-id])]
        (is (= 1 (count (:actions entry))))
        (is (= [:effect/assoc-in [:draft] "hi"] (:action (first (:actions entry)))))
        (is (some? (:dispatched-at entry)))
        (is (map? (:dispatch-elapsed entry)))))))

(deftest action-expansion-logs-nested-effects-test
  (testing "an action that expands to 2 effects logs both under the action's :expansions"
    (let [log (action-log/create-log)
          system (atom {:tasks []
                        :draft "buy milk"})
          config (action-log/install-logger
                  {:nexus/effects
                   {:effect/assoc-in (fn [_ system path v] (swap! system assoc-in path v))}
                   :nexus/actions
                   {:action/add-task
                    (fn [{:keys [draft tasks]}]
                      [[:effect/assoc-in [:tasks] (conj tasks {:text draft})]
                       [:effect/assoc-in [:draft] ""]])}
                   :nexus/system->state (fn [store] @store)}
                  log)]
      (nexus/dispatch config system nil [[:action/add-task]])
      (is (= {:tasks [{:text "buy milk"}]
              :draft ""} @system))
      (let [entry-id (first (:chronology @log))
            entry (get-in @log [:entries entry-id])
            action-entry (first (:actions entry))]
        (is (= [:action/add-task] (:action action-entry)))
        (is (= 2 (count (:expansions action-entry))))))))

(deftest dom-event-recorded-test
  (testing "dispatch-data containing :glitter/dom-event is recorded on the entry"
    (let [log (action-log/create-log)
          system (atom {})
          config (action-log/install-logger
                  {:nexus/effects
                   {:effect/assoc-in (fn [_ system path v] (swap! system assoc-in path v))}
                   :nexus/placeholders
                   {:glitter/value (fn [{:keys [glitter/dom-event]}] (:glitter/value dom-event))}}
                  log)
          dispatch-data {:glitter/dom-event {:glitter/value "typed"}}]
      (nexus/dispatch config system dispatch-data [[:effect/assoc-in [:draft] [:glitter/value]]])
      (is (= {:draft "typed"} @system))
      (let [entry-id (first (:chronology @log))
            entry (get-in @log [:entries entry-id])]
        (is (= {:glitter/value "typed"} (:dom-event entry)))))))
