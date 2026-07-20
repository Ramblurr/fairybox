;; Copyright © 2025 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2
(ns dev
  (:require
   [clj-reload.core :as clj-reload]
   [fairy.box.hardware.rfid :as rfid]
   [fairy.box.system :as sys]
   [ol.dev.portal :as my-portal]))

;; --------------------------------------------------------------------------------------------
;; System Control

(defn restart
  "Restart the application system."
  []
  (when @sys/app_
    ((@sys/app_ :stop)))
  (sys/-main))

(defn reset
  "Reset the application system by stopping the system, reloading all code, then starting the system again."
  []
  (when @sys/app_ ((@sys/app_ :stop)))
  (clj-reload/reload)
  (sys/-main))

(defn rfid-comp [] (get-in @sys/app_ [:ctx :donut.system/instances :fairy.box/components :fairy.box.hardware/rfid]))

(comment
  (rfid/place! (rfid-comp) "dev-card-001")
  (rfid/remove! (rfid-comp))
  ;;
  )

;; --------------------------------------------------------------------------------------------
;; REPL and Inspector

;; Configure the paths containing clojure sources we want clj-reload to reload
(clj-reload/init {:dirs      ["src" "env/dev" "test"]
                  :no-reload #{'user 'dev 'ol.dev.portal}})

(defonce ps (my-portal/open-portals))
(comment
  (reset! my-portal/portal-state nil))

(comment
  (restart)
  (reset) ;; rcf A reset is a stop, code reload, and start.
  (clj-reload/reload {:only :all})
  (clojure.repl.deps/sync-deps)
  ;;
  )

#_(comment

    (do
      (require '[babashka.fs :as fs])
      (require '[clojure.core.async :as async])
      (require '[fairy.box.audio.browse :as browse])
      (let [comps (-> @app/system :donut.system/instances :fairy.box/components)]
        (def settings (:fairy.box/settings comps))
        (def player (:player (:fairy.box.audio.system2/player comps)))
        (def emitter (:emitter (:fairy.box.audio.system2/player comps)))
        (def db-conn (:fairy.box.db/db comps))
        (def bus (:fairy.box.bus/bus comps))))

    (browse/canonicalize-path settings
                              "audiobooks/Arnold Lobel/Days with Frog and Toad")

    (async/put! emitter {:path "/player/commands" :value {:action :audio/stop}})
    (async/put! emitter {:path "/player/commands" :value {:action :audio/play}})
    (async/put! emitter {:path "/player/commands" :value {:action :audio/set-volume :volume 80}})

    (async/put! emitter {:path "/player/commands"
                         :value {:action :audio/play-path
                                 :item-path
                                 "audiobooks/Arnold Lobel/Days with Frog and Toad"
                               ;; "/srv/media/audiobooks/Margaret Wise Brown"
                               ;; "audiobooks/From Nonna/"
                                 :announce-per-track? true}})

    (-> @app/system :donut.system/instances :fairy.box/components :fairy.box/settings)

    (my-portal/open-portals))
