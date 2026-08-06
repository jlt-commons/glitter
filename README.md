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

Run `jolt counter` for the full interactive demo, `jolt smoke` for the
automated smoke test, `jolt keyed` for the live keyed-reconciliation smoke,
`jolt replace-child` for the live replace-in-place smoke, `jolt test` for the
unit suite.

**In CI, invoke the alias form, not the task form** — `jolt -M:test`,
`jolt -M:keyed`, `jolt -M:replace-child`. Verified against jolt v0.6.3: a
deps.edn `:tasks` entry does not propagate its child process's exit status, so
`jolt test` reports failures on stdout and still exits 0, while `jolt -M:test`
correctly exits 1. The task shorthand is fine interactively; it cannot gate a
build.

## Architecture

- `glitter.core`, `glitter.protocols`, `glitter.hiccup*`, `glitter.vdom`,
  `glitter.alias`, `glitter.errors`, `glitter.assert*`, `glitter.console-logger`
  — ported from [Replicant](https://github.com/cjohansen/replicant) (MIT,
  Christian Johansen). See `NOTICE.md`.
- `glitter.ffi`, `glitter.widget`, `glitter.genum` — forked from
  [glimmer](https://github.com/jolt-lang/glimmer).
- `glitter.app`, `glitter.gtk`, `glitter.test-renderer` — new code specific
  to glitter.

Full design rationale: see the design spec this project shipped from (not
included in this repo — routed to the centralized planning store per this
project's convention).

## Status

Early. Widget set matches whatever `glitter.widget` forked from glimmer at
the time (window/box/button/label/entry/checkbutton/separator/frame/scrolled).
No animated mount/unmount transitions, no GTK CSS class/style wiring yet —
see `NOTICE.md`'s file-by-file notes for exactly what's ported vs. new.
