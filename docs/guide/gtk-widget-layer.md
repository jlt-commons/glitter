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
