(ns glitter.crud
  "The 7GUIs 'CRUD' task (https://eugenkiss.github.io/7guis/tasks/#crud)
  over glitter — a small business-app form over an in-memory name
  database: a live prefix-filter, a single-selection listbox, a pair of
  name/surname fields, and Create/Update/Delete buttons (Update/Delete
  disabled unless a row is selected). Per the spec: 'the primary
  challenge is the separation of domain and presentation logic... more
  or less forced on the implementer due to the ability to filter the
  view by a prefix' — `get-people` below is that separation: pure,
  reused by both `view` (to render) and the `:action/select-row`
  action-expansion below (to resolve a clicked row's INDEX back to a
  stable person id).

  Ports [cjohansen/replicant-7guis](https://github.com/cjohansen/replicant-7uis)'s
  `src/guis/crud.cljc` — but that file, verified by reading it and its
  own test suite, is an INITIAL TAKE, not a finished implementation:
  only the filter is wired (`get-people`, `render-ui`'s layout,
  `::set-family-name-filter`); the listbox has no `:on`, the two name
  fields aren't connected to any state, and Create/Update/Delete are
  static `[:button ...]` text with no click handlers at all. This port
  completes the full interaction the spec actually describes.

  Retrofitted onto `glitter.nexus` (see `src/glitter/nexus.clj`) in this
  commit — dispatch previously went through a hand-written
  `execute-actions` `case` form. `:action/set-filter`/
  `:action/set-given-name`/`:action/set-family-name` are pure
  passthroughs of the typed value, plain `:effect/assoc-in` + a
  `:glitter/value` placeholder, the same shape `examples/glitter/flights.clj`
  uses throughout. `:action/select-row`/`:action/create`/`:action/update`/
  `:action/delete` each need to READ current state to decide what should
  happen, so they use the ACTION-EXPANSION layer (`register-action!`) —
  the piece `flights.clj` never needs at all, since every one of its
  interactions dispatches at most two effects, never an action
  expansion, with no read-before-write. See `flights.clj`'s own
  docstring for that pure-effects-only contrast.

  DESIGN CHOICE beyond the strict spec text: selecting a row
  auto-populates the Name/Surname fields with that person's current
  values. The spec's own text doesn't mandate this (and its own
  screenshot shows a selected 'Tisch, Roman' row alongside UNRELATED
  'John'/'Romba' field values, proving the spec's minimal reading is
  'user types whatever they want, Update replaces the selection with
  it') — but auto-populate is the near-universal convention in
  real-world CRUD UIs and in most reference 7GUIs implementations, and
  it's what makes Update usable without already knowing the selected
  row's current values by heart.

  TWO REAL FINDINGS surfaced live while building this — the first
  time this project has driven `:scrolled`/`:list-box` with any
  meaningful interaction beyond construction:

  1. `GtkListBox` doesn't implement `GtkScrollable` (confirmed against
     `gtk/gtklistbox.c`'s `G_DEFINE_TYPE_WITH_CODE` — no
     `GTK_TYPE_SCROLLABLE` in its interface list), so
     `gtk_scrolled_window_set_child` (confirmed via its own doc
     comment and body) auto-wraps it in a hidden `GtkViewport` —
     `:scrolled`'s real first GTK child is the viewport, not the
     `:list-box` widget directly. Pure GTK behavior, not a glitter
     concern for hiccup authors (`glitter.gtk`'s own `:children`
     bookkeeping for the `:scrolled` element is unaffected — it never
     sees the viewport at all), but anyone reading the LIVE tree back
     via raw FFI walks (as every smoke in this project does) needs to
     know to descend one extra level.

  2. A real, previously-undiscovered bug in `list-box-reorder-child!`
     (and its `flow-box-reorder-child!` sibling), found by the exact
     scenario this demo enables: renaming a selected person's family
     name to something that sorts to a DIFFERENT position triggers a
     keyed reorder (glitter's reconciler recognizes the SAME
     `:glitter/key`, just needing repositioning). `gtk_list_box_remove`
     disposes the now-unreferenced `GtkListBoxRow` immediately, and
     `GtkListBoxRow`'s own dispose unparents (confirmed by reading
     `gtk_list_box_row_dispose` directly) its own child — with nothing
     else in glitter holding an independent reference, that FINALIZES
     the child too. `list-box-reorder-child!` was reusing that now-
     dangling pointer for the reinsert, surfacing as
     `gtk_list_box_insert: assertion 'GTK_IS_WIDGET (child)' failed`
     plus cascading assertion failures. `docs/guide/limitations.md`
     had already flagged this exact path — a same-key reposition, not
     a tag-swap or plain append/remove — as 'implemented but not
     previously live-verified'; it wasn't safe. Fixed via a
     `g-object-ref-sink`/`g-object-unref` bracket around the
     remove-then-reinsert in both functions — see their own docstrings
     in `glitter.widget` and `examples/glitter/list_box_reorder_smoke.clj`
     for the permanent regression coverage. `docs/guide/gtk-widget-layer.md`
     has the full write-up.

  Keying each row by the person's `:id` (`:glitter/key`, not a plain
  positional index) is why (2) is even fixable as a REORDER rather
  than an unnecessary remove+recreate: it's what tells the reconciler
  'this is the SAME logical row, just needs a new position' instead of
  tearing the row down and building a fresh one on every filter/sort
  change — and it's what keeps a still-selected row's identity intact
  across a sort-order change, not tied to a since-shifted index.

  Run: jolt -M:crud (the :crud task/alias) or bb crud. Needs a display;
  closes the window to exit."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [glitter.app :as app]
            [glitter.core :as core]
            [glitter.gtk :as gtk]
            [glitter.nexus.registry :as nxr]))

