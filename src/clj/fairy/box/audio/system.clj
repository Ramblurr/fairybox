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
   [integrant.core :as ig]))

(defn- player-event
  "Constructs a valid event map for a player event"
  [event]
  {:path "/player/events" :value event})

(defn new-id! []
  (System/nanoTime))

(def ^:private audio-init-state {:play-requests {}
                                 :current-play-request nil
                                 :current-track {} ; media's meta data, title, artist, etc
                                 :current-playback {} ; current playback state, position, time, etc
                                 :player {}
                                 :config {:volume-up-step 5
                                          :volume-down-step -5}})

(defonce ^:private audio-state (atom audio-init-state))

@audio-state

(defn current-play-request! []
  (let [state @audio-state]
    (get-in state [:play-requests (:current-play-request state)])))

(defn current-track! []
  (-> @audio-state :current-track))

(defn current-play-queue! []
  (let [request (current-play-request!)
        current-track (current-track!)]
    {:folder-path (:folder-path request)
     :tracks (map (fn [^Media media]
                    (let [meta (get-in request [:media-info media])]
                      {:current? (= (:mrl current-track) (:mrl meta))
                       :meta meta})) (:medias request))}))

(defn current-playback! []
  (-> @audio-state :current-playback))

(defn- release-play-request! [{:keys [medias media-list]}]
  (when medias
    (interop/release-medias! medias))
  (when media-list
    (interop/release-media-list! media-list)))

(defn- release-play-request-by-id! [state play-request-id]
  (release-play-request! (get-in state [:play-requests play-request-id])))

(defn media-info [^Media media]
  (let [file-info {:mrl (interop/media->mrl media)
                   :media-state (interop/media->media-state media)
                   :duration (interop/media->media-duration media)
                   :media-type (interop/media->media-type media)}
        meta (or (interop/media->meta-map media) {})]
    (merge meta file-info)))

(defn handle-pre-play-parse [{:keys [internal-ch]} {:keys [media play-request-id]}]
  (let [state            @audio-state
        parsed-countdown (dec (get-in state [:play-requests play-request-id :parsed-countdown]))]
    (swap! audio-state assoc-in [:play-requests play-request-id :parsed-countdown] parsed-countdown)
    (swap! audio-state assoc-in [:play-requests play-request-id :media-info media] (media-info media))
    (when (= 0 parsed-countdown)
      (async/put! internal-ch {:event :internal-player/pre-play-parse-finished :play-request-id play-request-id}))))

(defn stop-and-release-current! [player {:keys [current-play-request] :as state}]
  (when current-play-request
    (interop/stop! player)
    (release-play-request-by-id! state current-play-request)))

(defn handle-pre-play-parse-finished [{:keys [player]} {:keys [play-request-id]}]
  (let [{:keys [current-play-request] :as state} @audio-state
        current-play-request (or current-play-request -1)]
    (if (< play-request-id current-play-request)
      (do
        ;; this play request that just finished parsing has been superceded
        (release-play-request-by-id! state play-request-id)
        (swap! audio-state m/dissoc-in [:play-requests play-request-id]))
      (do
        ;; this play request that just finished parsing should supercede the current one
        (stop-and-release-current! player state)
        (swap! audio-state m/dissoc-in [:play-requests current-play-request])
        (swap! audio-state assoc :current-play-request play-request-id)
        (interop/set-media-list! player (get-in state [:play-requests play-request-id :media-list]))
        (interop/unpause! player)))))

(defn handle-play-request [{:keys [internal-ch]} {:keys [folder-path track-paths play-request-id]}]
  (let [medias (interop/make-medias! track-paths)
        media-list (interop/make-media-list medias)]
    (swap! audio-state
           (fn [state]
             (-> state
                 #_(assoc :current-play-request play-request-id)
                 (assoc-in [:play-requests play-request-id] {:id play-request-id  :folder-path folder-path :track-paths track-paths :medias medias :media-list media-list :parsed-countdown (count medias)}))))
    ;; kick off async parse
    (interop/parse-medias! (interop/parse-event-listener
                            (fn [^Media media meta-map status]
                              ;; this is the parse handler callback
                              (async/put! internal-ch {:event :internal-player/pre-play-parse
                                                       :media media
                                                       :status status
                                                       :play-request-id play-request-id
                                                       :meta-map meta-map})))
                           medias)))

(defn handle-media-changed [{:keys [emitter]} {:keys [media-ref]}]
  (let [media (-> media-ref (.newMedia))]
    (try
      (let [info (media-info media)]
        (swap! audio-state assoc :current-track info)
        ;; (tap> {:meta info :media media :active (current-play-request!)})
        (async/put! emitter (player-event {:event :player/media-changed :info info})))
      (finally
        (.release media)))))

(def not-interesting #{:internal-player/buffering :internal-player/length-changed :internal-player/audio-device-changed
                       :internal-player/position-changed
                       :internal-player/time-changed})

