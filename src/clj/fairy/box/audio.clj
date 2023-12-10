(ns fairy.box.audio
  (:require
   [fairy.box.audio.interop :as interop]
   [clojure.tools.logging :as log]
   [integrant.core :as ig]))

(defn init-audio [opts]
  (log/info "\n-=[starting audio]=-")
  {:player (interop/init-player)})

(defn halt-player! [{:keys [player]}]
  (interop/release-player! player))

(defmethod ig/init-key ::player [_ opts]
  (init-audio opts))

(defmethod ig/halt-key! ::player [_ opts]
  (halt-player! opts))
