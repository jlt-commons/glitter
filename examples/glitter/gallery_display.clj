(ns glitter.gallery-display
  "Widget gallery 3 of 4: the READ-ONLY widgets — the ones with no signal
  of their own, driven entirely by re-applied props.

  Siblings: gallery_inputs.clj, gallery_layout.clj, gallery_chrome.clj.
  Reference: docs/guide/widgets.md.

  These are the widgets where glitter's model is at its simplest, and the
  reason is worth naming: a widget with no signal has nothing to write
  back, so there is no round trip to reason about at all. The view is a
  pure function of state, and that is the whole story. One :scale at the
  top drives every widget below it, so a single value flows outward into
  nine different presentations of itself.

  :label vs :inscription is the one real choice here. :label is the
  general-purpose text widget; :inscription is GTK's cheap one, built for
  many short throwaway labels (list rows, cells) where :label's full
  layout machinery is overkill. It cannot be selected or have markup —
  see docs/guide/widgets.md.

  :image and :picture are also not interchangeable. :image is for icons
  at a fixed pixel size; :picture is GdkPaintable-based and scales to fit
  its allocation, so it is the one to reach for with real image content.
  The PNG under examples/assets/ is committed and generated, so this demo
  does not depend on anything machine-local.

  Run: jolt -M:gallery-display (or bb gallery-display). Needs a display."
  (:require [clojure.tools.logging :as log]
            [glitter.app :as app]
            [glitter.core :as core]
            [glitter.gtk :as gtk]
            [nexus.registry :as nxr]))

(def ^:private mark "examples/assets/glitter-mark.png")

(defonce state (atom {:level 40.0 :busy? true :revealed? true}))

(defn view [{:keys [level busy? revealed?]}]
  (let [fraction (/ level 100.0)]
    [:vbox {:spacing 8 :margin 14}
     [:label {:markup "<span size='large' weight='bold'>Display</span>" :halign :start}]

     ;; The one input on the page. Everything below is a pure function of it.
     [:hbox {:spacing 8}
      [:label {:label "drives all" :width-chars 11 :xalign 0.0}]
      [:scale {:min 0 :max 100 :step 1 :value level :digits 0 :hexpand true
               :on {:value-changed [[:effect/assoc-in [:level] [:glitter/value]]]}}]]

     [:separator {}]

     ;; --- text ----------------------------------------------------------------
     ;; :label takes Pango markup; :inscription takes plain text only, and
     ;; ellipsizes via :text-overflow rather than :ellipsize.
     [:hbox {:spacing 8}
      [:label {:label "label" :width-chars 11 :xalign 0.0}]
      [:label {:markup (str "value is <b>" (int level) "</b>") :xalign 0.0 :hexpand true}]]
     [:hbox {:spacing 8}
      [:label {:label "inscription" :width-chars 11 :xalign 0.0}]
      [:inscription {:text (str "cheap text, no markup: " (int level))
                     :text-overflow :ellipsize-end :hexpand true}]]

     ;; --- progress and level ---------------------------------------------------
     ;; Both show a magnitude; :progress-bar is a task's completion (0.0-1.0)
     ;; and can show its own text, :level-bar is a measurement on its own scale.
     [:hbox {:spacing 8}
      [:label {:label "progress" :width-chars 11 :xalign 0.0}]
      [:progress-bar {:fraction fraction :show-text true
                      :text (str (int level) "%") :hexpand true}]]
     [:hbox {:spacing 8}
      [:label {:label "level" :width-chars 11 :xalign 0.0}]
      [:level-bar {:min-value 0 :max-value 100 :value level :hexpand true}]]

     ;; --- images ---------------------------------------------------------------
     [:hbox {:spacing 8}
      [:label {:label "image" :width-chars 11 :xalign 0.0}]
      ;; a named theme icon at a fixed size
      [:image {:icon-name "dialog-information" :pixel-size (+ 16 (int (/ level 3)))}]
      [:label {:label "picture" :xalign 0.0}]
      ;; real content, scaled to its allocation
      [:picture {:file mark :content-fit :contain :can-shrink true
                 :alternative-text "the glitter mark"
                 :width-request 64 :height-request 64}]]

     ;; --- busy and disclosure ---------------------------------------------------
     ;; :spinner animates while :spinning is true — it is the one widget here
     ;; whose appearance changes with no state change at all.
     [:hbox {:spacing 12}
      [:label {:label "spinner" :width-chars 11 :xalign 0.0}]
      [:spinner {:spinning busy?}]
      [:checkbutton {:label "spinning" :active busy?
                     :on {:toggled [[:action/toggle :busy?]]}}]
      [:checkbutton {:label "revealed" :active revealed?
                     :on {:toggled [[:action/toggle :revealed?]]}}]]

     ;; :revealer animates its child in and out; :expander hides one behind a
     ;; disclosure triangle. :revealer is driven purely by props, while
     ;; :expander has a user-driven state worth watching via :on-expanded.
     [:revealer {:reveal-child revealed? :transition-type :slide-down
                 :transition-duration 250}
      [:frame {:label "revealer child"}
       [:label {:label "revealed by a prop, animated by GTK" :margin 8}]]]

     [:expander {:label "expander — click the triangle" :expanded false}
      [:label {:label "expander child" :margin 8 :xalign 0.0}]]]))

(nxr/register-system->state! deref)

(nxr/register-effect! :effect/assoc-in
                      (fn [_ system path v] (swap! system assoc-in path v)))

;; :checkbutton's "toggled" carries no value (see gallery_inputs.clj), so the
;; flip is computed from state by a pure expansion.
(nxr/register-action! :action/toggle
                      (fn [state k] [[:effect/assoc-in [k] (not (get state k))]]))

(nxr/register-placeholder! :glitter/value
                           (fn [event] (get-in event [:glitter/dom-event :glitter/value])))

(nxr/on-error (fn [_ctx {:keys [err] :as error}]
                (log/error err "glitter.nexus dispatch error" (dissoc error :err))))

(core/set-dispatch!
 (fn [event actions] (nxr/dispatch state event actions)))

(defn -main [& _]
  (app/run (fn [window] (gtk/mount! window view state))
           :title "glitter gallery: display" :width 560 :height 620
           :app-id "glitter.gallery.display"))
