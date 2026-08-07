(ns glitter.temperature
  "The 7GUIs 'Temperature Converter' task
  (https://eugenkiss.github.io/7guis/tasks/#temp) over glitter — two
  linked numeric fields (Celsius, Fahrenheit); editing one immediately
  updates the other. Per the spec: widgets 'indirectly linked to each
  other... in an equational way... must have their values updated
  eagerly... The exception being the widget that caused the change.'

  Third glitter.nexus consumer, after flights.clj (pure effects only)
  and crud.clj/todo.clj (action-expansion retrofits). This demo needs
  exactly one action-expansion (:action/set-temperature) because which
  field is the SOURCE and which is the DERIVED value depends on which
  one the user just edited — a pure decision `set-temperature` below
  makes, not something a bare :effect/assoc-in can express.

  Ports the domain-logic SHAPE (fahrenheit->celsius, celsius->fahrenheit,
  set-temperature) from guis/temperature.cljc in
  cjohansen/replicant-7uis, but set-temperature adds an explicit
  both-nil guard upstream doesn't have: when :fmt/number (below) fails
  to parse the just-edited field's text, upstream's bare
  `(or celsius (fahrenheit->celsius fahrenheit))` shape would call
  fahrenheit->celsius on nil and throw — caught harmlessly by
  glitter.nexus's own exception handling, but relying on that as the
  invalid-input strategy is exactly the silent-swallow risk this
  project's nexus port final review flagged. This port's set-temperature
  returns an EXPLICIT no-op (`[]`, no effects, no swap!, no re-render)
  when neither field parsed — the just-typed invalid text stays in
  GTK's own live entry buffer untouched, the other field's last-valid
  value stays untouched, with no special-casing anywhere else.

  Run: jolt -M:temperature (the :temperature task/alias) or
  bb temperature. Needs a display; closes the window to exit."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [glitter.app :as app]
            [glitter.core :as core]
            [glitter.gtk :as gtk]
            [glitter.nexus.registry :as nxr]))

(defn fahrenheit->celsius [f]
  (* (- f 32) (/ 5.0 9)))

(defn celsius->fahrenheit [c]
  (+ (* c (/ 9.0 5)) 32))

(defn set-temperature [{:keys [celsius fahrenheit]}]
  (cond
    (some? celsius)    [[:effect/assoc-in [:celsius] celsius]
                        [:effect/assoc-in [:fahrenheit] (celsius->fahrenheit celsius)]]
    (some? fahrenheit) [[:effect/assoc-in [:celsius] (fahrenheit->celsius fahrenheit)]
                        [:effect/assoc-in [:fahrenheit] fahrenheit]]
    :else               []))

(defn format-number [n]
  (if (and (number? n) (== n (long n)))
    (str (long n))
    (str n)))

(defonce state
  (atom {:celsius 0.0 :fahrenheit 32.0}))

(defn view [state]
  [:vbox {:spacing 12 :margin 16}
   [:label {:markup "<span size='xx-large' weight='bold'>Temperature Converter</span>" :halign :start}]
   [:hbox {:spacing 8}
    [:entry {:text (format-number (:celsius state)) :width-chars 8
             :on {:change [[:action/set-temperature {:celsius [:fmt/number [:glitter/value]]}]]}}]
    [:label {:label "Celsius ="}]
    [:entry {:text (format-number (:fahrenheit state)) :width-chars 8
             :on {:change [[:action/set-temperature {:fahrenheit [:fmt/number [:glitter/value]]}]]}}]
    [:label {:label "Fahrenheit"}]]])

(nxr/register-effect! :effect/assoc-in
                      (fn [_ system path v] (swap! system assoc-in path v)))

(nxr/register-placeholder! :glitter/value
                           (fn [event] (get-in event [:glitter/dom-event :glitter/value])))

(nxr/register-placeholder! :fmt/number
                           (fn [_ s]
                             (when (string? s)
                               (let [trimmed (str/trim s)]
                                 (when (seq trimmed)
                                   (try (Double/parseDouble trimmed) (catch Exception _ nil)))))))

(nxr/register-action! :action/set-temperature
                      (fn [_state temps] (set-temperature temps)))

(nxr/register-system->state! deref)
(nxr/on-error (fn [_ctx {:keys [err] :as error}]
                (log/error err "glitter.nexus dispatch error" (dissoc error :err))))

(core/set-dispatch!
 (fn [event actions] (nxr/dispatch state event actions)))

(defn -main [& _]
  (app/run (fn [window] (gtk/mount! window view state))
           :title "glitter · Temperature Converter" :width 320 :height 100 :app-id "glitter.temperature"))
