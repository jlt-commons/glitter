(ns glitter.spin-button-list-box-smoke
  "Automated smoke against the LIVE GTK tree for :spin-button and :list-box —
  round 6's two signal-bearing additions (:revealer and :center-box, the
  round's two container-shaped additions, get their own
  revealer_center_box_smoke.clj).

  :spin-button's \"value-changed\" signal is confirmed via
  gtk/gtkspinbutton.c's g_signal_new to be the plain 2-arg-void shape every
  widget except :switch/:list-box already uses — but it's the SAME SIGNAL
  NAME :scale already uses, read back through a DIFFERENT getter
  (gtk_spin_button_get_value, not gtk_range_get_value). Before this smoke
  existed, glitter.widget/signal-value was keyed by bare GTK signal name —
  adding :spin-button that way would have silently clobbered :scale's entry
  (or vice versa). glitter.widget/signal-value is now keyed by
  [tag signal] instead — found live, WHILE adding :spin-button, before it
  ever shipped and broke :scale. This smoke's spin-button assertions are
  what pin that the fix actually works: the dispatched value is read via
  the RIGHT getter for THIS widget type, and scale_smoke.clj (already
  green) confirms :scale's own entry is undisturbed.

  :list-box's \"row-selected\"/\"row-activated\" are void(GtkListBox*,
  GtkListBoxRow*, gpointer) — 3 args, VOID return, a THIRD distinct
  callable shape from :switch's \"state-set\" (3-arg, non-void). Its
  value-fn re-reads gtk_list_box_get_selected_row -> row_get_index AFTER
  the signal fires, same pattern as every other value-bearing signal here.
  This smoke also pins a real container-management fix: :list-box (like
  :center-box) needed glitter.widget's insert-child-after!/reorder-child!
  actually implemented, not left as a documented no-op — a same-position
  hiccup TAG SWAP (e.g. :label -> :button at a fixed list-box position)
  goes through 'insert new, then remove old', and leaving that a no-op
  desyncs glitter.gtk's own :children bookkeeping from live GTK state,
  corrupting an unrelated sibling row. And a genuinely separate GTK
  gotcha, found live: removing the CURRENTLY SELECTED row fires a real,
  synchronous \"row-selected\" signal with a NULL row (GTK's own
  deselection notice) — left unsuppressed this dispatches a spurious
  deselection that can reenter core/reconcile mid-reconcile (mount!'s
  watcher runs inline when already on the GTK main thread). Both fixes are
  exercised below: swap row1's tag, then remove row1 itself (the row
  selected earlier in this same run) and confirm the select-dispatch count
  does NOT increment from that removal."
  (:require [glitter.app :as app]
            [glitter.core :as core]
            [glitter.ffi :as g]
            [glitter.gtk :as gtk]))

(defonce state (atom {:count 5.0 :selected nil :row1-state :label}))
(defonce select-dispatch-count (atom 0))

(defn view [{:keys [count row1-state]}]
  [:box {:spacing 4}
   [:spin-button {:min 0 :max 10 :step 1 :value count :on {:value-changed [[:action/set-count]]}}]
   (into [:list-box {:on {:row-selected [[:action/select]]}}
          [:label {:label "row0"}]]
         (case row1-state
           :label  [[:label {:label "row1"}] [:label {:label "row2"}]]
           :button [[:button {:label "row1-swapped"}] [:label {:label "row2"}]]
           :gone   [[:label {:label "row2"}]]))])

(defn execute-actions [event actions]
  (doseq [[kind] actions]
    (case kind
      :action/set-count (swap! state assoc :count (get-in event [:glitter/dom-event :glitter/value]))
      :action/select    (do (swap! select-dispatch-count inc)
                            (swap! state assoc :selected (get-in event [:glitter/dom-event :glitter/value])))
      nil)))

(core/set-dispatch! execute-actions)

