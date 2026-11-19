# glitter guide

## Why this exists

`glitter` is a Replicant-style GTK4 renderer for Jolt. Its sibling,
[glimmer](https://github.com/jolt-lang/glimmer), already applies Reagent's
model (ratoms, automatic dependency tracking, component-local state) to
GTK4; glitter deliberately applies a different one:
[Replicant](https://github.com/cjohansen/replicant)'s single application-
state atom, pure `state -> hiccup` view function, top-down re-render, and
data-driven action-dispatch handlers. This guide covers how that model
is adapted to a native, retained-mode, C-ABI toolkit with no DOM underneath.

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
- [`examples.md`](examples.md): the interactive demos, widget galleries and live-GTK smokes, and what each runs.
- [`architecture.md`](architecture.md): the reconcile to `IRender`/`IMemory` flow and `mount!`'s state-atom watcher.
- [`porting-and-attribution.md`](porting-and-attribution.md): what was ported from Replicant, forked from glimmer, ported from nexus or written new, and every deviation.

### Dispatch
- [`nexus.md`](nexus.md): `glitter.nexus`, the port of [nexus](https://github.com/cjohansen/nexus)'s action/effect/placeholder dispatch engine and how the demos wire it.

### GTK integration
- [`widgets.md`](widgets.md): the widget reference, with props, signals and `[:glitter/value]` for each of the 43 tags.
- [`gtk-widget-layer.md`](gtk-widget-layer.md): the tag-to-widget registry, the signal lifecycle and a per-widget record of what GTK forced.
- [`app-loop-and-threading.md`](app-loop-and-threading.md): the `GtkApplication` bootstrap and cross-thread marshalling.

### Verify
- [`testing-and-tasks.md`](testing-and-tasks.md): the unit suite, the headless test renderer, the live-GTK smokes and the tasks that run them.
- [`limitations.md`](limitations.md): known v1 gaps and why each is left unfixed.

## See also

- [glimmer](https://github.com/jolt-lang/glimmer): the Reagent-style
  sibling this project forked its GTK4 FFI/widget layer from.
- [Replicant](https://github.com/cjohansen/replicant): the source of
  `glitter.core`'s reconciler and most of the non-GTK-specific namespaces.
- `NOTICE` (repo root): the authoritative file-by-file attribution
  list; `porting-and-attribution.md` explains it, `NOTICE` is the
  source of truth for exact commit SHAs and per-file deviation notes.
