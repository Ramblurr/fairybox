(ns fairy.box.settings

  (:require
   [clojure.tools.logging :as log]
   [clojure.core.async :as async]
   [jp.nijohando.event :as ev]
   [integrant.core :as ig]))

(defmethod ig/init-key :fairy.box/settings [_ opts]
  opts)

(defn startup! [{:keys [bus]}]
  (let [emitter (async/chan)]
    (Thread/sleep 1000)
    (ev/emitize bus emitter)
    (async/put! emitter {:path "/system" :value {:event :system/initialized}})
    {:emitter emitter}))

(defmethod ig/init-key :fairy.box/startup [_ opts]
  (log/info "\n-=[initialized state reached]=-")
  (startup! opts))

(defmethod ig/halt-key! :fairy.box/startup [_ {:keys [emitter]}]
  (async/close! emitter))
