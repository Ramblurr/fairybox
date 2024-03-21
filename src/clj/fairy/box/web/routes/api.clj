(ns fairy.box.web.routes.api
  (:import [java.io FileInputStream File]
           [java.net URL URLDecoder])
  (:require
   [clojure.java.io :as io]
   [clojure.core.async :as async]
   [jp.nijohando.event :as ev]
   [fairy.box.audio :as audio]
   [fairy.box.web.views.home :as home]
   [fairy.box.web.controllers.health :as health]
   [fairy.box.web.middleware.exception :as exception]
   [fairy.box.web.middleware.formats :as formats]
   [integrant.core :as ig]
   [reitit.coercion.malli :as malli]
   [reitit.ring.coercion :as coercion]
   [reitit.ring.middleware.muuntaja :as muuntaja]
   [reitit.ring.middleware.parameters :as parameters]
   [reitit.swagger :as swagger]
   [clojure.string :as str]))

(def route-data
  {:coercion   malli/coercion
   :muuntaja   formats/instance
   :swagger    {:id ::api}
   :middleware [;; query-params & form-params
                parameters/parameters-middleware
                  ;; content-negotiation
                muuntaja/format-negotiate-middleware
                  ;; encoding response body
                muuntaja/format-response-middleware
                  ;; exception handling
                coercion/coerce-exceptions-middleware
                  ;; decoding request body
                muuntaja/format-request-middleware
                  ;; coercing response bodys
                coercion/coerce-response-middleware
                  ;; coercing request parameters
                coercion/coerce-request-middleware
                  ;; exception handling
                exception/wrap-exception]})

(defn get-current-artwork-path! []
  (when-let [url (:artwork-url (audio/current-track!))]
    (->
     (URL. url)
     (.getPath)
     (URLDecoder/decode "UTF-8"))))

(defn img-response [img-file img-type]
  {:status  200
   :headers {"Content-Type" (str "image/" img-type)
             "Cache-Control" "no-cache, no-store, must-revalidate"
             "Pragma" "no-cache"
             "Expires" "0"}
   :body    (FileInputStream. img-file)})

(defn default-artwork [req]
  (img-response (io/file (io/resource "public/img/jukebox.png"))  "png"))

(defn current-artwork [req]
  (if-let [image-path (get-current-artwork-path!)]
    (let [img-file (File. image-path)
          img-type (str/lower-case (subs image-path (inc (.lastIndexOf image-path "."))))]
      (if (.exists img-file)
        (img-response img-file img-type)
        (default-artwork req)))
    (default-artwork req)))

;; Routes
(defn api-routes [{:keys [emitter] :as opts}]
  [["/swagger.json"
    {:get {:no-doc  true
           :swagger {:info {:title "fairy.box API"}}
           :handler (swagger/create-swagger-handler)}}]
   ["/health"
    {:get health/healthcheck!}]

   ["/current-artwork"
    (fn [request]
      (current-artwork request))]

   ["/ws" (fn [request]
            {:undertow/websocket
             {:on-open (fn [{:keys [channel]}]
                         (home/new-ws-client channel))
              :on-message (fn [ev]
                            (home/ws-handler opts ev))
              :on-close-message (fn [{:keys [channel message]}]
                                  (home/remove-ws-client channel message))}})]])

(derive :reitit.routes/api :reitit/routes)

(defmethod ig/init-key :reitit.routes/api
  [_ {:keys [base-path]
      :or   {base-path ""}
      :as   opts}]
  [base-path route-data (api-routes opts)])

(defmethod ig/init-key :reitit.routes/bus-emitter [_ {:keys [bus] :as opts}]
  (let [emitter (async/chan)]
    (ev/emitize bus emitter)
    emitter))

(defmethod ig/halt-key! :reitit.routes/bus-emitter [_ emitter]
  (when emitter
    (async/close! emitter)))
