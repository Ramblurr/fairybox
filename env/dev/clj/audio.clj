(ns audio
  (:import [uk.co.caprica.vlcj.media.callback.seekable SeekableCallbackMedia]
           [java.nio.channels FileChannel]
           [java.nio.file Files Path Paths]
           [java.nio.file StandardOpenOption]
           [java.io IOException])
  (:require
   [clojure.java.io :as io]
   [fairy.box.audio.interop :as interop]))

(defn ->MappedByteBufferCallbackMedia [^java.nio.MappedByteBuffer buf close-fn]
  (proxy [SeekableCallbackMedia] []
    (onGetSize [] (.capacity buf))
    (onOpen []
      (if (nil? buf)
        false
        true))
    (onRead [buffer bufferSize]
      (let [remaining (.remaining buf)
            read (min bufferSize remaining)]
        (.get buf buffer 0 read)
        read))

    (onSeek [offset]
      (let [pos   (.position buf (cast Long (.intValue offset)))
            pos (.position pos)]
        (== pos offset)))
    (onClose []
      (close-fn))))

(defn ->FileMappedByteBufferCallbackMedia [url]
  (let [path (.toPath (io/file (.getFile url)))
        channel (Files/newByteChannel path (into-array StandardOpenOption [StandardOpenOption/READ]))
        ^java.nio.MappedByteBuffer buf (.map channel java.nio.channels.FileChannel$MapMode/READ_ONLY 0 (.size channel))]
    (->MappedByteBufferCallbackMedia buf (fn []
                                           (try
                                             (.close channel)
                                             (catch IOException e))))))
(comment
  (def player (interop/init-player!
               (fn [event]
                 (tap> {:player event}))))
  (do
    (interop/release-player! player)
    (def player nil))

  (def media-list
    (interop/make-media-list player ["/home/admin/fairybox/fart.mp3" "/home/admin/fairybox/ice-crack.mp3" "/home/admin/fairybox/sneeze.mp3"]))

  (interop/set-media-list! player media-list)
  (interop/unpause! player)
  (interop/stop! player)
  (interop/unpause! player)
  (interop/set-repeat-mode! player :default)
  (interop/extract-metadata "/home/admin/fairybox/fart.mp3")

  (def callback-media (->FileMappedByteBufferCallbackMedia (io/resource "sfx/sergequadrado__magic-harp-logo.wav")))
  (def callback-media nil)

  (-> player (.mediaPlayer) (.media) (.start callback-media nil))

;;
  )
