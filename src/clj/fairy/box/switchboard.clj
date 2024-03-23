(ns fairy.box.switchboard
  (:require
   [clojure.java.io :as io]
   [fairy.box.audio.browse :as browse]
   [fairy.box.db :as db]
   [medley.core :as m]
   [jp.nijohando.event :as ev]
   [clojure.core.async :as async]
   [clojure.tools.logging :as log]
   [integrant.core :as ig]))

(def ^:private init-state {:system-state :system-state/booting})
(defonce ^:private state (atom init-state))

(defn system-state! []
  (:system-state @state))

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
  (when (= :system-state/ready (system-state!))
    (let [{:keys [button-id action]} value]
      (condp = action
        :button/single-press (when-let [ev (button-press-event button-id)]
                               (async/put! emitter ev))
        nil))))

(defn emit-system! [emitter event]
  (async/put! emitter {:path "/system" :value event}))

(defn emit-player! [emitter event]
  (async/put! emitter {:path "/player/commands" :value event}))

(defn emit-led! [emitter event]
  (async/put! emitter {:path "/hardware/output/leds" :value event}))

(defn sfx-path [settings key]
  (let [path (-> settings :sfx key)]
    (str (browse/media-dir settings) "/" path)))

(defn rfid-handler [{:keys [db-conn emitter settings]} {:keys [value] :as ev}]
  (when (= :system-state/ready (system-state!))
    (condp = (:action value)
      :placed (do (prn value)
                  (prn (db/linked-folder @db-conn (:uid value)))
                  (emit-led! emitter {:action :led/pulse :names [:audio/play-pause] :after-set 1.0 :repeat-times 3})
                  (when-let [rel-folder-path (db/linked-folder @db-conn (:uid value))]
                    ;; rfid tags are linked with relative paths so the audio folder can be moved without breaking links

                    (async/put! emitter (doto  {:path "/player/commands"
                                                :value {:action :audio/play-path
                                                        :item-path (browse/absoluteify settings rel-folder-path)
                                                        :uid (:uid value)}} prn))))
      :removed (async/put! emitter {:path "/player/commands"
                                    :value {:action :audio/stop}})
      :error (do
               (log/error "RFID error" (:error value))
               (emit-led! emitter {:action :led/pulse :names [:audio/play-pause :audio/prev :audio/next :audio/volume-up :audio/volume-down] :after-set 0.0 :repeat-times 9})))))

(defn initiate-shutdown! [emitter]
  (emit-system! emitter {:event :system/cooling-down}))

(defn system-handler [{:keys [emitter settings]} {:keys [value] :as ev}]
  ;; (tap> {:system ev})
  (let [{:keys [event]} value]
    (condp = event
      :system/initialized (do
                            (swap! state assoc :system-state :system-state/initialized)
                            (emit-system! emitter {:event :system/warming-up}))
      :system/warming-up (do
                           (swap! state assoc :system-state :system-state/warming-up)
                           (emit-led! emitter {:action :led/set :groups [:all] :value  1.0})
                           (emit-player! emitter {:action :audio/play-one-shot :id :startup-sound
                                                  :item-path (sfx-path settings :startup)}))
      :system/warmed-up (do
                          (swap! state assoc :system-state :system-state/ready)
                          (emit-system! emitter {:event :system/ready}))
      :system/cooling-down (do
                             (swap! state assoc :system-state :system-state/cooling-down)
                             (emit-player! emitter {:action :audio/stop})
                             (emit-player! emitter {:action :audio/play-one-shot :id :shutdown-sound
                                                    :item-path (sfx-path settings :shutdown)}))
      :system/shutdown (do
                         (swap! state assoc :system-state :system-state/shutdown)
                         ;; todo perform shutdown
                         )
      nil)))

(defn player-handler [{:keys [emitter]} {:keys [value] :as ev}]
  (when (#{:system-state/warming-up :system-state/cooling-down} (system-state!))
    ;; (prn "player-handler " ev)
    (when (= :player/one-shot-finished (:event value))
      (condp = (:id value)
        :startup-sound  (emit-system! emitter {:event :system/warmed-up})
        :shutdown-sound (emit-system! emitter {:event :system/shutdown})))))

(def ^:private patch-ports {:rfid  {:handler #'rfid-handler
                                    :name :rfid
                                    :path "/hardware/input/rfid"}
                            :buttons {:handler #'button-handler
                                      :name :buttons
                                      :path "/hardware/input/buttons"}
                            :system {:handler #'system-handler
                                     :name :system
                                     :path "/system"}
                            :player {:handler #'player-handler
                                     :name :player
                                     :path "/player/events"}})

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
          (let [{:keys [handler name]} (channels channel)]
            (when event
              (try
                (handler (assoc  opts :emitter emitter) event)
                (catch Exception e
                  (log/error e "Switchboard event handler error" {:event event :handler name}))))
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
  (reset! state init-state)
  (init-switchboard! opts))

(defmethod ig/halt-key! ::switchboard [_ opts]
  (log/info "\n-=[goodbye switchboard]=-")
  (halt-switchboard! opts))
