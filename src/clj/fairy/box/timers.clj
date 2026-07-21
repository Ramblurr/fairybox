;; Copyright © 2025 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2
(ns fairy.box.timers
  (:require
   [chime.core :as chime]
   [clojure.core.async :as async]
   [clojure.tools.logging :as log]
   [donut.system :as ds]
   [fairy.box.audio.current :as player]
   [fairy.box.db :as db]
   [fairy.box.switchboard :as switchboard]
   [jp.nijohando.event :as ev])
  (:import
   [java.lang AutoCloseable]
   [java.time Duration Instant ZonedDateTime]
   [java.time.format DateTimeFormatter]))

(def duration-options
  [nil 2 5 10 15 20 30 45 60 120 180 240])
(def default-duration-minutes 30)
(def duration-options-set (set duration-options))
(def fade-duration-ms (* 2 60 1000))
(def fade-step-ms (* 10 1000))
(def fade-step-count 12)

(def wall-clock-formatter
  (DateTimeFormatter/ofPattern "HH:mm"))

(defn duration-label [minutes]
  (cond
    (nil? minutes) "Off"
    (= 1 minutes) "1 minute"
    (< minutes 60) (str minutes " minutes")
    (zero? (mod minutes 60))
    (let [hours (quot minutes 60)]
      (str hours (if (= 1 hours) " hour" " hours")))
    :else
    (str (/ minutes 60.0) " hours")))

(defn- duration-index [minutes]
  (or (first (keep-indexed (fn [index option]
                             (when (= option minutes) index))
                           duration-options))
      0))

(defn- emit-event! [emitter path value]
  (async/put! emitter {:path path :value value}))

(defn- emit-poweroff! [emitter reason]
  (emit-event! emitter
               "/system"
               {:event  :system/poweroff-now
                :reason reason}))

(defn current [timer]
  ((::current timer)))

(defn enabled? [timer]
  (:enabled? (current timer)))

(defn enable! [timer]
  ((::enable! timer)))

(defn disable! [timer]
  ((::disable! timer)))

(defn cycle! [timer direction]
  ((::cycle! timer) direction))

(defn tick! [timer]
  ((::tick! timer)))

(defn stop-timer! [timer]
  ((::stop! timer)))

(defn- format-wall-clock [^ZonedDateTime date-time]
  (some-> date-time (.format wall-clock-formatter)))

(defn- format-countdown [milliseconds]
  (let [total-seconds (long (Math/ceil (/ (max 0 (or milliseconds 0))
                                          1000.0)))
        seconds       (mod total-seconds 60)
        total-minutes (quot total-seconds 60)
        minutes       (mod total-minutes 60)
        hours         (quot total-minutes 60)]
    (if (pos? hours)
      (format "%d:%02d:%02d" hours minutes seconds)
      (format "%02d:%02d" minutes seconds))))

(defn- start-periodic-chime! [tick-ms callback]
  (let [period (Duration/ofMillis (long tick-ms))
        start  (.plus (Instant/now) period)]
    (chime/chime-at
     (chime/periodic-seq start period)
     (fn [_scheduled-time]
       (callback)))))

(defn- inactive-sleep-state [selected-minutes]
  {:enabled?            false
   :phase               :off
   :selected-minutes    selected-minutes
   :fade-deadline       nil
   :fade-initial-volume nil
   :fade-step           0
   :shutdown-deadline   nil})

(defn- bounded-volume [volume]
  (int (max 0 (min 100 (or volume 0)))))

(defn- remaining-ms [now deadline]
  (when deadline
    (max 0 (.toMillis (Duration/between now deadline)))))

(defn- sleep-current [state_ now-fn]
  (let [now      (now-fn)
        snapshot @state_]
    (assoc snapshot
           :remaining-ms
           (remaining-ms now (:fade-deadline snapshot))
           :fade-at
           (format-wall-clock (:fade-deadline snapshot))
           :shutdown-at
           (format-wall-clock (:shutdown-deadline snapshot)))))

(defn sleep-countdown-state [timer]
  (let [{:keys [enabled? phase remaining-ms fade-deadline]} (current timer)
        active? (and enabled? (not= :shutdown-wait phase))]
    {:active?     active?
     :deadline-ms (when active?
                    (some-> ^ZonedDateTime fade-deadline
                            .toInstant
                            .toEpochMilli))
     :countdown   (when active?
                    (format-countdown remaining-ms))}))

(defn- shutdown-deadline [fade-deadline delay-minutes]
  (.plusMinutes ^ZonedDateTime fade-deadline (long delay-minutes)))

(defn- notify-sleep-change! [emitter]
  (emit-event! emitter "/sleep/events" {:event :sleep/changed}))

