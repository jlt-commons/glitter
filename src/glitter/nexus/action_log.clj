(ns glitter.nexus.action-log
  "Dispatched-action accumulation, ported CONCEPT (not a literal file)
  from nexus.action-log + nexus.inspector
  (https://github.com/cjohansen/nexus), commit
  5f6c93672f25d2a5b2a91ac3b65a921ecf8826b2. Copyright 2025 Christian
  Johansen, Magnar Sveen, Teodor Heggelund. MIT License — see NOTICE.

  There is no single upstream file to mirror: the log-accumulation logic
  now lives inside nexus.inspector, entangled with dataspex.*
  rendering-protocol implementations (dp/IRenderInline, dp/IRenderSource,
  etc.) describing how to render a log entry in dataspex's browser-based
  inspector UI — a system with no glitter/GTK equivalent and no way to
  mechanically extract a 'just the code' seam from. This file ports the
  ACCUMULATION mechanism faithfully — the same nested :entries/
  :chronology tree, with per-action :expansions/:dispatches paths — and
  drops every dp/*/dataspex call site.

  Two adaptations, both because glitter's dispatch-data shape is
  fundamentally different from nexus's toolkit-agnostic 'dispatch-data
  could be anything' design:

  - `now` uses tick.core/now (a real Instant) instead of upstream's
    java.util.Date. — jolt.time/tick is already a project dependency
    (see examples/glitter/flights.clj), so this keeps one time library,
    not two. `now-ms` deliberately keeps System/nanoTime, NOT tick —
    elapsed-time measurement needs a monotonic clock immune to
    wall-clock/NTP adjustments, which a wall-clock Instant is not. Same
    split of concerns as upstream, just swapping the wall-clock half.
  - `find-event` (which upstream hunts through dispatch-data's values
    for something satisfying `(instance? js/Event x)`) simplifies to
    reading `:glitter/dom-event` directly — glitter's dispatch-data IS
    always the `event` map `core/set-dispatch!`'s fn receives, never an
    arbitrary bag of context values to search through.

  No viewer ships — entries are inspectable via `(pr-str @log)`. A GTK4-
  native viewer window is a natural, separately-scoped follow-up once
  there's a second reason to build one."
  (:require [tick.core :as t]))

(def conjv (fnil conj []))

(defn now [] (t/now))
(defn now-ms [] (/ (System/nanoTime) 1e6))

(defn measure-elapsed [{:keys [slow-threshold]} now-ms then-ms]
  (let [ms (- now-ms then-ms)]
    {:ms ms
     :slow? (<= slow-threshold ms)}))

(defn find-event [dispatch-data]
  (:glitter/dom-event dispatch-data))

(defn ^{:indent 2} update-log-entry [log ctx f & args]
  (apply swap! log update-in [:entries (::id ctx)] f args))

(defn get-dispatch-stub [log id]
  (let [dispatch (get-in @log [:entries id])]
    (assoc (select-keys dispatch [:id :dispatched-at])
           :actions (mapv :action (:actions dispatch)))))

(defn before-dispatch [log {:keys [dispatch-data]
                            :as ctx}]
  (let [id (random-uuid)
        event (find-event dispatch-data)
        ms (now-ms)]
    (swap! log
           #(cond-> (assoc-in % [:entries id]
                              (cond-> {:id id
                                       :dispatched-at (now)
                                       :actions []
                                       :dispatch-data dispatch-data}
                                event (assoc :dom-event event)
                                (::id ctx) (assoc :dispatched-by (get-dispatch-stub log (::id ctx)))))
              (::id ctx) (update-in [:entries (::id ctx) :dispatches] conjv id)
              :then (update :chronology conj id)))
    (assoc (dissoc ctx ::interpolate-path)
           ::id id
           ::dispatch-start ms
           ::path [:actions]
           ::dispatched-actions (:actions ctx)
           ::parent-path (when (::id ctx)
                           (let [path (into [:entries (::id ctx)] (pop (pop (::path ctx))))]
                             (into path [(dec (count (get-in @log path))) :dispatches]))))))

(defn after-dispatch [log ctx]
  (update-log-entry log ctx
                    (fn [entry]
                      (cond-> (assoc entry :dispatch-elapsed
                                     (measure-elapsed @log (now-ms) (::dispatch-start ctx)))
                        (and (empty? (:actions entry))
                             (::dispatched-actions ctx)
                             (:errors ctx))
                        (assoc :actions (for [action (::dispatched-actions ctx)]
                                          {:action action}))

                        (:errors ctx)
                        (assoc :errors (:errors ctx)))))
  (when-let [path (::parent-path ctx)]
    (swap! log update-in path conjv (get-dispatch-stub log (::id ctx))))
  ctx)

(defn batched? [ctx]
  (let [action-k (first (:action ctx))]
    (-> ctx :nexus :nexus/effects action-k meta :nexus/batch)))

(defn before-action [log ctx]
  (if (batched? ctx)
    ctx
    (let [idx (count (get-in @log (into [:entries (::id ctx)] (::path ctx))))
          now (now-ms)]
      (update-log-entry log ctx
                        (fn [entry]
                          (cond-> (update-in entry (::path ctx) conjv
                                             {:action (:action ctx)
                                              :state (:state ctx)})
                            (::interpolate-path ctx)
                            (assoc-in (conj (::interpolate-path ctx) :interpolation-elapsed)
                                      (measure-elapsed @log now (::before-interpolate ctx))))))
      (-> (update ctx ::path into [idx :expansions])
          (assoc ::before-action now
                 ::before-interpolate now
                 ::interpolate-path (conj (::path ctx) idx))))))

(defn after-action [log ctx]
  (if (batched? ctx)
    ctx
    (let [path (pop (::path ctx))
          details (-> ctx :action meta)]
      (update-log-entry log ctx
                        (fn [entry]
                          (cond-> entry
                            ;; ::before-action is a single key on ctx, so a
                            ;; nested action's before-action call overwrites
                            ;; the outer one's timestamp — :expansion-elapsed
                            ;; below ends up measured from the most-recently-
                            ;; started NESTED item, not this entry's own
                            ;; start time. Inherited verbatim from upstream
                            ;; nexus's inspector.cljc; not "fixed" here since
                            ;; that would be an undocumented divergence from
                            ;; a faithful port — see docs/guide/nexus.md.
                            (seq (get-in entry (conj path :expansions)))
                            (assoc-in (conj path :expansion-elapsed)
                                      (measure-elapsed @log (now-ms) (::before-action ctx)))

                            (:nexus/action details)
                            (update-in path merge {:interpolated (:action ctx)
                                                   :interpolations (->> (:nexus/interpolations details)
                                                                        (map (juxt :placeholder :resolution))
                                                                        (into {}))})

                            (nil? (:nexus/action details))
                            (update-in path dissoc :interpolation-elapsed))))
      (update ctx ::path #(pop (pop %))))))

(defn before-effect [log ctx]
  (when-not (batched? ctx)
    (update-log-entry log ctx
                      (fn [entry]
                        (cond-> entry
                          (::interpolate-path ctx)
                          (assoc-in (conj (::interpolate-path ctx) :interpolation-elapsed)
                                    (measure-elapsed @log (now-ms) (::before-interpolate ctx)))))))
  (let [idx (when (:effects ctx)
              (count (get-in @log (into [:entries (::id ctx)] (::path ctx)))))]
    (cond-> (assoc ctx ::before-effectuate (now-ms))
      idx (update ::path into [idx :expansions]))))

(defn after-effect [log ctx]
  (let [now (now-ms)]
    (update-log-entry log ctx
                      (fn [entry]
                        (let [path* (pop (pop (::path ctx)))
                              path (conj path* (cond-> (count (get-in entry path*))
                                                 (nil? (:effects ctx)) dec))
                              entry (cond-> (update-in entry path merge
                                                       {:result (:res ctx)
                                                        :effect-elapsed (measure-elapsed @log now (::before-effectuate ctx))})
                                      (nil? (:nexus/action (meta (:effect ctx))))
                                      (update-in path dissoc :interpolation-elapsed)

                                      (:effects ctx)
                                      (update-in path merge (select-keys ctx [:effects :state])))]
                          (update entry :effects conjv path)))))
  (cond-> ctx
    (nil? (:nexus/action (meta (:effect ctx)))) (dissoc ::interpolate-path)))

(defn get-interceptor [log]
  {:id ::inspector
   :before-dispatch #(before-dispatch log %)
   :after-dispatch #(after-dispatch log %)
   :before-action #(before-action log %)
   :after-action #(after-action log %)
   :before-effect #(before-effect log %)
   :after-effect #(after-effect log %)})

(defn create-log
  "Creates a fresh action-log atom. opts: {:keys [slow-threshold]}
  (default slow-threshold: 100ms)."
  [& [opts]]
  (atom
   (-> (into {} opts)
       (update :slow-threshold #(or % 100)))))

(defn install-logger
  "Adds this log's interceptor to a nexus config map's :nexus/interceptors."
  [nexus log]
  (update nexus :nexus/interceptors (fnil conj []) (get-interceptor log)))
