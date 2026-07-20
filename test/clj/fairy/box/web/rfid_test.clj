(ns fairy.box.web.rfid-test
  (:require
   [clojure.core.async :as async]
   [clojure.test :refer [deftest is]]
   [jp.nijohando.event :as ev]))

(defn- rfid-var [sym]
  (try
    (requiring-resolve (symbol "fairy.box.web.rfid" (name sym)))
    (catch Exception _
      nil)))

(defn- await-refresh [refreshes]
  (let [timeout (async/timeout 1000)
        [value channel] (async/alts!! [refreshes timeout])]
    (when-not (= channel timeout)
      value)))

(deftest tracks-current-rfid-before-refreshing
  (let [start! (rfid-var 'start-presence!)
        stop! (rfid-var 'stop-presence!)]
    (is (= {:start? true :stop? true}
           {:start? (some? start!) :stop? (some? stop!)}))
    (when (and start! stop!)
      (let [bus (ev/bus)
            emitter (async/chan 2)
            refreshes (async/chan 2)
            presence (start! {:bus bus
                              :refresh! #(async/>!! refreshes :refreshed)})]
        (try
          (ev/emitize bus emitter)
          (async/>!! emitter
                     {:path "/hardware/input/rfid"
                      :value {:action :placed :uid "tag-2" :at 1}})
          (is (= :refreshed (await-refresh refreshes)))
          (is (= {:action :placed :uid "tag-2" :at 1}
                 @(:state presence)))

          (async/>!! emitter
                     {:path "/hardware/input/rfid"
                      :value {:action :removed :uid "tag-2" :at 2}})
          (is (= :refreshed (await-refresh refreshes)))
          (is (= {:action :removed :uid "tag-2" :at 2}
                 @(:state presence)))
          (finally
            (stop! presence)
            (async/close! emitter)
            (async/close! refreshes)
            (ev/close! bus)))))))

(deftest stopped-presence-does-not-refresh
  (let [start! (rfid-var 'start-presence!)
        stop! (rfid-var 'stop-presence!)]
    (when (and start! stop!)
      (let [bus (ev/bus)
            emitter (async/chan 1)
            refreshes (async/chan 1)
            presence (start! {:bus bus
                              :refresh! #(async/>!! refreshes :refreshed)})]
        (try
          (ev/emitize bus emitter)
          (stop! presence)
          (async/>!! emitter
                     {:path "/hardware/input/rfid"
                      :value {:action :placed :uid "tag-after-stop"}})
          (is (nil? (await-refresh refreshes)))
          (finally
            (async/close! emitter)
            (async/close! refreshes)
            (ev/close! bus)))))))
