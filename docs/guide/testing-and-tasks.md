# Testing and tasks

glitter has two layers of verification: a headless unit suite against a
fake renderer, and 27 smokes that drive a real GTK4 window and assert on
its live state. Several bugs (keyed reorder, `replace-child!`'s position,
cross-thread render) looked correct against the fake renderer's bookkeeping
and only failed against live GTK, so both layers matter.

## Unit suite

`bb test` (or `jolt -M:test`) runs `test/glitter/test_runner.clj`, which
calls `(System/exit code)` directly. See [`limitations.md`](limitations.md)
for why a resolve-guarded exit doesn't work under Jolt.

The suite exercises `glitter.core`'s reconciler against
`glitter.test-renderer`, a fake in-memory `IRender`/`IMemory` that needs no
display:

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
[...]}`, and every protocol call appends a tuple to `log` (for example
`[:create-element "button"]` or `[:append-child "button" :to "box"]`).
`glitter.test-renderer/events` returns the log and `reset-events!` clears
it, so a test can look at one `core/reconcile` call in isolation. Tests
assert on both the event log and the resulting tree, so a log that looks
right can't hide a wrong tree.

`glitter.test-renderer` ships in `src/`, not `test/`, so applications built
on glitter can reuse it in their own headless tests.

## Live-GTK smokes

Each example under `examples/glitter/` opens a real GTK window, exercises
one behaviour, reads back actual GTK state (not glitter's own tracking),
and calls `(System/exit 1)` on mismatch. Their `:auto-quit-ms` option (see
`glitter.app/run`) quits the loop after a fixed delay, so they exit on their
own in CI. Per-widget detail is in [`gtk-widget-layer.md`](gtk-widget-layer.md).

| task | pins |
|---|---|
| `smoke` | mount a tree, run the loop without an exception escaping |
| `keyed` | keyed reorder lands in the right GTK order |
| `replace-child` | a replaced child stays at its position, not the end |
| `aliased` | aliases expand through the real renderer, on mount and update |
| `main-thread-smoke` | an off-main-thread swap! renders ON the GTK main thread |
| `scale-smoke` | GtkScale's value-changed signal delivers the right double, no spurious dispatch |
| `class-smoke` | :class reaches real GTK CSS classes: add, coexist, and remove on diff |
| `leaf-widgets-smoke` | :spinner/:progress-bar/:image construction + re-render, real GTK state |
| `toggle-level-smoke` | :toggle-button's toggled signal + :level-bar construction/re-render |
| `link-button-smoke` | :link-button reuses :on-click; real click via gtk_widget_activate |
| `switch-smoke` | :switch's state-set signal (3-arg, non-void return) via a generalized callable |
| `revealer-center-box-smoke` | :revealer's props-driven reveal/transition; :center-box's 3 named slots |
| `spin-button-list-box-smoke` | :spin-button's tag-safe value-fn; :list-box's row-selected + container-management fixes |
| `gallery-smoke` | all four widget galleries mount and round-trip |
| `password-search-entry-smoke` | :password-entry/:search-entry reuse :entry's GtkEditable-delegate "changed" signal |
| `expander-paned-smoke` | :expander/:paned's notify::* signals reuse the 3-arg-void callable shape |
| `aspect-frame-calendar-smoke` | :aspect-frame's single-child container reuse; :calendar's GDateTime refcounted round-trip |
| `overlay-flow-box-smoke` | :overlay's 1-main+N-overlay shape; :flow-box's list-box-lesson reuse |
| `picture-editable-label-smoke` | :picture's display-only GdkPaintable props; :editable-label's 3rd GtkEditable-delegate reuse |
| `notebook-scale-button-smoke` | :notebook's raw-signal-arg switch-page + mount-time auto-select-page-0; :scale-button's tag-aware value-changed |
| `inscription-search-bar-smoke` | :inscription's no-signal display props; :search-bar's single-child :search-mode/:show-close-button props |
| `header-bar-action-bar-smoke` | :header-bar/:action-bar's hybrid title/center-widget + pack-start shape |
| `menu-button-popover-smoke` | :menu-button's popover-as-child relationship (gtk_menu_button_set_popover); :popover's suppressing-guarded :visible + free-reuse :on-activate/:on-closed signals |
| `ctor-apply-regression-smoke` | pins four fixed bugs (:checkbutton's :label, :scale's/:spin-button's/:scale-button's :min/:max/:step clobbering) |
| `window-handle-stack-smoke` | :window-handle's single-child reuse; :stack's name-addressed pages + mount-time auto-select-page dispatch |
| `drop-down-grid-smoke` | :drop-down's GtkStringList-backed selection + free-reuse signals; :grid's child-props-driven 2D placement via glitter.gtk's set-attribute structural-prop interception |
| `list-box-reorder-smoke` | pins the g-object-ref-sink/g-object-unref fix for list-box-reorder-child!/flow-box-reorder-child!'s keyed-reorder use-after-dispose bug |

The interactive demos are `counter`, `todo`, `crud`, `flights`,
`temperature`, `timer` and the four `gallery-*` pages. They are meant to be
run and clicked, not asserted on. `flights`, `temperature`, `timer`, `todo`
and `crud` use `glitter.nexus` (see [`nexus.md`](nexus.md)); `counter`
dispatches by hand. `crud` also covers `:list-box` selection and keyed
reordering, and `timer` reads `System/nanoTime` at render time rather than
through `:nexus/system->state`. The 7GUIs demos are ports of the
[7GUIs tasks](https://eugenkiss.github.io/7guis/).

## Exit codes: `jolt <task>` vs `jolt -M:<alias>`

On jolt v0.6.3 a `deps.edn` `:tasks` entry did not propagate its child's
exit status, so `jolt test` printed failures and exited 0 while
`jolt -M:test` exited non-zero. Jolt fixed this in v0.7.28. glitter's floor
is now 0.8.24, so the task form is safe, but CI and `bb.edn` still use
`jolt -M:<alias>`, and you should use it to gate a build. The `jolt <task>`
shorthand is for interactive use.

## Running everything via `bb`

`bb.edn` wraps every `jolt -M:<alias>` invocation as a task. `bb info`
prints a grouped cheat-sheet and is the place to start. `bb test` runs the
unit suite, each smoke above has a `bb <name>` task, and `bb smokes` runs
all 27 in sequence and stops at the first failure.

Every task shells to `jolt -M:<alias>` directly, so `bb test` and
`bb smokes` are safe as a CI check.

## Quality tooling

```
bb lint / lint:strict / lint:errors      clj-kondo (report | propagate real exit | errors-only)
bb lsp:format / lsp:format-check          clojure-lsp reformat, or dry-run check
bb lsp:clean-ns / lsp:clean-ns-check      clojure-lsp ns cleanup, or dry-run check
bb lsp:diagnostics / lsp:check / lsp:fix  diagnostics | all dry-run checks | auto-fix
bb check:positional-args / :strict        fns with 3+ positional args (report | gate)
bb verify                                 pre-commit gate: lint (report) + test (must pass)
bb hooks:install / :install:full / :uninstall   git pre-commit hook (fast | +tests | remove)
```

The positional-args script and the clj-kondo hook are adapted from
`b12n-adk-clj` (a private repo) and
[`b12n-raylib-jlt`](https://github.com/jlt-commons/raylib-jlt); see
`NOTICE`.

### clj-kondo and `defcfn`

`jolt.ffi/defcfn` is a macro clj-kondo can't see through:

```clojure
(ffi/defcfn gtk-box-new "gtk_box_new" [:int :int] :pointer)
```

Without help, every `glitter.ffi` binding reports `Unresolved symbol` and
every call through the `g/` alias reports `Unresolved var`.
`.clj-kondo/hooks/jolt_ffi.clj` rewrites each `defcfn` into a `defn` with
the same name and an arity taken from the declared C argument types.

One change from the raylib-jlt original: `:pointer` returns map to a
number, not `nil`. `glitter.ffi` treats pointers as plain machine addresses
(jolt numbers) and `glitter.genum`/`glitter.widget` call `zero?` on them,
so `nil` produced false `type-mismatch` findings.

`glitter.alias/defalias` needs only `:lint-as {glitter.alias/defalias
clojure.core/defn}` in `.clj-kondo/config.edn`, since its shape is close
enough to `defn`.

### Ported files are excluded from lint

The ten files ported from Replicant (see
[`porting-and-attribution.md`](porting-and-attribution.md)) keep
`#?(:clj :cljs)` reader conditionals in a `.clj` extension. clj-kondo only
allows those in `.cljc`, so each would be a permanent `[syntax]` error, and
syntax findings can't be turned off through `:linters`. Jolt parses them
fine. `.clj-kondo/config.edn`'s `:output {:exclude-files [...]}` lists the
ten files, so `bb lint` can target `src test examples` without a file list,
and clojure-lsp, which runs clj-kondo, respects the same config.

