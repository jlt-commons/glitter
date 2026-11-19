# The GTK widget layer

`glitter.widget` maps hiccup tags to GTK widget constructors and prop
appliers. `glitter.gtk` drives it from the `IRender`/`IMemory` protocols.
This is the maintainer view: how the layer works, and the GTK4 traps behind
its design. For the widgets an app author can use, see
[`widgets.md`](widgets.md). Open gaps are listed in
[`limitations.md`](limitations.md).

## Widget specs

Each tag in the `specs` atom has the shape
`{:ctor (fn [props] widget) :apply (fn [widget props]) :container kw}`.
`:container` decides how children attach: `:box` (ordered list), single
child (`:window`, `:frame`, `:scrolled`, `:revealer`, `:expander`,
`:aspect-frame`, `:search-bar`, `:popover`, `:window-handle`), named slots
(`:center-box`, `:paned`), or `:none` (leaf). `register-widget!` lets an
extension add a tag without editing this namespace. `register-signal!` does
the same for a new `:on-*` event key, optionally with a value-fn for signals
that carry a value.

`create!` constructs a widget, applies props, applies the universal
`GtkWidget` props (`apply-widget-props!`: margins, align, expand, size
requests), connects direct `:on-*` props, then runs an optional `:connect`
closure. `:connect` runs once at mount, so it cannot re-wire a handler when
the action data changes. `apply-props!` re-applies a partial prop map and
touches only the keys present in it.

### Props flow through `:apply`, never `:ctor`

