;; Copyright © 2025 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2
(ns fairy.box.web.controllers.artwork
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [fairy.box.audio :as audio])
  (:import
   (java.net MalformedURLException URL URLDecoder)
   (java.nio.file Paths)))

(def ART_DIR (str (Paths/get (System/getProperty "user.home") (into-array String [".cache/vlc/art"]))))

(defn determine-extension [path]
  (let [possible-extensions [".png" ".jpg" ".jpeg" ".gif" ".PNG" ".JPG" ".JPEG" ".GIF"]]
    (some->> possible-extensions
             (map (fn [ext] (str path ext)))
             (map io/file)
             (filter #(.exists %))
             first
             str)))

(defn artwork-attachment-to-path [{:meta/keys [album album-artist artist]}]
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
  (let [{:meta/keys [artwork-url] :as meta} (:meta (audio/current-track!))]
    (when artwork-url
      (cond
        (str/starts-with? artwork-url "attachment://") (artwork-attachment-to-path meta)
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
