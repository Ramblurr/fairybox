;; Copyright © 2025 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2
(ns fairy.box.config
  (:require
    [kit.config :as config]))

(def ^:const system-filename "system.edn")

(defn system-config
  [options]
  (config/read-config system-filename options))