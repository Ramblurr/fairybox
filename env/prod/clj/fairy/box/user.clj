(ns fairy.box.user
  (:import
   [java.net URL] [java.io File] [java.util.zip ZipInputStream])
  (:require
   [clojure.string :as string]
   [fairy.box.audio.interop :as interop]
   [clojure.java.io :as io]
   [fairy.box.core :as main]))

(defn fix-jar-path [path]
  (if (re-find #"^jar:file:" path)
    (clojure.string/replace path #"^jar:file:" "zip:/")
    path))

(defn fix-jar-url [^java.net.URL url]
  (fix-jar-path (.toString url)))

(defn extract
  ([zip-path dest-dir]
   (extract zip-path dest-dir (constantly true)))
  ([zip-path dest-dir pred]
   (with-open [stream (-> zip-path io/as-file io/input-stream (java.util.zip.ZipInputStream.))]
     (loop [entry (.getNextEntry stream)]
       (when entry
         (when (pred entry)
           (let [entry-path (str dest-dir File/separatorChar (.getName entry))
                 entry-file (File. entry-path)]
             (if (.isDirectory entry)
               (when-not (.exists entry-file)
                 (.mkdirs entry-file))
               (let [parent-dir (.getParentFile entry-file)]
                 (when-not (.exists parent-dir) (.mkdirs parent-dir))
                 (clojure.java.io/copy stream entry-file)))))
         (recur (.getNextEntry stream)))))))

(comment

  (def path "jar:file:/home/ramblurr/box-standalone.jar!/sfx/startupsound.mp3")
  (fix-jar-path path)
  (fix-jar-url (java.net.URL. path))

  (keys @main/system)

  (do
    (require '[clojure.core.async :as async])

    (def player (:player (:fairy.box.audio.system/player @main/system)))
    (def emitter (:emitter (:fairy.box.audio.system/player @main/system)))) ;; rcf

  (-> player  (.mediaPlayer) (.media) (.start (fix-jar-path path) nil))
  (-> player  (.mediaPlayer) (.media) (.start (.toString (io/resource "sfx/startupsound.mp3")) nil))
  (-> player  (.mediaPlayer) (.media) (.start "/home/ramblurr/tmp2/sfx/startupsound.mp3" nil))
  (io/as-file "/home/ramblurr/box-standalone.jar")
  (extract  "/home/ramblurr/box-standalone.jar" "/home/ramblurr/tmp2" (fn [entry]
                                                                        (string/starts-with? (.getName entry) "sfx")))
  1)
