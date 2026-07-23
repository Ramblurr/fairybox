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
   [fairy.box2.system :as system]
   [taoensso.trove :as trove]))

(defonce ^:private stack_ (atom nil))

(defn- stack []
  (or @stack_
      (throw (ex-info "Box2 development stack is not started"
                      {:operation :start-required}))))

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

(defn- dispatch-effect! [media_ player_ {:effect/keys [type] :as effect}]
  (case (namespace type)
    "media.fx"
    (if-let [adapter @media_]
      (media/offer! adapter effect)
      {:accepted? false :reason :media-adapter-not-started})

    "player.fx"
    (if-let [adapter @player_]
      (player/dispatch-effect! adapter effect)
      {:accepted? false :reason :player-adapter-not-started})

    ;; The playable-card development stack intentionally has no LED, timer,
    ;; database-write, TTS, one-shot, or host-operation adapters.
    {:accepted? true :reason :not-configured-in-development}))

(defn- stop-component! [failures_ component stop-fn]
  (try
    (stop-fn)
    (catch Throwable error
      (let [failure {:component component :error error}]
        (swap! failures_ conj failure)
        (trove/log! {:level :error
                     :id    ::component-stop-failed
                     :msg   "Box2 development component failed to stop"
                     :data  failure})))))

(defn stop! []
  (if-let [{:keys [media-adapter player-adapter rfid-adapter runtime]} @stack_]
    (let [failures_ (atom [])]
      (reset! stack_ nil)
      (stop-component! failures_ :rfid #(rfid/stop! rfid-adapter))
      (stop-component! failures_ :media #(media/stop! media-adapter))
      (stop-component! failures_ :player #(player/stop! player-adapter))
      (stop-component! failures_ :runtime #(runtime/stop! runtime))
      (when (seq @failures_)
        (throw (ex-info "One or more Box2 development components failed to stop"
                        {:failures @failures_})))
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
         media_         (atom nil)
         player_        (atom nil)
         run            (runtime/start! #(dispatch-effect! media_ player_ %))
         submit!        #(runtime/submit! run %)
         player-adapter (player/start!
                         {:submit!        submit!
                          :submit-latest! #(runtime/submit-latest! run %)})
         _              (reset! player_ player-adapter)
         media-adapter  (media/start! {:media-dir media-dir
                                       :player    (:player player-adapter)
                                       :submit!   submit!})
         _              (reset! media_ media-adapter)
         rfid-reader    (fake/reader)
         rfid-adapter   (rfid/start! {:reader            rfid-reader
                                      :resolve-item-path #(db/linked-folder
                                                           @database_
                                                           %)
                                      :submit!           submit!})
         stack          {:database_      database_
                         :db-path        db-path
                         :media-adapter  media-adapter
                         :media-dir      media-dir
                         :player-adapter player-adapter
                         :rfid-adapter   rfid-adapter
                         :rfid-reader    rfid-reader
                         :runtime        run}]
     (reset! stack_ stack)
     (runtime/submit-and-await!
      run
      {:name :system.ev/initialized
       :data {:settings          (db/settings database)
              :settings-revision 0}})
     (status))))

(defn- await-if-accepted [runtime ticket]
  (if (:accepted? ticket)
    (runtime/await! runtime ticket)
    ticket))

(defn place-card!
  "Reports synthetic presence for `uid` and awaits it when the level changed."
  [uid]
  (when-not (and (string? uid) (not-empty uid))
    (throw (ex-info "RFID UID must be a non-empty string" {:uid uid})))
  (let [{:keys [rfid-reader runtime]} (stack)]
    (await-if-accepted runtime (fake/place! rfid-reader uid))))

(defn remove-card!
  "Reports synthetic absence and awaits it when the level changed."
  []
  (let [{:keys [rfid-reader runtime]} (stack)]
    (await-if-accepted runtime (fake/remove! rfid-reader))))

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

  ;; Observe the same UID again without an absence. Shared RFID ingress
  ;; suppresses the unchanged level, so no chart event or new request appears.
  (place-card! "dev-card-001")
  (status)
  (select-keys (get-in (snapshot) [:data :rfid])
               [:presence-epoch :present-uid])

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
