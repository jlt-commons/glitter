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

## `:center-box` cannot safely swap a slot's hiccup tag while all 3 slots are full

`GtkCenterBox` has exactly three fixed named slots (`start`/`center`/
`end`) — no transient capacity for a 4th simultaneous occupant the way
`:box`'s ordered list has.

**Why this matters:** `glitter.core`'s reconciler handles a same-position,
non-keyed hiccup TAG mismatch (e.g. a slot's child going from `:label` to
`:button`) as two separate steps — insert the new node, then remove the
old one — relying on the container having room to briefly hold both.
`GtkCenterBox` doesn't; `gtk_center_box_set_*_widget` unparents (and,
since nothing else references it, GTK finalizes) whatever was previously
in that slot the instant the new one is set. `glitter.gtk`'s own
`:children` bookkeeping, however, is generic across every container kind
and briefly assumes `:box`-like extra capacity — that mismatch corrupts
an UNRELATED third slot, not the one being swapped. Full trace:
[`gtk-widget-layer.md`](gtk-widget-layer.md#center-box--a-genuinely-new-container-strategy-and-a-real-v1-gap).

**What to do instead:** change a slot's PROPS, not its TAG (a props-only
update never goes through insert/remove at all). If the slot genuinely
needs to switch widget types dynamically, nest a stable wrapper tag one
level down (e.g. always render `[:box [...]]` in that slot) so the type
change happens where `:box`'s own genuine transient capacity already
handles it correctly.

**Why left as-is:** a real fix would need `glitter.gtk`'s generic
`:children` bookkeeping to become container-kind-aware (know that
`:center-box` has zero spare capacity and defer/reorder its own updates
accordingly) — a change to shared reconciler-adjacent machinery, not a
one-widget patch, and this round's tag-swap scenario is a narrow enough
usage pattern (most center-box usage keeps a stable widget type per slot
across re-renders) that it wasn't judged worth that scope increase yet.

## `:list-box`'s `reorder-child!` is implemented but not live-verified

`list-box-reorder-child!` (moving an already-parented row to a new
position) is real, reasoned-through code — not a documented no-op like
`:center-box`'s equivalent — but no smoke in this project currently
exercises a genuine reorder (only a same-position tag-swap, which goes
through `insert-child-after!`, and a pure append/remove, were live-tested).
Treat it as implemented, not verified-under-fire, until a keyed-list
reordering smoke covers it. See
[`gtk-widget-layer.md`](gtk-widget-layer.md#list-box--a-third-callable-shape-and-two-more-real-bugs).

## No longer a limitation: `:class` reaches real GTK CSS classes

Hiccup `:class` is diffed by the reconciler (`IRender/add-class`/
`remove-class`), and `glitter.gtk` now wires both to
`gtk_widget_add_css_class`/`gtk_widget_remove_css_class` — GTK4's actual
per-widget styling hook, including its built-in classes (`"flat"`,
`"suggested-action"`, `"destructive-action"`, `"pill"`, ...) which apply
with zero app-provided CSS. See
[`gtk-widget-layer.md`](gtk-widget-layer.md#known-gap-removing-a-key-is-a-no-op)
for the mechanics and `examples/glitter/class_smoke.clj` for the live
verification (add, coexist, and — the part that actually proves the diff
path works, not just `add-class` — remove on a re-render).

## Still a limitation: `:style`, and no animations

`:style` is still diffed (`IRender/set-style`/`remove-style` are called)
but both remain no-ops. Unlike `:class`, there's no small FFI addition
that fixes this: GTK4 has no equivalent of DOM's inline
`element.style.color = ...` — styling is exclusively class-based, matched
against rules loaded through a `GtkCssProvider`. A real `:style` prop
would need generating a unique class name and CSS rule text per widget,
managing that provider's lifecycle, and invalidating/reloading the rule
on every diff — genuine design work, not attempted here.
`on-transition-end` still fires its callback immediately and
synchronously — there is no animated mount/unmount transition support.
Both remain explicit v1 scope boundaries from the design spec, not
partially-implemented features.
