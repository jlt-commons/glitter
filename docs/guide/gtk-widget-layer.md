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

## `:password-entry`/`:search-entry` — free `GtkEditable` reuse, and a signal-value miss

Both `GtkPasswordEntry` and `GtkSearchEntry` implement `GtkEditable` via
a **delegate**, confirmed against `gtk/gtkpasswordentry.c` and
`gtk/gtksearchentry.c`: both call `gtk_editable_init_delegate`, which
internally does

```c
g_signal_connect (delegate, "changed", G_CALLBACK (delegate_changed), editable);
```

— the delegate helper connects to the INNER widget's `"changed"` and
re-emits it on the OUTER object. So `gtk_editable_set_text`/
`gtk_editable_get_text` and glitter.widget's existing `set-entry-text!`
work directly on either pointer, exactly like `:entry`, and `signals`
(the event-keyword -> GTK-signal-name table) needed no new entry: `:on-change
-> "changed"` already covers both.

`signal-value` (the `[tag signal] -> value-fn` table) is a different
story. Being keyed by `[tag signal]` since `:spin-button` (two widgets
ago) means `:entry`'s own `[:entry "changed"]` registration does **not**
automatically cover `:password-entry`/`:search-entry`, even though all
three share the identical GTK signal NAME and the identical value-fn
body (`gtk_editable_get_text`). **The first version of this round
shipped without `[:password-entry "changed"]`**, and a live smoke caught
it immediately: typing into the password entry dispatched
`:action/set-pw` (confirming the SIGNAL wiring worked), but the
dispatched value came back `nil` every time (confirming the VALUE
extraction silently failed) —

```clojure
;; set-event-handler's dispatch!, unchanged:
(handler (cond-> {:glitter/node el :glitter/gtk-widget src-widget}
           value-fn (assoc :glitter/value (value-fn src-widget))))
;; value-fn = (w/signal-value-fn :password-entry "changed") = nil,
;; since only [:entry "changed"] existed — the cond-> clause never fires,
;; :glitter/dom-event never gets a :glitter/value key at all.
```

Fixed by adding the widget's own entries, even though the value-fn body
is identical to `:entry`'s:

```clojure
[:password-entry "changed"] (fn [widget] (g/gtk-editable-get-text widget))
[:search-entry "changed"]   (fn [widget] (g/gtk-editable-get-text widget))
```

This is exactly the risk `:spin-button`'s `[tag signal]` re-keying was
designed to prevent (a shared signal name silently losing one widget's
registration) — but re-keying by tag doesn't mean each widget gets
`:entry`'s registration "for free" the way `signals` (the signal-NAME
table) does; it means the OPPOSITE — every widget that wants a
value-bearing signal needs its own explicit entry, full stop, even when
that entry is a byte-for-byte duplicate of another widget's. Caught by
`password_search_entry_smoke.clj` before it ever shipped, not
discovered after the fact.

`:search-entry` also gets its own genuinely new signal, `"search-changed"`
— confirmed via `gtk/gtksearchentry.c`'s `g_signal_new` call to be
`G_TYPE_NONE, 0`, the plain 2-arg-void shape, no `set-event-handler`
generalization needed. It's debounced by the widget's own internal timer
(`:search-delay` ms after the user stops typing) — **except** when the
text is cleared to empty, which reads `gtk_search_entry_changed`'s C
body directly:

```c
if (str == NULL || *str == '\0')
  {
    ...
    g_clear_handle_id (&entry->delayed_changed_id, g_source_remove);
    g_signal_emit (entry, signals[SEARCH_CHANGED], 0);   /* immediate */
  }
else
  {
    ...
    reset_timeout (entry);                               /* debounced */
  }
```

`password_search_entry_smoke.clj` uses this documented-in-source special
case (clear to `""`) as its live-interaction trigger for `"search-changed"`
— a deterministic, synchronous path, instead of trying to wait out a
real GLib timeout inside a smoke test.

## `:expander`/`:paned` — free signal reuse, and a second structural gap

Neither `GtkExpander` nor `GtkPaned` has a dedicated interaction signal
of its own. Confirmed live, not assumed: `gtk/gtkexpander.c` has no
`g_signal_new` call at all; `gtk/gtkpaned.c`'s only signals
(`cycle-child-focus`, `toggle-handle-focus`, `move-handle`,
`cycle-handle-focus`) are keybinding-navigation actions, not "the user
dragged the divider." Real interactivity for both means watching a
GObject **property-change** signal instead — `"notify::expanded"` for
`:expander`, `"notify::position"` for `:paned` — using GLib's standard
`"notify::<property-name>"` detailed-signal syntax with
`g_signal_connect_data`.

A GObject `"notify"` signal's real C signature is
`void (*notify) (GObject *gobject, GParamSpec *pspec, gpointer user_data)`
— 3 args, VOID return. That is the **exact same shape** already
generalized for `:list-box`'s `"row-selected"`/`"row-activated"` two
widget-additions ago (`void(GtkListBox*, GtkListBoxRow*, gpointer)`) —
a `GParamSpec*` is just another `:pointer` under FFI, indistinguishable
in shape from a `GtkListBoxRow*`. So both new signals slot into the
EXISTING literal branch with zero new `foreign-callable` call sites:

```clojure
(#{"row-selected" "row-activated" "notify::expanded" "notify::position"} signal)
(jolt.ffi/foreign-callable
 (fn [src-widget _pspec-or-row _data] (dispatch! src-widget))
 [:pointer :pointer :pointer] :void :collect-safe)
```

This is the payoff the round-6 `:list-box` write-up promised — "add a
new signal branch here for the next one" turned out to mean, for THIS
specific 3-arg-void shape, "add the signal NAME to the existing set,"
not "write a new branch." `expander_paned_smoke.clj`'s dispatch-count
assertions are what actually prove this reuse works end-to-end, not
just that it compiles.

### `:paned` — a second named-slot container, with a DIFFERENT verified failure shape

`GtkPaned` has two independently addressable NAMED slots
(`start_child`/`end_child`) — a simpler sibling to `:center-box`'s
three, built the same way (`paned-append-child!`/`paned-slot-setter`/
`paned-remove-child!`/`paned-replace-child!`/`paned-insert-after!`,
querying occupancy live via the getters). It inherits the same ROOT
CAUSE as `:center-box`'s structural v1 gap (see
[`:center-box`'s section](#center-box--a-genuinely-new-container-strategy-and-a-real-v1-gap)):
no transient capacity for a 3rd simultaneous occupant when both slots
are full and a same-slot hiccup TAG swap goes through
`glitter.core`'s "insert new, then remove old" sequencing.

This gap was applied to `:paned` from the start this round, informed by
the `:center-box` investigation — but the exact SYMPTOM still needed
live verification, not assumption, because `GtkPaned` only has 2 slots,
not 3, and that changes what actually breaks:

- **Swapping the LAST slot's tag** (`sibling` = the OTHER, unchanged
  slot's widget) while both are full lands **correctly**. The new child
  overwrites the occupied end slot directly — `gtk_paned_set_end_child`
  unparents (and, with nothing else referencing it, GTK finalizes) the
  old occupant immediately, same mechanism as `:center-box` — but unlike
  `:center-box` there is no THIRD slot after it for the reconciler's
  stale post-insert bookkeeping to corrupt into. Verified live: no
  assertion failure, correct final state. `expander_paned_smoke.clj`
  exercises exactly this path.
- **Swapping the FIRST slot's tag** (`sibling` = nil, since it's the
  first child) fails **differently**: `paned-append-child!`'s "first
  empty slot" search finds NEITHER slot empty (both still occupied at
  insert time) and silently no-ops — the new widget is created but never
  attached anywhere. The reconciler's subsequent removal of the OLD
  first-slot child then leaves that slot genuinely EMPTY, holding
  neither widget. Verified live in a throwaway probe: both
  `gtk_paned_get_start_child` and reading back the intended replacement
  trip `GTK_IS_BUTTON` assertions afterward — not corruption of an
  unrelated slot this time, just a silently-failed swap.

