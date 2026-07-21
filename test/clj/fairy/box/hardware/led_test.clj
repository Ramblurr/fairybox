(ns fairy.box.hardware.led-test
  (:require
   [clojure.core.async :as async]
   [clojure.test :refer [deftest is]]
   [donut.system :as ds]
   [fairy.box.hardware.led :as led]
   [fairy.box.playback-limits :as limits]
   [jp.nijohando.event :as ev])
  (:import
   [java.time ZonedDateTime]))

(defn- await-value [channel timeout-ms]
  (let [[value port] (async/alts!! [channel (async/timeout timeout-ms)])]
    (when (= port channel)
      value)))

(deftest routes-direct-and-animated-values-through-limit
  (let [writes_ (atom [])
        handles {:status {:name :status :handle ::fake :led-type :pwm}}
        emitter (async/chan 8)]
    (with-redefs [led/led-value! (fn [handle value]
                                   (swap! writes_ conj [(:name handle) value]))]
      (let [controller (led/output-controller handles emitter)]
        (try
          (led/refresh-limit! controller 0.2)
          (reset! writes_ [])
          (led/events-handler! {:controller controller :groups {}}
                               {:value {:action :led/set
                                        :names  [:status]
                                        :groups []
                                        :value  1.0}})
          (led/apply-tween! controller {:value 0.9 :data [:status]})
          (led/refresh-limit! controller 0.7)
          (led/set-led! controller :unknown 1.0)
          (led/refresh-limit! controller 0.3)
          (is (= [[:status 0.2]
                  [:status 0.2]
                  [:status 0.7]
                  [:status 0.3]]
                 @writes_))
          (finally
            (led/stop-controller! controller)
            (async/close! emitter)))))))

(deftest publishes-applied-values-on-event-bus
  (let [bus        (ev/bus)
        emitter    (async/chan)
        changes    (async/chan 3)
        controller (led/output-controller
                    (led/virtual-handles
                     [{:name :status :led-type :pwm}])
                    emitter)]
    (try
      (ev/emitize bus emitter)
      (ev/listen bus led/led-events-path changes)
      (led/set-led! controller :status 1.0)
      (let [first-event (await-value changes 1000)]
        (led/set-led! controller :status 1.0)
        (let [duplicate-event (await-value changes 50)]
          (led/refresh-limit! controller 0.5)
          (is (= {:first-event
                  {:path  "/hardware/output/leds/events"
                   :value {:event  :led/changed
                           :values {:status 1.0}}}
                  :duplicate-event nil
                  :limited-event
                  {:path  "/hardware/output/leds/events"
                   :value {:event  :led/changed
                           :values {:status 0.5}}}
                  :current-values  {:status 0.5}}
                 {:first-event     (select-keys first-event [:path :value])
                  :duplicate-event duplicate-event
                  :limited-event   (some-> (await-value changes 1000)
                                           (select-keys [:path :value]))
                  :current-values  (led/current-values controller)}))))
      (finally
        (led/stop-controller! controller)
        (async/close! emitter)
        (async/close! changes)
        (ev/close! bus)))))

(defn- test-policy [db-conn]
  (limits/start-policy!
   {:db-conn   db-conn
    :now-fn    #(ZonedDateTime/parse "2025-01-15T20:00:00+01:00")
    :scheduler {:schedule! (fn [_ _] ::pending)
                :cancel!   (constantly nil)
                :shutdown! (constantly nil)}}))

(defn- stop-policy! [policy]
  ((::ds/stop limits/PlaybackLimitsComponent)
   {::ds/instance policy}))

(deftest enabled-component-unsubscribes-before-release
  (let [events_ (atom [])
        db-conn (atom {:settings
                       {:audio {:max-volume               95
                                :max-volume-day           80
                                :max-volume-night         50
                                :max-led-brightness-day   75
                                :max-led-brightness-night 20
                                :day-start                "08:00"
                                :night-start              "19:00"}}})
        policy  (test-policy db-conn)
        bus     (ev/bus)
        handles {:status {:name :status :handle ::fake :led-type :pwm}}]
    (try
      (with-redefs [led/open-handles! (constantly handles)
                    led/led-value!    (fn [handle value]
                                        (swap! events_ conj
                                               [:write (:name handle) value]))
                    led/release-led!  (fn [handle]
                                        (swap! events_ conj
                                               [:release (:name handle)]))]
        (let [instance (led/start-component!
                        {:hardware-enablement {:leds true}
                         :bus                 bus
                         :leds                [{:name :status}]
                         :groups              {}
                         :playback-limits     policy})]
          (led/set-led! (:controller instance) :status 1.0)
          (led/stop-component! instance)
          (let [after-stop @events_]
            (swap! db-conn assoc-in
                   [:settings :audio :max-led-brightness-night]
                   10)
            (is (= {:events           [[:write :status 0.0]
                                       [:write :status 0.2]
                                       [:release :status]]
                    :post-stop-write? false}
                   {:events           after-stop
                    :post-stop-write? (not= after-stop @events_)})))))
      (finally
        (stop-policy! policy)
        (ev/close! bus)))))

