# Known v1 limitations

Each of these was a deliberate, human-approved scope decision made during
the project's final whole-branch review — not an oversight. If you're
tempted to "just fix" one of these opportunistically, read the rationale
first; each has a reason the mechanical fix was deferred rather than a
reason it's impossible.

## Removing an attribute entirely is a no-op

Setting a prop to a *new* value — including an explicit `false` — always
works correctly (see [`gtk-widget-layer.md`](gtk-widget-layer.md#boolean-props-some-not-truthiness)).
Dropping a key from your hiccup entirely does not.

**Why:** `glitter.widget/apply-props!` only ever receives the keys present
in the new prop map — that's intentional, and what makes it safe to call
with a single-key partial map like `{:label "new text"}` (exactly how
`glitter.gtk`'s `set-attribute` uses it, applying one changed key at a
time rather than the whole prop map on every change). A key that's
*absent* from the new map is simply never visited again, so the widget
keeps whatever value it last had.

DOM's `removeAttribute` has an obvious browser meaning (the attribute
disappears from the element, and CSS/JS both understand "attribute not
present" as a real, distinct state). GTK has no equivalent generic
"unset this property, revert to type default" API — a `GObject` property
always has *some* value, and there's no ABI-level way to ask "what would
this widget's `:sensitive` be if nobody had ever set it."

**What a real fix needs:** per-widget-type default values designed into
`glitter.widget`'s `:apply` closures (e.g. knowing that a fresh
`GtkCheckButton`'s `:active` defaults to `false`, so "attribute removed"
could mean "re-apply that default"). This is real design work per widget
type, not a mechanical patch — out of scope for v1, consistent with this
project's existing "no CSS wiring, no animations" v1 boundaries.

`glitter.gtk/remove-attribute`'s implementation makes this explicit rather
than silently no-opping without comment:

```clojure
(remove-attribute [_ el a]
  (w/apply-props! (:tag @el) (ptr el) {(keyword a) nil})
  nil)
```

— confirmed live against real GTK state: setting a checkbutton's `:active
true` then "removing" it still leaves `gtk_check_button_get_active`
returning `1`.

## `mount!` is one-way

`glitter.gtk/mount!` registers its `add-watch` under a fixed key
(`::render`) and returns `nil` — there is no corresponding `unmount!`.

**Consequences:**
- Mounting twice against the *same* state atom silently replaces the first
  watcher (the second `add-watch ::render ...` call under the same key
  overwrites the first), rather than running both.
- There's no way to detach a mounted tree and stop it re-rendering, short
  of `(remove-watch state-atom ::render)` by hand from outside the API.

**Why left as-is:** every current example and the intended v1 usage
pattern (one root window, mounted once, for the process's life) never
needs unmount. Adding a real unmount API — one that also tears down the
mounted widget tree, not just the watcher — is real design work (what
happens to in-flight renders? does it also need to release every retained
`foreign-callable` under the unmounted subtree?) better done when there's
an actual multi-window or hot-reload use case driving the requirements,
rather than speculatively.

## `IMemory` never releases

`:glitter/remember` data is held in a process-global map
(`glitter.gtk`'s private `memory` atom) keyed by the element atom, with no
eviction when that element is unmounted/removed from the tree.

```clojure
(defonce ^:private memory (atom {}))
...
proto/IMemory
(remember [_ node data] (swap! memory assoc node data) nil)
(recall [_ node] (get @memory node))
```

**Why this is fine for the intended use, but not for everything:** the
common `IMemory` use case is a value stashed on mount and read back on a
later update (Replicant's own docs describe this pattern) — small,
bounded, and the element usually lives for the app's whole lifetime
anyway. It is *not* fine for anything long-lived-relative-to-element-
churn or high-cardinality — a keyed list that creates and discards many
short-lived rows over a session would leak one map entry per discarded
row, forever.

**What Replicant's DOM backend does instead:** a `WeakMap` — verified
directly against `replicant.dom`'s source
(`(def ^:no-doc memories (js/WeakMap.))`), which lets the JS garbage
collector reclaim an entry the moment nothing else references its key
DOM node. There is no Jolt/Chez equivalent of a weak-keyed map available
to reach for yet, so this gap doesn't currently have a mechanical fix —
it's a real platform-capability gap, not a missed line of code.

## Not a limitation, but worth restating here: no GTK CSS wiring, no animations

Hiccup `:style`/`:class` props are accepted by the reconciler (diffed,
`IRender/set-style`/`remove-style`/`add-class`/`remove-class` are called)
but every one of those methods on `glitter.gtk`'s renderer is currently a
no-op — `glitter.widget` (forked from glimmer) has no `:style`/`:class`
vocabulary to build on yet. `on-transition-end` fires its callback
immediately and synchronously — there is no animated mount/unmount
transition support. Both are explicit v1 scope boundaries from the design
spec, not partially-implemented features.
