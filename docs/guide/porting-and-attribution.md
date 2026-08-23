# Porting and attribution

glitter's source falls into four buckets. `NOTICE` (repo root) is the
authoritative, maintained ledger; this page explains what the buckets mean
and summarizes the deviations; if the two ever disagree, `NOTICE` wins.

## Bucket 1: ported from Replicant

Mechanical rename port (`replicant.* → glitter.*`, including the
`:replicant/*` keyword namespace) via `sed -E 's/\breplicant\b/glitter/g'`,
from [Replicant](https://github.com/cjohansen/replicant) commit
`379bb3c1ad4d5d3002c57e67ab647d12f3c2d322` (2026-07-25), Copyright
2023-2025 Christian Johansen, MIT License.

Ported files: `glitter.protocols`, `glitter.hiccup`, `glitter.hiccup-headers`,
`glitter.console-logger`, `glitter.errors`, `glitter.assert`, `glitter.vdom`,
`glitter.asserts`, `glitter.core`, `glitter.alias`.

`glitter.core` (the diff/reconcile algorithm) is the largest and most
important of these, and carries **three deliberate deviations** from a pure
mechanical port:

1. **`build-event-map`'s `:clj` branch reads `(:glitter/node e)`** instead of
   hardcoding `nil`. Replicant's `#?(:cljs (.-target e) :clj nil)` reflects
   that its `:clj` branch was never exercised by a live DOM (Replicant
   targets ClojureScript/the browser), but glitter's events *are* live, so
   the acting element has to travel through somehow. `glitter.gtk` supplies
   it as `:glitter/node` on the event map it hands to the dispatched
   handler.
2. **`set-dispatch!` is new code, not a port.** It doesn't exist in
   `replicant.core` at all; only `*dispatch*`, the dynamic var it reads.
   The function lives in `replicant.dom` (browser-specific, never ported;
   see Bucket 3), so glitter added its own one-liner mirroring that
   original: `(defn set-dispatch! [f] (alter-var-root #'*dispatch*
   (constantly f)))`.
3. **`update-attr`/`set-attributes` route on `(some? v)` instead of
   truthiness.** DOM attributes have a natural "absent" state that
   coincides with `false` for most practical purposes (an `if-let`-style
   truthiness check works because DOM callers rarely need to distinguish
   "explicitly false" from "not set"). GTK properties don't share that: a
   checkbutton's `:active false` or a widget's `:sensitive false` is a real,
   meaningful boolean value that must reach the widget, not get silently
   treated as "attribute absent, remove it." Verified live against real GTK
   state during the project's final whole-branch review.

## Bucket 2: forked from glimmer

[glimmer](https://github.com/jolt-lang/glimmer) is authored by Dmitri
Sotnikov (`Yogthos`) under the `jolt-lang` organization: a different
author from glitter's, verified from git history on `upstream/main` (29
commits, 25 as `Yogthos` and 4 as `Dmitri Sotnikov`, zero by anyone else).
Upstream ships no LICENSE file, so no grant has been made; this bucket
records provenance, not a claimed permission. See `NOTICE`'s `## glimmer`
section for the authoritative statement and its full reasoning:

- `glitter.ffi`: forked from `glimmer.ffi`, plus **224 new bindings**
  added across the widget rounds. `NOTICE`'s `src/glitter/ffi.clj` entry
  is the authoritative, exhaustive list; it is deliberately not repeated
  here, because this page's own rule is that `NOTICE` wins when the two
  disagree, and a hand-maintained second copy is exactly how they come
  to disagree.

  What the bindings are for, in roughly the order they arrived:

  - **The original port**: signal disconnect, box insert-after, label
    read-back, prev-sibling lookup.
  - **`:scale`, `:class`**: `GtkRange`/`GtkScale` value and range,
    CSS-class add/remove/query.
  - **Display-only widgets**: `:spinner`, `:progress-bar`, `:image`,
    later `:picture` and `:inscription`.
  - **Interaction widgets**: `:toggle-button`, `:level-bar`,
    `:link-button`, `:switch`.
  - **Container strategies**: `:revealer`, `:center-box`, `:list-box`,
    `:paned`, `:overlay`, `:flow-box`, `:header-bar`, `:action-bar`,
    `:grid`, `:stack`.
  - **`GtkEditable` family**: `:password-entry`, `:search-entry`,
    `:editable-label`.
  - **Value / selection widgets**: `:spin-button`, `:scale-button`,
    `:notebook`, `:drop-down`, `:calendar` (including refcounted
    `GDateTime`).
  - **Popup surfaces**: `:menu-button`, `:popover`, `:window-handle`.
  - **The ctor/apply audit**: `GtkAdjustment` accessors plus
    `gtk-checkbutton-set-label`, added to fix four previously-shipped
    bugs.

  One binding arrived outside any widget round: `gtk-widget-get-sensitive`,
  added while verifying `examples/glitter/crud.clj`. Every prior use of
  `:sensitive` only ever *set* it; that was the first live check of its
  own read-back.

- `glitter.widget`: forked from `glimmer.widget`. What was added, and
  the four places its behaviour deliberately diverges from glimmer's.

  **Added**, alongside the shared helpers `insert-child-after!`,
  `signal-name`, `signal-value-fn`, `suppressing?`:

  - [`:scale`](gtk-widget-layer.md): the first-party demonstration of
    the value-bearing custom-signal path.
  - `:spinner`, `:progress-bar`, `:image`, `:level-bar`, display-only:
    no signal wiring, driven entirely by re-applied props.
  - `:toggle-button`, `:link-button`: both reuse an existing `signals`
    entry verbatim.
  - `:switch`: the widget that forced `glitter.gtk/set-event-handler`
    itself to generalize.
  - `:revealer`: display-only, and a free single-child-container reuse.
  - `:center-box`, a genuinely new container strategy: three fixed
    named slots, not an ordered list.
  - `:spin-button`: forced `signal-value` to be keyed by tag as well as
    signal name.
  - `:list-box`: a third callable shape, plus two more real bugs found
    and fixed.

  **Four behavioural deviations from glimmer:**

  1. `replace-child!`'s `:box` branch captures the old child's previous
     sibling via `gtk_widget_get_prev_sibling` and re-inserts with
     `gtk_box_insert_child_after`, where glimmer used `gtk_box_remove` +
     `gtk_box_append`. `append` always lands at the *end* of the box,
     silently relocating any non-final child.
  2. `signal-value` is keyed by `[tag gtk-signal-name]`, not by bare
     signal name: `:spin-button` and `:scale` emit the identical
     `"value-changed"` signal but need different getters.
  3. `insert-child-after!`/`reorder-child!` are no longer `:box`-only
     no-ops. `:list-box` needed both genuinely implemented; `:center-box`
     gets `insert-child-after!`, but its `reorder-child!` stays a
     structural no-op (three fixed slots have no ordering to change).
  4. `list-box-remove-child!`/`list-box-replace-child!`/
     `list-box-reorder-child!` suppress on the list-box widget around
     `gtk_list_box_remove`; removing the currently-selected row fires a
     real, synchronous `"row-selected(NULL)"` signal that would
     otherwise reach app dispatch.

  Round 7 adds `password-entry-spec`/`:password-entry` +
  `search-entry-spec`/`:search-entry` (both reuse `:entry`'s
  `GtkEditable`-delegate `"changed"` signal *name*, but each still needed
  its own `signal-value` entry under `[tag "changed"]`; reusing
  `:entry`'s registration doesn't work once `signal-value` is keyed by
  tag; `:search-entry` also gets its own new `:on-search-changed`; see
  [`gtk-widget-layer.md`](gtk-widget-layer.md#password-entrysearch-entry--free-gtkeditable-reuse-and-a-signal-value-miss)),
  `set-expander-expanded!`/`expander-spec`/`:expander` (single-child
  container, no dedicated signal; `:on-expanded` watches
  `"notify::expanded"` instead), and `set-paned-position!`/`paned-spec`/
  `:paned` + `paned-append-child!`/`paned-slot-setter`/
  `paned-remove-child!`/`paned-replace-child!`/`paned-insert-after!` (a
  second new named-slot container strategy; 2 slots this time;
  `:on-position-changed` watches `"notify::position"`; both `:expander`'s
  and `:paned`'s `notify::*` signals reuse `:list-box`'s generalized
  3-arg-void `set-event-handler` shape for free, and `:paned` inherits
  `:center-box`'s structural v1 gap with a verified-DIFFERENT failure
  shape; see
  [`gtk-widget-layer.md`](gtk-widget-layer.md#expanderpaned--free-signal-reuse-and-a-second-structural-gap)).

  Round 8 adds `aspect-frame-spec`/`:aspect-frame` (single-child
  container, same strategy as `:frame`/`:scrolled`/`:revealer`/
  `:expander`; quick win, see
  [`gtk-widget-layer.md`](gtk-widget-layer.md#aspect-framecalendar--a-quick-win-and-a-genuinely-new-value-type)),
  `calendar-date=`/`set-calendar-date!`/`calendar-spec`/`:calendar` + the
  new `:on-day-selected` signal (this project's first `GDateTime`-
  refcounted value type; same anchor as `:aspect-frame` above),
  `overlay-spec`/`:overlay` + `overlay-append-child!`/
  `overlay-remove-child!`/`overlay-replace-child!`/`overlay-insert-after!`
  (a THIRD new container strategy; one queryable main slot plus an
  unbounded, unenumerable overlay set; see
  [`gtk-widget-layer.md`](gtk-widget-layer.md#overlayflow-box--a-third-container-shape-and-a-verified-difference-not-an-assumption)),
  and `flow-box-spec`/`:flow-box` + `flow-box-child-of`/
  `flow-box-index-after`/`flow-box-insert-after!`/`flow-box-replace-child!`/
  `flow-box-reorder-child!` + the new `:on-child-activated` signal (a
  `:list-box` sibling verified to NOT share `gtk_list_box_remove`'s
  gotcha; same anchor as `:overlay` above).

  Round 9 adds `editable-label-spec`/`:editable-label` +
  `set-editable-label-editing!` (a THIRD `GtkEditable`-delegate reuse,
  after `:password-entry`/`:search-entry`; repeated the exact
  `signal-value`-miss near-miss from round 7 despite a comment saying
  it would be avoided, caught only by live probe testing; investigating
  it surfaced a general `GtkEditable` finding, not specific to this
  widget; see
  [`gtk-widget-layer.md`](gtk-widget-layer.md#pictureeditable-label--a-quick-win-a-third-gtkeditable-delegate-and-a-general-gtkeditable-finding)),
  `picture-spec`/`:picture` (display-only, no signal; same anchor as
  `:editable-label` above), `notebook-spec`/`:notebook` +
  `notebook-append-child!`/`notebook-remove-child!`/
  `notebook-replace-child!`/`notebook-index-after`/
  `notebook-insert-after!`/`notebook-reorder-child!` +
  `set-notebook-current-page!` + the new `:on-switch-page` signal (a
  SIXTH generalized `set-event-handler` callable shape, the first that
  reads its own raw signal argument instead of a getter, and a verified
  real mount-time auto-dispatch; see
  [`gtk-widget-layer.md`](gtk-widget-layer.md#notebookscale-button--a-sixth-callable-shape-a-mount-time-surprise-and-a-tag-aware-dispatch)),
  and `scale-button-spec`/`:scale-button` + `set-scale-button-value!` (a
  THIRD widget sharing `:scale`'s/`:spin-button`'s `"value-changed"`
  signal name, the first case needing a TAG-aware, not just signal-
  name-keyed, `set-event-handler` dispatch; same anchor as `:notebook`
  above).

  Round 10 adds `inscription-spec`/`:inscription` and
  `search-bar-spec`/`:search-bar` (both entirely display/props-driven,
  no signal, no `glitter.gtk` changes needed at all; see
  [`gtk-widget-layer.md`](gtk-widget-layer.md#inscriptionsearch-bar--two-more-quick-no-signal-wins)),
  `header-bar-spec`/`:header-bar` + `action-bar-spec`/`:action-bar` +
  `header-bar-append-child!`/`header-bar-remove-child!`/
  `header-bar-replace-child!`/`header-bar-insert-after!` +
  `action-bar-append-child!`/`action-bar-remove-child!`/
  `action-bar-replace-child!`/`action-bar-insert-after!` (a genuinely
  new HYBRID container shape; one named title/center-widget slot plus
  an ORDERED pack-start list; that surfaced two real findings: both
  widgets' `pack_end` silently reverses hiccup order, confirmed
  independently for each rather than assumed to carry over, so v1 only
  wires `pack_start`; and toggling `:show-title-buttons` prepends GTK's
  own native window-controls widget into the SAME pack-start region;
  see
  [`gtk-widget-layer.md`](gtk-widget-layer.md#header-baraction-bar--a-genuinely-new-hybrid-container-shape)),
  and `menu-button-spec`/`:menu-button` + `popover-spec`/`:popover` +
  `set-popover-visible!` + the new `:on-closed` signal entry (the
  FIRST popup surface in this project and the first hiccup
  relationship that isn't a normal append-child!-managed tree child;
  `:menu-button`'s ONE hiccup child, if present, is attached via
  `gtk_menu_button_set_popover` rather than any container-management
  case branch reused from an existing widget; both `:on-activate` and
  `:on-closed` turned out to be free reuses of the default 2-arg-void
  callable shape, needing zero `glitter.gtk` changes; see
  [`gtk-widget-layer.md`](gtk-widget-layer.md#menu-buttonpopover--a-popup-surface-not-a-normal-tree-child)).

  Round 11 adds a ctor/apply audit that found and fixed four real,
  previously-shipped bugs; `checkbutton-spec`'s never-applied
  `:label`, and `scale-spec`/`spin-button-spec`/`scale-button-spec`'s
  `:min`/`:max`/`:step` silently clobbering each other across
  single-key re-renders; see
  [`gtk-widget-layer.md`](gtk-widget-layer.md#the-ctorapply-audit--four-real-previously-shipped-bugs);
  `window-handle-spec`/`:window-handle` (single-child, no signal;
  quick win that also caught a missing-case-branch bug in this
  round's own new code; see
  [`gtk-widget-layer.md`](gtk-widget-layer.md#window-handle--a-quick-win-and-a-bug-in-this-rounds-own-code));
  `set-stack-visible-child-name!`/`stack-spec`/`:stack` + the new
  `:on-visible-child-changed` signal entry (a THIRD mount-time-auto-
  dispatch instance, plus a real `:apply`-timing gap fixed at the
  warning level only; see
  [`gtk-widget-layer.md`](gtk-widget-layer.md#stack--a-third-mount-time-auto-dispatch-instance-and-a-real-apply-timing-gap));
  `set-drop-down-selected!`/`drop-down-build-model!`/`drop-down-spec`/
  `:drop-down` + the new `:on-selected-changed` signal entry (the first
  "choose from options" widget, built on an incrementally-constructed
  `GtkStringList`; see
  [`gtk-widget-layer.md`](gtk-widget-layer.md#drop-down--the-first-choose-from-options-widget));
  and `grid-attach!`/`stack-append-child!`/`grid-spec`/`:grid` (the
  first container whose child placement is driven entirely by the
  child's own hiccup props, via the new `:glitter/structural-props`
  mechanism; see
  [`gtk-widget-layer.md`](gtk-widget-layer.md#glitterstructural-props--a-childs-props-read-by-its-parent)).
  `append-child!`/`remove-child!`/`replace-child!`/`insert-child-after!`
  each gained new `:grid`/`:stack`/`:window-handle` `case` branches, and
  the first three of those four functions gained an optional trailing
  `structural-props` argument threaded from `glitter.gtk` (see Bucket 3
  below).

  A post-round-11 fix, found while building `examples/glitter/crud.clj`:
  `list-box-reorder-child!`/`flow-box-reorder-child!` were reusing a
  widget pointer GTK had already disposed: `gtk_list_box_remove`/
  `gtk_flow_box_remove` dispose the now-unreferenced wrapping row/
  child, and BOTH wrappers' own `dispose` handlers unparent (and,
  with nothing else referencing it, finalize) their own child in turn
  (confirmed by reading `gtk_list_box_row_dispose`'s and
  `gtk_flow_box_child_dispose`'s C bodies directly). Fixed by
  bracketing the remove-then-reinsert in both functions with
  `g-object-ref-sink`/`g-object-unref`: the first actual call site
  for either binding in this codebase (both were already bound,
  inherited from glimmer's fork, but never previously used). See
  [`gtk-widget-layer.md`](gtk-widget-layer.md#list-box-reorder-child-flow-box-reorder-child--a-real-use-after-dispose-bug).

  See [`gtk-widget-layer.md`](gtk-widget-layer.md) for why all of this matters.
- `glitter.genum`: forked from `glimmer.genum`, unmodified.
- `glitter.app`: adapted from the non-reactive slice of `glimmer.core`
  (`post-to-gui`, `on-gui`, `run*`, `run`). glimmer's own `mount`/`unmount!`/
  `reload!`/`live-root`/`make-rerender-watcher` are **not** ported; they're
  glimmer-reconciler-specific and have no equivalent in glitter's
  state-atom model.

## Bucket 3: new to glitter

- `glitter.env`: Jolt/GTK environment detection. Not a port of
  `replicant.env`, which concerns ClojureScript compiler
  presence/optimization (irrelevant to a Jolt/Chez host.
- `glitter.gtk`) the `IRender`/`IMemory` GTK4 backend and `mount!`'s
  state-atom wiring. This is the file that makes glitter *glitter* rather
  than a Replicant-with-the-serial-numbers-filed-off; see
  [`architecture.md`](architecture.md) and
  [`gtk-widget-layer.md`](gtk-widget-layer.md). Round 11 adds
  `structural-child-props`/`structural-child-prop?` and special-cases
  them in `set-attribute`/`remove-attribute`. A namespaced-prop
  finding (`:grid/column`-style keys are silently dropped by
  `glitter.core`'s own `set-attr`/`update-attr` guard, upstream of
  `IRender` entirely) forced these onto plain, hyphenated keys instead,
  stashing matches on the CHILD's own `el` atom under
  `:glitter/structural-props` rather than routing them through
  `glitter.widget/apply-props!`. `append-child`, `insert-before`'s
  fresh-insert branch, and `replace-child` all thread that stashed map
  through to `glitter.widget`'s container-management functions as a new
  optional trailing argument; see
  [`gtk-widget-layer.md`](gtk-widget-layer.md#glitterstructural-props--a-childs-props-read-by-its-parent).
- `glitter.test-renderer`: an in-memory fake `IRender`/`IMemory`, inspired
  by Replicant's `mutation_log.cljc` but separately implemented (a
  different protocol-composition mechanism; `reify`, not
  `:extend-via-metadata`; was required; see below).

## Load-bearing implementation note: `reify`, never `:extend-via-metadata`

Replicant's own test helper (`replicant.mutation-log`) composes `IRender`
and a logging concern via `:extend-via-metadata true` plus `with-meta`.
This was verified **broken under Jolt** during this project's design
phase: requiring `replicant.mutation-log` and calling its renderer throws
`No method create-element in replicant.protocols/IRender`. Both
`glitter.gtk/renderer` and `glitter.test-renderer/renderer` instead
implement `IRender` *and* `IMemory` directly in a single `reify` form
(`reify` genuinely dispatches under Jolt where `:extend-via-metadata`
doesn't), and use `with-meta` only for auxiliary, non-protocol data (the
event log and memory atoms in `test-renderer`).

## Bucket 4: ported from nexus

The following files under `src/glitter/` are ported from
[nexus](https://github.com/cjohansen/nexus), commit
`5f6c93672f25d2a5b2a91ac3b65a921ecf8826b2`, by Christian Johansen,
Magnar Sveen, and Teodor Heggelund. MIT License: same terms as the
Replicant bucket above (see NOTICE for the full text).

- `src/glitter/nexus.clj`: `src/nexus/core.cljc`. One deliberate
  deviation: the three `#?(:clj Exception :cljs :default)`
  reader-conditionals collapse to a plain `Exception` catch (glitter
  targets Jolt only, no cljs).
- `src/glitter/nexus/registry.clj`: `src/nexus/registry.cljc`.
  Byte-for-byte, zero deviations.
- `src/glitter/nexus/action_log.clj`: a CONCEPT port, not a literal
  file: nexus's log-accumulation logic now lives inside
  `nexus.inspector.cljc`, entangled with `dataspex.*` rendering-protocol
  implementations with no glitter/GTK equivalent. This file ports the
  accumulation mechanism (the same nested `:entries`/`:chronology` tree)
  and drops every `dp/*`/dataspex call site. Three adaptations: `now`
  uses `tick.core/now` instead of `java.util.Date.` (jolt.time is
  already a project dependency); `find-event` reads `:glitter/dom-event`
  directly instead of hunting through `dispatch-data`'s values for a DOM
  `Event` instance, since glitter's `dispatch-data` always IS the event
  map; `measure-elapsed` returns a plain `{:ms .. :slow? ..}` map
  instead of upstream's rounded `Timing` record (`inspector.cljc`'s
  `round-tenth`): a correct adaptation, not an oversight, since
  `Timing` is a dataspex render type with no glitter equivalent, simply
  never written down until now.

Unlike Replicant/glimmer, nexus is a genuinely separate library (not
glitter's own reconciler or its widget-layer fork): glitter depends on
it conceptually the way an application depends on a dispatch library,
which is why this is its own bucket rather than folded into Bucket 1 or
3. See [`nexus.md`](nexus.md) for the architecture this enables.

## Keeping `NOTICE` current

Any new ported/forked file, or any new deviation in an already-ported file,
gets a line added to the relevant bucket in `NOTICE` in the same commit
as the code change, not as a follow-up. That file is what a downstream
consumer or license auditor actually reads; this guide page is context for
contributors, not a substitute.
