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
   [fairy.box2.model :as model]))

(def ^:private default-await-timeout-ms 5000)
(def ^:private default-effect-buffer-size 64)
(def ^:private default-effect-workers 2)
(def ^:private default-inbox-size 256)
(def ^:private default-shutdown-timeout-ms 5000)
(def ^:private outbox-key ::outbox)

(defrecord EffectQueue []
  sp/EventQueue
  (send! [_ env {:keys [data event send-id type]}]
    (when (= model/effect-send-type type)
      (swap! (get env outbox-key) conj {:effect/data data :effect/id send-id :effect/type event}))
    true)
  (cancel! [_ _ _ _] true)
  (receive-events! [_ _ _] nil)
  (receive-events! [_ _ _ _] nil))

(defn- snapshot* [memory]
  {:configuration (->> (::sc/configuration memory) sort vec)
   :data          (-> (::wmdm/data-model memory) (dissoc :_event :_sessionid))})

(defn snapshot [runtime] @(:snapshot_ runtime))
(defn history [runtime] @(:history_ runtime))
(defn effects [runtime] @(:effects_ runtime))
(defn errors [runtime] @(:errors_ runtime))

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
                       :entered  (set/difference (set (:configuration next-snapshot))
                                                 (set (:configuration before)))
                       :event    event
                       :exited   (set/difference (set (:configuration before))
                                                 (set (:configuration next-snapshot)))
                       :snapshot next-snapshot}]
    (reset! memory_ memory)
    (reset! snapshot_ next-snapshot)
    (swap! effects_ into emitted)
    (swap! history_ conj receipt)
    receipt))

(defn- effect-worker! [{:keys [effect-in errors_ execute-effect!]}]
  (let [done (async/promise-chan)]
    (async/thread
      (try
        (loop []
          (when-let [effect (async/<!! effect-in)]
            (try
              (execute-effect! effect)
              (catch Throwable error
                (swap! errors_ conj {:effect effect :error error})))
            (recur)))
        (finally
          (async/offer! done :stopped))))
    done))

(defn- complete! [completion result]
  (async/put! completion result (fn [_] nil)))

(defn- reject-buffered! [inbox error]
  (loop []
    (when-let [{:keys [completion]} (async/poll! inbox)]
      (complete! completion {:error error})
      (recur))))

(defn- owner! [{:keys [accepting?_ effect-in inbox] :as runtime}]
  (let [done (async/promise-chan)]
    (async/go
      (try
        (loop []
          (when-let [{:keys [completion event]} (async/<! inbox)]
            (let [result (try
                           {:receipt (process! runtime event)}
                           (catch Throwable error
                             {:error error}))]
              (if-let [receipt (:receipt result)]
                (do
                  (doseq [effect (:effects receipt)]
                    (async/>! effect-in effect))
                  (async/>! completion receipt))
                (async/>! completion result)))
            (recur)))
        (catch Throwable error
          (reset! accepting?_ false)
          (async/close! inbox)
          (swap! (:errors_ runtime) conj {:error error :source :runtime/owner})
          (reject-buffered! inbox error))
        (finally
          (reset! accepting?_ false)
          (async/close! effect-in)
          (async/>! done :stopped))))
    done))

(defn- validate-options!
  [execute-effect! {:keys [await-timeout-ms
                           effect-buffer-size
                           effect-workers
                           inbox-size
                           shutdown-timeout-ms]}]
  (when-not (ifn? execute-effect!)
    (throw (ex-info "Box2 runtime requires an effect executor" {})))
  (doseq [[option value] [[:await-timeout-ms await-timeout-ms]
                          [:effect-buffer-size effect-buffer-size]
                          [:effect-workers effect-workers]
                          [:inbox-size inbox-size]
                          [:shutdown-timeout-ms shutdown-timeout-ms]]]
    (when-not (pos-int? value)
      (throw (ex-info "Box2 runtime option must be a positive integer"
                      {:option option :value value})))))

