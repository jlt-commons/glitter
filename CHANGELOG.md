# Changelog

Notable changes to glitter, newest first. The format follows
[babashka's changelog](https://github.com/babashka/babashka/blob/master/CHANGELOG.md):
one bullet per user-visible change, written as what a reader would notice
rather than what a commit did.

Nothing has been released yet, so there is a single `Unreleased` section. It
covers the project from its first commit (2026-08-05) to now, grouped by
theme rather than dated per change — the whole thing is one continuous first
pass.

## Unreleased

### Rendering model

- **A Replicant-style renderer for GTK4 on [Jolt](https://github.com/jolt-lang/jolt).**
  One application-state atom, a pure `state -> hiccup` view function, top-down
  re-render on every state change, and event handlers expressed as *data*
  (`{:on {:click [[:action/inc]]}}`) dispatched through one global fn. This is
  deliberately a different model from [glimmer](https://github.com/jolt-lang/glimmer),
  which applies Reagent's ratom/dependency-tracking approach to the same
  toolkit.
- **`glitter.core` is `replicant.core` ported essentially unchanged** — the
  diff algorithm is upstream's. It talks to the live tree only through the
  `IRender`/`IMemory` protocols in `glitter.protocols`, so it has no idea GTK
  exists. `NOTICE` records every file's provenance and every deliberate
  deviation.
- **Two backends behind one reconciler**: `glitter.gtk` for real GTK4 widgets,
  and `glitter.test-renderer`, an in-memory fake that makes the reconciler
  testable with no display. The fake ships in `src/`, not `test/`, so
  applications built on glitter can reuse it in their own suites.
- **Handler data can change between renders without tearing down the widget.**
  Because handlers are data rather than closures, the same handler *position*
  can carry different action data across renders. `glitter.gtk` tracks each
  GTK signal connection's handler id and retained foreign-callable per element
  per event, so a handler-data-only change disconnects and reconnects cleanly
  instead of leaking either one.

### Action dispatch

- **`glitter.nexus` ports [nexus](https://github.com/cjohansen/nexus)'s
  data-driven action/effect/placeholder engine**, replacing the hand-written
  `case` form every demo previously wrote per action kind. Effects are the only
  functions allowed to mutate; placeholders resolve event-derived values into
  action data before it is used; action expansions are pure
  `(state & args) -> more-actions` functions for interactions that must read
  state before deciding what happens.
- `glitter.nexus.registry` is a registry-atom convenience API over it, and
  `glitter.nexus.action-log` accumulates dispatched actions for inspection.

### Widgets

- **Forty-three widget tags** map to GTK4 widgets (plus `:hbox`/`:vbox`
  sugar for an oriented `:box`), from `:window`/`:box`/
  `:button`/`:label`/`:entry` through `:list-box`, `:notebook`, `:stack`,
  `:drop-down`, `:grid`, `:header-bar`, `:menu-button`/`:popover` and more.
  The full set is listed in `README.md`; how each one was added — including the
  ones that needed real architectural work — is recorded in
  `docs/guide/gtk-widget-layer.md`.
- **Extensible from outside the library** via `glitter.widget/register-widget!`
  and `register-signal!`. Signals with the standard `void(widget, user_data)`
  shape work for free. Non-standard shapes (GTK's 3-arg `"state-set"`, the
  4-arg `"switch-page"`) need a hand-written literal branch, because
  `jolt.ffi/foreign-callable` requires literal argtypes at the call site and so
  signal shapes cannot be registered as data.
- **`:class` reaches real GTK CSS classes** (`gtk_widget_add/remove_css_class`).
  `:style` is still accepted and diffed but is inert — GTK4 has no
  DOM-`style`-attribute equivalent to wire it to.
- A round-11 audit of the constructor/prop-applier split found and fixed four
  previously shipped bugs that no existing smoke exercised: `:checkbutton`'s
  `:label` was read but never applied, `:scale-button`'s `:min`/`:max`/`:step`
  had the same gap, and `:scale`/`:spin-button`'s `:min`/`:max` clobbered each
  other across single-key re-renders.

### Examples

- **Six interactive demos**: a counter, a task board, and four from
  [7GUIs](https://eugenkiss.github.io/7guis/) — CRUD, Flight Booker,
  Temperature Converter and Timer. Between them they cover derived state,
  list-box selection, cross-field constraints, parse-on-input, and a demo whose
  state advances on its own via a background tick.
- **Flight Booker reads today from GLib, not `(t/today)`.** `jolt-lang/time`
  hardcodes `ZoneId/systemDefault` to UTC, so `(t/today)` answers the UTC date
  on every machine and ignores `TZ` even when it is explicitly set — the demo
  defaulted its departure field to *yesterday* for the first 10 hours of every
  AEST day. It now asks GLib (`g_date_time_new_now_local`, a new binding) and
  converts back to a tick date, so only the source of "today" changed. Needs
  jolt `v0.7.23-10-gc50a3717` or newer: before
  [jolt-lang/jolt#712](https://github.com/jolt-lang/jolt/pull/712) jolt's own
  zone probe left `TZ=UTC` set process-globally and GLib answered UTC too.
- **Twenty-six automated live-GTK smokes** (`bb smokes`) that mount real
  windows and assert against real GTK state. These exist because GTK4 is a
  live, stateful system with a blocking main loop: the keyed reorder,
  `replace-child!`'s position and the cross-thread render were each "obviously
  correct" on paper and wrong when actually run.

### Threading

- **`glitter.app` bootstraps `GtkApplication`** and provides `on-gui`/
  `post-to-gui` for touching widgets from a non-GTK-main thread (an nREPL
  eval, a `future`). `on-gui` runs *inline* when already on the GTK main
  thread rather than always marshalling through `g_idle_add`, so a click
  handler or a same-thread `swap!` gets a synchronous read-back.

### Tooling

- `bb info` prints a grouped cheat-sheet of every task. `bb test` runs the unit
  suite headlessly; `bb smokes` runs all twenty-six live-GTK smokes in sequence;
  `bb verify` is the pre-commit gate. `bb hooks:install` writes a fast (~2s)
  local pre-commit hook.
- **`.clj-kondo/hooks/jolt_ffi.clj` rewrites `jolt.ffi/defcfn` into an
  equivalent `defn`** so clj-kondo and clojure-lsp can see through the FFI
  macro. Without it every `gtk-*`/`g-*` binding reports as unresolved.
- **Never gate CI on `jolt <task>`**: a `deps.edn` `:tasks` entry does not
  propagate its child process's exit status (verified against jolt v0.6.3), so
  `jolt test` prints failures and still exits 0. Use `jolt -M:<alias>` or a
  `bb.edn` task.
- The codebase is uniformly `clojure-lsp`-formatted, including the ten files
  ported verbatim from Replicant. An earlier decision exempted those to
  preserve upstream diffability; it was reversed in favour of one project-wide
  style.

### Known gaps

`docs/guide/limitations.md` records every v1 gap with the reasoning behind
leaving it unfixed. The ones most likely to surprise: removing an attribute
entirely is a no-op (GTK has no generic "unset this property"); `mount!` is
one-way with no unmount; `IMemory` never releases; and several fixed-slot
containers (`:center-box`, `:paned`, `:overlay`, `:header-bar`) cannot safely
swap a child's hiccup *tag* while their slots are occupied.
