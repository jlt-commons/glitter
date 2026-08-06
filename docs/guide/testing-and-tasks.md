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

`jolt counter` is the sixth example — the full interactive quick-start demo
from `docs/guide/index.md`, meant to be run and clicked, not asserted on.

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
