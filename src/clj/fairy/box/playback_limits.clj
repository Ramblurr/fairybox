(ns fairy.box.playback-limits
  (:require
   [clojure.tools.logging :as log]
   [donut.system :as ds]
   [fairy.box.db :as db]
   [fairy.box.util :as util])
  (:import
   [java.time Duration LocalTime ZonedDateTime]
   [java.util.concurrent Executors Future ThreadFactory TimeUnit]))

(defn- configured-start [audio-settings new-key legacy-key]
  (if (contains? audio-settings new-key)
    (util/parse-wall-clock-time new-key (get audio-settings new-key))
    (let [legacy-hour (get audio-settings legacy-key)]
      (if (and (integer? legacy-hour)
               (<= 0 legacy-hour 23))
        (LocalTime/of legacy-hour 0)
        (util/parse-wall-clock-time
         new-key
         (get db/default-audio-settings new-key))))))

(defn- minutes-since-midnight [^LocalTime time]
  (+ (* 60 (.getHour time))
     (.getMinute time)))

(defn- numeric-setting [audio-settings setting-key default-value]
  (let [value (get audio-settings setting-key default-value)]
    (if (number? value)
      value
      default-value)))

(defn- audio-limit [audio-settings period-key]
  (let [absolute-maximum (numeric-setting audio-settings
                                          :max-volume
                                          (:max-volume db/default-audio-settings))]
    (min absolute-maximum
         (numeric-setting audio-settings period-key absolute-maximum))))

(defn- system-sound-volume [audio-settings setting-key profile-key]
  (audio-limit audio-settings
               (if (number? (get audio-settings setting-key))
                 setting-key
                 profile-key)))

(defn- brightness-limit [audio-settings setting-key]
  (let [percentage (numeric-setting audio-settings setting-key 100)]
    (double (/ (max 0 (min 100 percentage)) 100))))

(defn schedule [audio-settings]
  [{:id               :day
    :starts-at-minute (minutes-since-midnight
                       (configured-start audio-settings
                                         :day-start
                                         :hour-day-start))
    :limits           {:audio/max-volume      (audio-limit audio-settings
                                                           :max-volume-day)
                       :audio/startup-volume  (system-sound-volume
                                               audio-settings
                                               :startup-volume-day
                                               :max-volume-day)
                       :audio/shutdown-volume (system-sound-volume
                                               audio-settings
                                               :shutdown-volume-day
                                               :max-volume-day)
                       :led/max-brightness    (brightness-limit
                                               audio-settings
                                               :max-led-brightness-day)}}
   {:id               :night
    :starts-at-minute (minutes-since-midnight
                       (configured-start audio-settings
                                         :night-start
                                         :hour-night-start))
    :limits           {:audio/max-volume      (audio-limit audio-settings
                                                           :max-volume-night)
                       :audio/startup-volume  (system-sound-volume
                                               audio-settings
                                               :startup-volume-night
                                               :max-volume-night)
                       :audio/shutdown-volume (system-sound-volume
                                               audio-settings
                                               :shutdown-volume-night
                                               :max-volume-night)
                       :led/max-brightness    (brightness-limit
                                               audio-settings
                                               :max-led-brightness-night)}}])

(defn ordered-schedule [entries]
  (sort-by :starts-at-minute entries))

(defn active-entry [entries current-minute]
  (let [ordered (ordered-schedule entries)]
    (or (last (take-while #(<= (:starts-at-minute %) current-minute)
                          ordered))
        (last ordered))))

(defn minute-of-day [^ZonedDateTime now]
  (+ (* 60 (.getHour now))
     (.getMinute now)))

(defn current-entry [entries now]
  (active-entry entries (minute-of-day now)))

(defn- transition-time [^ZonedDateTime now starts-at-minute]
  (let [time  (LocalTime/of (quot starts-at-minute 60)
                            (mod starts-at-minute 60))
        today (-> time
                  (.atDate (.toLocalDate now))
                  (.atZone (.getZone now)))]
    (if (.isAfter today now)
      today
      (.plusDays today 1))))