(defn- emit-fade-step! [emitter level stop?]
  (emit-event! emitter
               "/player/commands"
               {:action :audio/sleep-fade-step
                :volume level
                :stop?  stop?}))

(defn- fade-step [now fade-deadline]
  (let [remaining (remaining-ms now fade-deadline)
        elapsed   (- fade-duration-ms remaining)]
    (-> (quot (max 0 elapsed) fade-step-ms)
        (min fade-step-count))))

(defn- fade-volume [initial-volume step]
  (int (Math/floor (* initial-volume
                      (/ (- fade-step-count step)
                         (double fade-step-count))))))

(defn start-sleep-timer!
  [{:keys [bus db-conn now-fn scheduler current-volume-fn tick-ms]
    :or   {now-fn            #(ZonedDateTime/now)
           current-volume-fn #(player/volume (player/current!))
           tick-ms           1000}}]
  (assert bus "Event bus is required for the sleep timer")
  (assert db-conn "Database connection is required for the sleep timer")
  (let [emitter (async/chan (async/sliding-buffer 64))
        state_  (atom (inactive-sleep-state nil))
        lock    (Object.)]
    (ev/emitize bus emitter)
    (letfn [(disable-timer! []
              (locking lock
                (swap! state_ #(inactive-sleep-state
                                (:selected-minutes %))))
              (notify-sleep-change! emitter)
              nil)
            (arm-timer! [minutes]
              (if (nil? minutes)
                (do
                  (locking lock
                    (reset! state_ (inactive-sleep-state nil)))
                  (notify-sleep-change! emitter))
                (let [now      (now-fn)
                      deadline (.plusMinutes ^ZonedDateTime now (long minutes))]
                  (locking lock
                    (reset! state_
                            {:enabled?            true
                             :phase               (if (<= minutes 2)
                                                    :fading
                                                    :counting)
                             :selected-minutes    minutes
                             :fade-deadline       deadline
                             :fade-initial-volume nil
                             :fade-step           0
                             :shutdown-deadline   nil}))
                  (notify-sleep-change! emitter)
                  nil)))
            (enable-timer! []
              (arm-timer! (or (:selected-minutes @state_)
                              default-duration-minutes)))
            (cycle-timer! [direction]
              (let [current-index (duration-index (:selected-minutes @state_))
                    offset        (case direction
                                    :previous -1
                                    :next 1
                                    0)
                    option-count  (count duration-options)
                    next-index    (mod (+ current-index offset) option-count)
                    minutes       (nth duration-options next-index)]
                (if (:enabled? @state_)
                  (arm-timer! minutes)
                  (do
                    (locking lock
                      (swap! state_ assoc :selected-minutes minutes))
                    (notify-sleep-change! emitter)))
                nil))
            (finish-fade! [^ZonedDateTime now state]
              (let [{:keys [shutdown? shutdown-delay-minutes]}
                    (db/sleep-settings @db-conn)]
                (emit-fade-step! emitter 0 true)
                (if shutdown?
                  (let [^ZonedDateTime deadline
                        (shutdown-deadline (:fade-deadline state)
                                           shutdown-delay-minutes)]
                    (if (not (.isAfter deadline now))
                      (do
                        (reset! state_ (inactive-sleep-state
                                        (:selected-minutes state)))
                        (emit-poweroff! emitter :sleep))
                      (reset! state_
                              (assoc state
                                     :phase :shutdown-wait
                                     :fade-step fade-step-count
                                     :shutdown-deadline deadline))))
                  (reset! state_ (inactive-sleep-state
                                  (:selected-minutes state))))
                (notify-sleep-change! emitter)))
            (tick-countdown! [^ZonedDateTime now state]
              (let [step     (fade-step now (:fade-deadline state))
                    fading?  (<= (remaining-ms now (:fade-deadline state))
                                 fade-duration-ms)
                    initial  (or (:fade-initial-volume state)
                                 (when (pos? step)
                                   (bounded-volume (current-volume-fn))))
                    previous (:fade-step state)]
                (when (or (and fading? (not= :fading (:phase state)))
                          initial)
                  (swap! state_ assoc
                         :phase (if fading? :fading :counting)
                         :fade-initial-volume initial))
                (when (and (> step previous)
                           (< step fade-step-count))
                  (emit-fade-step! emitter
                                   (fade-volume (or initial 0) step)
                                   false)
                  (swap! state_ assoc :fade-step step))
                (when (not (.isBefore now (:fade-deadline state)))
                  (finish-fade! now @state_))))
            (tick-shutdown-wait! [^ZonedDateTime now state]
              (let [{:keys [shutdown? shutdown-delay-minutes]}
                    (db/sleep-settings @db-conn)]
                (if-not shutdown?
                  (do
                    (reset! state_ (inactive-sleep-state
                                    (:selected-minutes state)))
                    (notify-sleep-change! emitter))
                  (let [^ZonedDateTime deadline
                        (shutdown-deadline (:fade-deadline state)
                                           shutdown-delay-minutes)]
                    (swap! state_ assoc :shutdown-deadline deadline)
                    (when (not (.isAfter deadline now))
                      (reset! state_ (inactive-sleep-state
                                      (:selected-minutes state)))
                      (emit-poweroff! emitter :sleep)
                      (notify-sleep-change! emitter))))))
            (tick-timer! []
              (locking lock
                (let [state @state_]
                  (when (:enabled? state)
                    (if (= :shutdown-wait (:phase state))
                      (tick-shutdown-wait! (now-fn) state)
                      (tick-countdown! (now-fn) state)))))
              nil)]
      (let [start!      (or (:start! scheduler) start-periodic-chime!)
            schedule    (start! tick-ms tick-timer!)
            stop-timer! (fn []
                          (.close ^AutoCloseable schedule)
                          (async/close! emitter)
                          nil)]
        {::current  #(sleep-current state_ now-fn)
         ::enable!  enable-timer!
         ::disable! disable-timer!
         ::cycle!   cycle-timer!
         ::tick!    tick-timer!
         ::stop!    stop-timer!}))))

