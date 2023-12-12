(ns fairy.box.bus
  (:require
   [jp.nijohando.event :as ev]
   [clojure.core.async :as async]
   [clojure.tools.logging :as log]
   [integrant.core :as ig]))

#_(defn start-test-listener [ch]
    (async/go-loop []
      (when-some [event (async/<! ch)]
        (prn "GOt some event" event)
        (recur))))

(defn init-bus! [opts]
  (let [bus (ev/bus)
        #_#_test-listener (async/chan)]
    #_(ev/listen bus "/hardware/input/buttons" test-listener)
    #_(ev/listen bus "/hardware/input/rfid" test-listener)
    #_(start-test-listener test-listener)
    bus))

(defmethod ig/init-key ::bus [_ opts]
  (log/info "\n-=[starting bus]=-")
  (init-bus! opts))

(defmethod ig/halt-key! ::bus [_ bus]
  (log/info "\n-=[goodbye bus]=-")
  (ev/close! bus))
