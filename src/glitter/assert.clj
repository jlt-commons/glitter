(ns glitter.assert
  (:require #?(:clj [glitter.env :as env])
            [glitter.console-logger :as console]
            [glitter.hiccup-headers :as hiccup])
  (:refer-clojure :exclude [assert])
  #?(:cljs (:require-macros [glitter.assert])))
;; Ported from replicant.assert (https://github.com/cjohansen/replicant),
;; commit 379bb3c1ad4d5d3002c57e67ab647d12f3c2d322. Copyright 2023-2025
;; Christian Johansen. MIT License — see NOTICE.md.

(def ^:no-doc current-context (atom nil))
(def ^:no-doc current-node (atom nil))
(def ^:no-doc error (atom nil))

(defn ^:no-doc assert? []
  #?(:clj (env/enabled? :glitter/asserts? (env/dev?))))

(defmacro ^:no-doc enter-node [headers]
  (when (assert?)
    `(when ~headers
       (when-let [ctx# (or (:glitter/context (hiccup/attrs ~headers))
                           (:glitter/context (meta (hiccup/sexp ~headers))))]
         (reset! current-context ctx#))
       (reset! current-node (hiccup/sexp ~headers)))))

(defmacro ^:no-doc assert [test title message & [hiccup]]
  (when (assert?)
    `(when (not ~test)
       (let [fn# (:fn-name @current-context)
             alias# (:alias @current-context)
             fd# (:data @current-context)]
         (reset! error
                 (cond-> {:title ~title
                          :message ~message
                          :hiccup (or ~hiccup @current-node)}
                   fn# (assoc :fname fn#)
                   alias# (assoc :alias alias#)
                   fd# (assoc :data fd#)))))))

;; API

(defn ^:export add-reporter
  "Add assert error exporter. `k` is a keyword, `f` is a function that will be
  called with an assert error, a map of
  `{:title :message :hiccup :fname :alias :data}`."
  [k f]
  (remove-watch error ::default)
  (add-watch error k (fn [_ _ _ error]
                       #?(:cljs (if (exists? js/requestAnimationFrame)
                                  (js/requestAnimationFrame #(f error))
                                  (f error))
                          :clj (f error)))))

(defn ^:export remove-reporter
  "Remove a previously added reporter, using the same `k` that was used to
  register it. To remove the default reporter, use `:glitter.assert/default`
  as `k`."
  [k]
  (remove-watch error k))

;; Install default reporter

(defmacro ^:no-doc configure []
  (when (assert?)
    `(add-reporter ::default console/report)))
