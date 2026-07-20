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

(deftest refreshes-for-all-player-state-including-time-and-position
  (let [bus (ev/bus)
        emitter (async/chan 16)
        refreshes (async/chan 16)
        refresher (player-events/start-player-refresh!
                   {:bus bus
                    :refresh! #(async/>!! refreshes :refreshed)})
        state-events
        [:player/state-changed
         :player/current-track-changed
         :player/muted
         :player/volume-changed
         :player/repeat-changed
         :player/shuffle-changed
         :player/queue-changed
         :player/time-changed
         :player/position-changed]]
    (try
      (ev/emitize bus emitter)
      (let [state-refreshes
            (mapv (fn [event]
                    (async/>!! emitter
                               {:path "/player/events"
                                :value {:event event}})
                    (await-value refreshes 1000))
                  state-events)]
        (async/>!! emitter
                   {:path "/player/events"
                    :value {:event :player/one-shot-finished}})
        (is (= {:state-refreshes
                (vec (repeat (count state-events) :refreshed))
                :unrelated-refresh nil}
               {:state-refreshes state-refreshes
                :unrelated-refresh (await-value refreshes 100)})))
      (finally
        (player-events/stop-player-refresh! refresher)
        (async/close! emitter)
        (async/close! refreshes)
        (ev/close! bus)))))

(deftest stopped-refresher-does-not-refresh
  (let [bus (ev/bus)
        emitter (async/chan 1)
        refreshes (async/chan 1)
        refresher (player-events/start-player-refresh!
                   {:bus bus
                    :refresh! #(async/>!! refreshes :refreshed)})]
    (try
      (ev/emitize bus emitter)
      (player-events/stop-player-refresh! refresher)
      (async/>!! emitter
                 {:path "/player/events"
                  :value {:event :player/queue-changed}})
      (is (nil? (await-value refreshes 100)))
      (finally
        (async/close! emitter)
        (async/close! refreshes)
        (ev/close! bus)))))

(deftest stops-refresher-started-before-throttling-was-added
  (let [listener (async/chan)]
    (try
      (is (= :stopped
             (try
               (player-events/stop-player-refresh!
                {:listener listener :worker nil})
               :stopped
               (catch Exception _
                 :threw))))
      (finally
        (async/close! listener)))))

(deftest throttles-player-refreshes-to-500-ms
  (let [bus (ev/bus)
        emitter (async/chan 2)
        refreshes (async/chan 2)
        refresher
        (player-events/start-player-refresh!
         {:bus bus
          :refresh! #(async/>!! refreshes (System/nanoTime))})]
    (try
      (ev/emitize bus emitter)
      (async/>!! emitter
                 {:path "/player/events"
                  :value {:event :player/time-changed}})
      (let [first-refresh (await-value refreshes 1000)]
        (async/>!! emitter
                   {:path "/player/events"
                    :value {:event :player/position-changed}})
        (let [premature-refresh (await-value refreshes 350)
              second-refresh (or premature-refresh
                                 (await-value refreshes 1000))
              elapsed-ms (when second-refresh
                           (quot (- second-refresh first-refresh)
                                 1000000))]
          (is (= {:premature-refresh? false
                  :elapsed-at-least-450-ms? true}
                 {:premature-refresh? (some? premature-refresh)
                  :elapsed-at-least-450-ms?
                  (and elapsed-ms (>= elapsed-ms 450))}))))
      (finally
        (player-events/stop-player-refresh! refresher)
        (async/close! emitter)
        (async/close! refreshes)
        (ev/close! bus)))))

(deftest coalesces-relevant-refreshes-without-blocking-the-event-bus
  (let [bus (ev/bus)
        emitter (async/chan)
        refreshes (async/chan 2)
        refresh-started (promise)
        release-refresh (promise)
        first-refresh? (atom true)
        refresher
        (player-events/start-player-refresh!
         {:bus bus
          :refresh! #(do
                       (when (compare-and-set! first-refresh? true false)
                         (deliver refresh-started true)
                         @release-refresh)
                       (async/>!! refreshes :refreshed))})
        producer
        (future
          (dotimes [i 128]
            (async/>!! emitter
                       {:path "/player/events"
                        :value
                        {:event (if (= i 127)
                                  :player/one-shot-finished
                                  :player/time-changed)}}))
          :finished)]
    (try
      (ev/emitize bus emitter)
      (is (true? (deref refresh-started 1000 false)))
      (let [result (deref producer 500 ::timeout)]
        (deliver release-refresh true)
        (is (= :finished result))
        (deref producer 1000 ::timeout))
      (is (= [:refreshed :refreshed]
             [(await-value refreshes 1000)
              (await-value refreshes 1000)]))
      (finally
        (deliver release-refresh true)
        (player-events/stop-player-refresh! refresher)
        (async/close! emitter)
        (async/close! refreshes)
        (ev/close! bus)))))

(deftest continues-refreshing-after-a-refresh-error
  (let [bus (ev/bus)
        emitter (async/chan 2)
        attempts (async/chan 2)
        attempt-number (atom 0)
        refresher
        (player-events/start-player-refresh!
         {:bus bus
          :refresh! #(let [attempt (swap! attempt-number inc)]
                       (async/>!! attempts attempt)
                       (when (= attempt 1)
                         (throw (ex-info "refresh failed" {}))))})]
    (try
      (ev/emitize bus emitter)
      (async/>!! emitter
                 {:path "/player/events"
                  :value {:event :player/time-changed}})
      (let [first-attempt (await-value attempts 1000)]
        (async/>!! emitter
                   {:path "/player/events"
                    :value {:event :player/position-changed}})
        (is (= [1 2]
               [first-attempt (await-value attempts 1000)])))
      (finally
        (player-events/stop-player-refresh! refresher)
        (async/close! emitter)
        (async/close! attempts)
        (ev/close! bus)))))

(deftest system-includes-player-event-refresher
  (is (= player-events/PlayerEventRefreshComponent
         (get-in (system/system)
                 [:donut.system/defs
                  :fairy.box/components
                  :fairy.box.web/player-event-refresh]))))
