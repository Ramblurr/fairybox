(ns fairy.box.audio.system
  (:import
   [uk.co.caprica.vlcj.media ParseFlag Meta Picture MetaData MediaParsedStatus MediaEventListener Media MediaRef MediaEventAdapter])
  (:require
   [fairy.box.audio.browse :as browse]
   [medley.core :as m]
   [jp.nijohando.event :as ev]
   [clojure.core.async :as async]
   [fairy.box.audio.interop :as interop]
   [clojure.tools.logging :as log]
   [integrant.core :as ig]
   [fairy.box.db :as db]))

(defn- player-event
  "Constructs a valid event map for a player event"
  [event]
  {:path "/player/events" :value event})

(def ^:private audio-init-state {:play-requests {}
                                 :current-play-request nil
                                 :current-track {} ; media's meta data, title, artist, etc
                                 :current-playback {} ; current playback state, position, time, etc
                                 :player {}
                                 :config {:volume-up-step 5
                                          :volume-down-step -5}})

(defonce ^:private audio-state (atom audio-init-state))

(defn current-play-request! []
  (let [state @audio-state]
    (get-in state [:play-requests (:current-play-request state)])))

(defn current-track! []
  (-> @audio-state :current-track))

(defn current-play-queue! []
  (let [{:keys [source-type folder-path media-info playlist-path]} (current-play-request!)
        current-track (current-track!)]
    {:source-type source-type
     :source-path (or  folder-path playlist-path)
     :tracks (map (fn [{:keys [mrl] :as meta}]
                    {:current?  (= (:mrl current-track) mrl)
                     :meta meta}) (vals media-info))}))

(defn current-playback! []
  (-> @audio-state :current-playback))

(defn- release-play-request! [{:keys [media-list]}]
  (interop/release-media-list! media-list))

(defn- release-play-request-by-id! [state play-request-id]
  (release-play-request! (get-in state [:play-requests play-request-id])))

(defn media-info [^Media media]
  (let [file-info {:mrl (interop/media->mrl media)
                   :media-state (interop/media->media-state media)
                   :duration (interop/media->media-duration media)
                   :media-type (interop/media->media-type media)}
        meta (or (interop/media->meta-map media) {})]
    (merge meta file-info)))

(defn parse-handler [{:keys [player internal-ch]} play-request-id]
  (interop/parse-event-listener
    ;; this is the parse handler callback
   (fn [^Media media meta-map status]
     (try
       (async/put! internal-ch {:event           :internal-player/pre-play-parse
                                :media-info      (media-info media)
                                :status          status
                                :play-request-id play-request-id
                                :meta-map        meta-map})
       (catch Exception e
         (log/error e "parse media error")
         (tap> {:parse-error e}))
       (finally
         (interop/release-later player [media]))))))

(defn pre-play-parse-playlist-items! [{:keys [player] :as sys} {:keys [id media-list] :as play-request}]
  (let [playlist-media (-> media-list (.media) (.newMedia 0))
        playlist-media-list (-> playlist-media (.subitems) (.newMediaList))
        subitem-count (-> playlist-media-list (.media) (.count))
        new-medias (interop/medias-from-medialist playlist-media-list)
        play-request (-> play-request
                         (assoc :media-list playlist-media-list)
                         (assoc :media-info {})
                         (assoc :parsed-countdown subitem-count)
                         (assoc :playlist-items-parsed true))]
    (swap! audio-state assoc-in [:play-requests id] play-request)
    (interop/release-later player [media-list])
    (interop/parse-medias-async! (parse-handler sys id) new-medias)))

(defn parse-countdown-finished! [{:keys [internal-ch] :as sys} {:keys [source-type id playlist-items-parsed] :as play-request}]
  (let [should-parse-subitems (and (= :playlist source-type) (not playlist-items-parsed))]

    (if should-parse-subitems
      (pre-play-parse-playlist-items! sys play-request)
      (async/put! internal-ch {:event :internal-player/pre-play-parse-finished :play-request-id id}))))

