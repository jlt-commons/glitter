# glitter

A Replicant-style GTK4 renderer for [Jolt](https://github.com/jolt-lang/jolt).

Where [glimmer](https://github.com/jolt-lang/glimmer) applies
[Reagent](https://reagent-project.github.io/)'s model to GTK4 (ratoms,
automatic dependency tracking, component-local state), glitter applies
[Replicant](https://github.com/cjohansen/replicant)'s model: a single
application-state atom, a pure `state -> hiccup` view function, top-down
re-render on every state change, and data-driven action-dispatch event
handlers instead of closures. No component-local state anywhere.

## Quick start

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

`jolt counter` is the full interactive demo. `jolt test` runs the unit suite.
The rest are automated live-GTK smokes, each of which exits non-zero on
failure:

| task | pins |
|---|---|
| `jolt smoke` | mount a tree and run the loop without an exception escaping |
| `jolt keyed` | keyed reorder lands in the right GTK order |
| `jolt replace-child` | a replaced child stays at its position, not the end |
| `jolt aliased` | aliases expand through the real renderer, on mount and update |
| `jolt main-thread-smoke` | an off-main-thread `swap!` renders ON the GTK main thread |

**In CI, invoke the alias form, not the task form** — `jolt -M:test`,
`jolt -M:keyed`, and so on. Verified against jolt v0.6.3: a
deps.edn `:tasks` entry does not propagate its child process's exit status, so
`jolt test` reports failures on stdout and still exits 0, while `jolt -M:test`
correctly exits 1. The task shorthand is fine interactively; it cannot gate a
build.

### Or via `bb`

If you have [Babashka](https://babashka.org/) installed, `bb.edn` wraps the
same tasks with a grouped cheat-sheet:

```
bb info      # start here — grouped task list
bb test      # jolt -M:test
bb counter   # interactive demo
bb smokes    # every live-GTK smoke in sequence, CI-safe (stops at first failure)
```

`bb.edn`'s tasks all shell out to the `-M:<alias>` form directly (never the
deps.edn `jolt <task>` shorthand), so `bb test` and `bb smokes` exit non-zero
on failure and are safe to use as a CI gate.

## Architecture

- `glitter.core`, `glitter.protocols`, `glitter.hiccup*`, `glitter.vdom`,
  `glitter.alias`, `glitter.errors`, `glitter.assert*`, `glitter.console-logger`
  — ported from [Replicant](https://github.com/cjohansen/replicant) (MIT,
  Christian Johansen). See `NOTICE.md`.
- `glitter.ffi`, `glitter.widget`, `glitter.genum` — forked from
  [glimmer](https://github.com/jolt-lang/glimmer). `glitter.app` is adapted
  from the non-reactive app-loop slice of `glimmer.core`.
- `glitter.gtk`, `glitter.test-renderer`, `glitter.env` — new code specific
  to glitter.

Full design rationale: see the design spec this project shipped from (not
included in this repo — routed to the centralized planning store per this
project's convention).

## Status

Early. Widget set matches whatever `glitter.widget` forked from glimmer at
the time (window/box/button/label/entry/checkbutton/separator/frame/scrolled).
No animated mount/unmount transitions, no GTK CSS class/style wiring yet —
see `NOTICE.md`'s file-by-file notes for exactly what's ported vs. new.

Known v1 limitations:

- **Removing an attribute entirely is a no-op.** Setting one to a new value
  always works, including an explicit `false`, but GTK has no generic "unset
  this property" the way `removeAttribute` does in the DOM, so dropping a key
  from your hiccup leaves the widget's last value in place. A real fix needs
  per-widget-type defaults in `glitter.widget`'s `:apply` closures.
- **`mount!` is one-way.** It registers its watcher under a fixed key and
  returns `nil`, so there is no unmount, and mounting twice against the same
  state atom silently replaces the first watcher rather than running both.
- **`IMemory` never releases.** `:glitter/remember` data is held in a
  process-global map keyed by element, with no eviction on unmount. Fine for
  the intended use (a value stashed on mount, read on update); don't lean on
  it for anything long-lived or high-cardinality. Replicant's DOM backend
  uses a `WeakMap` here; there is no equivalent yet.
