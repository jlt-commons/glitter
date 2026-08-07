(ns glitter.password-search-entry-smoke
  "Automated smoke against the LIVE GTK tree for :password-entry and
  :search-entry — round 7's two GtkEditable-family additions.

  Both implement GtkEditable via a delegate (confirmed against
  gtk/gtkpasswordentry.c and gtk/gtksearchentry.c: both call
  gtk_editable_init_delegate) — the delegate helper itself connects to the
  inner widget's \"changed\" and re-emits it on the outer object
  (gtk/gtkeditable.c's gtk_editable_init_delegate,
  g_signal_connect(delegate, \"changed\", ...)), so glitter.widget's
  existing set-entry-text! and :entry's \"changed\" signal ENTRY (the
  GTK-signal-NAME lookup in `signals`) work directly on either pointer
  with no new plumbing. What IS new: signal-value (keyed by [tag signal]
  since round 6) needed its own [:password-entry \"changed\"] and
  [:search-entry \"changed\"] entries — reusing :entry's registration
  under the bare signal name would NOT have worked, since signal-value
  keys by [tag signal], not signal name alone. Found live: the first
  version of this round shipped without these two entries, and
  :password-entry's dispatched value silently came back nil — caught by
  this very smoke before it shipped.

  :search-entry also gets its own genuinely new \":on-search-changed\"
  signal (confirmed via gtk/gtksearchentry.c's g_signal_new: G_TYPE_NONE,
  0, the plain 2-arg-void shape). It's debounced (fires :search-delay ms
  after the user stops typing) EXCEPT for one case, found by reading
  gtk_search_entry_changed's C body directly: clearing the text to EMPTY
  fires \"search-changed\" immediately and synchronously (no timeout) —
  gtk/gtksearchentry.c's own internal handler special-cases the empty
  string specifically so a cleared search box updates results right
  away. This smoke uses that deterministic path rather than waiting out
  a real GLib timeout."
  (:require [glitter.app :as app]
            [glitter.core :as core]
            [glitter.ffi :as g]
            [glitter.gtk :as gtk]))

(defonce state (atom {:pw "" :q "initial"}))
(defonce dispatch-count (atom 0))

(defn view [{:keys [pw q]}]
  [:box {:spacing 4}
   [:password-entry {:text pw :show-peek-icon true :on {:change [[:action/set-pw]]}}]
   [:search-entry {:text q :on {:search-changed [[:action/set-q]]}}]])

(defn execute-actions [event actions]
  (doseq [[kind] actions]
    (swap! dispatch-count inc)
    (case kind
      :action/set-pw (swap! state assoc :pw (get-in event [:glitter/dom-event :glitter/value]))
      :action/set-q  (swap! state assoc :q (get-in event [:glitter/dom-event :glitter/value]))
      nil)))

(core/set-dispatch! execute-actions)

(defn -main [& _]
  (let [results (atom {})]
    (app/run
     (fn [window]
       (gtk/mount! window view state)
       ;; :window is a single-child container — the mounted [:box ...] is
       ;; window's ONE child; :password-entry and :search-entry are box's
       ;; two children in sibling order.
       (let [box (g/gtk-widget-get-first-child window)
             pw (g/gtk-widget-get-first-child box)
             search (g/gtk-widget-get-next-sibling pw)]
         (swap! results assoc
                :pw-show-peek-on-mount (pos? (g/gtk-password-entry-get-show-peek-icon pw))
                :search-text-on-mount (g/gtk-editable-get-text search))
         ;; Real interaction: direct FFI, bypassing set-entry-text! so the
         ;; actual "changed" signal fires (glitter's OWN pw-entry value-fn,
         ;; not :entry's — see the ns docstring for why that distinction
         ;; matters).
         (g/gtk-editable-set-text pw "secret")
         (swap! results assoc
                :state-pw-after-type (:pw @state)
                :dispatch-count-after-type @dispatch-count)
         ;; Real interaction: clearing to empty fires "search-changed"
         ;; SYNCHRONOUSLY (verified against gtk_search_entry_changed's C
         ;; body) — no timeout to wait out.
         (g/gtk-editable-set-text search "")
         (swap! results assoc
                :state-q-after-clear (:q @state)
                :dispatch-count-after-clear @dispatch-count)
         ;; Programmatic sync-back elsewhere in the (hypothetical) app —
         ;; the password entry should follow via the EXISTING
         ;; set-entry-text! (generic across every GtkEditable, no
         ;; :password-entry-specific setter needed), no spurious dispatch.
         (reset! state {:pw "resynced" :q ""})
         (swap! results assoc
                :pw-text-after-programmatic (g/gtk-editable-get-text pw)
                :dispatch-count-after-programmatic @dispatch-count)))
     :title "glitter password+search entry smoke" :width 320 :height 100
     :app-id "glitter.password-search-entry-smoke" :auto-quit-ms 500)
    (println :results (pr-str @results))
    (when-not (and (true? (:pw-show-peek-on-mount @results))
                   (= "initial" (:search-text-on-mount @results))
                   (= "secret" (:state-pw-after-type @results))
                   (= 1 (:dispatch-count-after-type @results))
                   (= "" (:state-q-after-clear @results))
                   (= 2 (:dispatch-count-after-clear @results))
                   (= "resynced" (:pw-text-after-programmatic @results))
                   (= 2 (:dispatch-count-after-programmatic @results)))
      (println :FAIL "see :results above")
      ;; Direct call — see test_runner.clj/exit for why a resolve-guarded
      ;; version of this silently never exits.
      (System/exit 1))))
