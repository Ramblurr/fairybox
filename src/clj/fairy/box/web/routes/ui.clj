(ns fairy.box.web.routes.ui
  (:require

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

(defn ws-events-handler! [{:keys [db-conn]} {:keys [path value]}]
  (try
    (condp = path
      "/player/events"
      (prn "GOT PLAYER EVENT" value)
      "/hardware/input/rfid"
      (let [{:keys [uid action at]} value]
        (home/broadcast-rfid-change! @db-conn uid action)))
    (catch Exception e
      (log/error e "ws-events-handler error"))))

(defn init-ws-events! [{:keys [bus] :as opts}]
  (let [listener (async/chan)]
    (ev/listen bus "/hardware/input/rfid" listener)
    (ev/listen bus "/player/events" listener)
    (home/init-ws!)
    (async/go-loop []
      (when-some [event (async/<! listener)]
        (ws-events-handler! opts event)
        (recur)))
    {:listener listener}))

(defmethod ig/init-key ::ws-events [_ opts]
  (init-ws-events! opts))

(defmethod ig/halt-key! ::ws-events [_ {:keys [listener]}]
  (async/close! listener))
