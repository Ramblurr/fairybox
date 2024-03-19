(ns fairy.box.web.routes.utils)

(def route-data-path [:reitit.core/match :data])

(defn route-data
  [req]
  (get-in req route-data-path))

(defn route-data-key
  [req k]
  (get-in req (conj route-data-path k)))

(defn req-db [req]
  @(-> (route-data req) :db-conn))

(defn req-settings [req]
  (-> (route-data req) :settings))

(defn req-db-conn [req]
  (-> (route-data req) :db-conn))