(defonce state
  (atom {:people [{:id 1 :given-name "Hans" :family-name "Emil"}
                  {:id 2 :given-name "Max" :family-name "Mustermann"}
                  {:id 3 :given-name "Roman" :family-name "Tisch"}]
         :next-id 4
         :filter ""
         :selected-id nil
         :given-name ""
         :family-name ""}))

;; Domain logic, kept pure and reused by BOTH the view (to render) and
;; the :action/select-row action-expansion below (to resolve a clicked
;; row's live GTK index back to a stable :id — :list-box's "row-selected"
;; delivers an INDEX into whatever's currently displayed, not an
;; identity, so both sides must derive the exact same ordering or the
;; two would silently disagree).
;; Prefix-matches the FAMILY name only, case-insensitive and trimmed;
;; sorted by (family-name, given-name) — mirrors both the reference
;; replicant-7guis port's own get-people and the spec screenshot's own
;; order (Emil, Hans / Mustermann, Max / Tisch, Roman).
(defn get-people [{:keys [people] prefix :filter}]
  (let [f (some-> prefix str/trim not-empty str/lower-case)]
    (cond->> people
      f (filter #(str/starts-with? (str/lower-case (:family-name %)) f))
      :then (sort-by (juxt :family-name :given-name)))))

;; A labeled entry row — the filter field and the two name fields all
;; share this shape. Plain function call, not [field-row ...]: glitter's
;; hiccup (ported from Replicant) requires a literal KEYWORD in tag
;; position, so a function-valued tag would silently render as opaque
;; stringified text instead of expanding — see AGENTS.md convention #10
;; and examples/glitter/todo.clj's stat-card for the same pattern.
(defn- field-row [label value path]
  [:hbox {:spacing 8}
   ;; :xalign MUST be a float literal (0.0, not 0) — gtk_label_set_xalign
   ;; is declared :float in glitter.ffi, and Jolt's FFI does not coerce
   ;; an int argument to a float one; a bare 0 here throws "invalid
   ;; foreign-procedure argument" at set-attribute time.
   [:label {:label label :width-chars 8 :xalign 0.0}]
   [:entry {:text value :hexpand true :on {:change [[:effect/assoc-in [path] [:glitter/value]]]}}]])

(defn view [{:keys [filter given-name family-name selected-id] :as state}]
  (let [people (get-people state)
        ;; Sensitivity is derived from whether the selected id is still
        ;; VISIBLE in the current filtered view, not from :selected-id's
        ;; raw presence — filtering the selected person out of view
        ;; doesn't clear :selected-id in state (removing their row is
        ;; suppressed at the GTK level, the same suppressing guard every
        ;; :list-box removal uses), so this is what keeps Update/Delete
        ;; correctly disabled when the selection scrolls out of the
        ;; filtered results. `boolean`, not bare `some` — `some` returns
        ;; nil (not false) on no match, and glitter's apply-props! only
        ;; re-applies props present via `some?` (AGENTS.md convention #4:
        ;; :sensitive false must reach the widget, not be treated as
        ;; absent) — a bare `nil` here would be silently DROPPED instead
        ;; of applied, leaving :sensitive stuck at its last true value.
        ;; Found live: the button never went insensitive again once a
        ;; filter change hid the selected row.
        selected? (boolean (some #(= (:id %) selected-id) people))]
    [:vbox {:spacing 12 :margin 16}
     [:label {:markup "<span size='xx-large' weight='bold'>CRUD</span>" :halign :start}]
     (field-row "Filter:" filter :filter)
     [:hbox {:spacing 12 :vexpand true}
      [:scrolled {:hexpand true :vexpand true}
       (into [:list-box {:on {:row-selected [[:action/select-row [:glitter/value]]]}}]
             (for [p people]
               [:label {:glitter/key (:id p)
                        :label (str (:family-name p) ", " (:given-name p))
                        :halign :start
                        :margin-start 6 :margin-end 6 :margin-top 4 :margin-bottom 4}]))]
      [:vbox {:spacing 8 :valign :start}
       (field-row "Name:" given-name :given-name)
       (field-row "Surname:" family-name :family-name)]]
     [:hbox {:spacing 8}
      [:button {:label "Create" :on {:click [[:action/create]]}}]
      [:button {:label "Update" :sensitive selected? :on {:click [[:action/update]]}}]
      [:button {:label "Delete" :sensitive selected? :on {:click [[:action/delete]]}}]]]))

;; :action/set-filter/:action/set-given-name/:action/set-family-name are
;; pure passthroughs of the typed value — plain :effect/assoc-in, same
;; shape as flights.clj's date fields. :action/select-row/:action/create/
;; :action/update/:action/delete each need to READ current state to
;; decide what should happen — these are the glitter.nexus ACTION-
;; EXPANSION layer flights.clj never needed at all (see that file's own
;; docstring for the contrast): pure functions of (state & args)
;; returning the effects to run, never a swap! themselves.
(nxr/register-effect! :effect/assoc-in
                      (fn [_ system path v] (swap! system assoc-in path v)))

(nxr/register-placeholder! :glitter/value
                           (fn [event] (get-in event [:glitter/dom-event :glitter/value])))

;; :list-box's "row-selected" value-fn delivers the selected row's INDEX
;; (gtk_list_box_get_selected_row -> row_get_index), so this resolves it
;; against the SAME get-people ordering the view just rendered, then
;; auto-populates the two fields from that person.
(nxr/register-action! :action/select-row
                      (fn [state idx]
                        (if-let [person (nth (vec (get-people state)) idx nil)]
                          [[:effect/assoc-in [:selected-id] (:id person)]
                           [:effect/assoc-in [:given-name] (:given-name person)]
                           [:effect/assoc-in [:family-name] (:family-name person)]]
                          [])))

(nxr/register-action! :action/create
                      (fn [{:keys [given-name family-name next-id people]}]
                        (if (or (seq given-name) (seq family-name))
                          [[:effect/assoc-in [:people] (conj people {:id next-id :given-name given-name :family-name family-name})]
                           [:effect/assoc-in [:next-id] (inc next-id)]]
                          [])))

(nxr/register-action! :action/update
                      (fn [{:keys [selected-id given-name family-name people]}]
                        (if selected-id
                          [[:effect/assoc-in [:people]
                            (mapv #(if (= (:id %) selected-id)
                                     (assoc % :given-name given-name :family-name family-name)
                                     %)
                                  people)]]
                          [])))

(nxr/register-action! :action/delete
                      (fn [{:keys [selected-id people]}]
                        (if selected-id
                          [[:effect/assoc-in [:people] (vec (remove #(= (:id %) selected-id) people))]
                           [:effect/assoc-in [:selected-id] nil]
                           [:effect/assoc-in [:given-name] ""]
                           [:effect/assoc-in [:family-name] ""]]
                          [])))

(nxr/register-system->state! deref)
(nxr/on-error (fn [_ctx {:keys [err] :as error}]
                (log/error err "glitter.nexus dispatch error" (dissoc error :err))))

(core/set-dispatch!
 (fn [event actions] (nxr/dispatch state event actions)))

(defn -main [& _]
  (app/run (fn [window] (gtk/mount! window view state))
           :title "glitter · CRUD" :width 520 :height 340 :app-id "glitter.crud"))
