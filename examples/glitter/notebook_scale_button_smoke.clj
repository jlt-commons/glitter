(ns glitter.notebook-scale-button-smoke
  "Automated smoke against the LIVE GTK tree for :notebook and
  :scale-button — round 9's two bigger-architecture additions
  (:picture and :editable-label, the round's two smaller wins, get their
  own picture_editable_label_smoke.clj).

  :notebook is a tabbed container whose real interaction signal,
  \"switch-page\", is a SIXTH callable shape in this project — 4 args,
  void(GtkNotebook*, GtkWidget* page, guint page_num, gpointer),
  confirmed against gtk/gtknotebook.c's g_signal_new call — and the
  FIRST signal here that can't reuse the shared value-fn/dispatch! path:
  gtk_notebook_switch_page (the fn that emits the signal) only READS
  notebook->cur_page; the actual `cur_page = page` assignment happens in
  gtk_notebook_real_switch_page, the signal's OWN default class handler
  (G_SIGNAL_RUN_LAST), which runs AFTER user-connected handlers like
  glitter's — so re-reading gtk_notebook_get_current_page() the usual
  way would return the STALE previous page. Verified two ways, not just
  theorized: reading gtk_notebook_insert_page's C body directly, AND (in
  the throwaway probe this smoke was distilled from) capturing the raw
  dispatched page-num side-by-side with a same-tick getter read to
  confirm the getter really would have been stale.

  A SECOND, genuinely surprising real GTK behavior this smoke has to
  account for: gtk_notebook_insert_page's own C body auto-selects the
  first page added to an empty notebook —
  `if (!gtk_notebook_has_current_page (notebook))
     { gtk_notebook_switch_page (notebook, page); }`
  — which means mounting a :notebook with initial children genuinely
  DISPATCHES a \"switch-page\" action as a side effect of construction,
  before any simulated user interaction. This smoke's dispatch-count
  assertions start from 1 (not 0) after mount for exactly this reason —
  confirmed live via the probe's dispatch-log ordering showing the
  switch action FIRST, then root-caused by reading the C source, not
  assumed. Real GTK behavior, not a glitter bug; see
  docs/guide/gtk-widget-layer.md for the full write-up.

  :scale-button shares :scale's/:spin-button's exact GTK signal NAME
  (\"value-changed\") but has a genuinely different real C shape —
  void(GtkScaleButton*, double, gpointer), a 3-arg shape with a
  :double in the middle instead of the standard 2-arg-void default —
  confirmed against gtk/gtkscalebutton.c's g_signal_new call. The FIRST
  case in this project where signal name alone can't determine callable
  shape; glitter.gtk/set-event-handler's cond branches on the widget's
  own :tag as well as the signal name. Verified SAFE to still re-read
  via the usual getter (gtk_scale_button_get_value), unlike :notebook
  above: gtk/gtkscalebutton.c's cb_scale_value_changed (which emits the
  button's OWN \"value-changed\") reads gtk_range_get_value from the
  internal slider AFTER that slider's own signal already fired, and
  gtk_scale_button_get_value reads the SAME shared GtkAdjustment — so
  it's already current by the time any handler sees the button's own
  signal.

  No get-nth-page GTK API exists to read a notebook page's own child
  widget back out (only gtk_notebook_page_num, which needs a widget
  reference going IN) — so, like :overlay's own documented \"no
  enumerate\" gap, this smoke verifies :notebook only via
  gtk_notebook_get_current_page's int, not by reading page content."
  (:require [glitter.app :as app]
            [glitter.core :as core]
            [glitter.ffi :as g]
            [glitter.gtk :as gtk]))

(defonce state (atom {:current-page 0 :sb-value 0.0}))
(defonce dispatch-count (atom 0))

(defn view [{:keys [current-page sb-value]}]
  [:box {:spacing 4}
   [:notebook {:current-page current-page :on {:switch-page [[:action/switch]]}}
    [:label {:label "page0"}]
    [:label {:label "page1"}]
    [:label {:label "page2"}]]
   [:scale-button {:min 0 :max 100 :step 1 :value sb-value :on {:value-changed [[:action/set-sb]]}}]])

(defn execute-actions [event actions]
  (doseq [[kind] actions]
    (swap! dispatch-count inc)
    (case kind
      :action/switch (swap! state assoc :current-page (get-in event [:glitter/dom-event :glitter/value]))
      :action/set-sb (swap! state assoc :sb-value (get-in event [:glitter/dom-event :glitter/value]))
      nil)))

(core/set-dispatch! execute-actions)

(defn -main [& _]
  (let [results (atom {})]
    (app/run
     (fn [window]
       (gtk/mount! window view state)
       ;; :window is a single-child container — the mounted [:box ...] is
       ;; window's ONE child; :notebook and :scale-button are box's two
       ;; children in sibling order.
       (let [box (g/gtk-widget-get-first-child window)
             notebook (g/gtk-widget-get-first-child box)
             sb (g/gtk-widget-get-next-sibling notebook)]
         ;; Mount-time: appending the first of 3 pages auto-selects it
         ;; (see ns docstring) — current-page is already 0, and that
         ;; auto-select genuinely dispatched :action/switch once.
         (swap! results assoc
                :notebook-current-page-on-mount (g/gtk-notebook-get-current-page notebook)
                :dispatch-count-on-mount @dispatch-count
                :sb-value-on-mount (g/gtk-scale-button-get-value sb))
         ;; Real interaction: direct FFI, bypassing set-notebook-current-page!
         ;; so the actual "switch-page" signal fires, reading page-num from
         ;; the RAW signal argument (see ns docstring — not a stale getter).
         (g/gtk-notebook-set-current-page notebook 2)
         (swap! results assoc
                :state-current-page-after-switch (:current-page @state)
                :dispatch-count-after-switch @dispatch-count)
         ;; Real interaction: direct FFI, bypassing set-scale-button-value!
         ;; so the actual "value-changed" signal fires.
         (g/gtk-scale-button-set-value sb 42.0)
         (swap! results assoc
                :state-sb-value-after-drag (:sb-value @state)
                :dispatch-count-after-drag @dispatch-count)
         ;; Programmatic sync-back elsewhere in the (hypothetical) app —
         ;; both widgets should follow via their suppressing-guard
         ;; setters, no spurious extra dispatch.
         (reset! state {:current-page 0 :sb-value 0.0})
         (swap! results assoc
                :notebook-current-page-after-programmatic (g/gtk-notebook-get-current-page notebook)
                :sb-value-after-programmatic (g/gtk-scale-button-get-value sb)
                :dispatch-count-after-programmatic @dispatch-count)))
     :title "glitter notebook+scale-button smoke" :width 320 :height 140
     :app-id "glitter.notebook-scale-button-smoke" :auto-quit-ms 500)
    (println :results (pr-str @results))
    (when-not (and (= 0 (:notebook-current-page-on-mount @results))
                   (= 1 (:dispatch-count-on-mount @results))
                   (= 0.0 (:sb-value-on-mount @results))
                   (= 2 (:state-current-page-after-switch @results))
                   (= 2 (:dispatch-count-after-switch @results))
                   (= 42.0 (:state-sb-value-after-drag @results))
                   (= 3 (:dispatch-count-after-drag @results))
                   (= 0 (:notebook-current-page-after-programmatic @results))
                   (= 0.0 (:sb-value-after-programmatic @results))
                   (= 3 (:dispatch-count-after-programmatic @results)))
      (println :FAIL "see :results above")
      ;; Direct call — see test_runner.clj/exit for why a resolve-guarded
      ;; version of this silently never exits.
      (System/exit 1))))
