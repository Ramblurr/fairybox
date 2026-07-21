;; Copyright © 2025 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2
(ns fairy.box.env
  (:require
   [clojure.tools.logging :as log]))

(def defaults
  {:init  (fn []
            (log/info "\n-=[box starting using the development or test profile]=-"))
   :start (fn []
            (log/info "\n-=[box started successfully using the development or test profile]=-"))
   :stop  (fn []
            (log/info "\n-=[box has shut down successfully]=-"))
   :opts  {:profile       (if (System/getenv "NOT_A_RPI")
                            :dev-no-rpi
                            :dev)
           :persist-data? true}})
