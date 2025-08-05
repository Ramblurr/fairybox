;; Copyright © 2025 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2
(ns dev
  (:require
   [hifi.system :as hifi]
   [clj-reload.core :as clj-reload]
   [fairy.box.system :as app]
   ;; [fairy.box.audio.system2]
   ;; [fairy.box.web.handler]
   ;; [fairy.box.bus]
   ;; [fairy.box.config :as config]
   ;; [fairy.box.db]
   ;; [fairy.box.hardware]
   ;; [fairy.box.mqtt]
   ;; [fairy.box.settings]
   ;; [fairy.box.switchboard]
   ;; [fairy.box.tts]
   ;; [fairy.box.web.routes.api]
   ;; [fairy.box.web.routes.ui]
   [ol.dev.portal :as my-portal]))

;; --------------------------------------------------------------------------------------------
;; System Control

(defn restart
  "Restart the application system."
  []
  (hifi/stop @app/system)
  (app/start))

(defn reset
  "Reset the application system by stopping the system, reloading all code, then starting the system again."
  [& args]
  (hifi/stop @app/system)
  (apply clj-reload/reload args)
  (app/start))

;; --------------------------------------------------------------------------------------------
;; REPL and Inspector

;; Configure the paths containing clojure sources we want clj-reload to reload
(clj-reload/init {:dirs      ["src" "env/dev" "test"]
                  :no-reload #{'user 'dev 'ol.dev.portal}})

(defonce ps (my-portal/open-portals))
(comment
  (reset! my-portal/portal-state nil))

(comment

  (do
    (require '[clojure.core.async :as async])
    (let [comps (-> @app/system :donut.system/instances :fairy.box/components)]
      (def settings (:fairy.box/settings comps))
      (def player (:player (:fairy.box.audio.system2/player comps)))
      (def emitter (:emitter (:fairy.box.audio.system2/player comps)))
      (def db-conn (:fairy.box.db/db comps))
      (def bus (:fairy.box.bus/bus comps)))) ;; rcf

  (async/put! emitter {:path "/player/commands" :value {:action :audio/stop}})
  (async/put! emitter {:path "/player/commands" :value {:action :audio/play}})
  (async/put! emitter {:path "/player/commands" :value {:action :audio/set-volume :volume 100}})

  (async/put! emitter {:path "/player/commands"
                       :value {:action :audio/play-path
                               :item-path
                               ;; "audiobooks/Arnold Lobel/Days with Frog and Toad"
                               ;; "/srv/media/audiobooks/Margaret Wise Brown"
                               "audiobooks/From Nonna/"
                               :uid "play1"
                               :announce-per-track? true}})

  (-> @app/system :donut.system/instances :fairy.box/components :fairy.box/settings)

  (my-portal/open-portals)

  ;; Restart the system
  (restart)
  ;; Reset the system
  ;; A reset is a stop, code reload, and start.
  (reset)
  ;; You can pass options to clj-reload to control the reload behavior.
  (reset {:only :all})

  ;;; Adding/Modifying Dependencies in deps.edn
  ;; If you add or modify your dependencies, you can run this to sync them.
  ;; This will save you a REPL restart in most cases.
  (clojure.repl.deps/sync-deps)
  ;;
  )
