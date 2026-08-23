# App loop and cross-thread marshalling

`glitter.app` is adapted from the non-reactive slice of `glimmer.core`
(`post-to-gui`, `on-gui`, `run*`, `run`): bootstrapping a `GtkApplication`
and hopping callbacks onto the GTK main thread has nothing to do with which
reconciler sits on top, so this code ports cleanly. The one real change
from the original: glimmer's `:activate` handler hardcodes a call into
glimmer's own reconciler (`mount`); glitter's calls a caller-supplied
`on-activate` callback instead, so `glitter.app` has no dependency on any
particular reconciler at all.

## Bootstrapping

```clojure
(defn run
  [on-activate & {:as opts}]
  (let [start (fn [] (run* on-activate opts))]
    (if-let [hop (resolve 'jolt.host/call-on-main-thread-async)]
      (hop start)
      (start))))
```

`run*` builds a `GtkApplication`, connects an `:activate` signal that
creates a window, sets its title/size, calls `on-activate` with the
window's widget pointer (so the caller can `gtk/mount!` its own root
content), presents the window, and optionally wires an
`:auto-quit-ms` timeout (used by every automated live-GTK smoke to quit the
loop deterministically instead of hanging). `g_application_run` then blocks
running the GTK main loop.

**On macOS, `g_application_run` must run on the process main thread** or
AppKit aborts when it sets the main menu. `run` hops onto Jolt's main-thread
pump asynchronously via `jolt.host/call-on-main-thread-async` when that var
resolves (true for an nREPL session, whose primordial thread parks there);
otherwise it runs inline and blocks until the app quits (a plain `jolt run`
invocation, where the calling thread already *is* the process main thread).

## Cross-thread marshalling: `on-gui`

While the GTK loop runs, `g_application_run` owns the main thread. Any code
that touches a widget from a *different* thread (an nREPL eval's worker
thread, a `future`) has to defer that work onto the loop, because
off-main-thread widget mutation is an AppKit violation on macOS. GTK's
mechanism for this is a one-shot `g_idle_add` source (idle callbacks run on
the main thread):

```clojure
(defn- post-to-gui [work]
  (let [slot (atom nil)]
    (reset! slot (ffi/foreign-callable
                  (fn [_data]
                    (let [cb @slot]
                      (try (work) (finally (w/release-callable! cb))))
                    0)
                  [:pointer] :int :collect-safe))
    (w/retain-callable! @slot)
    (g/g-idle-add @slot ffi/null)
    nil))
```

Returning `0` (`FALSE`) tells GLib to run the source once and remove it;
the retained callable is released right after running so a long nREPL
session doesn't accumulate one retained closure per re-render.

`on-gui` is the public entry point every render actually goes through
(`glitter.gtk/mount!`'s state-atom watcher calls it, not `render!`
directly):

```clojure
(defn on-gui [work]
  (cond
    (not @gui-loop-running?) (work)
    (= (Thread/currentThread) @main-thread) (work)
    :else (post-to-gui work)))
```

Two things matter here, both load-bearing:

1. **Headless (no loop running) runs inline.** Unit tests using
   `glitter.test-renderer` never start a `GtkApplication`, so `on-gui`
   degrades to a plain synchronous call, no `g_idle_add` machinery, no
   dependency on a loop that doesn't exist.
2. **Already-on-the-main-thread runs inline, not marshalled.** `glitter.app`
   tracks which thread `g_application_run` actually runs on (`main-thread`,
   set once in `run*` via `(reset! main-thread (Thread/currentThread))`).
   Without this check, *every* call to `on-gui` (even one already safely on
   the GTK main thread, like a click handler's dispatch triggering a
   `swap!` whose watcher fires synchronously) would post asynchronously via
   `g_idle_add`, deferring the render to the next main-loop iteration. That
   breaks any caller expecting a synchronous read-back immediately after
   triggering a state change (`examples/glitter/keyed.clj` does exactly
   this: mutate state, then immediately read the live GTK tree back). The
   thread-identity check is what makes same-thread renders synchronous
   while still safely marshalling genuinely cross-thread ones.

## The regression this guards against

`examples/glitter/main_thread_smoke.clj` is the automated pin for exactly
this: it mutates `state` from inside a `future` (a genuinely different
thread), then schedules a read-back through `on-gui`, and asserts **which
thread `view` actually ran on**, not merely that the label updated. An
unmarshalled watcher would still update the label (nothing stops a
worker thread from mutating GTK internals under Jolt; it's just an AppKit
violation, not a crash), so a text-only assertion would pass with the bug
present and prove nothing. The example records `(Thread/currentThread)`
inside `view` itself and requires it to equal the GTK main thread, and
separately asserts the worker thread really was a different thread (so the
whole check can't pass vacuously if `future` ever ran inline).

This gap existed once: `glitter.gtk/mount!`'s state-atom watcher originally
called the reconciler synchronously on whatever thread performed the
`swap!`, bypassing `glitter.app`'s marshalling entirely. Every *other*
example mutates state from inside `activate` or a signal callback (i.e.
already on the GTK main thread), so none of them could ever have caught
it. This example is the one the original design spec called for and the
implementation arc initially missed; its absence is why the bug went
unnoticed until a dedicated review pass.
