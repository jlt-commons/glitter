# glitter.nexus: data-driven action/effect dispatch

`glitter.nexus` is a port of [nexus](https://github.com/cjohansen/nexus)
(same author as Replicant), a small, toolkit-agnostic action/effect/
placeholder dispatch engine. It sits directly downstream of
`core/set-dispatch!` (see [`architecture.md`](architecture.md)): where
`glitter.core` decides *when* to call a dispatch fn and *what data* to
hand it, `glitter.nexus` decides *what to do* with that data once
`set-dispatch!`'s registered fn receives it. See
[`porting-and-attribution.md`](porting-and-attribution.md)'s Bucket 4
for the exact file-by-file porting ledger.

## Why

Every glitter demo before this arc (`counter.clj`, `todo.clj`, `crud.clj`)
hand-wrote one `case` branch per action kind inside its own
`execute-actions` fn, manually reading
`(get-in event [:glitter/dom-event :glitter/value])` and `swap!`-ing the
state atom directly. This works, but (per this project's own design
spec for the arc) "it's boilerplate that scales linearly with the
number of interactive fields, and it puts side-effecting `swap!` calls
in the same function that also has to make domain decisions (should
this add a task? should this replace the selected person?)."

nexus solves this generically: **actions** are plain data
(`[:effect/assoc-in [:draft] [:glitter/value]]`) dispatched through a
registry of **effect** handlers (the ONLY functions allowed to mutate
anything), and **placeholder** resolvers that substitute event-derived
values into action data before it's used. Actions that need to make a
decision based on current state, not just pass an event value through,
register as **expansions**: pure functions of `(state & args)`
returning more actions/effects. The whole point: the only place a
`swap!` (or any side effect) can happen is inside a registered effect
handler; everything else, including "what should happen when this
button is clicked," is data a pure function computes.

## The four concepts

### Effects: the only place a `swap!` is allowed

An effect handler is a plain function registered under an action-kind
keyword. `glitter.nexus.registry/register-effect!` stores it under
`[:nexus/effects effect-k]`:

```clojure
(defn ^{:indent 1} register-effect! [effect-k f]
  (swap! !registry assoc-in [:nexus/effects effect-k] f))
```

Every demo in this project registers exactly one effect,
`:effect/assoc-in`, identical across `flights.clj`, `crud.clj`, and
`todo.clj`:

```clojure
(nxr/register-effect! :effect/assoc-in
                      (fn [_ system path v] (swap! system assoc-in path v)))
```

`glitter.nexus/execute-effect` is what actually calls it: it threads
the effect fn through `run-interceptors` (below), then invokes
`(apply effect-f (->execute-ctx ctx*) system (next effect))`. The first
argument (an execute-ctx, ignored by every effect registered in this
project so far) gives an effect a way to recursively `:dispatch` more
actions from inside itself, not used by `:effect/assoc-in`, but part
of why the arg list has a leading, usually-unused context arg.

### Placeholders: resolving event data into action data

Hiccup `:on` data is fixed at the moment `view` runs (see CONTRIBUTING.md
invariant #8); it can't carry a value that only exists once the user
types. nexus's placeholder mechanism is the generic version of the
`:glitter/value`-in-the-event-map trick every demo already needs:
`glitter.nexus/interpolate-walk` walks an action's data, and any nested
vector whose head matches a registered placeholder keyword gets replaced
by calling that placeholder's function with `dispatch-data` (and the
placeholder's own trailing args):

```clojure
(defn ^:no-doc interpolate-walk [placeholders interpolations dispatch-data x]
  (if (-> x meta :nexus/skip-interpolation)
    x
    (let [x' (if (coll? x)
               (walk/walk #(interpolate-walk placeholders interpolations dispatch-data %) identity x)
               x)]
      (if-let [f (when (vector? x')
                   (get placeholders (first x')))]
        (let [resolution (apply f dispatch-data (next x'))]
          (swap! interpolations conj {:placeholder x'
                                      :resolution resolution})
          resolution)
        x'))))
```

This is why `[:effect/assoc-in [:draft] [:glitter/value]]` works as
hiccup `:on` data: `[:glitter/value]` is itself a nested vector headed
by a registered placeholder keyword, so before `:effect/assoc-in`'s fn
ever runs, `interpolate-1` rewrites the whole action to
`[:effect/assoc-in [:draft] "whatever was typed"]`. `flights.clj` adds
a second placeholder, `:fmt/nth`, for its `:drop-down`'s int-index
selection (mirroring nexus's own `:fmt/long`/`:fmt/number` convention
(see `dev/counter/core.cljc` and the "Nested placeholders" section of
upstream's `Readme.md`) adapted because `:drop-down`'s value-fn
already delivers an int index rather than the raw DOM input string
`:fmt/long`/`:fmt/number` are built to convert):

```clojure
:nexus/placeholders
{:glitter/value (fn [event] (get-in event [:glitter/dom-event :glitter/value]))
 :fmt/nth (fn [_ coll idx] (nth coll idx))}
```

used as `[:effect/assoc-in [:type] [:fmt/nth [:one-way :roundtrip]
[:glitter/value]]]`; placeholders nest, so `:glitter/value` resolves
first (innermost), then `:fmt/nth` indexes into the literal
`[:one-way :roundtrip]` vector with that resolved int.

### Actions and expansions: pure functions of state, not `swap!`

An action that needs to *decide* what should happen (not just pass an
event value through) registers as an expansion:
`glitter.nexus.registry/register-action!` and `register-expansion!` are
literally the same function body, both writing to
`[:nexus/expansions action-k]`:

```clojure
(defn ^{:indent 1} register-action! [action-k f]
  (swap! !registry assoc-in [:nexus/expansions action-k] f))

(defn ^{:indent 1} register-expansion! [action-k f]
  (swap! !registry assoc-in [:nexus/expansions action-k] f))
```

(Verified by reading `glitter.nexus.registry` directly; this isn't a
glitter deviation, it's how upstream `nexus.registry` ships too, kept
byte-for-byte.) `glitter.nexus/dispatch-action` checks `:nexus/expansions`
before `:nexus/effects` when resolving an action kind, so an
action-expansion fn takes priority over a same-named effect if both
happen to be registered (none of this project's demos register both
under the same keyword). `crud.clj`'s `:action/select-row` is a
representative expansion; a pure function of `(state & args)` that
reads current state and returns MORE actions rather than mutating
anything itself:

```clojure
(nxr/register-action! :action/select-row
                      (fn [state idx]
                        (if-let [person (nth (vec (get-people state)) idx nil)]
                          [[:effect/assoc-in [:selected-id] (:id person)]
                           [:effect/assoc-in [:given-name] (:given-name person)]
                           [:effect/assoc-in [:family-name] (:family-name person)]]
                          [])))
```

One live-verified arity gotcha, found while retrofitting `crud.clj`
(Task 6 of this arc): `[[:action/select-row]]` (the naive hiccup
`:on` value with no trailing action-tuple data) silently mismatches
`register-action!`'s 2-arg fn (`state` and `idx`), and the resulting
exception is swallowed into nexus's own `:errors` accumulator (see
`try-f`/`log-error` below), not thrown. The fix is
`[[:action/select-row [:glitter/value]]]`: the placeholder resolves
`idx` from the dispatched list-box row index *before* the action fn
runs, giving it the correct 2-arg shape. If an action-expansion silently
never fires, check `:nexus/on-error`'s log output before assuming the
action itself is buggy.

### Interceptors: the mechanism the whole engine is built on

Every dispatch phase (the outer dispatch, each action, each effect)
runs through `run-interceptors`, which threads state through a stack of
`before-*`/`after-*` handler pairs, catching exceptions per-step via
`try-f` (so one interceptor's error doesn't kill the whole dispatch):

```clojure
(defn ^{:indent 1
        :no-doc true} run-interceptors [ctx interceptors [before after k]]
  (letfn [(invoke [f state phase interceptor]
            (->> (select-keys interceptor [:id])
                 (into (cond-> {:phase phase}
                         k (assoc k (get ctx k))))
                 (try-f state f)))]
    (loop [state (assoc ctx :queue interceptors :stack ())]
      (cond
        (:queue state)
        (let [interceptor (first (:queue state))
              state (-> (update state :queue next)
                        (update :stack conj interceptor))]
          (recur (invoke (get interceptor before) state (or (:phase interceptor) before) interceptor)))

        (:stack state)
        (let [interceptor (first (:stack state))
              state (update state :stack next)]
          (recur (invoke (get interceptor after) state after interceptor)))

        :else state))))
```

No demo in this arc registers a custom interceptor: `:nexus/interceptors`
stays empty in `flights.clj`/`crud.clj`/`todo.clj`. The one concrete
interceptor in this codebase is `glitter.nexus.action-log`'s
`get-interceptor` (see below), which any consumer can add via
`install-logger`, but none currently do.

## The glitter-specific wiring convention

`glitter.nexus`/`glitter.nexus.registry` stay 100% toolkit-agnostic,
faithful to upstream's own separation: `nexus.core` doesn't know about
the DOM any more than it should know about GTK. Two pieces are
glitter-specific and deliberately live in **each consuming demo**, not
in the ported files, mirroring how upstream's own dev example,
`dev/counter/core.cljc`, registers its own DOM-specific placeholders
(`:event.target/value`) rather than baking them into `nexus.core`:

- **The `:glitter/value` placeholder**:
  `(fn [event] (get-in event [:glitter/dom-event :glitter/value]))`.
  `event` here is the `dispatch-data` argument nexus's `dispatch` was
  called with, which (per `glitter.core/set-dispatch!`'s contract) is
  the map `glitter.core/build-event-map` builds
  (`{:glitter/trigger :glitter.trigger/dom-event :glitter/dom-event e
  ...}`); `e` is the raw event object `glitter.gtk/set-event-handler`
  constructs, which stuffs the widget's current value onto it as
  `:glitter/value` for any value-bearing signal (see
  [`gtk-widget-layer.md`](gtk-widget-layer.md) and CONTRIBUTING.md invariant
  #8). This is the SAME value every demo read by hand before this arc;
  registering it as a nexus placeholder just moves the `get-in` call out
  of a hand-written dispatch fn and into data.
- **`:nexus/on-error` → `clojure.tools.logging`**: every demo registers
  `(nxr/on-error (fn [_ctx {:keys [err] :as error}] (log/error err
  "glitter.nexus dispatch error" (dissoc error :err))))`. `glitter.nexus/
  log-error` calls this on ANY caught exception anywhere in the dispatch
  pipeline (a bad effect, a bad expansion, an unresolved effect
  handler); without it, errors are silently swallowed into
  `ctx :errors` and never surface anywhere. This is the reason
  `jolt-lang/logging` (a port of `clojure.tools.logging`) was added to
  `deps.edn` in this arc.

Both are toolkit-specific choices a Reagent/glimmer app or a
`nexus.core`-only web app wouldn't share, which is exactly why they
don't belong in `glitter.nexus`/`glitter.nexus.registry` themselves.

## Two consumer shapes

### `flights.clj`: pure effects only

`examples/glitter/flights.clj` (the 7GUIs Flight Booker) is the first
real consumer of `glitter.nexus`, and every interaction in it
dispatches at most two effects, never an action expansion: every
field is a pure `:effect/assoc-in` plus a registered placeholder,
except the "Try again" button, which dispatches two `:effect/assoc-in`
calls back to back. Zero hand-written case-dispatch code either way,
and no `:nexus/actions`/`:nexus/expansions` registered at all:

```clojure
[:entry {:text value :hexpand true
         :class (if error? ["error"] [])
         :on {:change [[:effect/assoc-in [path] [:glitter/value]]]}}]
```

`flights.clj` never calls `nxr/register-system->state!` at all: unlike
`crud.clj`/`todo.clj` (both call `(nxr/register-system->state! deref)`),
it doesn't need to. `glitter.nexus/dispatch`'s own assert
(`(when (:nexus/expansions nexus) (assert (or (ifn? (:nexus/system->state
nexus)) (ifn? (:nexus/system+dispatch-data->state nexus))) "Either ...
must be a function"))`) only fires when `:nexus/expansions` is
non-nil, and `flights.clj` registers no actions/expansions at all, so
`:nexus/expansions` stays nil throughout. Every effect in `flights.clj`
is a direct `assoc-in` on the raw system atom; nothing reads derived
state back through nexus.

### `crud.clj`/`todo.clj`/`temperature.clj`/`timer.clj`: action-expansions

`crud.clj` and `todo.clj` were both retrofitted onto `glitter.nexus` in
this arc (replacing a hand-written `execute-actions` `case` form);
`temperature.clj` (the 7GUIs Temperature Converter) and `timer.clj`
(the 7GUIs Timer) were both written against `glitter.nexus` from the
start, like `flights.clj` before them. All four register
`:nexus/expansions` (via `register-action!`/`register-expansion!`
(see above), the layer `flights.clj` never needs at all), but for a few
different reasons. `crud.clj`'s
`:action/select-row`/`:action/create`/`:action/update`/`:action/delete`
and `todo.clj`'s `:action/toggle`/`:action/add-task` need to READ
current state to decide what should happen. `todo.clj`'s
`:action/toggle` is the simplest expansion in the
codebase; reads the row's CURRENT `:done` value to `not` it, something
a pure `:effect/assoc-in` literally cannot express since it has no way
to read state before writing:

```clojure
(nxr/register-action! :action/toggle
                      (fn [state idx]
                        [[:effect/assoc-in [:tasks idx :done] (not (get-in state [:tasks idx :done]))]]))
```

`temperature.clj`'s single `:action/set-temperature` needs an
expansion for a different reason: it doesn't read app STATE at all
(its fn signature is `(fn [_state temps] ...)`, ignoring the first
arg): it branches on which key is present in the DISPATCH DATA (has
`:celsius` come through, or `:fahrenheit`?) to decide which field is
the source and which is derived, something a single bare
`:effect/assoc-in` can't express either, just for a different reason
than `crud.clj`/`todo.clj`'s state-reads. Because `glitter.nexus/dispatch`'s
assert (quoted above) fires whenever `:nexus/expansions` is non-nil
at all (regardless of whether the specific expansion that runs
actually touches state) `temperature.clj` still has to call
`(nxr/register-system->state! deref)` even though `set-temperature`
never uses its `state` argument; omitting it throws
`Assert failed: Either :nexus/system+dispatch-data->state or
:nexus/system->state must be a function` on the very first dispatch.

`timer.clj`'s two expansions need `:nexus/expansions` for a THIRD
reason, closer to `crud.clj`/`todo.clj`'s than `temperature.clj`'s:
`:action/tick`'s `(fn [state] [[:effect/schedule 100 [[:effect/assoc-in
[:last-tick] (:now state)] [:action/tick]]]])` genuinely reads
`(:now state)` (a fresh `System/nanoTime` reading, supplied by
`timer.clj`'s own `:nexus/system->state` (below)) to give `:last-tick`
a real timestamp instead of `nil`. (The `[:action/tick]` re-schedule
itself is an unconditional literal, not decided by `(:now state)`:
`:now`'s only causal effect here is `:last-tick`'s value, not whether
or how often the loop perpetuates.) `:action/reset`'s `(fn [_state]
[[:effect/assoc-in [:started] [:clock/now]]])` ignores `state` (same
shape as `temperature.clj`'s `set-temperature`), but the same
non-nil-`:nexus/expansions` assert applies regardless of which
specific expansion runs, so `timer.clj` also has to register a
`:nexus/system->state` function.

**`timer.clj` is the first demo whose `:nexus/system->state` isn't a
bare `deref`.** `flights.clj` needs none at all; `crud.clj`/`todo.clj`/
`temperature.clj` all register `(nxr/register-system->state! deref)`:
a no-op wrapper satisfying the assert above, since none of their
expansions need anything beyond the state atom's own stored keys.
`timer.clj` registers `(fn [system] (assoc @system :now
(System/nanoTime)))` instead, augmenting the dereffed atom with a
value the atom itself never stores. **This augmentation is consumed
by `:action/tick`'s own action-expansion; a dispatch-time read; NOT
by `view` (a render-time call).** `glitter.gtk/mount!`'s `add-watch`
re-renders `view` directly off the raw new value of the watched state
atom (`src/glitter/gtk.clj`), entirely independent of
`glitter.nexus`'s dispatch machinery, and unaware
`:nexus/system->state` even exists; since the atom itself never
stores a `:now` key
(only `:started`/`:duration`/`:last-tick` are ever written via
`:effect/assoc-in`), an early version of `timer.clj`'s `view` that
tried to read `(:now state)` directly threw a live
`NullPointerException` on every tick (verified live: 63 occurrences
over an 8-second run): swallowed by `glitter.nexus`'s own
`try-f`/`on-error` handling, so the process didn't crash, but
`core/reconcile` never ran, and the display never advanced past its
initial paint. The fix: `view` reads `System/nanoTime` directly
itself, the same live-fresh-read-at-render-time pattern
`flights.clj`'s `get-form-state` already established for today's date
(see that fn's own comment; that call was `(t/today)` when this was
written, and is `local-today` now — see
[the `(t/today)` finding below](#the-ttoday-is-utc-finding)): a demo's `view` computes what it needs
fresh, rather than assuming anything nexus computed for a DIFFERENT
purpose (dispatch-time action-expansion) will also be there at render
time.

All four demos still register `:effect/assoc-in` and `:glitter/value`
too (`crud.clj`/`todo.clj` for the fields that ARE pure passthroughs
(the filter field in `crud.clj`, which dispatches a bare
`:effect/assoc-in` directly via its `field-row` helper, not an action
at all; the draft-text field in `todo.clj`); `temperature.clj` has no
pure-passthrough field at all (both its `:entry` fields route through
`:action/set-temperature`), but still registers `:effect/assoc-in`
because `set-temperature`'s own expansion result is built from
`:effect/assoc-in` tuples, and `:glitter/value` because its
demo-local `:fmt/number` placeholder nests `[:glitter/value]` inside
its own placeholder chain (`[:fmt/number [:glitter/value]]`);
`timer.clj`'s single pure-passthrough interaction is its duration
`:scale`'s `:on {:value-changed [[:effect/assoc-in [:duration]
[:glitter/value]]]}}`) a bare `:effect/assoc-in`/`:glitter/value`
pair, no expansion, dispatched alongside its two action-expansions.
The two consumer shapes aren't mutually exclusive within one demo;
they're a per-interaction choice, made by whether that interaction
needs to read state, or branch on dispatch data, before deciding what
effects to run.

## Action-expansions are not atomic

Every effect inside an action-expansion (the `crud.clj`/`todo.clj`
consumer shape above) is dispatched separately, and each one drives its
own full, synchronous `core/reconcile` before the next effect in the
same expansion runs, not one render for the whole expansion. This
follows from two facts already true elsewhere in this project:
`app.clj`'s `on-gui` runs inline when already on the GTK main thread
(see CONTRIBUTING.md invariant #6), and every effect in this codebase
dispatches from a GTK signal callback, which already runs on that
thread. So there's no batching boundary around an expansion's effects
the way one hand-written `swap!` implicitly gave the pre-retrofit code.

`crud.clj`'s `:action/delete` is the concrete example: it expands into
4 `:effect/assoc-in` calls (`:people`, `:selected-id`, `:given-name`,
`:family-name`), so deleting a person drives 4 renders, where the
pre-retrofit hand-written version computed the whole transition in one
`swap!` and drove exactly 1. Today this doesn't expose any wrong
intermediate state: `crud.clj`'s own intermediate delete-render still
has `:selected-id` pointing at the just-removed person, but `view`'s
`selected?` derivation (which checks whether that id is still present
in `:people`) happens to keep the Update/Delete buttons insensitive
regardless. A future demo built on this pattern should keep the
possibility in mind: an expansion's effects are N sequential renders,
not one atomic transition, and an intermediate render CAN observe
partially-applied state.

## The action-log

`glitter.nexus.action-log` ports nexus's log-accumulation mechanism
(the nested `:entries`/`:chronology` tree tracking every dispatch, every
expanded action, and every executed effect, with per-entry elapsed-time
measurements (`:dispatch-elapsed`, `:expansion-elapsed`,
`:effect-elapsed`, each a `{:ms .. :slow? ..}` map from
`measure-elapsed`)) note that `:expansion-elapsed` is measured from
the most-recently-started NESTED item's start time, not the entry's
own, inherited verbatim from upstream nexus (`inspector.cljc`'s
`after-action`); not "fixed" here because doing so would be an
undocumented divergence from a faithful port (see the code comment
above `after-action` in `action_log.clj`). It captures, per top-level
dispatch: a UUID `:id`, a
`tick.core/now` timestamp (`:dispatched-at`), the raw `:dispatch-data`
(and, if present, the `:glitter/dom-event` under `:dom-event`), and a
nested `:actions` vector where each action's own `:expansions` holds
the further actions/effects it expanded into; recursively, so a
`crud.clj`-style `:action/select-row` expanding into three
`:effect/assoc-in` calls shows up as one top-level action entry with
three nested expansion entries.

It's wired in via `install-logger`, which conj's the log's interceptor
onto a nexus config map:

```clojure
(defn install-logger
  "Adds this log's interceptor to a nexus config map's :nexus/interceptors."
  [nexus log]
  (update nexus :nexus/interceptors (fnil conj []) (get-interceptor log)))