(defn next-transition
  ([entries]
   (next-transition entries (ZonedDateTime/now)))
  ([entries now]
   (when (seq entries)
     (->> entries
          (map #(transition-time now (:starts-at-minute %)))
          (sort-by #(.toInstant ^ZonedDateTime %))
          first))))

(def ^:private policy-setting-keys
  [:max-volume
   :max-volume-day
   :max-volume-night
   :startup-volume-day
   :startup-volume-night
   :shutdown-volume-day
   :shutdown-volume-night
   :max-led-brightness-day
   :max-led-brightness-night
   :day-start
   :night-start
   :hour-day-start
   :hour-night-start])

(def ^:private database-watch-key
  ::playback-limits-settings)

(defn- relevant-settings [database]
  (select-keys (db/audio-settings database) policy-setting-keys))

(defn- default-scheduler []
  (let [thread-factory (reify ThreadFactory
                         (newThread [_ runnable]
                           (doto (Thread. runnable "fairybox-playback-limits")
                             (.setDaemon true))))
        executor       (Executors/newSingleThreadScheduledExecutor
                        thread-factory)]
    {:schedule! (fn [delay-ms callback]
                  (.schedule executor
                             ^Runnable (reify Runnable
                                         (run [_]
                                           (callback)))
                             (long delay-ms)
                             TimeUnit/MILLISECONDS))
     :cancel!   (fn [future]
                  (.cancel ^Future future false))
     :shutdown! (fn []
                  (.shutdownNow executor))}))

(defn- notify-subscriber! [subscriber-id callback snapshot]
  (try
    (callback snapshot)
    (catch Throwable error
      (log/error error
                 "Playback limits subscriber failed"
                 {:subscriber-id subscriber-id}))))

(defn current-snapshot [policy]
  ((::current-snapshot policy)))

(defn current-limit [policy limit-key]
  (get-in (current-snapshot policy) [:limits limit-key]))

(defn subscribe! [policy subscriber-id callback]
  ((::subscribe! policy) subscriber-id callback))

(defn unsubscribe! [policy subscriber-id]
  ((::unsubscribe! policy) subscriber-id))

(defn refresh! [policy]
  ((::refresh! policy)))

(defn- stop-policy! [policy]
  ((::stop! policy)))

(defn start-policy!
  [{:keys [db-conn now-fn scheduler]
    :or   {now-fn #(ZonedDateTime/now)}}]
  (assert db-conn "Database connection is required for playback limits")
  (let [scheduler    (or scheduler (default-scheduler))
        state_       (atom nil)
        subscribers_ (atom {})
        lock         (Object.)
        pending_     (atom nil)
        stopped_     (atom false)]
    (letfn [(refresh-policy! []
              (locking lock
                (if @stopped_
                  @state_
                  (let [now        (now-fn)
                        entries    (schedule (db/audio-settings @db-conn))
                        active     (current-entry entries now)
                        transition (next-transition entries now)
                        snapshot   {:schedule        entries
                                    :active-period   (:id active)
                                    :limits          (:limits active)
                                    :next-transition transition}
                        previous   @state_]
                    (when-let [pending @pending_]
                      ((:cancel! scheduler) pending)
                      (reset! pending_ nil))
                    (reset! state_ snapshot)
                    (when transition
                      (let [delay-ms (max 0
                                          (.toMillis
                                           (Duration/between now transition)))
                            callback (fn []
                                       (try
                                         (refresh-policy!)
                                         (catch Throwable error
                                           (log/error
                                            error
                                            "Playback limits boundary refresh failed"))))]
                        (reset! pending_
                                ((:schedule! scheduler) delay-ms callback))))
                    (when-not (= previous snapshot)
                      (doseq [[subscriber-id callback] @subscribers_]
                        (notify-subscriber! subscriber-id callback snapshot)))
                    snapshot))))
            (subscribe-policy! [subscriber-id callback]
              (locking lock
                (when @stopped_
                  (throw (ex-info "Playback limits policy is stopped"
                                  {:subscriber-id subscriber-id})))
                (swap! subscribers_ assoc subscriber-id callback)
                (notify-subscriber! subscriber-id callback @state_)
                subscriber-id))
            (unsubscribe-policy! [subscriber-id]
              (locking lock
                (swap! subscribers_ dissoc subscriber-id)
                nil))
            (stop! []
              (locking lock
                (when (compare-and-set! stopped_ false true)
                  (remove-watch db-conn database-watch-key)
                  (when-let [pending @pending_]
                    ((:cancel! scheduler) pending)
                    (reset! pending_ nil))
                  (reset! subscribers_ {})
                  ((:shutdown! scheduler)))))]
      (let [policy {::current-snapshot #(deref state_)
                    ::subscribe!       subscribe-policy!
                    ::unsubscribe!     unsubscribe-policy!
                    ::refresh!         refresh-policy!
                    ::stop!            stop!}]
        (add-watch db-conn
                   database-watch-key
                   (fn [_ _ old-database new-database]
                     (when-not (= (relevant-settings old-database)
                                  (relevant-settings new-database))
                       (refresh-policy!))))
        (try
          (refresh-policy!)
          policy
          (catch Throwable error
            (stop!)
            (throw error)))))))

(def PlaybackLimitsComponent
  {::ds/start  (fn [{config ::ds/config}]
                 (start-policy! config))
   ::ds/stop   (fn [{instance ::ds/instance}]
                 (stop-policy! instance))
   ::ds/config {:db-conn (ds/ref [:fairy.box/components
                                  :fairy.box.db/db])}})
