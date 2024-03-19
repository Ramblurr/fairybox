(ns fairy.box.core
  (:import
   [com.diozero.util Diozero])
  (:require
   [clojure.tools.logging :as log]
   [integrant.core :as ig]
   [fairy.box.config :as config]
   [fairy.box.env :refer [defaults]]
   [signal.handler :as signal]

   ;; Edges
   [kit.edge.utils.nrepl]
   [kit.edge.server.undertow]
   [fairy.box.web.handler]

   ;; Routes
   [fairy.box.web.routes.api]
   [fairy.box.web.routes.ui]
   [fairy.box.db]
   [fairy.box.bus]
   [fairy.box.switchboard]
   [fairy.box.audio.system]
   [fairy.box.hardware]
   [fairy.box.settings]
   [fairy.box.mqtt])
  (:gen-class))

;; log uncaught exceptions in threads
(Thread/setDefaultUncaughtExceptionHandler
 (reify Thread$UncaughtExceptionHandler
   (uncaughtException [_ thread ex]
     (log/error {:what :uncaught-exception
                 :exception ex
                 :where (str "Uncaught exception on" (.getName thread))}))))

(defonce system (atom nil))

(defn stop-app! []
  ((or (:stop defaults) (fn [])))
  (some-> (deref system) (ig/halt!))
  (shutdown-agents))

(defn start-app [& [params]]
  ((or (:start params) (:start defaults) (fn [])))
  (->> (config/system-config (or (:opts params) (:opts defaults) {}))
       (ig/prep)
       (ig/init)
       (reset! system))
  (Diozero/initialiseShutdownHook)
  (.addShutdownHook (Runtime/getRuntime) (Thread. stop-app!)))

(defn -main [& _]
  (start-app))

(signal/with-handler :term
  (log/info "caught SIGTERM, quitting")
  (stop-app!)
  (log/info "all components shut down"))
