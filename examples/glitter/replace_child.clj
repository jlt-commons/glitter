(ns glitter.replace-child
  "Automated replace-child smoke against the LIVE GTK tree — the positional
  sibling of keyed.clj. Where keyed.clj proves a keyed REORDER lands correctly,
  this proves a REPLACEMENT stays where it was.

  The scenario is the most ordinary update Replicant hiccup has: a text child
  whose string value changes. glitter.core/reconcile* handles that by calling
  IRender/replace-child (its \"replace the text node at this index\" branch),
  never by patching the existing widget in place — so the new widget has to be
  put back at the replaced one's exact position.

  A stable sibling AFTER the text child is what makes a wrong position
  observable. With the text child last, an append-based implementation lands
  in the right place by accident — which is exactly why the original bug
  (gtk_box_remove + gtk_box_append, relocating to the end of the box) survived
  both smoke.clj and keyed.clj and was only caught by the final whole-branch
  review.

  Reads GTK's actual child order back via gtk_widget_get_first_child /
  get_next_sibling rather than glitter.gtk's Clojure-side :children tracking,
  because the bug this pins desynced precisely those two views of the tree —
  asserting against the bookkeeping would have agreed with itself and passed."
  (:require [glitter.app :as app]
            [glitter.core :as core]
            [glitter.ffi :as g]
            [glitter.gtk :as gtk]))

(defonce state (atom {:txt "first"}))

(defn view [{:keys [txt]}]
  [:box {:spacing 4}
   txt
   [:label {:label "anchor"}]])

(core/set-dispatch! (fn [_ _] nil))

(defn- gtk-child-labels
  "Walk the live GTK box's children and read each label's actual text — the
  ground truth, independent of any Clojure-side bookkeeping."
  [box-widget]
  (loop [child (g/gtk-widget-get-first-child box-widget) acc []]
    (if (or (nil? child) (zero? child))
      acc
      (recur (g/gtk-widget-get-next-sibling child)
             (conj acc (g/gtk-label-get-text child))))))

(defn -main [& _]
  (let [before (atom nil)
        after (atom nil)]
    (app/run
     (fn [window]
       (gtk/mount! window view state)
       ;; :window is a single-child container — the mounted [:box ...] is
       ;; window's ONE child, not window itself. Descend one level before
       ;; walking (the same descent keyed.clj needs).
       (let [box (g/gtk-widget-get-first-child window)]
         (reset! before (gtk-child-labels box))
         (reset! state {:txt "second"})
         (reset! after (gtk-child-labels box))))
     :title "glitter replace-child" :width 240 :height 160
     :app-id "glitter.replace-child" :auto-quit-ms 500)
    (println :before @before)
    (println :after @after)
    (when (not= ["second" "anchor"] @after)
      (println :FAIL "expected [second anchor], got" @after)
      ;; Direct call — see test_runner.clj/exit for why a resolve-guarded
      ;; version of this silently never exits.
      (System/exit 1))))
