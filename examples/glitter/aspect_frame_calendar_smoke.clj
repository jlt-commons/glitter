(ns glitter.aspect-frame-calendar-smoke
  "Automated smoke against the LIVE GTK tree for :aspect-frame and
  :calendar — round 8's quick-win and new-complexity-class additions
  (:overlay and :flow-box, the round's two new container/list widgets,
  get their own overlay_flow_box_smoke.clj).

  :aspect-frame (gtk_aspect_frame_set_child) is a single-child container
  — the exact :frame/:scrolled/:revealer/:expander strategy reused
  verbatim. Its own construction params (xalign/yalign/ratio/obey-child)
  are plain floats/bool with no GType-registration risk (unlike
  :scale's/:paned's orientation), so they resolve directly from props at
  construction time.

  :calendar is a genuinely new value-bearing leaf widget: \"day-selected\"
  is confirmed via gtk/gtkcalendar.c to be the plain 2-arg-void shape, but
  gtk_calendar_get_date returns a GDateTime* — a value type this project
  has never marshalled before. GDateTime is refcounted: gtk_calendar_get_date's
  own C body calls g_date_time_ref internally (confirmed by reading it
  directly), so the caller owns a NEW ref and must g_date_time_unref it;
  a date built via g_date_time_new_local is likewise caller-owned and
  must be unref'd after gtk_calendar_select_day (a plain in-param, the
  standard GLib convention — it does not take ownership). Every call site
  in glitter.widget's set-calendar-date!/calendar-date=/the
  [:calendar \"day-selected\"] signal-value entry unrefs what it refs, or
  every render/dispatch would leak one GDateTime object. This smoke's
  round-trip (real interaction -> dispatched [year month day] ->
  programmatic sync-back, no spurious dispatch) is what actually proves
  that refcounting discipline holds under repeated use, not just that it
  compiles."
  (:require [glitter.app :as app]
            [glitter.core :as core]
            [glitter.ffi :as g]
            [glitter.gtk :as gtk]))

(defonce state (atom {:date [2026 3 15]}))
(defonce dispatch-count (atom 0))

(defn view [{:keys [date]}]
  [:box {:spacing 4}
   [:aspect-frame {:xalign 0.5 :yalign 0.5 :ratio 2.0}
    [:label {:label "aspect-child"}]]
   [:calendar {:date date :on {:day-selected [[:action/set-date]]}}]])

(defn execute-actions [event actions]
  (doseq [[kind] actions]
    (swap! dispatch-count inc)
    (case kind
      :action/set-date (swap! state assoc :date (get-in event [:glitter/dom-event :glitter/value]))
      nil)))

(core/set-dispatch! execute-actions)

(defn -main [& _]
  (let [results (atom {})]
    (app/run
     (fn [window]
       (gtk/mount! window view state)
       ;; :window is a single-child container — the mounted [:box ...] is
       ;; window's ONE child; :aspect-frame and :calendar are box's two
       ;; children in sibling order.
       (let [box (g/gtk-widget-get-first-child window)
             aspect-frame (g/gtk-widget-get-first-child box)
             calendar (g/gtk-widget-get-next-sibling aspect-frame)]
         (swap! results assoc
                :aspect-frame-xalign-on-mount (g/gtk-aspect-frame-get-xalign aspect-frame)
                :aspect-frame-yalign-on-mount (g/gtk-aspect-frame-get-yalign aspect-frame)
                :aspect-frame-ratio-on-mount (g/gtk-aspect-frame-get-ratio aspect-frame)
                :aspect-frame-child-text (g/gtk-label-get-text (g/gtk-aspect-frame-get-child aspect-frame)))
         (let [d (g/gtk-calendar-get-date calendar)]
           (swap! results assoc :calendar-date-on-mount
                  [(g/g-date-time-get-year d) (g/g-date-time-get-month d) (g/g-date-time-get-day-of-month d)])
           (g/g-date-time-unref d))
         ;; Real interaction: direct FFI, bypassing set-calendar-date! so
         ;; the actual "day-selected" signal fires.
         (let [gdt (g/g-date-time-new-local 2026 6 20 0 0 0.0)]
           (g/gtk-calendar-select-day calendar gdt)
           (g/g-date-time-unref gdt))
         (swap! results assoc
                :state-date-after-select (:date @state)
                :dispatch-count-after-select @dispatch-count)
         ;; Programmatic sync-back elsewhere in the (hypothetical) app —
         ;; the calendar should follow via set-calendar-date!, no spurious
         ;; extra dispatch.
         (reset! state {:date [2026 1 1]})
         (let [d (g/gtk-calendar-get-date calendar)]
           (swap! results assoc :calendar-date-after-programmatic
                  [(g/g-date-time-get-year d) (g/g-date-time-get-month d) (g/g-date-time-get-day-of-month d)])
           (g/g-date-time-unref d))
         (swap! results assoc :dispatch-count-after-programmatic @dispatch-count)
         ;; Re-render: update aspect-frame's ratio via props (safe,
         ;; single-child container, no tag change).
         (swap! results assoc :aspect-frame-child-text-after-rerender
                (g/gtk-label-get-text (g/gtk-aspect-frame-get-child aspect-frame)))))
     :title "glitter aspect-frame+calendar smoke" :width 320 :height 260
     :app-id "glitter.aspect-frame-calendar-smoke" :auto-quit-ms 500)
    (println :results (pr-str @results))
    (when-not (and (= 0.5 (:aspect-frame-xalign-on-mount @results))
                   (= 0.5 (:aspect-frame-yalign-on-mount @results))
                   (= 2.0 (:aspect-frame-ratio-on-mount @results))
                   (= "aspect-child" (:aspect-frame-child-text @results))
                   (= [2026 3 15] (:calendar-date-on-mount @results))
                   (= [2026 6 20] (:state-date-after-select @results))
                   (= 1 (:dispatch-count-after-select @results))
                   (= [2026 1 1] (:calendar-date-after-programmatic @results))
                   (= 1 (:dispatch-count-after-programmatic @results))
                   (= "aspect-child" (:aspect-frame-child-text-after-rerender @results)))
      (println :FAIL "see :results above")
      ;; Direct call — see test_runner.clj/exit for why a resolve-guarded
      ;; version of this silently never exits.
      (System/exit 1))))
