# Testing and tasks

glitter has two layers of verification: a headless unit suite against a
fake renderer, and five automated smokes that drive a *real* GTK4 window
and assert on its actual live state. Both matter — several of this
project's real bugs (keyed reorder, `replace-child!`'s position, cross-
thread render) were each "obviously correct" against the fake renderer's
bookkeeping and only wrong when actually run against live GTK.

## Unit suite: `jolt test` / `bb test`

`test/glitter/test_runner.clj` is the entry point (`deps.edn`'s `:test`
alias points `-m` at it). It calls `(System/exit code)` directly rather
than relying on any resolve-guarded exit path — see
[`limitations.md`](limitations.md)'s note on why that guard doesn't
actually work under Jolt.

The suite exercises `glitter.core`'s reconciler against
`glitter.test-renderer` — a fake, in-memory `IRender`/`IMemory` (no live
GTK display required):

```clojure
(defn renderer []
  (let [log (atom []) memory (atom {})
        impl (reify
               proto/IRender
               (create-element [_ tag-name _options]
                 (swap! log conj [:create-element tag-name])
                 (atom {:tag-name tag-name :children []}))
               ...
               proto/IMemory
               (remember [_ node data] (swap! memory assoc node data) nil)
               (recall [_ node] (get @memory node)))]
    (with-meta impl {:log log :memory memory})))
```

Every element is a plain atom holding `{:tag-name <string> :children
[...]}`; every protocol call appends a pre-formatted tuple to `log` (e.g.
`[:create-element "button"]`, `[:append-child "button" :to "box"]`).
`glitter.test-renderer/events` returns that accumulated log;
`reset-events!` clears it in place, useful when a test wants to inspect
only a second `core/reconcile` call's mutations in isolation from the
mount that preceded it. Tests assert both on the event log (mutation
sequence) *and* on the resulting tree shape directly (`:children` order),
so a test can't pass on a log that happens to look right while the actual
tree ends up wrong.

`glitter.test-renderer` ships in `src/`, not `test/`, specifically so
applications built on glitter can reuse it for their own headless tests —
not just glitter's internal suite.

Run it: `jolt test`, or `jolt -M:test` in CI (see the exit-code note
below), or `bb test`.

## Live-GTK smokes