Either way, the remedy is identical to `:center-box`'s: no same-slot tag
swap when both slots are already occupied — change props instead of
tags, or nest a stable wrapper tag one level down so the type change
happens where `:box`-shaped reconciliation already handles it correctly.
`expander_paned_smoke.clj` only exercises the safe half (last-slot
swap); the first-slot failure mode is documented in
`paned-insert-after!`'s own docstring and `docs/guide/limitations.md`,
not re-tested in the permanent smoke — the same "document, don't ship a
test that asserts broken behavior" precedent `:center-box`'s smoke
already set.

## `:aspect-frame`/`:calendar` — a quick win, and a genuinely new value type

`GtkAspectFrame` (`gtk_aspect_frame_set_child`) is a single-child
container — the exact `:frame`/`:scrolled`/`:revealer`/`:expander`
strategy reused verbatim, one more `case` line in each of
`append-child!`/`remove-child!`/`replace-child!`. Its own construction
params — `xalign`/`yalign`/`ratio`/`obey-child` — are plain floats/bool,
confirmed individually re-settable post-construction via their own
setters (`gtk/gtkaspectframe.h`), so unlike `:paned`'s `orientation`
(a `GType` that may not be registered yet at first widget construction)
they carry no chicken-and-egg risk and resolve directly from props at
ctor time, with GTK's own documented defaults as fallback.

`GtkCalendar` is a genuinely new value-bearing leaf widget.
`"day-selected"` is confirmed via `gtk/gtkcalendar.c`'s `g_signal_new`
call to be the plain 2-arg-void shape — but `gtk_calendar_get_date`
returns a `GDateTime*`, a value type this project has never marshalled
before. GLib's `GDateTime` is refcounted, and the exact ownership rules
matter enough that they're worth pinning precisely rather than guessing
from general GObject conventions:

```c
/* gtk_calendar_get_date's actual C body — confirmed by reading it directly */
GDateTime *
gtk_calendar_get_date (GtkCalendar *self)
{
  return g_date_time_ref (self->date);   /* caller owns a NEW ref */
}
```

```c
/* gtk_calendar_select_day's signature — a plain in-param, standard GLib
   convention: does NOT take ownership of what's passed in */
void gtk_calendar_select_day (GtkCalendar *calendar, GDateTime *date);
```

So every call site that reads OR constructs a `GDateTime` owns a
reference it must release:

```clojure
(defn- calendar-date= [widget]
  (let [d (g/gtk-calendar-get-date widget)   ; owns a NEW ref
        result [(g/g-date-time-get-year d) (g/g-date-time-get-month d)
                 (g/g-date-time-get-day-of-month d)]]
    (g/g-date-time-unref d)                  ; release it
    result))

(defn- set-calendar-date! [widget [year month day :as date]]
  (when (and year month day (not= (vec date) (calendar-date= widget)))
    (let [gdt (g/g-date-time-new-local year month day 0 0 0.0)]  ; owns a NEW ref
      (swap! suppressing conj widget)
      (g/gtk-calendar-select-day widget gdt)   ; does NOT take ownership
      (swap! suppressing disj widget)
      (g/g-date-time-unref gdt))))             ; release it
```

Skipping either `unref` would leak one `GDateTime` object per render or
dispatch — small individually, but unbounded over a long-running app's
lifetime. `aspect_frame_calendar_smoke.clj`'s round-trip (a real
interaction dispatching a `[year month day]` value, then a programmatic
`reset!` syncing the widget back with the suppressing guard proven to
hold) is what actually exercises this discipline under repeated use, not
just confirms it compiles.

## `:overlay`/`:flow-box` — a third container shape, and a verified difference, not an assumption

`GtkOverlay` breaks the pattern every multi-child container up to this
point has followed. `:box` is an ordered append-list; `:center-box`/
`:paned` are fixed NAMED slots, each independently queryable via its own
getter. `GtkOverlay` has exactly ONE queryable slot —
`gtk_overlay_get_child`, the main content — plus an UNBOUNDED set of
floating overlay children with **no enumeration getter at all**
(confirmed: `gtk/gtkoverlay.h` has no "get overlays" function of any
kind). The "query live via getters, never track separately" pattern
`center-box-slot-setter`/`paned-slot-setter` both rely on simply doesn't
have anything to query for the overlay set.

The design: the FIRST hiccup child becomes main content; every
subsequent child becomes an overlay, unconditionally —

```clojure
(defn- overlay-append-child! [parent child]
  (if (ptr-null? (g/gtk-overlay-get-child parent))
    (g/gtk-overlay-set-child parent child)
    (g/gtk-overlay-add-overlay parent child)))
```

— and `overlay-remove-child!` decides which branch by checking whether
`child` IS the current main content (the one thing that IS queryable);
if not, it's assumed to be a registered overlay (safe, since every
overlay-container child glitter ever attaches goes through this same
function first). `overlay-replace-child!`'s overlay branch (remove old,
append new) does NOT preserve z-order — GTK has no "insert overlay at
position N" API to do better, a deliberate, documented simplification
rather than an oversight. The single named slot (main content) inherits
the identical structural v1 gap `:center-box`/`:paned` have: no
same-tag-swap once occupied — `overlay-insert-after!` only handles the
`sibling = nil` (main slot, still empty) case for real.

Because GTK exposes no overlay-enumeration API, verifying a REMOVAL
actually happened can't use the "check occupancy via the specific
container's own getter" pattern every earlier container smoke uses.
`overlay_flow_box_smoke.clj` instead reuses the GENERIC widget-tree walk
(`gtk_widget_get_first_child`/`get_next_sibling`) every structural smoke
in this project already has, since overlay children ARE real GTK
widget-tree children — just laid out specially by `GtkOverlay`'s own
layout manager:

```clojure
(defn- count-children [w]
  (loop [c (g/gtk-widget-get-first-child w) n 0]
    (if (or (nil? c) (zero? c)) n (recur (g/gtk-widget-get-next-sibling c) (inc n)))))
```

`:overlay-child-count-on-mount` reads `2` (main + one overlay);
`:overlay-child-count-after-drop` reads `1` after removing the overlay —
proof the removal reached live GTK state, not just that `main` stayed
correct.

### `:flow-box` — apply the `:list-box` lessons, but verify, don't assume

`GtkFlowBox`'s `append`/`insert`/`remove` share the identical signature
shape `GtkListBox`'s do (confirmed against `gtk/gtkflowbox.c`), strongly
suggesting the same auto-wrap-in-a-child-widget behavior
(`GtkListBoxRow` for `:list-box`, `GtkFlowBoxChild` for `:flow-box`) —
and `gtk_flow_box_insert`'s body confirms it: a plain child gets wrapped
in a fresh `GtkFlowBoxChild` exactly like `gtk_list_box_insert` wraps in
a fresh `GtkListBoxRow`.

The temptation, given the structural similarity, is to assume
`gtk_flow_box_remove` has the identical "needs the wrapper, not the
plain child" gotcha `:list-box` needed a fix for two rounds ago. **It
does not** — verified by reading `gtk_flow_box_remove`'s C body
directly, not by assuming the lesson transfers between sibling widgets:

```c
/* gtk_flow_box_remove's actual C body — confirmed by reading it directly */
if (GTK_IS_FLOW_BOX_CHILD (widget))
  child = GTK_FLOW_BOX_CHILD (widget);
else
  {
    child = (GtkFlowBoxChild*) gtk_widget_get_parent (widget);
    if (!GTK_IS_FLOW_BOX_CHILD (child))
      {
        g_warning ("Tried to remove non-child %p", widget);
        return;
      }
  }
```

