# Architecture

## The shape of a render

```mermaid
flowchart TD
  state["state atom"] -->|swap!| watch["add-watch fires"]
  watch --> ongui["glitter.app/on-gui<br/>(marshal to GTK main thread if needed)"]
  ongui --> view["(view @state)"]
  view -->|new hiccup| reconcile["glitter.core/reconcile(renderer, root-el, new-hiccup, prev-vdom)"]
  reconcile -->|"diffs new hiccup against prev-vdom, issues the<br/>minimal set of protocol calls to bring the<br/>live tree in sync"| protocols["glitter.protocols/IRender + IMemory<br/>(glitter.gtk implements this for real GTK4;<br/>glitter.test-renderer implements it for<br/>headless tests)"]
```

`glitter.core` (ported from `replicant.core`, see
[`porting-and-attribution.md`](porting-and-attribution.md)) is the entire
diff algorithm. It knows nothing about GTK: every effect it wants to have
on the live tree goes through the `IRender`/`IMemory` protocols in
`glitter.protocols`. That boundary is what lets two different backends
(`glitter.gtk` for real widgets, `glitter.test-renderer` for headless unit
tests) share the same reconciler.

## Composing views: plain function calls, not hiccup tags

glimmer/Reagent hiccup calls a function value in tag position
(`[my-component args...]`). glitter's hiccup, ported from Replicant, does
not: `glitter.hiccup/hiccup?` requires a literal keyword in position 0. A
vector whose first element is a function is treated as an opaque child
value, and `glitter.gtk`'s `create-text-node` stringifies it. The failure is
silent: no exception, just a child that renders as literal text such as
`[#object[my_ns$my_component 0x1234 "..."] arg1 arg2]`.

Call the helper as an ordinary function and splice its return value into the
parent vector: `(my-component args...)`, not `[my-component args...]`.

