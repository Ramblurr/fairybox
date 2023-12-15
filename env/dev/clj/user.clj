(ns user
  "Userspace functions you can run by default in your local REPL."
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

(comment

  (require '[clojure.core.async :as async])
  (async/go (async/>! (:publisher (:fairy.box.bus/bus state/system)) {:topic :buttons :value {:foo :bar}}))

  (def player (:player (:fairy.box.audio/player state/system)))
  (-> player  (.mediaPlayer) (.controls) (.stop))
  (def media1 (-> player  (.mediaPlayer) (.media) (.newMedia)))

  (portal-remote)
  (refresh)
  (go)
  (halt)
  (reset)
  (reset-all)
  (reset-web)
  ;;
  )
