;; Copyright © 2025 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2
(ns fairy.box.web.controllers.health
  (:require
   [cheshire.core :as cheshire]
   [fairy.box.switchboard :as switchboard]
   [hyperlith.impl.router :as router]
   [ring.util.http-response :as http-response])
  (:import
   [java.util Date]))

(defn ready?
  [_req]
  (let [system-state (switchboard/system-state!)
        body         {:system-state (name system-state)}
        response     ((if (= system-state :system-state/ready)
                        http-response/ok
                        http-response/service-unavailable)
                      (cheshire/generate-string body))]
    (http-response/content-type response
                                "application/json; charset=utf-8")))

(defn leds-on!
  [{:fairy.box/keys [component]}]
  (let [switchboard (component :fairy.box.switchboard/switchboard)]
    (assert (and switchboard (:emitter switchboard)) "Switchboard emitter is required")
    (switchboard/emit-led! (:emitter switchboard)
                           {:action :led/set
                            :groups [:all]
                            :value  1.0})
    (http-response/no-content)))

(defn healthcheck!
  [_req]
  (http-response/ok
   {:time     (str (Date. (System/currentTimeMillis)))
    :up-since (str (Date. (.getStartTime (java.lang.management.ManagementFactory/getRuntimeMXBean))))
    :app      {:status  (switchboard/system-state!)
               :message ""}}))

(router/add-route! [:get "/api/ready"] #'ready?)
(router/add-route! [:get "/api/leds-on"] #'leds-on!)