Unlike `gtk_list_box_remove` (which requires the row directly and warns
otherwise), `gtk_flow_box_remove` accepts EITHER the wrapped
`GtkFlowBoxChild` OR the plain inner widget, auto-unwrapping via
`gtk_widget_get_parent` internally when needed. So
`flow-box-remove-child!`/`flow-box-replace-child!` call
`gtk_flow_box_remove` with the plain child straight away — no
`list-box-row-of`-style recovery helper needed for removal at all.
Positional INSERT still needs a sibling's *wrapper* (to read its index
via `gtk_flow_box_child_get_index`), so `flow-box-child-of` exists for
that one purpose, mirroring `list-box-row-of`'s shape but used only on
the insert/reorder side.

`"child-activated"` is confirmed via `g_signal_new` to be
`void(GtkFlowBox*, GtkFlowBoxChild*, gpointer)` — the identical
3-arg-void shape already generalized for `:list-box`/`:expander`/
`:paned`, another free reuse with zero new `foreign-callable` call
sites. v1 deliberately wires `:on-child-activated` with **no value-fn**:
reading back "which child" would need `GList` traversal via
`gtk_flow_box_get_selected_children` — a genuinely new FFI complexity
class (walking a linked list through raw pointers) this project hasn't
needed yet, and not worth taking on for a first pass when
`:on-click`/`:on-toggled` already establish the precedent that a
dispatched event without a `:glitter/value` is a normal, supported
shape.

`overlay_flow_box_smoke.clj`'s flow-box assertions swap the MIDDLE
child's tag (`:label` -> `:button`) while all three positions are
occupied and confirm both siblings stay untouched — proving
`flow-box-insert-after!` (built on the verified-safe `gtk_flow_box_insert`
clamping behavior, same as `:list-box`'s) works correctly, without
needing `:flow-box` to inherit any of `:center-box`/`:paned`'s
fixed-slot capacity limits: `GtkFlowBoxChild`s have per-item capacity,
not a bounded slot count, so this class of gap simply doesn't apply
here.

## `:picture`/`:editable-label` — a quick win, a third `GtkEditable` delegate, and a general GtkEditable finding

`GtkPicture` has no signal at all (confirmed: no `g_signal_new` call in
`gtk/gtkpicture.c`) — purely display-only, the same shape as
`:spinner`/`:progress-bar`/`:image`/`:level-bar`, driven entirely by
re-applied props (`:content-fit`/`:can-shrink`/`:alternative-text`).
It's a modernized `:image`: `GdkPaintable`-based, with real aspect-ratio-
aware scaling via `:content-fit` instead of `:image`'s icon-name/file
choice. `gtk_picture_new_for_filename`/`gtk_picture_set_filename` take
plain strings (not a `GFile*`), matching `:image`'s existing
`gtk-image-new-from-file`/`gtk-image-set-from-file` bindings exactly —
checked against those before writing the new ones, so no new marshalling
convention was introduced.

`GtkEditableLabel` is a THIRD widget in this project implementing
`GtkEditable` via a delegate (after `:password-entry`/`:search-entry` —
confirmed against `gtk/gtkeditablelabel.c`'s `gtk_editable_init_delegate`
call), so it reuses `set-entry-text!`/`:entry`'s `"changed"` signal NAME
for free. It still needs its own `[:editable-label "changed"]`
`signal-value` entry, exactly like `:password-entry` did two rounds ago —
and this round repeated that EXACT mistake once, live, despite writing a
comment in `editable-label-spec` saying the entry would be "applied
proactively this time." It wasn't; the entry was written into the spec's
comment and then never actually added to the `signal-value` atom. Caught
by the throwaway probe this section's smoke was distilled from (a typed
value came back `nil`), not assumed safe because the comment said so —
the same lesson as the original `:password-entry` near-miss, now missed
*twice* by two different rounds' authors, which is why it's called out
here explicitly rather than trusted to a comment alone.

Click-to-edit itself is GTK's own built-in gesture — no signal wiring
needed for entering edit mode by clicking. Programmatic edit-mode control
goes through `gtk_editable_label_start_editing`/`stop_editing`, wrapped
in the usual set-compare-suppress shape:

```clojure
(defn- set-editable-label-editing! [widget editing?]
  (let [target (->bool editing?)]
    (when (not= target (g/gtk-editable-label-get-editing widget))
      (swap! suppressing conj widget)
      (if editing?
        (g/gtk-editable-label-start-editing widget)
        (g/gtk-editable-label-stop-editing widget 1))
      (swap! suppressing disj widget))))
```

`gtk_editable_label_stop_editing`'s second argument is a commit flag —
always `1` here, so leaving edit mode programmatically commits the
in-progress text rather than discarding it.

### A general `GtkEditable` finding: bulk text replacement isn't one atomic emission

Investigating this widget's `"changed"` dispatch count surfaced a real,
general `GtkEditable` behavior that applies to **every** widget built on
the delegate in this project — `:entry`, `:password-entry`,
`:search-entry`, and now `:editable-label` alike — not a glitter bug and
not specific to any one widget. `gtk_editable_set_text`'s own C body,
read directly rather than assumed atomic:

```c
/* gtk_editable_set_text's actual C body — confirmed by reading it directly */
void
gtk_editable_set_text (GtkEditable *editable, const char *text)
{
  ...
  gtk_editable_delete_text (editable, 0, -1);
  gtk_editable_insert_text (editable, text, -1, &pos);
}
```

Two separate mutations, not one — and only property `notify` is frozen/
thawed around them (`g_object_freeze_notify`/`thaw_notify`), NOT the
`"changed"` signal itself. Whether `"changed"` fires once or twice
depends on the state of the buffer BEFORE the call:
`gtk/gtktext.c`'s `gtk_text_delete_text` has an early return —

```c
/* gtk_text_delete_text's actual C body — confirmed by reading it directly */
if (start_pos == end_pos)
  return;
```

