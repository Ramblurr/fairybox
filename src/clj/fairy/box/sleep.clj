;; Copyright © 2025 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2
(ns fairy.box.sleep
  (:require
   [chime.core :as chime]
   [clojure.core.async :as async]
   [donut.system :as ds]
   [fairy.box.audio.current :as player]
   [fairy.box.db :as db]
   [jp.nijohando.event :as ev])
  (:import
   [java.lang AutoCloseable]
   [java.time Duration Instant ZonedDateTime]
   [java.time.format DateTimeFormatter]))

(def duration-options
  [nil 2 5 10 15 20 30 45 60 120 180 240])

(def ^:private default-duration-minutes 30)
(def ^:private fade-duration-ms (* 2 60 1000))
(def ^:private fade-step-ms (* 10 1000))
(def ^:private fade-step-count 12)

(def ^:private wall-clock-formatter
  (DateTimeFormatter/ofPattern "HH:mm"))

(defn format-duration [minutes]
  (cond
    (nil? minutes) "Off"
    (= 1 minutes) "1 minute"
    (< minutes 60) (str minutes " minutes")
    (zero? (mod minutes 60))
    (let [hours (quot minutes 60)]
      (str hours (if (= 1 hours) " hour" " hours")))
    :else
    (str (/ minutes 60.0) " hours")))

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

(defn- start-chime! [tick-ms callback]
  (let [period (Duration/ofMillis (long tick-ms))
        start  (.plus (Instant/now) period)]
    (chime/chime-at
     (chime/periodic-seq start period)
     (fn [_scheduled-time]
       (callback)))))

(defn- inactive-state [selected-minutes]
  {:enabled?            false
   :phase               :off
   :selected-minutes    selected-minutes
   :fade-deadline       nil
   :fade-initial-volume nil
   :fade-step           0
   :shutdown-deadline   nil})

(defn- duration-index [minutes]
  (or (first (keep-indexed (fn [index option]
                             (when (= option minutes) index))
                           duration-options))
      0))

(defn- bounded-volume [volume]
  (int (max 0 (min 100 (or volume 0)))))

(defn- remaining-ms [now deadline]
  (when deadline
    (max 0 (.toMillis (Duration/between now deadline)))))

(defn current [timer]
  (let [now      ((::now-fn timer))
        snapshot @(::state timer)]
    (assoc snapshot
           :remaining-ms
           (remaining-ms now (:fade-deadline snapshot))
           :fade-at
           (format-wall-clock (:fade-deadline snapshot))
           :shutdown-at
           (format-wall-clock (:shutdown-deadline snapshot)))))

(defn enabled? [timer]
  (:enabled? (current timer)))

(defn countdown-state [timer]
  (let [{:keys [enabled? phase remaining-ms fade-deadline]} (current timer)
        active? (and enabled? (not= :shutdown-wait phase))]
    {:active?     active?
     :deadline-ms (when active?
                    (some-> ^ZonedDateTime fade-deadline
                            .toInstant
                            .toEpochMilli))
     :countdown   (when active?
                    (format-countdown remaining-ms))}))

(defn- emit-event! [emitter path value]
  (async/put! emitter {:path path :value value}))

(defn- sleep-settings [db-conn]
  (db/sleep-settings @db-conn))

(defn- shutdown-deadline [fade-deadline delay-minutes]
  (.plusMinutes ^ZonedDateTime fade-deadline (long delay-minutes)))

(defn- notify-change! [emitter]
  (emit-event! emitter "/sleep/events" {:event :sleep/changed}))

(defn- emit-fade-step! [emitter level stop?]
  (emit-event! emitter
               "/player/commands"
               {:action :audio/sleep-fade-step
                :volume level
                :stop?  stop?}))

(defn- emit-poweroff! [emitter]
  (emit-event! emitter
               "/system"
               {:event  :system/poweroff-now
                :reason :sleep}))

