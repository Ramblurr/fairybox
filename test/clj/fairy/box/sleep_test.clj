(ns fairy.box.sleep-test
  (:require
   [clojure.core.async :as async]
   [clojure.test :refer [deftest is]]
   [fairy.box.sleep :as sleep]
   [jp.nijohando.event :as ev])
  (:import
   [java.lang AutoCloseable]
   [java.time ZonedDateTime]))

(defn- inert-scheduler []
  {:start! (fn [_tick-ms _callback]
             (reify AutoCloseable
               (close [_])))})

(defn- await-event
  ([channel]
   (await-event channel 1000))
  ([channel timeout-ms]
   (let [[event port] (async/alts!! [channel (async/timeout timeout-ms)])]
     (when (= channel port)
       event))))

(defn- timer-context [sleep-settings volume]
  (let [now_    (atom (ZonedDateTime/parse "2026-07-21T18:32:00+02:00"))
        bus     (ev/bus)
        db-conn (atom {:settings {:sleep sleep-settings}})
        timer   (sleep/start-timer!
                 {:bus               bus
                  :db-conn           db-conn
                  :now-fn            #(deref now_)
                  :scheduler         (inert-scheduler)
                  :current-volume-fn (constantly volume)})]
    {:bus     bus
     :db-conn db-conn
     :now_    now_
     :timer   timer}))

(defn- stop-context! [{:keys [bus timer]}]
  (sleep/stop-timer! timer)
  (ev/close! bus))

