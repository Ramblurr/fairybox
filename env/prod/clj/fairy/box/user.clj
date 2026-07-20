;; Copyright © 2025 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2
(ns fairy.box.user
  (:require
   [portal.api :as inspect]
   #_[clojure.string :as string]
   #_[clojure.java.io :as io]
   #_[fairy.box.switchboard :as switchboard]
   #_[fairy.box.system :as system]))

(println "Loading fairy.box.user PROD")

(defn portal-remote []
  (inspect/open {:theme :portal.colors/gruvbox
                 :portal.launcher/host "0.0.0.0"
                 :portal.launcher/port  7001})
  (add-tap portal.api/submit))

#_(defn extract
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
  (require '[fairy.box.system :as system]
           '[fairy.box.switchboard :as switchboard])
  (keys @system/app_)

  (portal-remote)

  (do
    (require '[clojure.core.async :as async])
    (let [audio (system/component :fairy.box.audio.system2/player)]
      (def player (:player audio))
      (def emitter (:emitter audio))))

  (switchboard/emit-led! emitter {:action :led/set :groups [:all] :value  0.0})
  (switchboard/emit-led! emitter {:action :led/set :names [:audio/volume-up] :value  0.0})
  (switchboard/emit-led! emitter {:action :led/set :groups [:all] :value  1.0})

  (switchboard/emit-led! emitter {:action :led/fade :names [:audio/volume-up] :duration 1000})
  (switchboard/emit-led! emitter {:action :led/fade :groups [:all] :duration 1000 :start-delay 1000 :after-set 1.0})
  (switchboard/emit-led! emitter {:action :led/fade :groups [:all] :duration 1000 :from 0.0 :to 1.0 :after-set 1.0})

  (switchboard/emit-led! emitter {:action :led/pulse :names [:audio/volume-up] :after-set 1.0})

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
