(ns fairy.box.web.player-events
  (:require
   [clojure.core.async :as async]
   [clojure.tools.logging :as log]
   [donut.system :as ds]
   [fairy.box.util :as util]
   [hyperlith.core :as h]
   [jp.nijohando.event :as ev]))

(def refresh-events
  #{:player/current-track-changed
    :player/muted
    :player/position-changed
    :player/queue-changed
    :player/repeat-changed
    :player/shuffle-changed
    :player/state-changed
    :player/time-changed
    :player/volume-changed})

(defn refresh-event? [{:keys [value]}]
  (contains? refresh-events (:event value)))

(defn start-player-refresh! [{:keys [bus refresh!]}]
  (assert bus "event bus is required")
  (assert refresh! "refresh function is required")
  (let [listener (async/chan (async/sliding-buffer 1)
                             (filter refresh-event?))
        throttled (util/throttle listener 500)
        worker (async/thread
                 (loop []
                   (when-some [_ (async/<!! throttled)]
                     (try
                       (refresh!)
                       (catch Exception error
                         (tap> ["player refresh failed" error])
                         (log/error error "player refresh failed")))
                     (recur))))]
    (ev/listen bus "/player/events" listener)
    {:listener listener
     :throttled throttled
     :refresh! refresh!
     :worker worker}))

(defn stop-player-refresh! [{:keys [listener throttled worker]}]
  (async/close! listener)
  (when throttled
    (async/close! throttled))
  (when worker
    (async/alts!! [worker (async/timeout 1000)]))
  nil)

(def PlayerEventRefreshComponent
  {::ds/start (fn [{config ::ds/config}]
                (start-player-refresh! config))
   ::ds/stop (fn [{instance ::ds/instance}]
               (stop-player-refresh! instance))
   ::ds/config {:bus [:donut.system/ref
                      [:fairy.box/components :fairy.box.bus/bus]]
                :refresh! h/refresh-all!}})
