(ns glitter.test-runner
  "Entry point for `jolt -M:test`. Requires each glitter test namespace and
  runs clojure.test against it. Prints a summary; exits non-zero if
  anything failed (so the :test task fails CI). Adapted from glimmer's
  test/glimmer/test_runner.clj — identical shape, glitter's own namespace
  list.

  As of the glitter-core/nexus-jolt extraction, this suite covers only the
  example-app tests (glitter.temperature-test, glitter.timer-test) — the
  reconciler/hiccup/assert/alias/nexus.action-log tests moved to
  glitter-core, and the nexus.core/nexus.registry tests moved to the
  standalone nexus-jolt package. See both repos' own test_runner.clj.

  Note: requiring glitter.temperature-test transitively requires
  glitter.temperature, whose top-level calls mutate glitter.core's and
  nexus.registry's global state for the rest of this test process. Test
  glitter.temperature's own logic via its named functions rather than
  relying on shared global state."
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
  ;; see deps.edn's own comment).
  (let [namespaces '[glitter.temperature-test glitter.timer-test]
        broken (atom [])]
    ;; A namespace that fails to REQUIRE used to be printed and then forgotten.
    ;; run-tests only ever sees what loaded, so its counters cannot tell a
    ;; namespace that does not exist from one that would not compile, and the
    ;; suite reported zero failures on a fraction of itself.
    ;;
    ;; Not hypothetical, and found by a fleet build rather than here: on jolt
    ;; v0.7.29, whose ffi/write takes its last two arguments the other way
    ;; round, glitter-uikit's runner exited 0 on 19 of its 37 tests because two
    ;; namespaces failed to load. This runner has the same shape, so it has the
    ;; same hole whether or not this project can currently trip it.
    (doseq [ns namespaces]
      (try (require ns :reload)
           (catch Exception e
             (swap! broken conj ns)
             (println "ERROR requiring" ns ":" (ex-message e)))))
    (let [loaded  (remove (set @broken) namespaces)
          ;; (apply t/run-tests '()) is (t/run-tests), which tests the CURRENT
          ;; namespace and reports a cheerful zero. Guard the empty case.
          results (if (seq loaded)
                    (apply t/run-tests loaded)
                    {:test 0 :pass 0 :fail 0 :error 0})
          failed  (+ (:fail results 0) (:error results 0) (count @broken))]
      (println "----")
      (when (seq @broken)
        (println "FAILED TO LOAD:" (count @broken) "of" (count namespaces)
                 "namespaces:" (pr-str @broken))
        (println "  a namespace that will not load is a failure, not an absence"))
      (println "tests:" (:test results 0)
               "assertions:" (:pass results 0) "passed /"
               failed "failed")
      (when (pos? failed) (exit 1)))))
