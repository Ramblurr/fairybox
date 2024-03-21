(ns fairy.box.audio.interop
  "Attempt to confine the VLCJ interop to this namespace."
  (:require
   [clojure.tools.logging :as log]
   [clojure.java.io :as io]
   [clojure.string :as str])
  (:import
   [uk.co.caprica.vlcj.media.callback.seekable SeekableCallbackMedia]
   [java.nio.channels FileChannel]
   [java.nio.file Files Path Paths]
   [java.nio.file StandardOpenOption]
   [java.io IOException]
   [java.net URL]
   [uk.co.caprica.vlcj.player.base MediaPlayer]
   [uk.co.caprica.vlcj.player.component  AudioListPlayerComponent]
   [uk.co.caprica.vlcj.factory MediaPlayerFactory]
   [uk.co.caprica.vlcj.medialist MediaList MediaListRef MediaListEventAdapter]
   [uk.co.caprica.vlcj.player.list PlaybackMode MediaListPlayer]
   [uk.co.caprica.vlcj.media ParseFlag Meta Picture MetaData MediaParsedStatus MediaEventListener Media MediaRef MediaEventAdapter]))

(defn munge-enum-name [^Enum e]
  (-> e (.name) (str/replace #"\W" "-") (str/replace #"_" "-") (str/lower-case) (keyword)))

(defn init-player! ^AudioListPlayerComponent [player-event-handler!]
  (proxy [AudioListPlayerComponent] []
    (mediaStateChanged [media newState] (player-event-handler! {:event :internal-player/media-state-changed :media media :new-state (munge-enum-name newState)}))
    (timeChanged [_mediaPlayer newTime] (player-event-handler! {:event :internal-player/time-changed :new-time newTime}))
    (finished [mediaPlayer] (player-event-handler! {:event :internal-player/finished :listener this}))
    (error [mediaPlayer] (player-event-handler! {:event :internal-player/error}))
    (backward [_mediaPlayer] (player-event-handler! {:event :internal-player/backward}))
    (forward [_mediaPlayer] (player-event-handler! {:event :internal-player/forward}))
    (buffering [_mediaPlayer newCache] (player-event-handler! {:event :internal-player/buffering :new-cache newCache}))
    (lengthChanged [_ newLength] (player-event-handler! {:event :internal-player/length-changed :new-length newLength}))
    (mediaChanged [^MediaPlayer player ^MediaRef mediaRef]
      (player-event-handler! {:event :internal-player/media-changed :media-ref mediaRef :player player :listener this}))
    (mediaDurationChange [_ newDuration] (player-event-handler! {:event :internal-player/media-duration-change :new-duration newDuration}))
    (mediaListEndReached [_] (player-event-handler! {:event :internal-player/media-list-end-reached}))
    (mediaListPlayerFinished [_] (player-event-handler! {:event :internal-player/media-list-player-finished}))
    (mediaMetaChanged [^Media media ^Meta meta] (player-event-handler! {:event :internal-player/media-meta-changed :media media :meta meta}))
    (mediaParsedChanged [^Media media ^MediaParsedStatus newStatus] (player-event-handler! {:event :internal-player/media-parsed-changed :media media :new-status (munge-enum-name newStatus)}))
    (mediaPlayerReady [_] (player-event-handler! {:event :internal-player/media-player-ready}))
    (mediaThumbnailGenerated [^Media media ^Picture picture] (player-event-handler! {:event :internal-player/media-thumbnail-generate :media media :picture picture}))
    (muted [_ muted?] (player-event-handler! {:event :internal-player/muted :muted? muted?}))
    (nextItem [_ ^MediaRef mediaRef] (player-event-handler! {:event :internal-player/next-item :media-ref mediaRef}))
    (opening [_] (player-event-handler! {:event :internal-player/opening}))
    (paused [_] (player-event-handler! {:event :internal-player/paused}))
    (playing [_] (player-event-handler! {:event :internal-player/playing}))
    (positionChanged [_ newPosition] (player-event-handler! {:event :internal-player/position-changed :new-position newPosition}))
    (stopped [^MediaListPlayer _] (player-event-handler! {:event :internal-player/stopped}))
    (volumeChanged [_ newVolume] (player-event-handler! {:event :internal-player/volume-changed :new-volume newVolume}))
    (audioDeviceChanged [_  ^String audioDevice] (player-event-handler! {:event :internal-player/audio-device-changed :audio-device audioDevice}))
    (mediaListItemAdded [_ ^MediaList mediaList ^MediaRef mediaRef index] (player-event-handler! {:event :internal-player/media-list-item-added :media-list mediaList :media-ref mediaRef :index index}))
    (mediaListItemDeleted [_ ^MediaList mediaList ^MediaRef mediaRef index] (player-event-handler! {:event :internal-player/media-list-item-deleted :media-list mediaList :media-ref mediaRef :index index}))
    ;;
    ))

(defn release-player! [^AudioListPlayerComponent player]
  (when player
    (.release player)))

(defn metadata->map [^MetaData metadata]
  (->> (.values metadata)
       (reduce (fn [acc [k v]]
                 (assoc acc (munge-enum-name k) v)) {})))

(defn media->meta-map [^Media media]
  (when-let [metadata (-> media (.meta) (.asMetaData))]
    (metadata->map metadata)))

(defn media->mrl [^Media media]
  (-> media (.info) (.mrl)))

(defn media->media-type [^Media media]
  (munge-enum-name (-> media (.info) (.type))))

(defn media->media-state [^Media media]
  (munge-enum-name (-> media (.info) (.state))))

(defn media->media-duration [^Media media]
  (-> media (.info) (.duration)))

(defn extract-metadata-async [filename]
  (let [factory (MediaPlayerFactory.)
        media (-> factory (.media) (.newMedia filename nil))
        result-promise (promise)
        listener (proxy [MediaEventAdapter] []
                   (mediaParsedChanged [^Media media ^MediaParsedStatus newStatus]
                     (let [^MetaData metadata (-> media (.meta) (.asMetaData))]
                       (-> media (.events) (.removeMediaEventListener this))
                       (->> (.values metadata)
                            (reduce (fn [acc [k v]]
                                      (assoc acc (munge-enum-name k) v)) (:filename filename))
                            (deliver result-promise)))))]

    (-> media (.events) (.addMediaEventListener listener))
    (-> media (.parsing) (.parse (into-array ParseFlag [ParseFlag/FETCH_LOCAL])))
    result-promise))

(defn extract-metadata [filename]
  @(extract-metadata-async  filename))

(defn play! [^AudioListPlayerComponent player filename]
  (-> player  (.mediaPlayer) (.media) (.play filename nil)))

(defn play-index! [^AudioListPlayerComponent player index]
  (-> player  (.mediaListPlayer) (.controls) (.play index)))

(defn mute! [^AudioListPlayerComponent player]
  (-> player  (.mediaPlayer) (.audio) (.mute)))

(defn set-mute! [^AudioListPlayerComponent player muted?]
  (-> player  (.mediaPlayer) (.audio) (.setMute muted?)))

(defn muted? [^AudioListPlayerComponent player]
  (-> player  (.mediaPlayer) (.audio) (.isMute)))

(defn set-volume!
  "Set the volume in a range of 0 to 200.
  The volume is actually a percentage of full volume, setting a volume over 100 may cause audible distortion.
  "
  [^AudioListPlayerComponent player volume]
  (assert (<= 0 volume 200))
  (-> player  (.mediaPlayer) (.audio) (.setVolume volume)))

(defn volume
  "Get the volume in a range of 0 to 200."
  [^AudioListPlayerComponent player]
  (-> player  (.mediaPlayer) (.audio) (.volume)))

(defn adjust-volume!
  [^AudioListPlayerComponent player delta]
  (let [volume (volume player)]
    (set-volume! player (max 0 (min 100 (+ volume delta))))))

(defn stop! [^AudioListPlayerComponent player]
  (-> player (.mediaListPlayer) (.controls) (.stop)))

(defn pause! [^AudioListPlayerComponent player]
  (-> player (.mediaListPlayer) (.controls) (.pause)))

(defn set-pause! [^AudioListPlayerComponent player pause?]
  (-> player (.mediaListPlayer) (.controls) (.setPause pause?)))

(defn unpause! [^AudioListPlayerComponent player]
  (-> player (.mediaListPlayer) (.controls) (.play)))

(defn play-pause! [^AudioListPlayerComponent player]
  (let [playing?  (-> player (.mediaListPlayer) (.status) (.isPlaying))]
    (if playing?
      (pause! player)
      (unpause! player))))

(defn next! [^AudioListPlayerComponent player]
  (-> player (.mediaListPlayer) (.controls) (.playNext)))

(defn previous! [^AudioListPlayerComponent player]
  (-> player (.mediaListPlayer) (.controls) (.playPrevious)))

(defn repeat?
  "Get whether or not the media player will automatically repeat playing the media when it has finished playing."
  [^AudioListPlayerComponent player]
  (-> player (.mediaListPlayer) (.controls) (.getRepeat)))

(defn set-repeat-mode!
  "Set whether or not the media player should automatically repeat playing the media when it has finished playing.

   mode - :default, :loop, :repeat"
  [^AudioListPlayerComponent player mode]
  (assert (#{:default :loop :repeat} mode))
  (let [playback-mode (case mode
                        :default PlaybackMode/DEFAULT
                        :loop PlaybackMode/LOOP
                        :repeat PlaybackMode/REPEAT)]
    (-> player (.mediaListPlayer) (.controls) (.setMode playback-mode))))

(defn skip-time!
  "Skip forward or backward by a period of time.
   To skip backwards specify a negative delta."
  [^AudioListPlayerComponent player time]
  (assert (number? time))
  (-> player  (.mediaPlayer)  (.controls) (.skipTime time)))

(defn skip-position!
  "Skip forward or backward by a change in position.
    To skip backwards specify a negative delta.
  position is a relative percentage of the total length of the media."
  [^AudioListPlayerComponent player position]
  (assert (number? position))
  (-> player  (.mediaPlayer)  (.controls) (.skipPosition position)))

(defn set-time! [^AudioListPlayerComponent player time]
  (-> player  (.mediaPlayer)  (.controls) (.setTime time)))

(defn set-position! [^AudioListPlayerComponent player position]
  (-> player  (.mediaPlayer)  (.controls) (.setPosition position)))

(defn make-medias!
  "Create a new Media object for every item in paths.
  Returns a vector of Medias."
  [paths]
  (let [factory (MediaPlayerFactory.)]
    (->> paths
         (map (fn [path]
                (-> factory (.media) (.newMedia path nil)))))))

(defn release-medias!
  "Release all the Medias in medias."
  [medias]
  (doseq [media medias]
    (when media
      (-> media (.release)))))

(defn make-media-list
  "Create a new media list containing the specified paths.
  You must release the returned media list when you are finished with it."
  ^MediaList [medias]
  (let [factory (MediaPlayerFactory.)
        media-list (-> factory (.media) (.newMediaList))]
    (doseq [media medias]
      (let [media-ref (.newMediaRef media)]
        (try
          (-> media-list (.media) (.add media-ref nil))
          (finally
            (.release media-ref)))))
    media-list))

(defn release-media-list! [^MediaList media-list]
  (when media-list
    (.release media-list)))

(defn parse-event-listener ^MediaEventAdapter [callback]
  (proxy [MediaEventAdapter] []
    (mediaParsedChanged [^Media media ^MediaParsedStatus newStatus]
      (try
        (callback media (metadata->map (-> media (.meta) (.asMetaData))) newStatus)
        (finally
          (-> media (.events) (.removeMediaEventListener this)))))))

(defn parse-medias-async!
  [^MediaEventListener event-listener  medias]
  (doseq [^Media media medias]
    (-> media (.events) (.addMediaEventListener event-listener))
    (-> media (.parsing) (.parse))))

(defn medias-from-medialist [^MediaList list]
  (let [n-tracks (-> list (.media) (.count))]
    (for [i (range n-tracks)]
      (-> list (.media) (.newMedia i)))))

(defn set-media-list!
  "Set a new media list. The media list will be released."
  [^AudioListPlayerComponent player ^MediaList list]
  (let [list-ref (.newMediaListRef list)]
    (try
      (-> player (.mediaListPlayer) (.list) (.setMediaList list-ref))
      (finally
        (.release list-ref)))))

#_(defn play-folder! [^AudioListPlayerComponent player folder-path]
    (let [paths (->> (str (browse/media-dir settings)  "/" folder-path)
                     (browse/list-media-files)
                     (map :abs-path))]
      (if (seq paths)
        (do
          (let [media-list (make-media-list player paths)]
            (parse-media-list! player media-list)
            (SleepUtil/sleepMillis 1000)
            (set-media-list! player media-list))
          (unpause! player))
        (throw (ex-info "No media files found in folder" {:error :audio/no-media-files :folder-path folder-path})))))

(defn ->MappedByteBufferCallbackMedia [^java.nio.MappedByteBuffer buf close-fn]
  (proxy [SeekableCallbackMedia] []
    (onGetSize [] (.capacity buf))
    (onOpen []
      (if (nil? buf)
        false
        true))
    (onRead [buffer bufferSize]
      (let [remaining (.remaining buf)
            read (min bufferSize remaining)]
        (.get buf buffer 0 read)
        read))

    (onSeek [offset]
      (let [pos   (.position buf (cast Long (.intValue offset)))
            pos (.position pos)]
        (== pos offset)))
    (onClose []
      (close-fn))))

(defn ->FileMappedByteBufferCallbackMedia
  "This is an example of how to play media from a byte buffer"
  [url]
  (let [path (.toPath (io/file (.getFile url)))
        _ (prn "to path for thing" path)
        channel (Files/newByteChannel path (into-array StandardOpenOption [StandardOpenOption/READ]))
        ^java.nio.MappedByteBuffer buf (.map channel java.nio.channels.FileChannel$MapMode/READ_ONLY 0 (.size channel))]
    (->MappedByteBufferCallbackMedia buf (fn []
                                           (try
                                             (.close channel)
                                             (catch IOException e))))))

(defn play-mrl!
  "Plays an MRL directly"
  [^AudioListPlayerComponent player mrl]
  (-> player (.mediaPlayer) (.media) (.start mrl nil)))

(defn run-on-player-thread
  "Runs the function on the player thread."
  [^AudioListPlayerComponent player fn]
  (-> player (.mediaListPlayer) (.submit fn)))

(defn release-later
  "Releases each releaseable (Media, MediaList, etc.) on the player thread."
  [^AudioListPlayerComponent player releaseables]
  (run-on-player-thread player
                        (fn []
                          (doseq [releaseable releaseables]
                            (when releaseable
                              (.release releaseable))))))
