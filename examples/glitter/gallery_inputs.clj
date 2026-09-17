(ns glitter.gallery-inputs
  "Widget gallery 1 of 4: every VALUE-BEARING widget glitter ships.

  The other three galleries are gallery_layout.clj (containers),
  gallery_display.clj (read-only widgets) and gallery_chrome.clj (app
  chrome). Between them they use all 43 registered tags; see
  docs/guide/widgets.md, which quotes these files.

  This one is not a widget zoo. Every widget below writes into ONE state
  atom, and the readout at the bottom renders that atom — so what you are
  watching is the actual round trip glitter is built on: widget signal ->
  action data -> effect -> swap! -> re-render. Nothing here is a closure
  over a widget, and no handler reads a widget back.

  Two things worth copying:

  1. Most handlers are the same one-liner:
     :on {:change [[:effect/assoc-in [:key] [:glitter/value]]]}
     The [:glitter/value] placeholder resolves to whatever the signal
     carried (see the signal-value table in glitter/widget.clj), so the
     handler needs no knowledge of the widget at all.

  2. :checkbutton and :toggle-button are the exception, and it is worth
     understanding why rather than pattern-matching around it. Their
     \"toggled\" signal has NO value-fn registered, so [:glitter/value]
     would resolve to nil and silently write nil into state. They use an
     action-expansion (:action/toggle) instead: a pure (state & args) ->
     more-actions fn that reads the current value and flips it. Same
     reason todo.clj's own toggle is an expansion.

  Run: jolt -M:gallery-inputs (or bb gallery-inputs). Needs a display."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [glitter.app :as app]
            [glitter.core :as core]
            [glitter.gtk :as gtk]
            [nexus.registry :as nxr]))

(def ^:private fruits ["apple" "banana" "cherry"])

(defonce state
  (atom {:entry ""
         :password ""
         :search ""
         :editable "click me"
         :spin 3.0
         :scale 40.0
         :volume 0.5
         :switch? false
         :check? true
         :toggle? false
         :fruit 0
         :date [2026 8 24]
         :clicks 0}))

;; One row: a fixed-width label plus whatever widget the caller passes.
;; A plain function returning hiccup, spliced with (field-row ...) — NOT
;; [field-row ...]. glitter has no function-as-tag convention; a vector
;; whose head is a function is treated as an opaque child and renders as
;; literal object text. See CONTRIBUTING.md invariant #9.
(defn- field-row [label widget]
  [:hbox {:spacing 8}
   [:label {:label label :width-chars 11 :xalign 0.0}]
   widget])

