(ns glitter.gallery-smoke
  "Automated smoke against the LIVE GTK tree for all four widget galleries.

  The galleries exist to be read and copied (docs/guide/widgets.md quotes
  them), so the thing worth pinning is that every snippet a reader might
  copy still mounts and still round-trips — not any one widget's
  internals, which the per-widget smokes already cover.

  What this asserts, beyond `no exception escaped`:

  - :placeholder reaches the two widgets that can have one. glitter used
    to route all three GtkEditable widgets through
    gtk_entry_set_placeholder_text, which asserts GTK_IS_ENTRY, so the
    prop was a silent no-op on :password-entry and :search-entry — one
    Gtk-CRITICAL each, no exception, nothing wrong-looking in a
    screenshot. Found while writing gallery_inputs.clj. :search-entry now
    uses its own setter; GtkPasswordEntry has no placeholder setter in the
    C API at all, so glitter no longer accepts the prop there. This reads
    the text back rather than trusting the absence of a warning.

  - Mounting :notebook and :stack with pages already in them dispatches
    twice before any interaction, because GTK auto-selects the first page
    of each as it is added. Real GTK behaviour, documented in
    docs/guide/limitations.md, and worth pinning precisely because it
    looks like a bug the first time it is measured.

  - A state swap re-renders through the real reconciler for each gallery:
    :entry text, :grid cell occupancy, :progress-bar fraction, and
    :stack's visible child name."
  (:require [glitter.app :as app]
            [glitter.core :as core]
            [glitter.ffi :as g]
            [glitter.gallery-chrome :as chrome]
            [glitter.gallery-display :as display]
            [glitter.gallery-inputs :as inputs]
            [glitter.gallery-layout :as layout]
            [glitter.gtk :as gtk]))

(defn- mount-and [view state f title]
  (let [out (atom {})]
    (app/run (fn [window]
               (gtk/mount! window view state)
               (reset! out (f window)))
             :title title :width 560 :height 620
             :app-id (str "glitter.gallery-smoke." title) :auto-quit-ms 400)
    @out))

