(ns audio
  (:require
   [fairy.box.audio.interop :as interop]))

(comment
  (def player (interop/init-player))
  (def player nil)
  (interop/release-player! player)

  (def media-list
    (interop/make-media-list player ["/home/admin/fairybox/fart.mp3" "/home/admin/fairybox/ice-crack.mp3" "/home/admin/fairybox/sneeze.mp3"]))

  (interop/set-media-list! player media-list)
  (interop/unpause! player)
  (interop/stop! player)
  (interop/unpause! player)
  (interop/set-repeat-mode! player :default)
  (interop/extract-metadata "/home/admin/fairybox/fart.mp3")

  ;;
  )
