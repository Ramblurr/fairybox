;; Copyright © 2025 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2
(ns fairy.box.settings
  (:require
   [clojure.core.async :as async]
   [integrant.core :as ig]
   [jp.nijohando.event :as ev]))

(defmethod ig/init-key :fairy.box/settings [_ opts]
  opts)

(def SettingsComponent
  {:donut.system/start (fn [{config :donut.system/config}] (:opts config))
   :donut.system/config {:opts [:donut.system/ref [:config :fairy.box/components :fairy.box/settings]]}})

(defn startup! [{:keys [bus]}]
  (let [emitter (async/chan)]
    (Thread/sleep 1000)
    (ev/emitize bus emitter)
    (async/put! emitter {:path "/system" :value {:event :system/initialized}})
    {:emitter emitter}))

(defn stop! [instance]
  (async/close! (:emitter instance)))

(defmethod ig/halt-key! :fairy.box/startup [_ {:keys [emitter]}]
  (async/close! emitter))

(def StartupComponent
  {:donut.system/start (fn [{config :donut.system/config}]
                         (startup! config))
   :donut.system/stop (fn [{:donut.system/keys [instance]}]
                        (stop! instance))
   :donut.system/config {:config         [:donut.system/ref [:config]]
                         ;; :leds        [:donut.system/ref [:fairy.box/components :fairy.box.hardware/leds]]
                         :bus         [:donut.system/ref [:fairy.box/components :fairy.box.bus/bus]]
                         ;; :rfid        [:donut.system/ref [:fairy.box/components :fairy.box.hardware/rfid]]
                         :player      [:donut.system/ref [:fairy.box/components :fairy.box.audio.system2/player]]
                         :settings    [:donut.system/ref [:fairy.box/components :fairy.box/settings]]
                         :switchboard [:donut.system/ref [:fairy.box/components :fairy.box.switchboard/switchboard]]
                         :tts         [:donut.system/ref [:fairy.box/components :fairy.box.tts/tts]]
                         ;; :http        :server/http
                         }})
(defn settings [{:fairy.box/keys [component]}]
  (component :fairy.box/settings))
