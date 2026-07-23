(ns fairy.box2.dev
  "REPL control surface for the real local Box2 playable-card stack."
  (:require
   [donut.system :as ds]
   [donut.system.repl :as dsr]
   [donut.system.repl.state :as dsrs]
   [fairy.box2.db :as db]
   [fairy.box2.media :as media]
   [fairy.box2.player :as player]
   [fairy.box2.runtime :as runtime]
   [fairy.box2.system :as system]))

(defonce ^:private stack_ (atom nil))

(defn- stack []
  (or @stack_
      (throw (ex-info "Box2 development stack is not started" {:operation :start-required}))))

(defn snapshot []
  (runtime/snapshot (:runtime (stack))))

(defn history []
  (runtime/history (:runtime (stack))))

(defn effects []
  (runtime/effects (:runtime (stack))))

(defn- active-value [configuration state-namespace]
  (some->> configuration
           (filter #(= state-namespace (namespace %)))
           first
           name
           keyword))

(defn status []
  (let [{:keys [configuration data]} (snapshot)]
    {:card-request (active-value configuration "card-request.st")
     :player       (active-value configuration "player.st")
     :rfid         (active-value configuration "rfid.st")
     :request      (or (get-in data [:audio :active-request])
                       (get-in data [:audio :pending-request]))}))

(defn- dispatch-effect! [stack {:keys [effect/data effect/type]}]
  (let [run     (:runtime stack)
        adapter @(:player_ stack)]
    (case type
      :media.fx/prepare
      (try
        (runtime/dispatch!
         run
         {:name :media.ev/prepared
          :data (assoc (select-keys data [:generation :request-id :settings-revision])
                       :paths (media/prepare! (:player adapter)
                                              (:media-dir stack)
                                              (:item-path data)))})
        (catch Throwable error
          (runtime/dispatch!
           run
           {:name :media.ev/preparation-failed
            :data (assoc (select-keys data [:generation :request-id :settings-revision])
                         :error {:category :media/preparation
                                 :message  (ex-message error)})})))

      :player.fx/install-queue
      (try
        (player/install-queue! adapter data)
        (runtime/dispatch! run {:name :player.ev/queue-installed
                                :data {:playback-context (:playback-context data)}})
        (catch Throwable error
          (runtime/dispatch! run {:name :player.ev/queue-install-failed
                                  :data {:playback-context (:playback-context data)
                                         :error            {:category :player/queue
                                                            :message  (ex-message error)}}})))

      :player.fx/pause
      (player/pause-playback! adapter)

      :player.fx/resume
      (player/resume-playback! adapter)

      :player.fx/start
      (player/start-playback! adapter data)

      nil)))

(defn stop! []
  (if-let [{:keys [player_ runtime]} @stack_]
    (do
      (reset! stack_ nil)
      (runtime/stop! runtime)
      (when-let [adapter @player_]
        (player/stop! adapter))
      :stopped)
    :already-stopped))

(defn start!
  "Starts the read-only DB, filesystem media, Vinyl/VLC, and chart stack."
  ([] (start! {}))
  ([{:keys [db-path media-dir]
     :or   {db-path   "data/db.edn"
            media-dir "../../sparklestories/media"}}]
   (stop!)
   (let [database       (db/read-db db-path)
         player_        (atom nil)
         run_           (atom nil)
         partial-stack  {:database_ (atom database)
                         :db-path   db-path
                         :media-dir media-dir
                         :player_   player_}
         run            (runtime/start! #(dispatch-effect! (assoc partial-stack :runtime @run_) %))
         _              (reset! run_ run)
         player-adapter (player/start! #(runtime/dispatch! run %))
         stack          (assoc partial-stack :runtime run)]
     (reset! player_ player-adapter)
     (reset! stack_ stack)
     (runtime/dispatch! run {:name :system.ev/initialized
                             :data {:settings          (db/settings database)
                                    :settings-revision 0}})
     (status))))

(defn place-card!
  "Resolves synthetic UID ingress outside the chart, then dispatches safe data."
  [uid]
  (when-not (and (string? uid) (not-empty uid))
    (throw (ex-info "RFID UID must be a non-empty string" {:uid uid})))
  (let [stack     (stack)
        item-path (db/linked-folder @(:database_ stack) uid)]
    (runtime/dispatch! (:runtime stack)
                       {:name :rfid.ev/card-placed
                        :data (cond-> {:request-id (random-uuid)
                                       :uid        uid}
                                item-path (assoc :item-path item-path))})))

(defn remove-card!
  "Dispatches removal for the currently presented synthetic card."
  []
  (let [uid (get-in (snapshot) [:data :rfid :present-uid])]
    (when-not uid
      (throw (ex-info "No RFID card is currently present" {:operation :remove-card})))
    (runtime/dispatch! (:runtime (stack))
                       {:name :rfid.ev/card-removed
                        :data {:uid uid}})))
(defmethod ds/named-system :donut.system/repl
  [_]
  (ds/system system/base-system
             {[:config] (system/read-config :dev-no-rpi)
              [:fairy.box/components :fairy.box.hardware/rfid] (fake/reader)}))

(comment
  ;; System Control
  (dsr/start)
  (dsr/stop)
  (dsr/restart)

  ;; Access the system's state
  dsrs/system
  (start!)
  (place-card! "dev-card-001")
  (status)
  (snapshot)
  (history)
  (place-card! "unknown-card")
  (remove-card!)
  (stop!)
  :rcf)
