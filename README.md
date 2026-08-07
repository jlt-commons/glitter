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

`jolt counter` is the full interactive demo. `jolt todo` is a larger
interactive task-board demo — derived counts, an entry with placeholder
text, checkbutton toggles, list rendering (ported from
[glimmer's `examples/glimmer/todo.clj`](https://github.com/jolt-lang/glimmer/blob/main/examples/glimmer/todo.clj),
contrasting glimmer's component-local ratom closures with glitter's one
state atom + data-driven action dispatch). `jolt test` runs the unit suite.
The rest are automated live-GTK smokes, each of which exits non-zero on
failure:

| task | pins |
|---|---|
| `jolt smoke` | mount a tree and run the loop without an exception escaping |
| `jolt keyed` | keyed reorder lands in the right GTK order |
| `jolt replace-child` | a replaced child stays at its position, not the end |
| `jolt aliased` | aliases expand through the real renderer, on mount and update |
| `jolt main-thread-smoke` | an off-main-thread `swap!` renders ON the GTK main thread |
| `jolt scale-smoke` | `:scale`'s `value-changed` signal delivers the right double, no spurious dispatch on programmatic sync |
| `jolt class-smoke` | `:class` reaches real GTK CSS classes — add, coexist, and remove on a re-render diff |
| `jolt leaf-widgets-smoke` | `:spinner`/`:progress-bar`/`:image` construction + re-render land on real GTK state |
| `jolt toggle-level-smoke` | `:toggle-button`'s `toggled` signal delivers correctly (reused from `:checkbutton`), `:level-bar` construction + re-render |
| `jolt link-button-smoke` | `:link-button` reuses `:on-click`; a real click (via `gtk_widget_activate`) reaches dispatch |
| `jolt switch-smoke` | `:switch`'s `state-set` signal (3-arg, non-void return — a generalized callable shape) delivers correctly, no spurious dispatch |
| `jolt revealer-center-box-smoke` | `:revealer`'s props-driven reveal/transition; `:center-box`'s 3 named slots survive an append, a props-only update, and a removal |
| `jolt spin-button-list-box-smoke` | `:spin-button`'s `value-changed` reads back through the right getter despite sharing `:scale`'s signal name; `:list-box`'s `row-selected` (a third callable shape) delivers the selected row's index, and a tag-swapped/removed row doesn't corrupt its siblings or spuriously dispatch |
| `jolt password-search-entry-smoke` | `:password-entry`/`:search-entry` reuse `:entry`'s `GtkEditable`-delegate `changed` signal; `:search-entry`'s own `search-changed` fires synchronously on a text clear, no spurious dispatch on programmatic sync |
| `jolt expander-paned-smoke` | `:expander`'s `notify::expanded` and `:paned`'s `notify::position` deliver correctly — free reuse of the 3-arg-void callable shape `:list-box` generalized — and `:paned`'s 2 named slots survive a safe tag swap |
| `jolt aspect-frame-calendar-smoke` | `:aspect-frame`'s single-child container reuse; `:calendar`'s `GDateTime`-refcounted date round-trips through a real interaction and a programmatic sync with no leaks or spurious dispatch |
| `jolt overlay-flow-box-smoke` | `:overlay`'s 1-main+N-overlay shape survives a real removal, verified via a generic widget-tree walk since GTK exposes no overlay-enumeration API; `:flow-box`'s tag-swapped middle child lands correctly without corrupting siblings |
| `jolt picture-editable-label-smoke` | `:picture`'s display-only `GdkPaintable` props land on real GTK state; `:editable-label` (a third `GtkEditable`-delegate reuse) dispatches its typed value, no spurious dispatch on a programmatic `:editing` toggle |
| `jolt notebook-scale-button-smoke` | `:notebook`'s `switch-page` (a sixth callable shape, reading `page-num` from its own raw signal argument, not a stale getter) delivers correctly, including its real mount-time auto-select-page-0 dispatch; `:scale-button`'s `value-changed` (sharing `:scale`'s/`:spin-button`'s signal name but needing tag-aware dispatch) delivers the right double |

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
bb todo      # interactive task-board demo
bb smokes    # every live-GTK smoke in sequence, CI-safe (stops at first failure)
```

`bb.edn`'s tasks all shell out to the `-M:<alias>` form directly (never the
deps.edn `jolt <task>` shorthand), so `bb test` and `bb smokes` exit non-zero
on failure and are safe to use as a CI gate.

### Quality tooling

```
bb lint / lint:strict / lint:errors      # clj-kondo: report | propagate exit | errors-only
bb lsp:format / lsp:format-check          # reformat, or check formatting (dry run)
bb lsp:clean-ns / lsp:clean-ns-check      # organize ns forms, or check (dry run)
bb lsp:diagnostics / lsp:check / lsp:fix  # diagnostics | all dry-run checks | auto-fix
bb check:positional-args / :strict        # find fns with 3+ positional args
bb verify                                 # pre-commit gate: lint (report) + test (must pass)
bb hooks:install / :install:full / :uninstall  # git pre-commit hook: fast | +tests | remove
```

`clj-kondo`/`clojure-lsp` both need `.clj-kondo/hooks/jolt_ffi.clj` (an
`analyze-call` hook rewriting `jolt.ffi/defcfn` into an equivalent `defn`,
adapted from [b12n-rljlt](https://github.com/burinc/b12n-rljlt)) to see
through the FFI-binding macro — without it every `gtk-*`/`g-*` name in
`glitter.ffi` and every call site through the `g/` alias reports as
unresolved. `.clj-kondo/config.edn`'s `:output {:exclude-files [...]}`
excludes the files ported verbatim from Replicant (they keep intentional
`#?(:clj :cljs)` reader conditionals in a `.clj` extension — a permanent,
harmless false positive for that specific porting strategy, not a real
defect), so `bb lint` and friends run unscoped over `src test examples`.
`bb hooks:install` sets up a fast pre-commit hook (lint errors + format +
ns cleanliness) that gates every commit on staying `bb lsp:format-check`-
clean — the whole codebase is formatted uniformly now, including the
files ported from Replicant (an earlier decision to exempt those from
clojure-lsp's default style, to preserve upstream diffability, was later
reversed in favor of one uniform style project-wide) — see
`docs/guide/testing-and-tasks.md` for the full rationale on both.

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