(defn internal-event-handler [{:keys [emitter] :as sys} event]
  (try
    (when-not (contains? not-interesting (:event event))
      (tap> {(:event event) event}))
    (condp = (:event event)
      :internal-player/pre-play-parse          (handle-pre-play-parse sys event)
      :internal-player/pre-play-parse-finished (handle-pre-play-parse-finished sys event)
      :internal-player/play-request            (handle-play-request sys event)
      :internal-player/media-changed (handle-media-changed sys event)
      :internal-player/muted (do
                               (swap! audio-state assoc-in [:current-playback :muted?] (:muted? event))
                               (async/put! emitter (player-event {:event :player/muted :muted? (:muted? event)})))
      :internal-player/volume-changed (do
                                        (swap! audio-state assoc-in [:current-playback :current-volume] (:new-volume event))
                                        (async/put! emitter (player-event {:event :player/volume-changed :volume (:new-volume event)})))
      :internal-player/playing (do (swap! audio-state assoc-in [:current-playback :state] :playing)
                                   (async/put! emitter (player-event {:event :player/state-changed :state :playing})))
      :internal-player/paused (do (swap! audio-state assoc-in [:current-playback :state] :paused)
                                  (async/put! emitter (player-event {:event :player/state-changed :state :paused})))
      :internal-player/opening (do (swap! audio-state assoc-in [:current-playback :state] :opening)
                                   (async/put! emitter (player-event {:event :player/state-changed :state :opening})))
      :internal-player/finished (do (swap! audio-state assoc-in [:current-playback :state] :finished)
                                    (async/put! emitter (player-event {:event :player/state-changed :state :finished})))
      :internal-player/position-changed (do (swap! audio-state assoc-in [:current-playback :current-position] (:new-position event))
                                            (async/put! emitter (player-event {:event :player/position-changed :position (:new-position event)})))
      :internal-player/time-changed (do (swap! audio-state assoc-in [:current-playback :current-time] (:new-time event))
                                        (async/put! emitter (player-event {:event :player/time-changed :time (:new-time event)})))
      :internal-player/repeat-changed (do
                                        (swap! audio-state assoc-in [:current-playback :repeat-mode] (:mode event))
                                        (async/put! emitter (player-event {:event :player/repeat-changed :time (:mode event)})))
      :internal-player/one-shot-finished (when-let [player (:player event)]
                                           (.release player))

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

(defn play-folder!
  "Starts the process to play all media in the given folder-path.
  Playback will not start right away, first the media has to be parsed asynchronously"
  [{:keys [internal-ch]} folder-path]
  (let [track-paths  (browse/list-media-file-paths folder-path)
        play-request-id (new-id!)]
    (if (seq track-paths)
      (async/put! internal-ch {:event :internal-player/play-request
                               :folder-path folder-path
                               :track-paths track-paths
                               :play-request-id play-request-id})
      (throw (ex-info "No media files found in folder" {:error :audio/no-media-files :folder-path folder-path})))))

(defn play-path! [sys item-path]
  (condp = (browse/playable-type item-path)
    :dir (play-folder! sys item-path)
    nil))

(defn play-one-shot! [{:keys [internal-ch emitter]} {:keys [item-path id]}]
  (letfn [(handler [{:keys [event listener]}]
            (when (= event :internal-player/finished)
              (async/put! emitter (player-event {:event :player/one-shot-finished :id id}))
              ;; we can't release the player here because it will crash
              (async/put! internal-ch {:event :internal-player/one-shot-finished :player listener})))]
    (let [player (interop/init-player! handler)]
      (interop/play-from-classpath! player item-path))))

(defn command-handler [{:keys [player internal-ch] :as sys} {:keys [path value] :as event}]
  (try
    (tap> {:command value})
    (let [{:keys [action item-path]} value
          {:keys [config]} @audio-state]
      (condp = action
        :audio/play-one-shot (play-one-shot! sys value)
        :audio/play-path (play-path! sys item-path)
        :audio/stop (interop/stop! player)
        :audio/play-pause (interop/play-pause! player)
        :audio/next (interop/next! player)
        :audio/prev (interop/previous! player)
        :audio/volume-up (interop/adjust-volume! player (get-in config [:volume-up-step]))
        :audio/volume-down (interop/adjust-volume! player (get-in config [:volume-down-step]))
        :audio/skip-time (interop/skip-time! player (get-in value [:milliseconds]))
        :audio/set-time (interop/set-time! player (get-in value [:milliseconds]))
        :audio/adjust-volume (interop/adjust-volume! player (get-in value [:delta]))
        :audio/set-volume (interop/set-volume! player
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

(defn- init-audio! [{:keys [bus]}]
  (let [emitter (async/chan)
        commands-ch (async/chan)
        internal-ch (async/chan)
        exit-ch (async/chan)
        player (interop/init-player! (fn [ev]
                                       (async/put! internal-ch ev)))
        sys {:emitter emitter
             :commands-ch commands-ch
             :internal-ch internal-ch
             :exit-ch exit-ch
             :player player}]
    (ev/listen bus "/player/commands" commands-ch)
    (ev/emitize bus emitter)
    (assoc sys :audio-loop (audio-loop sys))))

(defn- halt-player! [{:keys [internal-ch exit-ch commands-ch emitter audio-loop]}]
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
