(ns glitter.menu-button-popover-smoke
  "Automated smoke against the LIVE GTK tree for :menu-button and
  :popover — round 10's biggest architecture piece: the FIRST popup
  surface in this project, and the first hiccup RELATIONSHIP that isn't
  a normal append-child!-managed tree child.

  A :menu-button's ONE hiccup child (if present) is expected to be a
  :popover, attached via gtk_menu_button_set_popover — confirmed by
  reading its C body directly that this genuinely parents the popover
  (gtk_widget_set_parent/unparent), real ownership, not a passive
  reference. :popover is otherwise a normal single-child container
  (gtk_popover_set_child), same strategy as :frame/:revealer.

  Both signals turned out to be free reuses of the plain 2-arg-void
  shape already generalized in this project — confirmed via
  gtk/gtkmenubutton.c's and gtk/gtkpopover.c's own g_signal_new calls
  (G_TYPE_NONE, 0 for both \"activate\" and \"closed\") — no new
  callable branch needed in glitter.gtk/set-event-handler, unlike
  :notebook's/:scale-button's round-9 additions. :on-activate was
  already a registered signals entry (added speculatively in an earlier
  round); this is its first real use.

  :popover's :visible prop drives gtk_popover_popup/popdown through the
  usual suppressing-guard setter — a controlled-component contract
  identical to :notebook's :current-page: :menu-button's OWN internal
  click handling opens the popover independently of glitter (confirmed
  live: gtk_menu_button_set_popover wires its own \"closed\" listener
  separately from glitter's), so an app is expected to sync its own
  :visible state from :on-activate (open) and :on-closed (close) — this
  smoke plays that app's role explicitly.

  gtk_popover_popdown's underlying gtk_popover_hide vfunc is confirmed
  (by reading it directly) to emit \"closed\" SYNCHRONOUSLY as part of
  the same call, so no deferred wait is needed to observe it — same
  shape as every other value-bearing widget's synchronous-emission
  setter here. gtk_widget_activate on :menu-button, unlike GtkButton's
  own activation, does NOT need GtkButton's ~250ms press-animation
  delay (link_button_smoke.clj's own gotcha) — confirmed live: this
  smoke's real click still defers past window-realize as a matter of
  established caution, but the dispatch fires well within a much
  shorter window than :link-button ever needed."
  (:require [glitter.app :as app]
            [glitter.core :as core]
            [glitter.ffi :as g]
            [glitter.gtk :as gtk]))

(defonce state (atom {:popover-visible false}))
(defonce dispatch-count (atom 0))

(defn view [{:keys [popover-visible]}]
  [:box {:spacing 4}
   [:menu-button {:label "Menu" :on {:activate [[:action/activate]]}}
    [:popover {:visible popover-visible :has-arrow true :on {:closed [[:action/closed]]}}
     [:label {:label "popover-content"}]]]])

(defn execute-actions [_event actions]
  (doseq [[kind] actions]
    (swap! dispatch-count inc)
    (case kind
      :action/activate (swap! state assoc :popover-visible true)
      :action/closed   (swap! state assoc :popover-visible false)
      nil)))

(core/set-dispatch! execute-actions)

(defn -main [& _]
  (let [results (atom {})]
    (app/run
     (fn [window]
       (gtk/mount! window view state)
       ;; :window is a single-child container — the mounted [:box ...] is
       ;; window's ONE child, and :menu-button is box's one child in turn.
       (let [menu-button (g/gtk-widget-get-first-child (g/gtk-widget-get-first-child window))
             popover (g/gtk-menu-button-get-popover menu-button)]
         (swap! results assoc
                :popover-present-on-mount (some? popover)
                :popover-child-text-on-mount (g/gtk-label-get-text (g/gtk-popover-get-child popover))
                :popover-has-arrow-on-mount (pos? (g/gtk-popover-get-has-arrow popover))
                :popover-visible-on-mount (pos? (g/gtk-widget-get-visible popover)))
         ;; Real interaction: a genuine click via gtk_widget_activate,
         ;; deferred past window-realize (link_button_smoke.clj's own
         ;; timing precaution, applied here even though :menu-button
         ;; turned out not to need GtkButton's press-animation delay —
         ;; see ns docstring).
         (future
           (Thread/sleep 200)
           (app/on-gui
            (fn []
              (swap! results assoc :activate-returned (pos? (g/gtk-widget-activate menu-button)))
              (swap! results assoc
                     :state-popover-visible-after-activate (:popover-visible @state)
                     :dispatch-count-after-activate @dispatch-count)
              (future
                (Thread/sleep 200)
                (app/on-gui
                 (fn []
                   ;; Real interaction: direct FFI popdown, bypassing
                   ;; set-popover-visible!, so the actual "closed" signal
                   ;; fires synchronously.
                   (g/gtk-popover-popdown popover)
                   (swap! results assoc
                          :state-popover-visible-after-close (:popover-visible @state)
                          :popover-widget-visible-after-close (pos? (g/gtk-widget-get-visible popover))
                          :dispatch-count-after-close @dispatch-count)
                   ;; Programmatic sync-back elsewhere in the
                   ;; (hypothetical) app — the popover should follow via
                   ;; set-popover-visible!'s suppressing guard in BOTH
                   ;; directions, no spurious extra dispatch either way.
                   (swap! state assoc :popover-visible true)
                   (swap! results assoc
                          :popover-widget-visible-after-programmatic-open (pos? (g/gtk-widget-get-visible popover))
                          :dispatch-count-after-programmatic-open @dispatch-count)
                   (swap! state assoc :popover-visible false)
                   (swap! results assoc
                          :popover-widget-visible-after-programmatic-close (pos? (g/gtk-widget-get-visible popover))
                          :dispatch-count-after-programmatic-close @dispatch-count)))))))
         nil))
     :title "glitter menu-button+popover smoke" :width 320 :height 100
     :app-id "glitter.menu-button-popover-smoke" :auto-quit-ms 1200)
    (println :results (pr-str @results))
    (when-not (and (true? (:popover-present-on-mount @results))
                   (= "popover-content" (:popover-child-text-on-mount @results))
                   (true? (:popover-has-arrow-on-mount @results))
                   (false? (:popover-visible-on-mount @results))
                   (true? (:activate-returned @results))
                   (true? (:state-popover-visible-after-activate @results))
                   (= 1 (:dispatch-count-after-activate @results))
                   (false? (:state-popover-visible-after-close @results))
                   (false? (:popover-widget-visible-after-close @results))
                   (= 2 (:dispatch-count-after-close @results))
                   (true? (:popover-widget-visible-after-programmatic-open @results))
                   (= 2 (:dispatch-count-after-programmatic-open @results))
                   (false? (:popover-widget-visible-after-programmatic-close @results))
                   (= 2 (:dispatch-count-after-programmatic-close @results)))
      (println :FAIL "see :results above")
      ;; Direct call — see test_runner.clj/exit for why a resolve-guarded
      ;; version of this silently never exits.
      (System/exit 1))))
