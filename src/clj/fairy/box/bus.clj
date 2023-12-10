(ns fairy.box.bus
  (:require [clojure.core.async :as async]
            [integrant.core :as ig]))

(defn init-bus [opts]
  (let [publisher (async/chan)
        publication (async/pub publisher #(:topic %))]
    {:publisher publisher
     :publication publication}))

(defmethod ig/init-key ::bus [_ opts]
  (init-bus opts))

(defmethod ig/halt-key! ::bus [_ {:keys [publisher publication]}]
  (async/unsub-all publication)
  (async/close! publisher))

(defn publish-event
  "Publish an event to the bus from outside a go block."
  [{:keys [publisher] :as bus} event]
  (async/go (async/>! publisher event)))
