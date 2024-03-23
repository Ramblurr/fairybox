(ns fairy.box.web.routes.api
  (:import [java.nio.file Paths]
           [java.net URL URLDecoder MalformedURLException])
  (:require
   [clojure.tools.logging :as log]
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

(def ART_DIR (str (Paths/get (System/getProperty "user.home") (into-array String [".cache/vlc/art"]))))

(defn determine-extension [path]
  (let [possible-extensions [".png" ".jpg" ".jpeg" ".gif" ".PNG" ".JPG" ".JPEG" ".GIF"]]
    (some->> possible-extensions
             (map (fn [ext] (str path ext)))
             (map io/file)
             (filter #(.exists %))
             first
             str)))

(defn artwork-attachment-to-path [{:keys [album album-artist artist]}]
  (if (or (str/blank? artist) (str/blank? album))
    ;; If artist or album are missing, it was cached by title MD5 hash
    nil
    (determine-extension (str (Paths/get ART_DIR (into-array String ["artistalbum" artist album "art"]))))))

(defn artwork-file-url-to-path [url]
  (try
    (->
     (URL. url)
     (.getPath)
     (URLDecoder/decode "UTF-8"))
    (catch MalformedURLException e
      (tap> {:invalid-artwork-url url :error e})
      nil)))

(defn get-current-artwork-path! []
  (let [{:keys [artwork-url] :as current-track} (audio/current-track!)]
    (when artwork-url
      (cond
        (str/starts-with? artwork-url "attachment://") (artwork-attachment-to-path current-track)
        (str/starts-with? artwork-url "file://") (artwork-file-url-to-path artwork-url)
        :else (do (log/error "Unhandled VLC artwork path type" {:url artwork-url})
                  nil)))))

(defn img-response [input-stream img-type]
  {:status  200
   :headers {"Content-Type" (str "image/" img-type)
             "Cache-Control" "no-cache, no-store, must-revalidate"
             "Pragma" "no-cache"
             "Expires" "0"}
   :body    input-stream})

(defn default-artwork
  "Returns a resource pointing to the default artwork image in the classpath."
  []
  [(io/resource "public/img/jukebox.png") "png"])

(defn actual-artwork
  "Returns [file img-type] if the artwork exists, otherwise nil."
  []
  (when-let [image-path (get-current-artwork-path!)]
    (let [img-file (io/file image-path)
          img-type (str/lower-case (subs image-path (inc (.lastIndexOf image-path "."))))]
      (when (.exists img-file)
        [img-file img-type]))))

(defn current-artwork [req]
  (let [[img-file img-type] (or (actual-artwork) (default-artwork))]
    (img-response (io/input-stream img-file) img-type)))

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
