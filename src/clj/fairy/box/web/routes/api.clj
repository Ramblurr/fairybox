;; Copyright © 2025 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2
(ns fairy.box.web.routes.api
  (:require
   [hifi.core :as h]
   [clojure.core.async :as async]
   [fairy.box.switchboard :as switchboard]
   [fairy.box.web.controllers.artwork :as artwork]
   [fairy.box.web.controllers.health :as health]
   [fairy.box.web.middleware.exception :as exception]
   [fairy.box.web.middleware.formats :as formats]
   [jp.nijohando.event :as ev]
   [reitit.coercion.malli :as malli]
   [reitit.ring.coercion :as coercion]
   [reitit.ring.middleware.muuntaja :as muuntaja]
   [reitit.ring.middleware.parameters :as parameters]))

(h/defroutes routes
  ["/api" {}
   {:coercion   malli/coercion
    :muuntaja   formats/instance
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
                 exception/wrap-exception]}
   ["/health" {:get #'health/healthcheck!}]
   ["/ready" {:get #'health/ready?}]
   ["/current-artwork" {:get (fn [request] (artwork/current-artwork request))}]
   ["/shutdown" {:post (fn [{:fairy.box/keys [http-bus-emitter]}]
                         (switchboard/initiate-shutdown! http-bus-emitter)
                         {:status  204
                          :headers {}})}]
   ["/leds-on" {:get (fn [{:fairy.box/keys [http-bus-emitter]}]
                       (switchboard/emit-led! http-bus-emitter {:action :led/set :groups [:all] :value 1.0})
                       {:status  204
                        :headers {}})}]

   #_["/ws" (fn [request]
              {:undertow/websocket
               {:on-open          (fn [{:keys [channel]}]
                                    (home/new-ws-client channel))
                :on-message       (fn [ev]
                                    (home/ws-handler opts ev))
                :on-close-message (fn [{:keys [channel message]}]
                                    (home/remove-ws-client channel message))}})]])
(def HttpBusEmitterComponent
  {:donut.system/start (fn [{config :donut.system/config}]
                         (let [emitter (async/chan)]
                           (ev/emitize (:bus config) emitter)
                           emitter))
   :donut.system/stop (fn [{:donut.system/keys [instance]}]
                        (async/close! instance))
   :donut.system/config {:bus         [:donut.system/ref [:fairy.box/components :fairy.box.bus/bus]]}})