(defn- fade-step [now fade-deadline]
  (let [remaining (remaining-ms now fade-deadline)
        elapsed   (- fade-duration-ms remaining)]
    (-> (quot (max 0 elapsed) fade-step-ms)
        (min fade-step-count))))

(defn- fade-volume [initial-volume step]
  (int (Math/floor (* initial-volume
                      (/ (- fade-step-count step)
                         (double fade-step-count))))))

(defn start-timer!
  [{:keys [bus db-conn now-fn scheduler current-volume-fn tick-ms]
    :or   {now-fn            #(ZonedDateTime/now)
           current-volume-fn #(player/volume (player/current!))
           tick-ms           1000}}]
  (assert bus "Event bus is required for the sleep timer")
  (assert db-conn "Database connection is required for the sleep timer")
  (let [emitter (async/chan (async/sliding-buffer 64))
        state_  (atom (inactive-state nil))
        lock    (Object.)]
    (ev/emitize bus emitter)
    (letfn [(disable-timer! []
              (locking lock
                (swap! state_ #(inactive-state (:selected-minutes %))))
              (notify-change! emitter)
              nil)
            (arm-timer! [minutes]
              (if (nil? minutes)
                (do
                  (locking lock
                    (reset! state_ (inactive-state nil)))
                  (notify-change! emitter))
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
                  (notify-change! emitter)
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
                    (notify-change! emitter)))
                nil))
            (finish-fade! [^ZonedDateTime now state]
              (let [{:keys [shutdown? shutdown-delay-minutes]}
                    (sleep-settings db-conn)]
                (emit-fade-step! emitter 0 true)
                (if shutdown?
                  (let [^ZonedDateTime deadline
                        (shutdown-deadline (:fade-deadline state)
                                           shutdown-delay-minutes)]
                    (if (not (.isAfter deadline now))
                      (do
                        (reset! state_ (inactive-state
                                        (:selected-minutes state)))
                        (emit-poweroff! emitter))
                      (reset! state_
                              (assoc state
                                     :phase :shutdown-wait
                                     :fade-step fade-step-count
                                     :shutdown-deadline deadline))))
                  (reset! state_ (inactive-state
                                  (:selected-minutes state))))
                (notify-change! emitter)))
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
                    (sleep-settings db-conn)]
                (if-not shutdown?
                  (do
                    (reset! state_ (inactive-state
                                    (:selected-minutes state)))
                    (notify-change! emitter))
                  (let [^ZonedDateTime deadline
                        (shutdown-deadline (:fade-deadline state)
                                           shutdown-delay-minutes)]
                    (swap! state_ assoc :shutdown-deadline deadline)
                    (when (not (.isAfter deadline now))
                      (reset! state_ (inactive-state
                                      (:selected-minutes state)))
                      (emit-poweroff! emitter)
                      (notify-change! emitter))))))
            (tick-timer! []
              (locking lock
                (let [state @state_]
                  (when (:enabled? state)
                    (if (= :shutdown-wait (:phase state))
                      (tick-shutdown-wait! (now-fn) state)
                      (tick-countdown! (now-fn) state)))))
              nil)]
      (let [start!      (or (:start! scheduler) start-chime!)
            schedule    (start! tick-ms tick-timer!)
            stop-timer! (fn []
                          (.close ^AutoCloseable schedule)
                          (async/close! emitter)
                          nil)]
        {::state    state_
         ::now-fn   now-fn
         ::enable!  enable-timer!
         ::disable! disable-timer!
         ::cycle!   cycle-timer!
         ::tick!    tick-timer!
         ::stop!    stop-timer!}))))

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

(def SleepTimerComponent
  {::ds/start  (fn [{config ::ds/config}]
                 (start-timer! config))
   ::ds/stop   (fn [{instance ::ds/instance}]
                 (stop-timer! instance))
   ::ds/config {:bus     (ds/ref [:fairy.box/components
                                  :fairy.box.bus/bus])
                :db-conn (ds/ref [:fairy.box/components
                                  :fairy.box.db/db])}})