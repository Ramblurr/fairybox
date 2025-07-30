;; Copyright © 2025 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2
(ns fairy.box.test-utils
  #_(:require
     [fairy.box.core :as core]
     [integrant.repl.state :as state]))

#_(defn system-state
    []
    (or @core/system state/system))

#_(defn system-fixture
    []
    (fn [f]
      (when (nil? (system-state))
        (core/start-app {:opts {:profile :test}}))
      (f)
      (core/stop-app)))