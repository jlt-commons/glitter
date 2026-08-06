# Porting and attribution

glitter's source falls into three buckets. `NOTICE.md` (repo root) is the
authoritative, maintained ledger — this page explains what the buckets mean
and summarizes the deviations; if the two ever disagree, `NOTICE.md` wins.

## Bucket 1: ported from Replicant

Mechanical rename port (`replicant.* → glitter.*`, including the
`:replicant/*` keyword namespace) via `sed -E 's/\breplicant\b/glitter/g'`,
from [Replicant](https://github.com/cjohansen/replicant) commit
`379bb3c1ad4d5d3002c57e67ab647d12f3c2d322` (2026-07-25), Copyright
2023-2025 Christian Johansen, MIT License.

Ported files: `glitter.protocols`, `glitter.hiccup`, `glitter.hiccup-headers`,
`glitter.console-logger`, `glitter.errors`, `glitter.assert`, `glitter.vdom`,
`glitter.asserts`, `glitter.core`, `glitter.alias`.

`glitter.core` — the diff/reconcile algorithm — is the largest and most
important of these, and carries **three deliberate deviations** from a pure
mechanical port:

1. **`build-event-map`'s `:clj` branch reads `(:glitter/node e)`** instead of
   hardcoding `nil`. Replicant's `#?(:cljs (.-target e) :clj nil)` reflects
   that its `:clj` branch was never exercised by a live DOM (Replicant
   targets ClojureScript/the browser) — but glitter's events *are* live, so
   the acting element has to travel through somehow. `glitter.gtk` supplies
   it as `:glitter/node` on the event map it hands to the dispatched
   handler.
2. **`set-dispatch!` is new code, not a port.** It doesn't exist in
   `replicant.core` at all — only `*dispatch*`, the dynamic var it reads.
   The function lives in `replicant.dom` (browser-specific, never ported —
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

Same author/org as glitter, no license file, no attribution obligation —
listed here for provenance, not legal requirement:

- `glitter.ffi` — forked from `glimmer.ffi`, plus the original four new
  bindings (`g-signal-handler-disconnect`, `gtk-box-insert-child-after`,
  `gtk-label-get-text`, `gtk-widget-get-prev-sibling`), seven for `:scale`
  (`gtk-scale-new-with-range`, `gtk-scale-set-digits`,
  `gtk-scale-set-draw-value`, `gtk-range-set-value`, `gtk-range-get-value`,
  `gtk-range-set-range`, `gtk-range-set-increments`), three for `:class`
  (`gtk-widget-add-css-class`, `gtk-widget-remove-css-class`,
  `gtk-widget-has-css-class`), and thirteen for the display-only widgets
  `:spinner`/`:progress-bar`/`:image` (`gtk-spinner-new`,
  `gtk-spinner-set-spinning`, `gtk-spinner-get-spinning`,
  `gtk-progress-bar-new`, `gtk-progress-bar-set-fraction`,
  `gtk-progress-bar-set-text`, `gtk-progress-bar-set-show-text`,
  `gtk-progress-bar-get-fraction`, `gtk-image-new`,
  `gtk-image-new-from-icon-name`, `gtk-image-new-from-file`,
  `gtk-image-set-from-icon-name`, `gtk-image-set-from-file`,
  `gtk-image-set-pixel-size`, `gtk-image-get-icon-name`).
- `glitter.widget` — forked from `glimmer.widget`, plus `insert-child-after!`,
  `signal-name`, `signal-value-fn`, `suppressing?`, `set-scale-value!` and
  the `:scale` widget spec (a first-party demonstration of the
  value-bearing custom-signal path — see
  [`gtk-widget-layer.md`](gtk-widget-layer.md#scale--the-first-party-value-bearing-custom-signal)),
  plus the display-only `:spinner`/`:progress-bar`/`:image` widget specs
  (no signal wiring — driven entirely by re-applied props) as new public
  accessors, and one behavioral deviation: `replace-child!`'s `:box`
  branch captures the old child's previous sibling via
  `gtk_widget_get_prev_sibling` and re-inserts via
  `gtk_box_insert_child_after`, where glimmer used `gtk_box_remove` +
  `gtk_box_append` — `append` always lands at the *end* of the box, which
  silently relocates any non-final child. See
  [`gtk-widget-layer.md`](gtk-widget-layer.md) for why this matters.
- `glitter.genum` — forked from `glimmer.genum`, unmodified.
- `glitter.app` — adapted from the non-reactive slice of `glimmer.core`
  (`post-to-gui`, `on-gui`, `run*`, `run`). glimmer's own `mount`/`unmount!`/
  `reload!`/`live-root`/`make-rerender-watcher` are **not** ported — they're
  glimmer-reconciler-specific and have no equivalent in glitter's
  state-atom model.

## Bucket 3: new to glitter

- `glitter.env` — Jolt/GTK environment detection. Not a port of
  `replicant.env`, which concerns ClojureScript compiler
  presence/optimization — irrelevant to a Jolt/Chez host.
- `glitter.gtk` — the `IRender`/`IMemory` GTK4 backend and `mount!`'s
  state-atom wiring. This is the file that makes glitter *glitter* rather
  than a Replicant-with-the-serial-numbers-filed-off; see
  [`architecture.md`](architecture.md) and
  [`gtk-widget-layer.md`](gtk-widget-layer.md).
- `glitter.test-renderer` — an in-memory fake `IRender`/`IMemory`, inspired
  by Replicant's `mutation_log.cljc` but separately implemented (a
  different protocol-composition mechanism — `reify`, not
  `:extend-via-metadata` — was required; see below).

## Load-bearing implementation note: `reify`, never `:extend-via-metadata`

Replicant's own test helper (`replicant.mutation-log`) composes `IRender`
and a logging concern via `:extend-via-metadata true` plus `with-meta`.
This was verified **broken under Jolt** during this project's design
phase: requiring `replicant.mutation-log` and calling its renderer throws
`No method create-element in replicant.protocols/IRender`. Both
`glitter.gtk/renderer` and `glitter.test-renderer/renderer` instead
implement `IRender` *and* `IMemory` directly in a single `reify` form —
`reify` genuinely dispatches under Jolt where `:extend-via-metadata`
doesn't — and use `with-meta` only for auxiliary, non-protocol data (the
event log and memory atoms in `test-renderer`).

## Keeping `NOTICE.md` current

Any new ported/forked file, or any new deviation in an already-ported file,
gets a line added to the relevant bucket in `NOTICE.md` in the same commit
as the code change — not as a follow-up. That file is what a downstream
consumer or license auditor actually reads; this guide page is context for
contributors, not a substitute.
