(ns fairy.box.timers-test
  (:require
   [clojure.core.async :as async]
   [clojure.test :refer [deftest is]]
   [fairy.box.timers :as sleep]
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
        timer   (sleep/start-sleep-timer!
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
  (let [start-chime! (ns-resolve 'fairy.box.timers
                                 'start-periodic-chime!)
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
                  :labels   (mapv sleep/duration-label
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

(defn- recording-scheduler [started closed]
  {:start!
   (fn [deadline callback]
     (let [closed?_ (atom false)
           schedule {:deadline deadline
                     :callback callback
                     :closed?_ closed?_}]
       (async/put! started schedule)
       (reify AutoCloseable
         (close [_]
           (when (compare-and-set! closed?_ false true)
             (async/put! closed schedule))))))})

(defn- auto-shutdown-context
  [{:keys [settings ready? audio-active?]
    :or   {settings      {:enabled? false :duration-minutes 30}
           ready?        true
           audio-active? false}}]
  (let [now_          (atom (ZonedDateTime/parse
                             "2026-07-22T10:00:00+02:00"))
        bus           (ev/bus)
        db-conn       (atom {:settings {:auto-shutdown settings}})
        schedules     (async/chan 20)
        closed        (async/chan 20)
        emitter       (async/chan 20)
        system-events (async/chan 4)
        changes       (async/chan 8)
        timer         (sleep/start-auto-shutdown-timer!
                       {:bus      bus
                        :db-conn  db-conn
                        :now-fn   #(deref now_)
                        :ready-fn (constantly ready?)
                        :current-audio-active-fn
                        (constantly audio-active?)
                        :scheduler
                        (recording-scheduler schedules closed)})]
    (ev/emitize bus emitter)
    (ev/listen bus "/system" system-events)
    (ev/listen bus "/auto-shutdown/events" changes)
    {:bus           bus
     :changes       changes
     :closed        closed
     :db-conn       db-conn
     :emitter       emitter
     :now_          now_
     :schedules     schedules
     :system-events system-events
     :timer         timer}))

(defn- stop-auto-shutdown-context!
  [{:keys [bus changes closed emitter schedules system-events timer]}]
  (sleep/stop-timer! timer)
  (async/close! changes)
  (async/close! closed)
  (async/close! emitter)
  (async/close! schedules)
  (async/close! system-events)
  (ev/close! bus))

(defn- emit! [emitter path value]
  (async/>!! emitter {:path path :value value}))

(deftest auto-shutdown-configuration-persists-and-controls-the-schedule
  (let [{:keys [changes closed db-conn schedules timer] :as context}
        (auto-shutdown-context {})]
    (try
      (let [initial (sleep/current timer)]
        (sleep/cycle! timer :next)
        (let [selected        (sleep/current timer)
              selected-change (:value (await-event changes))]
          (sleep/enable! timer)
          (let [armed          (sleep/current timer)
                schedule       (await-event schedules)
                enabled-change (:value (await-event changes))]
            (sleep/disable! timer)
            (let [cancelled       (await-event closed)
                  disabled        (sleep/current timer)
                  disabled-change (:value (await-event changes))]
              (is (= {:initial    {:enabled?         false
                                   :selected-minutes 30
                                   :deadline         nil
                                   :idle?            true}
                      :selected   {:enabled?         false
                                   :selected-minutes 45
                                   :deadline         nil
                                   :idle?            true}
                      :armed      {:enabled?         true
                                   :selected-minutes 45
                                   :deadline
                                   (ZonedDateTime/parse
                                    "2026-07-22T10:45:00+02:00")
                                   :idle?            true}
                      :schedule-deadline
                      (ZonedDateTime/parse
                       "2026-07-22T10:45:00+02:00")
                      :cancelled? true
                      :disabled   {:enabled?         false
                                   :selected-minutes 45
                                   :deadline         nil
                                   :idle?            true}
                      :persisted  {:enabled?         false
                                   :duration-minutes 45}
                      :change-events
                      [{:event :auto-shutdown/changed}
                       {:event :auto-shutdown/changed}
                       {:event :auto-shutdown/changed}]}
                     {:initial           initial
                      :selected          selected
                      :armed             armed
                      :schedule-deadline (:deadline schedule)
                      :cancelled?        @(:closed?_ cancelled)
                      :disabled          disabled
                      :persisted         (get-in @db-conn
                                                 [:settings
                                                  :auto-shutdown])
                      :change-events     [selected-change
                                          enabled-change
                                          disabled-change]}))))))
      (finally
        (stop-auto-shutdown-context! context)))))

(deftest auto-shutdown-waits-for-idle-and-resets-on-interaction
  (let [{:keys [closed emitter now_ schedules system-events timer]
         :as   context}
        (auto-shutdown-context
         {:settings {:enabled? true :duration-minutes 10}})]
    (try
      (let [initial-schedule (await-event schedules)]
        (reset! now_ (.plusMinutes ^ZonedDateTime @now_ 2))
        (emit! emitter
               "/hardware/input/buttons"
               {:action    :button/single-press
                :button-id :audio/volume-up})
        (let [initial-cancelled (await-event closed)
              reset-schedule    (await-event schedules)]
          (emit! emitter
                 "/player/events"
                 {:event :player/state-changed :state :playing})
          (let [reset-cancelled (await-event closed)]
            ((:callback reset-schedule))
            (let [stale-poweroff (await-event system-events 50)
                  playing        (sleep/current timer)]
              (emit! emitter
                     "/player/events"
                     {:event :player/state-changed :state :paused})
              (let [paused-schedule (await-event schedules)]
                (emit! emitter
                       "/player/commands"
                       {:action :audio/play-one-shot :id :tts})
                (let [paused-cancelled (await-event closed)]
                  (emit! emitter
                         "/player/events"
                         {:event :player/one-shot-finished :id :tts})
                  (let [after-one-shot (await-event schedules)]
                    (emit! emitter
                           "/hardware/input/rfid"
                           {:action :error :error :unavailable})
                    (let [error-reset (await-event closed 50)]
                      (emit! emitter
                             "/hardware/input/rfid"
                             {:action :placed :uid "card"})
                      (let [card-cancelled (await-event closed)
                            final-schedule (await-event schedules)]
                        (reset! now_ (:deadline final-schedule))
                        ((:callback final-schedule))
                        (is (= {:initial-deadline
                                (ZonedDateTime/parse
                                 "2026-07-22T10:10:00+02:00")
                                :interaction-deadline
                                (ZonedDateTime/parse
                                 "2026-07-22T10:12:00+02:00")
                                :initial-cancelled?  true
                                :playback-cancelled? true
                                :stale-poweroff      nil
                                :playing             {:enabled?         true
                                                      :selected-minutes 10
                                                      :deadline         nil
                                                      :idle?            false}
                                :paused-deadline
                                (ZonedDateTime/parse
                                 "2026-07-22T10:12:00+02:00")
                                :one-shot-cancelled? true
                                :after-one-shot-deadline
                                (ZonedDateTime/parse
                                 "2026-07-22T10:12:00+02:00")
                                :rfid-error-reset    nil
                                :card-cancelled?     true
                                :poweroff            {:event  :system/poweroff-now
                                                      :reason :auto-shutdown}}
                               {:initial-deadline     (:deadline initial-schedule)
                                :interaction-deadline (:deadline reset-schedule)
                                :initial-cancelled?   @(:closed?_
                                                        initial-cancelled)
                                :playback-cancelled?  @(:closed?_
                                                        reset-cancelled)
                                :stale-poweroff       stale-poweroff
                                :playing              playing
                                :paused-deadline      (:deadline paused-schedule)
                                :one-shot-cancelled?  @(:closed?_
                                                        paused-cancelled)
                                :after-one-shot-deadline
                                (:deadline after-one-shot)
                                :rfid-error-reset     error-reset
                                :card-cancelled?      @(:closed?_ card-cancelled)
                                :poweroff             (:value
                                                       (await-event system-events))})))))))))))
      (finally
        (stop-auto-shutdown-context! context)))))

(deftest auto-shutdown-does-not-arm-before-the-system-is-ready
  (let [{:keys [emitter schedules] :as context}
        (auto-shutdown-context
         {:settings {:enabled? true :duration-minutes 5}
          :ready?   false})]
    (try
      (let [before-ready (await-event schedules 50)]
        (emit! emitter "/system" {:event :system/ready})
        (is (= {:before-ready nil
                :after-ready  (ZonedDateTime/parse
                               "2026-07-22T10:05:00+02:00")}
               {:before-ready before-ready
                :after-ready  (:deadline (await-event schedules))})))
      (finally
        (stop-auto-shutdown-context! context)))))