(ns glitter.keyed
  "Automated keyed-reconciliation smoke against the LIVE GTK tree — mirrors
  glimmer's examples/glimmer/keyed.clj. Mounts a 3-item keyed list, reorders
  it, then reads GTK's actual child order back via
  gtk_widget_get_first_child/get_next_sibling (not glitter.gtk's own
  Clojure-side :children tracking) to prove the real gtk_box_insert_child_after
  calls actually landed correctly, not just that the algorithm's decisions
  were correct in the abstract."
  (:require [glitter.app :as app]
            [glitter.core :as core]
            [glitter.ffi :as g]
            [glitter.gtk :as gtk]))

(defonce state (atom {:order ["a" "b" "c"]}))

(def labels {"a" "Item A" "b" "Item B" "c" "Item C"})

(defn view [{:keys [order]}]
  (into [:box {:spacing 4}]
        (for [k order]
          [:label {:glitter/key k :label (labels k)}])))

(core/set-dispatch! (fn [_ _] nil))

(defn- gtk-child-labels
  "Walk the live GTK box's children via FFI and read back each :label
  widget's actual text — the ground truth, independent of any Clojure-side
  bookkeeping."
  [box-widget]
  (loop [child (g/gtk-widget-get-first-child box-widget) acc []]
    (if (or (nil? child) (zero? child))
      acc
      (recur (g/gtk-widget-get-next-sibling child)
             (conj acc (g/gtk-label-get-text child))))))

(defn -main [& _]
  (let [captured (atom nil)]
    (app/run
     (fn [window]
       (gtk/mount! window view state)
       ;; after initial mount, reorder, then read back the live GTK tree
       (reset! state {:order ["c" "a" "b"]})
       ;; :window is a single-child container (gtk_window_set_child) — the
       ;; mounted [:box ...] hiccup is window's ONE child, not window
       ;; itself. Descend one level (window -> box) before walking for
       ;; label children, or gtk-child-labels ends up treating the box
       ;; widget as if it were a label and fails GTK's internal
       ;; GTK_IS_LABEL() assertion.
       (reset! captured (gtk-child-labels (g/gtk-widget-get-first-child window))))
     :title "glitter keyed" :width 240 :height 160 :app-id "glitter.keyed"
     :auto-quit-ms 500)
    (println :final-order @captured)
    (when (not= ["Item C" "Item A" "Item B"] @captured)
      (println :FAIL "expected [Item C Item A Item B], got" @captured)
      ;; Robust exit, matching test_runner.clj's own fallback pattern —
      ;; found live-verified during review that a bare
      ;; ((resolve 'jolt.host/exit) 1) can resolve to nil in this file's
      ;; standalone -main execution path (unlike test_runner.clj's
      ;; context, where jolt.host is already loaded), throwing "class nil
      ;; cannot be cast to class clojure.lang.IFn" instead of exiting 1 —
      ;; masking the real :FAIL with a confusing secondary crash.
      (cond
        (resolve 'jolt.host/exit) ((resolve 'jolt.host/exit) 1)
        (resolve 'System/exit)    ((resolve 'System/exit) 1)
        :else nil))))
