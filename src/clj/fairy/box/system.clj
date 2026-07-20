(ns fairy.box.system
  (:require ;; [fairy.box.web.routes.api :as api]
 ;; [fairy.box.web.routes.ui2 :as ui]
   [aero.core :as aero]
   [donut.system :as ds]
   [fairy.box.audio.system2 :as audio]
   [fairy.box.bus :as bus]
   [fairy.box.css :as css]
   [fairy.box.db :as db]
   [fairy.box.hardware.rfid :as rfid]
   [fairy.box.settings :as settings]
   [fairy.box.switchboard :as switchboard]
   [fairy.box.tts :as tts]
   [fairy.box.web.rfid :as web-rfid]
   [fairy.box.web.views :as views]
   [hyperlith.core :as h] ;; [fairy.box.web.routes.api :as api]
   ))

(defn system []
  {::ds/defs
   {:config (aero/read-config "config/env.edn")
    :fairy.box/components {:fairy.box/settings                settings/SettingsComponent
                           :fairy.box.db/db                   db/DbComponent
                           :fairy.box.bus/bus                 bus/BusComponent
                           :fairy.box.switchboard/switchboard switchboard/SwitchboardComponent
                           :fairy.box.audio.system2/player    audio/AudioSystemComponent
                          ;; :fairy.box.bus/http-bus-emitter    api/HttpBusEmitterComponent
                           :fairy.box.tts/tts                 tts/TTSComponent
                           :fairy.box.web/rfid-presence         web-rfid/RfidPresenceComponent
                          ;; :fairy.box.web/sse-broadcaster     ui/SSEBroadcastComponent
                           :fairy.box.hardware/enabled        nil
                           :fairy.box.hardware/rfid           rfid/RfidComponent
                           :fairy.box.hardware/buttons        nil
                           :fairy.box.hardware/leds           nil
                           :fairy.box.mqtt/client             nil
                           ;; :fairy.box/startup                 settings/StartupComponent
                           }}})
(comment
  (aero/read-config "config/env.edn")
  ;
  )
(defn- component-lookup-fn [running-system]
  (fn [component-key]
    (ds/instance running-system
                 [:fairy.box/components component-key])))

(defn ctx-start []
  (css/start)
  (let [running-system (ds/start (system))]
    (assoc running-system
           :url-for views/url-for
           :fairy.box/component (component-lookup-fn running-system))))

(defn ctx-stop [running-system]
  (ds/signal running-system ::ds/stop))

(defonce app_ (atom nil))

(defn -main [& _]
  (reset! app_
          (h/start-app
           {:ctx-start ctx-start
            :ctx-stop  ctx-stop
            :on-error #(tap> %)})))

;; Refresh app when you re-eval file
(h/refresh-all!)

(comment
  (do
    (when @app_
      ((@app_ :stop)))
    (do (-main) nil)) ;; rcf

;; stop server
  ((@app_ :stop))

  (keys @app_)
  (-> @app_ :ctx)

  (require '[clojure.repl.deps])
  (clojure.repl.deps/sync-deps)

  ;;
  )