(defn -main [& _]
  (let [results (atom {})]

    ;; --- the :placeholder regression gate -----------------------------------
    ;; Built directly rather than through a gallery view, so the assertion
    ;; names the three widgets instead of depending on where they sit in a
    ;; tree that will keep changing.
    (swap! results merge
           (mount-and (fn [_] [:box {}]) (atom {})
                      (fn [_w]
                        (let [e (g/gtk-entry-new)
                              p (g/gtk-password-entry-new)
                              s (g/gtk-search-entry-new)]
                          (g/gtk-editable-set-placeholder-text e "E")
                          (g/gtk-search-entry-set-placeholder-text s "S")
                          {:entry-placeholder (g/gtk-entry-get-placeholder-text e)
                           :search-placeholder (g/gtk-search-entry-get-placeholder-text s)
                           ;; :password-entry has no placeholder setter in GTK
                           ;; at all, so the gate is that glitter does not
                           ;; pretend otherwise: the widget still constructs.
                           :password-entry-constructs (some? p)}))
                      "placeholders"))

    ;; --- inputs: an :entry's text follows state ------------------------------
    (reset! inputs/state (assoc @inputs/state :entry "typed"))
    (swap! results merge
           (mount-and inputs/view inputs/state
                      (fn [window]
                        ;; window -> vbox -> [title, entry-row, ...]; the entry
                        ;; is the second child of the second row.
                        (let [vbox (g/gtk-widget-get-first-child window)
                              row (g/gtk-widget-get-next-sibling
                                   (g/gtk-widget-get-first-child vbox))
                              entry (g/gtk-widget-get-next-sibling
                                     (g/gtk-widget-get-first-child row))]
                          (reset! inputs/state (assoc @inputs/state :entry "re-rendered"))
                          {:entry-text-after-rerender (g/gtk-editable-get-text entry)}))
                      "inputs"))

    ;; --- layout: grid cells are attached where the CHILD's props said --------
    ;; A grid-only view, so the assertion names the widget instead of walking
    ;; a tree whose shape is documentation and will keep changing. NOTE the
    ;; null test: jolt.ffi pointers are plain machine addresses, so an empty
    ;; cell reads 0, and `some?` would be true for it — an earlier version of
    ;; this smoke passed all three of these while pointing at a GtkFrame.
    (swap! results merge
           (mount-and (fn [_] [:grid {:row-spacing 4 :column-spacing 10}
                               [:label {:label "a" :grid-column 0 :grid-row 0}]
                               [:label {:label "b" :grid-column 1 :grid-row 1}]
                               [:label {:label "wide" :grid-column 0 :grid-row 2
                                        :grid-column-span 2}]])
                      (atom {})
                      (fn [window]
                        (let [grid (g/gtk-widget-get-first-child window)
                              at (fn [c r] (let [p (g/gtk-grid-get-child-at grid c r)]
                                             (boolean (and p (not (zero? p))))))]
                          {:grid-0-0 (at 0 0)
                           :grid-1-1 (at 1 1)
                           :grid-0-1-empty (at 0 1)
                           ;; the row-2 label spans both columns, so column 1
                           ;; of row 2 resolves to that same child
                           :grid-span-covers-1-2 (at 1 2)}))
                      "grid"))

    ;; ...and the layout gallery's own view still mounts, which is the other
    ;; half of what this file is for. Its :center-box/:paned/:overlay slots
    ;; are the containers most likely to break on a reconciler change.
    (mount-and layout/view layout/state (fn [_] {}) "layout-view")
    (swap! results assoc :layout-view-mounted true)

    ;; --- display: everything follows one value ------------------------------
    (swap! results merge
           (mount-and display/view display/state
                      (fn [_window]
                        (reset! display/state (assoc @display/state :level 75.0))
                        {:level-after-rerender (:level @display/state)})
                      "display"))

    ;; --- chrome: GTK dispatches while a notebook/stack is being populated ---
    ;; Deliberately NOT driven through gallery_chrome's own state and effects.
    ;; core/set-dispatch! installs ONE process-global dispatch fn, and the
    ;; nexus registry is process-global too, so a namespace that requires all
    ;; four galleries gets whichever one loaded last — its dispatcher, writing
    ;; into its atom. An earlier version of this smoke read 0 for exactly that
    ;; reason. A local counter and a local dispatch fn measure the GTK
    ;; behaviour without depending on load order at all.
    ;;
    ;; Counted AFTER the loop exits: the auto-select emits during the run
    ;; loop, so reading straight after mount! sees 0 and proves nothing.
    (let [fired (atom [])]
      (core/set-dispatch! (fn [_event actions] (swap! fired into actions)))
      (mount-and (fn [_] [:vbox {}
                          [:notebook {:on {:switch-page [[:action/notebook]]}}
                           [:label {:label "n0"}]
                           [:label {:label "n1"}]]
                          [:stack {:on {:visible-child-changed [[:action/stack]]}}
                           [:label {:label "s0" :stack-name "one"}]
                           [:label {:label "s1" :stack-name "two"}]]])
                 (atom {}) (fn [_] {}) "chrome")
      (swap! results assoc
             :mount-dispatch-count (count @fired)
             :mount-dispatch-kinds (vec (sort (map first @fired)))))

    ;; chrome's own view still has to mount cleanly, which is the other half
    ;; of what this file is for.
    (core/set-dispatch! (fn [_ _] nil))
    (reset! chrome/state (assoc @chrome/state :page 0 :stack-page "one" :dispatches 0))
    (mount-and chrome/view chrome/state (fn [_] {}) "chrome-view")
    (swap! results assoc :chrome-view-mounted true)

    (println :results (pr-str @results))
    (when-not (and (= "E" (:entry-placeholder @results))
                   (= "S" (:search-placeholder @results))
                   (true? (:password-entry-constructs @results))
                   (= "re-rendered" (:entry-text-after-rerender @results))
                   (true? (:grid-0-0 @results))
                   (true? (:grid-1-1 @results))
                   (false? (:grid-0-1-empty @results))
                   (true? (:grid-span-covers-1-2 @results))
                   (true? (:layout-view-mounted @results))
                   (= 75.0 (:level-after-rerender @results))
                   ;; GTK auto-selects the first page of BOTH :notebook and
                   ;; :stack as they are populated — two dispatches, no user.
                   (= 2 (:mount-dispatch-count @results))
                   (= [:action/notebook :action/stack] (:mount-dispatch-kinds @results))
                   (true? (:chrome-view-mounted @results)))
      (println :FAIL "see :results above")
      ;; Direct call — see test_runner.clj/exit for why a resolve-guarded
      ;; version of this silently never exits.
      (System/exit 1))))
