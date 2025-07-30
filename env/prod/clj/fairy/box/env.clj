;; Copyright © 2025 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2
(ns fairy.box.env
  (:require [clojure.tools.logging :as log]))

(def defaults
  {:init       (fn []
                 (log/info "\n-=[box starting]=-"))
   :start      (fn []
                 (log/info "\n-=[box started successfully]=-"))
   :stop       (fn []
                 (log/info "\n-=[box has shut down successfully]=-"))
   :middleware (fn [handler _] handler)
   :opts       {:profile :prod}})