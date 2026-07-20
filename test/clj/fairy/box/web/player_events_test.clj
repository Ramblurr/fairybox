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

(deftest classifies-progress-and-structural-events-separately
  (let [progress-event? (ns-resolve 'fairy.box.web.player-events
                                    'progress-event?)
        events [:player/time-changed
                :player/position-changed
                :player/state-changed
                :player/current-track-changed
                :player/muted
                :player/volume-changed
                :player/repeat-changed
                :player/shuffle-changed
                :player/queue-changed
                :player/one-shot-finished]]
    (is (= {:progress [true true false false false false false false false false]
            :refresh [false false true true true true true true true false]}
           {:progress (when progress-event?
                        (mapv #(boolean (progress-event? {:value {:event %}}))
                              events))
            :refresh (mapv #(player-events/refresh-event?
                             {:value {:event %}})
                           events)}))))

(deftest routes-progress-events-without-refreshing-the-page
  (let [bus (ev/bus)
        emitter (async/chan 4)
        progress-pushes (async/chan 4)
        refreshes (async/chan 4)
        refresher
        (player-events/start-player-refresh!
         {:bus bus
          :progress! #(async/>!! progress-pushes :progress)
          :refresh! #(async/>!! refreshes :refreshed)})]
    (try
      (ev/emitize bus emitter)
      (async/>!! emitter
                 {:path "/player/events"
                  :value {:event :player/time-changed}})
      (let [progress-result (await-value progress-pushes 1000)
            progress-refresh (await-value refreshes 100)]
        (async/>!! emitter
                   {:path "/player/events"
                    :value {:event :player/state-changed}})
        (is (= {:progress-event {:progress progress-result
                                 :refresh progress-refresh}
                :structural-event
                {:progress (await-value progress-pushes 100)
                 :refresh (await-value refreshes 1000)}}
               {:progress-event {:progress :progress
                                 :refresh nil}
                :structural-event {:progress nil
                                   :refresh :refreshed}})))
      (finally
        (player-events/stop-player-refresh! refresher)
        (async/close! emitter)
        (async/close! progress-pushes)
        (async/close! refreshes)
        (ev/close! bus)))))

(deftest ignores-unrelated-player-events
  (let [bus (ev/bus)
        emitter (async/chan 1)
        progress-pushes (async/chan 1)
        refreshes (async/chan 1)
        refresher
        (player-events/start-player-refresh!
         {:bus bus
          :progress! #(async/>!! progress-pushes :progress)
          :refresh! #(async/>!! refreshes :refreshed)})]
    (try
      (ev/emitize bus emitter)
      (async/>!! emitter
                 {:path "/player/events"
                  :value {:event :player/one-shot-finished}})
      (is (= {:progress nil :refresh nil}
             {:progress (await-value progress-pushes 100)
              :refresh (await-value refreshes 100)}))
      (finally
        (player-events/stop-player-refresh! refresher)
        (async/close! emitter)
        (async/close! progress-pushes)
        (async/close! refreshes)
        (ev/close! bus)))))

(deftest stopped-refresher-does-not-push-or-refresh
  (let [bus (ev/bus)
        emitter (async/chan 2)
        progress-pushes (async/chan 1)
        refreshes (async/chan 1)
        refresher
        (player-events/start-player-refresh!
         {:bus bus
          :progress! #(async/>!! progress-pushes :progress)
          :refresh! #(async/>!! refreshes :refreshed)})]
    (try
      (ev/emitize bus emitter)
      (player-events/stop-player-refresh! refresher)
      (async/>!! emitter
                 {:path "/player/events"
                  :value {:event :player/time-changed}})
      (async/>!! emitter
                 {:path "/player/events"
                  :value {:event :player/queue-changed}})
      (is (= {:progress nil :refresh nil}
             {:progress (await-value progress-pushes 100)
              :refresh (await-value refreshes 100)}))
      (finally
        (async/close! emitter)
        (async/close! progress-pushes)
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

(deftest throttles-progress-pushes-to-250-ms
  (let [bus (ev/bus)
        emitter (async/chan 2)
        progress-pushes (async/chan 2)
        refresher
        (player-events/start-player-refresh!
         {:bus bus
          :progress! #(async/>!! progress-pushes (System/nanoTime))
          :refresh! (fn [])})]
    (try
      (ev/emitize bus emitter)
      (async/>!! emitter
                 {:path "/player/events"
                  :value {:event :player/time-changed}})
      (let [first-push (await-value progress-pushes 1000)]
        (async/>!! emitter
                   {:path "/player/events"
                    :value {:event :player/position-changed}})
        (let [premature-push (await-value progress-pushes 150)
              second-push (or premature-push
                              (await-value progress-pushes 350))
              elapsed-ms (when second-push
                           (quot (- second-push first-push)
                                 1000000))]
          (is (= {:premature-push? false
                  :elapsed-between-200-and-400-ms? true}
                 {:premature-push? (some? premature-push)
                  :elapsed-between-200-and-400-ms?
                  (and elapsed-ms (<= 200 elapsed-ms 400))}))))
      (finally
        (player-events/stop-player-refresh! refresher)
        (async/close! emitter)
        (async/close! progress-pushes)
        (ev/close! bus)))))

(deftest coalesces-page-refreshes-without-blocking-the-event-bus
  (let [bus (ev/bus)
        emitter (async/chan)
        refreshes (async/chan 2)
        refresh-started (promise)
        release-refresh (promise)
        first-refresh? (atom true)
        refresher
        (player-events/start-player-refresh!
         {:bus bus
          :progress! (fn [])
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
                                  :player/queue-changed)}}))
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

(deftest page-refresh-does-not-wait-for-a-progress-push
  (let [bus (ev/bus)
        emitter (async/chan 2)
        progress-started (promise)
        release-progress (promise)
        refreshes (async/chan 1)
        refresher
        (player-events/start-player-refresh!
         {:bus bus
          :progress! #(do
                        (deliver progress-started true)
                        @release-progress)
          :refresh! #(async/>!! refreshes :refreshed)})]
    (try
      (ev/emitize bus emitter)
      (async/>!! emitter
                 {:path "/player/events"
                  :value {:event :player/time-changed}})
      (is (true? (deref progress-started 1000 false)))
      (async/>!! emitter
                 {:path "/player/events"
                  :value {:event :player/state-changed}})
      (is (= :refreshed (await-value refreshes 1000)))
      (finally
        (deliver release-progress true)
        (player-events/stop-player-refresh! refresher)
        (async/close! emitter)
        (async/close! refreshes)
        (ev/close! bus)))))

(deftest continues-pushing-progress-after-an-error
  (let [bus (ev/bus)
        emitter (async/chan 2)
        attempts (async/chan 2)
        attempt-number (atom 0)
        refresher
        (player-events/start-player-refresh!
         {:bus bus
          :progress! #(let [attempt (swap! attempt-number inc)]
                        (async/>!! attempts attempt)
                        (when (= attempt 1)
                          (throw (ex-info "push failed" {}))))
          :refresh! (fn [])})]
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

(deftest continues-refreshing-after-a-refresh-error
  (let [bus (ev/bus)
        emitter (async/chan 2)
        attempts (async/chan 2)
        attempt-number (atom 0)
        refresher
        (player-events/start-player-refresh!
         {:bus bus
          :progress! (fn [])
          :refresh! #(let [attempt (swap! attempt-number inc)]
                       (async/>!! attempts attempt)
                       (when (= attempt 1)
                         (throw (ex-info "refresh failed" {}))))})]
    (try
      (ev/emitize bus emitter)
      (async/>!! emitter
                 {:path "/player/events"
                  :value {:event :player/state-changed}})
      (let [first-attempt (await-value attempts 1000)]
        (async/>!! emitter
                   {:path "/player/events"
                    :value {:event :player/queue-changed}})
        (is (= [1 2]
               [first-attempt (await-value attempts 1000)])))
      (finally
        (player-events/stop-player-refresh! refresher)
        (async/close! emitter)
        (async/close! attempts)
        (ev/close! bus)))))

(deftest system-includes-player-event-and-progress-components
  (let [components (get-in (system/system)
                           [:donut.system/defs
                            :fairy.box/components])
        progress-component
        (try
          (some-> (requiring-resolve
                   'fairy.box.web.player-progress/ProgressStreamComponent)
                  var-get)
          (catch Throwable _
            nil))]
    (is (= {:event-component player-events/PlayerEventRefreshComponent
            :progress-component progress-component
            :progress-stream-ref
            [:donut.system/ref
             [:fairy.box/components :fairy.box.web/player-progress]]}
           {:event-component
            (:fairy.box.web/player-event-refresh components)
            :progress-component
            (:fairy.box.web/player-progress components)
            :progress-stream-ref
            (get-in player-events/PlayerEventRefreshComponent
                    [:donut.system/config :progress-stream])}))))
