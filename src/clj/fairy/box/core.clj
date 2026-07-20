;; Copyright © 2025 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2
(ns fairy.box.core
  (:require
   [clojure.tools.logging :as log]
   [fairy.box.system :as system])
  (:gen-class))

(Thread/setDefaultUncaughtExceptionHandler
 (reify Thread$UncaughtExceptionHandler
   (uncaughtException [_ thread error]
     (log/error {:what :uncaught-exception
                 :exception error
                 :where (str "Uncaught exception on " (.getName thread))}))))

(defn stop-jvm! []
  (try
    (system/stop!)
    (finally
      (shutdown-agents))))

(defonce ^:private shutdown-hook
  (Thread.
   ^Runnable
   (reify Runnable
     (run [_]
       (stop-jvm!)))
   "fairybox-shutdown"))

(defonce ^:private shutdown-hook-installed? (atom false))

(defn- install-shutdown-hook! []
  (when (compare-and-set! shutdown-hook-installed? false true)
    (try
      (.addShutdownHook (Runtime/getRuntime) shutdown-hook)
      (catch Throwable error
        (reset! shutdown-hook-installed? false)
        (throw error)))))

(defn -main [& _]
  (install-shutdown-hook!)
  (system/-main {:profile :prod}))
