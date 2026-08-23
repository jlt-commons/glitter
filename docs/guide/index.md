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
- [`examples.md`](examples.md) — the six interactive demos (four of them
  [7GUIs](https://eugenkiss.github.io/7guis/) tasks) and the twenty-six
  live-GTK smokes: what each one runs, and what it pins. `counter.clj` is
  the 20-line version of the whole model and the fastest way in.
- [`architecture.md`](architecture.md) — the reconcile → `IRender`/
  `IMemory` flow, `mount!`'s state-atom watcher, why elements are tracking
  atoms rather than raw GTK pointers.
- [`porting-and-attribution.md`](porting-and-attribution.md) — the four
  sourcing buckets (ported from Replicant / forked from glimmer / new to
  glitter / ported from nexus — see [`nexus.md`](nexus.md)), and every
  documented deviation from the pure Replicant port.

### Dispatch
- [`nexus.md`](nexus.md) — `glitter.nexus`, a port of
  [nexus](https://github.com/cjohansen/nexus)'s data-driven
  action/effect/placeholder dispatch engine. It covers:

  - **The four concepts.** Effects (the only place a `swap!` is
    allowed), placeholders (resolving event data into action data),
    actions/expansions (pure functions of state that decide what should
    happen), and interceptors (the `before-*`/`after-*` mechanism the
    engine itself runs on).
  - **The two glitter-specific wiring pieces** every consumer registers
    for itself — `:glitter/value` and `:nexus/on-error` →
    `clojure.tools.logging` — and why they live in each demo rather than
    in the ported files.
  - **The two consumer shapes this project ships.** `flights.clj`'s
    pure-effects-only Flight Booker, versus `crud.clj`'s and
    `todo.clj`'s action-expansion retrofits.
  - **The action log** and its `:entries`/`:chronology` accumulation
    tree (`(pr-str @log)` — no viewer yet), plus the `t/parse-date`
    leniency finding that makes Flight Booker's date validation
    actually work.

### GTK integration
- [`gtk-widget-layer.md`](gtk-widget-layer.md) — the hiccup-tag → widget
  registry and the signal connect/disconnect lifecycle. The long one: a
  per-widget record of how each of the 43 supported tags was added and
  what it taught. Grouped by what it covers:

  - **Reconciler traps GTK forced.** `insert-before`'s
    reorder-vs-insert branch and `replace-child!`'s prev-sibling
    capture; `:scrolled`'s hidden `GtkViewport` auto-wrap around a
    non-`GtkScrollable` child; and a use-after-dispose bug in
    `list-box-reorder-child!`/`flow-box-reorder-child!`, both of which
    reused a pointer GTK had already disposed while removing the old
    wrapping row.
  - **Signal shapes that forced `set-event-handler` to generalize.**
    Most widgets reuse the uniform 2-arg-void shape for free. Four did
    not: `:switch` (3-arg, non-void return), `:list-box` (a distinct
    3-arg-void shape, later reused for free by `:expander`/`:paned`'s
    `notify::*`), `:notebook` (4-arg `"switch-page"` — the first signal
    that must read its own raw argument, because the getter is still
    stale when it fires), and `:scale-button` (the first case where the
    signal *name* alone can't determine the shape, making dispatch
    tag-aware).
  - **Container strategies beyond `:box`.** `:center-box`'s three named
    slots, `:paned`'s two, `:overlay`'s single queryable main slot plus
    an unenumerable overlay set, `:header-bar`/`:action-bar`'s hybrid
    (one named slot plus an ordered pack-start list), and `:grid`, whose
    child placement is driven entirely by the child's own props through
    the `:glitter/structural-props` mechanism.
  - **Findings that changed the design.** Namespaced keyword props never
    reach `IRender/set-attribute` at all; `signal-value` had to be keyed
    by `[tag signal]` rather than signal name; a ctor/apply audit found
    four previously-shipped bugs; and — not glitter-specific — bulk
    `GtkEditable` text replacement fires `"changed"` once or twice
    depending on the buffer's starting state.
  - **Free wins.** The display-only widgets (`:spinner`,
    `:progress-bar`, `:image`, `:level-bar`, `:revealer`, `:picture`,
    `:inscription`, `:search-bar`), the `GtkEditable`-delegate reuses
    (`:password-entry`, `:search-entry`, `:editable-label`), and
    `:menu-button`/`:popover` — the first popup surface here, and the
    first hiccup relationship that isn't an ordinary
    `append-child!`-managed tree child.

- [`app-loop-and-threading.md`](app-loop-and-threading.md) — the
  `GtkApplication` bootstrap and cross-thread marshalling that lets a
  `swap!` from any thread safely reach the GTK main loop.

### Verify
- [`testing-and-tasks.md`](testing-and-tasks.md) — the unit suite, the
  headless fake-`IRender` test renderer, the twenty-six automated live-GTK
  smokes, and the `jolt`/`bb` task surfaces that run them.
- [`limitations.md`](limitations.md) — every known v1 gap, and the
  reasoning behind leaving each one unfixed for now.

## See also

- [glimmer](https://github.com/jolt-lang/glimmer) — the Reagent-style
  sibling this project forked its GTK4 FFI/widget layer from.
- [Replicant](https://github.com/cjohansen/replicant) — the source of
  `glitter.core`'s reconciler and most of the non-GTK-specific namespaces.
- `NOTICE` (repo root) — the authoritative file-by-file attribution
  ledger; `porting-and-attribution.md` explains it, `NOTICE` is the
  source of truth for exact commit SHAs and per-file deviation notes.
