(ns fairy.box.web.player-events-test
  (:require
   [clojure.core.async :as async]
   [clojure.test :refer [deftest is]]
   [fairy.box.system :as system]
   [fairy.box.web.player-events :as player-events]
   [jp.nijohando.event :as ev]))

(defn- await-value [channel timeout-ms]
  (let [timeout (async/timeout timeout-ms)
        [value port] (async/alts!! [channel timeout])]
    (when (= port channel)
      value)))

(deftest refreshes-hyperlith-only-for-queue-changes
  (let [bus (ev/bus)
        emitter (async/chan 2)
        refreshes (async/chan 2)
        refresher (player-events/start-queue-refresh!
                   {:bus bus
                    :refresh! #(async/>!! refreshes :refreshed)})]
    (try
      (ev/emitize bus emitter)
      (async/>!! emitter
                 {:path "/player/events"
                  :value {:event :player/state-changed :state :playing}})
      (let [unrelated-refresh (await-value refreshes 100)]
        (async/>!! emitter
                   {:path "/player/events"
                    :value {:event :player/queue-changed}})
        (is (= {:unrelated-refresh nil
                :queue-refresh :refreshed}
               {:unrelated-refresh unrelated-refresh
                :queue-refresh (await-value refreshes 1000)})))
      (finally
        (player-events/stop-queue-refresh! refresher)
        (async/close! emitter)
        (async/close! refreshes)
        (ev/close! bus)))))

(deftest stopped-refresher-does-not-refresh
  (let [bus (ev/bus)
        emitter (async/chan 1)
        refreshes (async/chan 1)
        refresher (player-events/start-queue-refresh!
                   {:bus bus
                    :refresh! #(async/>!! refreshes :refreshed)})]
    (try
      (ev/emitize bus emitter)
      (player-events/stop-queue-refresh! refresher)
      (async/>!! emitter
                 {:path "/player/events"
                  :value {:event :player/queue-changed}})
      (is (nil? (await-value refreshes 100)))
      (finally
        (async/close! emitter)
        (async/close! refreshes)
        (ev/close! bus)))))

(deftest system-includes-player-event-refresher
  (is (= player-events/PlayerEventRefreshComponent
         (get-in (system/system)
                 [:donut.system/defs
                  :fairy.box/components
                  :fairy.box.web/player-event-refresh]))))
