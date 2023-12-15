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
  (str (java.util.UUID/randomUUID)))

(def ^:private audio-init-state {:play-requests {}
                                 :current-play-request nil
                                 :current-track {}
                                 :player {}
                                 :config {:volume-up-step 5
                                          :volume-down-step -5}})

(defonce ^:private audio-state (atom audio-init-state))

(defn current-play-request! []
  (let [state @audio-state]
    (get-in state [:play-requests (:current-play-request state)])))

(defn current-track! []
  (-> @audio-state :current-track))

(defn- release-play-request! [state play-request-id]
  (let [{:keys [medias media-list]} (get-in state [:play-requests play-request-id])]
    (when medias
      (interop/release-medias! medias))
    (when media-list
      (interop/release-media-list! media-list))))

(defn handle-pre-play-parse [{:keys [internal-ch player]} {:keys [media status meta-map play-request-id]}]
  (let [state @audio-state]
    (if (not= play-request-id (-> state :current-play-request))
      (do
        (release-play-request! state play-request-id)
        (swap! audio-state m/dissoc-in [:play-requests play-request-id]))
      (let [parsed-countdown (dec (get-in state [:play-requests play-request-id :parsed-countdown]))]
        (swap! audio-state assoc-in [:play-requests play-request-id :parsed-countdown] parsed-countdown)
        (when (= 0 parsed-countdown)
          (async/put! internal-ch {:event :internal-player/pre-play-parse-finished :play-request-id play-request-id}))))))

(defn handle-pre-play-parse-finished [{:keys [player]} {:keys [play-request-id]}]
  (let [state @audio-state]
    (if (not= play-request-id (-> state :current-play-request))
      (do
        (release-play-request! state play-request-id)
        (swap! audio-state m/dissoc-in [:play-requests play-request-id]))
      (do
        (interop/set-media-list! player (get-in state [:play-requests play-request-id :media-list]))
        (interop/unpause! player)))))

(defn handle-media-changed [{:keys [emitter]} {:keys [media-ref]}]
  (let [media (-> media-ref (.newMedia))]
    (try
      (let [file-info {:mrl (interop/media->mrl media)
                       :media-state (interop/media->media-state media)
                       :duration (interop/media->media-duration media)
                       :media-type (interop/media->media-type media)}
            meta (or (interop/media->meta-map media) {})
            info     (merge meta file-info)]
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
      :internal-player/media-changed (handle-media-changed sys event)
      :internal-player/position-changed (async/put! emitter (player-event {:event :player/position-changed :position (:new-position event)}))
      :internal-player/time-changed (async/put! emitter (player-event {:event :player/time-changed :time (:new-time event)}))
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
  Playback will not start right away, first the media has to be parsed."
  [{:keys [internal-ch]} folder-path]
  (let [track-paths  (browse/list-media-file-paths folder-path)
        play-request-id (new-id!)]
    (if (seq track-paths)
      (do
        (let [medias (interop/make-medias! track-paths)
              media-list (interop/make-media-list medias)]
          (swap! audio-state
                 (fn [state]
                   (-> state
                       (assoc :current-play-request play-request-id)
                       (assoc-in [:play-requests play-request-id] {:id play-request-id  :folder-path folder-path :track-paths track-paths :medias medias :media-list media-list :parsed-countdown (count medias)}))))
          (interop/parse-medias! (interop/parse-event-listener
                                  (fn [^Media media meta-map status]
                                    (async/put! internal-ch {:event :internal-player/pre-play-parse
                                                             :media media
                                                             :status status
                                                             :play-request-id play-request-id
                                                             :meta-map meta-map})))
                                 medias)))

      (throw (ex-info "No media files found in folder" {:error :audio/no-media-files :folder-path folder-path})))))

(defn command-handler [{:keys [player] :as sys} {:keys [path value] :as event}]
  (try
    (tap> {:command value})
    (let [{:keys [action folder-path]} value
          {:keys [config]} @audio-state]
      (condp = action
        :audio/play-folder (play-folder! sys folder-path)
        :audio/stop (interop/stop! player)
        :audio/play-pause (interop/play-pause! player)
        :audio/next (interop/next! player)
        :audio/prev (interop/previous! player)
        :audio/volume-up (interop/adjust-volume! player (get-in config [:volume-up-step]))
        :audio/volume-down (interop/adjust-volume! player ((get-in config [:volume-down-step])))
        nil))
    (catch Exception e
      (log/error e "audio command error"))))

(defn- release-all-resources! [{:keys [player]}]
  (try
    (interop/stop! player)
    (let [state @audio-state]
      (doseq [{:keys [medias media-list]} (vals (get-in state [:play-requests]))]
        (when medias
          (interop/release-medias! medias))
        (when media-list
          (interop/release-media-list! media-list))))
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