(deftest stop-cancels-live-animation-before-releasing-handles
  (let [events_ (atom [])
        db-conn (atom {:settings
                       {:audio {:max-volume               95
                                :max-volume-day           80
                                :max-volume-night         50
                                :max-led-brightness-day   75
                                :max-led-brightness-night 20
                                :day-start                "08:00"
                                :night-start              "19:00"}}})
        policy  (test-policy db-conn)
        bus     (ev/bus)
        handles {:status {:name :status :handle ::fake :led-type :pwm}}]
    (try
      (with-redefs [led/open-handles! (constantly handles)
                    led/led-value!    (fn [handle value]
                                        (swap! events_ conj
                                               [:write (:name handle) value]))
                    led/release-led!  (fn [handle]
                                        (swap! events_ conj
                                               [:release (:name handle)]))]
        (let [instance (led/start-component!
                        {:hardware-enablement {:leds true}
                         :bus                 bus
                         :leds                [{:name :status}]
                         :groups              {}
                         :playback-limits     policy})
              waiter   (led/events-handler!
                        instance
                        {:value {:action       :led/pulse
                                 :names        [:status]
                                 :groups       []
                                 :repeat-times 100
                                 :after-set    1.0}})]
          (led/stop-component! instance)
          (let [[_ port]   (async/alts!! [waiter (async/timeout 1000)])
                after-stop @events_]
            (led/set-led! (:controller instance) :status 1.0)
            (led/refresh-limit! (:controller instance) 0.5)
            (swap! db-conn assoc-in
                   [:settings :audio :max-led-brightness-night]
                   10)
            (is (= {:animation-stopped? true
                    :release-is-last?   true
                    :post-stop-write?   false}
                   {:animation-stopped? (= port waiter)
                    :release-is-last?   (= [:release :status]
                                           (last after-stop))
                    :post-stop-write?   (not= after-stop @events_)})))))
      (finally
        (stop-policy! policy)
        (ev/close! bus)))))

(deftest virtual-component-tracks-limited-values-without-opening-gpio
  (let [db-conn (atom {:settings
                       {:audio {:max-volume               95
                                :max-volume-day           80
                                :max-volume-night         50
                                :max-led-brightness-day   75
                                :max-led-brightness-night 20
                                :day-start                "08:00"
                                :night-start              "19:00"}}})
        policy  (test-policy db-conn)
        bus     (ev/bus)
        changes (async/chan 4)]
    (try
      (ev/listen bus led/led-events-path changes)
      (with-redefs [led/open-handles!
                    (fn [_]
                      (throw (ex-info "Virtual LEDs opened GPIO" {})))]
        (let [instance   (led/start-component!
                          {:hardware-enablement {:leds false}
                           :bus                 bus
                           :leds                [{:name     :status
                                                  :led-type :pwm}]
                           :groups              {}
                           :playback-limits     policy})
              controller (:controller instance)]
          (try
            (led/set-led! controller :status 1.0)
            (let [limited-event (await-value changes 1000)]
              (swap! db-conn assoc-in
                     [:settings :audio :max-led-brightness-night]
                     50)
              (let [policy-event (await-value changes 1000)]
                (led/set-led! controller :status 0.1)
                (is (= {:enabled?        false
                        :gpio-handles    {}
                        :configured-leds #{:status}
                        :current-values  {:status 0.1}
                        :events
                        [{:event :led/changed :values {:status 0.2}}
                         {:event :led/changed :values {:status 0.5}}
                         {:event :led/changed :values {:status 0.1}}]}
                       {:enabled?        (:enabled? instance)
                        :gpio-handles    (:leds instance)
                        :configured-leds (set (get-in instance [:groups :all]))
                        :current-values  (led/current-values controller)
                        :events          (mapv :value
                                               [limited-event
                                                policy-event
                                                (await-value changes 1000)])}))))
            (finally
              (led/stop-component! instance)))))
      (finally
        (stop-policy! policy)
        (async/close! changes)
        (ev/close! bus)))))
