(ns fairy.box.web.player-progress
  (:require
   [fairy.box.audio.current :as player]))

(defn- milliseconds->parts
  [^long duration-in-millis]
  (let [milliseconds      (mod duration-in-millis 1000)
        duration-in-secs  (quot duration-in-millis 1000)
        seconds           (mod duration-in-secs 60)
        duration-in-mins  (quot duration-in-secs 60)
        minutes           (mod duration-in-mins 60)
        duration-in-hours (quot duration-in-mins 60)
        hours             (mod duration-in-hours 24)
        days              (quot duration-in-hours 24)]
    {:milliseconds milliseconds
     :seconds      seconds
     :minutes      minutes
     :hours        hours
     :days         days}))

(defn time-label [milliseconds]
  (when milliseconds
    (let [{:keys [days hours minutes seconds milliseconds]}
          (milliseconds->parts milliseconds)
          rounded-seconds (if (> milliseconds 0)
                            (inc seconds)
                            seconds)]
      (str (when (> days 0) (format "%02dd " days))
           (when (> hours 0) (format "%02d:" hours))
           (format "%02d" minutes)
           ":"
           (format "%02d" rounded-seconds)))))

(defn time-left-label [current-time duration]
  (if (or (nil? duration) (zero? duration))
    (time-label duration)
    (str "-" (time-label (- duration (or current-time 0))))))

(defn progress-percentage [current-position]
  (if (number? current-position)
    (-> (* 100.0 current-position)
        (max 0.0)
        (min 100.0))
    0.0))

(defn progress-signals [current]
  (let [current-time (or (player/time current) 0)
        duration     (player/duration current)]
    {:_server_progress
     (progress-percentage (player/position current))
     :_server_time      (time-label current-time)
     :_server_time_left (time-left-label current-time duration)}))
