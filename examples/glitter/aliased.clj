(ns glitter.aliased
  "Live-GTK alias smoke — the design spec put aliases in v1 scope with the
  requirement that they be \"exercised by at least one example\", which the
  plan dropped. Until now the only alias coverage was alias_test.clj's
  expand-1 assertion against a plain data structure; nothing proved an alias
  actually expands through glitter.gtk's real renderer, where
  glitter.gtk/mount! merges the global registry into every reconcile call.

  Exercises both halves that a data-structure test cannot: an alias that
  renders on the INITIAL mount, and the same alias re-rendering with new
  attrs after a state change (glitter.core/reconcile*'s alias branch, which
  reuses the node when the alias result stays shape-compatible)."
  (:require [glitter.alias :as alias]
            [glitter.app :as app]
            [glitter.core :as core]
            [glitter.ffi :as g]
            [glitter.gtk :as gtk]))

(defonce state (atom {:n 1}))

(alias/defalias badge
  [{:keys [n]} _children]
  [:label {:label (str "badge:" n)}])

(defn view [{:keys [n]}]
  [:box {:spacing 4}
   [badge {:n n}]
   [:label {:label (str "plain:" n)}]])

(core/set-dispatch! (fn [_ _] nil))

(defn- gtk-child-labels [box-widget]
  (loop [child (g/gtk-widget-get-first-child box-widget) acc []]
    (if (or (nil? child) (zero? child))
      acc
      (recur (g/gtk-widget-get-next-sibling child)
             (conj acc (g/gtk-label-get-text child))))))

(defn -main [& _]
  (let [on-mount (atom nil)
        after-update (atom nil)]
    (app/run
     (fn [window]
       (gtk/mount! window view state)
       (let [box (g/gtk-widget-get-first-child window)]
         (reset! on-mount (gtk-child-labels box))
         (reset! state {:n 2})
         (reset! after-update (gtk-child-labels box))))
     :title "glitter aliases" :width 240 :height 160
     :app-id "glitter.aliased" :auto-quit-ms 500)
    (println :on-mount @on-mount)
    (println :after-update @after-update)
    (when-not (and (= ["badge:1" "plain:1"] @on-mount)
                   (= ["badge:2" "plain:2"] @after-update))
      (println :FAIL "expected [badge:1 plain:1] then [badge:2 plain:2], got"
               @on-mount @after-update)
      ;; Direct call — see test_runner.clj/exit for why a resolve-guarded
      ;; version of this silently never exits.
      (System/exit 1))))
