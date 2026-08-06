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
  global handler, never a closure.

  Run: jolt -M:todo (the :todo task/alias) or bb todo. Needs a display;
  closes the window to exit."
  (:require [glitter.app :as app]
            [glitter.core :as core]
            [glitter.gtk :as gtk]))

(defonce state
  (atom {:tasks [{:text "Try the glitter counter demo" :done true}
                 {:text "Toggle a task below"          :done false}
                 {:text "Add one of your own"          :done false}]
         :draft ""}))

;; A small "stat card": a big number over a muted label.
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
      [stat-card total "total"]
      [stat-card done  "done"]
      [stat-card left  "left"]]

     [:frame {:label (str left " remaining") :vexpand true}
      [:vbox {:spacing 6 :margin 12}
       (if (empty? tasks)
         [[:label {:markup "<span color='#888888'>Nothing here yet — add a task below.</span>"
                   :halign :start}]]
         (for [[idx t] (map-indexed vector tasks)]
           [task-row idx t]))]]

     [:hbox {:spacing 8}
      [:entry {:text draft :placeholder "Add a task…"
               :hexpand true :valign :center
               :on {:change   [[:action/set-draft]]
                    :activate [[:action/add-task]]}}]
      [:button {:label "Add" :valign :center :on {:click [[:action/add-task]]}}]]]))

;; The entry's live text does NOT travel through the action tuple — hiccup
;; :on data is static, fixed at render time, so an action can't carry a
;; value that only exists once the user types. glitter.gtk's
;; set-event-handler stuffs a value-bearing signal's current value onto the
;; constructed event object as :glitter/value (see glitter.widget's
;; signal-value table — "changed" resolves to gtk_editable_get_text); by the
;; time it reaches *dispatch*, glitter.core's build-event-map has wrapped
;; that object under :glitter/dom-event. So :action/set-draft reads the
;; typed text from `event`, not from its own action data. Verified live:
;; typing "hello" into the entry produces
;; (get-in event [:glitter/dom-event :glitter/value]) => "hello".
(defn execute-actions [event actions]
  (doseq [[kind idx] actions]
    (case kind
      :action/toggle    (swap! state update-in [:tasks idx :done] not)
      :action/set-draft (swap! state assoc :draft
                               (get-in event [:glitter/dom-event :glitter/value]))
      :action/add-task  (swap! state (fn [{:keys [draft] :as s}]
                                       (if (seq draft)
                                         (-> s
                                             (update :tasks conj {:text draft :done false})
                                             (assoc :draft ""))
                                         s)))
      nil)))

(core/set-dispatch! execute-actions)

(defn -main [& _]
  (app/run (fn [window] (gtk/mount! window view state))
           :title "glitter · tasks" :width 480 :height 420 :app-id "glitter.todo"))
