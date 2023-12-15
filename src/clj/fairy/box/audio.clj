(ns fairy.box.audio
  "Public interface for audio subsystem"
  (:require
   [fairy.box.audio.system :as audio]))

(defn current-play-request! []
  (audio/current-play-request!))

(defn current-track! []
  (audio/current-track!))
