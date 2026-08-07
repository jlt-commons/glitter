# Porting and attribution

glitter's source falls into four buckets. `NOTICE.md` (repo root) is the
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
  `gtk-widget-has-css-class`), thirteen for the display-only widgets
  `:spinner`/`:progress-bar`/`:image` (`gtk-spinner-new`,
  `gtk-spinner-set-spinning`, `gtk-spinner-get-spinning`,
  `gtk-progress-bar-new`, `gtk-progress-bar-set-fraction`,
  `gtk-progress-bar-set-text`, `gtk-progress-bar-set-show-text`,
  `gtk-progress-bar-get-fraction`, `gtk-image-new`,
  `gtk-image-new-from-icon-name`, `gtk-image-new-from-file`,
  `gtk-image-set-from-icon-name`, `gtk-image-set-from-file`,
  `gtk-image-set-pixel-size`, `gtk-image-get-icon-name`), ten more for
  `:toggle-button`/`:level-bar` (`gtk-toggle-button-new`,
  `gtk-toggle-button-new-with-label`, `gtk-toggle-button-set-active`,
  `gtk-toggle-button-get-active`, `gtk-level-bar-new`,
  `gtk-level-bar-set-value`, `gtk-level-bar-set-min-value`,
  `gtk-level-bar-set-max-value`, `gtk-level-bar-set-inverted`,
  `gtk-level-bar-get-value`), and eight more for `:link-button`/`:switch`
  (`gtk-link-button-new`, `gtk-link-button-new-with-label`,
  `gtk-link-button-set-uri`, `gtk-link-button-get-uri`,
  `gtk-widget-activate`, `gtk-switch-new`, `gtk-switch-set-active`,
  `gtk-switch-get-active`), and twenty-seven more for `:revealer`/
  `:center-box`/`:spin-button`/`:list-box` (`gtk-widget-get-parent`,
  `gtk-button-get-label`, `gtk-revealer-new`, `gtk-revealer-set-child`,
  `gtk-revealer-set-reveal-child`, `gtk-revealer-get-reveal-child`,
  `gtk-revealer-get-child-revealed`, `gtk-revealer-set-transition-type`,
  `gtk-revealer-get-transition-type`, `gtk-revealer-set-transition-duration`,
  `gtk-revealer-get-transition-duration`, `gtk-center-box-new`,
  `gtk-center-box-set-start-widget`, `gtk-center-box-get-start-widget`,
  `gtk-center-box-set-center-widget`, `gtk-center-box-get-center-widget`,
  `gtk-center-box-set-end-widget`, `gtk-center-box-get-end-widget`,
  `gtk-spin-button-new-with-range`, `gtk-spin-button-set-range`,
  `gtk-spin-button-set-value`, `gtk-spin-button-get-value`,
  `gtk-spin-button-set-digits`, `gtk-spin-button-set-increments`,
  `gtk-list-box-new`, `gtk-list-box-append`, `gtk-list-box-remove`,
  `gtk-list-box-insert`, `gtk-list-box-get-selected-row`,
  `gtk-list-box-row-get-index`, `gtk-list-box-select-row`), and twenty
  more for `:password-entry`/`:search-entry`/`:expander`/`:paned`
  (`gtk-password-entry-new`, `gtk-password-entry-set-show-peek-icon`,
  `gtk-password-entry-get-show-peek-icon`, `gtk-search-entry-new`,
  `gtk-search-entry-set-search-delay`, `gtk-search-entry-get-search-delay`,
  `gtk-expander-new`, `gtk-expander-set-label`, `gtk-expander-get-label`,
  `gtk-expander-set-expanded`, `gtk-expander-get-expanded`,
  `gtk-expander-set-child`, `gtk-paned-new`, `gtk-paned-set-start-child`,
  `gtk-paned-get-start-child`, `gtk-paned-set-end-child`,
  `gtk-paned-get-end-child`, `gtk-paned-set-position`,
  `gtk-paned-get-position`), and twenty-nine more for
  `:aspect-frame`/`:calendar`/`:overlay`/`:flow-box`
  (`gtk-aspect-frame-new`, `gtk-aspect-frame-set-child`,
  `gtk-aspect-frame-get-child`, `gtk-aspect-frame-set-xalign`,
  `gtk-aspect-frame-get-xalign`, `gtk-aspect-frame-set-yalign`,
  `gtk-aspect-frame-get-yalign`, `gtk-aspect-frame-set-ratio`,
  `gtk-aspect-frame-get-ratio`, `gtk-aspect-frame-set-obey-child`,
  `gtk-aspect-frame-get-obey-child`, `gtk-calendar-new`,
  `gtk-calendar-select-day`, `gtk-calendar-get-date`,
  `g-date-time-new-local`, `g-date-time-get-year`,
  `g-date-time-get-month`, `g-date-time-get-day-of-month`,
  `g-date-time-unref`, `gtk-overlay-new`, `gtk-overlay-set-child`,
  `gtk-overlay-get-child`, `gtk-overlay-add-overlay`,
  `gtk-overlay-remove-overlay`, `gtk-flow-box-new`,
  `gtk-flow-box-append`, `gtk-flow-box-remove`, `gtk-flow-box-insert`,
  `gtk-flow-box-child-get-index`), and twenty-three more for
  `:picture`/`:editable-label`/`:notebook`/`:scale-button`
  (`gtk-picture-new`, `gtk-picture-new-for-filename`,
  `gtk-picture-set-filename`, `gtk-picture-set-content-fit`,
  `gtk-picture-get-content-fit`, `gtk-picture-set-can-shrink`,
  `gtk-picture-get-can-shrink`, `gtk-picture-set-alternative-text`,
  `gtk-picture-get-alternative-text`, `gtk-editable-label-new`,
  `gtk-editable-label-get-editing`, `gtk-editable-label-start-editing`,
  `gtk-editable-label-stop-editing`, `gtk-notebook-new`,
  `gtk-notebook-append-page`, `gtk-notebook-insert-page`,
  `gtk-notebook-remove-page`, `gtk-notebook-page-num`,
  `gtk-notebook-set-current-page`, `gtk-notebook-get-current-page`,
  `gtk-scale-button-new`, `gtk-scale-button-set-value`,
  `gtk-scale-button-get-value`), one more for `:popover`'s suppressing-
  guard setter (`gtk-widget-get-visible`), and forty for
  `:inscription`/`:search-bar`/`:header-bar`/`:action-bar`/
  `:menu-button`/`:popover`
  (`gtk-inscription-new`, `gtk-inscription-get-text`,
  `gtk-inscription-set-text`, `gtk-inscription-get-text-overflow`,
  `gtk-inscription-set-text-overflow`, `gtk-search-bar-new`,
  `gtk-search-bar-set-child`, `gtk-search-bar-get-child`,
  `gtk-search-bar-set-search-mode`, `gtk-search-bar-get-search-mode`,
  `gtk-search-bar-set-show-close-button`,
  `gtk-search-bar-get-show-close-button`, `gtk-header-bar-new`,
  `gtk-header-bar-set-title-widget`, `gtk-header-bar-get-title-widget`,
  `gtk-header-bar-pack-start`, `gtk-header-bar-remove`,
  `gtk-header-bar-set-show-title-buttons`,
  `gtk-header-bar-get-show-title-buttons`, `gtk-action-bar-new`,
  `gtk-action-bar-pack-start`, `gtk-action-bar-set-center-widget`,
  `gtk-action-bar-get-center-widget`, `gtk-action-bar-remove`,
  `gtk-action-bar-set-revealed`, `gtk-action-bar-get-revealed`,
  `gtk-menu-button-new`, `gtk-menu-button-set-label`,
  `gtk-menu-button-get-label`, `gtk-menu-button-set-popover`,
  `gtk-menu-button-get-popover`, `gtk-popover-new`,
  `gtk-popover-set-child`, `gtk-popover-get-child`,
  `gtk-popover-set-has-arrow`, `gtk-popover-get-has-arrow`,
  `gtk-popover-set-autohide`, `gtk-popover-get-autohide`,
  `gtk-popover-popup`, `gtk-popover-popdown`), and round 11 adds
  twenty-four for `:window-handle`/`:stack`/`:drop-down`/`:grid`
  (`gtk-window-handle-new`, `gtk-window-handle-set-child`,
  `gtk-window-handle-get-child`, `gtk-stack-new`, `gtk-stack-add-child`,
  `gtk-stack-add-named`, `gtk-stack-remove`,
  `gtk-stack-get-child-by-name`, `gtk-stack-set-visible-child-name`,
  `gtk-stack-get-visible-child-name`, `gtk-string-list-new`,
  `gtk-string-list-append`, `gtk-drop-down-new`, `gtk-drop-down-set-model`,
  `gtk-drop-down-set-selected`, `gtk-drop-down-get-selected`,
  `gtk-grid-new`, `gtk-grid-attach`, `gtk-grid-remove`,
  `gtk-grid-get-child-at`, `gtk-grid-set-row-spacing`,
  `gtk-grid-get-row-spacing`, `gtk-grid-set-column-spacing`,
  `gtk-grid-get-column-spacing`), plus eight bug-fix bindings for the
  ctor/apply audit (`gtk-checkbutton-set-label`,
  `gtk-scale-button-get-adjustment`, `gtk-adjustment-configure`,
  `gtk-adjustment-get-lower`, `gtk-adjustment-get-upper`,
  `gtk-adjustment-get-step-increment`, `gtk-range-get-adjustment`,
  `gtk-spin-button-get-adjustment`) — see
  [`gtk-widget-layer.md`](gtk-widget-layer.md#the-ctorapply-audit--four-real-previously-shipped-bugs),
  and one more binding added while verifying `examples/glitter/crud.clj`
  (`gtk-widget-get-sensitive` — every prior use of `:sensitive` only
  ever set it; this is the first live check of its own read-back).
- `glitter.widget` — forked from `glimmer.widget`, plus `insert-child-after!`,
  `signal-name`, `signal-value-fn`, `suppressing?`, `set-scale-value!` and
  the `:scale` widget spec (a first-party demonstration of the
  value-bearing custom-signal path — see
  [`gtk-widget-layer.md`](gtk-widget-layer.md#scale--the-first-party-value-bearing-custom-signal)),
  the display-only `:spinner`/`:progress-bar`/`:image`/`:level-bar` widget
  specs (no signal wiring — driven entirely by re-applied props),
  `set-toggle-button-active!` + the `:toggle-button` widget spec and
  `link-button-spec`/`:link-button` (both reuse an existing `signals`
  entry verbatim — see
  [`gtk-widget-layer.md`](gtk-widget-layer.md#toggle-button--reusing-toggled-for-a-second-gtk4-class)),
  `set-switch-active!` + the `:switch` widget spec + the new
  `:on-state-set` signal entry (the widget that needed
  `glitter.gtk/set-event-handler` itself generalized — see
  [`gtk-widget-layer.md`](gtk-widget-layer.md#switch--generalizing-set-event-handler)),
  `revealer-spec`/`:revealer` (display-only, single-child container — see
  [`gtk-widget-layer.md`](gtk-widget-layer.md#revealer--a-free-single-child-container-reuse-plus-a-props-driven-widget)),
  `center-box-spec`/`:center-box` + `center-box-append-child!`/
  `center-box-remove-child!`/`center-box-replace-child!`/
  `center-box-insert-after!` (a genuinely new container strategy — 3 fixed
  named slots, not an ordered list — see
  [`gtk-widget-layer.md`](gtk-widget-layer.md#center-box--a-genuinely-new-container-strategy-and-a-real-v1-gap)),
  `set-spin-button-value!`/`spin-button-spec`/`:spin-button` (see
  [`gtk-widget-layer.md`](gtk-widget-layer.md#spin-button--generalizing-signal-value-by-tag)),
  and `list-box-spec`/`:list-box` + `list-box-row-of`/
  `list-box-remove-child!`/`list-box-replace-child!`/
  `list-box-index-after`/`list-box-insert-after!`/
  `list-box-reorder-child!`/`list-box-selected-index` + the new
  `:on-row-selected`/`:on-row-activated` signal entries (a third
  generalized `set-event-handler` callable shape, plus two more real bugs
  found and fixed along the way — see
  [`gtk-widget-layer.md`](gtk-widget-layer.md#list-box--a-third-callable-shape-and-two-more-real-bugs))
  as new public accessors, and four behavioral deviations: (1)
  `replace-child!`'s `:box` branch captures the old child's previous
  sibling via `gtk_widget_get_prev_sibling` and re-inserts via
  `gtk_box_insert_child_after`, where glimmer used `gtk_box_remove` +
  `gtk_box_append` — `append` always lands at the *end* of the box, which
  silently relocates any non-final child; (2) `signal-value` is keyed by
  `[tag gtk-signal-name]`, not bare signal name — `:spin-button` and
  `:scale` emit the identical `"value-changed"` signal but need different
  getters; (3) `insert-child-after!`/`reorder-child!` are no longer
  `:box`-only no-ops — `:list-box` needed both genuinely implemented,
  `:center-box` gets `insert-child-after!` but `reorder-child!` stays a
  structural no-op; (4) `list-box-remove-child!`/`list-box-replace-child!`/
  `list-box-reorder-child!` suppress on the list-box widget around
  `gtk_list_box_remove` — removing the currently-selected row fires a
  real, synchronous `"row-selected(NULL)"` GTK signal that would
  otherwise reach app dispatch.

  Round 7 adds `password-entry-spec`/`:password-entry` +
  `search-entry-spec`/`:search-entry` (both reuse `:entry`'s
  `GtkEditable`-delegate `"changed"` signal *name*, but each still needed
  its own `signal-value` entry under `[tag "changed"]` — reusing
  `:entry`'s registration doesn't work once `signal-value` is keyed by
  tag; `:search-entry` also gets its own new `:on-search-changed` — see
  [`gtk-widget-layer.md`](gtk-widget-layer.md#password-entrysearch-entry--free-gtkeditable-reuse-and-a-signal-value-miss)),
  `set-expander-expanded!`/`expander-spec`/`:expander` (single-child
  container, no dedicated signal — `:on-expanded` watches
  `"notify::expanded"` instead), and `set-paned-position!`/`paned-spec`/
  `:paned` + `paned-append-child!`/`paned-slot-setter`/
  `paned-remove-child!`/`paned-replace-child!`/`paned-insert-after!` (a
  second new named-slot container strategy — 2 slots this time —
  `:on-position-changed` watches `"notify::position"`; both `:expander`'s
  and `:paned`'s `notify::*` signals reuse `:list-box`'s generalized
  3-arg-void `set-event-handler` shape for free, and `:paned` inherits
  `:center-box`'s structural v1 gap with a verified-DIFFERENT failure
  shape — see
  [`gtk-widget-layer.md`](gtk-widget-layer.md#expanderpaned--free-signal-reuse-and-a-second-structural-gap)).

  Round 8 adds `aspect-frame-spec`/`:aspect-frame` (single-child
  container, same strategy as `:frame`/`:scrolled`/`:revealer`/
  `:expander` — quick win, see
  [`gtk-widget-layer.md`](gtk-widget-layer.md#aspect-framecalendar--a-quick-win-and-a-genuinely-new-value-type)),
  `calendar-date=`/`set-calendar-date!`/`calendar-spec`/`:calendar` + the
  new `:on-day-selected` signal (this project's first `GDateTime`-
  refcounted value type — same anchor as `:aspect-frame` above),
  `overlay-spec`/`:overlay` + `overlay-append-child!`/
  `overlay-remove-child!`/`overlay-replace-child!`/`overlay-insert-after!`
  (a THIRD new container strategy — one queryable main slot plus an
  unbounded, unenumerable overlay set — see
  [`gtk-widget-layer.md`](gtk-widget-layer.md#overlayflow-box--a-third-container-shape-and-a-verified-difference-not-an-assumption)),
  and `flow-box-spec`/`:flow-box` + `flow-box-child-of`/
  `flow-box-index-after`/`flow-box-insert-after!`/`flow-box-replace-child!`/
  `flow-box-reorder-child!` + the new `:on-child-activated` signal (a
  `:list-box` sibling verified to NOT share `gtk_list_box_remove`'s
  gotcha — same anchor as `:overlay` above).

  Round 9 adds `editable-label-spec`/`:editable-label` +
  `set-editable-label-editing!` (a THIRD `GtkEditable`-delegate reuse,
  after `:password-entry`/`:search-entry` — repeated the exact
  `signal-value`-miss near-miss from round 7 despite a comment saying
  it would be avoided, caught only by live probe testing; investigating
  it surfaced a general `GtkEditable` finding, not specific to this
  widget — see
  [`gtk-widget-layer.md`](gtk-widget-layer.md#pictureeditable-label--a-quick-win-a-third-gtkeditable-delegate-and-a-general-gtkeditable-finding)),
  `picture-spec`/`:picture` (display-only, no signal — same anchor as
  `:editable-label` above), `notebook-spec`/`:notebook` +
  `notebook-append-child!`/`notebook-remove-child!`/
  `notebook-replace-child!`/`notebook-index-after`/
  `notebook-insert-after!`/`notebook-reorder-child!` +
  `set-notebook-current-page!` + the new `:on-switch-page` signal (a
  SIXTH generalized `set-event-handler` callable shape, the first that
  reads its own raw signal argument instead of a getter, and a verified
  real mount-time auto-dispatch — see
  [`gtk-widget-layer.md`](gtk-widget-layer.md#notebookscale-button--a-sixth-callable-shape-a-mount-time-surprise-and-a-tag-aware-dispatch)),
  and `scale-button-spec`/`:scale-button` + `set-scale-button-value!` (a
  THIRD widget sharing `:scale`'s/`:spin-button`'s `"value-changed"`
  signal name, the first case needing a TAG-aware, not just signal-
  name-keyed, `set-event-handler` dispatch — same anchor as `:notebook`
  above).

  Round 10 adds `inscription-spec`/`:inscription` and
  `search-bar-spec`/`:search-bar` (both entirely display/props-driven,
  no signal, no `glitter.gtk` changes needed at all — see
  [`gtk-widget-layer.md`](gtk-widget-layer.md#inscriptionsearch-bar--two-more-quick-no-signal-wins)),
  `header-bar-spec`/`:header-bar` + `action-bar-spec`/`:action-bar` +
  `header-bar-append-child!`/`header-bar-remove-child!`/
  `header-bar-replace-child!`/`header-bar-insert-after!` +
  `action-bar-append-child!`/`action-bar-remove-child!`/
  `action-bar-replace-child!`/`action-bar-insert-after!` (a genuinely
  new HYBRID container shape — one named title/center-widget slot plus
  an ORDERED pack-start list — that surfaced two real findings: both
  widgets' `pack_end` silently reverses hiccup order, confirmed
  independently for each rather than assumed to carry over, so v1 only
  wires `pack_start`; and toggling `:show-title-buttons` prepends GTK's
  own native window-controls widget into the SAME pack-start region —
  see
  [`gtk-widget-layer.md`](gtk-widget-layer.md#header-baraction-bar--a-genuinely-new-hybrid-container-shape)),
  and `menu-button-spec`/`:menu-button` + `popover-spec`/`:popover` +
  `set-popover-visible!` + the new `:on-closed` signal entry (the
  FIRST popup surface in this project and the first hiccup
  relationship that isn't a normal append-child!-managed tree child —
  `:menu-button`'s ONE hiccup child, if present, is attached via
  `gtk_menu_button_set_popover` rather than any container-management
  case branch reused from an existing widget; both `:on-activate` and
  `:on-closed` turned out to be free reuses of the default 2-arg-void
  callable shape, needing zero `glitter.gtk` changes — see
  [`gtk-widget-layer.md`](gtk-widget-layer.md#menu-buttonpopover--a-popup-surface-not-a-normal-tree-child)).

  Round 11 adds a ctor/apply audit that found and fixed four real,
  previously-shipped bugs — `checkbutton-spec`'s never-applied
  `:label`, and `scale-spec`/`spin-button-spec`/`scale-button-spec`'s
  `:min`/`:max`/`:step` silently clobbering each other across
  single-key re-renders — see
  [`gtk-widget-layer.md`](gtk-widget-layer.md#the-ctorapply-audit--four-real-previously-shipped-bugs);
  `window-handle-spec`/`:window-handle` (single-child, no signal —
  quick win that also caught a missing-case-branch bug in this
  round's own new code — see
  [`gtk-widget-layer.md`](gtk-widget-layer.md#window-handle--a-quick-win-and-a-bug-in-this-rounds-own-code));
  `set-stack-visible-child-name!`/`stack-spec`/`:stack` + the new
  `:on-visible-child-changed` signal entry (a THIRD mount-time-auto-
  dispatch instance, plus a real `:apply`-timing gap fixed at the
  warning level only — see
  [`gtk-widget-layer.md`](gtk-widget-layer.md#stack--a-third-mount-time-auto-dispatch-instance-and-a-real-apply-timing-gap));
  `set-drop-down-selected!`/`drop-down-build-model!`/`drop-down-spec`/
  `:drop-down` + the new `:on-selected-changed` signal entry (the first
  "choose from options" widget, built on an incrementally-constructed
  `GtkStringList` — see
  [`gtk-widget-layer.md`](gtk-widget-layer.md#drop-down--the-first-choose-from-options-widget));
  and `grid-attach!`/`stack-append-child!`/`grid-spec`/`:grid` (the
  first container whose child placement is driven entirely by the
  child's own hiccup props, via the new `:glitter/structural-props`
  mechanism — see
  [`gtk-widget-layer.md`](gtk-widget-layer.md#glitterstructural-props--a-childs-props-read-by-its-parent)).
  `append-child!`/`remove-child!`/`replace-child!`/`insert-child-after!`
  each gained new `:grid`/`:stack`/`:window-handle` `case` branches, and
  the first three of those four functions gained an optional trailing
  `structural-props` argument threaded from `glitter.gtk` (see Bucket 3
  below).

  A post-round-11 fix, found while building `examples/glitter/crud.clj`:
  `list-box-reorder-child!`/`flow-box-reorder-child!` were reusing a
  widget pointer GTK had already disposed — `gtk_list_box_remove`/
  `gtk_flow_box_remove` dispose the now-unreferenced wrapping row/
  child, and BOTH wrappers' own `dispose` handlers unparent (and,
  with nothing else referencing it, finalize) their own child in turn
  (confirmed by reading `gtk_list_box_row_dispose`'s and
  `gtk_flow_box_child_dispose`'s C bodies directly). Fixed by
  bracketing the remove-then-reinsert in both functions with
  `g-object-ref-sink`/`g-object-unref` — the first actual call site
  for either binding in this codebase (both were already bound,
  inherited from glimmer's fork, but never previously used). See
  [`gtk-widget-layer.md`](gtk-widget-layer.md#list-box-reorder-child-flow-box-reorder-child--a-real-use-after-dispose-bug).

  See [`gtk-widget-layer.md`](gtk-widget-layer.md) for why all of this matters.
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
  [`gtk-widget-layer.md`](gtk-widget-layer.md). Round 11 adds
  `structural-child-props`/`structural-child-prop?` and special-cases
  them in `set-attribute`/`remove-attribute` — a namespaced-prop
  finding (`:grid/column`-style keys are silently dropped by
  `glitter.core`'s own `set-attr`/`update-attr` guard, upstream of
  `IRender` entirely) forced these onto plain, hyphenated keys instead
  — stashing matches on the CHILD's own `el` atom under
  `:glitter/structural-props` rather than routing them through
  `glitter.widget/apply-props!`. `append-child`, `insert-before`'s
  fresh-insert branch, and `replace-child` all thread that stashed map
  through to `glitter.widget`'s container-management functions as a new
  optional trailing argument — see
  [`gtk-widget-layer.md`](gtk-widget-layer.md#glitterstructural-props--a-childs-props-read-by-its-parent).
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

## Bucket 4: ported from nexus

The following files under `src/glitter/` are ported from
[nexus](https://github.com/cjohansen/nexus), commit
`5f6c93672f25d2a5b2a91ac3b65a921ecf8826b2`, by Christian Johansen,
Magnar Sveen, and Teodor Heggelund. MIT License — same terms as the
Replicant bucket above (see NOTICE.md for the full text).

- `src/glitter/nexus.clj` — `src/nexus/core.cljc`. One deliberate
  deviation: the three `#?(:clj Exception :cljs :default)`
  reader-conditionals collapse to a plain `Exception` catch (glitter
  targets Jolt only, no cljs).
- `src/glitter/nexus/registry.clj` — `src/nexus/registry.cljc`.
  Byte-for-byte, zero deviations.
- `src/glitter/nexus/action_log.clj` — a CONCEPT port, not a literal
  file: nexus's log-accumulation logic now lives inside
  `nexus.inspector.cljc`, entangled with `dataspex.*` rendering-protocol
  implementations with no glitter/GTK equivalent. This file ports the
  accumulation mechanism (the same nested `:entries`/`:chronology` tree)
  and drops every `dp/*`/dataspex call site. Two adaptations: `now` uses
  `tick.core/now` instead of `java.util.Date.` (jolt.time is already a
  project dependency); `find-event` reads `:glitter/dom-event` directly
  instead of hunting through `dispatch-data`'s values for a DOM `Event`
  instance, since glitter's `dispatch-data` always IS the event map.

Unlike Replicant/glimmer, nexus is a genuinely separate library (not
glitter's own reconciler or its widget-layer fork) — glitter depends on
it conceptually the way an application depends on a dispatch library,
which is why this is its own bucket rather than folded into Bucket 1 or
3. See [`nexus.md`](nexus.md) for the architecture this enables.

## Keeping `NOTICE.md` current

Any new ported/forked file, or any new deviation in an already-ported file,
gets a line added to the relevant bucket in `NOTICE.md` in the same commit
as the code change — not as a follow-up. That file is what a downstream
consumer or license auditor actually reads; this guide page is context for
contributors, not a substitute.