(defn -main [& _]
  (let [results (atom {})]
    (app/run
     (fn [window]
       (gtk/mount! window view state)
       ;; :window is a single-child container — the mounted [:box ...] is
       ;; window's ONE child; :spin-button and :list-box are box's two
       ;; children in sibling order. GTK auto-wraps each list-box child in
       ;; a GtkListBoxRow, so list-box's OWN first-child/next-sibling walk
       ;; already returns rows directly, not the labels inside them.
       (let [box (g/gtk-widget-get-first-child window)
             spin-button (g/gtk-widget-get-first-child box)
             list-box (g/gtk-widget-get-next-sibling spin-button)
             row1 #(g/gtk-widget-get-next-sibling (g/gtk-widget-get-first-child list-box))]
         (swap! results assoc
                :spin-value-on-mount (g/gtk-spin-button-get-value spin-button)
                :row1-label-on-mount (g/gtk-label-get-text (g/gtk-widget-get-first-child (row1))))
         ;; Real interaction: direct FFI, bypassing set-spin-button-value!
         ;; so the actual "value-changed" signal fires.
         (g/gtk-spin-button-set-value spin-button 7.0)
         (swap! results assoc
                :state-count-after-spin (:count @state)
                :dispatch-count-after-spin @select-dispatch-count)
         ;; Real interaction: direct FFI, bypassing any glitter setter (none
         ;; exists — selection is driven by user interaction only) so the
         ;; actual "row-selected" signal fires.
         (g/gtk-list-box-select-row list-box (row1))
         (swap! results assoc
                :state-selected-after-select (:selected @state)
                :dispatch-count-after-select @select-dispatch-count)
         ;; Programmatic sync-back elsewhere in the (hypothetical) app —
         ;; spin-button should follow, no spurious extra dispatch.
         (swap! state assoc :count 3.0)
         (swap! results assoc
                :spin-value-after-programmatic (g/gtk-spin-button-get-value spin-button)
                :dispatch-count-after-programmatic @select-dispatch-count)
         ;; Re-render: swap row1's TAG (:label -> :button) while it's the
         ;; CURRENTLY SELECTED row — exercises the insert-child-after! fix
         ;; (the new button must actually land in the tree) and proves the
         ;; container-management fix doesn't corrupt row0/row2.
         (swap! state assoc :row1-state :button)
         (swap! results assoc
                :row1-label-after-swap (g/gtk-button-get-label (g/gtk-widget-get-first-child (row1)))
                :row0-label-after-swap (g/gtk-label-get-text (g/gtk-widget-get-first-child
                                                              (g/gtk-widget-get-first-child list-box)))
                :dispatch-count-after-swap @select-dispatch-count)
         ;; Re-render: remove row1 (the previously-SELECTED row) entirely —
         ;; exercises the suppressing-guard fix on the removal side: GTK's
         ;; own incidental "row-selected(NULL)" deselection notice must NOT
         ;; reach *dispatch*.
         (swap! state assoc :row1-state :gone)
         (swap! results assoc
                :row0-label-after-remove (g/gtk-label-get-text (g/gtk-widget-get-first-child
                                                                (g/gtk-widget-get-first-child list-box)))
                :row1-label-after-remove (g/gtk-label-get-text (g/gtk-widget-get-first-child (row1)))
                :dispatch-count-after-remove @select-dispatch-count)))
     :title "glitter spin-button+list-box smoke" :width 320 :height 160
     :app-id "glitter.spin-button-list-box-smoke" :auto-quit-ms 500)
    (println :results (pr-str @results))
    (when-not (and (= 5.0 (:spin-value-on-mount @results))
                   (= "row1" (:row1-label-on-mount @results))
                   (= 7.0 (:state-count-after-spin @results))
                   (= 0 (:dispatch-count-after-spin @results))
                   (= 1 (:state-selected-after-select @results))
                   (= 1 (:dispatch-count-after-select @results))
                   (= 3.0 (:spin-value-after-programmatic @results))
                   (= 1 (:dispatch-count-after-programmatic @results))
                   (= "row1-swapped" (:row1-label-after-swap @results))
                   (= "row0" (:row0-label-after-swap @results))
                   (= 1 (:dispatch-count-after-swap @results))
                   (= "row0" (:row0-label-after-remove @results))
                   (= "row2" (:row1-label-after-remove @results))
                   (= 1 (:dispatch-count-after-remove @results)))
      (println :FAIL "see :results above")
      ;; Direct call — see test_runner.clj/exit for why a resolve-guarded
      ;; version of this silently never exits.
      (System/exit 1))))
