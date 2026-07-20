;; Copyright © 2025 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2
(ns fairy.box.web.middleware.core
  (:require
   [fairy.box.env :as env]
   [ring.middleware.defaults :as defaults]
   [ring.middleware.session.cookie :as cookie]))

(defn wrap-base
  [{:keys [site-defaults-config cookie-secret] _metrics :metrics :as opts}]
  (let [cookie-store (cookie/cookie-store {:key (.getBytes ^String cookie-secret)})]
    (fn [handler]
      (cond-> ((:middleware env/defaults) handler opts)
        true (defaults/wrap-defaults
              (assoc-in site-defaults-config [:session :store] cookie-store))))))

(def wrap-settings
  {:name        ::config
   :description "Middleware for injecting settings into request"
   :compile     (fn [{:keys [settings] :as _route-data} _route-opts]
                  (fn [handler]
                    (fn [request]
                      (assert settings "Settings not defined in route data")
                      (handler (assoc request :settings settings)))))})