(def SleepTimerComponent
  {::ds/start  (fn [{config ::ds/config}]
                 (start-sleep-timer! config))
   ::ds/stop   (fn [{instance ::ds/instance}]
                 (stop-timer! instance))
   ::ds/config {:bus     (ds/ref [:fairy.box/components
                                  :fairy.box.bus/bus])
                :db-conn (ds/ref [:fairy.box/components
                                  :fairy.box.db/db])}})

(def ^:private audio-active-states #{:opening :playing})
(def ^:private playback-states
  (into audio-active-states #{:paused :stopped :finished}))

(defn- start-deadline-chime! [^ZonedDateTime deadline callback]
  (chime/chime-at [(.toInstant deadline)]
                  (fn [_scheduled-time]
                    (callback))))

(defn- auto-shutdown-idle?
  [{:keys [ready? main-audio-active? one-shot-active?]}]
  (and ready?
       (not main-audio-active?)
       (not one-shot-active?)))

(defn- auto-shutdown-current [state_]
  (let [state @state_]
    (assoc (select-keys state [:enabled? :selected-minutes :deadline])
           :idle?
           (auto-shutdown-idle? state))))

(defn- notify-auto-shutdown-change! [emitter]
  (emit-event! emitter
               "/auto-shutdown/events"
               {:event :auto-shutdown/changed}))

(defn- persisted-auto-shutdown-settings [state]
  {:enabled?         (:enabled? state)
   :duration-minutes (:selected-minutes state)})

(defn start-auto-shutdown-timer!
  [{:keys [bus db-conn now-fn scheduler current-audio-active-fn ready-fn]
    :or   {now-fn   #(ZonedDateTime/now)
           current-audio-active-fn
           #(contains? audio-active-states
                       (player/state (player/current!)))
           ready-fn #(= :system-state/ready
                        (switchboard/system-state!))}}]
  (assert bus "Event bus is required for the auto shutdown timer")
  (assert db-conn "Database connection is required for the auto shutdown timer")
  (let [{:keys [enabled? duration-minutes]}
        (db/auto-shutdown-settings @db-conn)
        selected-minutes                    (if (contains? duration-options-set duration-minutes)
                                              duration-minutes
                                              default-duration-minutes)
        state_ (atom {:running?         true
                      :enabled?         (and (true? enabled?)
                                             (some? selected-minutes))
                      :selected-minutes selected-minutes
                      :ready?           (boolean (ready-fn))
                      :main-audio-active?
                      (boolean (current-audio-active-fn))
                      :one-shot-active? false
                      :deadline         nil})
        schedule_ (atom nil)
        lock (Object.)
        events (async/chan (async/sliding-buffer 128))
        emitter (async/chan (async/sliding-buffer 32))
        start! (or (:start! scheduler) start-deadline-chime!)]
    (doseq [path ["/hardware/input/buttons"
                  "/hardware/input/rfid"
                  "/player/commands"
                  "/player/events"
                  "/system"]]
      (ev/listen bus path events))
    (ev/emitize bus emitter)
    (letfn [(cancel-schedule! []
              (when-let [schedule @schedule_]
                (.close ^AutoCloseable schedule)
                (reset! schedule_ nil)))
            (timer-fired! [deadline]
              (locking lock
                (let [state @state_]
                  (when (and (:running? state)
                             (:enabled? state)
                             (auto-shutdown-idle? state)
                             (= deadline (:deadline state)))
                    (reset! schedule_ nil)
                    (swap! state_ assoc :deadline nil)
                    (emit-poweroff! emitter :auto-shutdown)))))
            (schedule-idle! []
              (cancel-schedule!)
              (let [{:keys [running? enabled? selected-minutes]
                     :as   state} @state_]
                (if (and running?
                         enabled?
                         selected-minutes
                         (auto-shutdown-idle? state))
                  (let [deadline (.plusMinutes ^ZonedDateTime (now-fn)
                                               (long selected-minutes))]
                    (swap! state_ assoc :deadline deadline)
                    (let [schedule (start! deadline
                                           #(timer-fired! deadline))]
                      (if (= deadline (:deadline @state_))
                        (reset! schedule_ schedule)
                        (.close ^AutoCloseable schedule))))
                  (swap! state_ assoc :deadline nil))))
            (note-interaction! []
              (locking lock
                (when (:running? @state_)
                  (schedule-idle!))))
            (set-main-audio-state! [playback-state]
              (when (contains? playback-states playback-state)
                (locking lock
                  (when (:running? @state_)
                    (swap! state_ assoc
                           :main-audio-active?
                           (contains? audio-active-states playback-state))
                    (schedule-idle!)))))
            (set-one-shot-active! [active?]
              (locking lock
                (when (:running? @state_)
                  (swap! state_ assoc :one-shot-active? active?)
                  (schedule-idle!))))
            (set-ready! [ready?]
              (locking lock
                (when (:running? @state_)
                  (swap! state_ assoc :ready? ready?)
                  (schedule-idle!))))
            (handle-player-command! [{:keys [action]}]
              (case action
                :audio/play-one-shot (set-one-shot-active! true)
                :audio/sleep-fade-step nil
                (note-interaction!)))
            (handle-player-event! [{:keys [event state]}]
              (case event
                :player/state-changed (set-main-audio-state! state)
                :player/one-shot-finished (set-one-shot-active! false)
                nil))
            (handle-system-event! [{:keys [event]}]
              (case event
                :system/ready (set-ready! true)
                (:system/initialized
                 :system/warming-up
                 :system/cooling-down
                 :system/shutdown
                 :system/poweroff-now) (set-ready! false)
                nil))
            (handle-event! [{:keys [path value] :as event}]
              (try
                (case path
                  "/hardware/input/buttons" (note-interaction!)
                  "/hardware/input/rfid" (when (#{:placed :removed}
                                                (:action value))
                                           (note-interaction!))
                  "/player/commands" (handle-player-command! value)
                  "/player/events" (handle-player-event! value)
                  "/system" (handle-system-event! value)
                  nil)
                (catch Throwable error
                  (log/error error
                             "Auto shutdown event handler failed"
                             {:event event}))))
            (configure-timer! [enabled? minutes]
              (locking lock
                (when (:running? @state_)
                  (swap! state_ assoc
                         :enabled? (and enabled? (some? minutes))
                         :selected-minutes minutes)
                  (swap! db-conn
                         update-in
                         [:settings :auto-shutdown]
                         merge
                         (persisted-auto-shutdown-settings @state_))
                  (schedule-idle!)))
              (notify-auto-shutdown-change! emitter)
              nil)
            (enable-timer! []
              (configure-timer! true
                                (or (:selected-minutes @state_)
                                    default-duration-minutes)))
            (disable-timer! []
              (configure-timer! false (:selected-minutes @state_)))
            (cycle-timer! [direction]
              (let [current-index (duration-index
                                   (:selected-minutes @state_))
                    offset        (case direction
                                    :previous -1
                                    :next 1
                                    0)
                    next-index    (mod (+ current-index offset)
                                       (count duration-options))
                    minutes       (nth duration-options next-index)]
                (configure-timer! (and (:enabled? @state_)
                                       (some? minutes))
                                  minutes)))
            (stop-timer! []
              (locking lock
                (swap! state_ assoc :running? false :deadline nil)
                (cancel-schedule!))
              (async/close! events)
              (async/close! emitter)
              nil)]
      (async/go-loop []
        (when-some [event (async/<! events)]
          (handle-event! event)
          (recur)))
      (locking lock
        (schedule-idle!))
      {::current  #(auto-shutdown-current state_)
       ::enable!  enable-timer!
       ::disable! disable-timer!
       ::cycle!   cycle-timer!
       ::stop!    stop-timer!})))

(def AutoShutdownTimerComponent
  {::ds/start  (fn [{config ::ds/config}]
                 (start-auto-shutdown-timer! config))
   ::ds/stop   (fn [{instance ::ds/instance}]
                 (stop-timer! instance))
   ::ds/config {:bus     (ds/ref [:fairy.box/components
                                  :fairy.box.bus/bus])
                :db-conn (ds/ref [:fairy.box/components
                                  :fairy.box.db/db])}})