```

No demo in this project currently calls `install-logger`: only
`test/glitter/nexus/action_log_test.clj`'s own unit tests exercise it
directly, against small standalone nexus configs. `(pr-str @log)` is
the current, and only, inspection method; there is no viewer. A GTK4-
native viewer window (walking `@log`'s `:chronology`/`:entries` tree the
way a browser-based dataspex panel would) is a natural, separately-
scoped follow-up once there's a second reason to build one; upstream's
own equivalent (`nexus.inspector`) is entangled with `dataspex.*`
rendering-protocol implementations that have no glitter/GTK analogue,
which is why this port stops at the accumulation mechanism and drops
every rendering call site (see
[`porting-and-attribution.md`](porting-and-attribution.md)'s Bucket 4).

## The `t/parse-date` leniency finding

`flights.clj`'s date fields need to satisfy the 7GUIs spec's "T is
colored red when ill-formatted" requirement, which means detecting
malformed date text reliably. The obvious approach, a bare `t/parse-date`
call, does NOT work: verified live under this Jolt port (`jolt-lang/time`,
pulling in `juxt/tick` transitively; see `deps.edn`), `t/parse-date` is
LENIENT, not strict. Three separate bad inputs against a `"dd.MM.yyyy"`
formatter, none of which threw:

- `"27.03.2014x"` (trailing garbage) silently parsed to `2014-03-27`,
  ignoring the trailing `x`.
- `"not-a-date"` silently parsed to `-0001-11-30`.
- `"31.02.2014"` (February 31st, not a real date) silently rolled over
  to `2014-03-03`.

Using `parse-date` naively for the ill-formatted-date check would have
shipped a feature that never actually triggers. The fix, also verified
live against all three inputs above plus a fourth
(`"7.3.2014"`; wrong digit count for the formatter's 2-digit pattern)
and the valid case, is a round-trip wrapper: parse, then re-format the
result with the SAME formatter, and reject (`nil`) unless the
re-formatted string exactly matches the trimmed input:

```clojure
(defn parse-date [s]
  (when (string? s)
    (let [trimmed (str/trim s)]
      (when (seq trimmed)
        (try
          (let [d (t/parse-date trimmed date-formatter)]
            (when (= trimmed (t/format date-formatter d))
              d))
          (catch Exception _ nil))))))
