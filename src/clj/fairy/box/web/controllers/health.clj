(ns fairy.box.web.controllers.health
  (:require
   [fairy.box.switchboard :as switchboard]
   [ring.util.http-response :as http-response])
  (:import
   [java.util Date]))

(defn ready?
  [req]
  (let [system-state (switchboard/system-state!)
        body {:system-state (name system-state)}]
    (if (= system-state  :system-state/ready)
      (http-response/ok body)
      (http-response/service-unavailable body))))

(defn healthcheck!
  [req]
  (http-response/ok
   {:time     (str (Date. (System/currentTimeMillis)))
    :up-since (str (Date. (.getStartTime (java.lang.management.ManagementFactory/getRuntimeMXBean))))
    :app      {:status (switchboard/system-state!)
               :message ""}}))
