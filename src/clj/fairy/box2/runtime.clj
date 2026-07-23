(ns fairy.box2.runtime
  "Serialized, commit-aware owner for the Box2 application chart."
  (:require
   [clojure.set :as set]
   [com.fulcrologic.statecharts :as sc]
   [com.fulcrologic.statecharts.data-model.working-memory-data-model :as wmdm]
   [com.fulcrologic.statecharts.events :as events]
   [com.fulcrologic.statecharts.protocols :as sp]
   [com.fulcrologic.statecharts.simple :as simple]
   [fairy.box2.model :as model])
  (:import
   [java.util.concurrent LinkedBlockingQueue TimeUnit]))

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

(defn- validate-event! [{:keys [data name] :as event}]
  (when-not (contains? model/events name)
    (throw (ex-info "Unknown Box2 event" {:event event})))
  (when-not (model/valid-payload? :events name data)
    (throw (ex-info "Invalid Box2 event payload" {:event event})))
  event)

(defn- process! [{:keys [env effects_ history_ memory_ snapshot_] :as runtime} event]
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
    ;; This ordering is the runtime's commit boundary: no effect runs before it.
    (reset! memory_ memory)
    (reset! snapshot_ next-snapshot)
    (swap! effects_ into emitted)
    (swap! history_ conj receipt)
    (doseq [effect emitted]
      (.submit ^java.util.concurrent.ExecutorService (:effect-pool runtime)
               ^Runnable #((:execute-effect! runtime) effect)))
    receipt))

(defn- owner! [runtime]
  (future
    (while @(:accepting?_ runtime)
      (when-let [{:keys [event result]} (.poll ^LinkedBlockingQueue (:inbox runtime) 100 TimeUnit/MILLISECONDS)]
        (try
          (deliver result (process! runtime event))
          (catch Throwable error
            (deliver result {:error error})))))))

(defn start!
  "Starts a chart owner. `execute-effect!` is invoked only after each commit."
  [execute-effect!]
  (let [effects_    (atom [])
        history_    (atom [])
        inbox       (LinkedBlockingQueue.)
        effect-pool (java.util.concurrent.Executors/newFixedThreadPool 2)
        queue       (->EffectQueue)
        env         (simple/simple-env {::sc/event-queue queue})
        runtime-id  (str (random-uuid))
        chart-key   (keyword "fairy.box2.chart" runtime-id)
        session-id  (keyword "fairy.box2.session" runtime-id)
        _           (simple/register! env chart-key model/application-chart)
        memory      (sp/start! (::sc/processor env) env chart-key {::sc/session-id session-id})
        runtime     {:accepting?_     (atom true)
                     :effect-pool     effect-pool
                     :effects_        effects_
                     :env             env
                     :execute-effect! execute-effect!
                     :history_        history_
                     :inbox           inbox
                     :memory_         (atom memory)
                     :snapshot_       (atom (snapshot* memory))}]
    (assoc runtime :owner (owner! runtime))))

(defn dispatch!
  "Serializes `event`, returning its committed receipt."
  [runtime event]
  (when-not @(:accepting?_ runtime)
    (throw (ex-info "Box2 runtime is stopped" {})))
  (let [result (promise)]
    (.put ^LinkedBlockingQueue (:inbox runtime) {:event event :result result})
    (let [receipt @result]
      (if-let [error (:error receipt)] (throw error) receipt))))

(defn stop! [runtime]
  (reset! (:accepting?_ runtime) false)
  @(:owner runtime)
  (.shutdownNow ^java.util.concurrent.ExecutorService (:effect-pool runtime))
  :stopped)