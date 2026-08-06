(ns glitter.link-button-smoke
  "Automated smoke against the LIVE GTK tree for :link-button — the second
  widget (after :toggle-button) that reuses an existing glitter signal
  entry verbatim rather than needing new wiring. GtkLinkButton extends
  GtkButton (gtk/gtklinkbutton.h includes gtk/gtkbutton.h — the standard
  parent-class header pattern), so its \"clicked\" signal is exactly the
  one :on-click already resolves to.

  The real-click simulation surfaced a genuine GTK4 timing gotcha, pinned
  here rather than just noted in a comment: gtk_widget_activate (the
  public function that simulates a real Enter/Space activation) does NOT
  synchronously emit \"clicked\" for a GtkButton. Traced through
  gtk/gtkbutton.c: gtk_real_button_activate only schedules its
  ~250ms press-animation timeout `if (gtk_widget_get_realized (widget)
  ...)`   — activating before the containing window is realized (i.e.
  before gtk_window_present has run and the main loop has processed it)
  is a silent no-op that still returns TRUE (TRUE only means \"an
  activate-signal handler ran\", not \"clicked will follow\"). Both the
  activate call and the result check are deferred via a future + on-gui,
  giving the window time to present and realize first — the same
  cross-thread-marshalling primitives main_thread_smoke.clj already
  established, applied to a different timing problem."
  (:require [glitter.app :as app]
            [glitter.core :as core]
            [glitter.ffi :as g]
            [glitter.gtk :as gtk]))

(defonce state (atom {:clicks 0}))

(defn view [{:keys [clicks]}]
  [:box {:spacing 4}
   [:link-button {:uri "https://example.com" :label (str "clicks:" clicks)
                  :on {:click [[:action/click]]}}]])

(defn execute-actions [_event actions]
  (doseq [[kind] actions]
    (case kind :action/click (swap! state update :clicks inc) nil)))

(core/set-dispatch! execute-actions)

(defn -main [& _]
  (let [results (atom {})]
    (app/run
     (fn [window]
       (gtk/mount! window view state)
       ;; :window is a single-child container — the mounted [:box ...] is
       ;; window's ONE child, and :link-button is box's one child in turn.
       (let [link (g/gtk-widget-get-first-child (g/gtk-widget-get-first-child window))]
         (swap! results assoc :uri-on-mount (g/gtk-link-button-get-uri link))
         (future
           ;; Let the window present/realize before activating — see the
           ;; ns docstring for why activating too early is a silent no-op.
           (Thread/sleep 200)
           (app/on-gui
            (fn []
              (swap! results assoc :activate-returned (pos? (g/gtk-widget-activate link)))
              (future
                ;; GtkButton's own press-animation timeout (ACTIVATE_TIMEOUT
                ;; = 250ms in gtk/gtkbutton.c) delays "clicked" further —
                ;; wait past it before checking.
                (Thread/sleep 400)
                (app/on-gui (fn [] (swap! results assoc :clicks-after-delay (:clicks @state))))))))))
     :title "glitter link-button smoke" :width 200 :height 100
     :app-id "glitter.link-button-smoke" :auto-quit-ms 1000)
    (println :results (pr-str @results))
    (when-not (and (= "https://example.com" (:uri-on-mount @results))
                   (true? (:activate-returned @results))
                   (= 1 (:clicks-after-delay @results)))
      (println :FAIL "see :results above")
      ;; Direct call — see test_runner.clj/exit for why a resolve-guarded
      ;; version of this silently never exits.
      (System/exit 1))))
