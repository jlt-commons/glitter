(ns glitter.env
  "Dev/prod flag plumbing for glitter. Not a port of replicant.env — that file
  exists to detect ClojureScript compiler presence/optimization level, a
  JVM-vs-CLJS concern that doesn't apply to a Jolt-only, GTK-only target. This
  is new, minimal code providing the same 3-function surface
  (dev?/enabled?/with-dev-key) that glitter.errors/glitter.assert consume.")

(defonce ^:private config (atom {}))

(defn configure!
  "Set a config key (e.g. :glitter/dev?, :glitter/asserts?,
  :glitter/catch-exceptions?) used by enabled?/dev?. Until called, defaults
  are dev-permissive in the sense of 'loud, not silent': :glitter/asserts?
  defaults on, but :glitter/catch-exceptions? defaults OFF (mirrors
  glitter.errors/catch-exceptions?'s (not (dev?)) computation — in dev,
  exceptions propagate instead of being swallowed; call
  (configure! :glitter/catch-exceptions? true) to opt into catching, e.g. for
  a release build or a test exercising the catching path itself)."
  [k v]
  (swap! config assoc k v))

(defn dev?
  "True unless explicitly configured off via (configure! :glitter/dev? false).
  Jolt has no separate release/dev CLJS build step to detect, so this defaults
  permissive."
  []
  (get @config :glitter/dev? true))

(defn enabled?
  "Value of config key `k`, or `default` when unset."
  [k & [default]]
  (get @config k (if (some? default) default true)))

(defmacro with-dev-key
  "No-op passthrough — mirrors replicant.env.cljc's squint/cherry stub. Real
  dev-key injection (adding a debug attribute in dev builds) isn't needed for
  glitter: there's no browser devtools inspecting the widget tree."
  [hiccup _k]
  hiccup)
