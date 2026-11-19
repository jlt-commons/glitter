# nexus (nexus-jolt): data-driven action/effect dispatch

`nexus.core` is a port of [nexus](https://github.com/cjohansen/nexus)
(same author as Replicant), a small, toolkit-agnostic action/effect/
placeholder dispatch engine. It sits downstream of `core/set-dispatch!`
(see [`architecture.md`](architecture.md)): `glitter.core` decides when to
call a dispatch fn and what data to hand it, and `nexus.core` decides what
to do with that data. See [`porting-and-attribution.md`](porting-and-attribution.md)'s
Bucket 4 for the file-by-file porting list.

The engine lives in the standalone
[nexus-jolt](https://github.com/jlt-commons/nexus-jolt) dependency as
`nexus.core` (dispatch, interpolation, effect execution) and
`nexus.registry` (the global registry and its `register-*!` fns, required
as `nxr` in every demo). `glitter.nexus.action-log` lives in
[glitter-core](https://github.com/jlt-commons/glitter-core).

## Why

Hand-written dispatch needs one `case` branch per action kind, reading
`(get-in event [:glitter/dom-event :glitter/value])` and `swap!`-ing the
state atom directly. That is boilerplate that grows with every interactive
field, and it mixes side effects with domain decisions.

With nexus, **actions** are plain data (`[:effect/assoc-in [:draft] [:glitter/value]]`)
dispatched through a registry of **effect** handlers (the only functions
allowed to mutate anything) and **placeholder** resolvers that substitute
event-derived values into action data. Actions that must decide something
from current state register as **expansions**: pure functions of
`(state & args)` that return more actions or effects. A `swap!` happens
only inside a registered effect handler.

## The four concepts

### Effects

An effect handler is a plain function registered under an action-kind
keyword with `nexus.registry/register-effect!`. Every demo registers
`:effect/assoc-in`:

```clojure
(nxr/register-effect! :effect/assoc-in
                      (fn [_ system path v] (swap! system assoc-in path v)))
```

`nexus.core/execute-effect` calls it as `(apply effect-f execute-ctx system (next effect))`.
The leading execute-ctx lets an effect `:dispatch` more actions from inside
itself. `:effect/assoc-in` ignores it.

### Placeholders

Hiccup `:on` data is fixed when `view` runs (see CONTRIBUTING.md invariant
#8), so it can't carry a value that only exists once the user types.
`nexus.core/interpolate-walk` walks an action's data, and any nested vector
whose head is a registered placeholder keyword is replaced by calling that
placeholder's fn with the dispatch data and the placeholder's own trailing
args. That is why `[:effect/assoc-in [:draft] [:glitter/value]]` works as
`:on` data: `[:glitter/value]` resolves first, and the action becomes
`[:effect/assoc-in [:draft] "whatever was typed"]`.

Placeholders nest. `flights.clj` adds `:fmt/nth` for its `:drop-down`,
whose value-fn delivers an int index rather than a DOM string:

```clojure
:nexus/placeholders
{:glitter/value (fn [event] (get-in event [:glitter/dom-event :glitter/value]))
 :fmt/nth (fn [_ coll idx] (nth coll idx))}
```

It is used as `[:effect/assoc-in [:type] [:fmt/nth [:one-way :roundtrip]
[:glitter/value]]]`: `:glitter/value` resolves first, then `:fmt/nth`
indexes into the literal vector.

### Actions and expansions

An action that needs to decide what happens registers with
`nexus.registry/register-action!` (identical to `register-expansion!`, as
upstream ships it). Both write to `[:nexus/expansions action-k]`, and
`nexus.core/dispatch-action` checks expansions before effects, so an
expansion wins over a same-named effect. `crud.clj`'s `:action/select-row`
reads state and returns more actions:

```clojure
(nxr/register-action! :action/select-row
                      (fn [state idx]
                        (if-let [person (nth (vec (get-people state)) idx nil)]
                          [[:effect/assoc-in [:selected-id] (:id person)]
                           [:effect/assoc-in [:given-name] (:given-name person)]
                           [:effect/assoc-in [:family-name] (:family-name person)]]
                          [])))
```

**Arity gotcha.** `[[:action/select-row]]` as `:on` data, with no trailing
placeholder, mismatches the 2-arg fn (`state` and `idx`). The exception is
swallowed into nexus's `:errors` accumulator and not thrown. Write
`[[:action/select-row [:glitter/value]]]` so the placeholder supplies `idx`
before the fn runs. If an expansion silently never fires, check the
`:nexus/on-error` log output first.

### Interceptors

Every dispatch phase (the outer dispatch, each action, each effect) runs
through `run-interceptors`, a stack of `before-*`/`after-*` handler pairs.
Each step is wrapped in `try-f`, so one interceptor's error doesn't kill
the dispatch. No demo registers a custom interceptor. The one concrete
interceptor is `glitter.nexus.action-log`'s `get-interceptor` (see
[The action-log](#the-action-log)).

## The glitter-specific wiring convention

`nexus.core` and `nexus.registry` stay toolkit-agnostic. Two pieces are
glitter-specific and live in each consuming demo, the same way upstream's
`dev/counter/core.cljc` registers its own DOM placeholders:

- **The `:glitter/value` placeholder**:
  `(fn [event] (get-in event [:glitter/dom-event :glitter/value]))`.
  `event` is the map `glitter.core/build-event-map` builds
  (`{:glitter/trigger :glitter.trigger/dom-event :glitter/dom-event e ...}`),
  and `e` is the event object `glitter.gtk/set-event-handler` constructs,
  which puts the widget's current value on it as `:glitter/value` for any
  value-bearing signal (see [`gtk-widget-layer.md`](gtk-widget-layer.md)
  and CONTRIBUTING.md invariant #8).
- **`:nexus/on-error` to `clojure.tools.logging`**: every demo registers
  `(nxr/on-error (fn [_ctx {:keys [err] :as error}] (log/error err
  "glitter.nexus dispatch error" (dissoc error :err))))`. Without it, any
  exception caught in the dispatch pipeline (a bad effect, a bad
  expansion, an unresolved handler) is swallowed into `ctx :errors` and
  never surfaces. That is why `jolt-lang/logging` is in `deps.edn`.

## Two consumer shapes

The choice is per interaction: does it need to read state, or branch on
dispatch data, before deciding what to do?

**Pure effects only** (`examples/glitter/flights.clj`, the Flight Booker).
Every field is a pure `:effect/assoc-in` plus a placeholder, and nothing
registers `:nexus/expansions`:

```clojure
[:entry {:text value :hexpand true
         :class (if error? ["error"] [])
         :on {:change [[:effect/assoc-in [path] [:glitter/value]]]}}]
```

`flights.clj` never calls `nxr/register-system->state!`.
`nexus.core/dispatch` asserts that `:nexus/system->state` (or
`:nexus/system+dispatch-data->state`) is a function only when
`:nexus/expansions` is non-nil, and here it stays nil.

**Action-expansions** (`crud.clj`, `todo.clj`, `temperature.clj`,
`timer.clj`). Each registers `:nexus/expansions`, so each must also
register a system->state fn or the first dispatch throws `Assert failed:
Either :nexus/system+dispatch-data->state or :nexus/system->state must be
a function`, even when the expansion that runs never reads state.

- `crud.clj` and `todo.clj` read current state. `todo.clj`'s
  `:action/toggle` is the simplest case, since a pure `:effect/assoc-in`
  can't read a value before writing it:

```clojure
(nxr/register-action! :action/toggle
                      (fn [state idx]
                        [[:effect/assoc-in [:tasks idx :done] (not (get-in state [:tasks idx :done]))]]))
```

- `temperature.clj`'s `:action/set-temperature` ignores state and branches
  on which key (`:celsius` or `:fahrenheit`) is present in the dispatch
  data.
- `timer.clj`'s `:action/tick` reads `(:now state)` for `:last-tick`.
  Its `:nexus/system->state` is `(fn [system] (assoc @system :now (System/nanoTime)))`
  rather than a bare `deref`, so `:now` exists only at dispatch time.
  `glitter.gtk/mount!`'s `add-watch` re-renders `view` straight from the
  raw atom value, independent of nexus, so `view` must not read `(:now state)`.
  It reads `System/nanoTime` itself, as `flights.clj`'s `get-form-state`
  does for `(t/today)`. Reading `:now` in `view` throws a
  `NullPointerException` on every tick, which `try-f`/`on-error` swallows,
  so the display never advances.

All four demos also register `:effect/assoc-in` and `:glitter/value` for
their plain passthrough fields (`temperature.clj` needs `:glitter/value`
for its demo-local `:fmt/number` placeholder).

## Action-expansions are not atomic

Each effect inside an expansion is dispatched separately and drives its
own synchronous `core/reconcile` before the next one runs. `app.clj`'s
`on-gui` runs inline on the GTK main thread (CONTRIBUTING.md invariant
#6), and every effect dispatches from a GTK signal callback on that
thread, so nothing batches an expansion's effects.

`crud.clj`'s `:action/delete` expands into 4 `:effect/assoc-in` calls
(`:people`, `:selected-id`, `:given-name`, `:family-name`), so it drives 4
renders. No wrong state shows today: the intermediate render still has
`:selected-id` pointing at the removed person, but `view`'s `selected?`
derivation keeps Update/Delete insensitive. An intermediate render can
observe partially applied state, so keep that in mind for new demos. A
coarser effect that batches an expansion into one render would be new
architecture on top of the port.

## The action-log

`glitter.nexus.action-log` (in glitter-core) ports nexus's log-accumulation
mechanism: a nested `:entries`/`:chronology` tree of every dispatch,
expanded action and executed effect, with per-entry elapsed-time maps
(`:dispatch-elapsed`, `:expansion-elapsed`, `:effect-elapsed`, each
`{:ms .. :slow? ..}`). `:expansion-elapsed` is measured from the
most-recently-started nested item's start time, as in upstream
`inspector.cljc`'s `after-action`. It is kept as is to stay a faithful
port. Each top-level dispatch records a UUID `:id`, a `:dispatched-at`
timestamp, the raw `:dispatch-data` (plus the `:glitter/dom-event` under
`:dom-event` when present), and a nested `:actions` vector whose
`:expansions` hold the further actions and effects.

`install-logger` conj's the log's interceptor onto a nexus config map:

```clojure
(defn install-logger
  "Adds this log's interceptor to a nexus config map's :nexus/interceptors."
  [nexus log]
  (update nexus :nexus/interceptors (fnil conj []) (get-interceptor log)))
```

No demo calls it. Only glitter-core's `test/glitter/nexus/action_log_test.clj`
exercises it. `(pr-str @log)` is the only way to inspect the log, and no
viewer ships. Upstream's `nexus.inspector` is entangled with `dataspex.*`
rendering protocols that have no glitter/GTK analogue, so the port stops at
accumulation and drops the rendering call sites (see
[`porting-and-attribution.md`](porting-and-attribution.md)'s Bucket 4).

## The `t/parse-date` leniency finding

`flights.clj` must color a date field red when its text is ill-formatted,
which needs reliable detection of malformed input. A bare `t/parse-date`
doesn't give that: under `jolt-lang/time` (which pulls in `juxt/tick`, see
`deps.edn`) it is lenient. Against a `"dd.MM.yyyy"` formatter, none of
these threw:

- `"27.03.2014x"` (trailing garbage) parsed to `2014-03-27`.
- `"not-a-date"` parsed to `-0001-11-30`.
- `"31.02.2014"` rolled over to `2014-03-03`.

The fix is a round-trip wrapper: parse, re-format with the same formatter,
and return `nil` unless the result equals the trimmed input. It also
rejects `"7.3.2014"` (wrong digit count):

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

This is the only date validation `flights.clj` uses. `get-form-state` calls
it on both date fields, and a `nil` result sets `:invalid?`, which drives
the `:class "error"` styling and the Book button's `:sensitive` state.

The leniency is structural. `jolt/time/fmt.clj`'s `parse-with-pattern` is a
hand-rolled field scanner, the library has no `ResolverStyle`, and
`DateTimeFormatterBuilder`'s `parseLenient`/`parseCaseInsensitive` are
no-ops.

## Time zones and the jolt floor

`flights.clj` defaults its departure field to plain `(t/today)`, which
answers the machine's local date. That needs `jolt-lang/time` v0.0.7 or
newer (older versions answered the UTC date and ignored `TZ`) and jolt
v0.7.24 or newer (earlier jolt leaked `TZ=UTC` from its boot-time libc zone
probe). The pins in `deps.edn` and the declared floor are well above both.
Don't add a GLib date helper to work around a wrong date. Check the
toolchain versions first.

`deps.edn` declares `:jolt/min-version "0.8.24"` (jolt-lang/time v0.0.16 and
later need jolt v0.8.24 and declare no floor themselves; CI pins 0.8.24).
A runtime below the floor refuses to load the project, and the floor is
read from every dependency's `deps.edn` as well as the project's. The key
is no use for guarding a break older than the runtime that reads it, such
as `ffi/write`'s moved argument order: a jolt that predates the reader
skips the key silently.