(defn handle-pre-play-parse!
  "This event is emitted in the parse handler callback, once for each item in the play request.
  We countdown the items parsed until they have all been parsed, then kick off the :internal-player/pre-play-parse-finished event"
  [sys {:keys [media-info play-request-id] :as ev}]
  (let [state            @audio-state
        {:keys [parsed-countdown] :as play-request} (get-in state [:play-requests play-request-id])
        parsed-countdown (dec parsed-countdown)
        play-request (-> play-request
                         (assoc :parsed-countdown parsed-countdown)
                         (assoc-in [:media-info (:mrl media-info)] media-info))]
    #_(tap> {:parsed-countdown parsed-countdown :play-request-id play-request-id})
    (if (= 0 parsed-countdown)
      (parse-countdown-finished! sys play-request)
      (swap! audio-state assoc-in [:play-requests play-request-id] play-request))))

(defn stop-and-release-current! [player {:keys [current-play-request] :as state}]
  (when current-play-request
    (interop/stop! player)
    (release-play-request-by-id! state current-play-request)))

(defn handle-pre-play-parse-finished! [{:keys [player]} {:keys [play-request-id]}]
  (let [state @audio-state
        current-play-request-id (get-in state [:current-play-request])
        current-play-request (get-in state [:play-requests current-play-request-id])
        finished-parsing-play-request (get-in state [:play-requests play-request-id])
        current-play-request (or current-play-request -1)]
    (if (< (:created-at finished-parsing-play-request)  (:created-at current-play-request -1))
      (do
        ;; this play request that just finished parsing has been superceded
        ;; so we don't need to play it, just release it
        (release-play-request-by-id! state play-request-id)
        (swap! audio-state m/dissoc-in [:play-requests play-request-id]))
      (do
        ;; this play request that just finished parsing should supercede the current one
        (stop-and-release-current! player state)
        (swap! audio-state m/dissoc-in [:play-requests current-play-request-id])
        (swap! audio-state assoc :current-play-request play-request-id)
        (interop/set-media-list! player (get-in state [:play-requests play-request-id :media-list]))
        (interop/unpause! player)))))

(defn prepare-play-request-folder [{:keys [source-type folder-path track-paths play-request-id]}]
  (let [medias (interop/make-medias! track-paths)
        media-list (interop/make-media-list medias)]
    [medias
     {:id play-request-id
      :created-at (System/currentTimeMillis)
      :source-type source-type
      :folder-path folder-path
      :media-list media-list
      :parsed-countdown (count medias)}]))

(defn prepare-play-request-playlist [{:keys [playlist-path source-type play-request-id]}]
  (let [medias (interop/make-medias! [playlist-path])
        media-list (interop/make-media-list medias)]
    [medias
     {:id play-request-id
      :created-at (System/currentTimeMillis)
      :source-type source-type
      :playlist-path playlist-path
      :playlist-items-parsed false
      :media-list media-list
      :parsed-countdown (count medias)}]))

(defn handle-play-request! [sys {:keys [source-type play-request-id] :as req}]
  (when-let [[medias new-play-request] (condp = source-type
                                         :folder   (prepare-play-request-folder req)
                                         :playlist (prepare-play-request-playlist req)
                                         nil)]
    (when new-play-request
      (swap! audio-state assoc-in [:play-requests play-request-id] new-play-request)
      ;; kick off async parse
      (interop/parse-medias-async! (parse-handler sys play-request-id) medias))))

(defn handle-media-changed [{:keys [player emitter]} {:keys [media-ref]}]
  (let [media (-> media-ref (.newMedia))]
    (try
      (let [info (media-info media)]
        (swap! audio-state assoc :current-track info)
        ;; (tap> {:meta info :media media :active (current-play-request!)})
        (async/put! emitter (player-event {:event :player/media-changed :info info})))
      (finally
        (.release media)))))

(def not-interesting #{:internal-player/buffering :internal-player/length-changed
                       :internal-player/audio-device-changed
                       :internal-player/position-changed
                       :internal-player/time-changed})

