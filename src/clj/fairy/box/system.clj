(ns fairy.box.system
  (:require
   [aero.core :as aero]
   [clojure.java.io :as io]
   [donut.system :as ds]
   [fairy.box.audio.system2 :as audio]
   [fairy.box.bus :as bus]
   [fairy.box.css :as css]
   [fairy.box.db :as db]
   [fairy.box.hardware.buttons :as buttons]
   [fairy.box.hardware.led :as led]
   [fairy.box.hardware.rfid :as rfid]
   [fairy.box.mqtt :as mqtt]
   [fairy.box.nrepl :as nrepl]
   [fairy.box.playback-limits :as playback-limits]
   [fairy.box.settings :as settings]
   [fairy.box.switchboard :as switchboard]
   [fairy.box.timers :as timers]
   [fairy.box.tts :as tts]
   [fairy.box.web.refresh :as web-refresh]
   [fairy.box.web.views :as views]
   [hyperlith.core :as h]))

(def config-resource "env.edn")

(defn default-profile []
  (cond
    (System/getenv "FAIRYBOX_PROFILE")
    (keyword (System/getenv "FAIRYBOX_PROFILE"))

    (System/getenv "NOT_A_RPI")
    :dev-no-rpi

    :else
    :prod))

(defn- config-source []
  (or (io/resource config-resource)
      (throw (ex-info "Application configuration was not found"
                      {:resource config-resource}))))

(defn read-config
  ([]
   (read-config (default-profile)))
  ([profile]
   (aero/read-config (config-source) {:profile profile})))

(defn- web-context [components]
  {:fairy.box/component components
   :url-for             views/url-for})

(defn start-web-server! [{:keys [components port]}]
  (when-not (css/precompiled?)
    (css/start))
  (h/start-app
   {:port      port
    :ctx-start #(web-context components)
    :ctx-stop  (constantly nil)
    :on-error  #(tap> %)}))

(defn stop-web-server! [{:keys [stop]}]
  (when stop
    (stop)))

(defn- component-ref [component-key]
  (ds/ref [:fairy.box/components component-key]))

(def WebServerComponent
  {::ds/start  (fn [{config ::ds/config}]
                 (start-web-server! config))
   ::ds/stop   (fn [{instance ::ds/instance}]
                 (stop-web-server! instance))
   ::ds/config {:port       (ds/ref [:config :fairy.box/components :fairy.box.web/server :port])
                :components {:fairy.box/settings                (component-ref :fairy.box/settings)
                             :fairy.box.db/db                   (component-ref :fairy.box.db/db)
                             :fairy.box.bus/bus                 (component-ref :fairy.box.bus/bus)
                             :fairy.box.switchboard/switchboard (component-ref :fairy.box.switchboard/switchboard)
                             :fairy.box.audio.system2/player    (component-ref :fairy.box.audio.system2/player)
                             :fairy.box.auto-shutdown/timer     (component-ref :fairy.box.auto-shutdown/timer)
                             :fairy.box.sleep/timer             (component-ref :fairy.box.sleep/timer)
                             :fairy.box.tts/tts                 (component-ref :fairy.box.tts/tts)
                             :fairy.box.web/refresh             (component-ref :fairy.box.web/refresh)
                             :fairy.box.hardware/rfid           (component-ref :fairy.box.hardware/rfid)
                             :fairy.box.hardware/buttons        (component-ref :fairy.box.hardware/buttons)
                             :fairy.box.hardware/leds           (component-ref :fairy.box.hardware/leds)
                             :fairy.box.mqtt/client             (component-ref :fairy.box.mqtt/client)}}})

(defn system
  ([]
   (system {}))
  ([{:keys [config profile]
     :or   {profile (default-profile)}}]
   {::ds/defs {:config               (or config (read-config profile))
               :fairy.box/components {:fairy.box/settings                settings/SettingsComponent
                                      :fairy.box.db/db                   db/DbComponent
                                      :fairy.box.playback-limits/policy  playback-limits/PlaybackLimitsComponent
                                      :fairy.box.bus/bus                 bus/BusComponent
                                      :fairy.box.switchboard/switchboard switchboard/SwitchboardComponent
                                      :fairy.box.audio.system2/player    audio/AudioSystemComponent
                                      :fairy.box.auto-shutdown/timer     timers/AutoShutdownTimerComponent
                                      :fairy.box.sleep/timer             timers/SleepTimerComponent
                                      :fairy.box.tts/tts                 tts/TTSComponent
                                      :fairy.box.web/refresh             web-refresh/RefreshComponent
                                      :fairy.box.hardware/rfid           rfid/RfidComponent
                                      :fairy.box.hardware/buttons        buttons/ButtonsComponent
                                      :fairy.box.hardware/leds           led/LedsComponent
                                      :fairy.box.mqtt/client             mqtt/MqttComponent
                                      :fairy.box.nrepl/server            nrepl/NreplComponent
                                      :fairy.box.web/server              WebServerComponent
                                      :fairy.box/startup                 settings/StartupComponent}}}))

(defonce app_ (atom nil))

(defn component [component-key]
  (when-let [running-system @app_]
    (ds/instance running-system
                 [:fairy.box/components component-key])))

(defn start!
  ([]
   (start! {}))
  ([opts]
   (locking app_
     (or @app_
         (try
           (let [running-system (ds/start (system opts))]
             (reset! app_ running-system))
           (catch Throwable error
             (ds/stop-failed-system error)
             (throw error)))))))

(defn stop! []
  (locking app_
    (when-let [running-system @app_]
      (try
        (ds/stop running-system)
        (finally
          (reset! app_ nil))))))

(defn restart!
  ([]
   (restart! {}))
  ([opts]
   (stop!)
   (start! opts)))

(defn -main [& [opts]]
  (start! (if (map? opts) opts {})))

(h/refresh-all!)

(comment
  (start! {:profile :dev-no-rpi})
  (component :fairy.box.hardware/rfid)
  (restart! {:profile :dev-no-rpi})
  (stop!)
  (read-config :prod)
  :rcf)
