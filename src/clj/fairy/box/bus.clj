(ns fairy.box.bus
  (:require
   [jp.nijohando.event :as ev]
   [clojure.core.async :as async]
   [clojure.tools.logging :as log]
   [integrant.core :as ig]))

(defn start-test-listener [ch]
  (async/go-loop []
    (when-some [event (async/<! ch)]
      (prn "GOt some event" event)
      (recur))))

(defn init-bus! [opts]
  (let [bus (ev/bus)
        test-listener (async/chan)]
    (ev/listen bus "/hardware/input/buttons" test-listener)
    (ev/listen bus "/hardware/input/rfid" test-listener)
    (start-test-listener test-listener)
    bus))

(defmethod ig/init-key ::bus [_ opts]
  (log/info "\n-=[starting bus]=-")
  (init-bus! opts))

(defmethod ig/halt-key! ::bus [_ bus]
  (log/info "\n-=[goodbye bus]=-")
  (ev/close! bus))