— so deleting from an ALREADY-EMPTY buffer is a silent no-op (no
`"changed"` emitted), leaving only the insert's own emission: **one**
`"changed"` total. Replacing NON-EMPTY text fires the delete's emission
AND the insert's: **two**. This only matters when SIMULATING a bulk
replace-all-text interaction the way this project's smokes do — calling
`gtk_editable_set_text` directly to bypass glitter's own wrapper and
trigger the real signal. A real user typing character-by-character never
takes this path at all; that goes through `gtk_editable_insert_text`
directly, once per keystroke, with no matching delete. Every
`GtkEditable`-family smoke in this project (`password_search_entry_smoke.clj`,
`picture_editable_label_smoke.clj`) starts its interaction target from
EMPTY text specifically so its dispatch-count assertion tests the
behavior under test, not this incidental doubling — round 7's
`:password-entry` smoke happened to get this right by starting `:pw` at
`""`, but that was accidental (discovered only while root-causing this
round's finding), not a deliberate choice documented at the time.

## `:notebook`/`:scale-button` — a sixth callable shape, a mount-time surprise, and a tag-aware dispatch

`GtkNotebook` does NOT auto-wrap its children the way `:list-box`/
`:flow-box` do — a page's child IS the real widget throughout, and
`gtk_notebook_page_num(notebook, child)` recovers its page index
directly (confirmed against `gtk/gtknotebook.h`), no unwrap step
anywhere. `gtk_notebook_insert_page`'s `position` argument clamps
out-of-range values to append (`if (position < 0 || position > nchildren)
position = nchildren;`, confirmed by reading the C body), the same
safe-clamping convention `:list-box`/`:flow-box` already rely on for
their own insert helpers. `tab_label` is confirmed nullable
(`g_return_val_if_fail (tab_label == NULL || GTK_IS_WIDGET (tab_label),
-1)`) — GTK auto-generates a default numbered tab when it's `NULL`. v1
always passes `NULL`: no per-child hiccup convention for custom tab
labels yet, deferred rather than inventing a second widget-per-page
shape for a first pass.

`"switch-page"` is a SIXTH callable shape in this project — 4 args,
`void(GtkNotebook*, GtkWidget* page, guint page_num, gpointer)`,
confirmed against `gtk/gtknotebook.c`'s `g_signal_new` call — and the
FIRST signal here that can't reuse the shared `dispatch!`/`value-fn`
path every earlier signal (including `:scale-button`'s, below) uses.
`gtk_notebook_switch_page` — the function that EMITS this signal — only
READS `notebook->cur_page`; the actual `cur_page = page` assignment
happens in `gtk_notebook_real_switch_page`, the signal's OWN DEFAULT
CLASS HANDLER, registered `G_SIGNAL_RUN_LAST`, which runs AFTER
user-connected handlers like glitter's. Re-reading
`gtk_notebook_get_current_page()` the way every other signal here
re-reads its property would return the STALE previous page — confirmed
by reading the C source, then confirmed a second way, empirically, in
the throwaway probe this section's smoke was distilled from: capturing
the raw dispatched `page-num` side by side with a same-tick getter read
showed the getter really would have lagged by one page. So this branch
reads `page-num` directly from its OWN raw signal argument and builds
the dispatched event map inline, bypassing `value-fn`/`dispatch!`
entirely:

```clojure
(= signal "switch-page")
(jolt.ffi/foreign-callable
 (fn [src-widget _page page-num _data]
   (when-not (w/suppressing? src-widget)
     (handler {:glitter/node el :glitter/gtk-widget src-widget :glitter/value page-num})))
 [:pointer :pointer :uint :pointer] :void :collect-safe)
```

### Mounting a notebook dispatches, before any interaction

A second, genuinely surprising real GTK behavior falls out of
`gtk_notebook_insert_page`'s own C body (also called internally by
`gtk_notebook_append_page`), read directly while investigating why the
throwaway probe's dispatch log had a `"switch-page"` entry BEFORE any
simulated user interaction:

```c
/* gtk_notebook_insert_page's actual C body (tail) — confirmed by reading it directly */
g_signal_emit (notebook, notebook_signals[PAGE_ADDED], 0, page->child, position);

if (!gtk_notebook_has_current_page (notebook))
  {
    gtk_notebook_switch_page (notebook, page);
  }
```

Appending the FIRST page to a notebook that has no current page yet
auto-selects it — which is exactly what happens when `:notebook`'s
initial children are appended during mount. So constructing a
`[:notebook ...]` with initial children genuinely DISPATCHES a
`"switch-page"` action as a side effect of mounting, before the app
ever interacts with the widget. This is real GTK behavior, not a
glitter bug — but it means a dispatch-count baseline of `0` after mount
is WRONG for any app using `:notebook` with pre-populated pages;
`notebook_scale_button_smoke.clj`'s own dispatch-count assertions start
from `1`, not `0`, for exactly this reason. There is no `get-nth-page`
GTK API to read a page's own child widget back out (only
`gtk_notebook_page_num`, which needs a widget reference going IN), so —
like `:overlay`'s own documented "no enumerate" gap — this smoke
verifies `:notebook` only via `gtk_notebook_get_current_page`'s int, not
by reading page content.

### `:scale-button` — a third widget sharing `"value-changed"`, safely this time

`gtk_scale_button_new` needs `min`/`max`/`step` at construction time,
the same shape `:scale`/`:spin-button` already establish; the 4th
argument (icon names shown at different value ranges) is always
`jolt.ffi/null` — verified live that GTK falls back to its own default
icon set rather than erroring on a null icon array.

Its `"value-changed"` signal shares the exact GTK signal NAME
`:scale`/`:spin-button` already use — a THIRD widget doing so — but has
a genuinely DIFFERENT real C shape: `void(GtkScaleButton*, double,
gpointer)`, confirmed against `gtk/gtkscalebutton.c`'s `g_signal_new`
call. This is the FIRST case in this project where the signal NAME
alone is insufficient to pick the right callable — `set-event-handler`'s
`cond` has to check the widget's own `:tag` too:

```clojure
(and (= signal "value-changed") (= (:tag @el) :scale-button))
(jolt.ffi/foreign-callable
 (fn [src-widget _value _data] (dispatch! src-widget))
 [:pointer :double :pointer] :void :collect-safe)
```