(defn internal-event-handler [{:keys [emitter player] :as sys} event]
  (try
    #_(when-not (contains? not-interesting (:event event))
        (tap> {(:event event) event}))
    (condp = (:event event)
      :internal-player/pre-play-parse          (handle-pre-play-parse! sys event)
      :internal-player/pre-play-parse-finished (handle-pre-play-parse-finished! sys event)
      :internal-player/play-request            (handle-play-request! sys event)
      :internal-player/media-changed           (handle-media-changed sys event)
      :internal-player/muted                   (do
                                                 (swap! audio-state assoc-in [:current-playback :muted?] (:muted? event))
                                                 (async/put! emitter (player-event {:event :player/muted :muted? (:muted? event)})))
      :internal-player/volume-changed          (let [new-volume (max 0 (:new-volume event))]
                                                 ;; sometimes vlc will send -1
                                                 (swap! audio-state assoc-in [:current-playback :current-volume] new-volume)
                                                 (async/put! emitter (player-event {:event :player/volume-changed :volume new-volume})))
      :internal-player/playing                 (do (swap! audio-state assoc-in [:current-playback :state] :playing)
                                                   (swap! audio-state assoc-in [:current-playback :current-volume] (float (/ (interop/volume player) 100)))
                                                   (async/put! emitter (player-event {:event :player/state-changed :state :playing})))
      :internal-player/paused                  (do (swap! audio-state assoc-in [:current-playback :state] :paused)
                                                   (async/put! emitter (player-event {:event :player/state-changed :state :paused})))
      :internal-player/stopped                  (do (swap! audio-state assoc-in [:current-playback :state] :stopped)
                                                    (async/put! emitter (player-event {:event :player/state-changed :state :stopped})))
      :internal-player/opening                 (do (swap! audio-state assoc-in [:current-playback :state] :opening)
                                                   (async/put! emitter (player-event {:event :player/state-changed :state :opening})))
      :internal-player/finished                (do (swap! audio-state assoc-in [:current-playback :state] :finished)
                                                   (async/put! emitter (player-event {:event :player/state-changed :state :finished})))
      :internal-player/position-changed        (do (swap! audio-state assoc-in [:current-playback :current-position] (:new-position event))
                                                   (async/put! emitter (player-event {:event :player/position-changed :position (:new-position event)})))
      :internal-player/time-changed            (do (swap! audio-state assoc-in [:current-playback :current-time] (:new-time event))
                                                   (async/put! emitter (player-event {:event :player/time-changed :time (:new-time event)})))
      :internal-player/repeat-changed          (do
                                                 (swap! audio-state assoc-in [:current-playback :repeat-mode] (:mode event))
                                                 (async/put! emitter (player-event {:event :player/repeat-changed :time (:mode event)})))
      nil)
    (catch Exception e
      (log/error e "internal event error"))))

(def fixers {:media (fn [^Media media]
                      (if (and (.meta media) (-> media (.meta) (.asMetaData)))
                        (do
                          ;; (tap> :FIXINGSHIT)
                          (->> (.values (-> media (.meta) (.asMetaData)))
                               (reduce (fn [acc [k v]]
                                         (assoc acc (interop/munge-enum-name k) v)) {})))

                        media))
             :media-ref identity
             :new-status identity
             :picture identity
             :media-list identity
             :meta identity})

(defn fix-event [event]
  (m/map-kv (fn [k v]
              (let [fix-fn (get fixers k identity)]
                [k (fix-fn v)]))

            event))

(defn raw-event-handler! [emitter event]
  ;; (tap> {(:event event) event})
  #_(when (= :player/media-changed (:event event))
    ;; (tap> {:player/media-changed event})
      (-> (:player event) (.submit (fn []
                                     (let [media (-> (:media-ref event) (.newMedia))]
                                       (-> media (.events) (.addMediaEventListener (:listener event)))
                                       (-> media (.parsing) (.parse)))))))

  #_(async/put! emitter (player-event (fix-event event))))

(defn can-resume-play-request? [new-play-request-id]
  (let [current-play-request-id (-> @audio-state :current-play-request)]
    (tap> [:can-resume-play-request? :new new-play-request-id :current current-play-request-id (= new-play-request-id current-play-request-id)])
    (= new-play-request-id current-play-request-id)))

(defn play-folder!
  "Starts the process to play all media in the given folder-path.
  Playback will not start right away, first the media has to be parsed asynchronously"
  [{:keys [internal-ch player]} folder-path]
  (if (can-resume-play-request? folder-path)
    (interop/unpause! player)
    (let [track-paths  (browse/list-media-file-paths folder-path)]
      (if (seq track-paths)
        (async/put! internal-ch {:event :internal-player/play-request
                                 :source-type :folder
                                 :folder-path folder-path
                                 :track-paths track-paths
                                 :play-request-id folder-path})
        (throw (ex-info "No media files found in folder" {:error :audio/no-media-files :folder-path folder-path}))))))

