(ns glitter.main-thread-smoke
  "Automated cross-thread smoke: does a state change made from a NON-GTK-main
  thread actually reach the widgets?

  This is the example the design spec called for and the arc never wrote, and
  its absence is why the bug it now pins went unnoticed: glitter.gtk/mount!'s
  state-atom watcher used to call the reconciler synchronously on whatever
  thread performed the swap!, bypassing glitter.app's g_idle_add marshalling
  entirely. Every other example mutates state from inside `activate` or a
  signal callback — i.e. already on the GTK main thread — so none of them
  could ever have caught it. On macOS, off-main-thread widget mutation is an
  AppKit violation, and an nREPL-driven dev session (a headline Jolt workflow,
  and the whole reason post-to-gui exists) hits it on the very first swap!.

  Mutates from a `future`'s worker thread, then schedules the read-back
  through glitter.app/on-gui. g_idle_add sources run FIFO, so the render the
  watcher posted is guaranteed to have run by the time the read-back fires.

  The load-bearing assertion is WHICH THREAD the view function ran on, not
  merely that the label updated. An unmarshalled watcher still updates the
  label — it just does it from the worker thread, which is precisely the
  AppKit violation — so a text-only assertion would pass with the bug present
  and prove nothing. `view` therefore records its own thread, and the test
  requires it to equal the GTK main thread. Also asserts the worker really
  was a different thread, so the whole thing cannot pass vacuously if
  `future` ever ran inline."
  (:require [glitter.app :as app]
            [glitter.core :as core]
            [glitter.ffi :as g]
            [glitter.gtk :as gtk]))

(defonce state (atom {:txt "initial"}))

;; Which thread the reconciler last invoked `view` on — the actual property
;; under test. Reset to nil immediately before the cross-thread swap so the
;; initial mount's (correctly main-thread) render can't mask a bad one.
(defonce render-thread (atom nil))

(defn view [{:keys [txt]}]
  (reset! render-thread (Thread/currentThread))
  [:box {:spacing 4} [:label {:label txt}]])

(core/set-dispatch! (fn [_ _] nil))

(defn- first-label-text [window]
  (-> window g/gtk-widget-get-first-child g/gtk-widget-get-first-child
      g/gtk-label-get-text))

(defn -main [& _]
  (let [captured (atom nil)
        worker-differed? (atom nil)
        rendered-on-main? (atom nil)]
    (app/run
     (fn [window]
       (gtk/mount! window view state)
       (let [main-thread (Thread/currentThread)]
         (future
           ;; Let the loop settle, then mutate from OFF the main thread.
           ;; 300ms (was 100ms — widened during the final whole-branch
           ;; review's follow-up pass as a bigger margin against a loaded
           ;; CI box; there's no clean observable signal for "the main
           ;; loop is actively pumping g_idle_add sources" to poll on
           ;; instead) against the 1500ms auto-quit-ms budget below.
           (Thread/sleep 300)
           (reset! worker-differed? (not= main-thread (Thread/currentThread)))
           (reset! render-thread nil)
           (reset! state {:txt "from-worker"})
           ;; queued behind the render the watcher just posted
           (app/on-gui (fn []
                         (reset! captured (first-label-text window))
                         (reset! rendered-on-main? (= main-thread @render-thread)))))))
     :title "glitter main-thread smoke" :width 240 :height 160
     :app-id "glitter.main-thread-smoke" :auto-quit-ms 1500)
    (println :worker-was-a-different-thread @worker-differed?)
    (println :label-after-cross-thread-swap @captured)
    (println :render-ran-on-gtk-main-thread @rendered-on-main?)
    (when-not (and (true? @worker-differed?)
                   (= "from-worker" @captured)
                   (true? @rendered-on-main?))
      (println :FAIL "expected worker-differed=true, label=from-worker,"
               "rendered-on-main=true; got"
               @worker-differed? @captured @rendered-on-main?)
      ;; Direct call — see test_runner.clj/exit for why a resolve-guarded
      ;; version of this silently never exits.
      (System/exit 1))))
