# Known v1 limitations

Each of these was a deliberate, human-approved scope decision made during
the project's final whole-branch review, not an oversight. If you're
tempted to "just fix" one of these opportunistically, read the rationale
first; each has a reason the mechanical fix was deferred rather than a
reason it's impossible.

## Removing an attribute entirely is a no-op

Setting a prop to a *new* value (including an explicit `false`) always
works correctly (see [`gtk-widget-layer.md`](gtk-widget-layer.md#boolean-props-some-not-truthiness)).
Dropping a key from your hiccup entirely does not.

**Why:** `glitter.widget/apply-props!` only ever receives the keys present
in the new prop map: that's intentional, and what makes it safe to call
with a single-key partial map like `{:label "new text"}` (exactly how
`glitter.gtk`'s `set-attribute` uses it, applying one changed key at a
time rather than the whole prop map on every change). A key that's
*absent* from the new map is simply never visited again, so the widget
keeps whatever value it last had.

DOM's `removeAttribute` has an obvious browser meaning (the attribute
disappears from the element, and CSS/JS both understand "attribute not
present" as a real, distinct state). GTK has no equivalent generic
"unset this property, revert to type default" API; a `GObject` property
always has *some* value, and there's no ABI-level way to ask "what would
this widget's `:sensitive` be if nobody had ever set it."

**What a real fix needs:** per-widget-type default values designed into
`glitter.widget`'s `:apply` closures (e.g. knowing that a fresh
`GtkCheckButton`'s `:active` defaults to `false`, so "attribute removed"
could mean "re-apply that default"). This is real design work per widget
type, not a mechanical patch; out of scope for v1, consistent with this
project's existing "no CSS wiring, no animations" v1 boundaries.

`glitter.gtk/remove-attribute`'s implementation makes this explicit rather
than silently no-opping without comment:

```clojure
(remove-attribute [_ el a]
  (w/apply-props! (:tag @el) (ptr el) {(keyword a) nil})
  nil)
```
;
confirmed live against real GTK state: setting a checkbutton's `:active
true` then "removing" it still leaves `gtk_check_button_get_active`
returning `1`.

## `mount!` is one-way

`glitter.gtk/mount!` registers its `add-watch` under a fixed key
(`::render`) and returns `nil`; there is no corresponding `unmount!`.

**Consequences:**
- Mounting twice against the *same* state atom silently replaces the first
  watcher (the second `add-watch ::render ...` call under the same key
  overwrites the first), rather than running both.
- There's no way to detach a mounted tree and stop it re-rendering, short
  of `(remove-watch state-atom ::render)` by hand from outside the API.

**Why left as-is:** every current example and the intended v1 usage
pattern (one root window, mounted once, for the process's life) never
needs unmount. Adding a real unmount API (one that also tears down the
mounted widget tree, not just the watcher) is real design work (what
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
later update (Replicant's own docs describe this pattern): small,
bounded, and the element usually lives for the app's whole lifetime
anyway. It is *not* fine for anything long-lived-relative-to-element-
churn or high-cardinality; a keyed list that creates and discards many
short-lived rows over a session would leak one map entry per discarded
row, forever.

**What Replicant's DOM backend does instead:** a `WeakMap`: verified
directly against `replicant.dom`'s source
(`(def ^:no-doc memories (js/WeakMap.))`), which lets the JS garbage
collector reclaim an entry the moment nothing else references its key
DOM node. There is no Jolt/Chez equivalent of a weak-keyed map available
to reach for yet, so this gap doesn't currently have a mechanical fix;
it's a real platform-capability gap, not a missed line of code.

## `:center-box` cannot safely swap a slot's hiccup tag while all 3 slots are full

`GtkCenterBox` has exactly three fixed named slots (`start`/`center`/
`end`), no transient capacity for a 4th simultaneous occupant the way
`:box`'s ordered list has.

**Why this matters:** `glitter.core`'s reconciler handles a same-position,
non-keyed hiccup TAG mismatch (e.g. a slot's child going from `:label` to
`:button`) as two separate steps (insert the new node, then remove the
old one) relying on the container having room to briefly hold both.
`GtkCenterBox` doesn't; `gtk_center_box_set_*_widget` unparents (and,
since nothing else references it, GTK finalizes) whatever was previously
in that slot the instant the new one is set. `glitter.gtk`'s own
`:children` bookkeeping, however, is generic across every container kind
and briefly assumes `:box`-like extra capacity: that mismatch corrupts
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
accordingly); a change to shared reconciler-adjacent machinery, not a
one-widget patch, and this round's tag-swap scenario is a narrow enough
usage pattern (most center-box usage keeps a stable widget type per slot
across re-renders) that it wasn't judged worth that scope increase yet.

## `:paned` cannot safely swap a slot's hiccup tag while both slots are full

Same root cause as `:center-box`'s gap above (`GtkPaned` has exactly two
fixed named slots (`start`/`end`), no transient capacity for a 3rd
simultaneous occupant), but a DIFFERENT, live-verified failure shape,
since two slots behave differently from three when the reconciler's
"insert new, then remove old" sequencing runs out of room:

- Swapping the **last** slot's tag while both are full lands correctly:
  there is no third slot after it for the reconciler's stale bookkeeping
  to corrupt into, unlike `:center-box`.
- Swapping the **first** slot's tag while both are full fails
  differently: the insert step finds no empty slot and silently no-ops,
  and the subsequent removal of the old occupant leaves that slot
  genuinely EMPTY, not corrupted, just missing both the old and the new
  widget.

**What to do instead:** same remedy as `:center-box`: change props, not
tags, or nest a stable wrapper tag one level down. Full trace, including
the live verification of both failure shapes:
[`gtk-widget-layer.md`](gtk-widget-layer.md#expanderpaned--free-signal-reuse-and-a-second-structural-gap).

**Why left as-is:** same reasoning as `:center-box`: a real fix needs
`glitter.gtk`'s generic child bookkeeping to become container-kind-aware,
not a one-widget patch, and swapping a fixed-slot container's tag
dynamically is a narrow usage pattern.

## `:overlay` cannot safely swap its main child's hiccup tag once occupied

Same class of gap as `:center-box`/`:paned`: `GtkOverlay`'s one named
slot (the main content) has no transient capacity for a second
simultaneous occupant, so `overlay-insert-after!` only handles the
`sibling = nil, slot still empty` case for real. Same remedy: change
props, not tags, or nest a stable wrapper tag one level down.

A second, more fundamental gap on top: GTK exposes **no API to
enumerate current overlay children at all** (confirmed: no "get
overlays" function in `gtk/gtkoverlay.h`), so anything beyond
"append/remove/replace the main slot correctly, plus append/remove an
overlay" is unverifiable by a live smoke, not merely untested. See
[`gtk-widget-layer.md`](gtk-widget-layer.md#overlayflow-box--a-third-container-shape-and-a-verified-difference-not-an-assumption).

## No longer a limitation: `:list-box`'s and `:flow-box`'s `reorder-child!`

This section used to read: *"implemented but not live-verified... no
smoke in this project currently exercises a genuine reorder for
either... treat both as implemented, not verified-under-fire, until a
keyed-list reordering smoke covers them."* That caveat was correct to
have; the live-verification it called for found a REAL bug.
`examples/glitter/crud.clj` (a port of the 7GUIs CRUD task) was the
first thing in this project to actually trigger a genuine keyed
reorder (renaming a selected person to a family name that sorts
differently), and it crashed: `list-box-reorder-child!`/
`flow-box-reorder-child!` were reusing a widget pointer that GTK had
already disposed as a side effect of removing its old wrapping row:
confirmed by reading `gtk_list_box_row_dispose`'s and
`gtk_flow_box_child_dispose`'s C bodies directly. Fixed via a
`g_object_ref_sink`/`g_object_unref` bracket around the remove-then-
reinsert in both functions, keeping the child alive across the gap.
`examples/glitter/list_box_reorder_smoke.clj` now pins this
permanently: reorders a keyed `:list-box` and a keyed `:flow-box` by
the same keys and reads the new order back via the live GTK tree, not
glitter's own bookkeeping, FAIL-path verified by temporarily reverting
the fix. See
[`gtk-widget-layer.md`](gtk-widget-layer.md#list-box-reorder-child-flow-box-reorder-child--a-real-use-after-dispose-bug)
for the full trace.

## `:flow-box`'s `:on-child-activated` dispatches with no value

v1 deliberately does not wire a value-fn for `"child-activated"`:
reading back "which child" would need `GList` traversal via
`gtk_flow_box_get_selected_children`, a new FFI complexity class (walking
a linked list through raw pointers) this project hasn't needed
elsewhere. `:on-click`/`:on-toggled` already establish the precedent
that a dispatched event with no `:glitter/value` is a normal, supported
shape: apps that need "which child was activated" must correlate it
themselves (e.g. via their own understanding of what's currently
rendered in the flow-box) until a real fix lands.

## `:notebook` has no per-child tab-label convention

`notebook-append-child!`/`notebook-insert-after!` always pass `NULL` as
`gtk_notebook_append_page`/`insert_page`'s `tab_label` argument: GTK
auto-generates a default numbered tab in that case. There is no v1
hiccup convention for supplying a custom tab label per page (that would
mean a second widget per child, a shape no other container in this
project needs), so this was deliberately deferred rather than invented
under time pressure. Apps that need custom tabs must wait for a real
fix, or work around it by rendering their own tab-strip alongside a
plain `:box` instead of using `:notebook`'s built-in tabs.

## `:notebook` dispatches a real action as a side effect of mounting

`gtk_notebook_insert_page`'s own C body auto-selects the first page
added to a notebook that has no current page yet (`if
(!gtk_notebook_has_current_page (notebook)) { gtk_notebook_switch_page
(notebook, page); }`, confirmed by reading it directly). Since
`:notebook`'s initial children are appended via this exact path during
mount, constructing a `[:notebook ...]` with pre-populated pages
genuinely fires `"switch-page"` (and therefore dispatches whatever
action the hiccup wires to it) before the app has done anything. This
is real GTK behavior, not a glitter defect, but it means any app
tracking a dispatch count (or assuming "no action fires until the user
interacts") must account for this one mount-time dispatch specifically
for `:notebook`; no other container in this project behaves this way.
See
[`gtk-widget-layer.md`](gtk-widget-layer.md#notebookscale-button--a-sixth-callable-shape-a-mount-time-surprise-and-a-tag-aware-dispatch)
for the full trace, including the empirical probe that confirmed it.

## Bulk `GtkEditable` text replacement can fire `"changed"` once or twice

`gtk_editable_set_text` is not a single atomic mutation: its C body
calls `gtk_editable_delete_text` then `gtk_editable_insert_text`
separately, and only property `notify` (not `"changed"` itself) is
frozen around the pair. Replacing text in an already-empty buffer fires
`"changed"` once (the delete is a no-op, confirmed via
`gtk/gtktext.c`'s early return when `start_pos == end_pos`); replacing
non-empty text fires it twice. This affects every widget in this
project built on the `GtkEditable` delegate (`:entry`,
`:password-entry`, `:search-entry`, `:editable-label`) not just one of
them, and only matters when a caller bulk-replaces text via
`gtk_editable_set_text` directly (this project's own "bypass the
wrapper, trigger the real signal" smoke-testing technique, or any real
app code doing a programmatic bulk replace); normal character-by-
character typing never hits this path. Every `GtkEditable`-family smoke
in this project starts its interaction target from empty text to avoid
asserting on this incidental doubling rather than the behavior under
test. See
[`gtk-widget-layer.md`](gtk-widget-layer.md#pictureeditable-label--a-quick-win-a-third-gtkeditable-delegate-and-a-general-gtkeditable-finding)
for the full trace.

## `:header-bar`/`:action-bar` cannot safely swap the title/center-widget's hiccup tag while occupied

Same root cause and same class of gap as `:center-box`'s/`:paned`'s/
`:overlay`'s: `*-insert-after!`'s `sibling` nil branch falls through to
`*-append-child!`, which sees the title/center-widget slot still
occupied and pack-starts the new widget instead of replacing the
title: the reconciler's subsequent removal of the old title then
leaves that slot empty with the new widget stranded in the pack-start
list. Change props instead of tags, or nest a stable wrapper tag one
level down.

## `:header-bar`/`:action-bar` never call `pack_end`

Confirmed by reading `gtk_header_bar_pack`'s and
`gtk_action_bar_pack_end`'s C bodies directly: both widgets'
`pack_end` reverse-accumulate (a `gtk_box_prepend` under one name, a
`gtk_box_insert_child_after(..., NULL)`: also a prepend: under the
other): feeding END-region children one at a time in hiccup order, as
the reconciler naturally does, would silently land them in REVERSE
order with no public API to fix afterward. v1 only wires `pack_start`;
`gtk_header_bar_pack_end`/`gtk_action_bar_pack_end` are not bound to
anything in this codebase. A future round adding end-region support
must also solve the reversal (e.g. resyncing the whole region on every
mutation), not just call the function. Neither widget's pack-start
region can be reordered either, for a genuinely structural reason: it's
a real ordered list internally, but a PRIVATE `GtkBox` glitter has no
pointer to: only the append-only `pack_start` functions are public.

## `:header-bar`'s `:show-title-buttons` shares glitter's own pack-start list

`gtk_header_bar_set_show_title_buttons(bar, TRUE)` prepends a native
`GtkWindowControls` widget into the SAME `start_box` glitter's own
pack-start children live in (confirmed by reading
`create_window_controls`'s C body directly). This is real GTK
behavior, not a glitter defect, and does not affect glitter's own
correctness (`gtk_header_bar_remove`/`replace` only ever act on
widgets glitter itself created), but any code inspecting the header
bar's pack-start region directly (rather than through glitter's own
API) needs to account for GTK's own widget potentially occupying the
front of that list. See
[`gtk-widget-layer.md`](gtk-widget-layer.md#header-baraction-bar--a-genuinely-new-hybrid-container-shape)
for the full trace, including how `header_bar_action_bar_smoke.clj`
asserts the shift explicitly.

## `:search-bar` cannot auto-manage search mode via key capture

v1 does not wire `gtk_search_bar_connect_entry`/
`gtk_search_bar_set_key_capture_widget`: both need a raw
`GtkEditable`/`GtkWidget` pointer glitter has no hiccup-level
convention for passing sideways yet (every other cross-widget
relationship in this project is either a normal tree child or a
single named prop, not "here's a pointer to a DIFFERENT widget
elsewhere in the tree"). `:search-mode` remains fully controllable
programmatically; only the Ctrl+F/Escape auto-toggle convenience is
missing.

## No longer a limitation: `:class` reaches real GTK CSS classes

Hiccup `:class` is diffed by the reconciler (`IRender/add-class`/
`remove-class`), and `glitter.gtk` now wires both to
`gtk_widget_add_css_class`/`gtk_widget_remove_css_class`: GTK4's actual
per-widget styling hook, including its built-in classes (`"flat"`,
`"suggested-action"`, `"destructive-action"`, `"pill"`, ...) which apply
with zero app-provided CSS. See
[`gtk-widget-layer.md`](gtk-widget-layer.md#known-gap-removing-a-key-is-a-no-op)
for the mechanics and `examples/glitter/class_smoke.clj` for the live
verification (add, coexist, and (the part that actually proves the diff
path works, not just `add-class`) remove on a re-render).

## `:grid`/`:stack`: a child's structural props are read only at first attach

`:grid-column`/`:grid-row`/`:grid-column-span`/`:grid-row-span` (read by
`:grid`) and `:stack-name` (read by `:stack`) are stashed on a CHILD's
own tracking atom via `:glitter/structural-props`, but only consumed at
the moment that child is FRESHLY attached: a fresh mount, or a keyed
insert. Changing an already-attached child's `:grid-column`/`:stack-name`
on a LATER re-render does not move it in the grid or rename its stack
page; there is no code path that re-reads `:glitter/structural-props`
for a child already in the tree.

**Why left as-is:** every other container in this project keys position
either by GTK's own live-queryable occupancy (`:center-box`/`:paned`) or
by plain append order (`:box`/`:list-box`): `:grid`/`:stack` are the
first containers where position is data the CHILD carries, and making
that data reactive to later changes would mean detecting a
structural-prop diff INSIDE `set-attribute` and re-invoking
`gtk_grid_attach`/`gtk_stack_add_named` on an already-parented widget
(gtk_grid_attach's own behavior on an already-attached child at a new
position isn't something this round verified live). Fine for the common
case of a static layout with fixed positions. See
[`gtk-widget-layer.md`](gtk-widget-layer.md#glitterstructural-props--a-childs-props-read-by-its-parent).

## `:stack` doesn't honor a non-default initial `:visible-child-name` at mount

`:apply` runs at `create!` time, BEFORE the reconciler has appended any
children to the newly-created widget (see the ctor/apply prop-flow
finding in [`gtk-widget-layer.md`](gtk-widget-layer.md#the-ctorapply-audit--four-real-previously-shipped-bugs)),
so an initial `[:stack {:visible-child-name "b"} ...]` always applies
`:visible-child-name` to a completely empty stack. `gtk_stack_get_child_by_name`
guards `set-stack-visible-child-name!` to avoid the resulting GTK
warning, but the requested page still doesn't take effect: GTK's own
`gtk_stack_add_page` auto-selects the FIRST added visible child instead,
same as `:notebook`'s mount-time auto-select above. A subsequent
re-render's `:visible-child-name` DOES work correctly (`:apply` runs
after the stack already has children by then); only the very first,
initial page selection is affected.

**Why left as-is:** a real fix needs `:apply` to defer the
`:visible-child-name` call until after the reconciler has appended this
render's children: a change to the create!/apply-props! sequencing
shared by every widget spec, not a `:stack`-local patch. See
[`gtk-widget-layer.md`](gtk-widget-layer.md#stack--a-third-mount-time-auto-dispatch-instance-and-a-real-apply-timing-gap).

## `:window`'s `:width`/`:height` can clobber each other across single-key re-renders

The same multi-key-clobbering shape the ctor/apply audit found and
fixed in `:scale`/`:spin-button`/`:scale-button` (above) also exists in
`window-spec`: `gtk_window_set_default_size(window, width, height)`
takes both dimensions in one call, and a render changing only `:width`
(or only `:height`) risks resetting the other back to a hardcoded `-1`
fallback rather than preserving its last-applied value.

**Why NOT fixed alongside the other three this round:** the fix pattern
used elsewhere (read the widget's CURRENT value as the fallback instead
of a hardcoded default) needs `gtk_window_get_default_size`, whose real
signature returns via OUT-PARAMETERS (`void gtk_window_get_default_size
(GtkWindow*, int *width, int *height)`), a genuinely new FFI marshalling
class this project hasn't taken on anywhere else. The practical severity
is also much lower than the other three: `:width`/`:height` are
initial-sizing-only concerns (GTK's own `-1` fallback means "natural
size," not garbage), and no live smoke or app in this project currently
sets them across separate single-key re-renders. Documented as a comment
above `window-spec` rather than fixed. See
[`gtk-widget-layer.md`](gtk-widget-layer.md#the-ctorapply-audit--four-real-previously-shipped-bugs).

## Action-expansions dispatch N sequential renders, not one atomic transition

`app.clj`'s `on-gui` runs inline when already on the GTK main thread,
and every glitter effect dispatches from a GTK signal callback (that
thread), so each `:effect/assoc-in` inside an action-expansion
(`register-action!`) drives its own full, synchronous `core/reconcile`
before the next effect in that expansion runs, not one render for the
whole expansion. `crud.clj`'s `:action/delete` is the concrete example:
4 effects, 4 renders, where the pre-retrofit hand-written version
computed the whole transition in one `swap!` and drove exactly 1.

**Why this is fine today, but worth knowing:** no demo in this project
currently exposes wrong intermediate state from this: `crud.clj`'s own
intermediate delete-render still has `:selected-id` pointing at the
just-removed person, but `view`'s `selected?` derivation happens to
keep the Update/Delete buttons insensitive regardless. A future demo
built on the action-expansion pattern should keep the possibility in
mind. See
[`nexus.md`](nexus.md#action-expansions-are-not-atomic) for the full
mechanics.

**Why left as-is:** a coarser effect type that batches an expansion's
effects into one render would be new architecture on top of a faithful
nexus port, not a mechanical fix: out of scope here.

## `glitter.nexus.registry`'s registry is one process-global atom

`glitter.nexus.registry/!registry` is a single top-level atom: two
glitter apps sharing one process (e.g. two demos' namespaces loaded
into the same REPL) share one set of registered effects/placeholders/
expansions, not one each. Every demo in this project runs as its own
process (`jolt -M:crud`, `jolt -M:flights`, ...), so this has never
mattered in practice, but it's a real constraint on any future
multi-app-in-one-process usage.

## No action-log viewer ships

`glitter.nexus.action-log` accumulates a full dispatch/expansion/effect
tree (see [`nexus.md`](nexus.md#the-action-log)), but `(pr-str @log)`
is the only inspection method today; there is no GTK-native viewer
window. A deliberate v1 scope decision from the design spec, not an
oversight; upstream's own viewer (`nexus.inspector`) is entangled with
`dataspex.*` rendering protocols that have no glitter/GTK equivalent.

## `(t/today)` is the UTC date, and glitter ships no general fix

Not a glitter bug, but it will bite anything built on glitter that
reaches for a local date, so it belongs on this list.
`jolt-lang/time` hardcodes `ZoneId/systemDefault` and
`Clock/systemDefaultZone` to `(zone-id "Z" 0)`, so tick's `(t/today)`
answers the **UTC** date on every machine and ignores `TZ` even when it
is explicitly set. Measured at 07:29 AEST on 2026-08-24, `(t/today)`
answered `2026-08-23`, and answered `2026-08-23` again under
`TZ=Australia/Sydney`.

`examples/glitter/flights.clj` works around it with a demo-local
`local-today` that asks GLib (`g_date_time_new_now_local`) instead.
**That helper is deliberately not promoted into `glitter.*`.** Doing so
would put a date API in a rendering library, and the honest fix is
upstream — `systemDefault` should answer the machine's real zone
(reading `TZ`, then `/etc/localtime`) rather than returning UTC as
though it knew. The libc backend underneath already answers *named*
zones correctly (`tz-offset-seconds "Australia/Sydney"` => `36000`);
only zone discovery is missing. Copy the four-line helper if you need
it; see [`nexus.md`](nexus.md#the-ttoday-is-utc-finding).

Note the version floor that goes with it: on a jolt older than
`v0.7.23-10-gc50a3717`, the GLib route is wrong too, because jolt's own
boot-time zone probe used to leave `TZ=UTC` set process-globally
([jolt-lang/jolt#712](https://github.com/jolt-lang/jolt/pull/712)).

## Still a limitation: `:style`, and no animations

`:style` is still diffed (`IRender/set-style`/`remove-style` are called)
but both remain no-ops. Unlike `:class`, there's no small FFI addition
that fixes this: GTK4 has no equivalent of DOM's inline
`element.style.color = ...`: styling is exclusively class-based, matched
against rules loaded through a `GtkCssProvider`. A real `:style` prop
would need generating a unique class name and CSS rule text per widget,
managing that provider's lifecycle, and invalidating/reloading the rule
on every diff; genuine design work, not attempted here.
`on-transition-end` still fires its callback immediately and
synchronously; there is no animated mount/unmount transition support.
Both remain explicit v1 scope boundaries from the design spec, not
partially-implemented features.

## `timer.clj`: dragging duration to 0 is a UX dead end, not a crash

`examples/glitter/timer.clj`'s duration `:scale` allows `:min 0`, and
`get-view-state` is written to stay safe at `:duration 0` (coerced to a
double before the percentage division, so it resolves to `{:pct 0
:elapsed "0s"}` rather than throwing), but the practical result is a
permanently frozen 0%/"0s" display with no way to progress, since
`elapsed` is always `(min elapsed 0)`. Not fixed, since "what should a
0-second timer visually do" isn't specified by the 7GUIs task and any
answer is a UX judgment call, not a correctness fix.
