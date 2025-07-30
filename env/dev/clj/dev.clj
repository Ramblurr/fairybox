(ns dev
  (:require
   [clj-reload.core :as clj-reload]
   [fairy.box.audio.interop :as interop]
   [fairy.box.audio.system]
   [fairy.box.web.handler]
   [fairy.box.bus]
   [kit.edge.server.undertow]
   [fairy.box.config :as config]
   [fairy.box.db]
   [fairy.box.hardware]
   [fairy.box.mqtt]
   [fairy.box.settings]
   [fairy.box.switchboard]
   [fairy.box.tts]
   [fairy.box.web.routes.api]
   [fairy.box.web.routes.ui]
   [integrant.core :as ig]
   [integrant.repl]
   [integrant.repl.state]
   [ol.dev.portal :as my-portal]))

;; --------------------------------------------------------------------------------------------
;; System Preparation

(defn rpi?
  "Are we running on a Raspberry Pi?"
  []
  (nil? (System/getenv "NOT_A_RPI")))

(defn dev-prep!
  []
  (integrant.repl/set-prep! (fn []
                              (-> (config/system-config {:profile (if (rpi?) :dev :dev-no-rpi)})
                                  (dissoc :nrepl/server)
                                  (ig/expand)))))

;; --------------------------------------------------------------------------------------------
;; System Control

(defn start []
  (set! *warn-on-reflection* true)
  (set! *print-namespace-maps* false)
  (integrant.repl/go)
  :started)

(defn stop []
  (integrant.repl/halt)
  (integrant.repl/halt)
  :stopped)

(defn reset []
  (stop)
  (clj-reload/reload)
  (start))

(defn reset-all []
  (stop)
  (clj-reload/reload {:only :all})
  (start))

;; --------------------------------------------------------------------------------------------
;; REPL and Inspector

;; Configure the paths containing clojure sources we want clj-reload to reload
(clj-reload/init {:dirs      ["src" "env/dev" "test"]
                  :no-reload #{'user 'dev 'ol.dev.portal}})

(dev-prep!)

(comment

  (do
    (require '[clojure.core.async :as async])
    (def settings (:fairy.box/settings integrant.repl.state/system))
    (def player (:player (:fairy.box.audio.system/player integrant.repl.state/system)))
    (def emitter (:emitter (:fairy.box.audio.system/player integrant.repl.state/system)))
    (def db-conn (:fairy.box.db/db integrant.repl.state/system))
    (def bus (:fairy.box.bus/bus integrant.repl.state/system))) ;; rcf

  (interop/pause! player)
  (interop/unpause! player)

  (my-portal/open-portals)
  (start)
  (stop)
  (reset)
  (reset-all)

  ;;
  )
