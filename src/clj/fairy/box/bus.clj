;; Copyright © 2025 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2
(ns fairy.box.bus
  (:require
   [clojure.tools.logging :as log]
   [jp.nijohando.event :as ev]))

(defn init-bus! [_]
  (let [bus (ev/bus)]
    bus))

(def BusComponent
  {:donut.system/start (fn [{config :donut.system/config}]
                         (init-bus! (:opts config)))
   :donut.system/stop (fn [{:donut.system/keys [instance]}]
                        (ev/close! instance))
   :donut.system/config {:opts [:donut.system/ref [:env :fairy.box/components :fairy.box.bus/bus]]}})
