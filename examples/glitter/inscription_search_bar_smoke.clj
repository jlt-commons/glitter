(ns glitter.inscription-search-bar-smoke
  "Automated smoke against the LIVE GTK tree for :inscription and
  :search-bar — round 10's two quick-win additions (:header-bar/
  :action-bar and :menu-button/:popover, the round's two bigger
  architecture pieces, get their own header_bar_action_bar_smoke.clj and
  menu_button_popover_smoke.clj).

  :inscription has no signal at all (confirmed: no g_signal_new in
  gtk/gtkinscription.c) — a lighter-weight sibling to :label: no markup
  interpretation, fixed :text-overflow handling (a GtkInscriptionOverflow
  nick) instead of Pango ellipsize/wrap options. Purely display-only,
  driven entirely by re-applied props, same shape as :picture.

  :search-bar is also display/props-only (no g_signal_new in
  gtk/gtksearchbar.c either) — a single-child container, same strategy as
  :frame/:revealer, driven by :search-mode/:show-close-button. Pairs
  naturally with :search-entry as its child. v1 deliberately does not
  wire gtk_search_bar_connect_entry/set_key_capture_widget — both need a
  raw GtkEditable/GtkWidget pointer glitter has no hiccup-level
  convention for passing sideways yet; :search-mode is still fully
  controllable programmatically without them."
  (:require [glitter.app :as app]
            [glitter.core :as core]
            [glitter.ffi :as g]
            [glitter.genum :as genum]
            [glitter.gtk :as gtk]))

(defonce state (atom {:text "hello" :overflow :clip :search-mode true :show-close true}))

(defn view [{:keys [text overflow search-mode show-close]}]
  [:box {:spacing 4}
   [:inscription {:text text :text-overflow overflow}]
   [:search-bar {:search-mode search-mode :show-close-button show-close}
    [:search-entry {}]]])

(core/set-dispatch! (fn [_event _actions] nil))

(defn -main [& _]
  (let [results (atom {})]
    (app/run
     (fn [window]
       (gtk/mount! window view state)
       ;; :window is a single-child container — the mounted [:box ...] is
       ;; window's ONE child; :inscription and :search-bar are box's two
       ;; children in sibling order.
       (let [box (g/gtk-widget-get-first-child window)
             insc (g/gtk-widget-get-first-child box)
             search-bar (g/gtk-widget-get-next-sibling insc)]
         (swap! results assoc
                :insc-text-on-mount (g/gtk-inscription-get-text insc)
                :insc-overflow-on-mount (g/gtk-inscription-get-text-overflow insc)
                :insc-overflow-clip-int (genum/enum "GtkInscriptionOverflow" :clip)
                :search-mode-on-mount (pos? (g/gtk-search-bar-get-search-mode search-bar))
                :search-close-on-mount (pos? (g/gtk-search-bar-get-show-close-button search-bar))
                :search-bar-has-child (some? (g/gtk-search-bar-get-child search-bar)))
         ;; Re-render: both widgets are entirely props-driven (no signal
         ;; to bypass) — a plain state swap and re-read is the correct
         ;; verification, matching :picture's own smoke.
         (swap! state assoc :text "changed" :overflow :ellipsize-end :search-mode false :show-close false)
         ;; gtk_inscription_get_text_overflow returns the raw GEnum int,
         ;; not a resolved nick — resolve :ellipsize-end through the SAME
         ;; forward path :apply itself uses and compare ints, rather than
         ;; hardcoding GTK_INSCRIPTION_OVERFLOW_ELLIPSIZE_END's numeric
         ;; value (same discipline as picture_editable_label_smoke.clj's
         ;; :content-fit comparison).
         (swap! results assoc
                :insc-text-after-rerender (g/gtk-inscription-get-text insc)
                :insc-overflow-after-rerender (g/gtk-inscription-get-text-overflow insc)
                :insc-overflow-ellipsize-end-int (genum/enum "GtkInscriptionOverflow" :ellipsize-end)
                :search-mode-after-rerender (pos? (g/gtk-search-bar-get-search-mode search-bar))
                :search-close-after-rerender (pos? (g/gtk-search-bar-get-show-close-button search-bar)))))
     :title "glitter inscription+search-bar smoke" :width 320 :height 100
     :app-id "glitter.inscription-search-bar-smoke" :auto-quit-ms 500)
    (println :results (pr-str @results))
    (when-not (and (= "hello" (:insc-text-on-mount @results))
                   (= (:insc-overflow-clip-int @results) (:insc-overflow-on-mount @results))
                   (true? (:search-mode-on-mount @results))
                   (true? (:search-close-on-mount @results))
                   (true? (:search-bar-has-child @results))
                   (= "changed" (:insc-text-after-rerender @results))
                   (= (:insc-overflow-ellipsize-end-int @results) (:insc-overflow-after-rerender @results))
                   (false? (:search-mode-after-rerender @results))
                   (false? (:search-close-after-rerender @results)))
      (println :FAIL "see :results above")
      ;; Direct call — see test_runner.clj/exit for why a resolve-guarded
      ;; version of this silently never exits.
      (System/exit 1))))
