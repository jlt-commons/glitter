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

Eight examples under `examples/glitter/` each open a real GTK window,
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
| `jolt scale-smoke` | `:scale`'s `value-changed` signal delivers the right double, and a programmatic state sync doesn't cause a spurious second dispatch | a real FFI `gtk_range_set_value` call simulates a live drag (bypassing `set-scale-value!`), asserting the dispatched double and the dispatch count both before and after a subsequent programmatic `reset!` — see [`gtk-widget-layer.md`](gtk-widget-layer.md#scale--the-first-party-value-bearing-custom-signal) |
| `jolt class-smoke` | `:class` reaches real GTK CSS classes — add, coexist, and remove on a re-render diff | reads class membership back via `gtk_widget_has_css_class`, asserting a built-in class and a custom class both apply on mount, then that dropping one class while adding another in the same re-render calls both `add-class` and `remove-class` correctly — see [`gtk-widget-layer.md`](gtk-widget-layer.md#known-gap-removing-a-key-is-a-no-op) |
| `jolt leaf-widgets-smoke` | `:spinner`/`:progress-bar`/`:image` construction + re-render land on real GTK state | reads each widget's actual state back (`gtk_spinner_get_spinning`/`gtk_progress_bar_get_fraction`/`gtk_image_get_icon_name`) on mount and after a re-render with different values — see [`gtk-widget-layer.md`](gtk-widget-layer.md#spinnerprogress-barimage--display-only-widgets-need-no-signal) |

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

## Quality tooling: lint, format, positional-args, git hooks

```
bb lint / lint:strict / lint:errors      clj-kondo (report | propagate real exit | errors-only)
bb lsp:format / lsp:format-check          clojure-lsp reformat, or dry-run check
bb lsp:clean-ns / lsp:clean-ns-check      clojure-lsp ns cleanup, or dry-run check
bb lsp:diagnostics / lsp:check / lsp:fix  diagnostics | all dry-run checks | auto-fix
bb check:positional-args / :strict        fns with 3+ positional args (report | gate)
bb verify                                 pre-commit gate: lint (report) + test (must pass)
bb hooks:install / :install:full / :uninstall   git pre-commit hook (fast | +tests | remove)
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

**Why `bb lint` can safely target `src test examples` directly, no manual
file list.** The files ported verbatim from Replicant (`glitter.core`,
`glitter.alias`, etc. — see
[`porting-and-attribution.md`](porting-and-attribution.md)) deliberately
keep `#?(:clj :cljs)` reader conditionals in a `.clj` extension, a
mechanical sed rename from Replicant's original `.cljc`. Standard Clojure
tooling — clj-kondo included — restricts reader conditionals to `.cljc`
files, so every one of those forms is a permanent `error: [syntax] Reader
conditionals are only allowed in .cljc files` finding. This is real syntax
Jolt itself parses and runs correctly (`jolt -M:test` proves that far more
rigorously than static analysis could); it is simply not the syntax
clj-kondo expects from a `.clj` extension. `"syntax"`-class findings aren't
gated by `:linters` levels the way ordinary lint warnings are, so the fix
is `.clj-kondo/config.edn`'s `:output {:exclude-files [...]}` — 10 regex
patterns, one per ported file — the same pattern
[`b12n-sumo-app`](https://github.com/burinc/b12n-sumo-app) uses to exclude
ClojureDart source clj-kondo can't parse at all. Scoping at the *config*
level rather than in every task's command line means `clj-kondo --lint src
test examples` (or even an editor's clojure-lsp pass, since `clojure-lsp
diagnostics` runs clj-kondo under the hood and respects the same config)
is safe to run unscoped anywhere — a new non-ported file is linted
automatically, with nothing to remember to add to a task's argument list.

**Why `bb lint`/`bb verify` never fail by default, but `bb lint:errors`
does.** Even with the ported files excluded, clj-kondo currently reports 2
warnings that are legitimate style opinions, not false positives: a
`missing-else-branch` on a deliberate throw-only guard in
`glitter.widget/markup-validate-element!`, and one genuinely unused
private helper in `test/glitter/core_test.clj`. Neither is worth a config
exclusion (that risks hiding a *real* future instance of either), but
neither should permanently block a gate either. clj-kondo's exit code is a
severity ladder — `0` clean, `2` warnings only, `3` errors present
(verified live: an intentionally-broken probe file reproduced `errors: 2,
warnings: 0` → exit `3`) — so `bb lint`/`bb verify` report and always exit
`0`, `bb lint:strict` propagates the raw code, and `bb lint:errors` (used
by the git hooks below) fails only when the exit code is exactly `3`,
treating the 2 known warnings the same as a clean run.

## Git hooks: `bb hooks:install` / `:install:full` / `:uninstall`

`bb hooks:install` writes an executable `.git/hooks/pre-commit` (via
`spit`, not tracked in the repo — each clone opts in with its own `bb
hooks:install` run, adapted from `b12n-adk-clj`'s identical pattern). The
FAST hook runs in ~2s: `clj-kondo --lint src test examples` gated on
`bb lint:errors`' exit-3-only rule, then `clojure-lsp clean-ns --dry`.
`bb hooks:install:full` adds a third step, the full `jolt -M:test` suite
(safe to run in a hook — the suite is headless, driven by
`glitter.test-renderer`, no live GTK window needed). `bb hooks:uninstall`
deletes the hook file (idempotent — reports "no pre-commit hook found" on
a second run rather than erroring). `git commit --no-verify` skips the
hook for one commit.

**Deliberately not included: `clojure-lsp format --dry`.** The codebase
currently has real drift against clojure-lsp's default formatting style in
a handful of files, found while first wiring these tasks up: clojure-lsp's
formatter wraps `{:keys [x] :as y}`-shaped destructuring across two lines
even when the whole form comfortably fits on one —

```clojure
;; clojure-lsp's default output
(defn reconcile* [{:keys [renderer]
                   :as impl} el headers vdom index]
  ...)
```

— which is not merely a style preference glitter happens to disagree
with. Checked directly against `replicant.core.cljc` in the actual
upstream Replicant source: **upstream itself writes this exact function
signature on one line.** Since `glitter.core` and the other Bucket-1 files
are supposed to stay a mechanical, diffable port of Replicant (see
[`porting-and-attribution.md`](porting-and-attribution.md)), reformatting
them to clojure-lsp's default would simultaneously read worse than the
hand-tuned original *and* reduce future diffability against upstream, for
no offsetting benefit. No config override was found that suppresses just
this rule (`:cljfmt {:function-arguments-indentation ...}` was tried with
both documented values, `:standard`/`:community` — neither preserves the
inline form). Rather than force a mass-reformat or silently accept the
readability regression, this was left as a known, open decision: `bb
lsp:format`/`bb lsp:format-check` remain available as on-demand tools, but
nothing runs them automatically, and they're not part of either git hook.
If a `.lsp/config.edn` override is found later that reconciles this, wire
`format --dry` into the hooks at that point — not before.

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
