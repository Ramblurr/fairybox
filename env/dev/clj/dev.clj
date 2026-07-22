;; Copyright © 2025 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2
(ns dev
  (:refer-clojure :exclude [remove])
  (:require
   [clojure.tools.logging :as log]
   [clojure.core.async :as async]
   [clj-reload.core :as clj-reload]
   [fairy.box.hardware.rfid :as rfid]
   [ol.dev.portal :as my-portal]))

;; --------------------------------------------------------------------------------------------
;; System Control
(def defaults
  {:init  (fn []
            (log/info "\n-=[box starting using the development or test profile]=-"))
   :start (fn []
            (log/info "\n-=[box started successfully using the development or test profile]=-"))
   :stop  (fn []
            (log/info "\n-=[box has shut down successfully]=-"))
   :opts  {:profile       (if (System/getenv "NOT_A_RPI")
                            :dev-no-rpi
                            :dev)
           :persist-data? true}})

(defn- profile []
  (get-in defaults [:opts :profile]))

(defn- stop-system! []
  ((requiring-resolve 'fairy.box.system/stop!)))

(defn- start-system! []
  ((requiring-resolve 'fairy.box.system/-main)
   {:profile (profile)}))

(defn restart
  "Restart the application system."
  []
  ((requiring-resolve 'fairy.box.system/restart!)
   {:profile (profile)}))

(defn reload
  "Reloads changed namespaces, rebuilds CSS, and refreshes connected views."
  []
  (let [result (clj-reload/reload)]
    ((requiring-resolve 'fairy.box.css/start))
    ((requiring-resolve 'hyperlith.core/refresh-all!))
    result))

(defn reset
  "Reset the application system by stopping, reloading code, and starting again."
  []
  #_(stop-system!)
  #_(clj-reload/reload)
  #_(start-system!)
  (reload))

(defn reset-and-restart
  []
  (stop-system!)
  (clj-reload/reload)
  ((requiring-resolve 'fairy.box.css/start))
  (start-system!)
  ((requiring-resolve 'hyperlith.core/refresh-all!)))

(defn rfid-comp []
  ((requiring-resolve 'fairy.box.system/component)
   :fairy.box.hardware/rfid))

(defn player-comp []
  ((requiring-resolve 'fairy.box.system/component)
   :fairy.box.audio.system2/player))

(defn emitter-comp []
  (:emitter (player-comp)))

(defn play-pause! []
  (async/put! (emitter-comp) {:path "/player/commands" :value {:action :audio/play-pause}}))

(defn stop []
  (async/put! (emitter-comp) {:path "/player/commands" :value {:action :audio/stop}}))

(defn clear-playlist []
  (async/put! (emitter-comp) {:path "/player/commands" :value {:action :audio/clear}}))

(defn place1 []
  (rfid/place! (rfid-comp) "dev-card-001"))

(defn remove []
  (rfid/remove! (rfid-comp)))

#_(do
    (reset-and-restart)
    #_(require '[babashka.fs :as fs])
    (require '[fairy.box.audio.browse :as browse])
    (let [component (requiring-resolve 'fairy.box.system/component)
          audio     (component :fairy.box.audio.system2/player)]
      (def settings (component :fairy.box/settings))
      (def player (:player audio))
      (def emitter (:emitter audio))
      (def db-conn (component :fairy.box.db/db))
      (def bus (component :fairy.box.bus/bus))))

(comment
  (reset-and-restart)
  (place1)
  (remove)
  (play-pause!)
  (stop)
  (clear-playlist)
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
  (reload)
  (clj-reload/reload {:only :all})
  (clojure.repl.deps/sync-deps)
  ;;
  )

(comment

  (do
    #_(require '[babashka.fs :as fs])
    (require '[fairy.box.audio.browse :as browse])
    (let [component (requiring-resolve 'fairy.box.system/component)
          audio     (component :fairy.box.audio.system2/player)]
      (def settings (component :fairy.box/settings))
      (def player (:player audio))
      (def emitter (:emitter audio))
      (def db-conn (component :fairy.box.db/db))
      (def bus (component :fairy.box.bus/bus))))

  #_(browse/canonicalize-path settings
                              "audiobooks/Arnold Lobel/Days with Frog and Toad")

  (async/put! emitter {:path "/player/commands" :value {:action :audio/stop}})
  (async/put! emitter {:path "/player/commands" :value {:action :audio/play}})
  (async/put! emitter {:path "/player/commands" :value {:action :audio/set-volume :volume 80}})

  (async/put! emitter {:path  "/player/commands"
                       :value {:action              :audio/play-path
                               :item-path
                               "audiobooks/Arnold Lobel/Days with Frog and Toad"
                               ;; "/srv/media/audiobooks/Margaret Wise Brown"
                               ;; "audiobooks/From Nonna/"
                               :announce-per-track? true}})

  (my-portal/open-portals)

  (rfid/place! (rfid-comp) "dev-card-001")
  (rfid/remove! (rfid-comp))

  (require '[fairy.box.hardware.led :as led])
  (def led-controller
    (:controller ((requiring-resolve 'fairy.box.system/component)
                  :fairy.box.hardware/leds)))
  (led/set-led! led-controller :audio/play-pause 0.0)
  (led/set-led! led-controller :audio/play-pause 1.0)
  (led/pulse led-controller [:audio/play-pause] 10 :dev-pulse))
