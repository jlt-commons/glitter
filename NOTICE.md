# Third-party code

## Replicant

The following files under `src/glitter/` are ported from
[Replicant](https://github.com/cjohansen/replicant), commit
`379bb3c1ad4d5d3002c57e67ab647d12f3c2d322` (2026-07-25), by Christian
Johansen. Porting is a mechanical rename (`replicant.*` → `glitter.*`,
including the `:replicant/*` keyword namespace) via
`sed -E 's/\breplicant\b/glitter/g'`, with any further deviation noted in
that file's header comment.

Copyright 2023-2025 Christian Johansen. MIT License:

```
Permission is hereby granted, free of charge, to any person obtaining a copy of
this software and associated documentation files (the "Software"), to deal in
the Software without restriction, including without limitation the rights to
use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
the Software, and to permit persons to whom the Software is furnished to do so,
subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
```

Ported files (path in glitter — original path in replicant):

- `src/glitter/protocols.clj` — `src/replicant/protocols.cljc`
- `src/glitter/hiccup.clj` — `src/replicant/hiccup.cljc`
- `src/glitter/hiccup_headers.clj` — `src/replicant/hiccup_headers.cljc`
- `src/glitter/console_logger.clj` — `src/replicant/console_logger.cljc`
- `src/glitter/errors.clj` — `src/replicant/errors.cljc`
- `src/glitter/assert.clj` — `src/replicant/assert.cljc`
- `src/glitter/vdom.clj` — `src/replicant/vdom.cljc`
- `src/glitter/asserts.clj` — `src/replicant/asserts.cljc`
- `src/glitter/core.clj` — `src/replicant/core.cljc` (three deliberate deviations: build-event-map's :clj branch reads :glitter/node from e instead of hardcoding nil; `set-dispatch!` is new code, not a port — replicant.dom's fn, replicant.core has no equivalent (see its docstring); update-attr/set-attributes route on `some?` instead of truthiness so an explicit `false` prop reaches the renderer instead of being treated as absent (GTK booleans, unlike DOM attributes, have no absent state) — see file header)
- `src/glitter/alias.clj` — `src/replicant/alias.cljc`

<!-- appended to by each porting task -->

## glimmer

The following files under `src/glitter/` are forked from
[glimmer](https://github.com/jolt-lang/glimmer) (same author/org as
glitter — no license file, no attribution obligation, listed here for
provenance only):

- `src/glitter/ffi.clj` — `src/glimmer/ffi.clj` (+ g-signal-handler-disconnect, gtk-box-insert-child-after, gtk-label-get-text, gtk-widget-get-prev-sibling, gtk-scale-new-with-range, gtk-scale-set-digits, gtk-scale-set-draw-value, gtk-range-set-value, gtk-range-get-value, gtk-range-set-range, gtk-range-set-increments, gtk-widget-add-css-class, gtk-widget-remove-css-class, gtk-widget-has-css-class, gtk-spinner-new, gtk-spinner-set-spinning, gtk-spinner-get-spinning, gtk-progress-bar-new, gtk-progress-bar-set-fraction, gtk-progress-bar-set-text, gtk-progress-bar-set-show-text, gtk-progress-bar-get-fraction, gtk-image-new, gtk-image-new-from-icon-name, gtk-image-new-from-file, gtk-image-set-from-icon-name, gtk-image-set-from-file, gtk-image-set-pixel-size, gtk-image-get-icon-name, gtk-toggle-button-new, gtk-toggle-button-new-with-label, gtk-toggle-button-set-active, gtk-toggle-button-get-active, gtk-level-bar-new, gtk-level-bar-set-value, gtk-level-bar-set-min-value, gtk-level-bar-set-max-value, gtk-level-bar-set-inverted, gtk-level-bar-get-value, gtk-link-button-new, gtk-link-button-new-with-label, gtk-link-button-set-uri, gtk-link-button-get-uri, gtk-widget-activate, gtk-switch-new, gtk-switch-set-active, gtk-switch-get-active)
- `src/glitter/widget.clj` — `src/glimmer/widget.clj` (+ insert-child-after!, signal-name, signal-value-fn, suppressing?, set-scale-value!, scale-spec/:scale widget entry, :on-value-changed signal, spinner-spec/:spinner, progress-bar-spec/:progress-bar, image-spec/:image widget entries (all three display-only, no signal), set-toggle-button-active!, toggle-button-spec/:toggle-button widget entry (reuses :on-toggled verbatim), level-bar-spec/:level-bar widget entry (display-only, no signal), link-button-spec/:link-button widget entry (reuses :on-click verbatim), set-switch-active!, switch-spec/:switch widget entry, :on-state-set signal; one behavioural deviation: replace-child!'s :box branch captures old-child's previous sibling via gtk_widget_get_prev_sibling and re-inserts with gtk_box_insert_child_after, where glimmer used gtk_box_remove + gtk_box_append — append always lands at the END of the box, silently relocating any non-final child — see the fn's docstring)
- `src/glitter/genum.clj` — `src/glimmer/genum.clj`
- `src/glitter/app.clj` — adapted from `src/glimmer/core.clj`'s non-reactive app-loop functions (post-to-gui, on-gui, run*, run); mount/unmount!/reload!/live-root/make-rerender-watcher are NOT ported (glimmer-reconciler-specific)

<!-- appended to by each forking task -->

## New code

The following files are original to glitter:

- `src/glitter/env.clj` — Jolt/GTK-specific environment detection (not a port of replicant.env, which concerns ClojureScript compiler presence/optimization — irrelevant to Jolt)
- `src/glitter/gtk.clj` — IRender/IMemory GTK4 backend and state-atom mount/render wiring. `set-event-handler` was generalized beyond the uniform `[:pointer :pointer] :void` foreign-callable shape to also support `GtkSwitch`'s `"state-set"` (`[:pointer :int :pointer] :int`) — branches explicitly on GTK signal name at two literal `foreign-callable` call sites (jolt's `argtypes`/`rettype` must be compile-time literals, verified live; a data-driven table lookup does not work) — see `docs/guide/gtk-widget-layer.md`
- `src/glitter/test_renderer.clj` — in-memory fake IRender/IMemory for headless reconciler tests (inspired by replicant's mutation_log.cljc, separately implemented)

## b12n-adk-clj / b12n-rljlt

Same author/org as glitter (private repos, no license file, no attribution
obligation, listed here for provenance only):

- `scripts/check_positional_args.clj` — `scripts/check_positional_args.clj`
  from b12n-adk-clj, `source-dirs` retargeted to `src/glitter`; one
  behavioural fix: `file-pattern` changed from the original's `"**/*.clj"`
  to `"{*,**/*}.clj"` — verified live that babashka.fs/glob's `**` requires
  at least one directory level, so the original pattern silently matches
  only files in subdirectories and misses every file sitting directly in
  `source-dirs` (glitter's `src/glitter` is flat, so the original pattern
  would have found zero of its 17 files; the same bug affects b12n-adk-clj's
  own copy, which has both flat and nested source files).
- `.clj-kondo/hooks/jolt_ffi.clj` — `.clj-kondo/hooks/jolt_ffi.clj` from
  b12n-rljlt (same `jolt.ffi/defcfn` macro, same false-positive problem);
  one behavioural deviation: `:pointer` return values map to a number, not
  `nil` — glitter.ffi's own ns docstring states pointers are "plain machine
  addresses (jolt numbers)", and glitter.genum/glitter.widget call `zero?`
  directly on `:pointer`-typed return values, which trips a spurious
  `type-mismatch` against a nil-typed stub (see the hook's docstring).
