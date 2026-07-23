(ns fairy.box2.runtime
  "Serialized, commit-aware owner for the Box2 application chart."
  (:require
   [clojure.core.async :as async]
   [clojure.set :as set]
   [com.fulcrologic.statecharts :as sc]
   [com.fulcrologic.statecharts.data-model.working-memory-data-model :as wmdm]
   [com.fulcrologic.statecharts.events :as events]
   [com.fulcrologic.statecharts.protocols :as sp]
   [com.fulcrologic.statecharts.simple :as simple]
   [fairy.box2.model :as model]
   [taoensso.trove :as trove]))

(def ^:private await-timeout-ms 5000)
(def ^:private shutdown-timeout-ms 5000)
(def ^:private effects-limit 128)
(def ^:private errors-limit 64)
(def ^:private history-limit 256)
(def ^:private required-inbox-size 64)
(def ^:private outbox-key ::outbox)

(defrecord EffectQueue []
  sp/EventQueue
  (send! [_ env {:keys [data event send-id type]}]
    (when (= model/effect-send-type type)
      (swap! (get env outbox-key) conj
             {:effect/data data
              :effect/id   send-id
              :effect/type event}))
    true)
  (cancel! [_ _ _ _] true)
  (receive-events! [_ _ _] nil)
  (receive-events! [_ _ _ _] nil))

(defn- append-recent [limit entries entry]
  (let [entries (conj entries entry)]
    (if (> (count entries) limit)
      (subvec entries (- (count entries) limit))
      entries)))

(defn- snapshot* [memory]
  {:configuration (->> (::sc/configuration memory) sort vec)
   :data          (-> (::wmdm/data-model memory)
                      (dissoc :_event :_sessionid))})

(defn snapshot [runtime] @(:snapshot_ runtime))
(defn history [runtime] @(:history_ runtime))
(defn effects [runtime] @(:effects_ runtime))
(defn errors [runtime] @(:errors_ runtime))
(defn fatal [runtime] @(:fatal_ runtime))