```

This is the ONLY date-validation strategy `flights.clj` uses, no
separate regex-based pre-check. `get-form-state` calls `parse-date` on
both the departure and return fields; a `nil` result flags that field
`:invalid?`, which drives both the `:class "error"` CSS styling and the
Book button's `:sensitive` state. This finding is specific to date
parsing (a `tick`/`jolt-lang/time` library behavior), not to any GTK
widget, so it's documented here rather than in
[`gtk-widget-layer.md`](gtk-widget-layer.md); this is its one and only
write-up in this project's docs.

It is also structural rather than a version to wait out.
`jolt/time/fmt.clj`'s `parse-with-pattern` is a hand-rolled field scanner
whose own comment calls itself "good enough for tick's parse-* with a
custom formatter"; the library has no `ResolverStyle` or
`withResolverStyle` anywhere, and `DateTimeFormatterBuilder`'s
`parseLenient`/`parseCaseInsensitive` are `(fn [b] b)` no-ops. Re-verified
against jolt `v0.7.23-10-gc50a3717` with `jolt-lang/time` at the SHA
`deps.edn` pins: all four bad inputs above still parse without throwing,
and the round-trip wrapper still rejects every one of them.

## The `(t/today)` is-UTC finding

A second, unrelated date finding from the same file, and the reason
`flights.clj` does not call `(t/today)` at all.

`ZoneId/systemDefault` and `Clock/systemDefaultZone` are hardcoded to UTC
in `jolt-lang/time` — literally `"systemDefault" (fn [] (zone-id "Z" 0))`
in `zones.clj`, and the matching `systemDefaultZone` in `zoned.clj`. So
`(t/today)` answers the **UTC** date on every machine, and ignores `TZ`
even when it is explicitly set. Measured at 07:29 AEST on 2026-08-24:

```
(t/today)                                  => 2026-08-23
TZ=Australia/Sydney … (t/today)            => 2026-08-23   ; TZ ignored
(t/zone)                                   => Z
(t/date (t/in (t/now) "Australia/Sydney")) => 2026-08-24
```

Note what is NOT broken: the libc zone backend underneath answers named
zones correctly (`tz-offset-seconds "Australia/Sydney"` => `36000`). Only
zone *discovery* — "which zone is this machine in" — is missing, and
`systemDefault` returns UTC instead of admitting it doesn't know.

`get-form-state` defaults the departure field to today, so this demo
opened on *yesterday* for the first 10 hours of every AEST day. The fix
is `local-today`, which asks GLib:

```clojure
(defn local-today []
  (let [d (g/g-date-time-new-now-local)
        date (t/new-date (g/g-date-time-get-year d)
                         (g/g-date-time-get-month d)
                         (g/g-date-time-get-day-of-month d))]
    (g/g-date-time-unref d)
    date))
