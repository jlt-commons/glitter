(ns glitter.timer
  "The 7GUIs 'Timer' task (https://eugenkiss.github.io/7guis/tasks/#timer)
  over glitter — a duration slider, an elapsed-time progress bar/label
  that advances on its own (not driven by user input), and a Reset
  button. Per the spec: 'the elapsed time needs to be updated
  periodically... a common approach is to use a timer.'

  Fourth glitter.nexus consumer (after flights.clj, crud.clj/todo.clj,
  temperature.clj) and the FIRST demo whose state changes on its own,
  without any user interaction. Two genuinely new pieces this demo
  needed:

  1. A background tick, via a new demo-local :effect/schedule effect —
     built on glitter.ffi/g-timeout-add + glitter.widget's
     retain-callable!/release-callable!, the EXACT one-shot
     schedule-then-release pattern glitter.app already uses for its own
     :auto-quit-ms option and post-to-gui's idle-callback marshalling
     (see glitter/app.clj) — no new glitter.ffi/glitter.widget/
     glitter.gtk code needed, g_timeout_add was already bound and
     already proven live. :effect/schedule's callback re-dispatches
     [:action/tick], which re-registers ANOTHER one-shot 100ms timeout —
     self-perpetuating, same shape as upstream's js/setTimeout-based
     version.

  2. A live clock reading on every dispatch. This demo's
     :nexus/system->state is NOT the bare `deref` every other
     glitter.nexus consumer in this project uses (crud.clj/todo.clj/
     temperature.clj) — it augments the dereffed atom with a fresh
     :now on every single dispatch: (fn [system] (assoc @system :now
     (System/nanoTime))). System/nanoTime, not System/currentTimeMillis
     or a wall-clock/tick.core Instant, following action-log.clj's own
     already-live-verified precedent (see its now-ms) for exactly the
     same reason: wall-clock time can jump (NTP adjustment); this timer
     only cares about ELAPSED DURATION since a reset point within one
     process run, which nanoTime's monotonic guarantee is built for.

     This :now augmentation is consumed by :action/tick's own
     action-expansion (dispatch-time — (fn [state] ... (:now state)
     ...) reads it to stamp :last-tick), NOT by view (render-time).
     glitter.gtk/mount!'s add-watch re-renders view directly off the
     raw new value of the watched state atom (src/glitter/gtk.clj) —
     entirely independent of glitter.nexus's dispatch machinery, and
     unaware :nexus/system->state even exists. Since the state atom
     itself never stores a :now key (only :started/:duration/
     :last-tick are ever written via :effect/assoc-in), an earlier
     version of view that trusted (:now state) directly threw a live
     NullPointerException on every tick (confirmed: 63 occurrences
     over an 8-second live run) — swallowed by glitter.nexus's own
     on-error handling, so the process didn't crash, but core/reconcile
     never ran, and the display never advanced past its initial paint.
     view instead reads System/nanoTime directly itself, the same
     live-fresh-read-at-render-time pattern flights.clj's
     get-form-state already established for (t/today) — see that fn's
     own comment ('today is read fresh each render... rather than
     snapshotted once at namespace-load time'). :nexus/system->state's
     :now registration stays exactly as it was for :action/tick's own
     sake — it's a dispatch-time mechanism, not a rendering one.

  The :action/tick expansion also writes a :last-tick key nothing ever
  READS — ported deliberately, not dead code: mount!'s re-render watcher
  (glitter/gtk.clj) only fires on an actual swap!/reset! of the state
  atom (plain add-watch semantics), and :effect/schedule's own
  10-times-a-second re-scheduling never itself touches state — so
  WITHOUT some :effect/assoc-in call every tick, elapsed time would be
  correctly recomputed internally on every dispatch but the screen would
  never actually repaint. :last-tick's WRITE, not its (nonexistent)
  READ, is the payload.

  Known accepted risk, not engineered around (matches this project's
  general documented-not-fixed v1 gap pattern): a scheduled tick can fire
  in the narrow window between the app window closing and the process
  exiting, attempting one last dispatch into an app that's mid-teardown.
  Every demo in this project already has no explicit unmount path (see
  docs/guide/limitations.md); Timer is simply the first with a
  background source that could still be pending when that gap opens.

  Ports the domain-logic SHAPE (get-view-state, format-seconds) from
  guis/timer.cljc in cjohansen/replicant-7uis — behaviorally identical,
  but state keys are plain (:duration/:started), not ::-namespaced,
  matching every sibling demo in this project; and on-load-actions fire
  via one direct dispatch call right after mount (this project's demos
  are one-view-per-process, unlike the reference's multi-tab SPA, so its
  view-switching on-load-trigger machinery doesn't apply here at all).

  Run: jolt -M:timer (the :timer task/alias) or bb timer. Needs a
  display; closes the window to exit."
  (:require [clojure.tools.logging :as log]
            [glitter.app :as app]
            [glitter.core :as core]
            [glitter.ffi :as g]
            [glitter.gtk :as gtk]
            [glitter.nexus.registry :as nxr]
            [glitter.widget :as w]
            [jolt.ffi :as ffi]))

(defn format-seconds [s]
  (let [s10 (int (* 10 s))]
    (if (= 0 (mod s10 10))
      (int (/ s10 10))
      (float (/ s10 10)))))

(defn get-view-state [state]
  (let [duration (or (:duration state) 20)
        elapsed (min (if-let [started (:started state)]
                       (/ (- (:now state) started) 1.0e9)
                       0)
                     duration)]
    {:pct (int (* 100 (/ elapsed duration)))
     :elapsed (str (format-seconds elapsed) "s")
     :duration duration}))

(defonce state
  (atom {}))

(defn view [state]
  (let [{:keys [pct duration elapsed]} (get-view-state (assoc state :now (System/nanoTime)))]
    [:vbox {:spacing 12 :margin 16}
     [:label {:markup "<span size='xx-large' weight='bold'>Timer</span>" :halign :start}]
     [:hbox {:spacing 8}
      [:label {:label "Elapsed time:" :width-chars 14 :xalign 0.0}]
      [:progress-bar {:fraction (/ pct 100.0) :hexpand true}]]
     [:label {:label elapsed :halign :start}]
     [:hbox {:spacing 8}
      [:label {:label "Duration:" :width-chars 14 :xalign 0.0}]
      [:scale {:min 0 :max 100 :step 1 :value duration :digits 0 :hexpand true
               :on {:value-changed [[:effect/assoc-in [:duration] [:glitter/value]]]}}]]
     [:button {:label "Reset" :on {:click [[:action/reset]]}}]]))

(nxr/register-effect! :effect/assoc-in
                      (fn [_ system path v] (swap! system assoc-in path v)))

;; Built on already-proven primitives — see glitter/app.clj's :auto-quit-ms
;; and post-to-gui for the exact same one-shot schedule-then-release shape.
(nxr/register-effect! :effect/schedule
                      (fn [{:keys [dispatch]} _ ms actions]
                        (let [slot (atom nil)]
                          (reset! slot (ffi/foreign-callable
                                        (fn [_data]
                                          (let [cb @slot]
                                            (try (dispatch actions)
                                                 (finally (w/release-callable! cb))))
                                          0)
                                        [:pointer] :int :collect-safe))
                          (w/retain-callable! @slot)
                          (g/g-timeout-add ms @slot ffi/null))))

(nxr/register-placeholder! :glitter/value
                           (fn [event] (get-in event [:glitter/dom-event :glitter/value])))

(nxr/register-placeholder! :clock/now (fn [_] (System/nanoTime)))

(nxr/register-action! :action/reset
                      (fn [_state] [[:effect/assoc-in [:started] [:clock/now]]]))

(nxr/register-action! :action/tick
                      (fn [state] [[:effect/schedule 100
                                    [[:effect/assoc-in [:last-tick] (:now state)]
                                     [:action/tick]]]]))

(nxr/register-system->state! (fn [system] (assoc @system :now (System/nanoTime))))

(nxr/on-error (fn [_ctx {:keys [err] :as error}]
                (log/error err "glitter.nexus dispatch error" (dissoc error :err))))

(core/set-dispatch!
 (fn [event actions] (nxr/dispatch state event actions)))

(defn -main [& _]
  (app/run (fn [window]
             (gtk/mount! window view state)
             (nxr/dispatch state nil [[:effect/assoc-in [:started] [:clock/now]]
                                      [:action/tick]]))
           :title "glitter · Timer" :width 360 :height 180 :app-id "glitter.timer"))
