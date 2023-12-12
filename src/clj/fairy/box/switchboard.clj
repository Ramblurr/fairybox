(ns fairy.box.switchboard
  (:require
   [medley.core :as m]
   [jp.nijohando.event :as ev]
   [clojure.core.async :as async]
   [clojure.tools.logging :as log]
   [integrant.core :as ig]))

(defn rfid-scanned [{:keys [db-conn]} event]
  (tap> {:rfid-scanned event}))

(def ^:private patch-ports {:rfid  {:handler rfid-scanned
                                    :name :rfid
                                    :path "/hardware/input/rfid"}})

(defn init-switchboard! [{:keys [bus] :as opts}]
  (let [channels  (m/map-keys (fn [_] (async/chan)) patch-ports)
        exit-ch (async/chan)]
    (doseq [[channel {:keys [path]}] channels]
      (ev/listen bus path channel))
    (async/go-loop []
      (let [[event channel] (async/alts! (concat [exit-ch] (keys channels)))]
        (if (= exit-ch channel)
          (do
            (async/close! exit-ch)
            nil)
          (let [{:keys [handler]} (channels channel)]
            (when event
              (handler opts event))
            (recur)))))
    {:channels channels
     :exit-ch exit-ch}))

(defn halt-switchboard! [{:keys [channels exit-ch]}]
  (async/put! exit-ch true)
  (doseq [channel (keys channels)] (async/close! channel)))

(defmethod ig/init-key ::switchboard [_ opts]
  (log/info "\n-=[starting switchboard]=-")
  (init-switchboard! opts))

(defmethod ig/halt-key! ::switchboard [_ opts]
  (log/info "\n-=[goodbye switchboard]=-")
  (halt-switchboard! opts))
