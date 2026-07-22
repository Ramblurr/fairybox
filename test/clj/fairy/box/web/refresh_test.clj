(ns fairy.box.web.refresh-test
  (:require
   [clojure.core.async :as async]
   [clojure.test :refer [deftest is]]
   [fairy.box.hardware.led :as led]
   [fairy.box.web.refresh :as refresh]
   [jp.nijohando.event :as ev]))

(defn- await-value [channel timeout-ms]
  (let [[value port] (async/alts!! [channel (async/timeout timeout-ms)])]
    (when (= port channel)
      value)))

(defn- emit! [emitter path value]
  (async/>!! emitter {:path path :value value}))

(deftest filters-refresh-worthy-events
  (let [accepted [{:path  "/player/events"
                   :value {:event :player/time-changed}}
                  {:path  "/player/events"
                   :value {:event :player/current-track-changed}}
                  {:path  "/sleep/events"
                   :value {:event :sleep/changed}}
                  {:path  "/auto-shutdown/events"
                   :value {:event :auto-shutdown/changed}}
                  {:path  "/tts/events"
                   :value {:event    :tts/catalog-refresh-started
                           :provider :google-cloud}}
                  {:path  "/tts/events"
                   :value {:event    :tts/catalog-updated
                           :provider :google-cloud}}
                  {:path  "/tts/events"
                   :value {:event    :tts/catalog-refresh-failed
                           :provider :google-cloud}}
                  {:path  "/hardware/output/leds/events"
                   :value {:event  :led/changed
                           :values {:audio/play-pause 1.0}}}
                  {:path  "/hardware/input/rfid"
                   :value {:action :placed :uid "tag-1"}}
                  {:path  "/hardware/input/rfid"
                   :value {:action :removed :uid "tag-1"}}
                  {:path  "/system"
                   :value {:event :system/ready}}]
        rejected [{:path  "/player/events"
                   :value {:event :player/one-shot-finished}}
                  {:path  "/sleep/events"
                   :value {:event :sleep/tick}}
                  {:path  "/auto-shutdown/events"
                   :value {:event :auto-shutdown/tick}}
                  {:path  "/tts/events"
                   :value {:event    :tts/catalog-refresh-queued
                           :provider :google-cloud}}
                  {:path  "/tts/events"
                   :value {:event    :tts/catalog-refresh-skipped
                           :provider :google-cloud}}
                  {:path  "/hardware/output/leds/events"
                   :value {:event :led/unknown}}
                  {:path  "/hardware/input/rfid"
                   :value {:action :read-error}}
                  {:path  "/system"
                   :value {:event :system/unknown}}
                  {:path  "/unrelated"
                   :value {:event :player/time-changed}}]]
    (is (= {:accepted (repeat (count accepted) true)
            :rejected (repeat (count rejected) false)}
           {:accepted (map refresh/refresh-event? accepted)
            :rejected (map refresh/refresh-event? rejected)}))))

