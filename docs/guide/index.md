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
  registry, the signal connect/disconnect lifecycle, and the specific GTK4
  API traps this project hit and fixed (`insert-before`'s reorder-vs-insert
  branch, `replace-child!`'s prev-sibling capture).
- [`app-loop-and-threading.md`](app-loop-and-threading.md) — the
  `GtkApplication` bootstrap and cross-thread marshalling that lets a
  `swap!` from any thread safely reach the GTK main loop.

### Verify
- [`testing-and-tasks.md`](testing-and-tasks.md) — the unit suite, the
  headless fake-`IRender` test renderer, the five automated live-GTK
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
