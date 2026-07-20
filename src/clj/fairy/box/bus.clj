;; Copyright © 2025 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2
(ns fairy.box.bus
  (:require
   [donut.system :as ds]
   [jp.nijohando.event :as ev]))

(def BusComponent
  {::ds/start  (fn [_]
                 (ev/bus))
   ::ds/stop   (fn [{instance ::ds/instance}]
                 (ev/close! instance))
   ::ds/config {:opts (ds/ref [:config
                               :fairy.box/components
                               :fairy.box.bus/bus])}})