(deftest schedules-ticks-with-chime
  (let [start-chime! (ns-resolve 'fairy.box.sleep 'start-chime!)
        chimed_      (promise)]
    (with-open [^AutoCloseable _schedule
                (start-chime! 10 #(deliver chimed_ true))]
      (is (true? (deref chimed_ 1000 false))))))

(deftest cycles-supported-durations-and-retains-a-disabled-selection
  (let [{:keys [timer] :as context} (timer-context {} 50)]
    (try
      (sleep/cycle! timer :next)
      (let [selected (select-keys (sleep/current timer)
                                  [:enabled? :selected-minutes])]
        (sleep/enable! timer)
        (sleep/cycle! timer :previous)
        (let [off (select-keys (sleep/current timer)
                               [:enabled? :selected-minutes])]
          (sleep/enable! timer)
          (sleep/disable! timer)
          (is (= {:options  [nil 2 5 10 15 20 30 45 60 120 180 240]
                  :labels   ["Off" "2 minutes" "5 minutes" "10 minutes"
                             "15 minutes" "20 minutes" "30 minutes"
                             "45 minutes" "1 hour" "2 hours" "3 hours"
                             "4 hours"]
                  :selected {:enabled? false :selected-minutes 2}
                  :off      {:enabled? false :selected-minutes nil}
                  :disabled {:enabled? false :selected-minutes 30}}
                 {:options  sleep/duration-options
                  :labels   (mapv sleep/format-duration
                                  sleep/duration-options)
                  :selected selected
                  :off      off
                  :disabled (select-keys (sleep/current timer)
                                         [:enabled? :selected-minutes])}))))
      (finally
        (stop-context! context)))))

(deftest fades-in-twelve-steps-stops-and-does-not-restore-on-cancel
  (let [{:keys [bus now_ timer] :as context}
        (timer-context {:shutdown? false :shutdown-delay-minutes 1} 72)
        player-events (async/chan 20)
        system-events (async/chan 1)]
    (ev/listen bus "/player/commands" player-events)
    (ev/listen bus "/system" system-events)
    (try
      (sleep/cycle! timer :next)
      (sleep/enable! timer)
      (let [^ZonedDateTime start @now_
            fade-events
            (mapv (fn [step]
                    (reset! now_ (.plusSeconds start (* 10 step)))
                    (sleep/tick! timer)
                    (:value (await-event player-events)))
                  (range 1 13))]
        (is (= {:fade-events
                [{:action :audio/sleep-fade-step :volume 66 :stop? false}
                 {:action :audio/sleep-fade-step :volume 60 :stop? false}
                 {:action :audio/sleep-fade-step :volume 54 :stop? false}
                 {:action :audio/sleep-fade-step :volume 48 :stop? false}
                 {:action :audio/sleep-fade-step :volume 42 :stop? false}
                 {:action :audio/sleep-fade-step :volume 36 :stop? false}
                 {:action :audio/sleep-fade-step :volume 30 :stop? false}
                 {:action :audio/sleep-fade-step :volume 24 :stop? false}
                 {:action :audio/sleep-fade-step :volume 18 :stop? false}
                 {:action :audio/sleep-fade-step :volume 12 :stop? false}
                 {:action :audio/sleep-fade-step :volume 6 :stop? false}
                 {:action :audio/sleep-fade-step :volume 0 :stop? true}]
                :final-state {:enabled?         false
                              :phase            :off
                              :selected-minutes 2}
                :poweroff    nil}
               {:fade-events fade-events
                :final-state (select-keys (sleep/current timer)
                                          [:enabled? :phase
                                           :selected-minutes])
                :poweroff    (await-event system-events 50)})))
      (sleep/enable! timer)
      (let [^ZonedDateTime start @now_]
        (reset! now_ (.plusSeconds start 10))
        (sleep/tick! timer)
        (let [reduced (:value (await-event player-events))]
          (sleep/disable! timer)
          (reset! now_ (.plusMinutes start 3))
          (sleep/tick! timer)
          (is (= {:reduced      {:action :audio/sleep-fade-step
                                 :volume 66
                                 :stop?  false}
                  :after-cancel nil
                  :state        {:enabled?         false
                                 :phase            :off
                                 :selected-minutes 2}}
                 {:reduced      reduced
                  :after-cancel (await-event player-events 50)
                  :state        (select-keys (sleep/current timer)
                                             [:enabled? :phase
                                              :selected-minutes])}))))
      (finally
        (async/close! player-events)
        (async/close! system-events)
        (stop-context! context)))))

(deftest waits-the-configured-delay-before-optional-poweroff
  (let [{:keys [bus db-conn now_ timer] :as context}
        (timer-context {:shutdown? true :shutdown-delay-minutes 1} 40)
        player-events (async/chan 2)
        system-events (async/chan 2)]
    (ev/listen bus "/player/commands" player-events)
    (ev/listen bus "/system" system-events)
    (try
      (sleep/cycle! timer :next)
      (sleep/enable! timer)
      (let [^ZonedDateTime fade-deadline
            (:fade-deadline (sleep/current timer))]
        (reset! now_ fade-deadline)
        (sleep/tick! timer)
        (let [fade-event (:value (await-event player-events))
              waiting    (select-keys (sleep/current timer)
                                      [:enabled? :phase :remaining-ms
                                       :fade-at :shutdown-at])]
          (reset! now_ (.plusSeconds fade-deadline 59))
          (sleep/tick! timer)
          (let [early-poweroff (await-event system-events 50)]
            (reset! now_ (.plusMinutes fade-deadline 1))
            (sleep/tick! timer)
            (let [poweroff (:value (await-event system-events))]
              (sleep/enable! timer)
              (let [^ZonedDateTime next-deadline
                    (:fade-deadline (sleep/current timer))]
                (swap! db-conn assoc-in
                       [:settings :sleep :shutdown-delay-minutes]
                       0)
                (reset! now_ next-deadline)
                (sleep/tick! timer)
                (let [immediate-poweroff (:value (await-event system-events))]
                  (is (= {:fade-event          {:action :audio/sleep-fade-step
                                                :volume 0
                                                :stop?  true}
                          :waiting             {:enabled?     true
                                                :phase        :shutdown-wait
                                                :remaining-ms 0
                                                :fade-at      "18:34"
                                                :shutdown-at  "18:35"}
                          :early-poweroff      nil
                          :poweroff            {:event  :system/poweroff-now
                                                :reason :sleep}
                          :zero-delay-poweroff {:event  :system/poweroff-now
                                                :reason :sleep}}
                         {:fade-event          fade-event
                          :waiting             waiting
                          :early-poweroff      early-poweroff
                          :poweroff            poweroff
                          :zero-delay-poweroff immediate-poweroff}))))))))
      (finally
        (async/close! player-events)
        (async/close! system-events)
        (stop-context! context)))))
