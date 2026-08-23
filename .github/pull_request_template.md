## What this changes

<!-- One or two sentences. If it adds a widget, name the hiccup tag and the
     GTK4 class it maps to. -->

## Gates

<!-- The first three are headless and fast. `bb smokes` opens real GTK4
     windows, so it needs a display — run it locally if you touched
     glitter.gtk, glitter.widget, or glitter.ffi. -->

- [ ] `bb test` passes (unit suite, headless)
- [ ] `bb lint:errors` passes (clj-kondo)
- [ ] `bb lsp:format-check` passes (clojure-lsp formatting, **not** cljfmt)
- [ ] `bb smokes` passes, or N/A (live-GTK — needed for widget-layer changes)

## If this adds a widget

<!-- Skip this section otherwise. See docs/guide/gtk-widget-layer.md. -->

- [ ] Spec registered in `glitter.widget/specs` (constructor, prop-applier,
      container strategy)
- [ ] Any value-bearing signal registered in `signal-value`, keyed by
      `[tag signal]` — **not** by bare signal name
- [ ] A non-standard signal shape has its own literal branch in
      `glitter.gtk/set-event-handler` (shapes can't be data-driven — see
      CONTRIBUTING.md § Adding a widget)
- [ ] Live smoke added under `examples/glitter/` and wired into `bb.edn`'s
      `smokes` list
- [ ] Widget named in `README.md`'s status list and in
      `docs/guide/gtk-widget-layer.md`
- [ ] Any new v1 gap recorded in `docs/guide/limitations.md` with the reasoning

## Invariants

<!-- CONTRIBUTING.md lists nine numbered invariants, each of which was a real
     bug at some point. If your change touches one, say which and why it's
     still safe. -->

- [ ] I read CONTRIBUTING.md § Invariants and this change doesn't regress one

## Environment you tested on

- OS / arch (`uname -sm`):
- jolt version (`jolt --version`):
- GTK4 version (`pkg-config --modversion gtk4`):

## Notes for the reviewer

<!-- Anything surprising, any deliberate deviation, anything you're unsure
     about. If you verified something against real GTK behaviour rather than
     reasoning about it, say so — that's the standard here. -->
