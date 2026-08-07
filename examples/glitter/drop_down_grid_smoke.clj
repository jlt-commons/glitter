(ns glitter.drop-down-grid-smoke
  "Automated smoke against the LIVE GTK tree for :drop-down and :grid —
  round 11's new-capability and new-architecture pieces (:window-handle
  and :stack get their own window_handle_stack_smoke.clj).

  :drop-down is the first \"choose from options\" widget in this project.
  Its item list is built via a GtkStringList, appended one string at a
  time (gtk_string_list_new(NULL) + gtk_string_list_append per item) —
  deliberately sidesteps gtk_drop_down_new_from_strings' raw C-string-
  array argument, an FFI marshalling class this project has never
  needed. :selected (an index) reuses the suppressing-guard pattern; its
  \"notify::selected\" and \"activate\" signals both turned out to be free
  reuses of shapes already generalized elsewhere in this project (a
  3-arg-void notify signal and the plain 2-arg-void :on-activate shape
  :menu-button already established) — no new glitter.gtk changes needed
  for either.

  :grid is the first container in this project whose child placement
  data lives on the CHILD's own hiccup props (:grid-column/:grid-row/
  :grid-column-span/:grid-row-span), not a fixed slot or append order.
  This needed real cross-cutting architecture, not just a new widget
  spec: glitter.gtk/set-attribute special-cases these props (plus
  :stack-name), stashing them on the CHILD's own tracking atom instead
  of routing through the widget's normal :apply closure (which has no
  idea what \"which grid cell am I in\" would even mean for itself — that
  question belongs to the PARENT). append-child!/insert-before/
  replace-child then thread this bookkeeping through to
  glitter.widget's grid-attach!, the only place with access to both the
  parent's container kind and the child's stashed props.

  A load-bearing finding drove that design, verified live via a
  throwaway probe before writing a single line of glitter.gtk: a
  NAMESPACED keyword prop like :grid/column NEVER reaches
  IRender/set-attribute at all — glitter.core's set-attr/update-attr
  both guard on `(when-not (namespace attr) ...)`, silently dropping any
  namespaced attribute at the reconciler level (inherited from
  Replicant's own convention that a namespaced attr is reserved/
  special). Confirmed by mounting a custom :my-plain-prop alongside a
  :grid/column on the same element — only the plain key ever printed
  from inside set-attribute. This is why :grid-column (a plain,
  hyphenated keyword) is used instead of the more Clojure-idiomatic
  :grid/column — the namespaced form would have silently done nothing
  at all, with no error, no warning, just an inert prop.

  This smoke's KNOWN V1 CONSTRAINT, deliberately not exercised here:
  grid position is read ONLY when a child is first attached (fresh
  mount or entering via a keyed insert); changing an already-attached
  child's :grid-column/etc on a LATER re-render does not move it. Fine
  for the common case of a static layout with fixed positions — see
  docs/guide/limitations.md."
  (:require [glitter.app :as app]
            [glitter.core :as core]
            [glitter.ffi :as g]
            [glitter.gtk :as gtk]))

(defonce state (atom {:selected 0}))
(defonce dispatch-count (atom 0))

(defn view [{:keys [selected]}]
  [:box {:spacing 4}
   [:drop-down {:items ["one" "two" "three"] :selected selected
                :on {:selected-changed [[:action/select]]}}]
   [:grid {:row-spacing 2 :column-spacing 2}
    [:label {:grid-column 0 :grid-row 0 :label "cell-0-0"}]
    [:label {:grid-column 1 :grid-row 0 :label "cell-1-0"}]
    [:label {:grid-column 0 :grid-row 1 :grid-column-span 2 :label "cell-0-1-span2"}]]])

(defn execute-actions [event actions]
  (doseq [[kind] actions]
    (swap! dispatch-count inc)
    (case kind
      :action/select (swap! state assoc :selected (get-in event [:glitter/dom-event :glitter/value]))
      nil)))

(core/set-dispatch! execute-actions)

(defn -main [& _]
  (let [results (atom {})]
    (app/run
     (fn [window]
       (gtk/mount! window view state)
       ;; :window is a single-child container — the mounted [:box ...] is
       ;; window's ONE child; :drop-down and :grid are box's two children
       ;; in sibling order.
       (let [box (g/gtk-widget-get-first-child window)
             dd (g/gtk-widget-get-first-child box)
             grid (g/gtk-widget-get-next-sibling dd)]
         (swap! results assoc
                :dd-selected-on-mount (g/gtk-drop-down-get-selected dd)
                :grid-0-0 (g/gtk-label-get-text (g/gtk-grid-get-child-at grid 0 0))
                :grid-1-0 (g/gtk-label-get-text (g/gtk-grid-get-child-at grid 1 0))
                :grid-0-1 (g/gtk-label-get-text (g/gtk-grid-get-child-at grid 0 1))
                ;; The column-span cell must occupy BOTH (0,1) and (1,1) —
                ;; same live GTK pointer at both coordinates.
                :grid-span-covers-1-1 (= (g/gtk-grid-get-child-at grid 0 1) (g/gtk-grid-get-child-at grid 1 1)))
         ;; Real interaction: direct FFI selection change, bypassing
         ;; set-drop-down-selected!, so the actual "notify::selected"
         ;; signal fires.
         (g/gtk-drop-down-set-selected dd 2)
         (swap! results assoc
                :state-selected-after-change (:selected @state)
                :dispatch-count-after-change @dispatch-count)
         ;; Programmatic sync-back elsewhere in the (hypothetical) app —
         ;; the drop-down should follow via set-drop-down-selected!'s
         ;; suppressing guard, no spurious extra dispatch.
         (reset! state {:selected 1})
         (swap! results assoc
                :dd-selected-after-programmatic (g/gtk-drop-down-get-selected dd)
                :dispatch-count-after-programmatic @dispatch-count)))
     :title "glitter drop-down+grid smoke" :width 320 :height 140
     :app-id "glitter.drop-down-grid-smoke" :auto-quit-ms 500)
    (println :results (pr-str @results))
    (when-not (and (= 0 (:dd-selected-on-mount @results))
                   (= "cell-0-0" (:grid-0-0 @results))
                   (= "cell-1-0" (:grid-1-0 @results))
                   (= "cell-0-1-span2" (:grid-0-1 @results))
                   (true? (:grid-span-covers-1-1 @results))
                   (= 2 (:state-selected-after-change @results))
                   (= 1 (:dispatch-count-after-change @results))
                   (= 1 (:dd-selected-after-programmatic @results))
                   (= 1 (:dispatch-count-after-programmatic @results)))
      (println :FAIL "see :results above")
      ;; Direct call — see test_runner.clj/exit for why a resolve-guarded
      ;; version of this silently never exits.
      (System/exit 1))))
