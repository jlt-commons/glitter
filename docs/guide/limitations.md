# Known v1 limitations

Each of these was a deliberate scope decision, not an oversight. Read the
rationale before "just fixing" one, because each has a reason the
mechanical fix was deferred.

## Removing an attribute entirely is a no-op

Setting a prop to a new value (including an explicit `false`) works (see
[`gtk-widget-layer.md`](gtk-widget-layer.md#boolean-props-use-some)).
Dropping a key from your hiccup does not.

**Why:** `glitter.widget/apply-props!` only receives the keys present in
the new prop map, which is what makes a single-key partial map like
`{:label "new text"}` safe (`glitter.gtk`'s `set-attribute` applies one
changed key at a time). An absent key is never visited, so the widget keeps
its last value. GTK has no generic "unset this property" API, because a
`GObject` property always has some value.

**What a real fix needs:** per-widget-type default values in
`glitter.widget`'s `:apply` closures (a fresh `GtkCheckButton`'s `:active`
defaults to `false`, so "removed" could re-apply that). That is design work
per widget type.

`glitter.gtk/remove-attribute` makes this explicit:

```clojure
(remove-attribute [_ el a]
  (w/apply-props! (:tag @el) (ptr el) {(keyword a) nil})
  nil)
```

Setting a checkbutton's `:active true` and then "removing" it still leaves
`gtk_check_button_get_active` returning `1`.

## `mount!` is one-way

`glitter.gtk/mount!` registers its `add-watch` under a fixed key
(`::render`) and returns `nil`. There is no `unmount!`.

- Mounting twice against the same state atom replaces the first watcher
  instead of running both.
- To stop a mounted tree re-rendering, call
  `(remove-watch state-atom ::render)` by hand.

**Why left as-is:** the intended usage is one root window mounted for the
process's life. A real unmount would also have to tear down the widget
tree and release retained `foreign-callable`s, which is better designed
once a multi-window or hot-reload use case exists.

## A state change during `mount!`'s first render does not repaint

`mount!` renders once and then installs its `add-watch` (`glitter/gtk.clj`:
`render!`, then `add-watch`). A state change during that first render
updates the atom with no watcher listening, so nothing repaints until the
next change.

GTK auto-selects the first page of a `:notebook` or `:stack` as it is
populated, which dispatches through glitter during that first render. The
atom then holds 2 when `mount!` returns while the label still shows 0.

In most apps nothing needs doing: the state is correct and the first
interaction repaints. If a view must show a mount-time-derived value
immediately, swap the atom once after `mount!` returns.
`examples/glitter/gallery_chrome.clj` deliberately doesn't, so the
behaviour is visible.

## `IMemory` never releases

`:glitter/remember` data is held in a process-global map (`glitter.gtk`'s
private `memory` atom) keyed by the element atom, with no eviction when the
element is removed from the tree.

```clojure
(defonce ^:private memory (atom {}))
...
proto/IMemory
(remember [_ node data] (swap! memory assoc node data) nil)
(recall [_ node] (get @memory node))
```

This is fine for the usual use, a small value stashed on mount and read
back on update. It is not fine for high-cardinality use: a keyed list that
creates and discards many short-lived rows would leak one entry per
discarded row. Replicant's DOM backend uses a `WeakMap`
(`replicant.dom`'s `memories`). Jolt/Chez has no weak-keyed map yet, so
there is no mechanical fix.

## Swapping a fixed-slot container's child tag

Several containers have fixed named slots and no spare capacity for a
second occupant. `glitter.core`'s reconciler handles a same-position,
non-keyed tag mismatch (a slot's child going from `:label` to `:button`) as
two steps, insert the new node and then remove the old one, which assumes
the container can briefly hold both. `glitter.gtk`'s generic `:children`
bookkeeping also assumes `:box`-like extra capacity.

**What to do instead, for all of the cases below:** change a slot's props,
not its tag (a props-only update never goes through insert/remove), or nest
a stable wrapper tag one level down (always render `[:box [...]]` in the
slot), so the type change happens where `:box`'s capacity handles it.

**Why left as-is:** a real fix needs `glitter.gtk`'s child bookkeeping to
become container-kind-aware. That changes shared machinery, and swapping a
fixed-slot tag dynamically is a narrow pattern.

### `:center-box` with all 3 slots full

`GtkCenterBox` has three slots (`start`/`center`/`end`).
`gtk_center_box_set_*_widget` unparents the previous occupant the instant
the new one is set, and the stale bookkeeping then corrupts an unrelated
third slot. Full trace:
[`gtk-widget-layer.md`](gtk-widget-layer.md#slot-containers-cannot-swap-a-tag-while-full).

### `:paned` with both slots full

`GtkPaned` has two slots (`start`/`end`). Swapping the last slot's tag works.
Swapping the first slot's tag fails differently from `:center-box`: the
insert finds no empty slot and no-ops, and removing the old occupant leaves
the slot empty. Full trace:
[`gtk-widget-layer.md`](gtk-widget-layer.md#slot-containers-cannot-swap-a-tag-while-full).

### `:overlay` once the main child is occupied

`overlay-insert-after!` only handles `sibling = nil, slot still empty`. GTK
also has no API to enumerate overlay children (no "get overlays" in
`gtk/gtkoverlay.h`), so anything beyond appending, removing or replacing
the main slot, plus appending or removing an overlay, can't be verified by
a live smoke. See
[`gtk-widget-layer.md`](gtk-widget-layer.md#overlay).

### `:header-bar`/`:action-bar` title or center widget while occupied

`*-insert-after!`'s nil-sibling branch falls through to `*-append-child!`,
which sees the slot occupied and pack-starts the new widget. Removing the
old title then leaves the slot empty with the new widget stranded in the
pack-start list.

## `:header-bar`/`:action-bar` never call `pack_end`

Both widgets' `pack_end` reverse-accumulate (a `gtk_box_prepend`, or a
`gtk_box_insert_child_after(..., NULL)`, which is also a prepend). Feeding
end-region children one at a time in hiccup order, as the reconciler does,
would land them in reverse order with no public API to fix it. v1 only wires
`pack_start`. Adding end-region support means solving the reversal too (for
example by resyncing the whole region on every mutation). Neither pack-start
region can be reordered, because it is a private `GtkBox` glitter has no
pointer to and only the append-only `pack_start` functions are public.

## `:header-bar`'s `:show-title-buttons` shares glitter's pack-start list

`gtk_header_bar_set_show_title_buttons(bar, TRUE)` prepends a native
`GtkWindowControls` widget into the same `start_box` that glitter's
pack-start children live in. glitter's correctness is unaffected, since
`gtk_header_bar_remove`/`replace` only act on widgets glitter created, but
code inspecting the pack-start region directly must allow for GTK's widget
at the front. See
[`gtk-widget-layer.md`](gtk-widget-layer.md#header-bar-and-action-bar).

## `:flow-box`'s `:on-child-activated` dispatches with no value

v1 doesn't wire a value-fn for `"child-activated"`. Reading back which
child needs `GList` traversal via `gtk_flow_box_get_selected_children`, an
FFI complexity class this project hasn't needed elsewhere. `:on-click` and
`:on-toggled` already dispatch with no `:glitter/value`, so apps that need
"which child" must correlate it themselves from what they rendered.

## `:notebook` has no per-child tab-label convention

`notebook-append-child!`/`notebook-insert-after!` pass `NULL` as the
`tab_label`, so GTK generates default numbered tabs. There is no hiccup
convention for a custom label per page, since it would need a second widget
per child. Render your own tab strip alongside a plain `:box` if you need
custom tabs.

## `:notebook` dispatches an action as a side effect of mounting

`gtk_notebook_insert_page` auto-selects the first page added to a notebook
with no current page. Mount appends the initial children through that path,
so constructing a `[:notebook ...]` with pre-populated pages fires
`"switch-page"` and dispatches whatever action the hiccup wires to it before
the user does anything. This is GTK behavior, and no other container here
does it. An app counting dispatches, or assuming none fire before
interaction, must allow for this one. See
[`gtk-widget-layer.md`](gtk-widget-layer.md#notebook-and-stack).

## Bulk `GtkEditable` text replacement can fire `"changed"` once or twice

`gtk_editable_set_text` calls `gtk_editable_delete_text` then
`gtk_editable_insert_text`, and only property `notify` is frozen around the
pair. Replacing text in an empty buffer fires `"changed"` once (the delete
is a no-op), and replacing non-empty text fires it twice. This affects every
`GtkEditable`-based widget (`:entry`, `:password-entry`, `:search-entry`,
`:editable-label`) and only matters when code bulk-replaces text through
`gtk_editable_set_text`, as the smoke tests do. Character-by-character
typing never hits it, and every `GtkEditable` smoke starts from empty text
to avoid asserting on the doubling. See
[`gtk-widget-layer.md`](gtk-widget-layer.md#entries-and-gtkeditable).

## `:search-bar` cannot auto-manage search mode via key capture

v1 doesn't wire `gtk_search_bar_connect_entry` or
`gtk_search_bar_set_key_capture_widget`, since both need a raw widget pointer
and glitter has no hiccup convention for passing a pointer to a different
widget in the tree. `:search-mode` is fully controllable programmatically.
Only the Ctrl+F/Escape auto-toggle is missing.

## `:grid`/`:stack`: a child's structural props are read only at first attach

`:grid-column`/`:grid-row`/`:grid-column-span`/`:grid-row-span` (read by
`:grid`) and `:stack-name` (read by `:stack`) are stashed on the child's
tracking atom via `:glitter/structural-props` and consumed only when the
child is freshly attached (a fresh mount or a keyed insert). Changing them
on a later re-render doesn't move the child or rename its stack page.

**Why left as-is:** these are the first containers where position is data
the child carries. Making it reactive would mean detecting a structural-prop
diff inside `set-attribute` and re-invoking `gtk_grid_attach` or
`gtk_stack_add_named` on an already-parented widget, which hasn't been
verified. It is fine for static layouts. See
[`gtk-widget-layer.md`](gtk-widget-layer.md#child-borne-props-glitterstructural-props).

## `:stack` doesn't honor a non-default initial `:visible-child-name` at mount

`:apply` runs at `create!` time, before the reconciler appends any children
(see
[`gtk-widget-layer.md`](gtk-widget-layer.md#props-flow-through-apply-never-ctor)),
so an initial `[:stack {:visible-child-name "b"} ...]` applies the name to an
empty stack. `set-stack-visible-child-name!` guards with
`gtk_stack_get_child_by_name` to avoid a GTK warning, but the page still
doesn't take effect, and GTK's `gtk_stack_add_page` auto-selects the first
visible child. Later re-renders work, so only the first selection is
affected.

**Why left as-is:** a fix needs `:apply` to defer the call until after the
children are appended, which changes the create!/apply-props! sequencing
shared by every widget spec. See
[`gtk-widget-layer.md`](gtk-widget-layer.md#notebook-and-stack).

## `:window`'s `:width`/`:height` can clobber each other across single-key re-renders

`window-spec` has the multi-key clobbering that the ctor/apply audit fixed in
`:scale`/`:spin-button`/`:scale-button`.
`gtk_window_set_default_size(window, width, height)` takes both dimensions,
so a render changing only one risks resetting the other to the `-1` fallback
instead of its last value.

**Why not fixed:** the usual fix reads the widget's current value as the
fallback, which needs `gtk_window_get_default_size`, and that returns through
out-parameters, an FFI marshalling class this project hasn't taken on. The
severity is low (`:width`/`:height` are initial-sizing concerns and `-1`
means natural size), and nothing here sets them across separate single-key
re-renders. A comment above `window-spec` records it. See
[`gtk-widget-layer.md`](gtk-widget-layer.md#props-flow-through-apply-never-ctor).

## Action-expansions dispatch N sequential renders, not one atomic transition

Each effect inside an expansion drives its own synchronous `core/reconcile`.
`crud.clj`'s `:action/delete` is 4 effects and 4 renders. No demo shows wrong
intermediate state today, but a new demo could. A batching effect would be
new architecture, so it is out of scope. See
[`nexus.md`](nexus.md#action-expansions-are-not-atomic).

## `nexus.registry`'s registry is one process-global atom

The registry is a single top-level atom, so two glitter apps in one process
(two demos loaded into one REPL) share one set of registered effects,
placeholders and expansions. Every demo runs as its own process
(`jolt -M:crud`, `jolt -M:flights`, ...), so this hasn't mattered, but it
constrains any multi-app-in-one-process use.

## No action-log viewer ships

`glitter.nexus.action-log` accumulates a dispatch/expansion/effect tree
(see [`nexus.md`](nexus.md#the-action-log)), and `(pr-str @log)` is the only
way to inspect it. A GTK-native viewer is out of scope for v1.

## `:password-entry` cannot have placeholder text

`:entry` and `:search-entry` accept `:placeholder`. `:password-entry`
doesn't, and glitter refuses the prop rather than accept one it can't honour.

**Why:** `GtkPasswordEntry` has a `placeholder-text` GObject property but no
`gtk_password_entry_set_placeholder_text` function (calling it on GTK 4.22.4
fails with `no entry for "gtk_password_entry_set_placeholder_text"`).
Reaching a property with no setter needs `g_object_set_property` plus
`GValue` marshalling, which this project hasn't needed elsewhere.
`gallery_smoke.clj` reads `:entry` and `:search-entry` placeholders back as
its regression check.

**Verify by calling.** `jolt.ffi/dlsym-native` reports
`gtk_editable_set_placeholder_text` present and the call then fails, and a
foreign-procedure built via `__cfn` reports every symbol callable even with
no GTK loaded. Only calling the function inside a running app tells you.

## `:style` is a no-op, and there are no animations

`:style` is diffed (`IRender/set-style`/`remove-style` are called) but both
are no-ops. GTK4 has no inline `element.style.color = ...`: styling is
class-based, matched against rules loaded through a `GtkCssProvider`. A real
`:style` would need a generated class name and rule text per widget plus
provider lifecycle management. `:class` does work: `IRender/add-class` and
`remove-class` call `gtk_widget_add_css_class`/`gtk_widget_remove_css_class`,
including GTK4's built-in classes (`"flat"`, `"suggested-action"`,
`"destructive-action"`, `"pill"`, ...). See
[`gtk-widget-layer.md`](gtk-widget-layer.md#removing-a-key-is-a-no-op)
and `examples/glitter/class_smoke.clj`.
`on-transition-end` fires its callback immediately and synchronously, so
there are no animated mount/unmount transitions.

## `timer.clj`: dragging duration to 0 is a UX dead end, not a crash

The duration `:scale` allows `:min 0`. `get-view-state` stays safe there
(coerced to a double before the division, giving `{:pct 0 :elapsed "0s"}`),
but the display freezes at 0%/"0s" with no way to progress. Not fixed, since
the 7GUIs task doesn't say what a 0-second timer should do.
