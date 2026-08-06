(ns glitter.class-smoke
  "Automated :class smoke against the LIVE GTK tree — pins glitter.gtk's
  add-class/remove-class wiring to real GTK CSS classes
  (gtk_widget_add_css_class/gtk_widget_remove_css_class), which used to be
  hardcoded no-ops (see docs/guide/gtk-widget-layer.md and NOTICE.md's v1
  status notes prior to this).

  Reads GTK's actual class membership back via gtk_widget_has_css_class —
  not any Clojure-side bookkeeping — because that's the only ground truth
  that proves the FFI calls actually landed, not just that glitter.core's
  diff decided to call add-class/remove-class in the abstract.

  Covers three things a scratch probe confirmed manually before this was
  written: a built-in GTK class (\"flat\", ships with zero app-provided
  CSS) applies on mount; a custom class name applies identically; and,
  critically, a class DROPPED from a re-render's :class set is actually
  removed from the live widget, not just never re-added — glitter.core's
  update-classes diffs old vs. new class sets and calls remove-class for
  anything missing from the new set, and this is what proves that call
  chain reaches GTK, not just glitter's own diff bookkeeping."
  (:require [glitter.app :as app]
            [glitter.core :as core]
            [glitter.ffi :as g]
            [glitter.gtk :as gtk]))

(defonce state (atom {:classes #{"flat" "custom-one"}}))

(defn view [{:keys [classes]}]
  [:box {:spacing 4}
   [:button {:label "hi" :class classes}]])

(core/set-dispatch! (fn [_ _] nil))

(defn- has-class? [widget name]
  (pos? (g/gtk-widget-has-css-class widget name)))

(defn -main [& _]
  (let [results (atom {})]
    (app/run
     (fn [window]
       (gtk/mount! window view state)
       ;; :window is a single-child container — the mounted [:box ...] is
       ;; window's ONE child, and :button is box's one child in turn.
       (let [button (g/gtk-widget-get-first-child (g/gtk-widget-get-first-child window))]
         (swap! results assoc
                :flat-on-mount   (has-class? button "flat")
                :custom-on-mount (has-class? button "custom-one")
                :absent-on-mount (has-class? button "never-added"))
         ;; A real diff: drop "custom-one", keep "flat", add "new-one".
         (reset! state {:classes #{"flat" "new-one"}})
         (swap! results assoc
                :flat-after-update   (has-class? button "flat")
                :custom-after-update (has-class? button "custom-one")
                :new-after-update    (has-class? button "new-one"))))
     :title "glitter class smoke" :width 200 :height 100 :app-id "glitter.class-smoke"
     :auto-quit-ms 500)
    (println :results (pr-str @results))
    (when-not (and (:flat-on-mount @results)
                   (:custom-on-mount @results)
                   (not (:absent-on-mount @results))
                   (:flat-after-update @results)
                   (not (:custom-after-update @results))
                   (:new-after-update @results))
      (println :FAIL "see :results above")
      ;; Direct call — see test_runner.clj/exit for why a resolve-guarded
      ;; version of this silently never exits.
      (System/exit 1))))