(defn view [state]
  [:vbox {:spacing 6 :margin 14}
   [:label {:markup "<span size='large' weight='bold'>Inputs</span>" :halign :start}]

   ;; --- text: four widgets, one GtkEditable contract ------------------------
   ;; :entry, :password-entry, :search-entry and :editable-label all
   ;; implement GtkEditable via a delegate, so all four deliver their text
   ;; through the same "changed" signal and the same [:glitter/value].
   (field-row "entry"
              [:entry {:text (:entry state) :placeholder "type here" :hexpand true
                       :on {:change [[:effect/assoc-in [:entry] [:glitter/value]]]}}])
   ;; No :placeholder on :password-entry — GTK exposes no setter for it on
   ;; this widget, so glitter does not accept the prop. :entry and
   ;; :search-entry both do.
   (field-row "password"
              [:password-entry {:text (:password state) :show-peek-icon true :hexpand true
                                :on {:change [[:effect/assoc-in [:password] [:glitter/value]]]}}])
   ;; :search-entry also has its own :search-changed, debounced by
   ;; :search-delay ms — except clearing to empty, which fires at once.
   (field-row "search"
              [:search-entry {:text (:search state) :placeholder "filter" :search-delay 100 :hexpand true
                              :on {:search-changed [[:effect/assoc-in [:search] [:glitter/value]]]}}])
   (field-row "editable"
              [:editable-label {:text (:editable state) :hexpand true
                                :on {:change [[:effect/assoc-in [:editable] [:glitter/value]]]}}])

   ;; --- numbers: three widgets, three different getters ---------------------
   ;; All three emit a signal NAMED "value-changed", and all three need a
   ;; different getter to read it back — which is why signal-value is keyed
   ;; by [tag signal] rather than by signal name. See docs/guide/widgets.md.
   (field-row "spin"
              [:spin-button {:min 0 :max 10 :step 1 :value (:spin state) :digits 0
                             :on {:value-changed [[:effect/assoc-in [:spin] [:glitter/value]]]}}])
   (field-row "scale"
              [:scale {:min 0 :max 100 :step 1 :value (:scale state) :digits 0 :hexpand true
                       :on {:value-changed [[:effect/assoc-in [:scale] [:glitter/value]]]}}])
   (field-row "volume"
              [:scale-button {:min 0 :max 1 :step 0.1 :value (:volume state)
                              :on {:value-changed [[:effect/assoc-in [:volume] [:glitter/value]]]}}])

   ;; --- booleans ------------------------------------------------------------
   ;; :switch carries its new value; :checkbutton and :toggle-button do not
   ;; (see the ns docstring), so those two dispatch an action-expansion.
   [:hbox {:spacing 12}
    [:label {:label "booleans" :width-chars 11 :xalign 0.0}]
    [:switch {:active (:switch? state) :valign :center
              :on {:state-set [[:effect/assoc-in [:switch?] [:glitter/value]]]}}]
    [:checkbutton {:label "check" :active (:check? state)
                   :on {:toggled [[:action/toggle :check?]]}}]
    [:toggle-button {:label "toggle" :active (:toggle? state)
                     :on {:toggled [[:action/toggle :toggle?]]}}]]

   ;; --- choosing ------------------------------------------------------------
   ;; :drop-down delivers an int INDEX, not the item text, so state holds
   ;; the index and the readout looks it up. flights.clj shows the other
   ;; option: a :fmt/nth placeholder that indexes into the vector before
   ;; the value ever reaches state.
   (field-row "drop-down"
              [:drop-down {:items fruits :selected (:fruit state)
                           :on {:selected-changed [[:effect/assoc-in [:fruit] [:glitter/value]]]}}])

   ;; --- buttons -------------------------------------------------------------
   [:hbox {:spacing 12}
    [:label {:label "buttons" :width-chars 11 :xalign 0.0}]
    [:button {:label "click me" :on {:click [[:action/click]]}}]
    [:link-button {:label "glitter" :uri "https://github.com/jlt-commons/glitter"}]]

   ;; --- a real value type: GDateTime round-tripped as [y m d] ---------------
   [:expander {:label "calendar" :expanded false}
    [:calendar {:date (:date state)
                :on {:day-selected [[:effect/assoc-in [:date] [:glitter/value]]]}}]]

   [:separator {}]
   ;; The whole point: this label is a pure function of the atom above.
   [:label {:label (str "state: " (pr-str (dissoc state :date)))
            :xalign 0.0 :wrap true :halign :start}]
   [:label {:label (str "date: " (str/join "-" (:date state))
                        "    clicks: " (:clicks state))
            :xalign 0.0 :halign :start}]])

(nxr/register-system->state! deref)

(nxr/register-effect! :effect/assoc-in
                      (fn [_ system path v] (swap! system assoc-in path v)))

;; Pure: reads the current value, returns the effect that flips it. The
;; widget is never consulted.
(nxr/register-action! :action/toggle
                      (fn [state k] [[:effect/assoc-in [k] (not (get state k))]]))

(nxr/register-action! :action/click
                      (fn [state] [[:effect/assoc-in [:clicks] (inc (:clicks state 0))]]))

(nxr/register-placeholder! :glitter/value
                           (fn [event] (get-in event [:glitter/dom-event :glitter/value])))

(nxr/on-error (fn [_ctx {:keys [err] :as error}]
                (log/error err "glitter.nexus dispatch error" (dissoc error :err))))

(core/set-dispatch!
 (fn [event actions] (nxr/dispatch state event actions)))

(defn -main [& _]
  (app/run (fn [window] (gtk/mount! window view state))
           :title "glitter gallery: inputs" :width 560 :height 620
           :app-id "glitter.gallery.inputs"))
