(ns glitter.revealer-center-box-smoke
  "Automated smoke against the LIVE GTK tree for :revealer and :center-box —
  round 6's two container-shaped additions (:spin-button and :list-box, the
  round's two signal-bearing additions, get their own
  spin_button_list_box_smoke.clj).

  :revealer is display-only (no signal, same shape as :spinner/:level-bar):
  :reveal-child directly drives the animated show/hide, :transition-type
  resolves a GtkRevealerTransitionType nick via glitter.genum (same runtime
  lookup :halign/:valign already use for GtkAlign), :transition-duration is
  a plain millisecond uint. It's also a single-child container reusing the
  EXACT SAME container-management case-branch shape :frame/:scrolled
  already established — no new container logic needed there.

  :center-box is genuinely new container-management logic: GtkCenterBox has
  three fixed NAMED slots (start/center/end), not an ordered append list —
  center-box-append-child!/center-box-remove-child!/
  center-box-replace-child!/center-box-insert-after! (glitter.widget) query
  slot occupancy LIVE via the getters rather than tracking it separately.
  This smoke's re-render only exercises SAFE operations for :center-box: a
  props-only update on an already-:label slot, and dropping a slot's child
  entirely (pure removal). It deliberately does NOT swap a slot's hiccup
  TAG (e.g. :label -> :button) while all 3 slots are full — verified live,
  separately, that doing so corrupts a third, unrelated slot. The
  mechanism: GtkCenterBox has no transient capacity for a 4th simultaneous
  occupant the way :box's ordered list does, but glitter.core's reconciler
  handles a same-position tag mismatch as 'insert new, then remove old' —
  two separate steps — and glitter.gtk's own :children bookkeeping (shared,
  generic code updated unconditionally by IRender/insert-before) briefly
  assumes :box-like extra capacity that GtkCenterBox structurally doesn't
  have. See docs/guide/gtk-widget-layer.md and
  glitter.widget/center-box-insert-after!'s docstring for the full trace —
  :list-box does NOT share this problem (confirmed separately; it has
  per-item GtkListBoxRow capacity, not a fixed slot count)."
  (:require [glitter.app :as app]
            [glitter.core :as core]
            [glitter.ffi :as g]
            [glitter.gtk :as gtk]))

(defonce state (atom {:revealed false :center-text "C" :drop-end? false}))

(defn view [{:keys [revealed center-text drop-end?]}]
  [:box {:spacing 4}
   [:revealer {:reveal-child revealed :transition-type :crossfade :transition-duration 200}
    [:label {:label "hidden-content"}]]
   (into [:center-box
          [:label {:label "L"}]
          [:label {:label center-text}]]
         (when-not drop-end? [[:label {:label "R"}]]))])

(core/set-dispatch! (fn [_ _] nil))

(defn -main [& _]
  (let [results (atom {})]
    (app/run
     (fn [window]
       (gtk/mount! window view state)
       ;; :window is a single-child container — the mounted [:box ...] is
       ;; window's ONE child; :revealer and :center-box are box's two
       ;; children in sibling order.
       (let [box        (g/gtk-widget-get-first-child window)
             revealer   (g/gtk-widget-get-first-child box)
             center-box (g/gtk-widget-get-next-sibling revealer)]
         (swap! results assoc
                :reveal-on-mount (pos? (g/gtk-revealer-get-reveal-child revealer))
                :transition-type-on-mount (g/gtk-revealer-get-transition-type revealer)
                :transition-duration-on-mount (g/gtk-revealer-get-transition-duration revealer)
                :center-start-on-mount (g/gtk-label-get-text (g/gtk-center-box-get-start-widget center-box))
                :center-center-on-mount (g/gtk-label-get-text (g/gtk-center-box-get-center-widget center-box))
                :center-end-on-mount (g/gtk-label-get-text (g/gtk-center-box-get-end-widget center-box)))
         ;; Re-render: reveal the revealer, update ONLY center-box's center
         ;; slot's text (props-only — no tag change, safe).
         (swap! state assoc :revealed true :center-text "C2")
         (swap! results assoc
                :reveal-after-rerender (pos? (g/gtk-revealer-get-reveal-child revealer))
                :center-start-after-props-update (g/gtk-label-get-text (g/gtk-center-box-get-start-widget center-box))
                :center-center-after-props-update (g/gtk-label-get-text (g/gtk-center-box-get-center-widget center-box))
                :center-end-after-props-update (g/gtk-label-get-text (g/gtk-center-box-get-end-widget center-box)))
         ;; Re-render: drop the END slot's child entirely (pure removal).
         (swap! state assoc :drop-end? true)
         (let [end (g/gtk-center-box-get-end-widget center-box)]
           (swap! results assoc
                  :center-end-gone-after-drop (or (nil? end) (zero? end))
                  :center-start-after-drop (g/gtk-label-get-text (g/gtk-center-box-get-start-widget center-box))
                  :center-center-after-drop (g/gtk-label-get-text (g/gtk-center-box-get-center-widget center-box))))))
     :title "glitter revealer+center-box smoke" :width 300 :height 120
     :app-id "glitter.revealer-center-box-smoke" :auto-quit-ms 500)
    (println :results (pr-str @results))
    (when-not (and (false? (:reveal-on-mount @results))
                   (= 1 (:transition-type-on-mount @results))
                   (= 200 (:transition-duration-on-mount @results))
                   (= "L" (:center-start-on-mount @results))
                   (= "C" (:center-center-on-mount @results))
                   (= "R" (:center-end-on-mount @results))
                   (true? (:reveal-after-rerender @results))
                   (= "L" (:center-start-after-props-update @results))
                   (= "C2" (:center-center-after-props-update @results))
                   (= "R" (:center-end-after-props-update @results))
                   (true? (:center-end-gone-after-drop @results))
                   (= "L" (:center-start-after-drop @results))
                   (= "C2" (:center-center-after-drop @results)))
      (println :FAIL "see :results above")
      ;; Direct call — see test_runner.clj/exit for why a resolve-guarded
      ;; version of this silently never exits.
      (System/exit 1))))
