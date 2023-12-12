(ns fairy.box.audio
  (:require
   [jp.nijohando.event :as ev]
   [clojure.core.async :as async]
   [fairy.box.audio.interop :as interop]
   [clojure.tools.logging :as log]
   [integrant.core :as ig]))

(defn command-handler [player emitter {:keys [path value] :as event}]
  (try
    (tap> {:command value})
    (let [{:keys [action folder-path]} value]
      (condp = action
        :audio/play-folder (interop/play-folder! player folder-path)
        :audio/stop (interop/stop! player)
        :audio/play-pause (interop/play-pause! player)
        :audio/next (interop/next! player)
        :audio/prev (interop/previous! player)
        :audio/volume-up (interop/adjust-volume! player 5)
        :audio/volume-down (interop/adjust-volume! player -5)
        nil))
    (catch Exception e
      (log/error e "audio command error"))))

(defn- audio-loop [exit-ch emitter commands init-state]
  (async/go-loop [player init-state]
    (async/alt!
      exit-ch ([_]
               (interop/release-player! player)
               (async/close! exit-ch)
               nil)
      commands ([ev]
                (when-let [event (command-handler player emitter ev)]
                  (async/>! emitter event))
                (recur player)))))

(defn- init-audio! [{:keys [bus]}]
  (let [emitter (async/chan)
        commands (async/chan)
        exit-ch (async/chan)
        init-state (interop/init-player)]
    (ev/listen bus "/player/commands" commands)
    (ev/emitize bus emitter)
    (audio-loop exit-ch emitter commands init-state)
    {:emitter emitter
     :commands commands
     :exit-ch exit-ch
     :state init-state}))

(defn- halt-player! [{:keys [exit-ch commands emitter]}]
  (async/put! exit-ch true)
  (async/close! commands)
  (async/close! emitter))

(defmethod ig/init-key ::player [_ opts]
  (log/info "\n-=[starting audio]=-")
  (init-audio! opts))

(defmethod ig/halt-key! ::player [_ opts]
  (log/info "\n-=[goodbye audio]=-")
  (halt-player! opts))
