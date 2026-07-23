(ns fairy.box2.system
  (:require
   [fairy.box2.rfid.mfrc522 :as mfrc522]
   [fairy.box2.db :as db]
   [aero.core :as aero]
   [clojure.java.io :as io]
   [donut.system :as ds]))

(defn default-profile []
  (cond
    (System/getenv "FAIRYBOX_PROFILE") (keyword (System/getenv "FAIRYBOX_PROFILE"))
    (System/getenv "NOT_A_RPI")        :dev-no-rpi
    :else                              :prod))

(defn config-source []
  (or (io/resource "env.edn")
      (throw (ex-info "env.edn config on classpath was not found" {}))))

(defn read-config
  ([]
   (read-config (default-profile)))
  ([profile]
   (assoc (aero/read-config (config-source) {:profile profile})
          :profile profile)))

(def base-system
  {::ds/defs {:config               {}
              :fairy.box/components {:fairy.box.hardware/rfid ds/required-component
                                     :fairy.box.db/db         db/DbComponent}}})
(defmethod ds/named-system ::prod
  [_]
  (ds/system base-system
             {[:config] (read-config :prod)
              [:fairy.box/components ::rfid-reader] (mfrc522/reader)}))

#_(defn component [component-key]
    (when-let [running-system @app_]
      (ds/instance running-system [:fairy.box/components component-key])))

(def app_ (atom nil))
(defn -main [& _]
  (try
    (let [running-system (ds/start ::prod)]
      (reset! app_ running-system))
    (catch Throwable error
      (ds/stop-failed-system error)
      (throw error))))

(comment
  (read-config :prod)
  (read-config)

  :rcf)
