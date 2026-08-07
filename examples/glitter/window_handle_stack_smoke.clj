(ns glitter.window-handle-stack-smoke
  "Automated smoke against the LIVE GTK tree for :window-handle and
  :stack — round 11's quick win and its :notebook-sibling tabbed
  container (:drop-down and :grid, the round's new-capability and
  new-architecture pieces, get their own drop_down_grid_smoke.clj).

  :window-handle is a single-child, no-signal CSD drag-handle container
  (confirmed: no g_signal_new in gtk/gtkwindowhandle.c) — rounds out the
  simple single-child wrapper family (:frame/:revealer/:expander/
  :search-bar/...). Its first live run caught a real bug in THIS round's
  own new code, not a pre-existing one: the :window-handle case branch
  was missing from append-child!/remove-child!/replace-child! entirely,
  so gtk_window_handle_set_child was never actually called — the widget
  compiled and mounted with no error, but silently had no child. Caught
  immediately by this smoke reading the child back and getting nothing,
  before it ever shipped.

  :stack is a :notebook sibling with no tabs of its own — pages are
  NAME-addressed (:stack-name on each child, intercepted via the same
  set-attribute mechanism :grid uses — see glitter.gtk's own comment),
  not index-addressed the way :notebook's pages are. GTK auto-selects
  the first added VISIBLE child as visible-child (confirmed via
  gtk_stack_add_page's C body) — a THIRD instance of the exact
  :notebook round-9 finding — so mounting a :stack with initial
  children genuinely dispatches a mount-time 'notify::visible-child-name'
  before any real interaction, same as :notebook's own dispatch-count
  baseline of 1, not 0.

  A second real finding, caught live while writing THIS smoke: :apply
  runs at create! time, BEFORE the reconciler has appended any children
  (see the ctor/apply prop-flow note in glitter.widget, above
  :checkbutton-spec) — so an initial :visible-child-name always landed
  on a completely empty stack and gtk_stack_set_visible_child_name
  warned 'Child name not found in GtkStack' on every fresh mount,
  silently falling back to GTK's own auto-select-first-page behavior.
  Fixed by guarding set-stack-visible-child-name! on
  gtk_stack_get_child_by_name (only proceed if the named page already
  exists) — silences the warning, but leaves a real, documented v1 gap:
  requesting a NON-default initial page still doesn't take effect at
  mount (this smoke's own initial :stack-page is deliberately \"a\", the
  page that auto-select would pick anyway, to test the parts of :stack
  that DO work correctly rather than assert on the known gap)."
  (:require [glitter.app :as app]
            [glitter.core :as core]
            [glitter.ffi :as g]
            [glitter.gtk :as gtk]))

(defonce state (atom {:stack-page "a"}))
(defonce dispatch-count (atom 0))

(defn view [{:keys [stack-page]}]
  [:box {:spacing 4}
   [:window-handle [:label {:label "handled"}]]
   [:stack {:visible-child-name stack-page :on {:visible-child-changed [[:action/switch]]}}
    [:label {:stack-name "a" :label "page-a"}]
    [:label {:stack-name "b" :label "page-b"}]]])

(defn execute-actions [event actions]
  (doseq [[kind] actions]
    (swap! dispatch-count inc)
    (case kind
      :action/switch (swap! state assoc :stack-page (get-in event [:glitter/dom-event :glitter/value]))
      nil)))

(core/set-dispatch! execute-actions)

(defn -main [& _]
  (let [results (atom {})]
    (app/run
     (fn [window]
       (gtk/mount! window view state)
       ;; :window is a single-child container — the mounted [:box ...] is
       ;; window's ONE child; :window-handle and :stack are box's two
       ;; children in sibling order.
       (let [box (g/gtk-widget-get-first-child window)
             wh (g/gtk-widget-get-first-child box)
             stack (g/gtk-widget-get-next-sibling wh)]
         (swap! results assoc
                :wh-child-text-on-mount (g/gtk-label-get-text (g/gtk-window-handle-get-child wh))
                :stack-visible-on-mount (g/gtk-stack-get-visible-child-name stack)
                :dispatch-count-on-mount @dispatch-count)
         ;; Real interaction: direct FFI stack switch, bypassing
         ;; set-stack-visible-child-name!, so the actual
         ;; "notify::visible-child-name" signal fires.
         (g/gtk-stack-set-visible-child-name stack "b")
         (swap! results assoc
                :state-stack-page-after-switch (:stack-page @state)
                :dispatch-count-after-switch @dispatch-count)
         ;; Programmatic sync-back elsewhere in the (hypothetical) app —
         ;; the stack should follow via set-stack-visible-child-name!'s
         ;; suppressing guard, no spurious extra dispatch.
         (reset! state {:stack-page "a"})
         (swap! results assoc
                :stack-visible-after-programmatic (g/gtk-stack-get-visible-child-name stack)
                :dispatch-count-after-programmatic @dispatch-count)))
     :title "glitter window-handle+stack smoke" :width 320 :height 100
     :app-id "glitter.window-handle-stack-smoke" :auto-quit-ms 500)
    (println :results (pr-str @results))
    (when-not (and (= "handled" (:wh-child-text-on-mount @results))
                   (= "a" (:stack-visible-on-mount @results))
                   (= 1 (:dispatch-count-on-mount @results))
                   (= "b" (:state-stack-page-after-switch @results))
                   (= 2 (:dispatch-count-after-switch @results))
                   (= "a" (:stack-visible-after-programmatic @results))
                   (= 2 (:dispatch-count-after-programmatic @results)))
      (println :FAIL "see :results above")
      ;; Direct call — see test_runner.clj/exit for why a resolve-guarded
      ;; version of this silently never exits.
      (System/exit 1))))
