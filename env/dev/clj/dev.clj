;; Copyright © 2025 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2
(ns dev
  (:require
   [clojure.core.async :as async]
   [clj-reload.core :as clj-reload]
   [fairy.box.hardware.rfid :as rfid]
   [ol.dev.portal :as my-portal]))

;; --------------------------------------------------------------------------------------------
;; System Control

(defn- system-app-atom []
  (var-get (requiring-resolve 'fairy.box.system/app_)))

(defn- stop-system! []
  (when-let [app @(system-app-atom)]
    ((:stop app))))

(defn- start-system! []
  ((requiring-resolve 'fairy.box.system/-main)))

(defn restart
  "Restart the application system."
  []
  (stop-system!)
  (start-system!))

(defn reset
  "Reset the application system by stopping the system, reloading all code, then starting the system again."
  []
  (stop-system!)
  (clj-reload/reload)
  (start-system!))

(defn rfid-comp []
  (get-in @(system-app-atom)
          [:ctx :donut.system/instances
           :fairy.box/components :fairy.box.hardware/rfid]))

(comment
  (rfid/place! (rfid-comp) "dev-card-001")
  (rfid/remove! (rfid-comp))
  ;;
  )

;; --------------------------------------------------------------------------------------------
;; REPL and Inspector

;; Configure the paths containing clojure sources we want clj-reload to reload
(clj-reload/init {:dirs      ["src" "env/dev/clj" "test"]
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

(comment

  (do
    #_(require '[babashka.fs :as fs])
    (require '[fairy.box.audio.browse :as browse])
    (let [comps (-> @(system-app-atom) :ctx :donut.system/instances :fairy.box/components)]
      (def settings (:fairy.box/settings comps))
      (def player (:player (:fairy.box.audio.system2/player comps)))
      (def emitter (:emitter (:fairy.box.audio.system2/player comps)))
      (def db-conn (:fairy.box.db/db comps))
      (def bus (:fairy.box.bus/bus comps))))

  #_(browse/canonicalize-path settings
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