Five examples under `examples/glitter/` each open a real GTK window,
exercise one specific behavior, read back *actual GTK state* (not
glitter's own Clojure-side tracking), and call `(System/exit 1)` directly
on mismatch:

| task | pins | how it verifies |
|---|---|---|
| `jolt smoke` | mount a tree and run the loop without an exception escaping | the loop simply completing is the assertion |
| `jolt keyed` | keyed reorder lands in the right GTK order | walks the live box's children via `gtk_widget_get_first_child`/`get_next_sibling`, reads each `:label`'s text back via `gtk_label_get_text` |
| `jolt replace-child` | a replaced child stays at its position, not the end | same live-walk approach, asserting position survived the swap |
| `jolt aliased` | aliases expand through the real renderer, on mount and update | mounts hiccup using a registered alias, confirms the expanded (not aliased) tag actually reached GTK |
| `jolt main-thread-smoke` | an off-main-thread `swap!` renders ON the GTK main thread | mutates from inside a `future`, records which thread `view` ran on, asserts it's the GTK main thread — see [`app-loop-and-threading.md`](app-loop-and-threading.md) |

`jolt counter` and `jolt todo` are the interactive examples — the full
quick-start demo from `docs/guide/index.md`, and a larger task-board demo
(ported from glimmer's own `todo.clj`) exercising derived counts, a
value-bearing `:change` handler, and checkbutton toggles — meant to be run
and clicked, not asserted on.

Each smoke's `:auto-quit-ms` option (see `glitter.app/run`) quits the GTK
loop after a fixed delay so the process exits deterministically instead of
hanging — no manual window-close needed to run these in CI.

## The exit-code trap: `jolt <task>` vs `jolt -M:<alias>`

**Verified against jolt v0.6.3**: a deps.edn `:tasks` entry does not
propagate its child process's exit status. `jolt test` (the task form)
prints failures to stdout and still exits 0; `jolt -M:test` (the alias
form) correctly exits non-zero. This isn't a glitter-specific quirk to
work around — it's how the `:tasks` wrapper behaves, and it applies to
every task in `deps.edn`, not just `test`.

**Always use `-M:<alias>` (or a `bb.edn` task, which already does this) to
gate a build.** The task shorthand (`jolt test`, `jolt keyed`, ...) is fine
for interactive use where a human is watching stdout.

## Running everything via `bb`

`bb.edn` wraps every `jolt -M:<alias>` invocation as a babashka task, with
a grouped `bb info` cheat-sheet as the discoverability entry point:

```
bb info               # grouped task list — start here
bb test                # jolt -M:test
bb counter              # interactive demo
bb todo                 # interactive task-board demo
bb smoke | keyed | replace-child | aliased | main-thread-smoke
                        # individual live-GTK smokes
bb smokes               # all five smokes in sequence; stops at first failure
```

Every `bb.edn` task shells to `jolt -M:<alias>` directly — never the
`jolt <task>` shorthand — so `bb test` and `bb smokes` are safe to use as a
CI gate on their own. `bb smokes` chains all five smokes with a plain
sequence of `shell` calls; babashka's task runner aborts on the first
non-zero exit, so it naturally stops at the first failure without any
extra control flow.

## Quality tooling: lint, format, positional-args

```
bb lint                          # clj-kondo, glitter-authored code (report only)
bb lint:strict                    # same, exits non-zero if anything is found
bb lsp:format / lsp:format-check  # clojure-lsp reformat, or dry-run check
bb lsp:clean-ns / lsp:clean-ns-check  # clojure-lsp ns cleanup, or dry-run check
bb check:positional-args / :strict    # fns with 3+ positional args (report | gate)
bb verify                         # pre-commit gate: lint (report) + test (must pass)
```

Adapted from sibling Jolt/FFI projects in the same author's umbrella
(`b12n-adk-clj` for the positional-args script, `b12n-rljlt` for the
clj-kondo hook — see `NOTICE.md`), not written from scratch, because both
needed the same fix for the same underlying problem: **`jolt.ffi/defcfn`
is a macro clj-kondo cannot see through.**

```clojure
(ffi/defcfn gtk-box-new "gtk_box_new" [:int :int] :pointer)
```

Without a hook, clj-kondo has no idea `gtk-box-new` is a defined var — every
one of `glitter.ffi`'s ~90 bindings reports as `Unresolved symbol`, and
every call site through the `g/` alias (`glitter.widget`, `glitter.gtk`,
`glitter.app`, `glitter.genum`) reports as `Unresolved var`. Scoped to just
the forked+new source files plus `test`/`examples`, that's 75 errors + 83
warnings — enough noise to make the linter worthless as a signal.
`.clj-kondo/hooks/jolt_ffi.clj` fixes this by rewriting each `defcfn` call
into an equivalent `defn` of the same name, same arity (derived from the
declared C argument-type vector), and an inferred return type (derived from
the declared C return type) — clj-kondo then sees a real var with the right
shape and stops flagging it.

**One adaptation beyond the b12n-rljlt original**: `:pointer` return values
map to a *number* here, not `nil`. rljlt's raylib pointers are opaque
handles only ever passed to other untyped `ffi/*` calls, so mapping them to
`nil` cost nothing there. glitter.ffi's own ns docstring states pointers
are "plain machine addresses (jolt numbers)", and the codebase relies on
this directly — `glitter.genum`/`glitter.widget` call `zero?` on
`:pointer`-typed return values (e.g. checking whether a GEnum lookup or a
`gtk_widget_get_prev_sibling` call returned a null pointer). Mapping
`:pointer` to `nil` there produced two spurious `type-mismatch` findings
("Expected: number, received: nil") against code that was already correct.

`glitter.alias/defalias` needed a second, smaller fix — `:lint-as
{glitter.alias/defalias clojure.core/defn}` in `.clj-kondo/config.edn`.
`defalias`'s shape (`name [argvec] body...`) is close enough to `defn`'s
that telling clj-kondo to analyze it *as* a `defn` call resolves both the
defined name and the destructured params, with no custom hook needed.

**Why `bb lint` is scoped to specific files, not all of `src/`.** The files
ported verbatim from Replicant (`glitter.core`, `glitter.alias`, etc. — see
[`porting-and-attribution.md`](porting-and-attribution.md)) deliberately
keep `#?(:clj :cljs)` reader conditionals in a `.clj` extension, a
mechanical sed rename from Replicant's original `.cljc`. Standard Clojure
tooling — clj-kondo included — restricts reader conditionals to `.cljc`
files, so every one of those forms is a permanent `error: [syntax] Reader
conditionals are only allowed in .cljc files` finding. This is real syntax
Jolt itself parses and runs correctly (`jolt -M:test` proves that far more
rigorously than static analysis could); it is simply not the syntax
clj-kondo expects from a `.clj` extension. There is no config-level fix —
`"syntax"`-class findings aren't gated by `:linters` levels the way
ordinary lint warnings are — so `bb lint`/`bb lint:strict`/`bb verify`
scope their targets to the files that don't carry this permanent, known,
harmless noise: `glitter.ffi`, `glitter.widget`, `glitter.genum`,
`glitter.app`, `glitter.env`, `glitter.gtk`, `glitter.test-renderer`, plus
`test/` and `examples/`.

**Why `bb lint` never fails by default.** Even within that scoped file set,
clj-kondo currently reports 2 warnings that are legitimate style opinions,
not false positives: a `missing-else-branch` on a deliberate throw-only
guard in `glitter.widget/markup-validate-element!`, and one genuinely
unused private helper in `test/glitter/core_test.clj`. Neither is worth a
config exclusion (that risks hiding a *real* future instance of either),
but neither should permanently block a gate either — so `bb lint` reports
and always exits 0, while `bb lint:strict` propagates clj-kondo's real exit
code for anyone who wants a hard local check. `bb verify` mirrors this
split: lint is informational, only the test suite gates.

**Why `check:positional-args`'s `exceptions` set is empty despite 32
current findings.** Running it against glitter's own `src/glitter/` finds
32 functions with 3+ positional args, almost entirely in two legitimate
categories: `glitter.core` internals mirroring Replicant's exact upstream
signatures (changing them would break porting parity), and
`glitter.widget`'s container-management fns (`append-child!`,
`reorder-child!`, `replace-child!`, ...) mirroring GTK's own C API
argument order. Pre-populating `exceptions` with all 32 names would defeat
the check's purpose; instead it stays non-strict by default (matches
b12n-adk-clj's own convention) and a human judges each new finding as it
appears, rather than a static list silently absorbing whatever's already
there.

**A real bug the b12n-adk-clj original script has, caught while
adapting it**: its `file-pattern` was `"**/*.clj"`, verified live that
`babashka.fs/glob`'s `**` requires at least one directory level — so it
silently matches files in subdirectories only. glitter's `src/glitter/` is
flat (17 files, no subdirectories), so the original pattern would have
found *zero* of them. b12n-adk-clj's own `src/net/b12n/adk/` is a mix of 18
flat files and 2 nested ones — meaning its own `check:positional-args` task
has likely only ever checked those 2 nested files. glitter's copy uses
`"{*,**/*}.clj"` instead, which matches both flat and nested files
(verified: 17 files found in glitter, vs. 0 with the original pattern).
