(ns fairy.box.audio.interop
  (:require
   [fairy.box.audio.browse :as browse]
   [clojure.string :as str]
   [clojure.tools.logging :as log])
  (:import
   [uk.co.caprica.vlcj.player.base MediaPlayer]
   [uk.co.caprica.vlcj.player.component  AudioListPlayerComponent]
   [uk.co.caprica.vlcj.factory MediaPlayerFactory]
   [uk.co.caprica.vlcj.medialist MediaList MediaListRef MediaListEventAdapter]
   [uk.co.caprica.vlcj.player.list PlaybackMode]
   [uk.co.caprica.vlcj.media ParseFlag MetaData MediaParsedStatus MediaEventListener Media MediaRef MediaEventAdapter]))

(defn init-player ^AudioListPlayerComponent []
  (proxy [AudioListPlayerComponent] []
    (mediaStateChanged [media newState]
      ;; (prn "State changed to" newState)
      )
    (timeChanged [mediaPlayer newTime]
      ;; (prn "Time changed to" newTime)
      )
    (finished [mediaPlayer]
      (prn "Finished playing media"))
    (error [mediaPlayer]
      (prn "Failed to play media"))))

(defn release-player! [^AudioListPlayerComponent player]
  (.release player))

(defn munge-enum-name [^Enum e]
  (-> e (.name) (str/replace #"\W" "-") (str/replace #"_" "-") (str/lower-case) (keyword)))

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

(defn mute! [^AudioListPlayerComponent player]
  (-> player  (.mediaPlayer) (.audio) (.mute)))

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

   repeat - true to automatically replay the media, otherwise false"
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
  (-> player  (.mediaPlayer)  (.controls) (.skipTime time)))

(defn skip-position!
  "Skip forward or backward by a change in position.
    To skip backwards specify a negative delta.
  position is a relative percentage of the total length of the media."
  [^AudioListPlayerComponent player position]
  (-> player  (.mediaPlayer)  (.controls) (.skipPosition position)))

(defn set-time! [^AudioListPlayerComponent player time]
  (-> player  (.mediaPlayer)  (.controls) (.setTime time)))

(defn set-position! [^AudioListPlayerComponent player position]
  (-> player  (.mediaPlayer)  (.controls) (.setPosition position)))

(defn- make-media-list
  "Create a new media list containing the specified paths.
  You must release the returned media list when you are finished with it."
  ^MediaList [^AudioListPlayerComponent player paths]
  (let [factory (MediaPlayerFactory.)
        media-list (-> factory (.media) (.newMediaList))]
    (doseq [path paths]
      (let [media-ref (-> factory (.media) (.newMediaRef path nil))]
        (-> media-list (.media) (.add media-ref nil))
        (.release media-ref)))
    media-list))

(defn- set-media-list!
  "Set a new media list. The media list will be released."
  [^AudioListPlayerComponent player ^MediaList list]
  (let [list-ref (.newMediaListRef list)]
    (try
      (-> player (.mediaListPlayer) (.list) (.setMediaList list-ref))
      (finally
        (.release list-ref)))))

(defn play-folder! [^AudioListPlayerComponent player folder-path]
  (let [paths (->> (str browse/media-dir "/" folder-path)
                   (browse/list-media-files)
                   (map :abs-path))]
    (if (seq paths)
      (do
        (set-media-list! player (make-media-list player paths))
        (unpause! player))
      (throw (ex-info "No media files found in folder" {:error :audio/no-media-files :folder-path folder-path})))))
