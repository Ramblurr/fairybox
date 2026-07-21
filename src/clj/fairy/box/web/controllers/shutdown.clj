;; Copyright © 2025 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2
(ns fairy.box.web.controllers.shutdown
  (:require
   [fairy.box.switchboard :as switchboard]
   [fairy.box.util :as util]
   [hyperlith.impl.router :as router]))

(defn shutdown!
  [{:fairy.box/keys [component]}]
  (let [emitter (when (ifn? component)
                  (some-> (component :fairy.box.switchboard/switchboard)
                          :emitter))]
    (if (and emitter
             (switchboard/emit-system! emitter {:event :system/poweroff}))
      {:status 202 :headers {} :body ""}
      {:status  503
       :headers {"Content-Type" "application/json; charset=utf-8"}
       :body    (util/->json {:error "Switchboard unavailable"})})))

(router/add-route! [:post "/api/shutdown"] #'shutdown!)
