(ns fairy.box.audio.browse
  (:import [java.nio.file Paths])
  (:require
   [clojure.string :as str]
   [clojure.java.io :as io]))

(defn media-dir [settings]
  (assert settings "settings not defined")
  (assert (-> settings :media :media-dir) "media-dir not defined")
 (-> settings :media :media-dir))

(defn validate-base-path
  "Helper to prevent path traversal attacks. If full-path is not contained inside base-path, will return false, otherwise true"
  [base-path full-path]
  (str/starts-with? (-> (io/file full-path) (.getCanonicalPath))
                    base-path))

(defn dir-item [^java.nio.file.Path root ^java.io.File f]
  {:name (.getName f)
   :abs-path (.getAbsolutePath f)
   :rel-path (when root (-> root (.relativize (.toPath f)) (.toString)))
   :dir? (.isDirectory f)
   :file? (.isFile f)
   :media-file? (re-find #"(?i)\.(mp3|wav|ogg|oga|opus|flac|m4b|m4a|aac)$" (.getName f))})

(defn playable-type
  "Returns :dir if the path is a playable directory, returns :file if it is a playable file. Returns nil otherwise"
  [settings abs-path]
  (let [file (io/file abs-path)]
    (when (and (validate-base-path (media-dir settings) abs-path) (.exists file))
      (let [{:keys [dir? file?]} (dir-item nil file)]
        (cond
          dir? :dir
          file? :file
          :else nil)))))

(defn list-contents [path]
  (let [root (io/file path)]
    (->>
     (seq (.listFiles root))
     (map (partial dir-item (.toPath root)))
     (sort-by (juxt :file? :name)))))

(defn list-media-files [path]
  (->> (list-contents path)
       (filter :media-file?)))

(defn list-media-file-paths [folder-path]
  (->> folder-path
       (list-media-files)
       (map :abs-path)))

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
   (.isDirectory (io/file folder-path))))

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
  (.getName (io/file path)))

(defn dirname
  "Given a path /foo/bar/baz returns /foo"
  ^String [^String path]
  (.getParent (io/file path)))

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
  (str (Paths/get (media-dir settings) (into-array [rel-path]))))

(comment
  (list-media-files (str (media-dir nil) "/WinnieThePooh"))
  (list-dirs media-dir)
  (absoluteify nil "WinnieThe")

  ;; rcf
  ;;
  )
