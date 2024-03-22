(ns fairy.box.user
  (:import
    [java.io File] [java.util.zip ZipInputStream])
  (:require
   [portal.api :as inspect]
   [clojure.string :as string]
   [fairy.box.audio.interop :as interop]
   [clojure.java.io :as io]
   [fairy.box.switchboard :as switchboard]
   [fairy.box.core :as main]))

(defn portal-remote []
  (inspect/open {:theme :portal.colors/gruvbox
                 :portal.launcher/host "0.0.0.0"
                 :portal.launcher/port  7001})
  (add-tap portal.api/submit))

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
  (keys @main/system)

  (portal-remote)

  (do
    (require '[clojure.core.async :as async])
    (def player (:player (:fairy.box.audio.system/player @main/system)))
    (def emitter (:emitter (:fairy.box.audio.system/player @main/system)))) ;; rcf

  (switchboard/emit-led! emitter {:action :led/set :groups [:all] :value  1.0})

  (switchboard/emit-led! emitter {:action :led/pulse :names [:audio/prev] :after-set 1.0})


  (interop/stop! player)
  (-> player  (.mediaPlayer) (.media) (.start "/home/ramblurr/media/sfx/startupsound.mp3" nil))
  (-> player (.mediaPlayer) (.audio) (.setOutputDevice "pipewire" "pipewire"))
  (-> player (.mediaPlayer) (.audio) (.setOutput "pipewire"))
  (-> player (.mediaPlayer) (.audio) (.outputDevice))
  (tap> (-> player (.mediaPlayer) (.audio) (.outputDevices)))

  (-> player (.mediaPlayer) (.mediaPlayer) (.audio) (.outputDevices))

  (-> player  (.mediaPlayer) (.media) (.start (.toString (io/resource "sfx/startupsound.mp3")) nil))
  (io/as-file "/home/ramblurr/box-standalone.jar")
  (extract  "/home/ramblurr/box-standalone.jar" "/home/ramblurr/tmp2" (fn [entry]
                                                                        (string/starts-with? (.getName entry) "sfx")))
  ;;
  )
