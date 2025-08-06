(ns fairy.box.audio.current
  (:require [fairy.box.audio.system2 :as audio]
            [clojure.string :as str]))

(defn current! []
  @audio/audio-state)

(defn duration [c]
  (get-in c [:playback :current-track :duration]))

(defn artist [c]
  (get-in c [:playback :current-track :duration :meta :meta/artist]))

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
  (get-in c [:playback :mrl]))

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
  (#{:opening :playing} (state c)))

(defn repeat-mode [c]
  (get-in c [:playback :repeat-mode] :none))

(defn shuffle? [c]
  (get-in c [:playback :shuffle?] false))

(defn queue [c]
  (get-in c [:queue :normal]))

(defn history [c]
  (get-in c [:queue :history]))

(defn full-queue [c]
  (let [{:keys [history current priority normal]} (get-in c [:queue])]
    (->>
     (concat
      (map-indexed #(assoc %2 :index (- (- (count history) %1))) history)
      (when current [(assoc current :index 0)])
      (map-indexed #(assoc %2 :index (+ 1 %1)) priority)
      (map-indexed #(assoc %2 :index (+ 1 (count priority) %1)) normal))
     (remove nil?))))