(defn start!
  "Starts an autonomous chart owner and asynchronous effect workers.

  Options:

  | key                    | description
  | ---------------------- | -----------
  | `:await-timeout-ms`    | Maximum development receipt wait (default `5000`)
  | `:effect-buffer-size`  | Buffered effect-command capacity (default `64`)
  | `:effect-workers`      | Number of blocking effect workers (default `2`)
  | `:inbox-size`          | Buffered event capacity (default `256`)
  | `:shutdown-timeout-ms` | Maximum wait per owner or worker (default `5000`)"
  ([execute-effect!]
   (start! execute-effect! {}))
  ([execute-effect! {:keys [await-timeout-ms
                            effect-buffer-size
                            effect-workers
                            inbox-size
                            shutdown-timeout-ms]
                     :or   {await-timeout-ms    default-await-timeout-ms
                            effect-buffer-size  default-effect-buffer-size
                            effect-workers      default-effect-workers
                            inbox-size          default-inbox-size
                            shutdown-timeout-ms default-shutdown-timeout-ms}}]
   (validate-options! execute-effect! {:await-timeout-ms    await-timeout-ms
                                       :effect-buffer-size  effect-buffer-size
                                       :effect-workers      effect-workers
                                       :inbox-size          inbox-size
                                       :shutdown-timeout-ms shutdown-timeout-ms})
   (let [accepting?_ (atom true)
         effects_    (atom [])
         errors_     (atom [])
         history_    (atom [])
         inbox       (async/chan inbox-size)
         effect-in   (async/chan effect-buffer-size)
         queue       (->EffectQueue)
         env         (simple/simple-env {::sc/event-queue queue})
         runtime-id  (str (random-uuid))
         chart-key   (keyword "fairy.box2.chart" runtime-id)
         session-id  (keyword "fairy.box2.session" runtime-id)
         _           (simple/register! env chart-key model/application-chart)
         memory      (sp/start! (::sc/processor env) env chart-key {::sc/session-id session-id})
         runtime     {:accepting?_         accepting?_
                      :await-timeout-ms    await-timeout-ms
                      :effect-in           effect-in
                      :effects_            effects_
                      :errors_             errors_
                      :env                 env
                      :execute-effect!     execute-effect!
                      :history_            history_
                      :inbox               inbox
                      :memory_             (atom memory)
                      :shutdown-timeout-ms shutdown-timeout-ms
                      :snapshot_           (atom (snapshot* memory))}
         workers     (mapv (fn [_] (effect-worker! runtime))
                           (range effect-workers))
         runtime     (assoc runtime :effect-workers workers)
         owner       (owner! runtime)]
     (assoc runtime :owner owner))))

(defn submit!
  "Offers `event` to the chart owner without blocking the calling thread.

  Returns a ticket containing `:accepted?`, and, when accepted, a completion
  channel suitable for [[await!]]."
  [runtime event]
  (if-not @(:accepting?_ runtime)
    {:accepted? false :reason :stopped}
    (let [completion (async/promise-chan)
          accepted?  (async/offer! (:inbox runtime)
                                   {:completion completion :event event})]
      (if accepted?
        {:accepted? true :completion completion}
        {:accepted? false
         :reason    (if @(:accepting?_ runtime) :full :stopped)}))))

(defn await!
  "Waits up to `timeout-ms` for an accepted submission's committed receipt.

  This bounded helper is intended for development and tests, not adapters."
  ([runtime ticket]
   (await! runtime ticket (:await-timeout-ms runtime)))
  ([_runtime {:keys [accepted? completion] :as ticket} timeout-ms]
   (when-not accepted?
     (throw (ex-info "Box2 event was not submitted" {:ticket ticket})))
   (let [timeout       (async/timeout timeout-ms)
         [result port] (async/alts!! [completion timeout] :priority true)]
     (when (= port timeout)
       (throw (ex-info "Timed out waiting for Box2 event commit"
                       {:timeout-ms timeout-ms})))
     (if-let [error (:error result)]
       (throw error)
       result))))

(defn submit-and-await!
  "Submits `event` and waits a bounded time for its committed receipt.

  This helper is intended for development and tests. Production adapters should
  use [[submit!]]."
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
    (async/close! (:inbox runtime)))
  (let [timeout-ms (:shutdown-timeout-ms runtime)]
    (await-stop! (:owner runtime) :owner timeout-ms)
    (doseq [[index worker] (map-indexed vector (:effect-workers runtime))]
      (await-stop! worker [:effect-worker index] timeout-ms)))
  :stopped)