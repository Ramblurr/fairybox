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

(deftest routes-direct-and-animated-values-through-limit
  (let [writes_ (atom [])
        handles {:status {:name :status :handle ::fake :led-type :pwm}}]
    (with-redefs [led/led-value! (fn [handle value]
                                   (swap! writes_ conj [(:name handle) value]))]
      (let [controller (led/output-controller handles)]
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
               @writes_))))))

(deftest publishes-applied-values-outside-controller-lock
  (let [controller  (led/output-controller
                     (led/virtual-handles
                      [{:name :status :led-type :pwm}]))
        deliveries_ (atom [])
        mutation_   (promise)
        first-set?  (atom true)]
    (try
      (led/subscribe!
       controller
       ::observer
       (fn [values]
         (swap! deliveries_ conj values)
         (when (and (= 1.0 (:status values))
                    (compare-and-set! first-set? true false))
           (deliver mutation_
                    (deref (future
                             (led/set-led! controller :status 0.5))
                           500
                           ::timeout)))))
      (led/set-led! controller :status 1.0)
      (is (= {:mutation-result 0.5
              :current-values  {:status 0.5}
              :deliveries      [{:status 0.0}
                                {:status 1.0}
                                {:status 0.5}]}
             {:mutation-result (deref mutation_ 1000 ::timeout)
              :current-values  (led/current-values controller)
              :deliveries      @deliveries_}))
      (finally
        (led/stop-controller! controller)))))

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
        bus     (ev/bus)]
    (try
      (with-redefs [led/open-handles!
                    (fn [_]
                      (throw (ex-info "Virtual LEDs opened GPIO" {})))]
        (let [instance    (led/start-component!
                           {:hardware-enablement {:leds false}
                            :bus                 bus
                            :leds                [{:name     :status
                                                   :led-type :pwm}]
                            :groups              {}
                            :playback-limits     policy})
              controller  (:controller instance)
              deliveries_ (atom [])]
          (try
            (led/subscribe! controller ::observer
                            #(swap! deliveries_ conj %))
            (led/set-led! controller :status 1.0)
            (swap! db-conn assoc-in
                   [:settings :audio :max-led-brightness-night]
                   50)
            (led/unsubscribe! controller ::observer)
            (let [after-unsubscribe @deliveries_]
              (led/set-led! controller :status 0.1)
              (is (= {:enabled?              false
                      :gpio-handles          {}
                      :configured-leds       #{:status}
                      :current-values        {:status 0.1}
                      :deliveries            [{:status 0.0}
                                              {:status 0.2}
                                              {:status 0.5}]
                      :post-unsubscribe-push false}
                     {:enabled?              (:enabled? instance)
                      :gpio-handles          (:leds instance)
                      :configured-leds       (set (get-in instance
                                                          [:groups :all]))
                      :current-values        (led/current-values controller)
                      :deliveries            after-unsubscribe
                      :post-unsubscribe-push (not= after-unsubscribe
                                                   @deliveries_)})))
            (finally
              (led/stop-component! instance)))))
      (finally
        (stop-policy! policy)
        (ev/close! bus)))))