`:ctor` always sees an empty prop map from the reconciler, and
`set-attribute` delivers props one key per call, even at initial mount (see
[`architecture.md`](architecture.md#ctors-props-argument-is-always-empty)).
So:

- `:apply` must cover every prop `:ctor` might branch on. A branch in
  `:ctor` is dead code on the real path.
- If `:apply` combines several keys into one native call (`:min`/`:max` into
  `gtk_adjustment_configure`), it must read the widget's own current values
  as fallbacks, never hardcoded defaults. Otherwise a render that changes
  only `:max` resets `:min`.
- `:scale` and `:spin-button` use `scale-apply-range!`, which reads the
  adjustment's current lower/upper as fallbacks.
  `examples/glitter/ctor_apply_regression_smoke.clj` changes `:min`, `:max`
  and `:step` on separate renders to pin this.
- `:window`'s `:width`/`:height` still have the hardcoded-fallback shape
  (`gtk_window_get_default_size` uses out-parameters). See
  [`limitations.md`](limitations.md).

### Boolean props use `some?`

`apply-props!` filters with `some?`, not truthiness, so `{:active false}`
reaches the widget. This matches `glitter.core`'s own deviation #3 (see
[`porting-and-attribution.md`](porting-and-attribution.md)). The two layers
have to agree, or a `false` would survive the diff and be dropped one layer
down.

### Namespaced props never arrive

`glitter.core`'s `set-attr` and `update-attr` skip any namespaced attribute
(`(when-not (namespace attr) ...)`), inherited from Replicant, so
`IRender/set-attribute` never sees `:grid/column`. That is why the
child-borne props are plain hyphenated keywords such as `:grid-column`.

### Removing a key is a no-op

A key dropped from the hiccup is never visited again, so the widget keeps
its last value, because GTK has no generic "unset property". See
[`limitations.md`](limitations.md). `:class` is exempt: `glitter.core`'s
`update-classes` diffs the class sets and calls `IRender/remove-class`,
which `glitter.gtk` wires to `gtk_widget_add_css_class` and
`gtk_widget_remove_css_class`. `:style` stays unwired: GTK4 has no inline
style, so `set-style`/`remove-style` are no-ops.

## Signals

### Connect and disconnect

glitter handlers are data and the diff calls `IRender/set-event-handler`
whenever the handler data changes
([`architecture.md`](architecture.md#event-dispatch-data-not-closures)).
glimmer's `connect-signals!` connects once and never disconnects, so
`glitter.gtk` connects directly and tracks `{:id <connection id> :cb
<foreign-callable>}` per event on the element atom:

```clojure
(set-event-handler [_ el event handler _opt]
  (when-let [{:keys [id cb]} (get-in @el [:handlers event])]
    (g/g-signal-handler-disconnect (ptr el) id)
    (w/release-callable! cb)
    (swap! el update :handlers dissoc event))
  (when-let [signal (w/signal-name (keyword (str "on-" (name event))))]
    (let [value-fn (w/signal-value-fn signal)
          cb (jolt.ffi/foreign-callable
              (fn [src-widget _data]
                (when-not (w/suppressing? src-widget)
                  (handler (cond-> {:glitter/node el :glitter/gtk-widget src-widget}
                             value-fn (assoc :glitter/value (value-fn src-widget))))))
              [:pointer :pointer] :void :collect-safe)
          id (g/g-signal-connect-data (ptr el) signal cb jolt.ffi/null jolt.ffi/null g/CONNECT-DEFAULT)]
      (w/retain-callable! cb)
      (swap! el assoc-in [:handlers event] {:id id :cb cb})))
  nil)
```

Both the connection and the callable must be released, otherwise every
handler whose data changes leaks a pinned foreign-callable. (This snippet is
the original two-argument form. The shape branching and the `[tag signal]`
key are described below.)

### Reading a value

A value-bearing signal puts the value at `:glitter/value`. `build-event-map`
wraps it under `:glitter/dom-event`, so a handler reads it from its first
dispatch argument, not from its static action data:

```clojure
(defn execute-actions [event actions]
  (doseq [[kind] actions]
    (case kind
      :action/set-draft (swap! state assoc :draft
                                (get-in event [:glitter/dom-event :glitter/value]))
      ...)))
```

`examples/glitter/todo.clj`'s `:action/set-draft` is the live example.

### The `suppressing` guard

Programmatic setters (`set-entry-text!`, `set-checkbutton-active!`,
`set-scale-value!`, and so on) write only when the new value differs from
the widget's current one, and bracket the GTK call with a `suppressing` set.
That stops the synchronous signal GTK emits during the setter from looping
back into a dispatch (`set_value -> value-changed -> dispatch -> re-render
-> set_value`). `glitter.gtk` connects signals itself, so it checks
`suppressing?` inside the callable.

```clojure
(defn- set-scale-value! [widget value]
  (when (and (some? value) (not= (double value) (g/gtk-range-get-value widget)))
    (swap! suppressing conj widget)
    (g/gtk-range-set-value widget (double value))
    (swap! suppressing disj widget)))
```

Container operations that emit a real signal need the same bracket (see
`:list-box`).

### `signals` and `signal-value`

`signals` maps event keys to GTK signal names: `:on-click "clicked"`,
`:on-change "changed"`, `:on-activate "activate"`, `:on-toggled "toggled"`,
`:on-value-changed "value-changed"`, plus the widget-specific ones below.
Many widgets reuse a registered signal for free, because the lookup is by
event key and signal name, not widget type. `:toggle-button` reuses
`"toggled"`, and `:link-button` inherits `"clicked"` from `GtkButton`.

`signal-value` maps `[tag gtk-signal-name]` to a `(fn [widget] value)` that
re-reads the widget's state after the signal fires. It is keyed by tag
because `:scale` and `:spin-button` both emit `"value-changed"` and read
back through different getters (`gtk_range_get_value` versus
`gtk_spin_button_get_value`). `signal-value-fn` and `register-signal!` take
a `tag` argument. Every widget that carries a value needs its own explicit
entry, even when the body duplicates another widget's: `:password-entry`,
`:search-entry` and `:editable-label` each need their own
`[tag "changed"]` entry, and a missing one dispatches with a `nil` value
and no error.

### Callable shapes

`jolt.ffi/foreign-callable` is a compile-time form: `argtypes` and
`rettype` must be literals at the call site, so a runtime table of shapes
does not compile (a let-bound vector fails with `Don't know how to create
ISeq from: clojure.lang.Symbol`). `set-event-handler` therefore branches on
the signal and has one literal `foreign-callable` per shape. There is no
generic extension point, and `register-signal!` deliberately takes no
`shape` parameter. A new shape is a new literal branch.

| Signals | C shape | Callable |
|---|---|---|
| default | `void(widget, data)` | `[:pointer :pointer] :void` |
| `"state-set"` (`:switch`) | `gboolean(widget, gboolean, data)` | `[:pointer :int :pointer] :int`, returns `0` |
| `"row-selected"`, `"row-activated"`, `"notify::expanded"`, `"notify::position"`, `"notify::selected"`, `"notify::visible-child-name"`, `"child-activated"` | `void(widget, ptr, data)` | `[:pointer :pointer :pointer] :void` |
| `"switch-page"` (`:notebook`) | `void(widget, page, guint, data)` | `[:pointer :pointer :uint :pointer] :void` |
| `"value-changed"` on `:scale-button` | `void(widget, double, data)` | `[:pointer :double :pointer] :void` |

The `:scale-button` branch tests the element's `:tag` as well as the signal
name, because the name alone does not pick the shape. A `GParamSpec*` from
a `notify::` signal is just another `:pointer`, so property-change signals
join the 3-argument branch with no new call site.

A shared `dispatch!` closure holds the suppressing check and the handler
call. Two shapes read the value differently:

- `:switch`: returning `0` (FALSE) lets GTK's default handler also run
  (`gtk_switch_set_state`, which syncs the visual state). `"state-set"`
  needs no argument threading because `gtk_switch_set_active` updates
  `is_active` before it emits, so `gtk_switch_get_active` is already
  current. `"activate"` is not usable for a switch: it fires on keyboard
  activation only.
- `:notebook`'s `"switch-page"` reads `page-num` from the raw signal
  argument and builds the event map inline. The signal's default class
  handler assigns `cur_page` after user handlers run, so a getter re-read
  returns the previous page.

```clojure
(= signal "switch-page")
(jolt.ffi/foreign-callable
 (fn [src-widget _page page-num _data]
   (when-not (w/suppressing? src-widget)
     (handler {:glitter/node el :glitter/gtk-widget src-widget :glitter/value page-num})))
 [:pointer :pointer :uint :pointer] :void :collect-safe)
```

`:scale-button` can use a getter re-read, because its signal is emitted
after the shared adjustment is already updated.

## Children

### `insert-before`

`IRender/insert-before` serves a new child and a reposition of an existing
child. DOM's `insertBefore` handles both. GTK does not:
`gtk_box_insert_child_after` requires an unparented child and no-ops with a
`GTK-CRITICAL` otherwise. Repositioning uses `gtk_box_reorder_child_after`.
`glitter.gtk` branches on whether `child-node` is already in the parent's
`:children`:

```clojure
(insert-before [_ el child-node reference-node]
  (let [cs (:children @el)
        idx (.indexOf cs reference-node)
        prev-sibling (when (pos? idx) (ptr (nth cs (dec idx))))]
    (if (some #(= % child-node) cs)
      (w/reorder-child! (:tag @el) (ptr el) (ptr child-node) prev-sibling)
      (w/insert-child-after! (:tag @el) (ptr el) (ptr child-node) prev-sibling)))
  (swap! el update :children
         (fn [cs]
           (let [without (vec (remove #(= % child-node) cs))
                 idx (.indexOf without reference-node)]
             (into (conj (subvec without 0 idx) child-node) (subvec without idx)))))
  nil)
```

The bookkeeping (remove the child, re-splice before `reference-node`) is
right for both cases, and `prev-sibling` from the pre-removal `:children`
stays a valid anchor. `glitter.test-renderer`'s fake `insert-before` uses
the same formula. `examples/glitter/keyed.clj` reads the live tree back with
`gtk_widget_get_first_child`/`get_next_sibling`, not glitter's `:children`.

### `replace-child!`

`gtk_box_remove` then `gtk_box_append` would move a replaced non-final child
to the end. The box branch captures the previous sibling before removing,
then inserts at that anchor (null sibling means front):

```clojure
(replace-child! [parent-tag parent old-child new-child]
  (case (container-kind parent-tag)
    :box (let [prev (g/gtk-widget-get-prev-sibling old-child)
               prev (when-not (or (nil? prev) (zero? prev)) prev)]
           (g/gtk-box-remove parent old-child)
           (g/gtk-box-insert-child-after parent new-child (or prev ffi/null)))
    ...))
```

`examples/glitter/replace_child.clj` pins it.

### Insert-then-remove on a tag swap

For a same-position, non-keyed tag change, the reconciler calls
`insert-before` for the new node and then `remove-child` for the old one,
not `replace-child!`. A `:box` can hold both briefly. A container with a
fixed number of slots cannot, so the rule below applies to every
slot-based container.

### Child-borne props (`:glitter/structural-props`)

A `GtkGrid` cell position and a `GtkStack` page name belong to the child,
but the parent needs them at attach time, and `:apply` sees only its own
widget. `glitter.gtk/set-attribute` and `remove-attribute` stash the keys in
`structural-child-props` (`:grid-column`, `:grid-row`, `:grid-column-span`,
`:grid-row-span`, `:stack-name`) on the child's `el` atom under
`:glitter/structural-props`. `append-child`, the fresh-insert branch of
`insert-before`, and `replace-child` pass them as a trailing argument to
`glitter.widget`'s `append-child!`/`insert-child-after!`/`replace-child!`,
where `grid-attach!` and `stack-append-child!` consume them. Removal needs
no props, since both `gtk_grid_remove` and `gtk_stack_remove` take the
widget pointer alone.

They are read only at first attach. Changing `:grid-column` or `:stack-name`
on an attached child does not move or rename it, and `reorder-child!` is
meaningless for these containers. See [`limitations.md`](limitations.md).

## Container strategies

| Strategy | Widgets | Notes |
|---|---|---|
| ordered list | `:box` | transient capacity for insert-then-remove |
| single child | `:window`, `:frame`, `:scrolled`, `:revealer`, `:expander`, `:aspect-frame`, `:search-bar`, `:popover`, `:window-handle` | one `case` line each in `append-child!`/`remove-child!`/`replace-child!` |
| named slots | `:center-box` (start/center/end), `:paned` (start/end) | occupancy queried live from GTK getters |
| main plus extras | `:overlay` (main child plus overlays), `:header-bar` and `:action-bar` (title/center slot plus pack-start list) | first hiccup child fills the slot, later children are added |
| wrapped ordered | `:list-box`, `:flow-box` | GTK wraps each child in a row |
| indexed | `:notebook` | pages |
| child-placed | `:grid`, `:stack` | structural props |
| popup | `:menu-button` | its child is a `:popover` |

A new single-child widget missing from any of the three container functions
mounts without error and silently has no child (this once happened to
`:window-handle`). Check all three when adding one.

### Slot containers cannot swap a tag while full

`:center-box`, `:paned`, `:overlay`'s main slot, and `:header-bar`'s and
`:action-bar`'s title/center slot have no transient capacity. When the
reconciler inserts the new child before removing the old one, the insert
either overwrites the old occupant (GTK unparents and finalizes it) or finds
no empty slot and silently no-ops, and then the removal leaves the slot
empty or the bookkeeping stale. The symptoms differ per widget: a corrupted
trailing slot in `:center-box`, a silently failed swap for `:paned`'s first
slot, a stranded widget in the pack-start list for the bars. This cannot be
patched in the `*-insert-after!` functions. The rule for app code: do not
swap a full slot's hiccup tag. Change props instead, or nest a stable
wrapper (`[:box [:label ...]]` or `[:box [:button ...]]`) so the type change
happens inside a `:box`. Details are in [`limitations.md`](limitations.md).
The smokes cover only the safe paths (props-only update, pure removal,
`:paned`'s last-slot swap).

`center-box-*` and `paned-*` functions query occupancy live via the getters
(`ptr-null?` is empty) and find a child's slot by comparing pointers.
`center-box-append-child!` fills the first empty slot in start, center, end
order.

### `:overlay`

The first hiccup child is the main content (`gtk_overlay_set_child`) and
every later child is an overlay (`gtk_overlay_add_overlay`). GTK has no
overlay enumeration, so `overlay-remove-child!` checks whether the child is
the current main child and otherwise assumes an overlay. A replaced
overlay is removed and re-added, which loses z-order because GTK has no
insert-at-position call. Smokes count children with the generic
`first_child`/`next_sibling` walk.

### `:header-bar` and `:action-bar`

Only `pack_start` is used. `gtk_header_bar_pack_end` is a `gtk_box_prepend`
and `gtk_action_bar_pack_end` inserts after `NULL`, so feeding end children
in hiccup order would reverse them, with no public API to fix the order. A
future `pack_end` binding has to solve the reversal, not just call the
function. The pack-start list cannot be reordered, because it lives in a
private `GtkBox`. `gtk_header_bar_remove` and `gtk_action_bar_remove`
handle either role in one call, but `*-replace-child!` must check the role
before removing.

`:show-title-buttons true` makes GTK prepend its own `GtkWindowControls`
into the same pack-start box, shifting glitter's children back by one. That
is harmless to glitter, since it only removes widgets it created.

### `:list-box` and `:flow-box`

Both auto-wrap a child in a `GtkListBoxRow` or `GtkFlowBoxChild`.

- Append and insert take the plain child. `gtk_list_box_remove` needs the
  row, and passing the child prints `Tried to remove non-child` and no-ops,
  although its doc comment suggests otherwise. `list-box-row-of` recovers
  the row via `gtk_widget_get_parent`, and `list-box-remove-child!`,
  `-replace-child!` and `-reorder-child!` all pass the row.
- `gtk_flow_box_remove` accepts either the wrapper or the plain child, so no
  unwrap is needed for removal. `flow-box-child-of` is used only to read a
  sibling's index for positional insert.
- `gtk_list_box_insert` and `gtk_flow_box_insert` clamp an out-of-range or
  `-1` position to append, which `list-box-insert-after!` and
  `flow-box-insert-after!` rely on. `list-box-index-after` re-reads the
  sibling row's index at call time. Neither has a slot limit, so tag swaps
  are safe.
- Reorder: `list-box-reorder-child!` removes the old row and reinserts the
  same child. Removing the row disposes it, and its `dispose` unparents the
  child, which finalizes it when nothing else holds a reference. The child
  pointer would dangle. The fix brackets the remove and reinsert with
  `g_object_ref_sink` and `g_object_unref`, and `flow-box-reorder-child!` has
  the same shape. `examples/glitter/list_box_reorder_smoke.clj` reorders a
  keyed list-box and flow-box and reads the live order back.
- Removing the selected row makes GTK emit `"row-selected"` a second time
  with a NULL row. Unsuppressed, the app's `swap!` triggers a nested
  `core/reconcile` inside the in-flight one and trips a `GTK_IS_WIDGET`
  assertion (render runs inline on the main thread, see
  [`app-loop-and-threading.md`](app-loop-and-threading.md)). The list-box
  remove, replace and reorder functions therefore bracket their
  `gtk_list_box_remove` with `suppressing` on the list-box itself, which is
  the `src-widget` of that signal.
- `list-box-selected-index` re-reads `gtk_list_box_get_selected_row` and
  `gtk_list_box_row_get_index` for both `"row-selected"` and
  `"row-activated"`. A row is selected before it is activated, so the
  getter is current.
- `:on-child-activated` on `:flow-box` dispatches with no value, because
  reading the child would need `GList` traversal through raw pointers.

```clojure
(defn- list-box-remove-child! [parent child]
  (when-let [row (list-box-row-of child)]
    (swap! suppressing conj parent)
    (g/gtk-list-box-remove parent row)
    (swap! suppressing disj parent)))
```

### `:scrolled` and `GtkViewport`

`gtk_scrolled_window_set_child` wraps a child that does not implement
`GtkScrollable` (`:list-box`, `:flow-box`, `:grid`) in a `GtkViewport`.
`glitter.gtk`'s `:children` never sees it, but code that walks the live tree
with raw FFI must descend one extra level:

```clojure
(-> scrolled g/gtk-widget-get-first-child   ; the auto-inserted GtkViewport
    g/gtk-widget-get-first-child)           ; the actual :list-box
```

### `:notebook` and `:stack`

- `gtk_notebook_insert_page` auto-selects the first page when the notebook
  has no current page, so mounting a `:notebook` with initial children
  dispatches `"switch-page"` before any interaction. `:stack` does the same
  through `"notify::visible-child-name"`, because `gtk_stack_add_page`
  selects the first visible child. Dispatch-count baselines after mount
  start at `1`, not `0`.
- `:notebook` page children are the real widgets, and
  `gtk_notebook_page_num` recovers a page index. `tab_label` is always
  `NULL`, so GTK generates numbered tabs, and there is no per-child label
  convention. There is no API to read a page's child back, so smokes check
  only `gtk_notebook_get_current_page`.
- `:stack` pages are named through `:stack-name`. `:apply` runs at `create!`,
  before any child is appended, so an initial `:visible-child-name` lands on
  an empty stack. `set-stack-visible-child-name!` guards on
  `gtk_stack_get_child_by_name` to avoid GTK's warning. Requesting a
  non-default initial page therefore does not take effect at mount. See
  [`limitations.md`](limitations.md).

```clojure
(defn- set-stack-visible-child-name! [widget name]
  (when (and name (g/gtk-stack-get-child-by-name widget name)
             (not= name (g/gtk-stack-get-visible-child-name widget)))
    (swap! suppressing conj widget)
    (g/gtk-stack-set-visible-child-name widget name)
    (swap! suppressing disj widget)))
```

### `:menu-button` and `:popover`

A `:menu-button`'s one child is expected to be a `:popover`, attached with
`gtk_menu_button_set_popover`, which takes real ownership
(`gtk_widget_set_parent`). `:popover` is an ordinary single-child container.
`"activate"` and `"closed"` use the default shape. `gtk_popover_popdown`
emits `"closed"` synchronously, so `:visible` drives popup and popdown
through a suppressing setter:

```clojure
(defn- set-popover-visible! [widget visible?]
  (let [target (->bool visible?)]
    (when (not= target (g/gtk-widget-get-visible widget))
      (swap! suppressing conj widget)
      (if visible? (g/gtk-popover-popup widget) (g/gtk-popover-popdown widget))
      (swap! suppressing disj widget))))
```

This is a controlled-component contract. The menu button opens its popover
on its own, so an app syncs its `:visible` state from `:on-activate` (open)
and `:on-closed` (close). Unlike `GtkButton`, `gtk_widget_activate` on a
`:menu-button` needs no press-animation delay, because it goes through
`gtk_widget_class_set_activate_signal`.

## Widget notes

### Display-only widgets

`:spinner`, `:progress-bar`, `:image`, `:level-bar`, `:picture`,
`:inscription` and `:aspect-frame` have no signal. `:apply` re-applies
whichever props are present on each render.

- `:image`'s `:ctor` picks `:icon-name` or `:file`. A later `:apply` of
  either key replaces the content, since `GtkImage` switches storage type
  with the most recent setter.
- `:level-bar` sets min and max before value, so a caller's range is live
  before the value that must lie inside it. `:mode` is not supported.
- `:picture` uses `gtk_picture_set_filename` with a plain string.
  `:content-fit`, `:can-shrink` and `:alternative-text` are applied.
- `:inscription` has no markup. `:text-overflow` takes `:clip`,
  `:ellipsize-start`, `:ellipsize-middle` or `:ellipsize-end`.
- `:aspect-frame`'s `xalign`, `yalign`, `ratio` and `obey-child` are plain
  settable props with no ordering concern.
- `:revealer` applies `:transition-type` and `:transition-duration` before
  `:reveal-child`, so the first reveal uses the caller's transition.
  `:transition-type` resolves through `glitter.genum`.
- `:search-bar` is a single-child container driven by `:search-mode` and
  `:show-close-button`. `gtk_search_bar_connect_entry` and
  `set_key_capture_widget` are not wired, because there is no hiccup
  convention for passing a widget pointer sideways.

### `:scale`

`gtk_scale_new_with_range` builds its own adjustment. `:ctor` builds a
horizontal scale (the raw `0`, `GTK_ORIENTATION_HORIZONTAL`) because
`GtkOrientation` may not be registered at construction, and `:apply`
corrects it through `->orientation`, as `box-spec` and `separator-spec` do
(see [`architecture.md`](architecture.md)).
`examples/glitter/scale_smoke.clj` pins a real value change, the sync back,
and no spurious second dispatch. `:spin-button` and `:scale-button` are
built the same way.

### `:switch`, `:toggle-button`, `:link-button`

- `:switch` needs the `"state-set"` shape described above. Shipping it
  state-settable but never dispatching was rejected as a trap that looks
  identical to `:checkbutton`.
- `GtkToggleButton` and `GtkCheckButton` are unrelated classes in GTK4, so
  `:toggle-button` has its own `set-toggle-button-active!`, structurally
  identical to `set-checkbutton-active!`.
- `gtk_widget_activate` on a `GtkButton` does not emit `"clicked"`
  synchronously. It schedules it about 250 ms later
  (`ACTIVATE_TIMEOUT`), and only if the widget is realized. `on-activate`
  runs before `gtk_window_present`, so activating a button directly inside
  it is a silent no-op. A smoke that simulates a click defers both the
  activate call and the check, via `future`, `Thread/sleep` and
  `app/on-gui` (`examples/glitter/link_button_smoke.clj`).

### Entries and `GtkEditable`

- `:password-entry`, `:search-entry` and `:editable-label` implement
  `GtkEditable` through a delegate that re-emits the inner `"changed"` on
  the outer object. `set-entry-text!` and `gtk_editable_get_text` work on
  them directly, and `:on-change` needs no new `signals` entry. Each still
  needs its own `[tag "changed"]` `signal-value` entry (above).
- `:search-entry` adds `"search-changed"` (default shape). It is debounced
  by `:search-delay`, except that clearing the text to empty emits at once.
  Smokes use that clear as a deterministic trigger.
- `:editable-label` edits on click through GTK's own gesture.
  `set-editable-label-editing!` wraps `gtk_editable_label_start_editing`
  and `stop_editing`, and always passes commit flag `1`, so leaving edit
  mode programmatically keeps the text.
- `gtk_editable_set_text` is a delete followed by an insert, so a bulk
  replace emits `"changed"` once on an empty buffer (the delete no-ops) and
  twice on a non-empty one. Typing goes through `insert_text` once per
  keystroke. Smokes that simulate a replace start from empty text so the
  dispatch count tests the behaviour and not the doubling. See
  [`limitations.md`](limitations.md).

### `:calendar`

`gtk_calendar_get_date` returns a new `GDateTime` reference that the caller
owns, and `gtk_calendar_select_day` does not take ownership of its
argument. Every read and every constructed value must be released with
`g_date_time_unref`, or each render and dispatch leaks one object:

```clojure
(defn- calendar-date= [widget]
  (let [d (g/gtk-calendar-get-date widget)   ; owns a NEW ref
        result [(g/g-date-time-get-year d) (g/g-date-time-get-month d)
                 (g/g-date-time-get-day-of-month d)]]
    (g/g-date-time-unref d)                  ; release it
    result))

(defn- set-calendar-date! [widget [year month day :as date]]
  (when (and year month day (not= (vec date) (calendar-date= widget)))
    (let [gdt (g/g-date-time-new-local year month day 0 0 0.0)]  ; owns a NEW ref
      (swap! suppressing conj widget)
      (g/gtk-calendar-select-day widget gdt)   ; does NOT take ownership
      (swap! suppressing disj widget)
      (g/g-date-time-unref gdt))))             ; release it
```

The dispatched value is `[year month day]`.

### `:drop-down`

The `GtkStringList` model is built one string at a time with
`gtk_string_list_append`, which avoids the `char**` array that
`gtk_drop_down_new_from_strings` needs. `:ctor` builds an empty model, and
`:apply`'s `:items` swaps in a fresh model with `gtk_drop_down_set_model`
whenever the list changes. `:selected` is an int index through the usual
suppressing setter. Signals are `"notify::selected"` (3-argument shape) and
`"activate"` (default shape).

```clojure
(defn- drop-down-build-model! [items]
  (let [model (g/gtk-string-list-new jolt.ffi/null)]
    (doseq [item items] (g/gtk-string-list-append model item))
    model))
```

### `:grid`

`grid-attach!` reads the child's structural props (defaults `0`, `0`, `1`,
`1`) and calls `gtk_grid_attach`. `:row-spacing` and `:column-spacing` are
ordinary props on the grid. `gtk_grid_get_child_at` is the verification
primitive for smokes, and a span cell is checked at both coordinates it
covers.

```clojure
(defn- grid-attach! [parent child structural-props]
  (let [{:keys [grid-column grid-row grid-column-span grid-row-span]} structural-props]
    (g/gtk-grid-attach parent child
                        (or grid-column 0) (or grid-row 0)
                        (or grid-column-span 1) (or grid-row-span 1))))
```

## Testing with live GTK

Each widget has a smoke under `examples/glitter/*_smoke.clj`, run by
`bb smokes`. The common conventions:

- Simulate a real interaction with a direct FFI call (`gtk_range_set_value`,
  `gtk_switch_set_active`, ...) that bypasses the glitter setter, so the real
  signal fires. Then check that a programmatic `reset!` pushes the value
  back and causes no second dispatch.
- Read results from live GTK (`gtk_widget_get_first_child`/`get_next_sibling`
  walks and widget getters), not from glitter's own bookkeeping.
- Do not assert known-broken behaviour in a permanent smoke. Document it in
  [`limitations.md`](limitations.md) and cover the safe path.
- Keep a smoke's start state from coinciding with a fallback value. Two
  range bugs hid because the smokes chose `0` as the minimum.
