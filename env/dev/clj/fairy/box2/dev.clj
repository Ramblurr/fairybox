(ns fairy.box2.dev
  "REPL control surface for the real local Box2 playable-card stack."
  (:require
   [donut.system :as ds]
   [donut.system.repl :as dsr]
   [donut.system.repl.state :as dsrs]
   [fairy.box2.db :as db]
   [fairy.box2.media :as media]
   [fairy.box2.player :as player]
   [fairy.box2.rfid :as rfid]
   [fairy.box2.rfid.fake :as fake]
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

(defn errors []
  (runtime/errors (:runtime (stack))))

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

(defn- execute-effect! [stack {:keys [effect/data effect/type]}]
  (let [run     (:runtime stack)
        adapter @(:player_ stack)]
    (case type
      :media.fx/prepare
      (try
        (runtime/submit!
         run
         {:name :media.ev/prepared
          :data (assoc (select-keys data [:generation :request-id :settings-revision])
                       :paths (media/prepare! (:player adapter)
                                              (:media-dir stack)
                                              (:item-path data)))})
        (catch Throwable error
          (runtime/submit!
           run
           {:name :media.ev/preparation-failed
            :data (assoc (select-keys data [:generation :request-id :settings-revision])
                         :error {:category :media/preparation
                                 :message  (ex-message error)})})))

      :player.fx/install-queue
      (try
        (player/install-queue! adapter data)
        (runtime/submit! run {:name :player.ev/queue-installed
                              :data {:playback-context (:playback-context data)}})
        (catch Throwable error
          (runtime/submit! run {:name :player.ev/queue-install-failed
                                :data {:playback-context (:playback-context data)
                                       :error            {:category :player/queue
                                                          :message  (ex-message error)}}})))

      :player.fx/pause
      (player/pause-playback! adapter)

      :player.fx/resume
      (player/resume-playback! adapter)

      :player.fx/start
      (player/start-playback! adapter data)

      :player.fx/stop
      (player/stop-playback! adapter)

      nil)))

(defn stop! []
  (if-let [{:keys [player_ rfid-adapter runtime]} @stack_]
    (do
      (reset! stack_ nil)
      (try
        (rfid/stop! rfid-adapter)
        (runtime/stop! runtime)
        (finally
          (when-let [adapter @player_]
            (player/stop! adapter))))
      :stopped)
    :already-stopped))

(defn start!
  "Starts the read-only DB, filesystem media, fake RFID, Vinyl/VLC, and chart stack."
  ([] (start! {}))
  ([{:keys [db-path media-dir]
     :or   {db-path   "data/db.edn"
            media-dir "../../sparklestories/media"}}]
   (stop!)
   (let [database       (db/read-db db-path)
         database_      (atom database)
         player_        (atom nil)
         run_           (atom nil)
         partial-stack  {:database_ database_
                         :db-path   db-path
                         :media-dir media-dir
                         :player_   player_}
         run            (runtime/start! #(execute-effect!
                                          (assoc partial-stack :runtime @run_)
                                          %))
         _              (reset! run_ run)
         player-adapter (player/start! #(runtime/submit! run %))
         rfid-reader    (fake/reader)
         rfid-adapter   (rfid/start! {:reader            rfid-reader
                                      :resolve-item-path #(db/linked-folder @database_ %)
                                      :submit!           #(runtime/submit! run %)})
         stack          (assoc partial-stack
                               :rfid-adapter rfid-adapter
                               :rfid-reader rfid-reader
                               :runtime run)]
     (reset! player_ player-adapter)
     (reset! stack_ stack)
     (runtime/submit-and-await!
      run
      {:name :system.ev/initialized
       :data {:settings          (db/settings database)
              :settings-revision 0}})
     (status))))

(defn place-card!
  "Reports synthetic presence for `uid` and waits a bounded time for its commit."
  [uid]
  (when-not (and (string? uid) (not-empty uid))
    (throw (ex-info "RFID UID must be a non-empty string" {:uid uid})))
  (let [{:keys [rfid-reader runtime]} (stack)]
    (runtime/await! runtime (fake/place! rfid-reader uid))))

(defn remove-card!
  "Reports synthetic absence and waits a bounded time for its commit."
  []
  (let [{:keys [rfid-reader runtime]} (stack)]
    (runtime/await! runtime (fake/remove! rfid-reader))))

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

;; Start (or fully reset) the real DB, filesystem, Vinyl/VLC, and chart stack.
  (start!)
  (status)

  ;; Play the registered development card. Evaluate `status` as asynchronous
  ;; preparation and VLC callbacks advance it to :active/:playing.
  (place-card! "dev-card-001")
  (status)

  ;; Observe the same UID again without an absence. This advances only the
  ;; observation sequence; it does not create a request or restart playback.
  (place-card! "dev-card-001")
  (status)
  (select-keys (get-in (snapshot) [:data :rfid])
               [:observation-seq :presence-epoch :present-uid])

  ;; Remove the active card and place it again. With the development settings,
  ;; removal pauses playback and the newer presence epoch resumes it.
  (remove-card!)
  (status)
  (place-card! "dev-card-001")
  (status)

  ;; Exercise direct card supersession without an intermediate absence. After
  ;; the development card is playing, this changes present -> present with a
  ;; different UID. Substitute another registered UID to prepare linked media.
  (place-card! "unknown-card")
  (status)
  (get-in (snapshot) [:data :rfid])

  ;; Inspect autonomous chart processing and dispatched effects.
  (snapshot)
  (take-last 10 (history))
  (take-last 10 (effects))

  (stop!)
  :rcf)
