(ns glitter.gallery-chrome
  "Widget gallery 4 of 4: app CHROME and navigation.

  Siblings: gallery_inputs.clj, gallery_layout.clj, gallery_display.clj.
  Reference: docs/guide/widgets.md.

  Everything here is a controlled component: the widget has its own idea
  of what is showing, glitter has state, and a signal keeps them in step.
  That contract is the interesting part, and it is easy to get subtly
  wrong, so each one below is wired both ways — state drives the widget
  via a prop, and the widget's signal writes back.

  Two behaviours here are real GTK, not glitter quirks, and both surprise
  people:

  1. Mounting :notebook or :stack with pages already in them DISPATCHES.
     GTK auto-selects the first page as it is added, which emits
     switch-page / notify::visible-child-name before any user has done
     anything. So a dispatch count of 0 right after mount is the wrong
     expectation for either widget.

     Worse, and worth seeing on screen: those dispatches land during
     mount!'s FIRST render, which runs before mount! installs its
     add-watch (glitter/gtk.clj — render! then add-watch, in that order).
     So the state really does change, and nothing re-renders to show it.
     The counter below reads 0 on a freshly mounted window even though
     the atom already holds 2, until the first interaction re-renders.
     Verified live: the atom is 2 the instant mount! returns.

  2. :header-bar's :show-title-buttons puts GTK's own window-controls
     widget into the SAME pack-start region your children live in, and
     prepends it. glitter never calls pack_end on either bar, because
     gtk_header_bar_pack_end prepends into its own end region — feeding
     children one at a time, as the reconciler does, would silently
     reverse them with no public API to fix the order afterwards.

  :popover is opened by :menu-button's own internal click handling, which
  glitter does not see. That is why :on-activate exists here: the app
  learns the popover opened, and syncs its own :visible state to match.

  Run: jolt -M:gallery-chrome (or bb gallery-chrome). Needs a display."
  (:require [clojure.tools.logging :as log]
            [glitter.app :as app]
            [glitter.core :as core]
            [glitter.gtk :as gtk]
            [glitter.nexus.registry :as nxr]))

(defonce state
  (atom {:page 0 :stack-page "one" :menu-open? false :searching? true :dispatches 0}))

(defn view [{:keys [page stack-page menu-open? searching? dispatches]}]
  [:vbox {:spacing 8 :margin 0}
   ;; --- header bar ----------------------------------------------------------
   ;; One named title slot plus an ordered pack-start list. The FIRST hiccup
   ;; child is the title widget; the rest pack to the start.
   [:header-bar {:show-title-buttons false}
    [:label {:markup "<b>Chrome</b>"}]
    ;; :menu-button's single child is attached as its popover — a real
    ;; parenting relationship, not an ordinary tree child.
    [:menu-button {:label "menu"
                   :on {:activate [[:action/menu-opened]]}}
     [:popover {:visible menu-open? :has-arrow true
                :on {:closed [[:effect/assoc-in [:menu-open?] false]]}}
      [:vbox {:spacing 6 :margin 10}
       [:label {:label "popover content"}]
       [:button {:label "close" :on {:click [[:effect/assoc-in [:menu-open?] false]]}}]]]]]

   [:vbox {:spacing 8 :margin 12}
    ;; --- search bar ---------------------------------------------------------
    ;; A revealer-backed strip holding one child. v1 cannot call
    ;; gtk_search_bar_connect_entry, which would need a raw widget pointer
    ;; glitter has no hiccup convention for passing sideways — so the entry
    ;; is an ordinary child and :search-mode is driven from state.
    [:search-bar {:search-mode searching? :show-close-button false}
     [:search-entry {:placeholder "search…" :hexpand true}]]
    [:checkbutton {:label "search-mode" :active searching?
                   :on {:toggled [[:action/toggle :searching?]]}}]

    ;; --- notebook -------------------------------------------------------------
    ;; Tabs are GTK-generated: v1 always passes a NULL tab label, so there is
    ;; no per-child hiccup convention for custom tabs yet.
    [:label {:label "notebook — tabs are GTK-generated" :xalign 0.0}]
    [:notebook {:current-page page :height-request 90
                :on {:switch-page [[:effect/assoc-in [:page] [:glitter/value]]]}}
     [:label {:label "notebook page 0" :margin 12}]
     [:label {:label "notebook page 1" :margin 12}]
     [:label {:label "notebook page 2" :margin 12}]]

    ;; --- stack ----------------------------------------------------------------
    ;; Same idea, addressed by NAME rather than index. :stack-name is a
    ;; structural child prop, so it is plain and non-namespaced for the same
    ;; reason :grid's cell props are.
    [:label {:label "stack — pages addressed by name" :xalign 0.0}]
    [:stack {:visible-child-name stack-page :height-request 60
             :on {:visible-child-changed [[:effect/assoc-in [:stack-page] [:glitter/value]]]}}
     [:label {:label "stack page ONE" :stack-name "one" :margin 12}]
     [:label {:label "stack page TWO" :stack-name "two" :margin 12}]]
    [:hbox {:spacing 8}
     [:button {:label "show one" :on {:click [[:effect/assoc-in [:stack-page] "one"]]}}]
     [:button {:label "show two" :on {:click [[:effect/assoc-in [:stack-page] "two"]]}}]]

    ;; Reads 0 until something re-renders, even though the atom is already
    ;; 2 — see the ns docstring. Not a bug to fix here: it is the clearest
    ;; demonstration in the project of what "the view is a pure function of
    ;; state, re-run on change" does NOT promise, namely that a change made
    ;; before the watcher exists will paint itself.
    [:label {:label (str "page: " page "  ·  stack: " stack-page
                         "  ·  dispatches: " dispatches
                         (when (zero? dispatches) "  (stale — see docstring)"))
             :xalign 0.0}]]

   ;; --- action bar -----------------------------------------------------------
   ;; The header bar's sibling, for the bottom of a window. Same hybrid shape:
   ;; first child is the center widget, the rest pack to the start.
   [:action-bar {:revealed true}
    [:label {:label "action bar centre"}]
    [:button {:label "start-packed"}]]])

(nxr/register-system->state! deref)

(nxr/register-effect! :effect/assoc-in
                      (fn [_ system path v]
                        (swap! system assoc-in path v)
                        (swap! system update :dispatches inc)))

(nxr/register-action! :action/toggle
                      (fn [state k] [[:effect/assoc-in [k] (not (get state k))]]))

;; :menu-button opens its popover itself; this only mirrors that into state so
;; the :visible prop and the widget agree.
(nxr/register-action! :action/menu-opened
                      (fn [_state] [[:effect/assoc-in [:menu-open?] true]]))

(nxr/register-placeholder! :glitter/value
                           (fn [event] (get-in event [:glitter/dom-event :glitter/value])))

(nxr/on-error (fn [_ctx {:keys [err] :as error}]
                (log/error err "glitter.nexus dispatch error" (dissoc error :err))))

(core/set-dispatch!
 (fn [event actions] (nxr/dispatch state event actions)))

(defn -main [& _]
  (app/run (fn [window] (gtk/mount! window view state))
           :title "glitter gallery: chrome" :width 560 :height 620
           :app-id "glitter.gallery.chrome"))
