(ns user
  "Userspace functions you can run by default in your local REPL."
  (:import [uk.pigpioj PigpioJ]
           [uk.co.caprica.vlcj.media ParseFlag Meta Picture MetaData MediaParsedStatus MediaEventListener Media MediaRef MediaEventAdapter])
  (:require
   [portal.api :as inspect]
   [clojure.pprint]
   [clojure.spec.alpha :as s]
   [clojure.tools.namespace.repl :as repl]
   [criterium.core :as c]                                  ;; benchmarking
   [expound.alpha :as expound]
   [integrant.core :as ig]
   [integrant.repl :refer [clear go halt prep init reset reset-all]]
   [integrant.repl.state :as state]
   [kit.api :as kit]
   [lambdaisland.classpath.watch-deps :as watch-deps]      ;; hot loading for deps
   [fairy.box.audio.browse :as browse]
   [fairy.box.audio.interop :as interop]
   [fairy.box.audio.system :as audio-sys]
   [fairy.box.core :refer [start-app]]))

;; uncomment to enable hot loading for deps
(watch-deps/start! {:aliases [:dev :test]})

(alter-var-root #'s/*explain-out* (constantly expound/printer))

;; (add-tap (bound-fn* clojure.pprint/pprint))
;; (remove-tap (bound-fn* clojure.pprint/pprint))
;;

(defn dev-prep!
  []
  (integrant.repl/set-prep! (fn []
                              (-> (fairy.box.config/system-config {:profile :dev})
                                  (ig/prep)))))

(defn test-prep!
  []
  (integrant.repl/set-prep! (fn []
                              (-> (fairy.box.config/system-config {:profile :test})
                                  (ig/prep)))))

;; Can change this to test-prep! if want to run tests as the test profile in your repl
;; You can run tests in the dev profile, too, but there are some differences between
;; the two profiles.
(dev-prep!)

(repl/set-refresh-dirs "src/clj")

(def refresh repl/refresh)

(defn portal-remote []
  (inspect/open {:theme :portal.colors/gruvbox
                 :portal.launcher/host "10.9.6.33"
                 :portal.launcher/port  7001})
  (add-tap portal.api/submit))

(defn reset-web []
  (ig/halt! state/system [:handler/ring  :server/http :reitit.routes/api :router/routes :router/core :system/env])
  (ig/init state/system [:handler/ring  :server/http :reitit.routes/api :router/routes :router/core :system/env]))

(portal-remote)

(comment
  (async/go (async/>! (:publisher (:fairy.box.bus/bus state/system)) {:topic :buttons :value {:foo :bar}}))
  (do
    (require '[clojure.core.async :as async])
    (def settings (:fairy.box/settings state/system))
    (def player (:player (:fairy.box.audio.system/player state/system)))
    (def emitter (:emitter (:fairy.box.audio.system/player state/system)))) ;; rcf
  (async/put! emitter {:path "/system" :value {:event :system/cooling-down}})
  (async/put! emitter {:path "/hardware/output/leds" :value
                       {:action :led/set
                        :groups [:all]
                        :value  0.0}})
  (-> player  (.mediaPlayer) (.controls) (.stop))
  (def media1 (-> player  (.mediaPlayer) (.media) (.newMedia)))
  (-> player  (.mediaPlayer) (.audio) (.setVolume 40))

  (def playlist "/home/ramblurr/media/playlists/LibbyDish1.m3u")

  (browse/m3u? playlist)
  (browse/playable-type settings playlist)

  (interop/stop! player)
  (def medias (interop/make-medias! [playlist]))
  (def media-list (interop/make-media-list medias))

  (browse/playable-type settings (browse/absoluteify settings "playlists/LibbyDish1.m3u"))
  (async/put! emitter {:path  "/player/commands"
                       :value {:action    :audio/play-path
                               :item-path (browse/absoluteify settings "playlists/LibbyDish1.m3u")
                               :uid       nil}})


  (audio-sys/media-info (first medias))
  (-> (first medias)  (.info) (.type))
  (def playlist-media-list (-> (first medias) (.subitems) (.newMediaList)))
  (-> playlist-media-list (.media) (.count))
  (-> playlist-media-list (.media) (.mrls))
  (def item-0 (-> playlist-media-list (.media) (.newMedia 0)))
  (interop/media->meta-map (first medias))
  (interop/media->meta-map item-0)
  (interop/parse-medias-async! (interop/parse-event-listener
                          (fn [^Media media meta-map status]
                             ;; this is the parse handler callback
                            (tap> {:event    :internal-player/pre-play-parse
                                   :media    media
                                   :status   status
                                   :meta-map meta-map})))
                         [item-0])

  (.release playlist-media-list)

  (-> (first medias) (.parsing)  (.status))

  (-> media-list (.media) (.count))

  (doseq [m medias] (.release m))
  (.release media-list)

  (interop/set-media-list! player media-list)
  (interop/unpause! player)

  (-> media-list (.media) (.count))

  (-> player  (.mediaPlayer) (.media) (.play playlist nil))

  (-> player (.mediaListPlayer) (.list) (.media) (.mrls))

  (-> player (.mediaListPlayer) (.controls) (.playNext))

  #_(PigpioJ/autoDetectedImplementation)

  1
  (portal-remote)
  (refresh)
  (go)
  (halt)
  (reset)
  (reset-all)
  (reset-web)
  1
  ;;
  )