(defn- record-error! [runtime error]
  (swap! (:errors_ runtime) #(append-recent errors-limit % error)))

(defn- validate-event! [{:keys [data name] :as event}]
  (when-not (contains? model/events name)
    (throw (ex-info "Unknown Box2 event" {:event event})))
  (when-not (model/valid-payload? :events name data)
    (throw (ex-info "Invalid Box2 event payload" {:event event})))
  event)

(defn- process! [{:keys [env effects_ history_ memory_ snapshot_]} event]
  (let [event         (validate-event! event)
        outbox_       (atom [])
        before        (snapshot* @memory_)
        memory        (sp/process-event! (::sc/processor env)
                                         (assoc env outbox-key outbox_)
                                         @memory_
                                         (events/new-event event))
        next-snapshot (snapshot* memory)
        emitted       @outbox_
        receipt       {:effects  emitted
                       :entered  (set/difference
                                  (set (:configuration next-snapshot))
                                  (set (:configuration before)))
                       :event    event
                       :exited   (set/difference
                                  (set (:configuration before))
                                  (set (:configuration next-snapshot)))
                       :snapshot next-snapshot}]
    (reset! memory_ memory)
    (reset! snapshot_ next-snapshot)
    (swap! effects_
           (fn [entries]
             (reduce #(append-recent effects-limit %1 %2)
                     entries
                     emitted)))
    (swap! history_ #(append-recent history-limit % receipt))
    receipt))

(defn- complete! [completion result]
  (when completion
    (async/offer! completion result)))

(defn- close-ingress! [{:keys [progress-in required-in]}]
  (async/close! required-in)
  (async/close! progress-in))

(defn- fail-fast! [{:keys [accepting?_ fatal_] :as runtime} failure]
  (when (compare-and-set! fatal_ nil failure)
    (reset! accepting?_ false)
    (close-ingress! runtime))
  {:accepted? false
   :failure   @fatal_
   :reason    :runtime-failed})

(defn- log-fatal! [{:keys [fatal-logged?_ fatal_] :as runtime}]
  (when-let [failure @fatal_]
    (when (compare-and-set! fatal-logged?_ false true)
      (record-error! runtime failure)
      (trove/log! {:level :error
                   :id    ::runtime-failed
                   :msg   "Box2 runtime failed"
                   :data  failure}))))

(defn- accepted-routing? [result]
  (or (true? result)
      (true? (:accepted? result))))

(defn- route-effects! [{:keys [dispatch-effect!] :as runtime} receipt]
  (loop [[effect & remaining] (:effects receipt)]
    (when effect
      (let [result (try
                     (dispatch-effect! effect)
                     (catch Throwable error
                       (fail-fast! runtime
                                   {:error  error
                                    :effect effect
                                    :reason :effect-routing-threw})))]
        (if (accepted-routing? result)
          (recur remaining)
          (let [failure (or (:failure result)
                            {:effect effect
                             :reason :effect-routing-rejected
                             :result result})]
            (fail-fast! runtime failure)
            failure))))))

(defn- process-envelope! [runtime {:keys [completion event]}]
  (try
    (let [receipt (process! runtime event)]
      (if-let [failure (route-effects! runtime receipt)]
        (do
          (complete! completion
                     {:error   (ex-info "Failed to route a committed Box2 effect"
                                        failure)
                      :receipt receipt})
          false)
        (do
          (complete! completion receipt)
          true)))
    (catch Exception error
      (record-error! runtime
                     {:error error
                      :event event
                      :reason :event-processing-failed})
      (trove/log! {:level :error
                   :id    ::event-processing-failed
                   :msg   "Box2 event processing failed"
                   :data  {:error error :event event}})
      (complete! completion {:error error})
      true)))

(defn- reject-buffered! [channels failure]
  (doseq [channel channels]
    (loop []
      (when-let [{:keys [completion]} (async/poll! channel)]
        (complete! completion
                   {:error (ex-info "Box2 runtime stopped before event commit"
                                    failure)})
        (recur)))))

(defn- owner! [{:keys [accepting?_ progress-in required-in] :as runtime}]
  (async/thread
    (try
      (loop [channels [required-in progress-in]]
        (when (and (seq channels) (nil? @(:fatal_ runtime)))
          (let [[envelope channel] (async/alts!! channels :priority true)]
            (if envelope
              (when (process-envelope! runtime envelope)
                (recur channels))
              (recur (into [] (remove #{channel}) channels))))))
      (catch Throwable error
        (fail-fast! runtime
                    {:error  error
                     :reason :owner-infrastructure-failed}))
      (finally
        (reset! accepting?_ false)
        (close-ingress! runtime)
        (when-let [failure @(:fatal_ runtime)]
          (reject-buffered! [required-in progress-in] failure))
        (log-fatal! runtime)))
    :stopped))

(defn start!
  "Starts one serialized chart owner with bounded required and progress ingress.

  `dispatch-effect!` must route one committed effect without blocking and return
  `true` or a map containing `:accepted? true`. Effects from each macrostep are
  offered in chart order before the next event is processed."
  [dispatch-effect!]
  (when-not (ifn? dispatch-effect!)
    (throw (ex-info "Box2 runtime requires an effect dispatcher" {})))
  (let [accepting?_ (atom true)
        effects_    (atom [])
        errors_     (atom [])
        fatal_      (atom nil)
        history_    (atom [])
        progress-in (async/chan (async/sliding-buffer 1))
        required-in (async/chan required-inbox-size)
        queue       (->EffectQueue)
        env         (simple/simple-env {::sc/event-queue queue})
        runtime-id  (str (random-uuid))
        chart-key   (keyword "fairy.box2.chart" runtime-id)
        session-id  (keyword "fairy.box2.session" runtime-id)
        _           (simple/register! env chart-key model/application-chart)
        memory      (sp/start! (::sc/processor env)
                               env
                               chart-key
                               {::sc/session-id session-id})
        runtime     {:accepting?_     accepting?_
                     :dispatch-effect! dispatch-effect!
                     :effects_         effects_
                     :errors_          errors_
                     :env              env
                     :fatal_           fatal_
                     :fatal-logged?_   (atom false)
                     :history_         history_
                     :memory_          (atom memory)
                     :progress-in      progress-in
                     :required-in      required-in
                     :snapshot_        (atom (snapshot* memory))}
        owner       (owner! runtime)]
    (assoc runtime :owner owner)))

(defn- stopped-ticket [runtime]
  (if-let [failure @(:fatal_ runtime)]
    {:accepted? false
     :failure   failure
     :reason    :runtime-failed}
    {:accepted? false :reason :stopped}))

(defn- submission-envelope [event completion?]
  (cond-> {:event event}
    completion? (assoc :completion (async/promise-chan))))

(defn submit!
  "Offers required `event` to the chart owner without blocking.

  Required-lane overflow marks the runtime unhealthy. The returned ticket can be
  passed to [[await!]] in development and tests; production callbacks ignore it."
  [runtime event]
  (if-not @(:accepting?_ runtime)
    (stopped-ticket runtime)
    (let [envelope (submission-envelope event true)]
      (if (async/offer! (:required-in runtime) envelope)
        {:accepted?  true
         :completion (:completion envelope)}
        (if @(:accepting?_ runtime)
          (fail-fast! runtime
                      {:event  event
                       :reason :required-ingress-full})
          (stopped-ticket runtime))))))

(defn submit-latest!
  "Offers replaceable player progress without blocking.

  Only `:player.ev/time-changed` is replaceable. A newer buffered progress event
  replaces the older one while preserving the required ingress lane."
  [runtime event]
  (cond
    (not= :player.ev/time-changed (:name event))
    {:accepted? false :reason :not-replaceable}

    (not @(:accepting?_ runtime))
    (stopped-ticket runtime)

    :else
    (if (async/offer! (:progress-in runtime)
                      (submission-envelope event false))
      {:accepted? true}
      (stopped-ticket runtime))))

(defn await!
  "Waits up to `timeout-ms` for an accepted submission's committed receipt.

  This bounded helper is intended for development and tests, not adapters."
  ([runtime ticket]
   (await! runtime ticket await-timeout-ms))
  ([_runtime {:keys [accepted? completion] :as ticket} timeout-ms]
   (when-not (and accepted? completion)
     (throw (ex-info "Box2 event has no awaitable submission"
                     {:ticket ticket})))
   (let [timeout       (async/timeout timeout-ms)
         [result port] (async/alts!! [completion timeout] :priority true)]
     (when (= port timeout)
       (throw (ex-info "Timed out waiting for Box2 event commit"
                       {:timeout-ms timeout-ms})))
     (if-let [error (:error result)]
       (throw error)
       result))))

(defn submit-and-await!
  "Submits required `event` and waits a bounded time for its committed receipt.

  This helper is intended for development and tests. Production adapters use
  [[submit!]]."
  ([runtime event]
   (await! runtime (submit! runtime event)))
  ([runtime event timeout-ms]
   (await! runtime (submit! runtime event) timeout-ms)))

(defn- await-stop! [channel component timeout-ms]
  (let [timeout        (async/timeout timeout-ms)
        [_result port] (async/alts!! [channel timeout] :priority true)]
    (when (= port timeout)
      (throw (ex-info "Timed out stopping Box2 runtime component"
                      {:component component :timeout-ms timeout-ms}))))
  :stopped)

(defn stop! [runtime]
  (when (compare-and-set! (:accepting?_ runtime) true false)
    (close-ingress! runtime))
  (await-stop! (:owner runtime)
               :owner
               shutdown-timeout-ms)
  :stopped)
