(ns fairy.box.audio.browse
  (:require
   [clojure.java.io :as io]))

(def media-dir "/home/admin/audio")

(defn dir-item [^java.nio.file.Path root ^java.io.File f]
  {:name (.getName f)
   :abs-path (.getAbsolutePath f)
   :rel-path (-> root (.relativize (.toPath f)) (.toString))
   :dir? (.isDirectory f)
   :file? (.isFile f)
   :media-file? (re-find #"(?i)\.(mp3|wav|ogg|oga|opus|flac|m4b|m4a|aac)$" (.getName f))})

(defn list-contents [path]
  (let [root (io/file path)]
    (->>
     (seq (.listFiles root))
     (map (partial dir-item (.toPath root))))))

(defn list-media-files [path]
  (->> (list-contents path)
       (filter :media-file?)))

(defn list-media-file-paths [folder-path]
  (->> (str media-dir "/" folder-path)
       (list-media-files)
       (map :abs-path)))

(defn list-dirs
  ([path]
   (->> (list-contents path)
        (filter :dir?)))
  ([root path]
   (->> (list-contents (str root "/" path))
        (filter :dir?))))

(defn list-media-dir []
  (list-dirs media-dir))

(comment
  (list-media-files (str  media-dir "/WinnieThePooh"))
  (list-dirs media-dir)

  ;; rcf
  ;;
  )
