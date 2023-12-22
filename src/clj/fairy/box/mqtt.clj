(ns fairy.box.mqtt
  (:require
   [clojure.core.async :as async]
   [jp.nijohando.event :as ev]

   [clojure.tools.logging :as log]
   [integrant.core :as ig]
   [cheshire.core :as cheshire]
   [clojurewerkz.machine-head.client :as mh]))
(def ->json cheshire/generate-string)
(def <-json #(cheshire/parse-string % true))

(comment

  (do
    (def conn (mh/connect "tcp://10.9.4.3:1883"))) ;; rcf

  (mh/subscribe conn {"homeassistant/binary_sensor/garden/state" 0} (fn [^String topic _ ^bytes payload]
                                                                      (tap> (String. payload "UTF-8"))))
  (mh/subscribe conn {"fairybox/test/command" 0} (fn [^String topic _ ^bytes payload]
                                                   (tap>
                                                    (<-json
                                                     (String. payload "UTF-8")))))

  (mh/publish conn "fairybox1/title" "Hello World")

  (mh/publish conn "fairybox/discovery/test" (->json {:fairybox-discovery true
                                                      :fairybox-id "test"
                                                      :fairybox-name "TestBox"
                                                      :fairybox-command-topic "fairybox/test/command"
                                                      :fairybox-state-topic "fairybox/test/state"}))
  (mh/publish conn "fairybox/test/state" (->json {:state "playing"
                                                  :now-playing {:title "Bear Roll"
                                                                :artist "ABBA"
                                                                :album "Bear Things"
                                                                :track-number 1
                                                                :duration-seconds 60
                                                                :playlist-name "All About Bears"}}))

  (mh/disconnect conn)

  ;;
  )
(defn command-handler! [emit! {:keys [action] :as payload}]
  (condp = action
    "audio/play-pause" (emit! {:action :audio/play-pause})
    "audio/play" (emit! {:action :audio/play})
    "audio/pause" (emit! {:action :audio/pause})
    "audio/stop" (emit! {:action :audio/stop})
    "audio/next" (emit! {:action :audio/next})
    "audio/prev" (emit! {:action :audio/prev})
    "audio/set-volume" (emit! {:action :audio/set-volume :volume (:volume payload)})
    "audio/set-mute" (emit! {:action :audio/set-mute :muted? (:muted payload)})
    "audio/set-time" (emit! {:action :audio/set-time :milliseconds (:milliseconds payload)})
    "audio/set-repeat" (emit! {:action :audio/set-repeat :mode (get {"off" :default "all" :repeat "one" :loop} (:mode payload))})
    (do
      (tap> {:mqtt-unhandled-cmd payload})
      nil)))

(def public-events #{:player/muted
                     :player/volume-changed
                     :player/media-changed
                     :player/state-changed
                     :player/time-changed})

(defn events-handler! [{:keys [client topic]} {event :value}]
  (when (public-events (:event event))
    (mh/publish client topic (->json event))  event))

(defn start-publish-loop! [opts listener]
  (async/go-loop []
    (when-some [event (async/<! listener)]
      (events-handler! opts event)
      (recur))))

(defn init-publisher! [{:keys [bus settings] :as opts}]
  (let [listener (async/chan)
        topic (format "fairybox/%s/state" (:fairybox-id settings))]
    (ev/listen bus "/player/events" listener)
    (start-publish-loop! (assoc opts :topic topic) listener)
    {:listener listener}))

(defn halt-publisher! [{:keys [listener]}]
  (async/close! listener))

(defmethod ig/init-key ::publisher [_ opts]
  (log/info "\n-=[starting mqtt publisher]=-")
  (init-publisher! opts))

(defmethod ig/halt-key! ::publisher [_ opts]
  (log/info "\n-=[goodbye mqtt publisher]=-")
  (halt-publisher! opts))

(defn emit! [emitter event]
  (async/put! emitter {:path "/player/commands" :value event}))

(defn init-subscriber! [{:keys [client bus settings qos]}]
  (let [emitter (async/chan)
        topic (format "fairybox/%s/command" (:fairybox-id settings))]
    (ev/emitize bus emitter)
    (mh/subscribe client {topic qos}
                  (fn [^String topic _ ^bytes payload]
                    (let [emit! (partial emit! emitter)]
                      (command-handler! emit! (<-json (String. payload "UTF-8"))))))
    {:emitter emitter
     :client client
     :topic topic}))

(defn halt-subscriber! [{:keys [emitter topic client]}]
  (async/close! emitter)
  (mh/unsubscribe client topic))

(defmethod ig/init-key ::subscriber [_ opts]
  (log/info "\n-=[starting mqtt subscriber]=-")
  (init-subscriber! opts))

(defmethod ig/halt-key! ::subscriber [_ opts]
  (log/info "\n-=[goodbye mqtt subscriber]=-")
  (halt-subscriber! opts))

(defn init-client! [{:keys [uri opts]}]
  (mh/connect uri opts))

(defn halt-client! [conn]
  (try
    (when conn
      (mh/disconnect conn))
    (catch Exception e
      (log/error e "halting mqtt connection error"))))

(defmethod ig/init-key ::client [_ opts]
  (log/info "\n-=[starting mqtt client]=-")
  (init-client! opts))

(defmethod ig/halt-key! ::client [_ opts]
  (log/info "\n-=[goodbye mqtt client]=-")
  (halt-client! opts))
