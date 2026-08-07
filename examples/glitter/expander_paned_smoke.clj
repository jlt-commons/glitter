(ns glitter.expander-paned-smoke
  "Automated smoke against the LIVE GTK tree for :expander and :paned —
  round 7's two remaining additions (:password-entry/:search-entry, the
  round's two GtkEditable-family additions, get their own
  password_search_entry_smoke.clj).

  Neither GtkExpander nor GtkPaned has a dedicated interaction signal of
  its own — confirmed live (no g_signal_new call in gtk/gtkexpander.c;
  gtk/gtkpaned.c's only signals are keybinding ones like
  cycle-child-focus/move-handle, not \"the user dragged the divider\").
  Real interactivity for both means watching a GObject property-change
  signal: \"notify::expanded\" for :expander, \"notify::position\" for
  :paned. Both reuse the EXACT 3-arg-void foreign-callable shape round 6
  already generalized for :list-box's \"row-selected\"/\"row-activated\" —
  void(GObject*, GParamSpec*, gpointer) is the standard GObject \"notify\"
  signature, and a GParamSpec* is just another :pointer under FFI, so no
  new literal call site was needed in glitter.gtk/set-event-handler; just
  two more signal names added to the existing case branch's set. This
  smoke's dispatch assertions are what actually prove that free reuse
  works end-to-end, not just that it compiles.

  :expander (gtk_expander_set_child) is a single-child container, same
  strategy as :frame/:scrolled/:revealer. :paned is a genuinely new
  2-NAMED-SLOT container (start/end) — simpler sibling to :center-box's
  three, and inheriting the same class of structural v1 gap (see
  glitter.widget/paned-insert-after!'s docstring): a same-slot hiccup TAG
  swap while BOTH slots are full is unsupported, though the exact
  FAILURE SHAPE differs from :center-box's (verified live, separately —
  swapping the LAST slot happens to land correctly since there's no
  trailing third slot to corrupt into; swapping the FIRST slot silently
  drops the new widget instead). This smoke only exercises the SAFE path
  it does support: swapping the last slot's tag."
  (:require [glitter.app :as app]
            [glitter.core :as core]
            [glitter.ffi :as g]
            [glitter.gtk :as gtk]))

(defonce state (atom {:expanded false :position 100 :swap? false}))
(defonce dispatch-count (atom 0))

(defn view [{:keys [expanded position swap?]}]
  [:box {:spacing 4}
   [:expander {:label "more" :expanded expanded :on {:expanded [[:action/toggle]]}}
    [:label {:label "expander-child"}]]
   [:paned {:orientation :horizontal :position position :on {:position-changed [[:action/reposition]]}}
    [:label {:label "A"}]
    (if swap? [:button {:label "B2"}] [:label {:label "B"}])]])

(defn execute-actions [event actions]
  (doseq [[kind] actions]
    (swap! dispatch-count inc)
    (case kind
      :action/toggle     (swap! state assoc :expanded (pos? (get-in event [:glitter/dom-event :glitter/value])))
      :action/reposition (swap! state assoc :position (get-in event [:glitter/dom-event :glitter/value]))
      nil)))

(core/set-dispatch! execute-actions)

(defn -main [& _]
  (let [results (atom {})]
    (app/run
     (fn [window]
       (gtk/mount! window view state)
       ;; :window is a single-child container — the mounted [:box ...] is
       ;; window's ONE child; :expander and :paned are box's two children
       ;; in sibling order.
       (let [box (g/gtk-widget-get-first-child window)
             expander (g/gtk-widget-get-first-child box)
             paned (g/gtk-widget-get-next-sibling expander)]
         (swap! results assoc
                :expander-label-on-mount (g/gtk-expander-get-label expander)
                :expander-expanded-on-mount (pos? (g/gtk-expander-get-expanded expander))
                :paned-position-on-mount (g/gtk-paned-get-position paned)
                :paned-start-on-mount (g/gtk-label-get-text (g/gtk-paned-get-start-child paned))
                :paned-end-on-mount (g/gtk-label-get-text (g/gtk-paned-get-end-child paned)))
         ;; Real interaction: direct FFI, bypassing set-expander-expanded!
         ;; so the actual "notify::expanded" signal fires.
         (g/gtk-expander-set-expanded expander 1)
         (swap! results assoc
                :state-expanded-after-toggle (:expanded @state)
                :dispatch-count-after-toggle @dispatch-count)
         ;; Real interaction: direct FFI, bypassing set-paned-position! so
         ;; the actual "notify::position" signal fires.
         (g/gtk-paned-set-position paned 222)
         (swap! results assoc
                :state-position-after-drag (:position @state)
                :dispatch-count-after-drag @dispatch-count)
         ;; Programmatic sync-back elsewhere in the (hypothetical) app —
         ;; both widgets should follow, no spurious extra dispatch.
         (reset! state {:expanded false :position 50})
         (swap! results assoc
                :expander-expanded-after-programmatic (pos? (g/gtk-expander-get-expanded expander))
                :paned-position-after-programmatic (g/gtk-paned-get-position paned)
                :dispatch-count-after-programmatic @dispatch-count)
         ;; Re-render: swap paned's LAST slot's tag (:label -> :button)
         ;; while both slots are occupied — the SAFE half of the
         ;; documented v1 gap (see the ns docstring).
         (swap! state assoc :swap? true)
         (swap! results assoc
                :paned-start-after-swap (g/gtk-label-get-text (g/gtk-paned-get-start-child paned))
                :paned-end-after-swap (g/gtk-button-get-label (g/gtk-paned-get-end-child paned)))))
     :title "glitter expander+paned smoke" :width 320 :height 140
     :app-id "glitter.expander-paned-smoke" :auto-quit-ms 500)
    (println :results (pr-str @results))
    (when-not (and (= "more" (:expander-label-on-mount @results))
                   (false? (:expander-expanded-on-mount @results))
                   (= 100 (:paned-position-on-mount @results))
                   (= "A" (:paned-start-on-mount @results))
                   (= "B" (:paned-end-on-mount @results))
                   (true? (:state-expanded-after-toggle @results))
                   (= 1 (:dispatch-count-after-toggle @results))
                   (= 222 (:state-position-after-drag @results))
                   (= 2 (:dispatch-count-after-drag @results))
                   (false? (:expander-expanded-after-programmatic @results))
                   (= 50 (:paned-position-after-programmatic @results))
                   (= 2 (:dispatch-count-after-programmatic @results))
                   (= "A" (:paned-start-after-swap @results))
                   (= "B2" (:paned-end-after-swap @results)))
      (println :FAIL "see :results above")
      ;; Direct call — see test_runner.clj/exit for why a resolve-guarded
      ;; version of this silently never exits.
      (System/exit 1))))
