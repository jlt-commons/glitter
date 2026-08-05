(ns glitter.app
  "GTK4 application bootstrap and cross-thread marshalling for glitter.

  Adapted from the non-reactive slice of glimmer.core (post-to-gui, on-gui,
  run*, run) — mounting a reactive component tree is glimmer-specific;
  starting a GtkApplication and hopping callbacks onto the GTK main thread is
  not. The one change from the original: glimmer's :activate handler
  hardcodes a call into glimmer's own reconciler (mount); here it calls a
  caller-supplied `on-activate` callback instead, so this namespace has no
  dependency on any particular reconciler.

  The app loop is a GtkApplication whose :activate handler creates a window
  and calls `on-activate` with the window's widget pointer; g_application_run
  blocks running the GTK main loop, and every signal/activate callback is a
  :collect-safe foreign-callable."
  (:require [glitter.ffi :as g]
            [glitter.widget :as w]
            [jolt.ffi :as ffi]))

;; While a GTK app runs, g_application_run owns the main thread. Work posted
;; from another thread (an nREPL eval on its worker thread, a future) must be
;; deferred onto the loop via a one-shot g_idle_add source (idle callbacks run
;; on the main thread) — AppKit rejects off-main-thread widget mutation on
;; macOS. Headless, with no loop running, work runs synchronously.
(defonce ^:private gui-loop-running? (atom false))

(defn- post-to-gui
  "Schedule zero-arg `work` on the GTK main loop via a one-shot g_idle_add
  source. The source returns FALSE (0) so it fires once and is removed; the
  retained callable is released after running so a long REPL session doesn't
  accumulate them."
  [work]
  (let [slot (atom nil)]
    (reset! slot (ffi/foreign-callable (fn [_data]
                                         (let [cb @slot]
                                           (try (work)
                                                (finally (w/release-callable! cb))))
                                         0)
                                       [:pointer] :int :collect-safe))
    (w/retain-callable! @slot)
    (g/g-idle-add @slot ffi/null)
    nil))

(defn on-gui
  "Run zero-arg `work` on the GTK main thread — asynchronously, on the next
  main loop iteration — while a GUI app is running. Runs `work` inline when no
  GUI loop is running (so it is usable headless and in tests)."
  [work]
  (if (not @gui-loop-running?) (work) (post-to-gui work)))

(defn ^:private run*
  [on-activate opts]
  (let [{:keys [app-id title width height auto-quit-ms]
         :or {app-id "glitter.app" title "glitter" width 400 height 300}} opts
        app (g/gtk-application-new app-id g/APPLICATION-DEFAULT-FLAGS)
        activate (fn [_app _data]
                   (let [win (g/gtk-application-window-new app)]
                     (g/gtk-window-set-title win title)
                     (g/gtk-window-set-default-size win width height)
                     (on-activate win)
                     (g/gtk-window-present win)
                     (when auto-quit-ms
                       (let [quit (ffi/foreign-callable
                                   (fn [_data] (g/g-application-quit app) 0)
                                   [:pointer] :int :collect-safe)]
                         (w/retain-callable! quit)
                         (g/g-timeout-add auto-quit-ms quit ffi/null)))))
        activate-cb (ffi/foreign-callable activate [:pointer :pointer] :void :collect-safe)]
    (w/retain-callable! activate-cb)
    (g/g-signal-connect-data app "activate" activate-cb ffi/null ffi/null g/CONNECT-DEFAULT)
    (try
      (reset! gui-loop-running? true)
      (g/g-application-run app 0 ffi/null)
      (finally (reset! gui-loop-running? false)))))

(defn run
  "Run a GTK4 application. On :activate, a window is created and `on-activate`
  (a fn of one arg — the window's widget pointer) is called so the caller can
  mount its own root content into it. Blocks until the app quits.

  Options:
    :app-id        GApplication id      (default \"glitter.app\")
    :title         window title         (default \"glitter\")
    :width         window width in px   (default 400)
    :height        window height in px  (default 300)
    :auto-quit-ms  if set, quit the loop after this many milliseconds
                   (smoke/automated tests).

  On macOS, g_application_run must run on the process main thread or AppKit
  aborts when it sets the main menu. Hops onto jolt's main-thread pump
  asynchronously via jolt.host/call-on-main-thread-async when resolvable
  (an nREPL session's primordial thread parks there); runs inline and blocks
  until the app quits otherwise (plain `jolt run`)."
  [on-activate & {:as opts}]
  (let [start (fn [] (run* on-activate opts))]
    (if-let [hop (resolve 'jolt.host/call-on-main-thread-async)]
      (hop start)
      (start))))