For a fragment referenced by name across files, use
[`glitter.alias`](porting-and-attribution.md). `defalias` registers a
function under a qualified keyword, and `[my-ns/my-component args...]` is
expanded through that registry (`glitter.alias/alias-hiccup?` checks
`qualified-keyword?`). `examples/glitter/aliased.clj` exercises the alias
path and `examples/glitter/todo.clj`'s `stat-card`/`task-row` are the
plain-function case (see CONTRIBUTING.md's invariants list).

## `mount!`: the state-atom watcher

`glitter.gtk/mount!` is Replicant's `state-atom.md` pattern, adapted from
`document.body` to a GTK root window:

```clojure
(defn mount!
  [window view state-atom]
  (let [r (renderer)
        root-el (atom {:tag :window :widget window :children [] :handlers {}})
        vdom (atom nil)
        render! (fn [state]
                  (reset! vdom (:vdom (core/reconcile r root-el (view state) @vdom
                                                      {:aliases (alias/get-registered-aliases)}))))]
    (render! @state-atom)
    (add-watch state-atom ::render (fn [_ _ _ state] (app/on-gui (fn [] (render! state)))))
    nil))
```

Notes:

1. **The root element is `window` itself**, tagged `:window`: GTK windows
   are single-child containers (`gtk_window_set_child`), so the mounted
   `[:box ...]` (or whatever the view returns) becomes window's *one*
   child, not window's replacement. `glitter.widget/append-child!`/
   `remove-child!`/`replace-child!` all branch on `container-kind`, and
   `:window` routes to `gtk_window_set_child`.
2. **Every re-render goes through `app/on-gui`**, not called directly. A
   `swap!` on `state-atom` can originate from any thread (an nREPL eval's
   worker thread, a `future`): `on-gui` is what makes that safe. See
   [`app-loop-and-threading.md`](app-loop-and-threading.md).
3. **Registered aliases are merged into every reconcile call
   automatically** via `{:aliases (alias/get-registered-aliases)}`: an
   application never has to pass its alias registry through by hand.

## Why elements are atoms, not raw GTK pointers

GTK4 has no O(1) indexed-child-lookup API:
`gtk_widget_get_first_child`/`gtk_widget_get_next_sibling` is an O(n) walk
from the start of a container's child list. `glitter.core`'s reconciler,
however, expects to be able to hold an opaque "el" value per node and use
it as a stable identity across renders (for `IMemory`'s `remember`/`recall`,
for computing keyed-list diffs, and so on).

So `glitter.gtk`'s `IRender/create-element` and `create-text-node` don't
return the widget pointer directly; they return a Clojure atom:

```clojure
{:tag <hiccup tag keyword>
 :widget <GTK widget pointer>
 :children [<child atom> ...]
 :handlers {<event keyword> {:id <signal connection id> :cb <retained foreign-callable>}}}
```

- `:widget` is the actual GTK pointer, retrieved via the private `ptr` helper
  whenever a real FFI call needs it.
- `:children` is glitter's own ordered bookkeeping of this element's
  children (as `el` atoms), maintained by every `IRender` method that
  mutates the tree (`append-child`, `remove-child`, `insert-before`,
  `replace-child`, `remove-all-children`). This is what
  `insert-before` consults to tell a fresh insertion apart from a keyed
  reorder; see [`gtk-widget-layer.md`](gtk-widget-layer.md).
- `:handlers` tracks each connected GTK signal's connection id *and* its
  retained `foreign-callable`, so `set-event-handler`/`remove-event-handler`
  can cleanly disconnect and release exactly the right one later.
- `:glitter/structural-props` holds a handful of props a CHILD
  carries for its PARENT to consume: `:grid-column`/`:grid-row`/
  `:grid-column-span`/`:grid-row-span` (`:grid`) and `:stack-name`
  (`:stack`): stashed here by `set-attribute` instead of being routed to
  the child's own `:apply` closure, since no widget's `:apply` has any
  idea what "which grid cell am I in" would mean for itself. See
  [`gtk-widget-layer.md`](gtk-widget-layer.md) for the full mechanism and
  why these props must be plain, non-namespaced keywords.

## `:ctor`'s `props` argument is always empty

`:ctor` never receives the real hiccup props. `glitter.core`'s `create-node`
calls `IRender/create-element` with only an optional XML-namespace hint:

```clojure
;; glitter.core.clj, create-node's actual call
(r/create-element renderer tag-name (when ns {:ns ns}))
```

So `glitter.gtk`'s `create-element` invokes a spec's `:ctor` with `{}` or
`{:ns "..."}`. The real prop values arrive afterward, because
`glitter.core`'s `set-attributes` calls `IRender/set-attribute` once per
key, not as one batched map:

```clojure
;; glitter.gtk.clj, set-attribute's actual body
(set-attribute [_ el a v _opt]
  (w/apply-props! (:tag @el) (ptr el) {(keyword a) v})
  nil)
```

Two consequences for widget-spec authors:

1. A `:ctor` that branches on a prop (`(if (:label p) ...)`) never sees it
   through the real reconciler, so `:apply` must also apply that prop or it
   never takes effect.

2. An `:apply` that combines several keys into one native call must not use
   a hardcoded fallback for an absent key (`(or (:min p) 0)`). A render that
   changes only `:max` delivers `{:max 80}` alone, and the fallback would
   overwrite the real `:min`. Read the widget's own current value through a
   GTK getter instead. See [`gtk-widget-layer.md`](gtk-widget-layer.md).

`IMemory` (`remember`/`recall`) is glitter's equivalent of Replicant's
per-element scratch storage, used for e.g. stashing a value on mount and
reading it back on update. It keys off the `el` atom itself, not the raw
pointer. An atom is already a stable Clojure identity, which sidesteps any
question of whether Jolt FFI pointers hash/compare correctly as map keys.

## Event dispatch: data, not closures

A hiccup handler in glitter is data (`[:button {:on {:click
[[:action/inc]]}}]`) dispatched through one global function registered via
`core/set-dispatch!`:

```clojure
(core/set-dispatch!
 (fn [event actions]
   (doseq [[kind & args] actions]
     (case kind ...))))
```

`glitter.core/get-event-handler` wraps the raw action data in a function
that, when the underlying GTK signal fires, calls `(*dispatch* event-map
actions)` (`event-map` is built by `build-event-map`, which (on the `:clj`
side) reads the acting element out of `(:glitter/node e)` rather than a DOM
`event.target` (**deviation #1** from the pure Replicant port; see
[`porting-and-attribution.md`](porting-and-attribution.md))).

Because the handler is just data, it can change between renders (the same
button's `:on {:click [...]}` can carry different action tuples across two
renders) without the *signal itself* needing to change. `glitter.core`'s
diff still calls `IRender/set-event-handler` again whenever that data
changes, which is exactly why `glitter.gtk` has to manage GTK signal
connect/disconnect itself instead of connecting once at widget-creation
time. Full mechanics: [`gtk-widget-layer.md`](gtk-widget-layer.md).
