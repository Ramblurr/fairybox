(ns fairy.box.system
  (:require
   [hifi.web.middleware :as hifi.mw]
   [hifi.web :as web]
   [hifi.html :as html]
   [hifi.core :as h]
   [fairy.box.tts :as tts]
   [fairy.box.audio.system2 :as audio]
   [fairy.box.switchboard :as switchboard]
   [fairy.box.bus :as bus]
   [fairy.box.db :as db]
   [fairy.box.settings :as settings]
   [fairy.box.web.routes.api :as api]
   [fairy.box.web.routes.ui2 :as ui]
   [clojure.tools.logging :as log]
   #_[nrepl.cmdline :as nrepl]
   ;; [hifi.config :as config]
   ;; [hifi.datastar :as datastar]
   ;; [hifi.datastar.http-kit :as d*http-kit]
   ;; [hifi.engine.shell :as shell]
   ;; [hifi.util.assets :as assets]
   ))

#_(h/defroutes routes
    ["" (routes-ui/routes)
     ["/api" routes-api/route-data
      (routes-api/routes)]])

#_(defonce system (atom nil))

#_(defn start []
    (let [new-system (hifi/start {:routes              #'routes
                                  :port                3000
                                  :debug-errors?       true
                                  :reload-per-request? true}
                                 (FairyboxSystemDef))]

      (reset! system new-system)
      (log/info "Fairy box started Visit http://localhost:3000")
      new-system))

(h/defplugin app
  "My application"
  {:fairy.box/components {:fairy.box/settings                settings/SettingsComponent
                          :fairy.box.db/db                   db/DbComponent
                          :fairy.box.bus/bus                 bus/BusComponent
                          :fairy.box.switchboard/switchboard switchboard/SwitchboardComponent
                          :fairy.box.audio.system2/player    audio/AudioSystemComponent
                          :fairy.box.bus/http-bus-emitter    api/HttpBusEmitterComponent
                          :fairy.box.tts/tts                 tts/TTSComponent
                          :fairy.box.web/sse-broadcaster     ui/SSEBroadcastComponent
                          :fairy.box.hardware/enabled        nil
                          :fairy.box.hardware/rfid           nil
                          :fairy.box.hardware/buttons        nil
                          :fairy.box.hardware/leds           nil
                          :fairy.box.mqtt/client             nil
                          :fairy.box/startup                 settings/StartupComponent}

   :hifi/routes     (web/route-group ui/routes api/routes)
   :hifi/middleware {:fairy.box/middleware (hifi.mw/middleware-component
                                            {:name    :fairy.box/middleware
                                             :factory (fn [{:keys [db-conn http-bus-emitter settings]}]
                                                        (fn [handler]
                                                          (fn extra-mw [req]
                                                            (handler (assoc req
                                                                            :fairy.box/http-bus-emitter http-bus-emitter
                                                                            :fairy.box/settings settings
                                                                            :fairy.box/db-conn db-conn)))))
                                             :donut.system/config
                                             {:http-bus-emitter [:donut.system/ref [:fairy.box/components :fairy.box.bus/http-bus-emitter]]
                                              :db-conn          [:donut.system/ref [:fairy.box/components :fairy.box.db/db]]
                                              :settings         [:donut.system/ref [:fairy.box/components :fairy.box/settings]]}})}})