### Lint exit codes

clj-kondo exits `0` when clean, `2` with warnings only and `3` with errors.
`bb lint` and `bb verify` report and always exit `0`, `bb lint:strict`
propagates the raw code, and `bb lint:errors` (used by the git hooks) fails
only on exit `3`. Currently `bb lint` reports 0 errors and 1 warning, a
`missing-else-branch` on the deliberate throw-only guard in
`glitter.widget/markup-validate-element!`. It isn't worth a config exclusion,
which could hide a real future instance.

## Git hooks

`bb hooks:install` writes an untracked `.git/hooks/pre-commit`, so each
clone opts in. The fast hook (about 2s) runs `clj-kondo --lint src test
examples` with `lint:errors`' exit-3 rule, then `clojure-lsp format --dry`,
then `clojure-lsp clean-ns --dry`. `bb hooks:install:full` adds the
headless `jolt -M:test` suite. `bb hooks:uninstall` removes the hook, and
`git commit --no-verify` skips it for one commit.

### Formatting

clojure-lsp formats the whole codebase, including the ten Replicant files,
and `bb lsp:format-check` must stay clean. This costs diffability against
upstream: for example clojure-lsp splits `{:keys [renderer] :as impl}`
destructuring across two lines where Replicant keeps it on one, and no
`:cljfmt` `:function-arguments-indentation` setting (`:standard` or
`:community`) preserves the inline form.

### Positional args

`check:positional-args` finds functions with 3+ positional args. Its
`exceptions` set is empty on purpose. The findings are mostly
`glitter.core` internals that mirror Replicant's signatures and
`glitter.widget`'s container fns (`append-child!`, `reorder-child!`,
`replace-child!`) that mirror GTK's C argument order. Listing them would
defeat the check, so it stays non-strict and a human judges each new
finding.

Its `file-pattern` is `"{*,**/*}.clj"`. The `b12n-adk-clj` original used
`"**/*.clj"`, and `babashka.fs/glob`'s `**` needs at least one directory
level, so it misses files in a flat directory such as `src/glitter/`.
