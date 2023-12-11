(ns fairy.box.bus
  (:require [clojure.core.async :as async]
            [clojure.tools.logging :as log]

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

(defn system-loop [name handler topic exit-fn exit-ch subscriber {:keys [publication publisher] :as bus} init-state]
  (async/go-loop [state init-state]
    (async/alt!
      exit-ch ([_]
               ;; (prn "pre goodbye " name)
               (exit-fn state)
               (async/unsub publication topic subscriber)
               (async/close! exit-ch)
               (async/close! subscriber)
               (log/info (format "\n-=[goodbye %s]=-" name))
               nil)
      subscriber ([ev]
                  (handler state publisher ev)
                  (recur state)))))

(defn init-system [{:keys [bus] :as opts} name init-fn exit-fn handler topic]
  (log/info (format "\n-=[starting %s]=-" name))
  (let [{:keys [publication]} bus
        subscriber          (async/chan)
        exit-ch             (async/chan)
        sub                 (async/sub publication topic subscriber)
        init-state          (init-fn opts)]
    (system-loop name handler topic exit-fn exit-ch subscriber bus init-state)
    {:subscriber subscriber
     :name name
     :sub        sub
     :exit-ch    exit-ch
     :state      init-state}))

(defn halt-system! [{:keys [exit-ch name]}]
  (async/put! exit-ch true))

(defonce event-cache (atom {}))

(defn init-event-cache! [_]
  (reset! event-cache {})
  nil)

(defn release-event-cache! [opts])

(defn event-cache-handler! [_ publisher {:keys [topic value]}]
  (let [{:keys [uid action at]} value]
    (condp = action
      :placed (swap! event-cache assoc :current-uid uid)
      :removed (swap! event-cache assoc :current-uid nil)
      nil)))

(defmethod ig/init-key ::event-cache [_ opts]
  (init-system opts "event-cache" init-event-cache! release-event-cache! event-cache-handler! :rfid))

(defmethod ig/halt-key! ::event-cache [_ opts]
  (halt-system! opts))

(defn current-rfid! []
  (-> @event-cache :current-uid))
