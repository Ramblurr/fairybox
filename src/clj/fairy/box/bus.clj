(ns fairy.box.bus
  (:require [clojure.core.async :as async]
            [integrant.core :as ig]))

(def topics
  "Not really used? Just for documentation"
  #{:player-control                     ; Commands to the audio sub system
    :player-events                      ; Events from the audio sub system
    :buttons                            ; Events from the buttons input sub system
    :rfid                               ; Events from the rfid input sub system
    :leds                               ; Events to the leds output sub system
    })

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
