;; Copyright © 2025 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2
(ns fairy.box.web.controllers.health
  (:require
   [fairy.box.switchboard :as switchboard]
   [fairy.box.util :as util]
   [hyperlith.impl.router :as router])
  (:import
   [java.util Date]))

(defn ready?
  [_req]
  (let [system-state (switchboard/system-state!)]
    {:status  (if (= system-state :system-state/ready)
                200
                503)
     :headers {"Content-Type" "application/json; charset=utf-8"}
     :body    (util/->json {:system-state (name system-state)})}))

(defn leds-on!
  [{:fairy.box/keys [component]}]
  (let [switchboard (component :fairy.box.switchboard/switchboard)]
    (assert (and switchboard (:emitter switchboard)) "Switchboard emitter is required")
    (switchboard/emit-led! (:emitter switchboard)
                           {:action :led/set
                            :groups [:all]
                            :value  1.0})
    {:status 204 :headers {} :body ""}))

(defn healthcheck!
  [_req]
  {:status  200
   :headers {}
   :body    {:time     (str (Date. (System/currentTimeMillis)))
             :up-since (str (Date. (.getStartTime (java.lang.management.ManagementFactory/getRuntimeMXBean))))
             :app      {:status  (switchboard/system-state!)
                        :message ""}}})

(router/add-route! [:get "/api/ready"] #'ready?)
(router/add-route! [:get "/api/leds-on"] #'leds-on!)