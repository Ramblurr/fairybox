(ns fairy.box2.dev
  "REPL control surface for the Box2 card-request walking skeleton.

  This namespace owns one development chart session. Convenience functions
  construct validated events and submit them through [[dispatch!]]. Synthetic
  card links and captured effects remain outside chart data.

  The manual completion functions model adapter callbacks without performing
  hardware or filesystem work."
  (:require
   [clojure.set :as set]
   [com.fulcrologic.statecharts :as sc]
   [com.fulcrologic.statecharts.data-model.working-memory-data-model :as wmdm]
   [com.fulcrologic.statecharts.events :as events]
   [com.fulcrologic.statecharts.protocols :as sp]
   [com.fulcrologic.statecharts.simple :as simple]
   [fairy.box2.model :as model]))

(defonce ^:private runtime_ (atom nil))

(defrecord CapturingEventQueue [outbox_]
  sp/EventQueue
  (send! [_ _ {:keys [data event send-id type]}]
    (when (= model/effect-send-type type)
      (swap! outbox_ conj
             {:effect/data data
              :effect/id   send-id
              :effect/type event}))
    true)
  (cancel! [_ _ _source-session-id _send-id]
    true)
  (receive-events! [_ _ _]
    nil)
  (receive-events! [_ _ _ _]
    nil))

(defn- runtime []
  (or @runtime_
      (throw (ex-info "Box2 development runtime is not started"
                      {:operation :start-required}))))

(defn- working-memory-snapshot [working-memory]
  {:configuration (->> (::sc/configuration working-memory)
                       sort
                       vec)
   :data          (-> (::wmdm/data-model working-memory)
                      (dissoc :_event :_sessionid))})

(defn snapshot
  "Returns the current immutable Box2 chart snapshot."
  []
  (working-memory-snapshot @(:working-memory_ (runtime))))

(defn history
  "Returns the committed event receipts for the current development session."
  []
  @(:history_ (runtime)))

(defn effects
  "Returns every effect emitted by the current development session."
  []
  @(:effects_ (runtime)))

