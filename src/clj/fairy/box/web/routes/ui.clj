(ns fairy.box.web.routes.ui
  (:require
   [fairy.box.util :refer [throttle]]
   [clojure.core.async :as async]
   [jp.nijohando.event :as ev]
   [fairy.box.web.middleware.exception :as exception]
   [fairy.box.web.middleware.formats :as formats]
   [fairy.box.web.views.home :as home]
   [integrant.core :as ig]
   [reitit.ring.middleware.muuntaja :as muuntaja]
   [reitit.ring.middleware.parameters :as parameters]
   [clojure.tools.logging :as log]))

(defn route-data [opts]
  (merge
   opts
   {:muuntaja   formats/instance
    :middleware
    [;; Default middleware for ui
    ;; query-params & form-params
     parameters/parameters-middleware
      ;; encoding response body
     muuntaja/format-response-middleware
      ;; exception handling
     exception/wrap-exception]}))

(derive :reitit.routes/ui :reitit/routes)

(defmethod ig/init-key :reitit.routes/ui
  [_ {:keys [base-path]
      :or   {base-path ""}
      :as   opts}]
  [base-path (route-data opts) (home/ui-routes base-path)])

(defn ws-events-handler! [{:keys [db-conn position-ch time-ch]} {:keys [path value]}]
  (try
    (condp = path
      "/player/events" (do (when-not (contains? #{:player/time-changed :player/position-changed}  (:event value))
                             (tap> {(:event value) value}))
                           (condp = (:event value)
                             :player/position-changed (async/put! position-ch value)
                             :player/time-changed (async/put! time-ch value)
                             (home/broadcast-player-event! value)))

      "/hardware/input/rfid"
      (let [{:keys [uid action at]} value]
        (home/broadcast-rfid-change! @db-conn uid action)))
    (catch Exception e
      (log/error e "ws-events-handler error"))))

(defn start-throttled-forwarder! [ch]
  (async/go-loop []
    (when-some [event (async/<! ch)]
      (try
        (home/broadcast-player-event! event)
        (catch Exception e
          (log/error e "broadcast-player-event error")))
      (recur))))

(defn start-main-loop! [opts listener]
  (async/go-loop []
    (when-some [event (async/<! listener)]
      (ws-events-handler! opts event)
      (recur))))

(defn init-ws-events! [{:keys [bus] :as opts}]
  (let [listener (async/chan)
        emitter (async/chan)
        time-ch (async/chan (async/sliding-buffer 1))
        position-ch (async/chan (async/sliding-buffer 1))
        throttled-time (throttle time-ch 500)
        throttled-position (throttle position-ch 1000)]
    (ev/emitize bus emitter)
    (ev/listen bus "/hardware/input/rfid" listener)
    (ev/listen bus "/player/events" listener)
    (home/init-ws!)
    (start-throttled-forwarder! throttled-time)
    (start-throttled-forwarder! throttled-position)
    (start-main-loop! (-> opts
                          (assoc :emitter emitter)
                          (assoc :time-ch time-ch)
                          (assoc :position-ch position-ch))
                      listener)

    {:listener listener
     :position position-ch
     :emitter emitter
     :time time-ch
     :throttled-position throttled-position
     :throttled-time throttled-time}))

(defmethod ig/init-key ::ws-events [_ opts]
  (init-ws-events! opts))

(defmethod ig/halt-key! ::ws-events [_ {:keys [listener position time throttled-position throttled-time emitter]}]
  (async/close! emitter)
  (async/close! listener)
  (async/close! position)
  (async/close! time)
  (async/close! throttled-position)
  (async/close! throttled-time))
