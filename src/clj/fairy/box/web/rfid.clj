(ns fairy.box.web.rfid
  (:require
   [clojure.core.async :as async]
   [donut.system :as ds]
   [hyperlith.core :as h]
   [jp.nijohando.event :as ev]))

(defn start-presence! [{:keys [bus refresh!]}]
  (assert bus "event bus is required")
  (assert refresh! "refresh function is required")
  (let [listener (async/chan (async/sliding-buffer 8))
        state (atom {:action :removed :uid nil})
        worker (async/thread
                 (loop []
                   (when-some [{:keys [value]} (async/<!! listener)]
                     (when (#{:placed :removed} (:action value))
                       (reset! state value)
                       (refresh!))
                     (recur))))]
    (ev/listen bus "/hardware/input/rfid" listener)
    {:listener listener
     :refresh! refresh!
     :state state
     :worker worker}))

(defn stop-presence! [{:keys [listener worker]}]
  (async/close! listener)
  (when worker
    (async/alts!! [worker (async/timeout 1000)]))
  nil)

(defn current-uid [{:keys [state]}]
  (let [{:keys [action uid]} (some-> state deref)]
    (when (= :placed action)
      uid)))

(defn refresh! [{refresh-fn :refresh!}]
  (refresh-fn))

(def RfidPresenceComponent
  {::ds/start (fn [{config ::ds/config}]
                (start-presence! config))
   ::ds/stop (fn [{instance ::ds/instance}]
               (stop-presence! instance))
   ::ds/config {:bus [:donut.system/ref
                      [:fairy.box/components :fairy.box.bus/bus]]
                :refresh! h/refresh-all!}})
