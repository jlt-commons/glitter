(ns glitter.switch-smoke
  "Automated smoke against the LIVE GTK tree for :switch — the payoff of
  generalizing glitter.gtk/set-event-handler beyond the uniform
  void(widget, user_data) callable shape every other glitter signal uses.

  GtkSwitch's real interaction signal, \"state-set\", is
  gboolean (*)(GtkSwitch*, gboolean, gpointer) — 3 args, non-void return —
  confirmed against gtk/gtkswitch.c's g_signal_new call directly, the same
  discipline that first flagged this gap two widget-additions ago (see
  docs/guide/gtk-widget-layer.md's \"Surveyed and deliberately not added:
  GtkSwitch\" section, now followed by \"`:switch` — generalizing
  set-event-handler\" describing how this got resolved).

  jolt.ffi/foreign-callable's argtypes/rettype must be compile-time
  literals — verified live, twice, in throwaway probes isolated from this
  codebase, that passing them as a let-bound local (even holding the exact
  literal value) throws a compile-time error. glitter.gtk/set-event-handler
  branches explicitly on the GTK signal name and uses a separate literal
  foreign-callable call for \"state-set\", rather than a data-driven table
  lookup (which was the first design tried, and does not work).

  Covers the same three-part rigor scale_smoke.clj/toggle_level_smoke.clj
  established: a real interaction (direct FFI gtk_switch_set_active,
  bypassing set-switch-active! so the actual \"state-set\" signal fires)
  reaches *dispatch* with the correct value; a subsequent programmatic
  state change pushes the widget back in sync via set-switch-active!; and
  that programmatic push does NOT cause a second, spurious dispatch (the
  suppressing guard, now proven to also work for a 3-arg/non-void-return
  signal, not just the standard 2-arg-void ones)."
  (:require [glitter.app :as app]
            [glitter.core :as core]
            [glitter.ffi :as g]
            [glitter.gtk :as gtk]))

(defonce state (atom {:on false}))
(defonce dispatch-count (atom 0))

(defn view [{:keys [on]}]
  [:box {:spacing 4}
   [:switch {:active on :on {:state-set [[:action/set-on]]}}]])

(defn execute-actions [event actions]
  (doseq [[kind] actions]
    (case kind
      :action/set-on
      (do (swap! dispatch-count inc)
          (swap! state assoc :on (pos? (get-in event [:glitter/dom-event :glitter/value]))))
      nil)))

(core/set-dispatch! execute-actions)

(defn -main [& _]
  (let [results (atom {})]
    (app/run
     (fn [window]
       (gtk/mount! window view state)
       ;; :window is a single-child container — the mounted [:box ...] is
       ;; window's ONE child, and :switch is box's one child in turn.
       (let [sw (g/gtk-widget-get-first-child (g/gtk-widget-get-first-child window))]
         (swap! results assoc :active-on-mount (pos? (g/gtk-switch-get-active sw)))
         ;; Simulate a real interaction directly via FFI, bypassing
         ;; set-switch-active! so the real "state-set" signal fires.
         (g/gtk-switch-set-active sw 1)
         (swap! results assoc
                :state-on-after-toggle (:on @state)
                :dispatch-count-after-toggle @dispatch-count)
         ;; Programmatic state change elsewhere in the (hypothetical) app —
         ;; widget should follow, no spurious extra dispatch.
         (reset! state {:on false})
         (swap! results assoc
                :widget-active-after-programmatic (pos? (g/gtk-switch-get-active sw))
                :dispatch-count-after-programmatic @dispatch-count)))
     :title "glitter switch smoke" :width 200 :height 100
     :app-id "glitter.switch-smoke" :auto-quit-ms 500)
    (println :results (pr-str @results))
    (when-not (and (false? (:active-on-mount @results))
                   (true? (:state-on-after-toggle @results))
                   (= 1 (:dispatch-count-after-toggle @results))
                   (false? (:widget-active-after-programmatic @results))
                   (= 1 (:dispatch-count-after-programmatic @results)))
      (println :FAIL "see :results above")
      ;; Direct call — see test_runner.clj/exit for why a resolve-guarded
      ;; version of this silently never exits.
      (System/exit 1))))
