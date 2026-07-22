;; Copyright © 2025 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2
(ns fairy.box.audio.browse
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [fairy.box.util.natural-sorting :as natsort])
  (:import
   [java.nio.file Paths]))

(defn media-dir [settings]
  (assert settings "settings not defined")
  (assert (-> settings :media :media-dir) "media-dir not defined")
  (-> settings :media :media-dir fs/path fs/canonicalize str))

(defn validate-base-path
  "Returns true when canonical `full-path` is contained by canonical `base-path`."
  [base-path full-path]
  (fs/starts-with? (fs/canonicalize (fs/path full-path))
                   (fs/canonicalize (fs/path base-path))))

(defn dir-item [^java.nio.file.Path root ^java.io.File f]
  {:name           (.getName f)
   :abs-path       (.getAbsolutePath f)
   :rel-path       (when root (-> root (.relativize (.toPath f)) (.toString)))
   :dir?           (.isDirectory f)
   :file?          (.isFile f)
   :playlist-file? (re-find #"(?i)\.(m3u)$" (.getName f))
   :media-file?    (re-find #"(?i)\.(mp3|wav|ogg|oga|opus|flac|m4b|m4a|aac|m3u)$" (.getName f))})

(defn m3u?
  "Returns true if the file is a playlist"
  [abs-path]
  (some? (re-find #"(?i)\.m3u$" abs-path)))

(defn list-contents
  "List contents of a directory, sorted by filename. If root is provided, :rel-path will be relative to root"
  ([root path]
   (let [path-f (fs/file path)]
     (->>
      (seq (.listFiles path-f))
      (map (partial dir-item (fs/path root)))
       ;; (natsort/sort-by (juxt :file? :name))
      (natsort/sort-by :name))))
  ([path]
   (list-contents path path)))

(defn list-media-files [path]
  (->> (list-contents path)
       (filter :media-file?)))

(defn list-media-file-paths [folder-path]
  (->> folder-path
       (list-media-files)
       (map :abs-path)))

(defn canonicalize-path
  "Returns the canonical path for `path` when it is within the media directory."
  [settings path]
  (let [media-base (media-dir settings)
        abs-path   (fs/canonicalize (fs/path media-base path))]
    (when (validate-base-path media-base abs-path)
      (str abs-path))))

(defn media-relative-path
  "Returns the canonical media-root-relative representation of `path`."
  [settings path]
  (when-let [abs-path (canonicalize-path settings path)]
    (str (fs/relativize (media-dir settings) abs-path))))

(defn playable-type
  "Returns :dir, :playlist, or :file if the path is a playable media path, otherwise nil."
  [settings path]
  (if (str/starts-with? path "http")
    :url
    (let [media-base (media-dir settings)
          abs-path   (canonicalize-path settings path)]
      (when (and abs-path (.exists (fs/file abs-path)))
        (let [{:keys [dir? file? media-file?]}
              (dir-item (Paths/get media-base (into-array ["/"]))
                        (fs/file abs-path))]
          (cond
            (and dir? (not-empty (list-media-files (fs/file abs-path)))) :dir
            (m3u? abs-path) :playlist
            (str/ends-with? abs-path ".tts-cache") :tts
            (and file? media-file?) :file
            :else nil))))))

(defn list-dirs
  ([path]
   (->> (list-contents path)
        (filter :dir?)))
  ([root path]
   (->> (list-contents (str root "/" path))
        (filter :dir?))))

(defn list-media-dir [settings]
  (list-dirs (media-dir settings)))

(defn valid-dir? [settings folder-path]
  (and
   (validate-base-path (media-dir settings) folder-path)
   (fs/directory? folder-path)))

(defn normalize-path
  "Normalizes a file path on Unix systems by eliminating '.' and '..' from it."
  ^String [^String file-path]
  (loop [dest [] src (str/split file-path #"/")]
    (if (empty? src)
      (str/join "/" dest)
      (let [curr (first src)]
        (cond (= curr ".") (recur dest (rest src))
              (= curr "..") (recur (vec (butlast dest)) (rest src))
              :else (recur (conj dest curr) (rest src)))))))

(defn basename
  "Given a path /foo/bar/baz returns baz"
  ^String [^String path]
  (fs/file-name path))

(defn dirname
  "Given a path /foo/bar/baz returns /foo"
  ^String [^String path]
  (some-> path fs/parent str))

(defn component-paths
  "Given a string /Foo/Bar/Baz, returns a vector of strings: [/Foo /Foo/Bar /Foo/Bar/Baz]"
  [^String s]
  (assert (str/starts-with? s "/") "Path must be absolute")
  (assert (not (str/ends-with? s "/")) "Path must not end with slash")
  (let [sub (str/split s #"/")]
    (map (fn [i]
           (str "/"
                (str/join "/" (subvec sub 1 (inc i)))))
         (range 1 (inc (count (re-seq #"/" s)))))))

(defn absoluteify
  "Given a relative path inside the media dir, return the absolute path"
  [settings rel-path]
  (when rel-path
    (str (Paths/get (media-dir settings) (into-array [rel-path])))))

(defn sfx-path [settings key]
  (when-let [path (-> settings :sfx key)]
    (str (media-dir settings) "/" path)))

(comment
  (list-media-files (str (media-dir nil) "/WinnieThePooh"))
  (list-dirs media-dir)
  (absoluteify nil "WinnieThe")

  ;; rcf
  ;;
  )
