(ns fairy.box.audio.browse
  (:require
   [clojure.java.io :as io]))

(def media-dir "/home/admin/audio")

(defn dir-item [^java.nio.file.Path root ^java.io.File f]
  {:name (.getName f)
   :abs-path (.getAbsolutePath f)
   :rel-path (-> root (.relativize (.toPath f)) (.toString))
   :dir? (.isDirectory f)
   :file? (.isFile f)})

(defn list-contents [path]
  (let [root (io/file path)]
    (->>
     (seq (.listFiles root))
     (map (partial dir-item (.toPath root))))))

(defn list-dirs [path]
  (->> (list-contents path)
       (filter :dir?)))

(defn list-media-dir []
  (list-dirs media-dir))

(comment
  (list-contents media-dir)
  (list-dirs media-dir)

  ;; rcf
  ;;
  )
