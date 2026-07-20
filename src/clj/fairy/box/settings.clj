;; Copyright © 2025 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2
(ns fairy.box.settings
  (:require
   [clojure.core.async :as async]
   [donut.system :as ds]
   [jp.nijohando.event :as ev]))

(def SettingsComponent
  {::ds/start  (fn [{config ::ds/config}]
                 (:opts config))
   ::ds/config {:opts (ds/ref [:config
                               :fairy.box/components
                               :fairy.box/settings])}})

(defn startup! [{:keys [bus delay-ms]}]
  (let [emitter (async/chan)]
    (when (pos? delay-ms)
      (Thread/sleep (long delay-ms)))
    (ev/emitize bus emitter)
    (async/put! emitter {:path  "/system"
                         :value {:event :system/initialized}})
    {:emitter emitter}))

(defn stop! [{:keys [emitter]}]
  (async/close! emitter))

(def StartupComponent
  {::ds/start  (fn [{config ::ds/config}]
                 (startup! config))
   ::ds/stop   (fn [{instance ::ds/instance}]
                 (stop! instance))
   ::ds/config {:delay-ms    (ds/ref [:config
                                      :fairy.box/components
                                      :fairy.box/startup
                                      :delay-ms])
                :bus         (ds/ref [:fairy.box/components
                                      :fairy.box.bus/bus])
                :buttons     (ds/ref [:fairy.box/components
                                      :fairy.box.hardware/buttons])
                :leds        (ds/ref [:fairy.box/components
                                      :fairy.box.hardware/leds])
                :mqtt        (ds/ref [:fairy.box/components
                                      :fairy.box.mqtt/client])
                :player      (ds/ref [:fairy.box/components
                                      :fairy.box.audio.system2/player])
                :rfid        (ds/ref [:fairy.box/components
                                      :fairy.box.hardware/rfid])
                :server      (ds/ref [:fairy.box/components
                                      :fairy.box.web/server])
                :settings    (ds/ref [:fairy.box/components
                                      :fairy.box/settings])
                :switchboard (ds/ref [:fairy.box/components
                                      :fairy.box.switchboard/switchboard])
                :tts         (ds/ref [:fairy.box/components
                                      :fairy.box.tts/tts])}})
(defn settings [{:fairy.box/keys [component]}]
  (component :fairy.box/settings))
