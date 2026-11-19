# Porting and attribution

glitter's source falls into four buckets. `NOTICE` (repo root) is the
authoritative record for what is still vendored here. If this page and
`NOTICE` disagree, `NOTICE` wins.

Buckets 1 and 4 now live in other repositories:
[glitter-core](https://github.com/jlt-commons/glitter-core) and
[nexus-jolt](https://github.com/jlt-commons/nexus-jolt). Their own `NOTICE`
files are the authoritative record for those two.

## Bucket 1: ported from Replicant (now in glitter-core)

A mechanical rename port (`replicant.*` to `glitter.*`, including the
`:replicant/*` keyword namespace, via `sed -E 's/\breplicant\b/glitter/g'`)
of [Replicant](https://github.com/cjohansen/replicant) commit
`379bb3c1ad4d5d3002c57e67ab647d12f3c2d322` (2026-07-25), Copyright
2023-2025 Christian Johansen, MIT License.

In glitter-core: `glitter.protocols`, `glitter.hiccup`,
`glitter.hiccup-headers`, `glitter.console-logger`, `glitter.errors`,
`glitter.assert`, `glitter.asserts`, `glitter.vdom`, `glitter.core` and
`glitter.alias`. `glitter.test-renderer` and `glitter.env` are new code
there (see Bucket 3).

`glitter.core` (the diff/reconcile algorithm) carries three deliberate
deviations from a pure mechanical port:

1. **`build-event-map`'s `:clj` branch reads `(:glitter/node e)`** instead of
   hardcoding `nil`. Replicant's `:clj` branch was never exercised by a live
   DOM, but glitter's events are live, so the acting element has to travel
   through somehow. `glitter.gtk` supplies it as `:glitter/node` on the
   event map.
2. **`set-dispatch!` is new code.** Replicant only has `*dispatch*`, the
   dynamic var it reads. The setter lives in `replicant.dom` (browser-only,
   never ported), so glitter added a one-liner:
   `(defn set-dispatch! [f] (alter-var-root #'*dispatch* (constantly f)))`.
3. **`update-attr`/`set-attributes` route on `(some? v)` instead of
   truthiness.** A checkbutton's `:active false` or a widget's
   `:sensitive false` is a meaningful value that must reach GTK, not be
   treated as "attribute absent, remove it."

## Bucket 2: forked from glimmer

[glimmer](https://github.com/jolt-lang/glimmer) is authored by Dmitri
Sotnikov (`Yogthos`) under the `jolt-lang` organization, a different author
from glitter's, verified from git history on `upstream/main` (29 commits, 25
as `Yogthos` and 4 as `Dmitri Sotnikov`, zero by anyone else). Upstream ships
no LICENSE file, so no grant has been made. This bucket records provenance,
not a claimed permission. See `NOTICE`'s `## glimmer` section for the
authoritative statement and its reasoning.

- `glitter.ffi`: forked from `glimmer.ffi`, plus many new GTK bindings
  added for the widgets glitter supports. `NOTICE`'s `src/glitter/ffi.clj`
  entry is the exhaustive list; it is not repeated here so there is only one
  copy to keep current.
- `glitter.widget`: forked from `glimmer.widget`. It adds the widget specs
  and container strategies for every tag beyond glimmer's nine (see
  [`gtk-widget-layer.md`](gtk-widget-layer.md)) and the shared helpers
  `insert-child-after!`, `signal-name`, `signal-value-fn` and `suppressing?`.
  Four behaviours deliberately differ from glimmer's:
  1. `replace-child!`'s `:box` branch captures the old child's previous
     sibling via `gtk_widget_get_prev_sibling` and re-inserts with
     `gtk_box_insert_child_after`, where glimmer used `gtk_box_remove` +
     `gtk_box_append`. `append` always puts the child at the end of the box,
     silently relocating any non-final child.
  2. `signal-value` is keyed by `[tag gtk-signal-name]`, not by bare signal
     name: `:spin-button` and `:scale` emit the identical `"value-changed"`
     signal but need different getters.
  3. `insert-child-after!`/`reorder-child!` are no longer `:box`-only no-ops.
     `:list-box` and `:flow-box` need both. The named-slot containers get
     `insert-child-after!`, and their `reorder-child!` stays a structural
     no-op because fixed slots have no ordering to change.
  4. `list-box-remove-child!`/`list-box-replace-child!`/
     `list-box-reorder-child!` suppress on the list-box widget around
     `gtk_list_box_remove`, because removing the selected row fires a real,
     synchronous `"row-selected(NULL)"` that would otherwise reach app
     dispatch.
- `glitter.genum`: forked from `glimmer.genum`, unmodified.
- `glitter.app`: adapted from the non-reactive part of `glimmer.core`
  (`post-to-gui`, `on-gui`, `run*`, `run`). glimmer's own `mount`/`unmount!`/
  `reload!`/`live-root`/`make-rerender-watcher` are not ported, because
  they are glimmer-reconciler-specific and have no equivalent in glitter's
  state-atom model.

## Bucket 3: new to glitter

- `glitter.env` (in glitter-core): Jolt/GTK environment detection. Not a
  port of `replicant.env`, which concerns ClojureScript compiler
  presence and optimization.
- `glitter.gtk`: the `IRender`/`IMemory` GTK4 backend and `mount!`'s
  state-atom wiring. See [`architecture.md`](architecture.md) and
  [`gtk-widget-layer.md`](gtk-widget-layer.md). It also stashes a child's
  structural props (`:grid-column` and the like) under
  `:glitter/structural-props` and threads them to `glitter.widget`'s
  container functions, see
  [`gtk-widget-layer.md`](gtk-widget-layer.md#child-borne-props-glitterstructural-props).
  Namespaced keys such as `:grid/column` can't be used for these, because
  `glitter.core` drops them before any backend sees them.
- `glitter.test-renderer` (in glitter-core): an in-memory fake
  `IRender`/`IMemory`, inspired by Replicant's `mutation_log.cljc` but
  implemented separately with `reify`, see the next section.

## Implementation note: `reify`, never `:extend-via-metadata`

Replicant's test helper (`replicant.mutation-log`) composes `IRender` and a
logging concern via `:extend-via-metadata true` plus `with-meta`. That is
broken under Jolt: calling its renderer throws
`No method create-element in replicant.protocols/IRender`. Both
`glitter.gtk/renderer` and `glitter.test-renderer/renderer` instead
implement `IRender` and `IMemory` directly in one `reify` form, which does
dispatch under Jolt, and use `with-meta` only for auxiliary non-protocol data
(the event log and memory atoms in `test-renderer`).

## Bucket 4: ported from nexus (now in nexus-jolt and glitter-core)

Ported from [nexus](https://github.com/cjohansen/nexus), commit
`5f6c93672f25d2a5b2a91ac3b65a921ecf8826b2`, by Christian Johansen, Magnar
Sveen and Teodor Heggelund. MIT License, same terms as Replicant (see
`NOTICE` for the full text).

- `nexus.core` (was `glitter.nexus`, in nexus-jolt): from
  `src/nexus/core.cljc`. One deviation: the three
  `#?(:clj Exception :cljs :default)` reader conditionals collapse to a plain
  `Exception` catch, since the target is Jolt only.
- `nexus.registry` (was `glitter.nexus.registry`, in nexus-jolt): from
  `src/nexus/registry.cljc`. Byte-for-byte, zero deviations.
- `glitter.nexus.action-log` (in glitter-core): a concept port, not a
  literal file. Upstream's log accumulation lives inside
  `nexus.inspector.cljc`, entangled with `dataspex.*` rendering protocols
  that have no glitter/GTK equivalent. This file keeps the accumulation
  mechanism (the same nested `:entries`/`:chronology` tree) and drops every
  `dp/*`/dataspex call site. Three adaptations: `now` uses `tick.core/now`
  instead of `java.util.Date.`; `find-event` reads `:glitter/dom-event`
  directly, since glitter's `dispatch-data` always is the event map;
  `measure-elapsed` returns a plain `{:ms .. :slow? ..}` map instead of
  upstream's rounded `Timing` record, which is a dataspex render type.

nexus is a separate library, not glitter's reconciler or its widget-layer
fork, which is why it is its own bucket. See [`nexus.md`](nexus.md) for the
architecture it enables.

## Keeping `NOTICE` current

Any new ported or forked file, or any new deviation in an already-ported
file, gets a line in the relevant bucket in `NOTICE` in the same commit as
the code change. That file is what a downstream consumer or license auditor
reads. This page is context for contributors, not a substitute.
