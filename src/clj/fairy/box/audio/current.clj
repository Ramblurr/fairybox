(ns fairy.box.audio.current
  (:refer-clojure :exclude [time])
  (:require
   [clojure.string :as str]
   [fairy.box.audio.system2 :as audio]))

(defn current! []
  @audio/audio-state)

(defn current-track [c]
  (get-in c [:playback :current-track]))

(defn tts-cache-track? [track]
  (boolean
   (some-> (:mrl track)
           (str/ends-with? ".tts-cache"))))

(defn duration [c]
  (get-in c [:playback :current-track :duration]))

(defn artist [c]
  (get-in c [:playback :current-track :meta :meta/artist]))

(defn track-title [c]
  (get-in c [:playback :current-track :meta :meta/title]))

(defn album [c]
  (get-in c [:playback :current-track :meta :meta/album]))

(defn album-artist [c]
  (get-in c [:playback :current-track :meta :meta/album-artist]))

(defn track-number [c]
  (get-in c [:playback :current-track :meta :meta/track-number]))

(defn track-total [c]
  (get-in c [:playback :current-track :meta :meta/track-total]))

(defn disc-number [c]
  (get-in c [:playback :current-track :meta :meta/disc-number]))

(defn disc-total [c]
  (get-in c [:playback :current-track :meta :meta/disc-total]))

(defn genre [c]
  (get-in c [:playback :current-track :meta :meta/genre]))

(defn mrl [c]
  (get-in c [:playback :current-track :mrl]))

(defn volume [c]
  (get-in c [:mixer :volume]))

(defn muted? [c]
  (get-in c [:mixer :muted?]))

(defn time [c]
  (get-in c [:playback :time]))

(defn position [c]
  (get-in c [:playback :position]))

(defn state [c]
  (get-in c [:playback :state]))

(defn playing? [c]
  (#{:opening :playing :paused} (state c)))

(defn repeat-mode [c]
  (get-in c [:playback :repeat-mode] :none))

(defn shuffle? [c]
  (get-in c [:playback :shuffle?] false))

(defn queue [c]
  (get-in c [:queue :normal]))

(defn history [c]
  (get-in c [:queue :history]))

(defn queue-source-type [c]
  (get-in c [:queue :source-type]))

(defn queue-source-path [c]
  (get-in c [:queue :source-path]))

(defn full-queue [c]
  (get-in c [:queue :tracks]))

(defn display-track
  ([c]
   (display-track (current-track c) (full-queue c)))
  ([physical-current physical-queue]
   (if-not (tts-cache-track? physical-current)
     physical-current
     (or (some (fn [{:keys [index] :as track}]
                 (when (and (pos? (or index 0))
                            (not (tts-cache-track? track)))
                   track))
               physical-queue)
         (assoc physical-current :meta #:meta{:title "TTS"})))))

(defn display-queue [physical-queue]
  (into [] (remove tts-cache-track?) physical-queue))
