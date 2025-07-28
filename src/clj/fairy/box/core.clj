(ns fairy.box.core
  (:import
   [com.diozero.util Diozero])
  (:require
   [clojure.core.async :as async]
   [clojure.tools.logging :as log]
   [integrant.core :as ig]
   [fairy.box.config :as config]
   [fairy.box.env :refer [defaults]]
   [signal.handler :as signal]
   [jp.nijohando.event :as ev]

   ;; Edges
   [kit.edge.utils.nrepl]
   [kit.edge.server.undertow]
   [fairy.box.web.handler]

   ;; System components
   [fairy.box.web.routes.api]
   [fairy.box.web.routes.ui]
   [fairy.box.db]
   [fairy.box.bus]
   [fairy.box.switchboard :as switchboard]
   [fairy.box.audio.system]
   [fairy.box.hardware]
   [fairy.box.settings]
   [fairy.box.mqtt]
   [fairy.box.tts])
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

(defn shutdown-loop [{:keys [value] :as event}]
  (if (= :system/shutdown (:event value))
    (do
      (try
        (log/info "system shutdown ready")
        (stop-app!)
        (Thread/sleep 5000)
        (finally
          (System/exit 0)))
      nil)
    :recur))

(defn initiate-shutdown! [emitter bus]
  (let [listener (async/chan)]
    (prn "starting shutdown")
    (ev/listen bus "/system" listener)
    (async/go-loop []
      (if (shutdown-loop (async/<! listener))
        (recur)
        (prn "shutdown-loop done")))
    (switchboard/initiate-shutdown! emitter)))

(defn -main [& _]
  (start-app))

(signal/with-handler :term
  (try
    (log/info "caught SIGTERM, quitting")
    (initiate-shutdown!
     (:emitter (:fairy.box.audio.system/player @system))
     (:fairy.box.bus/bus @system))
    #_(stop-app!)
    #_(log/info "all components shut down")
    (catch Throwable t
      (log/error t "Error during shutdown")
      (println t))))
