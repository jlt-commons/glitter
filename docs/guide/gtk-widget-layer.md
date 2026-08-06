# The GTK widget layer

`glitter.widget` maps hiccup tags to GTK widget constructors and prop
appliers; `glitter.gtk` drives it from the `IRender`/`IMemory` protocols.
This page covers the mechanics and the specific GTK4 API traps this
project hit — each is a real, live-verified bug this codebase used to have.

## The widget registry

```clojure
(def specs
  (atom {:window (window-spec) :box (box-spec) :button (button-spec)
         :label (label-spec) :entry (entry-spec) :checkbutton (checkbutton-spec)
         :separator (separator-spec) :frame (frame-spec) :scrolled (scrolled-spec)}))
```

Each spec is `{:ctor (fn [props] widget) :apply (fn [widget props]) :container kw}`.
`:container` determines how children attach: `:box` (ordered
append/remove/reorder), `:window`/`:frame`/`:scrolled` (single child), or
`:none` (leaf). `register-widget!` lets extensions (e.g. a hypothetical
`:gl-area`/`:scale` addition) add new tags without editing this namespace;
`register-signal!` does the same for new `:on-*` event keys, optionally
with a `value-fn` for value-bearing signals (a slider's `"value-changed"`
delivering the current double, for instance — `"changed"` on an entry
already does this, reading the text via `gtk_editable_get_text`).

`create!` builds a widget end-to-end: construct, apply props, apply the
universal `GtkWidget`-level props (`apply-widget-props!` — margins, halign/
valign, hexpand/vexpand, size requests), connect any direct `:on-*` props
via `connect-signals!`, then run an optional `:connect` closure for signals
that don't fit the uniform `void(widget, user_data)` shape. `apply-props!`
re-applies a (possibly partial) prop map to an existing widget on re-render
— it's safe to call with a single-key map like `{:label "new text"}`,
because only keys *present* in the map are touched; absent keys are never
reset to a default.

## Signal lifecycle: why glitter connects/disconnects itself

glimmer's `connect-signals!` connects each signal **once**, at `create!`
time, and never disconnects — correct for its Reagent-style model, where
the handler closure captures a ratom and the closure itself never goes
stale.

glitter's handlers are data (see
[`architecture.md`](architecture.md#event-dispatch-data-not-closures)), and
`glitter.core`'s diff calls `IRender/set-event-handler` again whenever the
handler *data* changes between renders — not just on mount/unmount. So
`glitter.gtk`'s `IRender/set-event-handler` connects directly (bypassing
`connect-signals!`, which never exposes the connection id a real disconnect
needs) and tracks `{:id <signal connection id> :cb <retained
foreign-callable>}` per event on the element atom:

```clojure
(set-event-handler [_ el event handler _opt]
  (when-let [{:keys [id cb]} (get-in @el [:handlers event])]
    (g/g-signal-handler-disconnect (ptr el) id)
    (w/release-callable! cb)
    (swap! el update :handlers dissoc event))
  (when-let [signal (w/signal-name (keyword (str "on-" (name event))))]
    (let [value-fn (w/signal-value-fn signal)
          cb (jolt.ffi/foreign-callable
              (fn [src-widget _data]
                (when-not (w/suppressing? src-widget)
                  (handler (cond-> {:glitter/node el :glitter/gtk-widget src-widget}
                             value-fn (assoc :glitter/value (value-fn src-widget))))))
              [:pointer :pointer] :void :collect-safe)
          id (g/g-signal-connect-data (ptr el) signal cb jolt.ffi/null jolt.ffi/null g/CONNECT-DEFAULT)]
      (w/retain-callable! cb)
      (swap! el assoc-in [:handlers event] {:id id :cb cb})))
  nil)
```

Both parts matter: disconnecting the *signal* alone still leaves the
foreign-callable pinned in `glitter.widget`'s retain set forever — an
unbounded leak for any handler whose data changes across renders. Both
`id` and `cb` are tracked so both can be released together.

**Reading a value-bearing signal's value back out.** The `:glitter/value`
built above travels through `glitter.core`'s `build-event-map`, which wraps
the whole object under `:glitter/dom-event` before handing it to
`*dispatch*`. So a handler that needs the live value (an entry's
`:change`, say) reads it from the *first* dispatch argument, not from its
own static action data — hiccup `:on` data is fixed at the moment `view`
runs, so an action tuple can't carry a value that only exists once the
user types:

```clojure
(defn execute-actions [event actions]
  (doseq [[kind] actions]
    (case kind
      :action/set-draft (swap! state assoc :draft
                                (get-in event [:glitter/dom-event :glitter/value]))
      ...)))
```

Verified live (typing `"hello"` into an entry produces exactly this shape
at `*dispatch*`):

```clojure
#:glitter{:trigger :glitter.trigger/dom-event
          :dom-event #:glitter{:node #object[...] :gtk-widget 41299379472 :value "hello"}
          :node #object[...]
          :dispatch #object[...]
          :js-event #:glitter{:node #object[...] :gtk-widget 41299379472 :value "hello"}}
```

`examples/glitter/todo.clj`'s `:action/set-draft` is the live example.

`suppressing?` guards against a second failure mode: `glitter.widget`'s
programmatic setters (`set-entry-text!`, `set-checkbutton-active!`) only
touch the widget when the new value differs from its *current* value, and
bracket the actual GTK call with a `suppressing` set so the synchronous
signal emission GTK fires during the setter doesn't loop back into a
dispatch. Because `glitter.gtk` connects its own signals directly rather
than through `connect-signals!`, it has to consult this same guard itself
— without it, a purely programmatic value change (e.g. re-syncing an entry
after an external state update) would fire a real user-facing dispatch
that nothing actually triggered.

## `insert-before`: two cases GTK doesn't unify

`IRender/insert-before` is called for two genuinely different situations,
mirroring DOM's own `insertBefore` auto-move semantics:

1. A brand-new child, never yet parented anywhere.
2. Repositioning a child that's **already** a child of the same parent — a
   keyed reconciliation moving an existing row.

