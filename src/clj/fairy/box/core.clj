;; Copyright © 2025 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2
(ns fairy.box.core
  (:require
   [clojure.tools.logging :as log]
   [fairy.box.system :as system]
   [hifi.system :as hifi]
   [hifi.util.shutdown :as shutdown]
   [nrepl.cmdline :as nrepl])
  (:import
   [com.diozero.util Diozero])
  (:gen-class))

;; log uncaught exceptions in threads
(Thread/setDefaultUncaughtExceptionHandler
 (reify Thread$UncaughtExceptionHandler
   (uncaughtException [_ thread ex]
     (log/error {:what :uncaught-exception
                 :exception ex
                 :where (str "Uncaught exception on" (.getName thread))}))))

(defn stop-jvm []
  (hifi/stop @system/system)
  (Diozero/shutdown)
  (shutdown-agents))

(defn -main []
  (let [system                  (system/start)
        {:keys [enabled? args]} (get-in system [:donut.system/instances :config :hifi/repl])]
    (shutdown/add-shutdown-hook! ::stop stop-jvm)
    (when enabled?
      (if args
        (apply nrepl/-main args)
        (log/info "No nREPL args provided under :hifi/repl, starting without nREPL.")))))
