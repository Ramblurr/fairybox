(ns fairy.box.switchboard
  (:require
   [fairy.box.audio.browse :as browse]
   [fairy.box.db :as db]
   [medley.core :as m]
   [jp.nijohando.event :as ev]
   [clojure.core.async :as async]
   [clojure.tools.logging :as log]
   [integrant.core :as ig]))

(defn rfid-handler [{:keys [db-conn emitter]} {:keys [value] :as ev}]
  (if (= (:action value) :placed)
    (when-let [rel-folder-path (db/linked-folder @db-conn (:uid value))]
      ;; rfid tags are linked with relative paths so the audio folder can be moved without breaking links

      (async/put! emitter {:path "/player/commands"
                           :value {:action :audio/play-path
                                   :item-path (browse/absoluteify rel-folder-path)
                                   :uid (:uid value)}}))
    (async/put! emitter {:path "/player/commands"
                         :value {:action :audio/stop}})))

(def button-press-event {:audio/play-pause {:path "/player/commands"
                                            :value {:action :audio/play-pause}}
                         :audio/next {:path "/player/commands"
                                      :value {:action :audio/next}}
                         :audio/prev {:path "/player/commands"
                                      :value {:action :audio/prev}}
                         :audio/volume-up {:path  "/player/commands"
                                           :value {:action :audio/volume-up}}
                         :audio/volume-down {:path  "/player/commands"
                                             :value {:action :audio/volume-down}}})

(defn button-handler [{:keys [emitter]} {:keys [value] :as ev}]
  (tap> {:button ev})
  (let [{:keys [button-id action]} value]
    (condp = action
      :button/single-press (when-let [ev (button-press-event button-id)]
                             (async/put! emitter ev))
      nil)))

(def ^:private patch-ports {:rfid  {:handler rfid-handler
                                    :name :rfid
                                    :path "/hardware/input/rfid"}
                            :buttons {:handler button-handler
                                      :name :buttons
                                      :path "/hardware/input/buttons"}})

(defn init-switchboard! [{:keys [bus] :as opts}]
  (let [channels  (m/map-keys (fn [_] (async/chan)) patch-ports)
        exit-ch (async/chan)
        emitter (async/chan)]
    (ev/emitize bus emitter)
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
              (handler (assoc  opts :emitter emitter) event))
            (recur)))))
    {:channels channels
     :emitter emitter
     :exit-ch exit-ch}))

(defn halt-switchboard! [{:keys [channels exit-ch emitter]}]
  (async/put! exit-ch true)
  (async/close! emitter)
  (doseq [channel (keys channels)] (async/close! channel)))

(defmethod ig/init-key ::switchboard [_ opts]
  (log/info "\n-=[starting switchboard]=-")
  (init-switchboard! opts))

(defmethod ig/halt-key! ::switchboard [_ opts]
  (log/info "\n-=[goodbye switchboard]=-")
  (halt-switchboard! opts))
