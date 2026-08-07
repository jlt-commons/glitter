(ns glitter.todo
  "A task board demo — port of glimmer's examples/glimmer/todo.clj to
  glitter's Replicant-style model. Exercises derived counts computed
  inline from one state atom (no reaction — glitter has no reactive-
  derivation primitive; the whole view is just re-run on every change),
  an expanding entry (:change / :activate, with a placeholder),
  checkbutton toggles, and list rendering inside a frame. Layout uses the
  universal GtkWidget props (margins, halign, hexpand, valign) and
  resolves GtkAlign nicks (:start, :center) at runtime via glitter.genum
  — so glitter carries no enum-constant table.

  Contrast with glimmer's original: there, :done toggling and the draft
  entry are component-local ratoms mutated by closures
  (#(swap! state update-in [idx :done] not), #(reset! draft %)) captured
  per-row/per-widget at render time. Here, ALL state lives in one
  top-level atom; the view is a pure function of it; and every handler is
  DATA — an action tuple carrying whatever the closure used to
  close over (the row index, the new entry text) — dispatched through one
  global handler, never a closure. That global handler is
  glitter.nexus (see src/glitter/nexus.clj), not a hand-written `case`
  form: :action/toggle/:action/add-task are ACTION-EXPANSIONS (they read
  current state to decide what should happen), the same layer
  examples/glitter/crud.clj's select-row/create/update/delete use;
  examples/glitter/flights.clj is the pure-effects-only sibling, needing
  no action-expansion layer at all.

  Run: jolt -M:todo (the :todo task/alias) or bb todo. Needs a display;
  closes the window to exit."
  (:require [clojure.tools.logging :as log]
            [glitter.app :as app]
            [glitter.core :as core]
            [glitter.gtk :as gtk]
            [glitter.nexus.registry :as nxr]))

(defonce state
  (atom {:tasks [{:text "Try the glitter counter demo" :done true}
                 {:text "Toggle a task below"          :done false}
                 {:text "Add one of your own"          :done false}]
         :draft ""}))

;; A small "stat card": a big number over a muted label. Called as a plain
;; function returning hiccup — (stat-card n label), NOT [stat-card n label].
;; Unlike glimmer/Reagent, glitter's hiccup (ported from Replicant) has no
;; function-as-tag convention: glitter.hiccup/hiccup? requires a KEYWORD in
;; position 0, so a vector whose first element is a function value fails
;; that check and falls through to being treated as an opaque child value
;; (stringified via `str`) rather than expanded. Replicant's real component
;; mechanism is glitter.alias/defalias + a qualified-keyword tag (see
;; examples/glitter/aliased.clj) — for a helper this small and non-reusable,
;; a plain function call is simpler than registering an alias for it.
(defn- stat-card [n label]
  [:vbox {:spacing 0 :margin-start 14 :margin-end 14 :margin-top 10 :margin-bottom 10}
   [:label {:markup (str "<span size='xx-large' weight='bold'>" n "</span>") :halign :start}]
   [:label {:markup (str "<span color='#888888'>" label "</span>") :halign :start}]])

;; One task row: a checkbox (toggles :done by index) and the text, struck
;; through when done. `idx` stays stable because we only append/toggle, never
;; remove from the middle (positional reconciler — no keyed children yet).
;; The toggle handler carries idx as DATA ([:action/toggle idx]) rather than
;; closing over it — glimmer's original closes over both idx and state
;; directly in the checkbutton's :on-toggled fn.
(defn- task-row [idx {:keys [text done]}]
  [:hbox {:spacing 8}
   [:checkbutton {:active done :valign :center
                  :on {:toggled [[:action/toggle idx]]}}]
   [:label {:markup (if done (str "<s>" text "</s>") text)
            :halign :start :hexpand true :valign :center}]])

(defn view [{:keys [tasks draft]}]
  (let [total (count tasks)
        done  (count (filter :done tasks))
        left  (count (remove :done tasks))]
    [:vbox {:spacing 16 :margin 20}
     [:label {:markup "<span size='xx-large' weight='bold'>Tasks</span>" :halign :start}]

     [:hbox {:spacing 8}
      (stat-card total "total")
      (stat-card done  "done")
      (stat-card left  "left")]

     [:frame {:label (str left " remaining") :vexpand true}
      [:vbox {:spacing 6 :margin 12}
       (if (empty? tasks)
         [[:label {:markup "<span color='#888888'>Nothing here yet — add a task below.</span>"
                   :halign :start}]]
         (for [[idx t] (map-indexed vector tasks)]
           (task-row idx t)))]]

     [:hbox {:spacing 8}
      [:entry {:text draft :placeholder "Add a task…"
               :hexpand true :valign :center
               :on {:change   [[:effect/assoc-in [:draft] [:glitter/value]]]
                    :activate [[:action/add-task]]}}]
      [:button {:label "Add" :valign :center :on {:click [[:action/add-task]]}}]]]))

;; :action/set-draft is a pure passthrough. :action/toggle/:action/add-task
;; need to READ current state (the row's CURRENT :done value to `not`;
;; :draft's current value to decide whether to add anything and to know
;; what to conj) — glitter.nexus ACTION-EXPANSIONS, same layer crud.clj's
;; select-row/create/update/delete use, see that file for the contrast
;; with flights.clj's pure-effects-only shape.
(nxr/register-effect! :effect/assoc-in
                      (fn [_ system path v] (swap! system assoc-in path v)))

(nxr/register-placeholder! :glitter/value
                           (fn [event] (get-in event [:glitter/dom-event :glitter/value])))

(nxr/register-action! :action/toggle
                      (fn [state idx]
                        [[:effect/assoc-in [:tasks idx :done] (not (get-in state [:tasks idx :done]))]]))

(nxr/register-action! :action/add-task
                      (fn [{:keys [draft tasks]}]
                        (if (seq draft)
                          [[:effect/assoc-in [:tasks] (conj tasks {:text draft :done false})]
                           [:effect/assoc-in [:draft] ""]]
                          [])))

(nxr/register-system->state! deref)
(nxr/on-error (fn [_ctx {:keys [err] :as error}]
                (log/error err "glitter.nexus dispatch error" (dissoc error :err))))

(core/set-dispatch!
 (fn [event actions] (nxr/dispatch state event actions)))

(defn -main [& _]
  (app/run (fn [window] (gtk/mount! window view state))
           :title "glitter · tasks" :width 480 :height 420 :app-id "glitter.todo"))
