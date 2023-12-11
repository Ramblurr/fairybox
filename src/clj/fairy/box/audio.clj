(ns fairy.box.audio
  (:require
   [clojure.core.async :as async]
   [fairy.box.audio.interop :as interop]
   [clojure.tools.logging :as log]
   [integrant.core :as ig]))

(def player-commands-topic :player-control)
(def player-events-topic :player-events)

(defn handler [player publisher {:keys [topic value] :as event}]
  (println "player got: " value)
  (when (= :bar (:foo value))
    (async/put! publisher {:topic player-events-topic :value {:foo :baz}})))

(defn audio-loop [exit-ch subscriber {:keys [publication publisher] :as bus} init-state]
  (log/info "\n-=[starting audio]=-")
  (async/go-loop [player init-state]
    (async/alt!
      exit-ch ([_]
               (log/info "\n-=[goodbye audio]=-")
               (interop/release-player! player)
               (async/unsub publication player-commands-topic subscriber)
               (async/close! exit-ch)
               (async/close! subscriber)
               nil)
      subscriber ([ev]
                  (handler player publisher ev)
                  (recur player)))))

(defn init-audio [{:keys [bus]}]
  (let [subscriber (async/chan)
        exit-ch (async/chan)
        sub (async/sub (:publication bus) player-commands-topic subscriber)
        init-state (interop/init-player)]
    (audio-loop exit-ch subscriber bus init-state)
    {:subscriber subscriber
     :sub sub
     :exit-ch exit-ch
     :state init-state}))

(defn halt-player! [{:keys [exit-ch]}]
  (async/put! exit-ch true))

(defmethod ig/init-key ::player [_ opts]
  (init-audio opts))

(defmethod ig/halt-key! ::player [_ opts]
  (halt-player! opts))