(defn play-playlist! [{:keys [internal-ch player]} playlist-path]
  (if (can-resume-play-request? playlist-path)
    (interop/unpause! player)
    (async/put! internal-ch {:event :internal-player/play-request
                             :source-type :playlist
                             :playlist-path playlist-path
                             :play-request-id playlist-path})))

(defn play-url! [{:keys [player]} url]
  (let [medias (interop/make-medias! [url])
        media-list (interop/make-media-list medias)]
    (interop/stop! player)
    (interop/set-media-list! player media-list)
    (interop/unpause! player)
    (interop/release-media-list! media-list)
    (interop/release-medias! medias)))

(defn play-path! [sys item-path]
  (condp = (browse/playable-type (:settings sys) item-path)
    :dir (play-folder! sys item-path)
    :playlist (play-playlist! sys item-path)
    :url (play-url! sys item-path)
    :tts (play-url! sys item-path)
    (throw (ex-info "Unknown playable-type" {:error :audio/not-playable-type :path item-path}))))

(defn metadata-for
  "Returns (blocks!) a vector of parsed metadata for the .m3u or all files in path."
  [sys item-path]
  (condp = (browse/playable-type (:settings sys) item-path)
    :dir (interop/parse-medias-sync (browse/list-media-file-paths item-path))
    :playlist (interop/parse-media-playlist-sync item-path)
    (throw (ex-info "Unknown playable-type" {:error :audio/not-playable-type :path item-path}))))

(defn maximum-volume
  ([db current-hour]
   (let [;; user configured absolute max volume - can never be louder than this
         absolute-max-volume (db/max-volume db)
         hour-day-start (db/hour-day-start db)
         hour-night-start (db/hour-night-start db)
         max-volume-night (db/max-volume-night db)
         max-volume-day (db/max-volume-day db)
         m (min absolute-max-volume
                (if (< (dec hour-day-start) current-hour hour-night-start)
                  (or max-volume-day absolute-max-volume)
                  (or max-volume-night absolute-max-volume)))]
     #_(tap> {:hour current-hour
              :hour-day-start hour-day-start
              :hour-night-start hour-night-start
              :max-volume-night max-volume-night
              :max-volume-day max-volume-day
              :absolute-max-volume absolute-max-volume
              :result m})
     m))
  ([db]
   (maximum-volume db (-> (java.time.LocalTime/now) (.getHour)))))

(defn new-player! [db handler]
  (let [player (interop/init-player! handler)]
    (interop/set-volume! player (maximum-volume db))
    player))

(defn play-one-shot! [{:keys [emitter db-conn]} {:keys [item-path id]}]
  ;; this one-shot function creates a new player, plays the item, and then releases the player
  ;; we do this because we want to handle the events separate from the normal player
  (let [player-atom (atom nil)
        handler     (fn [{:keys [event]}]
                      (try
                        (when (= event :internal-player/finished)
                          (async/put! emitter (player-event {:event :player/one-shot-finished :id id}))
                          (future
                            (try
                              (Thread/sleep 500)
                              (when @player-atom
                                (interop/stop! @player-atom)
                                (Thread/sleep 500)
                                (interop/release-player! @player-atom))
                              (catch Exception e
                                (log/error e "one-shot cleanup error")))))
                        (catch Exception e
                          (log/error e "one-shot event error"))))

        player      (new-player! @db-conn handler)]
    (interop/play-mrl! player item-path)
    (reset! player-atom player)))

(defn wrap-volume [db-conn new-volume]
  (let [db @db-conn
        minv (db/min-volume db)
        maxv (maximum-volume db)
        v (max minv (min maxv new-volume))]
    #_(tap> {:minv minv :maxv maxv :v v})
    v))

(defn adjust-volume! [{:keys [player db-conn] :as sys} delta]
  (let [current (interop/volume player)
        new-volume (wrap-volume db-conn (+ current delta))]
    (interop/set-volume! player new-volume)))