## Documentation

- **[`docs/guide/index.md`](docs/guide/index.md)** — the full guide: the
  reconcile → `IRender`/`IMemory` architecture, the GTK4-specific widget/
  signal-lifecycle mechanics, cross-thread marshalling, the porting/
  attribution ledger, testing, and every known v1 limitation in depth.
  Mirrored to [`b12n-wikis/glitter`](https://github.com/burinc/b12n-wikis/tree/main/glitter)
  for browsing outside a checkout.
- **[`AGENTS.md`](AGENTS.md)** — canonical context for coding agents working
  in this repo (architecture summary, build/run commands, conventions and
  gotchas not to regress). `CLAUDE.md` imports it for Claude Code.
- Design spec / implementation plan: not included in this repo — they live
  at `~/dev/b12n-sp-docs/glitter/{specs,plans}/` (the centralized
  superpowers planning store) for anyone with access to that store.

## Status

Early. Widget set: window/box/button/label/entry/checkbutton/separator/
frame/scrolled (forked from glimmer) plus `:scale`/`:spinner`/
`:progress-bar`/`:image`/`:toggle-button`/`:level-bar`/`:link-button`/
`:switch`/`:revealer`/`:center-box`/`:spin-button`/`:list-box`/
`:password-entry`/`:search-entry`/`:expander`/`:paned`/`:aspect-frame`/
`:calendar`/`:overlay`/`:flow-box`/`:picture`/`:editable-label`/
`:notebook`/`:scale-button`
(first-party, added directly to glitter; see
`docs/guide/gtk-widget-layer.md`) — `:spinner`/`:progress-bar`/`:image`/
`:level-bar`/`:revealer` are display-only props with no signal to wire
(`:revealer` is also a single-child container, same strategy as
`:frame`/`:scrolled`); `:toggle-button`/`:link-button` reuse
`:checkbutton`'s `:on-toggled` and `:button`'s `:on-click` signal entries
verbatim; `:switch`/`:list-box` each needed
`glitter.gtk/set-event-handler` itself generalized beyond the uniform
2-arg-void callable shape (see below); `:center-box` is a genuinely
different container strategy (3 fixed named slots, not an ordered list);
`:spin-button` needed `glitter.widget/signal-value` re-keyed by
`[tag signal]` since it shares `:scale`'s exact GTK signal name. Hiccup
`:class` reaches real GTK CSS classes (`gtk_widget_add/remove_css_class`
— GTK4's built-in classes like `"flat"`/`"suggested-action"`/
`"destructive-action"`/`"pill"` work with zero app-provided CSS); `:style`
still doesn't — GTK4 has no DOM-`style`-attribute equivalent, only
class-based styling, so an inline `:style` prop has no direct GTK
counterpart to wire to. No animated mount/unmount transitions yet — see
`NOTICE.md`'s file-by-file notes for exactly what's ported vs. new.

**Non-standard GTK signals.** Almost every GTK signal glitter connects is
`void(widget, user_data)` — `glitter.gtk/set-event-handler` builds that
shape by default. `GtkSwitch`'s real interaction signal, `"state-set"`, is
`gboolean(GtkSwitch*, gboolean, gpointer)` — 3 args, non-void return.
`GtkListBox`'s `"row-selected"`/`"row-activated"` are
`void(GtkListBox*, GtkListBoxRow*, gpointer)` — 3 args, VOID return, a
THIRD distinct shape. Both confirmed by reading the relevant `g_signal_new`
call directly, not assumed. `jolt.ffi/foreign-callable`'s
`argtypes`/`rettype` must be compile-time literals (verified live that a
let-bound local, even holding the exact same value, throws a compile
error), so `set-event-handler` branches explicitly on the GTK signal name
and uses a separate, literal `foreign-callable` call per non-standard
shape — there's no data-driven way to add one; it needs its own literal
branch. See `docs/guide/gtk-widget-layer.md` for the full story, including
the design that was tried first and didn't work.

**`signal-value` keyed by `[tag signal]`, not bare signal name.**
`:spin-button` (`GtkSpinButton`) emits `"value-changed"` — the exact same
GTK signal name `:scale` (`GtkScale`/`GtkRange`) already uses — but needs
a different getter to read the value back. A table keyed by bare signal
name would have one widget's registration silently clobber the other's;
found live, before it ever shipped and broke `:scale`. See
`docs/guide/gtk-widget-layer.md`.

**`:center-box`'s 3 fixed named slots vs. `glitter.gtk`'s generic child
bookkeeping.** Known v1 gap, found live: do not swap a slot's hiccup tag
(e.g. `:label` -> `:button`) while all 3 slots are occupied — the
reconciler handles a same-position tag mismatch as "insert new, then
remove old," and unlike `:box`'s arbitrary-capacity list, GtkCenterBox has
no room for a transient 4th occupant, so the mismatch between glitter's
generic bookkeeping and GtkCenterBox's real capacity can corrupt an
unrelated third slot. `:list-box` does not share this problem. This same
investigation also caught and fixed a real bug: `insert-child-after!`/
`reorder-child!` were originally left as no-ops for any container besides
`:box` — wrong, not just incomplete, since even a plain non-keyed tag
swap goes through them. Both are now properly implemented for
`:list-box`; `:center-box` gets `insert-child-after!` but not
`reorder-child!` (a genuinely structural no-op — three named slots have
no meaningful "reorder"). See `docs/guide/gtk-widget-layer.md`.

**`:password-entry`/`:search-entry` reuse `:entry`'s `GtkEditable`
signal for free — but `signal-value` still needed new entries.** Both
implement `GtkEditable` via a delegate (confirmed against
`gtk/gtkpasswordentry.c`/`gtk/gtksearchentry.c`), so `"changed"` and
`gtk_editable_set/get_text` work on either pointer with no new plumbing.
`signal-value`'s `[tag signal]` keying (above) meant `:entry`'s own
registration didn't automatically cover them, though — the first version
of this round shipped without `[:password-entry "changed"]`, silently
losing the dispatched value. `:search-entry` also gets a genuinely new
`"search-changed"` signal, debounced except when clearing to empty text
(fires synchronously — confirmed by reading `gtk_search_entry_changed`'s
C body).

**`:expander`/`:paned` reuse the 3-arg-void shape for free; `:paned`
inherits `:center-box`'s gap with a different failure shape.** Neither
widget has a dedicated interaction signal — real interactivity is
`"notify::expanded"`/`"notify::position"`, GObject property-change
signals that share the exact `void(GObject*, GParamSpec*, gpointer)`
shape `:list-box` already generalized `set-event-handler` for, so no new
literal call site was needed. `:paned` is a new 2-named-slot container
(a simpler `:center-box`), inheriting the same structural v1 gap but
verified to fail DIFFERENTLY: swapping the last slot's tag while both
are full lands correctly (no trailing slot to corrupt into); swapping
the first slot's tag silently drops the new widget and leaves that slot
empty. See `docs/guide/gtk-widget-layer.md` for both traces.

**`:aspect-frame` (quick win) and `:calendar` (a genuinely new value
type).** `:aspect-frame` reuses `:frame`'s single-child container
strategy verbatim — only its own xalign/yalign/ratio/obey-child
construction params are new, and being plain floats/bool they carry no
GType-registration risk. `:calendar`'s `"day-selected"` is the standard
2-arg-void shape, but `gtk_calendar_get_date` returns a `GDateTime*` —
this project's first refcounted GLib value type. Confirmed by reading
`gtk_calendar_get_date`'s C body directly that it calls `g_date_time_ref`
internally, so every read/construct site must `g_date_time_unref` what
it refs, or every render/dispatch leaks one `GDateTime` object.

**`:overlay` — a third, genuinely different container shape — and
`:flow-box`, a `:list-box` sibling with a verified DIFFERENCE, not an
assumed similarity.** `GtkOverlay` has exactly one queryable slot (the
main content) plus an unbounded set of floating overlay children with NO
enumeration getter at all — unlike `:center-box`/`:paned`, overlay
occupancy can't be queried live; the first hiccup child becomes main,
every later child becomes an overlay. It inherits the same class of
structural gap as `:center-box`/`:paned` for its one named slot.
`:flow-box` auto-wraps children the identical way `:list-box` does, but
`gtk_flow_box_remove` does NOT share `gtk_list_box_remove`'s gotcha —
its C body explicitly accepts either the wrapped child or the plain
widget, confirmed by reading it directly rather than assuming the
lesson transfers between sibling widgets. See
`docs/guide/gtk-widget-layer.md` for both write-ups.

**`:picture` (quick win) and `:editable-label` (a third `GtkEditable`
delegate, and a general finding).** `:picture` is display-only — no
`g_signal_new` in `gtk/gtkpicture.c` — a modernized `:image` with real
aspect-ratio-aware scaling via `:content-fit`. `:editable-label`
implements `GtkEditable` via a delegate, like `:password-entry`/
`:search-entry` before it, reusing `"changed"` for free — but repeated
the exact `[:password-entry "changed"]` near-miss from two rounds ago:
a comment said its own `signal-value` entry would be "applied
proactively this time," and it was still missed on the first pass,
caught only by live probe testing. Investigating it surfaced a real,
general `GtkEditable` finding, not specific to this widget:
`gtk_editable_set_text` is delete-then-insert, two separate mutations —
replacing already-empty text fires `"changed"` once, replacing
non-empty text fires it twice. Affects `:entry`/`:password-entry`/
`:search-entry`/`:editable-label` alike.

**`:notebook` — a sixth callable shape, a real mount-time dispatch, and
`:scale-button` — the first tag-aware signal dispatch.** `:notebook`'s
`"switch-page"` is `void(GtkNotebook*, GtkWidget*, guint, gpointer)` — 4
args — and the FIRST signal here that can't reuse the shared
re-read-via-getter path: the getter's own update happens in the
signal's default class handler, which runs AFTER glitter's, so the
value-fn reads `page-num` straight from its own raw signal argument
instead — verified both by reading the C source and by an empirical
probe comparing the raw value against a same-tick (stale) getter read.
Separately, GTK auto-selects the first page added to an empty
notebook — confirmed by reading `gtk_notebook_insert_page`'s C body —
so mounting a `:notebook` with initial children genuinely dispatches a
`"switch-page"` action as a mount-time side effect, before any real
interaction. `:scale-button` is a THIRD widget sharing `:scale`'s/
`:spin-button`'s exact `"value-changed"` signal name, with a genuinely
different C shape (`void(GtkScaleButton*, double, gpointer)`) — the
first case where `set-event-handler` has to check the widget's own tag,
not just the signal name, to pick the right callable. Verified SAFE to
still re-read via the usual getter, unlike `:notebook`'s signal. See
`docs/guide/gtk-widget-layer.md` for both write-ups.

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
- **`:center-box` cannot safely swap a slot's hiccup tag while all 3 slots
  are occupied.** Change props instead of tags, or nest a stable wrapper
  tag one level down so the type change happens where `:box`-shaped
  reconciliation already handles it correctly.
- **`:paned` cannot safely swap a slot's hiccup tag while both slots are
  occupied either** — same remedy, but the failure shape differs:
  swapping the last slot lands correctly, swapping the first silently
  drops the new widget and leaves that slot empty.
- **`:overlay` cannot safely swap the main child's hiccup tag once
  occupied either** — same class of gap, and GTK exposes no way to
  enumerate overlay children at all, so this is unverifiable by a live
  smoke beyond the append/remove/replace path that's already covered.
- **`:flow-box`'s `:on-child-activated` carries no `:glitter/value`.**
  Reading back "which child" would need `GList` marshalling via
  `gtk_flow_box_get_selected_children` — a new FFI complexity class
  deliberately deferred.
- **`:notebook` always passes a `NULL` tab label.** GTK auto-generates
  a default numbered tab; there's no v1 hiccup convention for a custom
  tab label per page yet.
- **Mounting a `:notebook` with pre-populated pages dispatches once,
  before any real interaction.** GTK auto-selects the first page added
  to an empty notebook, firing `"switch-page"` as a side effect of
  construction — real GTK behavior, but a dispatch-count baseline of 0
  right after mount is wrong for `:notebook`.
- **Bulk `GtkEditable` text replacement can fire `"changed"` once or
  twice**, depending on whether the buffer started empty — affects
  `:entry`/`:password-entry`/`:search-entry`/`:editable-label` alike;
  only matters for a programmatic bulk replace via
  `gtk_editable_set_text`, not normal typing.
