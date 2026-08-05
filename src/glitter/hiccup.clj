(ns glitter.hiccup)

;; Ported from replicant.hiccup (https://github.com/cjohansen/replicant),
;; commit 379bb3c1ad4d5d3002c57e67ab647d12f3c2d322. Copyright 2023-2025
;; Christian Johansen. MIT License — see NOTICE.md.

(defn hiccup?
  "Returns `true` if `sexp` is a vector with a keyword in the first position."
  [sexp]
  (and (vector? sexp)
       (not (map-entry? sexp))
       (keyword? (first sexp))))

(defn update-attrs
  "Ensure that `hiccup` has an attribute map, and call `update` on it with `args`.

  ```clj
  (update-attrs [:h1 \"Hello\"] assoc :title \"Hi\")
  ;;=> [:h1 {:title \"Hi\"} \"Hello\"]

  (update-attrs [:h1 {:title \"Hello\"} \"Hello\"] dissoc :title)
  ;;=> [:h1 {} \"Hello\"]
  ```"
  [hiccup & args]
  (if (map? (second hiccup))
    (apply update hiccup 1 args)
    (into [(first hiccup) (apply (first args) {} (rest args))] (rest hiccup))))

(defn set-attr
  "Set attribute `attr` on the `hiccup` node to `v`. Updates the attribute map
  if it exists, otherwise inserts one.

  ```clj
  (set-attr [:h1 \"Hello\"] :title \"Hi\")
  ;;=> [:h1 {:title \"Hi\"} \"Hello\"]

  (set-attr [:h1 {:title \"Hello\"} \"Hello\"] :title \"Hi\")
  ;;=> [:h1 {:title \"Hi\"} \"Hello\"]
  ```"
  [hiccup attr v]
  (update-attrs hiccup assoc attr v))
