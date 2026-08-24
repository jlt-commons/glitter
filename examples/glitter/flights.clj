(ns glitter.flights
  "The 7GUIs 'Flight Booker' task
  (https://eugenkiss.github.io/7guis/tasks/#flight-booker) over glitter —
  a combobox choosing one-way/return flight, two date textfields, and a
  Book button whose enabled state depends on both fields' validity and
  (for return flights) their relative order. Per the spec: 'the primary
  challenge lies on modelling constraints between widgets... and within
  a widget.'

  First real consumer of glitter.nexus (see src/glitter/nexus.clj) —
  every interaction below dispatches at most two effects, never an
  action expansion (zero hand-written case-dispatch code): every field
  is a pure :effect/assoc-in plus a registered placeholder, except the
  \"Try again\" button, which dispatches two :effect/assoc-in calls
  back to back (see view below). todo.clj and crud.clj were
  retrofitted onto glitter.nexus in later tasks of this same arc (see
  those files' own docstrings for their pre-retrofit
  closures-vs-data-dispatch contrast) — this file still doesn't need
  the action-EXPANSION layer they use (`register-action!`) at all,
  since none of its interactions need to read current state before
  deciding what effects to run.

  Ports guis/flights.cljc from cjohansen/replicant-7uis (a real, tested,
  complete implementation — unlike crud.cljc's unfinished initial-take
  skeleton) for the domain-logic SHAPE (get-form-state), but:

  - Dates use the OFFICIAL spec's DD.MM.YYYY format, not the reference
    port's ISO-ish YYYY-MM-DD (a deviation in that file from its own
    spec's screenshot, not something to replicate).
  - Date parsing/formatting/comparison/'today' all go through
    jolt.time/tick (see deps.edn), not GTK/GLib FFI and not hand-rolled
    regex — cleaner domain/presentation separation. Note the pinned
    jolt-lang/time SHA is load-bearing for (t/today): before v0.0.7 it
    answered the UTC date, and this demo defaulted its departure field
    to YESTERDAY for the first 10 hours of every AEST day. See
    docs/guide/nexus.md.
  - `parse-date` below is NOT a bare `t/parse-date` call — verified live
    that `t/parse-date` is LENIENT under this Jolt port (doesn't throw
    on malformed input: '27.03.2014x' silently parsed to 2014-03-27
    ignoring the trailing garbage; 'not-a-date' silently parsed to
    -0001-11-30; '31.02.2014' — not a real date — silently rolled over
    to 2014-03-03). The round-trip wrapper (parse, reformat with the
    SAME formatter, reject unless the reformatted string exactly matches
    the trimmed input) is what actually makes 'T is colored red when
    ill-formatted' work; verified against all three bad inputs above
    plus a fourth ('7.3.2014', wrong digit count) and the valid case.

  Run: jolt -M:flights (the :flights task/alias) or bb flights. Needs a
  display; closes the window to exit."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [glitter.app :as app]
            [glitter.core :as core]
            [glitter.gtk :as gtk]
            [glitter.nexus.registry :as nxr]
            [tick.core :as t]))

(def ^:private date-formatter (t/formatter "dd.MM.yyyy"))

;; The ONLY date-validation strategy this demo uses — see the ns
;; docstring for why a bare t/parse-date call is not safe.
(defn parse-date [s]
  (when (string? s)
    (let [trimmed (str/trim s)]
      (when (seq trimmed)
        (try
          (let [d (t/parse-date trimmed date-formatter)]
            (when (= trimmed (t/format date-formatter d))
              d))
          (catch Exception _ nil))))))

(defn format-date [d]
  (t/format date-formatter d))

(defonce state
  (atom {:type :one-way
         :departure-date nil ;; nil = "use today's date"
         :return-date nil    ;; nil = "use departure-date's value"
         :booked? false}))

