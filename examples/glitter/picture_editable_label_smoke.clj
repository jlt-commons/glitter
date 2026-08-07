(ns glitter.picture-editable-label-smoke
  "Automated smoke against the LIVE GTK tree for :picture and
  :editable-label — two of round 9's four additions (:notebook and
  :scale-button, the round's bigger architecture pieces, get their own
  notebook_scale_button_smoke.clj).

  :picture has no signal at all (confirmed: no g_signal_new in
  gtk/gtkpicture.c) — a modernized :image (GdkPaintable-based, :content-fit
  for scaling instead of an icon-name/file choice), purely display-only,
  same shape as :spinner/:progress-bar/:image/:level-bar. This smoke drives
  it entirely through re-applied props (:content-fit, :can-shrink,
  :alternative-text), no interaction to simulate.

  :editable-label is a THIRD widget implementing GtkEditable via a delegate
  (after :password-entry/:search-entry — confirmed against
  gtk/gtkeditablelabel.c's gtk_editable_init_delegate call), reusing
  set-entry-text!/:entry's \"changed\" signal NAME for free, but needing its
  own [:editable-label \"changed\"] signal-value entry — the SAME
  near-miss as round 7's :password-entry (a comment said 'applied
  proactively this time' and then the entry was STILL forgotten on the
  first pass; caught by this exact scenario before it shipped, not
  assumed safe from the comment alone).

  Deliberately starts :text EMPTY (matching :password-entry/:search-entry's
  own precedent in password_search_entry_smoke.clj), which sidesteps a
  separate, real GtkEditable finding this round surfaced: gtk_editable_set_text
  is NOT a single atomic mutation — gtk/gtkeditable.c's body calls
  gtk_editable_delete_text(editable, 0, -1) then gtk_editable_insert_text(...),
  two separate calls, and only property `notify` (not \"changed\" itself) is
  frozen/thawed around them. gtk/gtktext.c's gtk_text_delete_text has an
  early return (`if (start_pos == end_pos) return;`) when the buffer is
  ALREADY empty — so replacing EMPTY text fires \"changed\" ONCE (delete is a
  no-op), but replacing NON-EMPTY text fires it TWICE (delete and insert each
  emit separately). This is a real, general GtkEditable behavior affecting
  every widget built on the delegate (:entry/:password-entry/:search-entry/
  :editable-label alike), NOT a glitter bug, and NOT specific to
  :editable-label — it only surfaces when simulating a bulk replace-all-text
  interaction (this project's usual 'bypass the wrapper, trigger the real
  signal' smoke technique) on a widget that already holds non-empty text; a
  real user typing character-by-character never hits this path (that goes
  through gtk_editable_insert_text directly, once per keystroke). Every
  GtkEditable-family smoke in this project starts its interaction target
  from empty text specifically to avoid asserting on this incidental
  double-emission rather than the behavior under test. See
  docs/guide/gtk-widget-layer.md for the full write-up."
  (:require [glitter.app :as app]
            [glitter.core :as core]
            [glitter.ffi :as g]
            [glitter.genum :as genum]
            [glitter.gtk :as gtk]))

(defonce state (atom {:label "" :fit :contain :shrink true :editing false}))
(defonce dispatch-count (atom 0))

(defn view [{:keys [label fit shrink editing]}]
  [:box {:spacing 4}
   [:picture {:content-fit fit :can-shrink shrink :alternative-text "a picture"}]
   [:editable-label {:text label :editing editing :on {:change [[:action/set-label]]}}]])

(defn execute-actions [event actions]
  (doseq [[kind] actions]
    (swap! dispatch-count inc)
    (case kind
      :action/set-label (swap! state assoc :label (get-in event [:glitter/dom-event :glitter/value]))
      nil)))

(core/set-dispatch! execute-actions)

(defn -main [& _]
  (let [results (atom {})]
    (app/run
     (fn [window]
       (gtk/mount! window view state)
       ;; :window is a single-child container — the mounted [:box ...] is
       ;; window's ONE child; :picture and :editable-label are box's two
       ;; children in sibling order.
       (let [box (g/gtk-widget-get-first-child window)
             picture (g/gtk-widget-get-first-child box)
             label (g/gtk-widget-get-next-sibling picture)]
         (swap! results assoc
                :picture-alt-text-on-mount (g/gtk-picture-get-alternative-text picture)
                :picture-can-shrink-on-mount (pos? (g/gtk-picture-get-can-shrink picture))
                :label-text-on-mount (g/gtk-editable-get-text label)
                :label-editing-on-mount (pos? (g/gtk-editable-label-get-editing label)))
         ;; Real interaction: direct FFI, bypassing set-entry-text! so the
         ;; actual "changed" signal fires. :text starts empty (see ns
         ;; docstring), so this is ONE emission, not two.
         (g/gtk-editable-set-text label "typed")
         (swap! results assoc
                :state-label-after-type (:label @state)
                :dispatch-count-after-type @dispatch-count)
         ;; Re-render: change :content-fit, a plain re-applied prop, no
         ;; signal involved. gtk_picture_get_content_fit returns the raw
         ;; GEnum int, not a resolved nick — genum/enum has no reverse
         ;; (int -> keyword) lookup, so resolve :cover through the SAME
         ;; forward path :apply itself uses and compare ints, rather than
         ;; hardcoding GTK_CONTENT_FIT_COVER's numeric value.
         (swap! state assoc :fit :cover)
         (swap! results assoc
                :picture-fit-after-rerender (g/gtk-picture-get-content-fit picture)
                :picture-fit-cover-int (genum/enum "GtkContentFit" :cover))
         ;; Programmatic sync-back elsewhere in the (hypothetical) app —
         ;; the label should follow via set-editable-label-editing!'s
         ;; suppressing guard, no spurious extra dispatch. Also proves
         ;; set-editable-label-editing! itself (start-editing), not just
         ;; the underlying set-entry-text! path.
         (swap! state assoc :editing true)
         (swap! results assoc
                :label-editing-after-programmatic (pos? (g/gtk-editable-label-get-editing label))
                :dispatch-count-after-programmatic @dispatch-count)))
     :title "glitter picture+editable-label smoke" :width 320 :height 100
     :app-id "glitter.picture-editable-label-smoke" :auto-quit-ms 500)
    (println :results (pr-str @results))
    (when-not (and (= "a picture" (:picture-alt-text-on-mount @results))
                   (true? (:picture-can-shrink-on-mount @results))
                   (= "" (:label-text-on-mount @results))
                   (false? (:label-editing-on-mount @results))
                   (= "typed" (:state-label-after-type @results))
                   (= 1 (:dispatch-count-after-type @results))
                   (= (:picture-fit-cover-int @results) (:picture-fit-after-rerender @results))
                   (true? (:label-editing-after-programmatic @results))
                   (= 1 (:dispatch-count-after-programmatic @results)))
      (println :FAIL "see :results above")
      ;; Direct call — see test_runner.clj/exit for why a resolve-guarded
      ;; version of this silently never exits.
      (System/exit 1))))
