(ns glitter.test-runner
  "Entry point for `jolt -M:test`. Requires each glitter test namespace and
  runs clojure.test against it. Prints a summary; exits non-zero if anything
  failed (so the :test task fails CI). Adapted from glimmer's
  test/glimmer/test_runner.clj — identical shape, glitter's own namespace list."
  (:require [clojure.test :as t]))

(defmethod t/report :error [m]
  (t/with-test-out
    (t/inc-report-counter :error)
    (println "\nERROR in" (t/testing-vars-str m))
    (when (seq t/*testing-contexts*) (println (t/testing-contexts-str)))
    (when-let [message (:message m)] (println message))
    (when-let [e (:actual m)]
      (if (instance? Throwable e)
        (do (println "  ->" (.getName (class e)) ":" (ex-message e))
            (when-let [d (ex-data e)] (prn d))
            (when-let [c (ex-cause e)]
              (println "  caused by:" (.getName (class c)) ":" (ex-message c))))
        (prn e)))))

(defn- exit
  "Terminate the process with `code`.

  Call System/exit DIRECTLY. `System/exit` is a static-method interop FORM,
  not a var, so `(resolve 'System/exit)` is ALWAYS nil — under Jolt and on the
  JVM alike. A cond guarded on that resolve therefore never fires and silently
  falls through to nil, which is what the previous version of this fn did: the
  suite printed its failures and still exited 0, so `jolt test` could not fail
  CI at all. `jolt.host` ships no `exit` either (checked against ns-publics),
  so that branch was dead for the same reason.

  Verified during the final whole-branch review: `(System/exit 7)` yields
  shell status 7, both before `glitter.app/run` and after it returns, so the
  GTK main-loop hop does not interfere."
  [code]
  (System/exit code))

(defn -main [& _]
  ;; deps.edn's :test alias puts examples/ on this process's classpath
  ;; (needed so glitter.temperature-test can require glitter.temperature —
  ;; see deps.edn's own comment). Requiring glitter.temperature-test
  ;; therefore transitively requires glitter.temperature itself, whose
  ;; top-level (core/set-dispatch! ...) and nxr/register-*! calls mutate
  ;; genuinely global, process-wide state (glitter.core/*dispatch*,
  ;; glitter.nexus.registry/!registry) for the rest of THIS test process —
  ;; not scoped to glitter.temperature-test's own deftests. Any future
  ;; example-backed test namespace added here does the same. This is safe
  ;; today only because glitter.nexus.registry-test's own `use-fixtures
  ;; :each` resets !registry to {} before/after each of ITS deftests — a
  ;; future test that relies on global dispatch/registry state some other
  ;; way could break in an order-dependent way (namespaces run in the
  ;; order listed below). Verified live: after glitter.nexus.registry-test's
  ;; deftests run, !registry no longer contains glitter.temperature's
  ;; require-time registrations at all (its :each fixture's before-only
  ;; reset leaves !registry holding whatever its OWN last deftest put
  ;; there) — so a hypothetical test reaching into the shared registry
  ;; from glitter.temperature-test (listed after registry-test below)
  ;; would see none of glitter.temperature's registrations. Test
  ;; glitter.temperature's placeholder/expansion LOGIC via its own named
  ;; functions instead of the shared registry, for exactly this reason.
  (let [namespaces '[glitter.hiccup-test glitter.assert-test glitter.asserts-test
                     glitter.core-test glitter.alias-test glitter.nexus-test
                     glitter.nexus.registry-test glitter.nexus.action-log-test
                     glitter.temperature-test]]
    (doseq [ns namespaces]
      (try (require ns :reload)
           (catch Exception e
             (println "ERROR requiring" ns ":" (ex-message e)))))
    (let [results (apply t/run-tests namespaces)
          failed (+ (:fail results 0) (:error results 0))]
      (println "----")
      (println "tests:" (:test results 0)
               "assertions:" (:pass results 0) "passed /"
               failed "failed")
      (when (pos? failed) (exit 1)))))
