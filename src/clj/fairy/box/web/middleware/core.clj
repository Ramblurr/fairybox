(ns fairy.box.web.middleware.core
  (:require
   [fairy.box.env :as env]
   [ring.middleware.defaults :as defaults]
   [ring.middleware.session.cookie :as cookie]))

(defn wrap-base
  [{:keys [metrics site-defaults-config cookie-secret] :as opts}]
  (let [cookie-store (cookie/cookie-store {:key (.getBytes ^String cookie-secret)})]
    (fn [handler]
      (cond-> ((:middleware env/defaults) handler opts)
        true (defaults/wrap-defaults
              (assoc-in site-defaults-config [:session :store] cookie-store))))))

(def wrap-settings
  {:name ::env
   :description "Middleware for injecting settings into request"
   :compile (fn [{:keys [settings] :as _route-data} _route-opts]
              (fn [handler]
                (fn [request]
                  (handler (assoc request :settings settings)))))})