(deftest subscribes-to-each-refresh-event-path
  (let [bus       (ev/bus)
        emitter   (async/chan 4)
        refreshes (async/chan 4)
        component (refresh/start-refresh!
                   {:bus         bus
                    :db-conn     (atom {})
                    :interval-ms 5
                    :refresh!    #(async/>!! refreshes :refreshed)})
        events    [["/player/events" {:event :player/time-changed}]
                   ["/hardware/output/leds/events" {:event :led/changed}]
                   ["/sleep/events" {:event :sleep/changed}]
                   ["/auto-shutdown/events"
                    {:event :auto-shutdown/changed}]
                   ["/tts/events"
                    {:event    :tts/catalog-refresh-started
                     :provider :google-cloud}]
                   ["/system" {:event :system/ready}]]]
    (try
      (ev/emitize bus emitter)
      (is (= (repeat (count events) :refreshed)
             (mapv (fn [[path value]]
                     (emit! emitter path value)
                     (await-value refreshes 1000))
                   events)))
      (finally
        (refresh/stop-refresh! component)
        (async/close! emitter)
        (async/close! refreshes)
        (ev/close! bus)))))

(deftest exposes-current-event-only-while-rendering
  (let [bus        (ev/bus)
        emitter    (async/chan 2)
        observed   (async/chan 1)
        cleared    (promise)
        component_ (atom nil)
        component  (refresh/start-refresh!
                    {:bus         bus
                     :db-conn     (atom {})
                     :interval-ms 5
                     :refresh!
                     #(async/>!! observed
                                 (refresh/current-event @component_))})
        event      {:path  "/tts/events"
                    :value {:event     :tts/catalog-refresh-started
                            :operation :refresh
                            :provider  :google-cloud}}]
    (reset! component_ component)
    (add-watch (:current-event_ component) ::cleared
               (fn [_ _ previous current]
                 (when (and (some? previous) (nil? current))
                   (deliver cleared true))))
    (try
      (ev/emitize bus emitter)
      (emit! emitter (:path event) (:value event))
      (let [during-render (some-> (await-value observed 1000)
                                  (select-keys [:path :value]))
            cleared?      (deref cleared 1000 false)
            after-render  (refresh/current-event component)]
        (is (= {:during-render event
                :cleared?      true
                :after-render  nil}
               {:during-render during-render
                :cleared?      cleared?
                :after-render  after-render})))
      (finally
        (remove-watch (:current-event_ component) ::cleared)
        (refresh/stop-refresh! component)
        (async/close! emitter)
        (async/close! observed)
        (ev/close! bus)))))

(deftest throttles-led-change-refreshes
  (let [bus        (ev/bus)
        emitter    (async/chan)
        refreshes  (async/chan 4)
        controller (led/output-controller
                    (led/virtual-handles
                     [{:name :audio/play-pause :led-type :pwm}])
                    emitter)
        component  (refresh/start-refresh!
                    {:bus      bus
                     :db-conn  (atom {})
                     :refresh! #(async/>!! refreshes (System/nanoTime))})]
    (try
      (ev/emitize bus emitter)
      (led/set-led! controller :audio/play-pause 1.0)
      (let [first-refresh (await-value refreshes 1000)]
        (led/set-led! controller :audio/play-pause 0.0)
        (led/set-led! controller :audio/play-pause 1.0)
        (led/set-led! controller :audio/play-pause 0.0)
        (let [premature-refresh (await-value refreshes 150)
              second-refresh    (or premature-refresh
                                    (await-value refreshes 350))
              elapsed-ms        (when (and first-refresh second-refresh)
                                  (quot (- second-refresh first-refresh)
                                        1000000))]
          (is (= {:first-refresh?     true
                  :premature-refresh? false
                  :elapsed-ms-valid?  true
                  :final-value        0.0}
                 {:first-refresh?     (some? first-refresh)
                  :premature-refresh? (some? premature-refresh)
                  :elapsed-ms-valid?  (and elapsed-ms
                                           (<= 200 elapsed-ms 450))
                  :final-value        (get (led/current-values controller)
                                           :audio/play-pause)}))))
      (finally
        (refresh/stop-refresh! component)
        (led/stop-controller! controller)
        (async/close! emitter)
        (async/close! refreshes)
        (ev/close! bus)))))

(deftest updates-rfid-presence-before-refreshing
  (let [bus        (ev/bus)
        emitter    (async/chan 4)
        refreshes  (async/chan 2)
        component_ (atom nil)
        component  (refresh/start-refresh!
                    {:bus         bus
                     :db-conn     (atom {})
                     :interval-ms 5
                     :refresh!
                     #(let [instance @component_]
                        (async/>!!
                         refreshes
                         {:state @(:rfid-presence instance)
                          :uid   (refresh/current-uid instance)}))})]
    (reset! component_ component)
    (try
      (ev/emitize bus emitter)
      (emit! emitter "/hardware/input/rfid"
             {:action :read-error :uid "ignored"})
      (is (nil? (await-value refreshes 50)))
      (emit! emitter "/hardware/input/rfid"
             {:action :placed :uid "tag-2" :at 1})
      (is (= {:state {:action :placed :uid "tag-2" :at 1}
              :uid   "tag-2"}
             (await-value refreshes 1000)))
      (emit! emitter "/hardware/input/rfid"
             {:action :removed :uid "tag-2" :at 2})
      (is (= {:state {:action :removed :uid "tag-2" :at 2}
              :uid   nil}
             (await-value refreshes 1000)))
      (finally
        (refresh/stop-refresh! component)
        (async/close! emitter)
        (async/close! refreshes)
        (ev/close! bus)))))

(deftest database-watch-enqueues-only-real-changes
  (let [bus       (ev/bus)
        database  (atom {})
        refreshes (async/chan 2)
        component (refresh/start-refresh!
                   {:bus         bus
                    :db-conn     database
                    :interval-ms 5
                    :refresh!    #(async/>!! refreshes :refreshed)})]
    (try
      (swap! database assoc :setting :changed)
      (is (= :refreshed (await-value refreshes 1000)))
      (swap! database identity)
      (is (nil? (await-value refreshes 50)))
      (finally
        (refresh/stop-refresh! component)
        (async/close! refreshes)
        (ev/close! bus)))))

(deftest coalesces-a-burst-while-rendering
  (let [bus       (ev/bus)
        calls_    (atom 0)
        completed (async/chan 3)
        started   (promise)
        release   (promise)
        component (refresh/start-refresh!
                   {:bus         bus
                    :db-conn     (atom {})
                    :interval-ms 50
                    :refresh!
                    (fn []
                      (let [call (swap! calls_ inc)]
                        (when (= call 1)
                          (deliver started true)
                          @release)
                        (async/>!! completed call)))})]
    (try
      (refresh/request! component)
      (is (true? (deref started 1000 false)))
      (dotimes [_ 128]
        (refresh/request! component))
      (deliver release true)
      (is (= [1 2]
             [(await-value completed 1000)
              (await-value completed 1000)]))
      (is (nil? (await-value completed 100)))
      (is (= 2 @calls_))
      (finally
        (deliver release true)
        (refresh/stop-refresh! component)
        (async/close! completed)
        (ev/close! bus)))))

(deftest serializes-refresh-calls
  (let [bus       (ev/bus)
        active_   (atom 0)
        peak_     (atom 0)
        calls_    (atom 0)
        starts    (async/chan 2)
        release   (promise)
        component (refresh/start-refresh!
                   {:bus         bus
                    :db-conn     (atom {})
                    :interval-ms 5
                    :refresh!
                    (fn []
                      (let [call   (swap! calls_ inc)
                            active (swap! active_ inc)]
                        (swap! peak_ max active)
                        (async/>!! starts call)
                        (try
                          (when (= call 1)
                            @release)
                          (finally
                            (swap! active_ dec)))))})]
    (try
      (refresh/request! component)
      (is (= 1 (await-value starts 1000)))
      (Thread/sleep 20)
      (refresh/request! component)
      (is (nil? (await-value starts 50)))
      (is (= {:calls 1 :active 1 :peak 1}
             {:calls @calls_ :active @active_ :peak @peak_}))
      (deliver release true)
      (is (= 2 (await-value starts 1000)))
      (is (= 1 @peak_))
      (finally
        (deliver release true)
        (refresh/stop-refresh! component)
        (async/close! starts)
        (ev/close! bus)))))

(deftest shutdown-removes-all-refresh-sources-and-stops-worker
  (let [bus       (ev/bus)
        emitter   (async/chan 2)
        database  (atom {})
        calls_    (atom 0)
        component (refresh/start-refresh!
                   {:bus         bus
                    :db-conn     database
                    :interval-ms 5
                    :refresh!    #(swap! calls_ inc)})]
    (ev/emitize bus emitter)
    (refresh/stop-refresh! component)
    (emit! emitter "/system" {:event :system/ready})
    (swap! database assoc :after-stop true)
    (refresh/request! component)
    (Thread/sleep 50)
    (try
      (is (= {:calls             0
              :watch-removed?    true
              :requests-closed?  true
              :throttled-closed? true}
             {:calls @calls_
              :watch-removed?
              (not (contains? (.getWatches ^clojure.lang.ARef database)
                              (:watch-key component)))
              :requests-closed?
              (false? (async/offer! (:requests component) ::late))
              :throttled-closed?
              (false? (async/offer! (:throttled component) ::late))}))
      (finally
        (async/close! emitter)
        (ev/close! bus)))))

(deftest continues-after-refresh-errors
  (let [bus       (ev/bus)
        attempts_ (atom 0)
        attempts  (async/chan 2)
        component (refresh/start-refresh!
                   {:bus         bus
                    :db-conn     (atom {})
                    :interval-ms 5
                    :refresh!
                    (fn []
                      (let [attempt (swap! attempts_ inc)]
                        (async/>!! attempts attempt)
                        (when (= attempt 1)
                          (throw (ex-info "refresh failed" {})))))})]
    (try
      (refresh/request! component)
      (is (= 1 (await-value attempts 1000)))
      (refresh/request! component)
      (is (= 2 (await-value attempts 1000)))
      (finally
        (refresh/stop-refresh! component)
        (async/close! attempts)
        (ev/close! bus)))))
