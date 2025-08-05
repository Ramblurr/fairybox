;; Copyright © 2025 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2
(ns fairy.box.mqtt
  (:require
   [fairy.box.util :as util]
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

(def mqtt-init-state {:connected? false
                      :client nil
                      :subscriber-state nil
                      :publisher-state nil})
(defonce ^:private mqtt-state (atom mqtt-init-state))

(defn- mqtt-connected?! [] (:connected? @mqtt-state))

(defn command-handler! [emit! {:keys [action] :as payload}]
  (when (mqtt-connected?!)
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
      "audio/set-repeat" (emit! {:action :audio/set-repeat :mode (get {"off" :none "all" :list "one" :track} (:mode payload))})
      (do
        (tap> {:mqtt-unhandled-cmd payload})
        nil))))

(def public-events #{:player/muted
                     :player/volume-changed
                     :player/media-changed
                     :player/state-changed
                     :player/time-changed})

(defn events-handler! [{:keys [client topic]} {event :value}]
  (when (mqtt-connected?!)
    (when (public-events (:event event))
      (mh/publish client topic (->json event))  event)))

(defn start-publish-loop! [opts listener]
  (async/go-loop []
    (when-some [event (async/<! listener)]
      (events-handler! opts event)
      (recur))))

(defn init-publisher! [{:keys [bus settings] :as opts}]
  (let [{:keys [fairybox-id]} settings
        _ (assert fairybox-id)
        listener (async/chan (async/sliding-buffer 512))
        topic (format "fairybox/%s/state" fairybox-id)]
    (ev/listen bus "/player/events" listener)
    (start-publish-loop! (assoc opts :topic topic) listener)
    {:listener listener}))

(defn halt-publisher! [{:keys [listener] :as opts}]
  (when listener
    (log/info "mqtt halt-subscriber!")
    (async/close! listener)
    nil))

(defn emit! [emitter event]
  (async/put! emitter {:path "/player/commands" :value event}))

(defn init-subscriber! [{:keys [client bus settings qos]}]
  (let [emitter (async/chan (async/sliding-buffer 512))
        topic (format "fairybox/%s/command" (:fairybox-id settings))]
    (ev/emitize bus emitter)
    (mh/subscribe client {topic qos}
                  (fn [^String topic _ ^bytes payload]
                    (let [emit! (partial emit! emitter)]
                      (command-handler! emit! (<-json (String. payload "UTF-8"))))))
    {:emitter emitter
     :client client
     :topic topic}))

(defn halt-subscriber! [{:keys [emitter topic client] :as opts}]
  (when opts
    (try
      (log/info "mqtt halt-subscriber!")
      (async/close! emitter)
      (mh/unsubscribe client topic)
      nil
      (catch Exception e
        (log/error e "halting mqtt subscriber error")))))

(defn- close-client! [client]
  (try
    (when client
      (mh/disconnect-and-close client))
    (catch Exception _)))

(declare try-connect!)
(defn mqtt-connect! [{:keys [uri mqtt-opts] :as opts}]
  (mh/connect uri {:opts mqtt-opts
                   :on-connect-complete (fn [client _ _]
                                          (log/info "mqtt connection complete")
                                          (let [opts (assoc opts :client client)]
                                            (swap! mqtt-state (fn [state]
                                                                (-> state
                                                                    (assoc :client client)
                                                                    (assoc :connected? true)
                                                                    (assoc :subscriber-state (init-subscriber! opts))
                                                                    (assoc :publisher-state (init-publisher! opts)))))))

                   :on-connection-lost (fn [reason]
                                         (log/warn reason "mqtt connection lost")
                                         (swap! mqtt-state (fn [state]
                                                             (-> state
                                                                 (assoc :connected? false)
                                                                 (assoc :subscriber-state (halt-subscriber! (:subscriber-state state)))
                                                                 (assoc :publisher-state (halt-publisher! (:publisher-state state))))))
                                         (try-connect! opts))}))

(defn try-connect!
  "Attempts repeated retried to re-establish connection."
  [{:keys [exit-ch] :as opts}]
  (log/info "Attempting to establish mqtt connection")
  (async/go-loop [retries 1]
    (let [retry-timeout (min (* retries 1000) 60000) ;; timeout maxes at 60s
          conn (try (mqtt-connect! opts)
                    (catch Exception e e))]
      (when (util/exception? conn)
        (log/info "Unable to establish mqtt connection, retrying in " retry-timeout "ms. "
                  "Reported exception: " (ex-message conn))
        (when (= :timeout (async/alt!
                            (async/timeout retry-timeout) :timeout
                            exit-ch :exit))
          (recur (inc retries)))))))

(defn init-client! [opts]
  (reset! mqtt-state mqtt-init-state)

  (let [exit-ch (async/chan)
        opts (-> opts
                 (assoc :uri "tcp://home.int.socozy.casa:1883")
                 (assoc-in [:settings :fairybox-id] "fairybox1")
                 (assoc-in [:mqtt-opts :username] "phoniebox")
                 (assoc-in [:mqtt-opts :password] "frostbite-enlarging-pretext-stingily-gosling-task-osmosis-flirt"))]

    (prn opts)
    (if (:uri opts)
      (do
        (let [fairybox-id (get-in opts [:settings :fairybox-id])]
          (assert (and (some? fairybox-id) (not= "" fairybox-id)) "fairybox-id must be set!"))
        (try-connect! (assoc opts :exit-ch exit-ch))
        {:exit-ch exit-ch})
      (log/info "mqtt not connecting uri is nil"))))

(defn halt-client! [{:keys [exit-ch]}]
  (when exit-ch
    (async/put! exit-ch :exit)
    (halt-subscriber! (:subscriber-state @mqtt-state))
    (halt-publisher! (:publisher-state @mqtt-state))
    (close-client! (:client @mqtt-state)))
  (reset! mqtt-state mqtt-init-state))

(defmethod ig/init-key ::client [_ opts]
  (log/info "\n-=[starting mqtt client]=-")
  (init-client! opts))

(defmethod ig/halt-key! ::client [_ opts]
  (log/info "\n-=[goodbye mqtt client]=-")
  (halt-client! opts))
