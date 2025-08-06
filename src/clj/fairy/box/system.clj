(ns fairy.box.system
  (:require
   [fairy.box.tts :as tts]
   [fairy.box.audio.system2 :as audio]
   [fairy.box.switchboard :as switchboard]
   [fairy.box.bus :as bus]
   [fairy.box.db :as db]
   [fairy.box.settings :as settings]
   [fairy.box.web.routes.api :as routes-api]
   [fairy.box.web.routes.ui2 :as routes-ui]
   [clojure.tools.logging :as log]
   #_[nrepl.cmdline :as nrepl]
   ;; [hifi.config :as config]
   ;; [hifi.datastar :as datastar]
   ;; [hifi.datastar.http-kit :as d*http-kit]
   ;; [hifi.engine.shell :as shell]
   ;; [hifi.html :as html]
   [hifi.system :as hifi]
   [hifi.system.middleware :as hifi.mw]
   ;; [hifi.util.assets :as assets]
   ))

(defn FairyboxSystemDef []
  {:fairy.box/components {:fairy.box/settings                settings/SettingsComponent
                          :fairy.box.db/db                   db/DbComponent
                          :fairy.box.bus/bus                 bus/BusComponent
                          :fairy.box.switchboard/switchboard switchboard/SwitchboardComponent
                          :fairy.box.audio.system2/player    audio/AudioSystemComponent
                          :fairy.box.bus/http-bus-emitter    routes-api/HttpBusEmitterComponent
                          :fairy.box.tts/tts                 tts/TTSComponent
                          :fairy.box.web/sse-broadcaster     routes-ui/SSEBroadcastComponent
                          :fairy.box.hardware/enabled        nil
                          :fairy.box.hardware/rfid           nil
                          :fairy.box.hardware/buttons        nil
                          :fairy.box.hardware/leds           nil
                          :fairy.box.mqtt/client             nil
                          :fairy.box/startup                 settings/StartupComponent}
   :hifi/middleware      {:fairy.box/middleware (hifi.mw/middleware-component
                                                 {:name    :fairy.box/middleware
                                                  :factory (fn [{:keys [db-conn http-bus-emitter]}]
                                                             (fn [handler]
                                                               (fn extra-mw [req]
                                                                 (handler (assoc req
                                                                                 :fairy.box/http-bus-emitter http-bus-emitter
                                                                                 :fairy.box/db-conn db-conn)))))
                                                  :donut.system/config
                                                  {:http-bus-emitter [:donut.system/ref [:fairy.box/components :fairy.box.bus/http-bus-emitter]]
                                                   :db-conn          [:donut.system/ref [:fairy.box/components :fairy.box.db/db]]}})}})

(defn routes []
  [""
   (routes-ui/routes)
   ["/api" routes-api/route-data
    (routes-api/routes)]])

(defonce system (atom nil))

(defn start []
  (let [new-system (hifi/start {:routes              #'routes
                                :port                3000
                                :debug-errors?       true
                                :reload-per-request? true}
                               (FairyboxSystemDef))]

    (reset! system new-system)
    (log/info "Fairy box started Visit http://localhost:3000")
    new-system))
