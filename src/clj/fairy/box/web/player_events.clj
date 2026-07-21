(ns fairy.box.web.player-events
  (:require
   [clojure.core.async :as async]
   [clojure.tools.logging :as log]
   [donut.system :as ds]
   [fairy.box.util :as util]
   [fairy.box.web.player-progress :as progress]
   [hyperlith.core :as h]
   [jp.nijohando.event :as ev]))

(def progress-events
  #{:player/position-changed
    :player/time-changed})

(def refresh-events
  #{:player/current-track-changed
    :player/muted
    :player/queue-changed
    :player/repeat-changed
    :player/shuffle-changed
    :player/state-changed
    :player/volume-changed
    :auto-shutdown/changed
    :sleep/changed})

(defn progress-event? [{:keys [value]}]
  (contains? progress-events (:event value)))

(defn refresh-event? [{:keys [value]}]
  (contains? refresh-events (:event value)))

(defn- start-worker [channel operation error-message]
  (async/thread
    (loop []
      (when-some [_ (async/<!! channel)]
        (try
          (operation)
          (catch Exception error
            (tap> [error-message error])
            (log/error error error-message)))
        (recur)))))

(defn start-player-refresh! [{:keys [bus progress! refresh!]}]
  (assert bus "event bus is required")
  (assert progress! "progress push function is required")
  (assert refresh! "refresh function is required")
  (let [progress-listener  (async/chan (async/sliding-buffer 1)
                                       (filter progress-event?))
        progress-throttled (util/throttle progress-listener 250)
        progress-worker    (start-worker progress-throttled
                                         progress!
                                         "player progress push failed")
        refresh-listener   (async/chan (async/sliding-buffer 1)
                                       (filter refresh-event?))
        refresh-throttled  (util/throttle refresh-listener 500)
        refresh-worker     (start-worker refresh-throttled
                                         refresh!
                                         "player refresh failed")]
    (ev/listen bus "/auto-shutdown/events" refresh-listener)
    (ev/listen bus "/player/events" progress-listener)
    (ev/listen bus "/player/events" refresh-listener)
    (ev/listen bus "/sleep/events" refresh-listener)
    {:progress-listener  progress-listener
     :progress-throttled progress-throttled
     :progress-worker    progress-worker
     :refresh-listener   refresh-listener
     :refresh-throttled  refresh-throttled
     :refresh-worker     refresh-worker}))

(defn stop-player-refresh!
  [{:keys [listener progress-listener progress-throttled progress-worker
           refresh-listener refresh-throttled refresh-worker throttled worker]}]
  (doseq [channel [listener progress-listener progress-throttled
                   refresh-listener refresh-throttled throttled]]
    (when channel
      (async/close! channel)))
  (doseq [task [progress-worker refresh-worker worker]]
    (when task
      (async/alts!! [task (async/timeout 1000)])))
  nil)

(def PlayerEventRefreshComponent
  {::ds/start
   (fn [{config ::ds/config}]
     (start-player-refresh!
      (assoc config
             :progress!
             #(progress/broadcast! (:progress-stream config)))))
   ::ds/stop   (fn [{instance ::ds/instance}]
                 (stop-player-refresh! instance))
   ::ds/config {:bus      [:donut.system/ref
                           [:fairy.box/components :fairy.box.bus/bus]]
                :progress-stream
                [:donut.system/ref
                 [:fairy.box/components :fairy.box.web/player-progress]]
                :refresh! h/refresh-all!}})
