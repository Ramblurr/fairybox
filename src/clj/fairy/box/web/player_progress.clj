(ns fairy.box.web.player-progress
  (:require
   [clojure.tools.logging :as log]
   [donut.system :as ds]
   [fairy.box.audio.current :as player]
   [hyperlith.core :as h]
   [hyperlith.impl.router :as router]
   [starfederation.datastar.clojure.adapter.http-kit :as d*http-kit]
   [starfederation.datastar.clojure.api :as d*]))

(def stream-path "/api/player/progress-stream")
(def component-key :fairy.box.web/player-progress)

(defn duration-data
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

(defn format-duration [milliseconds]
  (when milliseconds
    (let [{:keys [days hours minutes seconds milliseconds]}
          (duration-data milliseconds)
          rounded-seconds (if (> milliseconds 0)
                            (inc seconds)
                            seconds)]
      (str (when (> days 0) (format "%02dd " days))
           (when (> hours 0) (format "%02d:" hours))
           (format "%02d" minutes)
           ":"
           (format "%02d" rounded-seconds)))))

(defn format-time-left [current-time duration]
  (if (or (nil? duration) (zero? duration))
    (format-duration duration)
    (str "-" (format-duration (- duration (or current-time 0))))))

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
     :_server_time      (format-duration current-time)
     :_server_time_left (format-time-left current-time duration)}))

(defn start-progress-stream! []
  {:connections (atom #{})})

(defn register! [{:keys [connections]} sse]
  (swap! connections conj sse)
  sse)

(defn unregister! [{:keys [connections]} sse]
  (swap! connections disj sse)
  nil)

(defn- send-signals! [stream sse signals]
  (try
    (if (d*/patch-signals! sse signals)
      true
      (do
        (unregister! stream sse)
        false))
    (catch Exception error
      (unregister! stream sse)
      (log/warn error "Failed to push player progress")
      false)))

(defn- progress-json []
  (h/edn->json (progress-signals (player/current!))))

(defn broadcast! [{:keys [connections] :as stream}]
  (let [signals (progress-json)]
    (doseq [sse @connections]
      (send-signals! stream sse signals)))
  nil)

(defn stop-progress-stream! [{:keys [connections] :as stream}]
  (doseq [sse @connections]
    (try
      (d*/close-sse! sse)
      (catch Exception error
        (log/warn error "Failed to close player progress stream")))
    (unregister! stream sse))
  nil)

(defn stream-handler
  [{:fairy.box/keys [component] :as req}]
  (let [stream (component component-key)]
    (d*http-kit/->sse-response
     req
     {d*http-kit/on-open
      (fn [sse]
        (register! stream sse)
        (send-signals! stream sse (progress-json)))

      d*http-kit/on-close
      (fn [sse _status]
        (unregister! stream sse))})))

(router/add-route! [:get stream-path] #'stream-handler)

(def ProgressStreamComponent
  {::ds/start (fn [_]
                (start-progress-stream!))
   ::ds/stop  (fn [{instance ::ds/instance}]
                (stop-progress-stream! instance))})