;; Domain logic, kept pure — the "separation of domain and presentation
;; logic" the spec calls out by name. today is read fresh each render
;; (t/today is cheap; no reason to cache it in state) rather than
;; snapshotted once at namespace-load time, so the demo behaves
;; correctly if left running across a real day boundary.
(defn get-form-state [{:keys [type departure-date return-date]}]
  (let [today (format-date (t/today))
        departure-value (or departure-date today)
        departure-parsed (parse-date departure-value)
        departure-invalid? (and departure-date (nil? departure-parsed))
        roundtrip? (= type :roundtrip)
        return-value (or return-date departure-value)
        return-parsed (parse-date return-value)
        return-invalid? (and roundtrip? return-date (nil? return-parsed))
        return-before-departure? (and roundtrip?
                                      (not return-invalid?)
                                      (not departure-invalid?)
                                      (t/< return-parsed departure-parsed))]
    {:type type
     :departure {:value departure-value :invalid? departure-invalid?}
     :return {:value return-value :disabled? (not roundtrip?) :invalid? return-invalid?}
     :book-disabled? (boolean (or departure-invalid? return-invalid? return-before-departure?))}))

;; See crud.clj's field-row for the same shape/:xalign-must-be-a-float
;; note — this demo's fields don't share crud.clj's helper directly
;; (different label width / no shared ns), so it's redefined here.
(defn- field-row [label value error? path]
  [:hbox {:spacing 8}
   [:label {:label label :width-chars 8 :xalign 0.0}]
   [:entry {:text value :hexpand true
            :class (if error? ["error"] [])
            :on {:change [[:effect/assoc-in [path] [:glitter/value]]]}}]])

(defn view [state]
  (if (:booked? state)
    (let [{:keys [type departure return]} (get-form-state state)]
      [:vbox {:spacing 12 :margin 16}
       [:label {:markup "<span size='xx-large' weight='bold'>Flight Booker</span>" :halign :start}]
       [:label {:label (str "You have booked a " (name type) " flight on " (:value departure)
                            (when (= type :roundtrip) (str ", returning on " (:value return)))
                            ".")
                :halign :start :wrap true}]
       [:button {:label "Try again" :on {:click [[:effect/assoc-in [:booked?] false]
                                                 [:effect/assoc-in [:type] :one-way]]}}]])
    (let [{:keys [type departure return book-disabled?]} (get-form-state state)]
      [:vbox {:spacing 12 :margin 16}
       [:label {:markup "<span size='xx-large' weight='bold'>Flight Booker</span>" :halign :start}]
       [:drop-down {:items ["one-way flight" "return flight"]
                    :selected (if (= type :roundtrip) 1 0)
                    :on {:selected-changed
                         [[:effect/assoc-in [:type] [:fmt/nth [:one-way :roundtrip] [:glitter/value]]]]}}]
       (field-row "Departure:" (:value departure) (:invalid? departure) :departure-date)
       [:hbox {:spacing 8}
        [:label {:label "Return:" :width-chars 8 :xalign 0.0}]
        [:entry {:text (:value return) :hexpand true
                 :sensitive (not (:disabled? return))
                 :class (if (:invalid? return) ["error"] [])
                 :on {:change [[:effect/assoc-in [:return-date] [:glitter/value]]]}}]]
       [:button {:label "Book" :sensitive (not book-disabled?)
                 :on {:click [[:effect/assoc-in [:booked?] true]]}}]])))

(nxr/register-effect! :effect/assoc-in
                      (fn [_ system path v] (swap! system assoc-in path v)))

(nxr/register-placeholder! :glitter/value
                           (fn [event] (get-in event [:glitter/dom-event :glitter/value])))

(nxr/register-placeholder! :fmt/nth (fn [_ coll idx] (nth coll idx)))

(nxr/on-error (fn [_ctx {:keys [err] :as error}]
                (log/error err "glitter.nexus dispatch error" (dissoc error :err))))

(core/set-dispatch!
 (fn [event actions] (nxr/dispatch state event actions)))

(defn -main [& _]
  (app/run (fn [window] (gtk/mount! window view state))
           :title "glitter · Flight Booker" :width 320 :height 260 :app-id "glitter.flights"))
