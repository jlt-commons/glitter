# Widget reference

Every hiccup tag glitter registers, what props it takes, and what it sends
back. This is the "how do I use it" page;
[`gtk-widget-layer.md`](gtk-widget-layer.md) is the "how it was built and
what it taught" page, and they deliberately do not duplicate each other.

Every snippet below is lifted from a runnable file. The four galleries
under `examples/glitter/` between them use all 43 tags, and
`gallery_smoke.clj` mounts all four against live GTK on every `bb smokes`
run, so nothing here can quietly stop compiling:

| gallery | run | covers |
|---|---|---|
| [`gallery_inputs.clj`](../../examples/glitter/gallery_inputs.clj) | `bb gallery-inputs` | the value-bearing widgets |
| [`gallery_layout.clj`](../../examples/glitter/gallery_layout.clj) | `bb gallery-layout` | the containers |
| [`gallery_display.clj`](../../examples/glitter/gallery_display.clj) | `bb gallery-display` | the read-only widgets |
| [`gallery_chrome.clj`](../../examples/glitter/gallery_chrome.clj) | `bb gallery-chrome` | app chrome and navigation |

Recordings of all four, with what each one shows, are in
[`examples.md`](examples.md#widget-galleries).

## The three rules that apply to every tag

**Props are applied, never diffed away.** A prop set to a new value always
reaches the widget, including an explicit `false`. *Removing* a key from
your hiccup does nothing — GTK has no generic "unset this property", so the
widget keeps what it last had. See
[`limitations.md`](limitations.md#removing-an-attribute-entirely-is-a-no-op).

**Handlers are data, not closures.** `:on {:click [[:action/inc]]}` — the
vector of action tuples is dispatched through the one fn you registered
with `core/set-dispatch!`. The same handler position can carry different
data across renders without the widget or its signal connection being torn
down. `core/set-dispatch!` installs *one* process-global dispatch fn, so a
single process hosts a single app.

**A signal's value does not travel in the action tuple.** Hiccup is fixed
when `view` runs, so an action cannot carry a value that only exists once
the user types. The value arrives on the event instead, and
`[:glitter/value]` is the placeholder that pulls it out:

```clojure
[:entry {:text (:draft state)
         :on {:change [[:effect/assoc-in [:draft] [:glitter/value]]]}}]
```

Which signals actually carry a value is the single most useful thing to
know about this API, so it has [its own table](#which-signals-carry-a-value)
below.

## Universal props

Accepted by every tag, in addition to its own:

| prop | value |
|---|---|
| `:margin` | px, all four sides at once |
| `:margin-start` `:margin-end` `:margin-top` `:margin-bottom` | px, individually |
| `:halign` `:valign` | `:start` `:end` `:center` `:fill` `:baseline` |
| `:hexpand` `:vexpand` | boolean — take extra space along that axis |
| `:width-request` `:height-request` | px, a minimum |
| `:class` | vector of CSS class names, e.g. `["error"]` |

`:class` reaches real GTK CSS classes. `:style` is accepted and diffed but
does nothing: GTK4 has no inline-style equivalent to wire it to.

`:sensitive false` greys a widget out and is accepted by most interactive
tags (`:button`, `:entry`, `:drop-down`, `:spin-button`, …).

## Inputs

Widgets that produce a value. From
[`gallery_inputs.clj`](../../examples/glitter/gallery_inputs.clj).

### Text

`:entry`, `:password-entry`, `:search-entry` and `:editable-label` all
implement `GtkEditable` through a delegate, so all four report their text
through the same `:change` signal.

```clojure
[:entry {:text (:entry state) :placeholder "type here"
         :on {:change [[:effect/assoc-in [:entry] [:glitter/value]]]}}]

[:password-entry {:text (:password state) :show-peek-icon true
                  :on {:change [[:effect/assoc-in [:password] [:glitter/value]]]}}]

;; :search-changed is debounced by :search-delay ms — except clearing to
;; empty, which fires immediately.
[:search-entry {:text (:search state) :placeholder "filter" :search-delay 100
                :on {:search-changed [[:effect/assoc-in [:search] [:glitter/value]]]}}]

;; click-to-edit, GTK's own gesture; :editing drives it programmatically
[:editable-label {:text (:name state)
                  :on {:change [[:effect/assoc-in [:name] [:glitter/value]]]}}]
```

| tag | props | signals |
|---|---|---|
| `:entry` | `:text` `:placeholder` `:sensitive` | `:change` |
| `:password-entry` | `:text` `:show-peek-icon` `:sensitive` | `:change` |
| `:search-entry` | `:text` `:placeholder` `:search-delay` `:sensitive` | `:change` `:search-changed` |
| `:editable-label` | `:text` `:editing` `:sensitive` | `:change` |

**`:password-entry` does not take `:placeholder`.** GTK exposes no C setter
for it on that widget — `placeholder-text` is property-only there — so
glitter does not accept a prop it cannot honour. It used to, routing all
three through `gtk_entry_set_placeholder_text`, which asserts `GTK_IS_ENTRY`
and therefore did nothing at all on `:password-entry` and `:search-entry`.
`:search-entry` has its own setter and works; `:password-entry` is a real
gap, gated by `gallery_smoke.clj`.

### Numbers

Three widgets, all emitting a signal *named* `value-changed`, all needing a
different getter to read back — which is why glitter keys its value table by
`[tag signal]` rather than by signal name.

```clojure
[:spin-button {:min 0 :max 10 :step 1 :value (:spin state) :digits 0
               :on {:value-changed [[:effect/assoc-in [:spin] [:glitter/value]]]}}]

[:scale {:min 0 :max 100 :step 1 :value (:scale state) :digits 0
         :draw-value true
         :on {:value-changed [[:effect/assoc-in [:scale] [:glitter/value]]]}}]

;; a speaker-style popup slider
[:scale-button {:min 0 :max 1 :step 0.1 :value (:volume state)
                :on {:value-changed [[:effect/assoc-in [:volume] [:glitter/value]]]}}]
```

`:min`/`:max`/`:step` are re-applied on every render, and each reads the
widget's own current value as its fallback — so changing only `:max` no
longer clobbers `:min`, which was a real shipped bug (see
[`gtk-widget-layer.md`](gtk-widget-layer.md)).

### Booleans

```clojure
;; :switch carries its new value
[:switch {:active (:on? state)
          :on {:state-set [[:effect/assoc-in [:on?] [:glitter/value]]]}}]

;; :checkbutton and :toggle-button do NOT — see the note below
[:checkbutton {:label "check" :active (:check? state)
               :on {:toggled [[:action/toggle :check?]]}}]
[:toggle-button {:label "toggle" :active (:toggle? state)
                 :on {:toggled [[:action/toggle :toggle?]]}}]
```

`:checkbutton` and `:toggle-button` have **no value registered** for
`toggled`, so `[:glitter/value]` would resolve to `nil` and write `nil` into
your state. Use an action-expansion that reads the current value and flips
it:

```clojure
(nxr/register-action! :action/toggle
                      (fn [state k] [[:effect/assoc-in [k] (not (get state k))]]))
```

### Choosing, and dates

```clojure
;; delivers an int INDEX, not the item text
[:drop-down {:items ["apple" "banana" "cherry"] :selected (:fruit state)
             :on {:selected-changed [[:effect/assoc-in [:fruit] [:glitter/value]]]}}]

;; :date is a [year month day] vector, in and out
[:calendar {:date (:date state)
            :on {:day-selected [[:effect/assoc-in [:date] [:glitter/value]]]}}]

[:button {:label "click me" :tooltip "does a thing"
          :on {:click [[:action/click]]}}]
[:link-button {:label "glitter" :uri "https://github.com/jlt-commons/glitter"}]
```

## Containers

How each one decides where a child goes. From
[`gallery_layout.clj`](../../examples/glitter/gallery_layout.clj).

### Ordered — children append in hiccup order

```clojure
[:box {:spacing 8 :orientation :horizontal}  ; :hbox / :vbox are sugar
 [:label {:label "a"}]
 [:label {:label "b"}]]

[:flow-box {} …]                              ; wraps
[:list-box {:on {:row-selected [[:effect/assoc-in [:row] [:glitter/value]]]}} …]
```

`:list-box` and `:flow-box` each wrap every child in an opaque row object,
which is why a keyed reorder of either had a use-after-dispose bug worth its
own smoke. `:list-box`'s selection is a row **index**.

### Single child

`:frame` `:aspect-frame` `:scrolled` `:revealer` `:expander` `:search-bar`
`:window-handle` `:popover` all take exactly one child.

```clojure
[:frame {:label "titled"} [:label {:label "inside"}]]
[:aspect-frame {:ratio 2.0 :obey-child false} [:label {:label "2:1"}]]
[:scrolled {} [:vbox {} …]]
```

A `:scrolled` child that is not `GtkScrollable` (a `:vbox` is not) gets
silently wrapped by GTK in its own `GtkViewport`, so the live widget tree
has one more level than your hiccup does.

### Fixed named slots — position comes from child order

```clojure
[:center-box {}                 ; exactly three: start, center, end
 [:label {:label "start"}] [:label {:label "center"}] [:label {:label "end"}]]

[:paned {:position (:divider state)   ; exactly two: start, end
         :on {:position-changed [[:effect/assoc-in [:divider] [:glitter/value]]]}}
 [:label {:label "start pane"}] [:label {:label "end pane"}]]

[:overlay {}                    ; first child is content; the rest float on top
 [:label {:label "main"}] [:label {:label "floating" :halign :end}]]
```

**Do not swap a slot's hiccup TAG while the slots are occupied.** The
reconciler handles a tag mismatch as insert-new-then-remove-old, and these
containers have no room to hold both — in `:center-box` that corrupts an
unrelated third slot. Change props, or nest a stable wrapper tag one level
down. Full reasoning in [`limitations.md`](limitations.md).

### Child-driven placement

`:grid` is the one container where the position lives on the **child**,
because `gtk_grid_attach` takes it as arguments and no widget's own prop
applier could know what it is about to be attached to.

```clojure
[:grid {:row-spacing 4 :column-spacing 10}
 [:label {:label "r0 c0" :grid-column 0 :grid-row 0}]
 [:label {:label "r0 c1" :grid-column 1 :grid-row 0}]
 [:label {:label "spans"  :grid-column 0 :grid-row 1 :grid-column-span 2}]]
```

`:grid-column` `:grid-row` `:grid-column-span` `:grid-row-span`, and
`:stack-name` for `:stack`, are **plain, non-namespaced keywords on
purpose**. `glitter.core` drops namespaced attributes upstream of every
backend, so `:grid/column` would silently never arrive. They are also read
only when a child is *first* attached: changing one on a later render does
not move the child.

## Display

No signal of their own; driven entirely by re-applied props. From
[`gallery_display.clj`](../../examples/glitter/gallery_display.clj).

```clojure
[:label {:markup "value is <b>42</b>" :xalign 0.0 :wrap true}]
[:inscription {:text "cheap text" :text-overflow :ellipsize-end}]
[:progress-bar {:fraction 0.75 :show-text true :text "75%"}]
[:level-bar {:min-value 0 :max-value 100 :value 40}]
[:spinner {:spinning true}]
[:image {:icon-name "dialog-information" :pixel-size 32}]
[:picture {:file "examples/assets/glitter-mark.png" :content-fit :contain
           :can-shrink true :alternative-text "the mark"}]
[:revealer {:reveal-child true :transition-type :slide-down
            :transition-duration 250} [:label {:label "revealed"}]]
[:separator {:orientation :horizontal}]
```

Two pairs that are easy to confuse:

- **`:label` vs `:inscription`.** `:label` is the general-purpose text
  widget and takes Pango markup. `:inscription` is GTK's cheap one, for many
  short throwaway labels; no markup, and it ellipsizes via `:text-overflow`
  rather than `:ellipsize`.
- **`:image` vs `:picture`.** `:image` is for icons at a fixed
  `:pixel-size`. `:picture` is `GdkPaintable`-based and scales to its
  allocation, so it is the one for real image content.

`:expander` is display-shaped but does have a signal, since the user can
open it: `:on {:expanded [[…]]}` carries the new boolean.

## Chrome and navigation

Controlled components: the widget has its own idea of what is showing,
you hold state, and a signal keeps them in step. From
[`gallery_chrome.clj`](../../examples/glitter/gallery_chrome.clj).

```clojure
;; first child is the title/center widget; the rest pack to the start
[:header-bar {:show-title-buttons false}
 [:label {:markup "<b>Title</b>"}]
 [:menu-button {:label "menu" :on {:activate [[:action/menu-opened]]}}
  ;; a menu button's single child is attached as its popover — a real
  ;; parenting relationship, not an ordinary tree child
  [:popover {:visible (:menu-open? state) :has-arrow true
             :on {:closed [[:effect/assoc-in [:menu-open?] false]]}}
   [:label {:label "popover content"}]]]]

[:notebook {:current-page (:page state)
            :on {:switch-page [[:effect/assoc-in [:page] [:glitter/value]]]}}
 [:label {:label "page 0"}] [:label {:label "page 1"}]]

[:stack {:visible-child-name (:page-name state)
         :on {:visible-child-changed [[:effect/assoc-in [:page-name] [:glitter/value]]]}}
 [:label {:label "one" :stack-name "one"}]
 [:label {:label "two" :stack-name "two"}]]

[:search-bar {:search-mode (:searching? state) :show-close-button false}
 [:search-entry {:placeholder "search…"}]]

[:action-bar {:revealed true} [:label {:label "centre"}] [:button {:label "start"}]]
```

Three behaviours here are real GTK, not glitter quirks:

- **Mounting `:notebook` or `:stack` with pages already in them
  dispatches.** GTK auto-selects the first page as it is added, so each
  emits once before any user interaction. `gallery_smoke.clj` pins this at
  exactly two dispatches for a view containing one of each. A dispatch count
  of 0 right after mount is the wrong expectation.

  There is a sharper edge to this. `mount!` renders once and *then* installs
  its watcher (`render!`, then `add-watch`, in that order), and those
  auto-select dispatches land during that first render — so the state
  changes with no watcher to notice, and the screen does not update. A view
  that displays something derived from those dispatches shows a stale value
  until the next unrelated state change re-renders it. `gallery-chrome`
  demonstrates this deliberately: its counter reads 0 on a freshly mounted
  window while the atom already holds 2.
- **`:header-bar`'s `:show-title-buttons` shares your pack-start region**
  with GTK's own window-controls widget, prepended.
- **Neither bar can `pack_end`**, and neither can reorder its pack-start
  region. `gtk_header_bar_pack_end` prepends into its own end region, so
  feeding children one at a time — as the reconciler does — would silently
  reverse them, with no public API to fix the order afterwards.

`:popover` is opened by `:menu-button`'s own internal click handling, which
glitter never sees. That is what `:on {:activate …}` is for: the app learns
the popover opened and syncs its `:visible` state to match.

## Which signals carry a value

`[:glitter/value]` resolves to the value in the right-hand column. Where it
says **none**, the placeholder resolves to `nil` — read what you need from
state instead.

| tag | signal | `[:glitter/value]` is |
|---|---|---|
| `:entry` `:password-entry` `:search-entry` `:editable-label` | `:change` | the text |
| `:search-entry` | `:search-changed` | the text |
| `:scale` | `:value-changed` | a double |
| `:spin-button` | `:value-changed` | a double |
| `:scale-button` | `:value-changed` | a double |
| `:switch` | `:state-set` | the new boolean |
| `:list-box` | `:row-selected` `:row-activated` | the row index |
| `:expander` | `:expanded` | the new boolean |
| `:paned` | `:position-changed` | the divider px |
| `:calendar` | `:day-selected` | `[year month day]` |
| `:notebook` | `:switch-page` | the new page index |
| `:stack` | `:visible-child-changed` | the visible child's name |
| `:drop-down` | `:selected-changed` | the selected index |
| `:button` `:link-button` | `:click` | **none** |
| `:checkbutton` `:toggle-button` | `:toggled` | **none** |
| `:menu-button` | `:activate` | **none** |
| `:popover` | `:closed` | **none** |
| `:flow-box` | `:child-activated` | **none** (would need `GList` marshalling) |

## Adding a widget glitter does not ship

```clojure
(w/register-widget! :my-thing
                    {:ctor (fn [props] (my-ffi/thing-new))
                     :apply (fn [widget props]
                              (when (contains? props :label)
                                (my-ffi/thing-set-label widget (:label props))))
                     :container :none})

;; :on {:pinged …} -> the "pinged" GTK signal, carrying no value
(w/register-signal! :my-thing :on-pinged "pinged")

;; …or with a value-fn as the optional 4th argument, so [:glitter/value]
;; resolves to whatever it reads back. `tag` is required either way: the
;; value-fn is looked up by [tag signal], because more than one widget can
;; emit the same signal NAME meaning different things — :scale,
;; :spin-button and :scale-button all emit "value-changed".
(w/register-signal! :my-thing :on-pinged "pinged"
                    (fn [w] (my-ffi/thing-get-value w)))
```

A signal with GTK's usual `void(widget, user_data)` shape works this way for
free. **Anything else needs a hand-written branch in
`glitter.gtk/set-event-handler`** — it cannot be registered as data, because
jolt's `foreign-callable` requires literal argtypes at the call site. See
[`gtk-widget-layer.md`](gtk-widget-layer.md) for the four shapes that
already needed one, and `CONTRIBUTING.md` for the walkthrough.