(defn set-volume! [{:keys [player db-conn] :as sys} v]
  (interop/set-volume! player (wrap-volume db-conn v)))

(defn command-handler [{:keys [player internal-ch] :as sys} {:keys [path value] :as event}]
  (try
    (let [{:keys [action item-path]} value
          {:keys [config]} @audio-state]
      (condp = action
        :audio/play-one-shot (play-one-shot! sys value)
        :audio/play-path (play-path! sys item-path)
        :audio/stop (interop/stop! player)
        :audio/play-pause (interop/play-pause! player)
        :audio/next (interop/next! player)
        :audio/prev (interop/previous! player)
        :audio/volume-up (adjust-volume! sys (get-in config [:volume-up-step]))
        :audio/volume-down (adjust-volume! sys (get-in config [:volume-down-step]))
        :audio/skip-time (interop/skip-time! player (get-in value [:milliseconds]))
        :audio/set-time (interop/set-time! player (get-in value [:milliseconds]))
        :audio/adjust-volume (adjust-volume! sys (get-in value [:delta]))
        :audio/set-volume (set-volume! sys
                                       ;; interop wants [0, 100] integer
                                       (max 0 (min (int (* 100 (get-in value [:volume]))) 100)))
        :audio/set-pause (interop/set-pause! player (:paused? value))
        :audio/play (interop/set-pause! player false)
        :audio/pause (interop/set-pause! player true)
        :audio/set-mute (interop/set-mute! player (:muted? value))
        :audio/toggle-mute (interop/mute! player)
        :audio/play-queue-index (interop/play-index! player (:item-index value))
        :audio/set-repeat (when (:mode value)
                            ;; hack alert: vlcj does not expose any events or state around the repeat mode
                            ;; so we have to track it ourselves
                            (interop/set-repeat-mode! player (:mode value))
                            (async/put! internal-ch {:event :internal-player/repeat-changed :mode (:mode value)}))
        nil))
    (catch Exception e
      (log/error e "audio command error"))))

(defn- release-all-resources! [{:keys [player]}]
  (try
    (interop/stop! player)
    (let [state @audio-state]
      (doseq [req (vals (get-in state [:play-requests]))]
        (release-play-request! req)))
    (catch Exception e
      (log/error e "release-all-resources error"))
    (finally
      (reset! audio-state audio-init-state)
      (interop/release-player! player))))

(defn- audio-loop [{:keys [exit-ch internal-ch commands-ch] :as sys}]
  (async/go-loop []
    (async/alt!
    ;; NOTE - all functions called here must not throw exceptions
      exit-ch ([_]
               (release-all-resources! sys)
               (async/close! exit-ch)
               nil)
      internal-ch ([ev]
                   (internal-event-handler sys ev)
                   (recur))
      commands-ch ([ev]
                   (command-handler sys ev)
                   (recur)))))

(defn- init-audio! [{:keys [bus settings db-conn]}]
  (let [emitter (async/chan)
        commands-ch (async/chan (async/sliding-buffer 2048))
        internal-ch (async/chan (async/sliding-buffer 2048))
        exit-ch (async/chan)
        player (new-player! @db-conn (fn [ev]
                                       (try
                                         (async/put! internal-ch ev)
                                         (catch Exception e
                                           (log/error e "player put internal-ch error")))))
        sys {:emitter emitter
             :settings settings
             :db-conn db-conn
             :commands-ch commands-ch
             :internal-ch internal-ch
             :exit-ch exit-ch
             :player player}]
    (ev/listen bus "/player/commands" commands-ch)
    (ev/emitize bus emitter)
    (assoc sys :audio-loop (audio-loop sys))))

(defn- halt-player! [{:keys [internal-ch exit-ch commands-ch emitter audio-loop player]}]
  (async/put! exit-ch true)
  (async/close! commands-ch)
  (async/close! internal-ch)
  (async/close! emitter)
  (async/close! audio-loop))

(defmethod ig/init-key ::player [_ opts]
  (log/info "\n-=[starting audio]=-")
  (init-audio! opts))

(defmethod ig/halt-key! ::player [_ opts]
  (log/info "\n-=[goodbye audio]=-")
  (halt-player! opts))