```

It converts straight back to a tick date, so parsing, formatting and
comparison all stay on one representation; only the *source* of "today"
changes. The `GDateTime` is caller-owned and unref'd, the same discipline
`:calendar`'s `set-calendar-date!`/`signal-value` entry already follow
(see [`gtk-widget-layer.md`](gtk-widget-layer.md#aspect-framecalendar--a-quick-win-and-a-genuinely-new-value-type)).

This reverses a decision `flights.clj`'s own ns docstring used to argue
for — that reaching for a GDateTime binding just to answer "what is
today's date" was worse layering than using tick for it. That was
reasoning from an unchecked fact, and the docstring now records the
reversal rather than quietly dropping it.

**Requires jolt `v0.7.23-10-gc50a3717` or newer.** Before
[jolt-lang/jolt#712](https://github.com/jolt-lang/jolt/pull/712), jolt's
own boot-time libc zone probe set `TZ` and never restored it, leaving
every jolt process in whichever zone it probed last — `"UTC"`, as it
happened. That is process-global, so GLib read it too and
`g_date_time_new_now_local` answered UTC as well: measured
`[2026 8 23 21]` where the real local time was `[2026 8 24 7]`, and
correct again the instant `TZ` was unset in-process. On an older jolt
this route silently returns the UTC date, which is why both
`glitter.ffi`'s binding comment and `local-today` say so at the call
site. On a current jolt, `TZ` reads `nil` in a fresh process, survives
being set by the caller, and survives a zone query.
