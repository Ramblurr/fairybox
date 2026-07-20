(ns fairy.box.web.player-events
  (:require
   [clojure.core.async :as async]
   [donut.system :as ds]
   [hyperlith.core :as h]
   [jp.nijohando.event :as ev]))

(defn start-queue-refresh! [{:keys [bus refresh!]}]
  (assert bus "event bus is required")
  (assert refresh! "refresh function is required")
  (let [listener (async/chan (async/sliding-buffer 8))
        worker (async/thread
                 (loop []
                   (when-some [{:keys [value]} (async/<!! listener)]
                     (when (= :player/queue-changed (:event value))
                       (refresh!))
                     (recur))))]
    (ev/listen bus "/player/events" listener)
    {:listener listener
     :refresh! refresh!
     :worker worker}))

(defn stop-queue-refresh! [{:keys [listener worker]}]
  (async/close! listener)
  (when worker
    (async/alts!! [worker (async/timeout 1000)]))
  nil)

(def PlayerEventRefreshComponent
  {::ds/start (fn [{config ::ds/config}]
                (start-queue-refresh! config))
   ::ds/stop (fn [{instance ::ds/instance}]
               (stop-queue-refresh! instance))
   ::ds/config {:bus [:donut.system/ref
                      [:fairy.box/components :fairy.box.bus/bus]]
                :refresh! h/refresh-all!}})
