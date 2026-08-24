(ns glitter.gallery-layout
  "Widget gallery 2 of 4: the CONTAINERS, and how each one decides where a
  child goes.

  Siblings: gallery_inputs.clj, gallery_display.clj, gallery_chrome.clj.
  Reference: docs/guide/widgets.md.

  Containers are where glitter's reconciler does its least DOM-like work,
  because GTK containers do not agree on what \"add a child\" means. Four
  distinct shapes appear below, and the differences are the point:

  - ORDERED list — :box (:hbox/:vbox are sugar for it), :flow-box,
    :list-box. Children append in hiccup order.
  - FIXED NAMED SLOTS — :center-box (start/center/end), :paned
    (start/end). Position comes from child ORDER in hiccup, and the slot
    count is hard: there is no room for a transient extra occupant.
  - CHILD-DRIVEN placement — :grid. A cell comes from the CHILD's own
    props (:grid-column/:grid-row/:grid-column-span/:grid-row-span),
    because gtk_grid_attach takes the position as arguments and no
    widget's own :apply could know what it is about to be attached to.
  - SINGLE CHILD — :frame, :aspect-frame, :scrolled, :window-handle.

  Three things this file deliberately does NOT do, all documented in
  docs/guide/limitations.md:

  1. It never swaps a slot's hiccup TAG in :center-box/:paned/:overlay
     while the slots are occupied. The reconciler handles a tag mismatch
     as insert-new-then-remove-old, and a fixed-slot container has no
     room to hold both — in :center-box that corrupts an unrelated third
     slot. Change props, not tags.
  2. It never moves a child between grid cells on a later render.
     Structural props are read once, at first attach.
  3. It drives :paned's divider from a :scale rather than by dragging,
     because the recorded GIFs are steered by keyboard only.

  Run: jolt -M:gallery-layout (or bb gallery-layout). Needs a display."
  (:require [clojure.tools.logging :as log]
            [glitter.app :as app]
            [glitter.core :as core]
            [glitter.gtk :as gtk]
            [glitter.nexus.registry :as nxr]))

(def ^:private planets ["Mercury" "Venus" "Earth" "Mars"])

(defonce state (atom {:divider 150 :row 0}))

(defn- titled [label & children]
  (into [:frame {:label label}
         [:vbox {:spacing 6 :margin 8}]]
        children))

(defn view [{:keys [divider row]}]
  [:vbox {:spacing 10 :margin 12}
   ;; :window-handle makes its child a drag handle for the window — the
   ;; same single-child strategy as :frame, no props of its own.
   [:window-handle {}
    [:label {:markup "<span size='large' weight='bold'>Containers</span>" :halign :start}]]

   ;; --- child-driven placement ---------------------------------------------
   ;; Each cell's position lives on the CHILD, in plain non-namespaced keys.
   ;; They must be non-namespaced: glitter.core drops namespaced attributes
   ;; upstream of every backend, so :grid/column would silently never arrive.
   [:frame {:label "grid — placement from the child's own props"}
    [:grid {:row-spacing 4 :column-spacing 10 :margin 8}
     [:label {:label "r0 c0" :grid-column 0 :grid-row 0}]
     [:label {:label "r0 c1" :grid-column 1 :grid-row 0}]
     [:label {:label "r1 c0" :grid-column 0 :grid-row 1}]
     [:label {:label "r1 c1" :grid-column 1 :grid-row 1}]
     [:label {:label "r2 spans both columns" :grid-column 0 :grid-row 2 :grid-column-span 2}]]]

   ;; --- fixed named slots ---------------------------------------------------
   ;; Three children, three slots, by order. A fourth would have nowhere to go.
   [:frame {:label "center-box — three named slots, by child order"}
    [:center-box {:margin 8}
     [:label {:label "start"}]
     [:label {:label "center"}]
     [:label {:label "end"}]]]

   ;; Two slots. :position is a real GObject property, so it round-trips:
   ;; the scale below writes it, and dragging the divider would write back
   ;; through :on-position-changed.
   [:frame {:label "paned — two slots, divider driven by state"}
    [:vbox {:spacing 6 :margin 8}
     [:paned {:position divider :height-request 70}
      [:label {:label "start pane"}]
      [:label {:label "end pane"}]]
     [:hbox {:spacing 8}
      [:label {:label "divider" :width-chars 8 :xalign 0.0}]
      [:scale {:min 60 :max 320 :step 10 :value divider :digits 0 :hexpand true
               :on {:value-changed [[:effect/assoc-in [:divider] [:glitter/value]]]}}]]]]

   ;; --- one main child plus floating children -------------------------------
   ;; The first hiccup child is the main content; every later child floats on
   ;; top. GTK exposes no way to enumerate the floating set, so glitter can
   ;; add and remove them but never read them back.
   [:frame {:label "overlay — first child is content, the rest float"}
    [:overlay {:margin 8 :height-request 60}
     [:label {:label "main content"}]
     [:label {:label "overlay" :halign :end :valign :start}]]]

   ;; --- ordered lists --------------------------------------------------------
   ;; :flow-box wraps its children; :list-box stacks them and carries a
   ;; selection. Both auto-wrap each child in an opaque row object, which is
   ;; why a keyed reorder of either had a use-after-dispose bug worth a smoke.
   [:hbox {:spacing 10}
    (titled "flow-box"
            (into [:flow-box {}]
                  (for [p planets] [:label {:label p :margin 4}])))
    (titled "list-box — selection is a row INDEX"
            (into [:list-box {:on {:row-selected [[:effect/assoc-in [:row] [:glitter/value]]]}}]
                  (for [p planets] [:label {:label p :margin 4}])))]

   [:separator {}]

   ;; --- single-child wrappers ------------------------------------------------
   ;; :aspect-frame keeps its child at a fixed ratio; :scrolled clips and
   ;; scrolls one. A :scrolled child that is not GtkScrollable (a :vbox is
   ;; not) gets silently wrapped by GTK in its own GtkViewport, so the live
   ;; tree has one more level than the hiccup does.
   [:hbox {:spacing 10}
    [:aspect-frame {:ratio 2.0 :obey-child false :width-request 120 :height-request 60}
     [:label {:label "2:1"}]]
    [:scrolled {:hexpand true :height-request 60}
     (into [:vbox {:spacing 2}]
           (for [i (range 12)] [:label {:label (str "scrolled row " i) :xalign 0.0}]))]]

   [:label {:label (str "selected row: " row "  ·  divider: " (int divider))
            :xalign 0.0 :halign :start}]])

(nxr/register-system->state! deref)

(nxr/register-effect! :effect/assoc-in
                      (fn [_ system path v] (swap! system assoc-in path v)))

(nxr/register-placeholder! :glitter/value
                           (fn [event] (get-in event [:glitter/dom-event :glitter/value])))

(nxr/on-error (fn [_ctx {:keys [err] :as error}]
                (log/error err "glitter.nexus dispatch error" (dissoc error :err))))

(core/set-dispatch!
 (fn [event actions] (nxr/dispatch state event actions)))

(defn -main [& _]
  (app/run (fn [window] (gtk/mount! window view state))
           :title "glitter gallery: containers" :width 620 :height 700
           :app-id "glitter.gallery.layout"))