(defn latest-effect
  "Returns the newest captured effect whose type is `effect-type`."
  [effect-type]
  (last (filter #(= effect-type (:effect/type %)) (effects))))

(defn in?
  "Returns whether `state-id` is active in the current chart configuration."
  [state-id]
  (contains? (set (:configuration (snapshot))) state-id))

(defn- active-value [configuration state-namespace]
  (some->> configuration
           (filter #(= state-namespace (namespace %)))
           first
           name
           keyword))

(defn status
  "Returns a compact view of the current Box2 session and latest effects."
  []
  (let [{:keys [configuration data]} (snapshot)
        latest-receipt               (peek (history))
        request (or (get-in data [:audio :active-request])
                    (get-in data [:audio :pending-request]))]
    {:system       (active-value configuration "system.st")
     :lifecycle    (active-value configuration "lifecycle.st")
     :rfid         (active-value configuration "rfid.st")
     :card-request (active-value configuration "card-request.st")
     :player       (active-value configuration "player.st")
     :request      request
     :effects      (mapv :effect/type (:effects latest-receipt))}))

(defn- validate-event! [{:keys [data name] :as event}]
  (when-not (contains? model/events name)
    (throw (ex-info "Unknown Box2 event"
                    {:event event})))
  (when-not (model/valid-payload? :events name data)
    (throw (ex-info "Invalid Box2 event payload"
                    {:event   event
                     :payload data})))
  event)

(defn- validate-effect! [{:keys [effect/data effect/type] :as effect}]
  (when-not (model/valid-payload? :effects type data)
    (throw (ex-info "Invalid Box2 effect payload"
                    {:effect effect})))
  effect)

(defn dispatch!
  "Processes one validated `event` through the serialized development runtime.

  Returns a receipt containing the event, entered and exited states, captured
  effects, and resulting snapshot."
  [event]
  (let [{:keys [effects_ env history_ lock outbox_ working-memory_]} (runtime)]
    (locking lock
      (let [event              (validate-event! event)
            before             (working-memory-snapshot @working-memory_)
            _                  (reset! outbox_ [])
            next-memory        (sp/process-event! (::sc/processor env)
                                                  env
                                                  @working-memory_
                                                  (events/new-event event))
            next-snapshot      (working-memory-snapshot next-memory)
            emitted-effects    (mapv validate-effect! @outbox_)
            before-config      (set (:configuration before))
            next-configuration (set (:configuration next-snapshot))
            receipt            {:effects  emitted-effects
                                :entered  (set/difference next-configuration
                                                          before-config)
                                :event    event
                                :exited   (set/difference before-config
                                                          next-configuration)
                                :snapshot next-snapshot}]
        (reset! working-memory_ next-memory)
        (swap! effects_ into emitted-effects)
        (swap! history_ conj receipt)
        receipt))))

(defn stop!
  "Stops and forgets the current development session.

  Returns `:stopped` when a session existed and `:already-stopped` otherwise."
  []
  (if @runtime_
    (do
      (reset! runtime_ nil)
      :stopped)
    :already-stopped))

(defn- valid-card-links? [card-links]
  (and (map? card-links)
       (every? (fn [[uid item-path]]
                 (and (string? uid)
                      (not-empty uid)
                      (string? item-path)
                      (not-empty item-path)))
               card-links)))

(defn start!
  "Starts a fresh singleton Box2 development session.

  Any existing development session is stopped first.

  Options:

  | key                  | description |
  |----------------------|-------------|
  | `:card-links`        | Synthetic UID-to-media-path projection (default `{}`) |
  | `:settings`          | Initial non-secret operational settings (default `{}`) |
  | `:settings-revision` | Initial settings revision (default `0`) |"
  ([]
   (start! {}))
  ([{:keys [card-links settings settings-revision]
     :or   {card-links        {}
            settings          {}
            settings-revision 0}}]
   (when-not (valid-card-links? card-links)
     (throw (ex-info "Card links must map non-empty UID strings to non-empty media paths"
                     {:card-links card-links})))
   (stop!)
   (let [effects_        (atom [])
         history_        (atom [])
         lock            (Object.)
         outbox_         (atom [])
         queue           (->CapturingEventQueue outbox_)
         env             (simple/simple-env {::sc/event-queue queue})
         runtime-id      (str (random-uuid))
         chart-key       (keyword "fairy.box2.dev.chart" runtime-id)
         session-id      (keyword "fairy.box2.dev.session" runtime-id)
         _               (simple/register! env chart-key model/application-chart)
         working-memory  (sp/start! (::sc/processor env)
                                    env
                                    chart-key
                                    {::sc/session-id session-id})
         development-run {:card-links      card-links
                          :effects_        effects_
                          :env             env
                          :history_        history_
                          :lock            lock
                          :outbox_         outbox_
                          :working-memory_ (atom working-memory)}]
     (reset! runtime_ development-run)
     (dispatch! {:name :system.ev/initialized
                 :data {:settings          settings
                        :settings-revision settings-revision}})
     (status))))

(defn place-card!
  "Resolves `uid` against the synthetic card links and places the card."
  [uid]
  (when-not (and (string? uid) (not-empty uid))
    (throw (ex-info "RFID UID must be a non-empty string"
                    {:uid uid})))
  (let [item-path (get (:card-links (runtime)) uid)]
    (dispatch! {:name :rfid.ev/card-placed
                :data (cond-> {:request-id (random-uuid)
                               :uid        uid}
                        item-path (assoc :item-path item-path))})))

(defn remove-card!
  "Removes the currently presented synthetic RFID card."
  []
  (let [uid (get-in (snapshot) [:data :rfid :present-uid])]
    (when-not uid
      (throw (ex-info "No RFID card is currently present"
                      {:operation :remove-card})))
    (dispatch! {:name :rfid.ev/card-removed
                :data {:uid uid}})))

(defn- require-effect [effect effect-type]
  (when-not (= effect-type (:effect/type effect))
    (throw (ex-info "Unexpected Box2 effect type"
                    {:effect        effect
                     :expected-type effect-type})))
  effect)

(defn- require-latest-effect [effect-type]
  (or (latest-effect effect-type)
      (throw (ex-info "No matching Box2 effect has been emitted"
                      {:effect-type effect-type}))))

(defn prepared!
  "Completes a captured media-preparation `effect` with playable `paths`.

  With one argument, completes the latest `:media.fx/prepare` effect. Supplying
  an older effect allows stale-completion scenarios."
  ([paths]
   (prepared! (require-latest-effect :media.fx/prepare) paths))
  ([effect paths]
   (let [{:keys [generation request-id settings-revision]}
         (:effect/data (require-effect effect :media.fx/prepare))]
     (dispatch! {:name :media.ev/prepared
                 :data {:generation        generation
                        :paths             paths
                        :request-id        request-id
                        :settings-revision settings-revision}}))))

(defn queue-installed!
  "Acknowledges installation for a captured queue-installation `effect`.

  With no arguments, acknowledges the latest `:player.fx/install-queue`
  effect."
  ([]
   (queue-installed! (require-latest-effect :player.fx/install-queue)))
  ([effect]
   (let [playback-context
         (get-in (require-effect effect :player.fx/install-queue)
                 [:effect/data :playback-context])]
     (dispatch! {:name :player.ev/queue-installed
                 :data {:playback-context playback-context}}))))

(defn player-state!
  "Reports `player-state` for the chart's current playback context."
  [player-state]
  (let [playback-context (get-in (snapshot)
                                 [:data :audio :playback-context])]
    (when-not playback-context
      (throw (ex-info "No playback context is installed"
                      {:player-state player-state})))
    (dispatch! {:name :player.ev/state-changed
                :data {:playback-context playback-context
                       :state            player-state}})))

(comment
  (start!
   {:card-links {"CARD-A" "/media/story-a"
                 "CARD-B" "/media/story-b"}})

  (status)

  (place-card! "CARD-A")
  (def prepare-a (latest-effect :media.fx/prepare))

  (prepared! prepare-a
             ["/media/story-a/01.mp3"
              "/media/story-a/02.mp3"])
  (queue-installed!)
  (player-state! :opening)
  (in? :card-request.st/active)

  (snapshot)
  (effects)
  (history)

  (remove-card!)
  (place-card! "UNKNOWN")

  (place-card! "CARD-A")
  (def stale-a (latest-effect :media.fx/prepare))
  (place-card! "CARD-B")
  (prepared! stale-a ["/media/stale-a.mp3"])
  (status)

  (dispatch! {:name :player.ev/stop-requested})
  (stop!)

  :rcf)