Unlike `:notebook`'s `"switch-page"` above, this one IS safe to re-read
via the usual getter-based `value-fn` — verified, not assumed by
analogy, by reading `gtk/gtkscalebutton.c`'s `cb_scale_value_changed`
(the internal callback that emits the button's OWN `"value-changed"`):
it reads `gtk_range_get_value` from the button's internal slider AFTER
that slider's own `"value-changed"` has already fired, then emits the
button's signal. `gtk_scale_button_get_value` reads that SAME shared
`GtkAdjustment`, so it's already current by the time ANY handler sees
the button's own signal — the getter-re-read pattern holds here even
though the raw-argument-only pattern was required for `:notebook`.

`notebook_scale_button_smoke.clj`'s dispatch-count sequence — `1` after
mount (the notebook's own auto-select), `2` after a real page switch,
`3` after a real scale-button drag, `3` again (unchanged) after a
programmatic sync-back of both widgets — is what proves both findings
end-to-end against live GTK state, not just that the code compiles.

## `:inscription`/`:search-bar` — two more quick, no-signal wins

`GtkInscription` has no signal at all (confirmed: no `g_signal_new` in
`gtk/gtkinscription.c`) — a lighter-weight sibling to `:label`: no
markup interpretation, fixed `:text-overflow` handling (a
`GtkInscriptionOverflow` nick — `:clip`/`:ellipsize-start`/
`:ellipsize-middle`/`:ellipsize-end`) instead of Pango's ellipsize/wrap
options. Purely display-only, same shape as `:picture`.

`GtkSearchBar` is also entirely props/display-driven (no `g_signal_new`
in `gtk/gtksearchbar.c` either) — a single-child container, same
strategy as `:frame`/`:revealer`, controlled by `:search-mode`/
`:show-close-button`. Pairs naturally with `:search-entry` as its
child. v1 deliberately does not wire `gtk_search_bar_connect_entry`/
`gtk_search_bar_set_key_capture_widget` — both need a raw
`GtkEditable`/`GtkWidget` pointer glitter has no hiccup-level
convention for passing sideways yet, and `:search-mode` is fully
controllable programmatically without them.

Neither widget touched `glitter.gtk` at all this round — the first
round where every new widget is entirely `:apply`-driven, with zero
signal wiring of any kind.

## `:header-bar`/`:action-bar` — a genuinely new hybrid container shape

Every multi-child container up to this point has been either a plain
ordered list (`:box`/`:list-box`/`:flow-box`/`:notebook`) or a FIXED
set of independently-named slots (`:center-box`'s three,`:paned`'s
two, `:overlay`'s one-plus-unbounded-unenumerable). `GtkHeaderBar` and
`GtkActionBar` are a genuinely different shape again: one named
title-widget/center-widget slot, plus an **ordered** pack-start list —
unbounded, like `:box`'s children, but coexisting with a named slot the
way `:center-box`'s do.

v1 convention, mirroring `:overlay`'s own "query GTK's own occupancy,
don't track position separately" pattern: the FIRST hiccup child
becomes the title-widget (`:header-bar`) or center-widget
(`:action-bar`) if that slot is still empty; every LATER child gets
`pack_start`'d, in order:

```clojure
(defn- header-bar-append-child! [parent child]
  (if (ptr-null? (g/gtk-header-bar-get-title-widget parent))
    (g/gtk-header-bar-set-title-widget parent child)
    (g/gtk-header-bar-pack-start parent child)))
```

A real, verified finding drove the decision to stop there and not also
wire `pack_end`. Reading `gtk_header_bar_pack`'s C body directly:

```c
/* gtk_header_bar_pack's actual C body — confirmed by reading it directly */
if (pack_type == GTK_PACK_START)
  gtk_box_append (GTK_BOX (bar->start_box), widget);
else if (pack_type == GTK_PACK_END)
  gtk_box_prepend (GTK_BOX (bar->end_box), widget);
```

`pack_start` is a safe `gtk_box_append` — hiccup order lands correctly
when the reconciler feeds children one at a time in its normal append
sequence. `pack_end` is a `gtk_box_prepend` — feeding END children one
at a time in hiccup order would silently land them in REVERSE order in
the live GTK tree, with no public API to fix the positioning
afterward (there is no `gtk_header_bar_reorder`, and `bar->end_box`
is a private field glitter has no pointer to). `GtkActionBar`'s
`pack_end` carries the identical risk via a DIFFERENT GTK call —
confirmed independently, not assumed to carry over just because the
widgets look alike:

```c
/* gtk_action_bar_pack_end's actual C body — confirmed by reading it directly */
gtk_box_insert_child_after (GTK_BOX (action_bar->end_box), child, NULL);
```

`gtk_box_insert_child_after(box, child, NULL)` is this project's own
established convention for "insert as the FIRST child" — a prepend
under a different name. Both widgets reverse-accumulate on `pack_end`;
v1 therefore only calls `pack_start` anywhere in this codebase.
`gtk_header_bar_pack_end`/`gtk_action_bar_pack_end` are bound to
nothing — a future round wiring them must also solve the reversal, not
just call the function.

`gtk_header_bar_remove`/`gtk_action_bar_remove` each handle EITHER role
(title/center-widget or a pack-start child) in one call — confirmed via
their C bodies, which branch on the child's actual GTK parent
(`start_box` vs. `center_box`) — so, unlike `:overlay`'s remove, no role
check is needed before calling it. `*-replace-child!` DOES need to check
the role first (removal loses that information), same "capture before
you mutate" concern every other `replace-child!` here has.

**Known v1 gap**, same root cause as `:center-box`'s/`:paned`'s/
`:overlay`'s: if the title/center-widget slot is already occupied and
its hiccup TAG gets swapped, `*-insert-after!`'s `sibling` nil branch
falls through to `*-append-child!`, which sees the slot still occupied
and pack-starts the new widget instead of replacing the title — the
reconciler's subsequent removal of the old title then leaves that slot
empty with the new widget stranded in the pack-start list. Change
props instead of tags, or nest a stable wrapper tag one level down.

Neither widget's pack-start region can be reordered either — a THIRD
variant of the same structural reason `:overlay` can't reorder its
overlay children: the region is a real ordered list internally, but
it's a PRIVATE `GtkBox` glitter has no pointer to; only the append-only
`pack_start` functions are public API.

### A second real finding: `:show-title-buttons` shares the SAME list

Neither widget exposes an enumeration getter for its pack-start region
(same situation `:overlay`'s own smoke is already in), so
`header_bar_action_bar_smoke.clj` identifies the real `start_box` by
the `"start"` CSS class GTK itself adds internally (confirmed via both
`gtk_header_bar_init`'s and `gtk_action_bar_init`'s C bodies), after
walking down through each widget's own private wrapper
(`GtkWindowHandle` for `:header-bar`, `GtkRevealer` for `:action-bar`)
and its `GtkCenterBox` — a structural depth (4 levels) confirmed by
reading both init functions directly, not guessed. The first attempt
at this smoke assumed a flat 3-sibling composite and mis-treated a
`GtkCenterBox` pointer as a `GtkButton`, caught immediately by a live
`GTK-CRITICAL` rather than a silent wrong answer.

That same investigation surfaced a second, independent real finding:
`gtk_header_bar_set_show_title_buttons(bar, TRUE)` calls
`create_window_controls(bar)`, which `gtk_box_prepend`s a native
`GtkWindowControls` widget into `bar->start_box` — THE SAME pack-start
region glitter's own hiccup children live in:

```c
/* create_window_controls's actual C body (tail) — confirmed by reading it directly */
gtk_box_prepend (GTK_BOX (bar->start_box), controls);
bar->start_window_controls = controls;
```

Toggling `:show-title-buttons` true therefore lands a GTK-managed,
non-button widget at the FRONT of the pack-start list, shifting
glitter's own tracked children back by one position. This is real GTK
behavior sharing the same mutable list, not a glitter bug — and not
something glitter's own container-management code needs to guard
against, since `gtk_header_bar_remove`/`replace` only ever act on
widgets glitter itself created, never on GTK's own internal controls
widget. `header_bar_action_bar_smoke.clj` asserts the shift explicitly
rather than avoiding it: mounts with `:show-title-buttons false` (so
the mount-time read sees only glitter's own two buttons), then toggles
it true on re-render and confirms the pack-start count grows by one
while glitter's own buttons keep their relative order, now at the tail.

## `:menu-button`/`:popover` — a popup surface, not a normal tree child

Every container up to this point manages a REAL tree child — something
`append-child!`/`remove-child!` parents directly into the widget
hierarchy glitter's diff walks. `:menu-button`'s relationship to
`:popover` is different: its ONE hiccup child (if present) is expected
to be a `:popover`, attached via `gtk_menu_button_set_popover` — a
popup surface, not a box-shaped child. Confirmed by reading
`gtk_menu_button_set_popover`'s C body directly that this is real
ownership (`gtk_widget_set_parent`/`unparent`), not a passive
reference:

```c
/* gtk_menu_button_set_popover's actual C body (relevant lines) —
   confirmed by reading it directly */
if (popover)
  {
    gtk_widget_set_parent (menu_button->popover, GTK_WIDGET (menu_button));
    g_signal_connect_swapped (menu_button->popover, "closed",
                              G_CALLBACK (menu_deactivate_cb), menu_button);
    ...
  }
```

`:popover` itself is an ordinary single-child container
(`gtk_popover_set_child`), same strategy as `:frame`/`:revealer`.

Both signals turned out to be free reuses of the plain 2-arg-void shape
already generalized in this project — confirmed via
`gtk/gtkmenubutton.c`'s and `gtk/gtkpopover.c`'s own `g_signal_new`
calls that `"activate"` and `"closed"` are both `G_TYPE_NONE, 0`. No new
`foreign-callable` branch was needed in `glitter.gtk/set-event-handler`
at all — the first round where every new signal is a free reuse.
`:on-activate` was already a registered `signals` entry (added
speculatively in an earlier round, unused until now); `:on-closed` is
new, but only as a signal NAME entry, not a new shape.

`gtk_popover_popdown`'s underlying `gtk_popover_hide` vfunc is confirmed
(by reading it directly) to emit `"closed"` SYNCHRONOUSLY as part of
the same call — `_gtk_widget_set_visible_flag` -> `gtk_widget_unmap` ->
`g_signal_emit(CLOSED)`, all in one function body — so `:popover`'s
`:visible` prop drives `popup`/`popdown` through the usual
suppressing-guard setter, same synchronous-emission shape as every
other value-bearing widget here:

```clojure
(defn- set-popover-visible! [widget visible?]
  (let [target (->bool visible?)]
    (when (not= target (g/gtk-widget-get-visible widget))
      (swap! suppressing conj widget)
      (if visible? (g/gtk-popover-popup widget) (g/gtk-popover-popdown widget))
      (swap! suppressing disj widget))))
```

This is a controlled-component contract identical to `:notebook`'s
`:current-page`: `:menu-button`'s own internal click handling opens the
popover independently of glitter (confirmed live: `set_popover` wires
its OWN internal `"closed"` listener, separate from glitter's — GTK
happily supports multiple listeners on one signal), so an app is
expected to sync its own `:visible` state from `:on-activate` (open)
and `:on-closed` (close). `menu_button_popover_smoke.clj` plays that
app's role explicitly and verifies BOTH directions of the suppressing
guard: opening/closing programmatically after the real click cycle
causes no spurious extra dispatch either way.

`gtk_widget_activate` on `:menu-button`, unlike GtkButton's own
activation (`:link-button`'s ~250ms press-animation gotcha), does NOT
need that delay — confirmed live, not assumed just because both are
"button-like": the dispatch fires reliably within a much shorter
deferred window than `:link-button` ever needed. `:menu-button` is not
a `GtkButton` subclass; its `"activate"` signal is wired via
`gtk_widget_class_set_activate_signal`, a generic `GtkWidget`
mechanism entirely outside `GtkButton`'s own press-animation state
machine.

## The ctor/apply audit — four real, previously-shipped bugs

Every widget spec up to this point was written under an implicit,
never-verified assumption: that `:ctor`'s `props` argument sees the
widget's real initial hiccup props, so a construction-time branch like
`(if (:label p) (gtk-button-new-with-label (:label p)) (gtk-button-new))`
does something. Investigating `:grid` (below) required understanding
the ctor/apply prop flow precisely enough to design a new mechanism, and
that investigation started with a targeted `println` probe inside
`glitter.gtk/create-element` — which showed `options` is **always**
`nil` or `{:ns "..."}` at the real call site, never `{:label "x"}` or
anything resembling a hiccup prop. See
[`architecture.md`](architecture.md#ctors-props-argument-is-always-empty--verified-live-round-11)
for the full mechanism (`create-node`'s actual call, `set-attribute`'s
one-key-per-call shape). This section covers what that finding meant
for the widgets already shipped by round 10.

A systematic audit of all 42 pre-round-11 widget specs, cross-checking
every `:ctor` prop reference against `:apply`'s coverage, found four
real bugs — all confirmed live, all shipped since as early as round 1,
all invisible to every existing smoke because no existing smoke's
chosen test values happened to exercise the gap:

1. **`:checkbutton-spec`'s `:label` was read in `:ctor` but never
   applied.** `(gtk-check-button-new-with-label (:label p))` never
   actually ran (props are empty at `:ctor` time), and `:apply` never
   called `gtk_check_button_set_label` either — so a checkbutton's
   label was silently always empty. Confirmed live:
   `[:checkbutton {:label "x"}]` mounted with no visible label at all.
   The only call site in this project, `examples/glitter/todo.clj`,
   never passes `:label`, so the bug shipped invisibly for 10 rounds.
   Fixed by adding label handling to `:apply`:

   ```clojure
   (defn- checkbutton-spec []
     {:ctor  (fn [_] (g/gtk-check-button-new))
      :apply (fn [w p]
               (when (contains? p :label) (g/gtk-checkbutton-set-label w (:label p)))
               ...)
      :container :none})
   ```

2. **`:scale-button-spec`'s `:min`/`:max`/`:step` had the identical
   gap** — read in `:ctor`, never covered by `:apply` at all.
   `GtkScaleButton` has no direct set-range call the way `GtkRange`
   does; the fix reads the button's own `GtkAdjustment` via
   `gtk_scale_button_get_adjustment` and reconfigures it with
   `gtk_adjustment_configure`. The FIRST fix attempt used hardcoded
   fallbacks (`(or (:min p) 0)`) inside that call — which turned out to
   be bug #3's exact shape, caught by re-testing with values
   (`:min 10 :max 20`) that didn't happen to match the fallback.

3. **`:scale-spec`'s and `:spin-button-spec`'s `:min`/`:max` clobbered
   each other across separate renders.** Both `:apply` closures called
   one combined native function (`gtk_adjustment_configure` /
   equivalent) with a hardcoded fallback for whichever key was absent
   from the CURRENT `set-attribute` call — but `set-attribute` fires
   once per changed key, never as a batched map, even at initial
   mount. A render that changes only `:max` genuinely arrives as
   `{:max 80}` alone; a hardcoded `(or (:min p) 0)` then resets `:min`
   back to `0` even though nothing asked for that. Confirmed live via
   a dedicated probe: mounting `[:scale {:min 5 :max 50}]` produced an
   actual live range of `(0.0, 50.0)`, not `(5.0, 50.0)` — the bug
   fires even on the very first render, since `set-attributes` still
   delivers `:min` and `:max` as two separate `set-attribute` calls at
   create time. The existing `scale_smoke.clj` (`:min 0 :max 100`) and
   `spin_button_list_box_smoke.clj` (`:min 0`) both happened to choose
   `0` as their minimum, which is exactly the broken fallback — an
   order-dependent coincidence that masked the bug for 10 rounds.

   Fixed by reading the widget's OWN current adjustment value as the
   fallback instead of a hardcoded default:

   ```clojure
   (defn- scale-apply-range! [widget p]
     (when (or (contains? p :min) (contains? p :max))
       (let [adj (g/gtk-range-get-adjustment widget)]
         (g/gtk-adjustment-configure
          adj
          (double (or (:min p) (g/gtk-adjustment-get-lower adj)))
          (double (or (:max p) (g/gtk-adjustment-get-upper adj)))
          (g/gtk-adjustment-get-step-increment adj) 0.0 0.0 0.0))))
   ```

   `:spin-button-spec` got the identical fix via
   `gtk_spin_button_get_adjustment` instead of `gtk_range_get_adjustment`
   (GtkSpinButton is not a GtkRange subclass).

4. **Not fixed — documented v1 gap:** the audit found ONE more
   instance of the same shape, `window-spec`'s `:width`/`:height` ->
   `gtk_window_set_default_size`. Its counterpart getter,
   `gtk_window_get_default_size`, uses OUT-PARAMETERS — a genuinely new
   FFI marshalling class this project hasn't taken on — and the
   practical severity is much lower than the other three (an
   initial-sizing-only concern; the `-1` fallback GTK itself uses means
   "natural size," not garbage, so a `:width`-only re-render doesn't
   *break* anything, it just stops applying a previously-set `:height`
   on the next unrelated resize). Left as a documented comment above
   `window-spec` rather than fixed this round.

`examples/glitter/ctor_apply_regression_smoke.clj` pins bugs 1-3
permanently: it changes `:min`/`:max`/`:step` on SEPARATE re-renders
(not together in one hiccup swap) specifically to exercise the
one-key-at-a-time call pattern that caused the clobbering — changing
both together in one render would never have caught the original bug,
since that code path (`set-attribute` receiving two keys in one call)
never exists in the real reconciler at all.

**The rule going forward, stated for every future widget-spec author:**
design for props flowing entirely through `:apply`, never rely on
`:ctor` seeing anything. If `:ctor` branches on a prop for a
performance or correctness reason (e.g. picking the right
`gtk_*_new_with_*` constructor), that branch is dead code through the
real reconciler path — `:apply` must independently cover the same prop,
and if `:apply` combines multiple keys into one native call, it must
read the widget's OWN current values as fallbacks, never hardcoded
defaults.

## A second finding: namespaced keyword props are silently dropped

Before designing `:grid`'s structural-props mechanism, the original
plan used namespaced keys — `:grid/column`, `:grid/row`, `:stack/name`
— matching this project's own Clojure conventions
([`clojure/conventions.md`](../../CLAUDE.md)'s "keywords over strings
for keys" guidance, and simply looking more idiomatic). A throwaway
probe mounting `[:label {:my-plain-prop 42 :grid/column 2}]` and
printing every key `IRender/set-attribute` actually received showed
only `:my-plain-prop` ever arrived — `:grid/column` never reached
`set-attribute` at all, no error, no warning, just silently absent.

Root cause: `glitter.core`'s `set-attr` and `update-attr` both guard on
`(when-not (namespace attr) ...)` before calling into `IRender` —
inherited directly from Replicant's own convention that a namespaced
hiccup attribute is reserved for framework-internal use, not meant to
reach the DOM. glitter's port kept this guard verbatim (it's exactly
the mechanism `:glitter/remember`-style internal keys rely on to stay
invisible to `IRender/set-attribute`), and nothing about it is
glitter-specific or fixable at the widget-spec level — the drop happens
in `glitter.core`, upstream of every backend.

This is why `:grid-column`/`:grid-row`/`:grid-column-span`/
`:grid-row-span`/`:stack-name` (below) are plain, hyphenated,
non-namespaced keywords rather than the more idiomatic `:grid/column`
form: the namespaced form would have compiled, mounted with no error,
and simply never worked, for every consumer forever — a worse trap
than an unusual naming choice.

## `:glitter/structural-props` — a child's props read by its PARENT

Every container strategy up to this point — ordered append lists,
fixed named slots, the popup-surface special case — has one thing in
common: the PARENT alone decides where a child goes. `:grid` breaks
that: a `GtkGrid` cell's position is data the CHILD carries
(`:grid-column`/`:grid-row`/`:grid-column-span`/`:grid-row-span`), and
`:stack`'s page name (`:stack-name`) is the same shape — a child-borne
prop the parent needs at attach time.

Two things rule out routing these through the normal `:apply` path.
First, no widget's `:apply` closure has any way to know it's about to
be attached to a `:grid` versus a `:box` — `:apply` only ever sees its
OWN widget and its OWN props, never its parent. Second, even if it did,
`:apply` runs on an ALREADY-CONSTRUCTED widget; grid attachment is a
call the PARENT makes (`gtk_grid_attach`) at the moment the child is
inserted into the tree, not a property the child widget itself holds.

The mechanism: `glitter.gtk/set-attribute` and `remove-attribute`
special-case a small `structural-child-props` set, stashing matching
keys on the CHILD's own `el` atom instead of routing them to
`glitter.widget/apply-props!`:

```clojure
(def ^:private structural-child-props
  #{:grid-column :grid-row :grid-column-span :grid-row-span :stack-name})

(defn- structural-child-prop? [k] (contains? structural-child-props k))

(set-attribute [_ el a v _opt]
  (if (structural-child-prop? a)
    (swap! el assoc-in [:glitter/structural-props a] v)
    (w/apply-props! (:tag @el) (ptr el) {a v}))
  nil)
```

`append-child`, the fresh-insert branch of `insert-before`, and
`replace-child` all read `(:glitter/structural-props @child-node)` off
the CHILD and pass it as a new, optional trailing argument into
`glitter.widget`'s `append-child!`/`insert-child-after!`/
`replace-child!` — the only place with access to both the parent's
container kind and the child's stashed props:

```clojure
(w/append-child! (:tag @el) (ptr el) (ptr child-node) (:glitter/structural-props @child-node))
```

`glitter.widget`'s `grid-attach!` and `stack-append-child!` are the
consumers:

```clojure
(defn- grid-attach! [parent child structural-props]
  (let [{:keys [grid-column grid-row grid-column-span grid-row-span]} structural-props]
    (g/gtk-grid-attach parent child
                        (or grid-column 0) (or grid-row 0)
                        (or grid-column-span 1) (or grid-row-span 1))))

(defn- stack-append-child! [parent child structural-props]
  (if-let [name (:stack-name structural-props)]
    (g/gtk-stack-add-named parent child name)
    (g/gtk-stack-add-child parent child)))
```

`remove-child!` needed no threading at all — both `gtk_grid_remove` and
`gtk_stack_remove` identify the child by widget pointer alone, with no
position/name argument needed for removal.

**Known v1 constraint, deliberately not solved this round:**
structural props are read ONLY when a child is first attached (a fresh
mount or a keyed insert) — changing an already-attached child's
`:grid-column`/`:stack-name` on a LATER re-render does not move or
rename it, because there is no code path that re-reads
`:glitter/structural-props` for an already-parented child.
`reorder-child!`'s docstring documents this explicitly for both
containers: `:grid`/`:stack` positions are data-driven/name-addressed,
not order-driven, so "reorder" has no meaning for them the way it does
for `:box`. Fine for the common case of a static layout with fixed
positions; see [`limitations.md`](limitations.md).

## `:window-handle` — a quick win, and a bug in THIS round's own code

`GtkWindowHandle` has no signal of its own (confirmed: no
`g_signal_new` in `gtk/gtkwindowhandle.c`) — a single-child CSD
drag-handle wrapper, the same container strategy as `:frame`/
`:revealer`/`:expander`/`:search-bar`. Its first live smoke run caught
a real bug, but one shipped by THIS round's own new code, not a
pre-existing one: the `:window-handle` case branch was missing
entirely from `append-child!`/`remove-child!`/`replace-child!` in
`glitter.widget.clj`. The widget compiled cleanly and mounted with no
exception — `gtk_window_handle_set_child` simply never ran, so the
widget silently had no child at all. Caught immediately by
`window_handle_stack_smoke.clj` reading the child back and getting
nothing, before this ever shipped. Fixed by adding the three missing
`case` branches, mirroring every other single-child container already
in those functions.

## `:stack` — a third mount-time-auto-dispatch instance, and a real `:apply`-timing gap

`GtkStack` is a `:notebook` sibling with no visible tabs of its own —
pages are NAME-addressed (`:stack-name`, via the structural-props
mechanism above), not index-addressed the way `:notebook`'s pages are.
`gtk_stack_add_page`'s C body auto-selects the first added VISIBLE
child as the stack's `visible-child` — confirmed by reading it
directly — a THIRD instance of the exact mount-time-auto-dispatch
finding `:notebook` first surfaced two rounds ago. Mounting a `:stack`
with initial children genuinely dispatches a `"notify::visible-child-name"`
before any real interaction; `window_handle_stack_smoke.clj`'s
dispatch-count baseline starts at `1`, not `0`, for the same reason
`notebook_scale_button_smoke.clj`'s does.

A second, genuinely new finding fell out of writing this smoke: `:apply`
runs at `create!` time, BEFORE the reconciler has appended any
children (see the ctor/apply prop-flow section above) — so an initial
`:visible-child-name` prop always landed on a completely EMPTY stack.
`gtk_stack_set_visible_child_name` warned `Child name not found in
GtkStack` on every single fresh mount, silently falling back to GTK's
own auto-select-first-page behavior instead of the requested page.
Fixed the WARNING (not the underlying timing gap) by guarding
`set-stack-visible-child-name!` on `gtk_stack_get_child_by_name`,
proceeding only if the named page already exists:

```clojure
(defn- set-stack-visible-child-name! [widget name]
  (when (and name (g/gtk-stack-get-child-by-name widget name)
             (not= name (g/gtk-stack-get-visible-child-name widget)))
    (swap! suppressing conj widget)
    (g/gtk-stack-set-visible-child-name widget name)
    (swap! suppressing disj widget)))
```

This silences the spurious warning, but leaves a real, documented v1
gap: requesting a NON-default initial page still doesn't take effect
at mount, because `:apply` has no way to defer itself until after
children exist. `window_handle_stack_smoke.clj`'s own initial
`:stack-page` is deliberately `"a"` — the page GTK's own auto-select
would land on anyway — specifically to exercise the parts of `:stack`
that DO work correctly (real interaction, suppressing-guard
programmatic sync-back) rather than assert on the known gap. See
[`limitations.md`](limitations.md).

## `:drop-down` — the first "choose from options" widget

`GtkDropDown` needed a model — `GtkStringList` — built incrementally,
one string at a time, deliberately sidestepping
`gtk_drop_down_new_from_strings`'s raw C-string-array argument, an FFI
marshalling class (a `char**` array) this project has never needed:

```clojure
(defn- drop-down-build-model! [items]
  (let [model (g/gtk-string-list-new jolt.ffi/null)]
    (doseq [item items] (g/gtk-string-list-append model item))
    model))
```

Consistent with the round's "design for `:apply`, never `:ctor`" rule
(above): `:ctor` always builds an EMPTY `GtkStringList` — real props
are never present at `:ctor` time regardless, so there's no reason to
build anything else there — and `:apply`'s `:items` key calls
`gtk_drop_down_set_model` to swap in a freshly-built model whenever the
item list changes. `:selected` (a plain int index) follows the usual
set-compare-suppress shape every value-bearing widget here uses.

Both of `:drop-down`'s signals turned out to be free reuses, needing
zero new `glitter.gtk` callable-shape code: `"notify::selected"` is the
same 3-arg-void GObject property-change shape already generalized for
`:list-box`/`:expander`/`:paned`/`:stack` (above), and `"activate"` is
the same plain 2-arg-void shape `:menu-button` already registered.
`drop_down_grid_smoke.clj`'s selection round-trip (a real FFI
selection change bypassing the wrapper, then a programmatic sync-back)
proves both reuses hold for a fourth and fifth widget respectively,
not just that they compile.

## `:grid` — the first child-placement container

`GtkGrid`'s attachment API, `gtk_grid_attach(grid, child, column, row,
width, height)`, takes the child's cell position as direct arguments
at attach time — no separate "set cell" call the way `:center-box`'s
named-slot setters work, and no ordered-append semantics the way
`:box`'s do. This is what the `:glitter/structural-props` mechanism
(above) exists to serve: `grid-attach!` reads the child's stashed
`:grid-column`/`:grid-row`/`:grid-column-span`/`:grid-row-span` (each
defaulting to `0`/`0`/`1`/`1` when absent) and calls `gtk_grid_attach`
directly. `:row-spacing`/`:column-spacing` are the grid's OWN
(non-structural) props, applied the normal way through `:apply`.

`gtk_grid_remove` takes the child widget pointer directly, needing no
position information — `remove-child!`'s `:grid` branch is a one-line
`case` addition, no structural-props threading needed at all.
`gtk_grid_get_child_at(grid, column, row)` is the live-GTK verification
primitive `drop_down_grid_smoke.clj` uses to confirm real cell
placement, including a column-span cell verified by pointer-equality at
BOTH of the two coordinates it's meant to cover — proving the span
argument, not just the base column/row, reached GTK correctly.

`:grid` inherits `:glitter/structural-props`'s v1 constraint above: a
child's cell position is fixed at first attach, not reactive to a
later re-render's `:grid-column`/etc changes.

## `:scrolled` wraps a non-`GtkScrollable` child in a hidden `GtkViewport`

`:scrolled` shipped in round 1, forked verbatim from glimmer, but
`examples/glitter/crud.clj` (a port of the 7GUIs CRUD task) is the
first time any example or smoke in this project has actually given it
real content to wrap. Reading a live GTK widget tree back afterward via
the usual `gtk_widget_get_first_child` walk (every smoke's ground-truth
technique) initially found `:scrolled`'s child to be some OTHER widget
entirely, not the `:list-box` that was actually mounted inside it.

Root cause, confirmed by reading `gtk_scrolled_window_set_child`'s own
doc comment and C body directly: *"If `child` does not implement the
`GtkScrollable` interface, the scrolled window will add `child` to a
`GtkViewport` instance and then add the viewport as its child widget."*
`GtkListBox` does not implement `GtkScrollable` — confirmed against
`gtk/gtklistbox.c`'s `G_DEFINE_TYPE_WITH_CODE`, which lists no
`GTK_TYPE_SCROLLABLE` interface — so `:scrolled`'s REAL first GTK child
is a `GtkViewport`, and the `:list-box` is one level further down, as
the viewport's own first child:

```clojure
;; scrolled's :list-box child, read back from live GTK:
(-> scrolled g/gtk-widget-get-first-child   ; the auto-inserted GtkViewport
    g/gtk-widget-get-first-child)           ; the actual :list-box
```

This is pure GTK behavior, not a glitter concern for ordinary hiccup
authors — `glitter.gtk`'s own `:children` bookkeeping for the
`:scrolled` element never sees the viewport at all (it only ever calls
`gtk_scrolled_window_set_child` once, with the `:list-box` pointer; GTK
manages the viewport-wrapping internally and transparently). It only
matters to code that reads the LIVE tree back via raw FFI walks — every
live-GTK smoke in this project — which now needs to know to descend one
extra level whenever the wrapped child is something non-scrollable like
`:list-box`/`:flow-box`/`:grid`. A child that DOES implement
`GtkScrollable` (nothing in this project's widget set does yet) would
not get this extra wrapper.

## `list-box-reorder-child!`/`flow-box-reorder-child!` — a real use-after-dispose bug

Also found while building `examples/glitter/crud.clj`: renaming a
selected person's family name to something that sorts to a DIFFERENT
position in the filtered/sorted list triggers a KEYED REORDER —
`glitter.core`'s reconciler recognizes the row's `:glitter/key` as the
SAME logical child, just needing a new position, and dispatches to
`IRender/insert-before` → (since the child is already tracked)
`list-box-reorder-child!`. `docs/guide/limitations.md` had already
flagged this exact path — a same-key reposition, not a tag-swap or a
plain append/remove — as *"implemented but not previously
live-verified"*. It wasn't safe: the live GTK tree crashed with
`gtk_list_box_insert: assertion 'GTK_IS_WIDGET (child)' failed` plus
cascading assertion failures on other widgets still touching the same
now-invalid pointer.

Root cause, confirmed by reading GTK source directly rather than
assumed: `list-box-reorder-child!` removes the child's OLD row first
(`gtk_list_box_remove parent row`), then tries to reuse the SAME
`child` pointer for the reinsert. `gtk_list_box_remove`, once nothing
else references the row, disposes it immediately — and
`GtkListBoxRow`'s own `dispose` handler unparents *its own child*:

```c
/* gtk_list_box_row_dispose's actual C body — confirmed by reading it
   directly */
static void
gtk_list_box_row_dispose (GObject *object)
{
  GtkListBoxRowPrivate *priv = ROW_PRIV (GTK_LIST_BOX_ROW (object));
  ...
  g_clear_pointer (&priv->child, gtk_widget_unparent);
  ...
}
```

With nothing else in glitter holding an independent reference to that
child, `gtk_widget_unparent` here doesn't just detach it — it drops the
child's refcount to zero and finalizes it too. `child` is a genuinely
DANGLING pointer by the time `list-box-insert-after!` tries to reuse
it. `flow-box-reorder-child!` has the byte-for-byte identical shape,
confirmed independently rather than assumed to carry over just because
the widgets are siblings: `gtk_flow_box_child_dispose` has the same
`g_clear_pointer (&priv->child, gtk_widget_unparent)` call.

The fix brackets the remove-then-reinsert with `g_object_ref_sink` /
`g_object_unref` — the standard GTK C idiom for surviving a reparent
gap where the widget would otherwise be briefly, unintentionally
ownerless:

```clojure
(defn- list-box-reorder-child! [parent child sibling]
  (let [row (list-box-row-of child)]
    (when row
      (g/g-object-ref-sink child)     ; extra ref — survives the row's disposal
      (swap! suppressing conj parent)
      (g/gtk-list-box-remove parent row)
      (swap! suppressing disj parent))
    (list-box-insert-after! parent child sibling)
    (when row
      (g/g-object-unref child))))     ; release it — child is now owned by its NEW row
```

`g_object_ref_sink` is safe to call on an ALREADY-sunk, parented widget
too (its own semantics: a normal `+1` ref if the object isn't floating)
— it isn't a floating-ref-specific trick, which is why it's the right
primitive here even though `child` was never floating at this call
site (it was sunk into its OLD row long ago). Both
`g-object-ref-sink`/`g-object-unref` were already bound in
`glitter.ffi` (inherited from glimmer's own fork, describing GTK's
general floating-ref convention in the ns docstring) but had never
actually been called from glitter's own code until this fix — the
first real use of either.

`examples/glitter/list_box_reorder_smoke.clj` pins this permanently:
reorders a keyed `:list-box` and a keyed `:flow-box` by the SAME keys
(no add/remove), and reads the new order back via the usual
`gtk_widget_get_first_child`/`get_next_sibling` ground-truth walk, not
glitter's own bookkeeping — the same discipline
`examples/glitter/keyed.clj` established for `:box`'s own keyed
reorder. FAIL-path verified by temporarily reverting the fix: the
smoke crashes with the exact assertion failures above and exits 1;
restoring the fix returns it to a clean exit 0.

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
