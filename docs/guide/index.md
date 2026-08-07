# glitter — Guide

## Why this exists

`glitter` is a Replicant-style GTK4 renderer for Jolt. Its sibling,
[glimmer](https://github.com/jolt-lang/glimmer), already applies Reagent's
model (ratoms, automatic dependency tracking, component-local state) to
GTK4; glitter deliberately applies a different model —
[Replicant](https://github.com/cjohansen/replicant)'s single application-
state atom, pure `state -> hiccup` view function, top-down re-render, and
data-driven action-dispatch handlers. This guide covers how that model was
adapted to a native, retained-mode, C-ABI toolkit that has no DOM
underneath it.

## What glitter is

A `.clj` (Jolt/Chez Scheme host, not JVM) library:

```clojure
(require '[glitter.app :as app]
         '[glitter.core :as core]
         '[glitter.gtk :as gtk])

(defonce state (atom {:count 0}))

(defn view [{:keys [count]}]
  [:box {:spacing 12}
   [:label {:label (str "Count: " count)}]
   [:button {:label "+ 1" :on {:click [[:action/inc]]}}]])

(core/set-dispatch!
 (fn [_event actions]
   (doseq [[kind] actions]
     (case kind :action/inc (swap! state update :count inc) nil))))

(app/run (fn [window] (gtk/mount! window view state)))
```

Every subsequent `swap!` on `state` triggers a full re-render of `view`;
`glitter.core`'s reconciler diffs the new hiccup against the previous vdom
and issues the minimal set of `IRender`/`IMemory` calls needed to bring the
live GTK widget tree in sync.

## Pages

### Orientation
- [`architecture.md`](architecture.md) — the reconcile → `IRender`/
  `IMemory` flow, `mount!`'s state-atom watcher, why elements are tracking
  atoms rather than raw GTK pointers.
- [`porting-and-attribution.md`](porting-and-attribution.md) — the three
  sourcing buckets (ported from Replicant / forked from glimmer / new to
  glitter), and every documented deviation from the pure Replicant port.

### GTK integration
- [`gtk-widget-layer.md`](gtk-widget-layer.md) — the hiccup-tag → widget
  registry, the signal connect/disconnect lifecycle, the specific GTK4 API
  traps this project hit and fixed (`insert-before`'s reorder-vs-insert
  branch, `replace-child!`'s prev-sibling capture), `:scale`'s
  value-bearing custom signal, `:class`'s real GTK CSS-class wiring, the
  display-only `:spinner`/`:progress-bar`/`:image`/`:level-bar`/
  `:revealer` widgets, `:toggle-button`'s and `:link-button`'s signal
  reuse (including a real GTK4 button-activation timing gotcha), `:switch`
  and `:list-box` — the two widgets that needed
  `glitter.gtk/set-event-handler` itself generalized beyond the uniform
  2-arg-void signal shape (to a third, distinct shape for `:list-box`),
  `:spin-button` — the widget that needed `glitter.widget/signal-value`
  re-keyed by `[tag signal]` after sharing `:scale`'s exact GTK signal
  name, `:center-box` — a genuinely new 3-named-slot container
  strategy that surfaced a real v1 gap (and, along the way, a real fix to
  `insert-child-after!`/`reorder-child!` for every non-`:box` container),
  `:password-entry`/`:search-entry` — free `GtkEditable`-delegate signal
  reuse plus a `signal-value` miss caught live before it shipped, and
  `:expander`/`:paned` — free reuse of `:list-box`'s generalized
  3-arg-void shape for their `notify::*` signals, and `:paned`'s own
  2-named-slot container with a structural gap verified to fail
  differently from `:center-box`'s, `:aspect-frame`/`:calendar` — a
  quick single-child-container win alongside this project's first
  refcounted `GDateTime` marshalling, `:overlay`/`:flow-box` — a
  THIRD, genuinely different container shape (one queryable main slot
  plus an unenumerable overlay set) and a `:list-box` sibling verified
  to NOT share its `gtk_list_box_remove` gotcha, not assumed to,
  `:picture`/`:editable-label` — a quick display-only win, a THIRD
  `GtkEditable`-delegate reuse, and a general (not glitter-specific)
  `GtkEditable` finding that bulk text replacement fires `"changed"`
  once or twice depending on the buffer's starting state, and
  `:notebook`/`:scale-button` — a SIXTH callable shape
  (`"switch-page"`, the first signal here that has to read its own raw
  argument instead of re-reading a getter, since the getter would be
  stale), a verified real mount-time auto-dispatch when a notebook's
  first page is added, and a THIRD widget sharing `"value-changed"`'s
  signal name that needed the first tag-aware (not just signal-name-
  keyed) `set-event-handler` dispatch, `:inscription`/`:search-bar` —
  two more quick, entirely no-signal wins (the first round where zero
  widgets need any `glitter.gtk` changes at all), `:header-bar`/
  `:action-bar` — a genuinely new hybrid container shape (one named
  title/center-widget slot plus an ORDERED pack-start list) that
  surfaced two real findings: `pack_end` silently reverses hiccup
  order on both widgets (confirmed independently for each, not
  assumed to carry over — v1 only wires `pack_start`), and toggling
  `:show-title-buttons` prepends GTK's own native window-controls
  widget into the SAME pack-start list glitter's children live in, and
  `:menu-button`/`:popover` — the FIRST popup surface in this project
  and the first hiccup relationship that isn't a normal
  append-child!-managed tree child, with both signals turning out to
  be free reuses of the default 2-arg-void shape, a ctor/apply prop-flow
  audit that found and fixed four real, previously-shipped bugs
  (`:checkbutton`'s never-applied `:label`, and three widgets'
  `:min`/`:max`/`:step` silently clobbering each other across
  single-key re-renders), a namespaced-keyword-props-are-silently-
  dropped finding that shaped the whole design that followed, the new
  `:glitter/structural-props` mechanism (a CHILD's props read by its
  PARENT at attach time) that `:grid` and `:stack` both need,
  `:window-handle` — a quick single-child win that also caught a bug
  in this round's OWN new code, `:stack` — a THIRD mount-time-auto-
  dispatch instance plus a real `:apply`-timing gap, `:drop-down` —
  the first "choose from options" widget, built on an incrementally-
  constructed `GtkStringList`, and `:grid` — the first container whose
  child placement is driven entirely by the child's own hiccup props.
- [`app-loop-and-threading.md`](app-loop-and-threading.md) — the
  `GtkApplication` bootstrap and cross-thread marshalling that lets a
  `swap!` from any thread safely reach the GTK main loop.

### Verify
- [`testing-and-tasks.md`](testing-and-tasks.md) — the unit suite, the
  headless fake-`IRender` test renderer, the twenty-five automated live-GTK
  smokes, and the `jolt`/`bb` task surfaces that run them.
- [`limitations.md`](limitations.md) — every known v1 gap, and the
  reasoning behind leaving each one unfixed for now.

## See also

- [glimmer](https://github.com/jolt-lang/glimmer) — the Reagent-style
  sibling this project forked its GTK4 FFI/widget layer from.
- [Replicant](https://github.com/cjohansen/replicant) — the source of
  `glitter.core`'s reconciler and most of the non-GTK-specific namespaces.
- `NOTICE.md` (repo root) — the authoritative file-by-file attribution
  ledger; `porting-and-attribution.md` explains it, `NOTICE.md` is the
  source of truth for exact commit SHAs and per-file deviation notes.
