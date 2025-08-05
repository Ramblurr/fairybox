;; Copyright © 2025 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2
(ns fairy.box.web.routes.api
  (:require
   [clojure.core.async :as async]
   [fairy.box.switchboard :as switchboard]
   [fairy.box.web.controllers.artwork :as artwork]
   [fairy.box.web.controllers.health :as health]
   [fairy.box.web.middleware.exception :as exception]
   [fairy.box.web.middleware.formats :as formats]
   [fairy.box.web.views.home :as home]
   [integrant.core :as ig]
   [jp.nijohando.event :as ev]
   [reitit.coercion.malli :as malli]
   [reitit.ring.coercion :as coercion]
   [reitit.ring.middleware.muuntaja :as muuntaja]
   [reitit.ring.middleware.parameters :as parameters]
   [ring.util.http-response :as http-response]))

(def route-data
  {:coercion   malli/coercion
   :muuntaja   formats/instance
   #_#_:swagger    {:id ::api}
   :middleware [;; query-params & form-params
                parameters/parameters-middleware
                  ;; content-negotiation
                muuntaja/format-negotiate-middleware
                  ;; encoding response body
                muuntaja/format-response-middleware
                  ;; exception handling
                coercion/coerce-exceptions-middleware
                  ;; decoding request body
                muuntaja/format-request-middleware
                  ;; coercing response bodys
                coercion/coerce-response-middleware
                  ;; coercing request parameters
                coercion/coerce-request-middleware
                  ;; exception handling
                exception/wrap-exception]})

;; Routes
(defn api-routes [{:keys [emitter] :as opts}]
  [#_["/swagger.json"
      {:get {:no-doc  true
             :swagger {:info {:title "fairy.box API"}}
             :handler (swagger/create-swagger-handler)}}]
   ["/health"
    {:get #'health/healthcheck!}]

   ["/ready"
    {:get #'health/ready?}]

   ["/current-artwork"
    (fn [request]
      (artwork/current-artwork request))]

   ["/shutdown"
    {:post (fn [request]
             (switchboard/initiate-shutdown! emitter)
             (http-response/ok {}))}]

   ["/leds-on"
    {:get (fn [request]
            (switchboard/emit-led! emitter {:action :led/set :groups [:all] :value  1.0})
            (http-response/ok {}))}]

   ["/ws" (fn [request]
            {:status 500}
            #_{:undertow/websocket
               {:on-open (fn [{:keys [channel]}]
                           (home/new-ws-client channel))
                :on-message (fn [ev]
                              (home/ws-handler opts ev))
                :on-close-message (fn [{:keys [channel message]}]
                                    (home/remove-ws-client channel message))}})]])

(derive :reitit.routes/api :reitit/routes)

(defmethod ig/init-key :reitit.routes/api
  [_ {:keys [base-path]
      :or   {base-path ""}
      :as   opts}]
  [base-path route-data (api-routes opts)])

(defmethod ig/init-key :reitit.routes/bus-emitter [_ {:keys [bus] :as opts}]
  (let [emitter (async/chan)]
    (ev/emitize bus emitter)
    emitter))

(defmethod ig/halt-key! :reitit.routes/bus-emitter [_ emitter]
  (when emitter
    (async/close! emitter)))
