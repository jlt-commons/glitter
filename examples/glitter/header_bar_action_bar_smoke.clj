(ns glitter.header-bar-action-bar-smoke
  "Automated smoke against the LIVE GTK tree for :header-bar and
  :action-bar — round 10's genuinely new HYBRID container shape: one
  named title/center-widget slot plus an ORDERED pack-start list. Not a
  fixed set of named slots (:center-box/:paned, verified fixed-capacity)
  and not a plain ordered list (:box/:list-box/:flow-box/:notebook).

  v1 convention, mirroring :overlay's own 'query GTK's own occupancy'
  pattern: the FIRST hiccup child becomes the title-widget/center-widget
  (if that slot is still empty); every LATER child gets pack_start'd, in
  order.

  A real, verified finding drove that scope call: confirmed by reading
  gtk_header_bar_pack's C body directly, pack_start calls gtk_box_append
  (safe — preserves hiccup order) but pack_end calls gtk_box_prepend — a
  genuinely surprising internal detail that would silently REVERSE
  hiccup order if fed one child at a time in the reconciler's normal
  append sequence. gtk_action_bar_pack_end has the identical reversal
  risk via a DIFFERENT GTK call (gtk_box_insert_child_after with a NULL
  sibling — 'insert as the first child', this project's own established
  insert-before convention, i.e. a prepend under another name),
  confirmed independently rather than assumed to carry over just
  because the widgets look alike. v1 therefore only wires pack_start;
  gtk_header_bar_pack_end/gtk_action_bar_pack_end are not called
  anywhere in this codebase.

  Neither widget has an enumeration getter for its pack-start region
  (same 'no getter, walk the tree' situation :overlay's own smoke is
  already in) — this smoke instead identifies the real start_box by the
  \"start\" CSS class GTK itself adds internally (confirmed via both
  gtk_header_bar_init's and gtk_action_bar_init's C bodies:
  gtk_widget_add_css_class(start_box, \"start\")), after walking down
  through each widget's own private wrapper (GtkWindowHandle for
  :header-bar, GtkRevealer for :action-bar) and its GtkCenterBox — a
  structural depth (4 levels) confirmed by reading both init functions
  directly, not guessed from a first attempt that assumed a flat
  3-sibling composite and mis-treated a GtkCenterBox as a GtkButton
  (caught by a live GTK-CRITICAL before it ever reached this file).

  Neither widget has a signal of its own.

  A SECOND real, verified finding, found live while writing this exact
  smoke: gtk_header_bar_set_show_title_buttons(bar, TRUE) calls
  create_window_controls(bar) (confirmed by reading its C body
  directly), which gtk_box_prepends a native GtkWindowControls widget
  into bar->start_box — THE SAME pack-start region glitter's own hiccup
  children live in. Toggling :show-title-buttons true therefore lands a
  GTK-managed, non-button widget at the FRONT of the pack-start list,
  shifting glitter's own tracked children back by one position — real
  GTK behavior sharing the same mutable list, not a glitter bug, and not
  something glitter's own container-management code needs to guard
  against (gtk_header_bar_remove/replace only ever act on widgets
  glitter itself created, never on GTK's own internal controls widget).
  This smoke asserts the shift explicitly rather than avoiding it:
  :show-title-buttons starts false specifically so :hb-start-box-order
  -on-mount sees ONLY glitter's own children, and the re-render step
  toggles it true and confirms the pack-start count grows by one while
  glitter's own two buttons keep their RELATIVE order at the tail."
  (:require [glitter.app :as app]
            [glitter.core :as core]
            [glitter.ffi :as g]
            [glitter.gtk :as gtk]))

(defonce state (atom {:hb-title "Title" :ab-center "Center" :show-title-buttons false :revealed true}))

(defn view [{:keys [hb-title ab-center show-title-buttons revealed]}]
  [:box {:spacing 4}
   [:header-bar {:show-title-buttons show-title-buttons}
    [:label {:label hb-title}]
    [:button {:label "hb-start-1"}]
    [:button {:label "hb-start-2"}]]
   [:action-bar {:revealed revealed}
    [:label {:label ab-center}]
    [:button {:label "ab-start-1"}]]])

(core/set-dispatch! (fn [_event _actions] nil))

;; Generic widget-tree walk — same technique overlay_flow_box_smoke.clj
;; uses, since neither widget exposes an enumeration getter either.
(defn- children-of [w]
  (loop [c (g/gtk-widget-get-first-child w) acc []]
    (if (or (nil? c) (zero? c)) acc (recur (g/gtk-widget-get-next-sibling c) (conj acc c)))))

;; See ns docstring for the verified 4-level depth and the "start"
;; CSS-class identification this relies on.
(defn- start-box-of [bar]
  (let [wrapper (g/gtk-widget-get-first-child bar)
        center-box (g/gtk-widget-get-first-child wrapper)]
    (first (filter #(pos? (g/gtk-widget-has-css-class % "start")) (children-of center-box)))))

(defn -main [& _]
  (let [results (atom {})]
    (app/run
     (fn [window]
       (gtk/mount! window view state)
       ;; :window is a single-child container — the mounted [:box ...] is
       ;; window's ONE child; :header-bar and :action-bar are box's two
       ;; children in sibling order.
       (let [box (g/gtk-widget-get-first-child window)
             header-bar (g/gtk-widget-get-first-child box)
             action-bar (g/gtk-widget-get-next-sibling header-bar)]
         (swap! results assoc
                :hb-title-text-on-mount (g/gtk-label-get-text (g/gtk-header-bar-get-title-widget header-bar))
                :hb-start-box-order-on-mount (mapv g/gtk-button-get-label (children-of (start-box-of header-bar)))
                :hb-show-title-buttons-on-mount (pos? (g/gtk-header-bar-get-show-title-buttons header-bar))
                :ab-center-text-on-mount (g/gtk-label-get-text (g/gtk-action-bar-get-center-widget action-bar))
                :ab-start-box-order-on-mount (mapv g/gtk-button-get-label (children-of (start-box-of action-bar)))
                :ab-revealed-on-mount (pos? (g/gtk-action-bar-get-revealed action-bar)))
         ;; Re-render: :revealed is a plain props-driven boolean, no
         ;; interaction with action-bar's pack-start list — its own
         ;; start-box order stays unchanged. :show-title-buttons true
         ;; triggers the SECOND real finding documented in the ns
         ;; docstring: GTK prepends its own native window-controls
         ;; widget into header-bar's start_box, growing the pack-start
         ;; count by one while glitter's own two buttons keep their
         ;; relative order, now at the TAIL instead of the front.
         (swap! state assoc :show-title-buttons true :revealed false)
         (let [hb-start-after (children-of (start-box-of header-bar))]
           (swap! results assoc
                  :hb-show-title-buttons-after-rerender (pos? (g/gtk-header-bar-get-show-title-buttons header-bar))
                  :ab-revealed-after-rerender (pos? (g/gtk-action-bar-get-revealed action-bar))
                  :hb-start-box-count-after-rerender (count hb-start-after)
                  :hb-start-box-tail-after-rerender (mapv g/gtk-button-get-label (subvec (vec hb-start-after) 1))
                  :ab-start-box-order-after-rerender (mapv g/gtk-button-get-label (children-of (start-box-of action-bar)))))))
     :title "glitter header-bar+action-bar smoke" :width 500 :height 100
     :app-id "glitter.header-bar-action-bar-smoke" :auto-quit-ms 500)
    (println :results (pr-str @results))
    (when-not (and (= "Title" (:hb-title-text-on-mount @results))
                   (= ["hb-start-1" "hb-start-2"] (:hb-start-box-order-on-mount @results))
                   (false? (:hb-show-title-buttons-on-mount @results))
                   (= "Center" (:ab-center-text-on-mount @results))
                   (= ["ab-start-1"] (:ab-start-box-order-on-mount @results))
                   (true? (:ab-revealed-on-mount @results))
                   (true? (:hb-show-title-buttons-after-rerender @results))
                   (false? (:ab-revealed-after-rerender @results))
                   (= 3 (:hb-start-box-count-after-rerender @results))
                   (= ["hb-start-1" "hb-start-2"] (:hb-start-box-tail-after-rerender @results))
                   (= ["ab-start-1"] (:ab-start-box-order-after-rerender @results)))
      (println :FAIL "see :results above")
      ;; Direct call — see test_runner.clj/exit for why a resolve-guarded
      ;; version of this silently never exits.
      (System/exit 1))))
