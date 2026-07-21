;; Copyright © 2025 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2
(ns fairy.box.web.api
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [fairy.box.audio.current :as current]
   [fairy.box.switchboard :as switchboard]
   [fairy.box.util :as util]
   [hyperlith.impl.router :as router])
  (:import
   [java.net MalformedURLException URL URLDecoder]
   [java.nio.file Paths]
   [java.util Date]))

(def ART_DIR
  (str (Paths/get (System/getProperty "user.home")
                  (into-array String [".cache/vlc/art"]))))

(defn determine-extension [path]
  (let [possible-extensions [".png" ".jpg" ".jpeg" ".gif" ".PNG" ".JPG" ".JPEG" ".GIF"]]
    (some->> possible-extensions
             (map (fn [ext] (str path ext)))
             (map io/file)
             (filter #(.exists ^java.io.File %))
             first
             str)))

(defn artwork-attachment-to-path [{:meta/keys [album artist]}]
  (if (or (str/blank? artist) (str/blank? album))
    ;; If artist or album are missing, it was cached by title MD5 hash
    nil
    (determine-extension
     (str (Paths/get ART_DIR
                     (into-array String ["artistalbum" artist album "art"]))))))

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
  (let [{:meta/keys [artwork-url] :as meta}
        (:meta (current/display-track (current/current!)))]
    (when artwork-url
      (cond
        (str/starts-with? artwork-url "attachment://") (artwork-attachment-to-path meta)
        (str/starts-with? artwork-url "file://") (artwork-file-url-to-path artwork-url)
        :else (do (log/error "Unhandled VLC artwork path type" {:url artwork-url})
                  nil)))))

(defn img-response [input-stream img-type]
  {:status  200
   :headers {"Content-Type"  (str "image/" img-type)
             "Cache-Control" "no-cache, no-store, must-revalidate"
             "Pragma"        "no-cache"
             "Expires"       "0"}
   :body    input-stream})

(defn default-artwork
  "Returns a resource pointing to the default artwork image in the classpath."
  []
  [(io/resource "public/img/jukebox.png") "png"])

(defn actual-artwork
  "Returns [file img-type] if the artwork exists, otherwise nil."
  []
  (when-let [^String image-path (get-current-artwork-path!)]
    (let [^java.io.File img-file (io/file image-path)
          img-type               (str/lower-case
                                  (subs image-path (inc (.lastIndexOf image-path "."))))]
      (when (.exists img-file)
        [img-file img-type]))))

(defn current-artwork [_req]
  (let [[img-file img-type] (or (actual-artwork) (default-artwork))]
    (img-response (io/input-stream img-file) img-type)))

(defn ready?
  [_req]
  (let [system-state (switchboard/system-state!)]
    {:status  (if (= system-state :system-state/ready)
                200
                503)
     :headers {"Content-Type" "application/json; charset=utf-8"}
     :body    (util/->json {:system-state (name system-state)})}))

(defn leds-on!
  [{:fairy.box/keys [component]}]
  (let [switchboard (component :fairy.box.switchboard/switchboard)]
    (assert (and switchboard (:emitter switchboard)) "Switchboard emitter is required")
    (switchboard/emit-led! (:emitter switchboard)
                           {:action :led/set
                            :groups [:all]
                            :value  1.0})
    {:status 204 :headers {} :body ""}))

(defn healthcheck!
  [_req]
  {:status  200
   :headers {}
   :body    {:time     (str (Date. (System/currentTimeMillis)))
             :up-since (str (Date. (.getStartTime (java.lang.management.ManagementFactory/getRuntimeMXBean))))
             :app      {:status  (switchboard/system-state!)
                        :message ""}}})

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

(router/add-route! [:get "/api/current-artwork"] #'current-artwork)
(router/add-route! [:get "/api/leds-on"] #'leds-on!)
(router/add-route! [:get "/api/ready"] #'ready?)
(router/add-route! [:post "/api/shutdown"] #'shutdown!)