DOM's `insertBefore` handles both uniformly (inserting an already-attached
node moves it). GTK does not: `gtk_box_insert_child_after` asserts its
child argument is **unparented**
(`gtk_widget_get_parent(child) == NULL`) and throws a `GTK-CRITICAL` +
silently no-ops when called on an already-parented child — exactly what a
keyed reorder does. The real GTK API for repositioning an existing child is
`gtk_box_reorder_child_after` (already correct for this case, ported
verbatim from glimmer's `reorder-child!`).

So `glitter.gtk`'s `insert-before` branches on whether `child-node` is
already tracked in the parent's `:children`:

```clojure
(insert-before [_ el child-node reference-node]
  (let [cs (:children @el)
        idx (.indexOf cs reference-node)
        prev-sibling (when (pos? idx) (ptr (nth cs (dec idx))))]
    (if (some #(= % child-node) cs)
      (w/reorder-child! (:tag @el) (ptr el) (ptr child-node) prev-sibling)
      (w/insert-child-after! (:tag @el) (ptr el) (ptr child-node) prev-sibling)))
  (swap! el update :children
         (fn [cs]
           (let [without (vec (remove #(= % child-node) cs))
                 idx (.indexOf without reference-node)]
             (into (conj (subvec without 0 idx) child-node) (subvec without idx)))))
  nil)
```

The bookkeeping formula (remove `child-node` from wherever it currently
sits, then re-splice it immediately before `reference-node`) is correct for
*both* cases with no further branching, because repositioning one child in
GTK never changes any other child's tree position — `prev-sibling`,
computed from the pre-removal `:children`, stays a valid physical anchor
regardless of where `child-node` itself used to sit.

This exact bug class recurs in `glitter.test-renderer`'s fake
`insert-before` too (a keyed move there used to leave a stale duplicate
entry in `:children` instead of relocating it) — fixed with the identical
remove-then-resplice formula, since the fake renderer's job is to mirror
real reconciler bookkeeping even though it never touches actual GTK.

`examples/glitter/keyed.clj` pins this against the *live* GTK tree — it
reads back the actual widget order via
`gtk_widget_get_first_child`/`gtk_widget_get_next_sibling`, not glitter's
own `:children` tracking, to prove the real `gtk_box_insert_child_after`
calls landed correctly and not just that the algorithm's decisions were
correct in the abstract.

## `replace-child!`: capture the anchor before you remove it

glimmer's original `:box` branch did `gtk_box_remove` then `gtk_box_append`
— which always inserts at the **end** of the box. Replacing a non-final
child silently relocated it there, desyncing every consumer's positional
tracking (verified live during this project's final whole-branch review).

The fix captures the old child's previous sibling **before** removing it
(removal loses that information), then inserts the new child at that same
anchor:

```clojure
(replace-child! [parent-tag parent old-child new-child]
  (case (container-kind parent-tag)
    :box (let [prev (g/gtk-widget-get-prev-sibling old-child)
               prev (when-not (or (nil? prev) (zero? prev)) prev)]
           (g/gtk-box-remove parent old-child)
           (g/gtk-box-insert-child-after parent new-child (or prev ffi/null)))
    ...))
```

`gtk_widget_get_prev_sibling` returning null/0 means "was already the
first child" — `insert-child-after` with a null sibling arg inserts at the
front, which is exactly the desired position. `examples/glitter/replace_child.clj`
pins this.

## `:scale` — the first-party value-bearing custom signal

Every widget so far routes `:on {:click ...}`-style handlers through
`glitter.widget`'s pre-registered `signals`/`signal-value` tables
(`:on-click`, `:on-change`, `:on-activate`, `:on-toggled`). `:scale` (a
slider, `GtkScale`/`GtkRange`) adds a fifth, `:on-value-changed ->
"value-changed"`, and is the first widget beyond entry's built-in
`:change` to carry a value through `signal-value`'s value-fn mechanism —
concretely exercising the extension path `register-signal!`'s own
docstring describes for third parties, using the exact same registration
shape (baked into the initial atom literals rather than a runtime
`register-signal!` call, matching the other four):

```clojure
(def signals
  (atom {:on-click          "clicked"
         :on-change         "changed"
         :on-activate       "activate"
         :on-toggled        "toggled"
         :on-value-changed  "value-changed"}))

(def ^:private signal-value
  (atom {"changed"       (fn [widget] (g/gtk-editable-get-text widget))
         "value-changed" (fn [widget] (g/gtk-range-get-value widget))}))
```

`gtk_scale_new_with_range(orientation, min, max, step)` constructs its own
internal `GtkAdjustment` — no separate adjustment binding was needed. The
same construct-before-`GtkOrientation`-is-registered concern `box-spec`/
`separator-spec` already handle applies here too (see
[`architecture.md`](architecture.md)'s discussion of enum resolution
timing): `:ctor` builds horizontal (the raw `0` — verified against `gtk/
gtkenums.h`'s `GtkOrientation` ordinal, `GTK_ORIENTATION_HORIZONTAL` is the
first member) and `:apply` corrects it via `->orientation` once the widget
exists.

`set-scale-value!` follows `set-entry-text!`/`set-checkbutton-active!`'s
established set-compare-suppress shape exactly — set only when the value
actually differs, bracketed by the `suppressing` guard so the reconciler
feeding `:value` back on every render can't loop
`set_value -> value-changed -> dispatch -> re-render -> set_value`:

```clojure
(defn- set-scale-value! [widget value]
  (when (and (some? value) (not= (double value) (g/gtk-range-get-value widget)))
    (swap! suppressing conj widget)
    (g/gtk-range-set-value widget (double value))
    (swap! suppressing disj widget)))
```

Verified live end-to-end (`examples/glitter/scale_smoke.clj`, which pins
all three): a real drag (simulated via a direct `gtk_range_set_value` FFI
call, bypassing `set-scale-value!` so the actual `"value-changed"` signal
fires) reaches `*dispatch*` with the correct double at
`(get-in event [:glitter/dom-event :glitter/value])`; a subsequent
state-driven re-render pushes the new value back onto the live widget; and
that programmatic push does **not** trigger a second, spurious dispatch —
confirming the suppressing guard works for this widget exactly as it does
for entry and checkbutton.

## `:spinner`/`:progress-bar`/`:image` — display-only widgets need no signal

Three widgets with a genuinely simpler shape than everything above:
`:spinner` (an indeterminate "loading" indicator, one boolean prop
`:spinning`), `:progress-bar` (`:fraction`/`:text`/`:show-text`), and
`:image` (`:icon-name`/`:file`/`:pixel-size`). None of them have a signal
worth wiring — they exist purely to *display* state the application
already tracks elsewhere, so `:apply` re-applying whatever props are
present on every render is the entire implementation:

```clojure
(defn- spinner-spec []
  {:ctor  (fn [_] (g/gtk-spinner-new))
   :apply (fn [w p] (when (contains? p :spinning) (g/gtk-spinner-set-spinning w (->bool (:spinning p)))))
   :container :none})
```

`:image`'s `:ctor` picks whichever of `:icon-name`/`:file` is present at
construction time (falling back to the empty constructor), and `:apply`
re-applies either key on a later render — no explicit "clear the old
content" step is needed, because `GtkImage`'s internal storage type
(icon name vs. file vs. paintable) switches automatically to whatever
setter was called most recently.

Verified live (`examples/glitter/leaf_widgets_smoke.clj`): all three
widgets' construction-time props land correctly, and re-rendering with
different values (a different fraction, a different icon name, spinning
flipped off) is reflected in real GTK state — read back via
`gtk_spinner_get_spinning`/`gtk_progress_bar_get_fraction`/
`gtk_image_get_icon_name`, not glitter's own bookkeeping.

## Surveyed and deliberately not added: `GtkSwitch`

`GtkSwitch` looked like a fourth simple leaf widget from its construction
API alone (`gtk_switch_new`, `gtk_switch_set_active`/`get_active` — just
as trivial as checkbutton's). Its *interaction* signal is where it stops
fitting the pattern. Checked directly against `gtk/gtkswitch.c`'s
`g_signal_new` calls: `GtkSwitch` has exactly two signals.

- `"activate"` — the simple `void(widget, user_data)` shape every other
  glitter signal uses, but GTK's own doc comment on it is explicit:
  *"Emitted to animate the switch. Applications should never connect to
  this signal, but use the [property@Gtk.Switch:active] property."* It
  fires on keyboard activation (Space/Enter) only, not on a mouse click,
  and using it to detect interaction would silently miss the common case
  while looking like it worked.
- `"state-set"` — the actual interaction signal, but its C signature is
  `gboolean (*state_set) (GtkSwitch *widget, gboolean state, gpointer
  user_data)`: **three** arguments (not two) and a **`gboolean` return**
  (not `void`) that GTK uses to decide whether to run its own default
  handler. `glitter.gtk`'s `set-event-handler` hardcodes every signal's
  foreign-callable to `[:pointer :pointer] :void` — this doesn't fit, and
  wiring it correctly would mean either generalizing that callable shape
  (real new architecture, not a small addition) or connecting a
  differently-shaped callable as a one-off special case for this single
  widget.

The `:connect` key in a widget spec (see `register-widget!`'s docstring:
*"for widgets whose signals don't fit the uniform void(widget,data)
shape, e.g. a GtkGLArea's realize/render/resize"*) looks like an escape
hatch, but doesn't actually solve this: `:connect` runs **once at mount**
with whatever `props` were current then, the same one-time-connection
model `connect-signals!` already uses for `create!`'s direct-props path.
`:switch`'s handler needs the same reconciler-driven re-wiring
`set-event-handler`/`remove-event-handler` give every other interactive
widget (so a handler *closure* never goes stale when the action data
changes between renders, without the widget's identity changing) — and
`:connect` doesn't provide that.

Shipping `:switch` state-settable-but-non-interactive (readable via
`:active`, but never dispatching back on user toggle) was considered and
rejected as a quiet half-measure: it would look identical to `:checkbutton`
in hiccup but silently not round-trip, a worse trap than not shipping it
at all. Left as an explicitly open decision (see `AGENTS.md`'s Scope
section) rather than resolved either way.

**Resolved two rounds later** — see
["`:switch` — generalizing `set-event-handler`"](#switch--generalizing-set-event-handler)
below. This section is kept as-written rather than rewritten: the
investigation and the "not worth a quiet half-measure" judgment call were
both correct and are exactly the reasoning that later justified the real
architecture work.

## `:toggle-button` — reusing `"toggled"` for a second GTK4 class

`GtkToggleButton` is the widget `:switch` isn't: checked its actual
signals in `gtk/gtktogglebutton.c` (the same way `:switch`'s were
checked) before assuming anything, and found exactly one,
`void (*toggled) (GtkToggleButton*)` — the identical
`void(widget, user_data)` shape already registered as `:on-toggled` for
`:checkbutton`. No new entry in `signals`/`signal-value` was needed at
all; `glitter.gtk`'s existing `set-event-handler` handles `:toggle-button`
exactly the way it already handles `:checkbutton`, because the lookup is
by hiccup event key + registered GTK signal name, not by widget type.

`GtkToggleButton` and `GtkCheckButton` are unrelated classes in GTK4 (they
shared a type hierarchy pre-GTK4; that relationship was removed), so
`toggle-button-spec` needs its own `gtk_toggle_button_set_active`/
`get_active` FFI pair and its own `set-toggle-button-active!` — but the
helper is otherwise a byte-for-byte structural copy of
`set-checkbutton-active!`, same set-compare-suppress shape:

```clojure
(defn- set-toggle-button-active! [widget active?]
  (let [target (->bool active?)]
    (when (not= target (g/gtk-toggle-button-get-active widget))
      (swap! suppressing conj widget)
      (g/gtk-toggle-button-set-active widget target)
      (swap! suppressing disj widget))))
```

Verified live end-to-end (`examples/glitter/toggle_level_smoke.clj`, same
three-part rigor as `:scale`'s smoke): a real click (direct FFI
`gtk_toggle_button_set_active`, bypassing `set-toggle-button-active!` so
the actual `"toggled"` signal fires) reaches `*dispatch*` and updates
state; a subsequent programmatic `reset!` pushes the widget back in sync;
and that programmatic push does **not** trigger a second, spurious
dispatch. This is what actually proves the *reuse* works — not just that
`:checkbutton`'s original wiring works, which was already known.

`:level-bar` (a gauge/indicator — `:value`/`:min-value`/`:max-value`/
`:inverted`) shipped alongside it in the same pass, same display-only
shape as `:spinner`/`:progress-bar`/`:image`: no signal, `:apply`
re-applies whatever props are present on every render, min/max set before
value so a caller-supplied range is live before the value meant to land
inside it (same ordering concern `:scale`'s `:apply` already has for
re-ranging before setting `:value`). `:mode` (continuous vs. discrete
segments) is out of scope for v1, matching `:progress-bar`'s minimal
display-widget scope.

## `:link-button` — a second free signal reuse, plus a real GTK4 timing gotcha

`GtkLinkButton` is the same story as `:toggle-button`, one level up:
`gtk/gtklinkbutton.h` `#include`s `gtk/gtkbutton.h` — the standard
GTK header pattern for a parent-class include — confirming `GtkLinkButton`
genuinely *extends* `GtkButton`, not just resembles it. It inherits
`"clicked"` for free: `:link-button` needed no `signals`/`signal-value`
entry at all, only its own `gtk_link_button_new`/`_with_label`/
`set_uri`/`get_uri` FFI calls and a `link-button-spec` that reuses
`gtk_button_set_label`/`gtk_widget_set_tooltip_text`/
`gtk_widget_set_sensitive` directly, since those are inherited
`GtkButton`/`GtkWidget` methods `button-spec` already calls.

Writing this widget's smoke surfaced a genuine GTK4 timing gotcha, worth
pinning precisely because it would silently break any future test (or
application code) that tries to simulate a button click programmatically.
`gtk_widget_activate` — the public function that simulates a real
Enter/Space key activation — does **not** synchronously emit `"clicked"`
for a `GtkButton`. Traced through `gtk/gtkbutton.c`:

```c
#define ACTIVATE_TIMEOUT 250
...
static void
gtk_real_button_activate (GtkButton *button)
{
  ...
  if (gtk_widget_get_realized (widget) && !priv->activate_timeout)
    {
      priv->activate_timeout = g_timeout_add_once (ACTIVATE_TIMEOUT, button_activate_timeout, button);
      ...
    }
}
```

Two traps stacked here, both found live rather than assumed:

1. **`"clicked"` fires ~250ms later**, via a `g_timeout_add_once` — the
   press-animation delay. `gtk_widget_activate` returns before that
   timeout runs, so checking any dispatched result immediately after
   calling it reads stale state. `examples/glitter/link_button_smoke.clj`
   defers its check via a `future` + `Thread/sleep 400` + `app/on-gui`,
   the same cross-thread-marshalling primitives
   `main_thread_smoke.clj` already established, applied to a different
   timing problem (there, marshalling *onto* the main thread from a
   worker; here, giving the main thread's own event sources time to run).
2. **The timeout is only scheduled `if (gtk_widget_get_realized
   (widget) ...)`.** `glitter.app/run*`'s `:activate` handler calls
   `on-activate` (where `gtk/mount!` and any smoke-test interaction code
   runs) *before* `gtk_window_present` — so activating a button
   immediately inside `on-activate` is a **silent no-op**: `gtk_widget_
   activate` still returns `TRUE` (that only means "an activate-signal
   handler ran," not "clicked will follow"), but the widget isn't
   realized yet, so the whole timeout-scheduling branch is skipped and
   `"clicked"` never fires at all. The smoke defers the *activate call
   itself* (not just the check) via the same future, giving the window
   time to present and realize first.

## `:switch` — generalizing `set-event-handler`

`GtkSwitch` was
[surveyed and deliberately not added](#surveyed-and-deliberately-not-added-gtkswitch)
two widget-additions ago, specifically because its interaction signal,
`"state-set"`, doesn't fit the uniform `void(widget, user_data)` shape
every other glitter signal's `foreign-callable` uses — confirmed against
`gtk/gtkswitch.c`'s `g_signal_new` call: `gboolean (*state_set)
(GtkSwitch *widget, gboolean state, gpointer user_data)`, 3 args, a
`gboolean` return GTK uses to decide whether its own default handler
should also run.

### The design that was tried first, and doesn't work

The natural-looking fix: a data table mapping GTK signal name to its
callable shape, looked up at runtime inside `set-event-handler`, spliced
into one generic `foreign-callable` call:

```clojure
;; DOES NOT WORK — kept here as a documented dead end, not a suggestion
(def signal-callable-shape
  (atom {"state-set" {:argtypes [:pointer :int :pointer] :rettype :int :return 0}}))

(let [{:keys [argtypes rettype return]} (get @signal-callable-shape signal default-shape)
      cb (jolt.ffi/foreign-callable (fn [w & _] ... return) argtypes rettype :collect-safe)]
  ...)
```

This compiles the *shape* of the idea correctly but fails at the
`foreign-callable` call itself. `jolt.ffi/foreign-callable` (and the
`__ccallable` special form it expands to) is a **compile-time**
construct — `argtypes`/`rettype` must be literal at the call site, not a
runtime value. Verified live, twice, in throwaway namespaces isolated
from this codebase before touching `glitter.gtk` at all:

```clojure
;; Literal argtypes/rettype: compiles and runs fine.
(jolt.ffi/foreign-callable (fn [a & _] a) [:pointer :int :pointer] :int :collect-safe)

;; The SAME values, let-bound first: fails to compile.
(let [argtypes [:pointer :int :pointer] rettype :int]
  (jolt.ffi/foreign-callable (fn [a b c] a) argtypes rettype :collect-safe))
;; => Unhandled exception: java.lang.IllegalArgumentException:
;;    Don't know how to create ISeq from: clojure.lang.Symbol
;;    ex-data: {:jolt/error {:type :analysis-error, ...}}
```

Same error, both times — `argtypes`/`rettype` reaching the macro as a
symbol (a local binding) rather than a literal vector/keyword breaks the
special form's compile-time analysis. A variadic handler function
(`(fn [a & rest] ...)`) works fine on its own (also verified in
isolation) — it's specifically the *runtime-computed argtypes/rettype*
that can't work, not the handler's arity.

### What actually works: explicit branching, one literal call site per shape

`glitter.gtk/set-event-handler` branches on the GTK signal name in plain
Clojure code, and uses a **separate, fully literal** `foreign-callable`
call for each distinct shape:

```clojure
(let [dispatch! (fn [src-widget]
                  (when-not (w/suppressing? src-widget)
                    (handler (cond-> {:glitter/node el :glitter/gtk-widget src-widget}
                               value-fn (assoc :glitter/value (value-fn src-widget))))))
      cb (if (= signal "state-set")
           (jolt.ffi/foreign-callable
            (fn [src-widget _state _data] (dispatch! src-widget) 0)
            [:pointer :int :pointer] :int :collect-safe)
           (jolt.ffi/foreign-callable
            (fn [src-widget _data] (dispatch! src-widget))
            [:pointer :pointer] :void :collect-safe))]
  ...)
```

The dispatch logic (suppressing-guard + calling `handler`) is factored
into a shared `dispatch!` closure so it isn't duplicated between
branches, but the `foreign-callable` calls themselves — the part that
actually has the compile-time-literal constraint — stay separate and
literal. **There is no generic extension point for adding a third
non-standard shape**: it means adding a third literal branch here, by
hand. `glitter.widget/register-signal!` intentionally has no `shape`
parameter for this reason — accepting one and storing it in a table
would silently promise a capability `set-event-handler` can't actually
honor.

`"state-set"`'s value-fn doesn't need the signal's own second argument
(the new boolean state) either, despite that value being right there in
the callable's parameter list. Verified against `gtk_switch_set_active`
in `gtk/gtkswitch.c` directly:

```c
if (self->is_active != is_active)
  {
    self->is_active = is_active;                       /* set FIRST */
    ...
    g_signal_emit (self, signals[STATE_SET], 0, is_active, &handled);  /* emitted AFTER */
```

`self->is_active` (what `gtk_switch_get_active` reads) is updated
*before* `"state-set"` is emitted — so by the time any handler runs,
`gtk_switch_get_active` already reflects the new value. `"state-set"`'s
`signal-value` entry is a plain `(fn [widget] (g/gtk-switch-get-active
widget))`, exactly the same shape as `:scale`'s and `:change`'s, with no
special argument threading needed:

```clojure
"state-set" (fn [widget] (g/gtk-switch-get-active widget))
```

Returning `0`/`FALSE` from the "state-set" callable (the `:return`-shaped
value baked into the branch above) matters for a second reason beyond
"satisfy the C ABI": GTK's docs say the signal handler should return
`TRUE` to *prevent* the default handler from running. Returning `FALSE`
lets that default handler (`gtk_switch_set_state`, which keeps the
switch's internal visual `::state` sub-property in sync with `::active`)
also run — verified live that this doesn't conflict with glitter's own
dispatch, since both independently react to the same already-updated
`::active` value; glitter never needs to call `gtk_switch_set_state`
itself.

`set-switch-active!` is the usual set-compare-suppress helper, structurally
identical to `set-checkbutton-active!`/`set-toggle-button-active!` — the
suppressing guard works identically for a 3-arg/non-void-return signal as
it does for the standard 2-arg-void ones, since suppression is checked
inside `dispatch!`, before the shape-specific branch ever matters.

Verified live end-to-end (`examples/glitter/switch_smoke.clj`, the full
three-part rigor every value-bearing widget's smoke uses): a real
interaction (direct FFI `gtk_switch_set_active`, bypassing
`set-switch-active!` so the actual `"state-set"` signal fires) reaches
`*dispatch*` with the correct value at `(get-in event [:glitter/dom-event
:glitter/value])`; a subsequent programmatic `reset!` pushes the widget
back in sync; and that programmatic push does **not** trigger a second,
spurious dispatch — the suppressing guard proven to work for this signal
shape too, not just the standard ones. `jolt -M:test` and `bb smokes`
(all prior smokes) were re-run after this change to confirm zero
regression in the shared `set-event-handler` path every other interactive
widget depends on, before this shipped.

## `:revealer` — a free single-child container reuse, plus a props-driven widget

`GtkRevealer` (`gtk_revealer_set_child`) is a single-child container —
the exact same container strategy `:frame`/`:scrolled` already
established, so `append-child!`/`remove-child!`/`replace-child!` each
needed only one more `case` line, no new logic. No signal either:
`:reveal-child` (bool), `:transition-type` (a `GtkRevealerTransitionType`
nick — `:crossfade`, `:slide-right`, ... — resolved at runtime via
`glitter.genum`, the same mechanism `:halign`/`:valign` already use for
`GtkAlign`), and `:transition-duration` (a plain millisecond `uint`) are
applied in that order — transition settings BEFORE the reveal itself, so
the first reveal already uses the caller's transition, not GTK's
defaults, mirroring `:scale`/`:level-bar`'s own "re-range before setting
value" ordering concern.

## `:center-box` — a genuinely new container strategy, and a real v1 gap

Every container kind so far — `:box` (ordered append list), `:window`/
`:frame`/`:scrolled`/`:revealer` (exactly one child) — fits one of two
shapes `glitter.gtk`'s generic child-tracking already assumes.
`GtkCenterBox` doesn't: it has **three independently addressable NAMED
slots** (`start`/`center`/`end`, via `gtk_center_box_set_start_widget`/
`set_center_widget`/`set_end_widget`), not a position in an ordered list.

The container-management functions
(`center-box-append-child!`/`center-box-remove-child!`/
`center-box-replace-child!`/`center-box-insert-after!`) don't track slot
occupancy separately — they query it LIVE via the three getters on every
call (`ptr-null?` = empty). `center-box-append-child!` places a new child
in the first empty slot, in `start -> center -> end` order.
`center-box-remove-child!`/`replace-child!` find which slot a given
child currently occupies (`center-box-slot-setter`, comparing the child's
pointer against each getter's live return) and null it out or overwrite
it. None of this needed a single line changed in `glitter.gtk` — the
whole thing lives inside `glitter.widget`'s existing
`(case (container-kind parent-tag) ...)` dispatch points.

### The gap that live testing found

A throwaway re-render probe — mount `[:center-box [L] [C] [R]]`, then
swap `C`'s hiccup TAG from `:label` to `:button` in a re-render, all 3
slots still full — reliably corrupted the **`R` (end) slot**, not `C`:
reading it back afterward threw `gtk_label_get_text: assertion
'GTK_IS_LABEL (self)' failed`. Root-caused by adding debug prints to
every container-management function and re-running:

```
:DEBUG-insert-child-after! :center-box
:DEBUG-remove-child! :center-box
:DEBUG-slot-setter :child <R's pointer> :start <L> :center <C> :end <R's pointer>
```

`insert-child-after!` fires FIRST (inserting the new button), THEN
`remove-child!` fires (removing the old label) — glitter.core's
reconciler handles a same-position, non-keyed TAG mismatch as **"insert
the new node, then remove the old one"**, two separate `IRender` calls,
not a single `replace-child!`. This is the reconciler's general shape for
ANY multi-child container, and it works fine for `:box`, which has
genuine transient capacity: a `:box` can briefly hold 4 widgets (old +
new) between the insert and the remove, exactly like glitter.gtk's own
`:children` bookkeeping (updated unconditionally by `IRender/insert-before`
regardless of whether the underlying GTK call did anything) assumes.

`GtkCenterBox` has no such transient capacity — there is no 4th slot.
The FIRST version of `center-box-insert-after!` (before this fix) reused
whichever slot the SIBLING implied was next (`sibling` = `L` -> target
`center`), overwriting it directly. `gtk_center_box_set_center_widget`
internally calls `gtk_widget_unparent` on whatever was there before — and
since nothing else in glitter holds a reference to that widget, GTK
finalizes it immediately. So the OLD `C` label was destroyed the moment
the NEW button was inserted, while glitter.gtk's `:children` bookkeeping
still (briefly, correctly for `:box`, WRONGLY for `:center-box`) believed
there were 4 tracked children `[L, button, C, R]`. The reconciler's NEXT
step — reconciling `R`, the trailing unchanged sibling, by index — reads
against that stale, now-4-long bookkeeping and ends up touching the
WRONG tracked entry, corrupting `R`.

**This is a genuine structural limitation, not a bug that can be patched
purely in `center-box-insert-after!`.** A 3-fixed-slot container
literally cannot hold 4 simultaneous occupants the way an ordered list
can — there's no slot to stash the new child in while the old one is
still "pending removal" from the reconciler's point of view. Documented
as a known v1 gap (see `glitter.widget/center-box-insert-after!`'s
docstring and `docs/guide/limitations.md`): **do not swap a slot's hiccup
tag while all 3 slots are occupied.** Change props instead of tags, or
nest a stable wrapper tag (e.g. always render `[:box [:label ...]]` or
`[:box [:button ...]]` inside the slot) so the type change happens one
level down, where `:box`'s own genuine transient capacity handles it
correctly — already proven by every existing `:box` smoke, including
`keyed.clj`.

`revealer_center_box_smoke.clj` deliberately exercises only the SAFE
paths this fix does support: a props-only text update on an
already-populated slot (proving unrelated slots stay untouched), and
dropping a slot's child entirely (pure removal, no concurrent insert).
`:list-box`, covered next, does NOT share this problem — confirmed
separately, live.

## `:spin-button` — generalizing `signal-value` by tag

`GtkSpinButton` (`gtk_spin_button_new_with_range` — no separate
`GtkAdjustment` binding needed, same shape as `:scale`) is a natural
value-bearing widget: its `"value-changed"` signal, confirmed via
`gtk/gtkspinbutton.c`'s `g_signal_new` call, is `g_signal_new(...,
G_TYPE_NONE, 0)` — the plain 2-arg-void shape every widget except
`:switch`/`:list-box` already uses. No new `set-event-handler` branch
needed.

But `"value-changed"` is the **exact same GTK signal name** `:scale`
already registered. `glitter.widget/signal-value` — the table mapping a
value-bearing signal to the `(fn [widget] value)` that reads it back —
was, before this widget, keyed by bare signal name:

```clojure
;; the OLD shape — works fine until a SECOND widget type shares a signal name
(def ^:private signal-value
  (atom {"changed"       (fn [widget] (g/gtk-editable-get-text widget))
         "value-changed" (fn [widget] (g/gtk-range-get-value widget))   ; :scale's getter
         "state-set"     (fn [widget] (g/gtk-switch-get-active widget))}))
```

`:scale`'s entry reads back via `gtk_range_get_value` (GtkScale extends
GtkRange). `:spin-button` needs `gtk_spin_button_get_value` instead —
GtkSpinButton is its own, unrelated GTK4 class. Registering
`:spin-button`'s value-fn under the bare string `"value-changed"` would
have **silently overwritten `:scale`'s entry** (or vice versa, depending
on which widget's spec happened to load last) — both widgets share the
one map key, and only one value-fn can occupy it. Found via source-level
verification WHILE adding `:spin-button`, cross-referencing
`gtk/gtkspinbutton.c`'s signal name against `gtk/gtkrange.c`'s, before
this ever shipped and broke `:scale` — not caught by any test, since no
prior widget had ever shared a signal name with another.

The fix: `signal-value` is now keyed by **`[tag gtk-signal-name]`**, a
2-element vector, not a bare string:

```clojure
(def ^:private signal-value
  (atom {[:entry "changed"]             (fn [widget] (g/gtk-editable-get-text widget))
         [:scale "value-changed"]       (fn [widget] (g/gtk-range-get-value widget))
         [:spin-button "value-changed"] (fn [widget] (g/gtk-spin-button-get-value widget))
         [:switch "state-set"]          (fn [widget] (g/gtk-switch-get-active widget))
         [:list-box "row-selected"]     list-box-selected-index
         [:list-box "row-activated"]    list-box-selected-index}))
```

`glitter.widget/signal-value-fn` and `register-signal!` both gained a
`tag` parameter to match (`register-signal!`'s docstring now says why —
"more than one widget type can emit the same GTK signal name with a
different meaning"), and `glitter.gtk/set-event-handler` looks the
value-fn up via `(w/signal-value-fn (:tag @el) signal)` instead of just
`signal`. `glitter.widget/connect-signals!` (the legacy `create!`
direct-props path — see the ns docstring) gained the same `tag`
parameter for consistency, though it isn't exercised by the reconciler-
driven render path.

`spin_button_list_box_smoke.clj`'s spin-button assertions are what pin
this actually works: the dispatched value comes back correctly through
`:spin-button`'s OWN getter, and `scale_smoke.clj` (already green,
re-run as part of every `bb smokes` pass) confirms `:scale`'s entry is
undisturbed.

## `:list-box` — a third callable shape, and two more real bugs

`GtkListBox`'s row-interaction signals, `"row-selected"`/
`"row-activated"`, are confirmed via `gtk/gtklistbox.c`'s `g_signal_new`
calls to be `void(GtkListBox*, GtkListBoxRow*, gpointer)` — 3 args, VOID
return. This is a THIRD distinct callable shape, alongside the default
2-arg-void and `"state-set"`'s 3-arg/non-void-return — `set-event-handler`
gained a third literal `foreign-callable` branch:

```clojure
cb (cond
     (= signal "state-set")
     (jolt.ffi/foreign-callable
      (fn [src-widget _state _data] (dispatch! src-widget) 0)
      [:pointer :int :pointer] :int :collect-safe)

     (#{"row-selected" "row-activated"} signal)
     (jolt.ffi/foreign-callable
      (fn [src-widget _row _data] (dispatch! src-widget))
      [:pointer :pointer :pointer] :void :collect-safe)

     :else
     (jolt.ffi/foreign-callable
      (fn [src-widget _data] (dispatch! src-widget))
      [:pointer :pointer] :void :collect-safe))
```

The row argument itself (`_row`) is ignored: `list-box-selected-index`
(shared by both signals, in `signal-value`) re-reads
`gtk_list_box_get_selected_row` -> `gtk_list_box_row_get_index` AFTER the
signal fires, same "re-read the widget's own state" pattern every other
value-fn here uses. Verified against `gtk/gtklistbox.c`'s
`gtk_list_box_select_and_activate_full` that a row is selected BEFORE it
is activated (`activate-single-click` defaults to `TRUE`), so the getter
already reflects the right row by the time either handler runs — even
for `"row-activated"`, which doesn't carry an explicit selection the way
`"row-selected"` does.

### Bug 1: `gtk_list_box_remove` needs the ROW, not the child

`gtk_list_box_append`/`insert` auto-wrap a plain child widget in a
`GtkListBoxRow` (confirmed against `gtk/gtklistbox.c`'s bodies), so
APPENDING takes the child directly — matching `gtk_box_append`'s shape,
and matching `gtk_list_box_remove`'s own doc comment, which reads almost
identically to `gtk_box_remove`'s ("the child to remove"). **The doc
comment is misleading.** Reading `gtk_list_box_remove`'s actual C body:

```c
if (!GTK_IS_LIST_BOX_ROW (child))
  {
    row = g_hash_table_lookup (box->header_hash, child);
    if (row != NULL) { ... }
    else { g_warning ("Tried to remove non-child %p", child); }
    return;
  }
row = GTK_LIST_BOX_ROW (child);
```

Passing the plain child widget (not its row) prints `Tried to remove
non-child` and silently no-ops — found live, via the exact warning text
appearing in a re-render smoke's stderr, traced to this function by
grepping the GTK source tree for the literal warning string. Fix:
`list-box-row-of` recovers the wrapping row via `gtk_widget_get_parent`
(GTK auto-wraps, so the child's immediate parent IS its row), and
`list-box-remove-child!`/`list-box-replace-child!`/
`list-box-reorder-child!` all pass the ROW to `gtk_list_box_remove`, not
the child.

This bug is what made the round's ORIGINAL `:list-box` v1 scope call —
"append/remove/replace correctly, but `insert-child-after!`/
`reorder-child!` stay documented no-ops, same as the single-child
containers" — look reasonable at first. It wasn't: exactly the same
"insert new, then remove old" reconciler sequence documented above for
`:center-box` applies to `:list-box` too, and leaving `insert-child-after!`
a no-op there desyncs glitter.gtk's `:children` bookkeeping from live GTK
state the identical way. Unlike `:center-box`, though, `:list-box` has NO
structural capacity limit — `GtkListBoxRow`s are just ordinary tree
children, so `gtk_list_box_insert(box, child, position)`'s index-based
API (confirmed via its own doc comment that an out-of-range or `-1`
position clamps to "append") gives `list-box-insert-after!`/
`list-box-reorder-child!` everything needed for a REAL fix, not a
documented gap:

```clojure
(defn- list-box-index-after [sibling]
  (if (ptr-null? sibling)
    0
    (inc (g/gtk-list-box-row-get-index (g/gtk-widget-get-parent sibling)))))

(defn- list-box-reorder-child! [parent child sibling]
  (when-let [row (list-box-row-of child)]
    (swap! suppressing conj parent)
    (g/gtk-list-box-remove parent row)
    (swap! suppressing disj parent))
  (list-box-insert-after! parent child sibling))
```

`list-box-index-after` always re-reads `sibling`'s row index at call
time — `reorder-child!` deliberately removes `child`'s OLD row FIRST,
THEN computes the target index, so a removal that happens to shift
`sibling`'s live index (if `child` was previously positioned before it)
is already reflected before the index is used. `list-box-reorder-child!`
was written but not live-exercised by any smoke in this round (only
`insert-child-after!`'s "insert new, tag-swap" path and pure removal
were); treat it as implemented-and-reasoned-through, not
verified-under-fire the way the rest of this round's changes are — see
`docs/guide/limitations.md`.

### Bug 2: removing the selected row fires a real, synchronous signal

Even after fixing bug 1, a smoke that removed the CURRENTLY SELECTED row
still threw `gtk_widget_get_parent: assertion 'GTK_IS_WIDGET (widget)'
failed`, and the app's own dispatch log showed an extra, unexpected
`:action/select` entry. Traced by adding a debug print inside the
`"row-selected"`/`"row-activated"` callable and re-running: removing the
selected row fires `"row-selected"` a SECOND time, with a NULL row
argument — GTK's own deselection notice. Confirmed against
`gtk/gtklistbox.c`'s `gtk_list_box_select_row_internal`, which calls
`g_signal_emit (box, signals[ROW_SELECTED], 0, row)` directly (not
conditionally), so removing a row that was selected leaves GTK's
internal state needing to announce "nothing is selected now."

This is a real GTK signal, not something `glitter.widget`'s existing
`suppressing` guard (built for GLITTER'S OWN programmatic setters like
`set-switch-active!`) was written to intercept — it's a side effect of a
CONTAINER operation (`gtk_list_box_remove`), not a value-setter. Left
unsuppressed, the spurious dispatch reaches the app's `*dispatch*` fn
exactly like a real user deselection would — and because `mount!`'s
watcher runs `render!` INLINE when already on the GTK main thread
(convention #6, `app-loop-and-threading.md`), and this all happens
synchronously inside a GTK signal callback fired from INSIDE an
in-progress `core/reconcile` call, the resulting `swap!` on the app's
state atom triggers a SECOND, NESTED `core/reconcile` call before the
outer one has finished — a reentrant reconcile, which is what actually
threw the `GTK_IS_WIDGET` assertion (touching a widget the outer,
still-in-flight reconcile hadn't finished processing yet).

Fix: `"row-selected"`'s `src-widget` argument is the list-box ITSELF
(per its real C signature), so `list-box-remove-child!`/
`list-box-replace-child!`/`list-box-reorder-child!` all `swap!
suppressing conj/disj` on `parent` (the list-box widget) around their
`gtk_list_box_remove` call — the exact same suppress-then-mutate shape
`set-switch-active!` and friends already use, just applied to a
container operation instead of a value setter:

```clojure
(defn- list-box-remove-child! [parent child]
  (when-let [row (list-box-row-of child)]
    (swap! suppressing conj parent)
    (g/gtk-list-box-remove parent row)
    (swap! suppressing disj parent)))
```

`spin_button_list_box_smoke.clj` pins both fixes end-to-end: it selects
row1, swaps row1's TAG (`:label` -> `:button`, exercising the
`insert-child-after!` fix), then removes row1 entirely (the row selected
earlier in the SAME run, exercising the suppressing-guard fix) — and
asserts the select-dispatch count does NOT increment from that removal.
`jolt -M:test` and `bb smokes` (all prior smokes, including `keyed.clj`,
which directly exercises the `:box` branch of the same
`insert-child-after!`/`reorder-child!` functions this round refactored
from `when` to `case`) were re-run to confirm zero regression before this
shipped.

## Boolean props: `some?`, not truthiness

`apply-props!` filters the prop map before handing it to a widget's
`:apply` closure:

```clojure
(let [applied (into {} (filter (fn [[k v]] (and (not (@signals k)) (some? v)))
                               (with-orientation tag props)))]
  ((:apply (spec-for! tag)) widget applied)
  (apply-widget-props! widget applied))
```

`some?` rather than truthiness is what lets `{:active false}` or
`{:sensitive false}` actually reach the widget instead of being filtered
out alongside genuinely-absent (`nil`) keys — mirrors
`glitter.core`'s own deviation #3 (see
[`porting-and-attribution.md`](porting-and-attribution.md)); the two layers
have to agree, or a `false` prop would survive the reconciler's diff only
to be silently dropped one layer down.

## Known gap: removing a key is a no-op

Setting a prop to a *new* value — including an explicit `false` — always
works, per the above. But **removing a key from the hiccup entirely** does
not revert the widget to any default: `apply-props!` only ever receives
keys present in the new prop map, so a dropped key is simply never visited
again, and the widget keeps its last-applied value. GTK has no generic
"unset this property" API the way DOM's `removeAttribute` does. See
[`limitations.md`](limitations.md) for the full writeup and why this is
left unfixed for v1.

**This gap does not apply to `:class`.** `:class` doesn't go through
`apply-props!`/`:apply` at all — `glitter.core`'s `update-classes`
diffs the old and new `:classes` sets directly and calls
`IRender/remove-class` for anything present in the old set but missing
from the new one, same as any other keyed collection diff in the
reconciler. `glitter.gtk` wires both `add-class` and `remove-class` to
real GTK calls:

```clojure
(add-class    [_ el cn] (g/gtk-widget-add-css-class    (ptr el) cn) nil)
(remove-class [_ el cn] (g/gtk-widget-remove-css-class (ptr el) cn) nil)
```

`glitter.core`'s `get-classes` already normalizes every `:class`
representation — a keyword, a symbol, a string, or a collection of
those — down to plain strings before either method is ever called, so
`cn` passes straight through to `gtk_widget_add_css_class`/
`gtk_widget_remove_css_class` with no marshalling needed. GTK4 ships
built-in classes (`"flat"`, `"suggested-action"`,
`"destructive-action"`, `"pill"`, ...) that apply immediately with no
app-provided CSS.

Verified live end-to-end (`examples/glitter/class_smoke.clj`): a
built-in class and a custom class both land on mount (confirmed via
`gtk_widget_has_css_class`, not glitter's own bookkeeping); and,
crucially, dropping a class from a re-render's `:class` set actually
removes it from the live widget — the class that was correctly
**not** re-applied is the weaker half of the test (any bug that just
skipped `add-class` entirely would still pass that check), so the
smoke also asserts a *different* class is added in the same
re-render, proving `update-classes`' diff calls both `add-class` and
`remove-class` correctly in one pass, not just one or the other.

**`:style` remains unwired.** Unlike `:class`, there is no GTK
equivalent of DOM's `element.style.color = ...` — an inline,
per-element property set outside any stylesheet. GTK4 styling is
exclusively class-based, matched against CSS rules loaded through a
`GtkCssProvider`. Synthesizing a real effect from an inline `:style`
map would mean generating a unique class name and CSS rule text per
widget and loading it through a provider at render time — genuine
design work (provider lifecycle, rule invalidation on every diff,
name collision avoidance), not a small FFI addition like `:class`
turned out to be. Hiccup `:style` props are still accepted and diffed
by `glitter.core` (calling `set-style`/`remove-style`), but those two
`IRender` methods remain the no-ops they always were.
