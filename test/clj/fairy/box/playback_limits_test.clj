(ns fairy.box.playback-limits-test
  (:require
   [clojure.test :refer [deftest is]]
   [donut.system :as ds]
   [fairy.box.playback-limits :as limits])
  (:import
   [java.time ZoneId ZonedDateTime]))

(def zone
  (ZoneId/of "Europe/Berlin"))

(def base-settings
  {:max-volume               95
   :max-volume-day           80
   :max-volume-night         50
   :max-led-brightness-day   75
   :max-led-brightness-night 20
   :day-start                "08:30"
   :night-start              "19:30"})

(defn- at [day hour minute]
  (ZonedDateTime/of 2025 1 day hour minute 0 0 zone))

(defn- scheduler-double []
  (let [events_ (atom [])
        token_  (atom 0)]
    {:events_ events_
     :scheduler
     {:schedule! (fn [delay-ms callback]
                   (let [token (swap! token_ inc)]
                     (swap! events_ conj {:event    :scheduled
                                          :token    token
                                          :delay-ms delay-ms
                                          :callback callback})
                     token))
      :cancel!   (fn [token]
                   (swap! events_ conj {:event :cancelled :token token}))
      :shutdown! (fn []
                   (swap! events_ conj {:event :shutdown}))}}))

(defn- stop-policy! [policy]
  ((::ds/stop limits/PlaybackLimitsComponent)
   {::ds/instance policy}))

(deftest compiles-and-selects-cyclic-schedule
  (let [schedule     (limits/schedule base-settings)
        equal-starts (limits/schedule
                      (assoc base-settings
                             :day-start "08:00"
                             :night-start "08:00"))
        legacy       (limits/schedule
                      (-> base-settings
                          (dissoc :day-start :night-start)
                          (assoc :hour-day-start 7
                                 :hour-night-start 21)))
        capped       (limits/schedule (assoc base-settings :max-volume 40))
        clamped      (limits/schedule
                      (assoc base-settings
                             :max-led-brightness-day 150
                             :max-led-brightness-night -20))
        defaulted    (limits/schedule
                      (dissoc base-settings
                              :max-led-brightness-day
                              :max-led-brightness-night))]
    (is (= {:schedule
            [{:id               :day
              :starts-at-minute 510
              :limits           {:audio/max-volume   80
                                 :led/max-brightness 0.75}}
             {:id               :night
              :starts-at-minute 1170
              :limits           {:audio/max-volume   50
                                 :led/max-brightness 0.2}}]
            :periods              [:night :night :day :day :night]
            :next-transition      "2025-01-15T19:30+01:00[Europe/Berlin]"
            :equal-start          :night
            :legacy-starts        [420 1260]
            :capped-volumes       [40 40]
            :clamped-brightness   [1.0 0.0]
            :defaulted-brightness [1.0 1.0]}
           {:schedule        schedule
            :periods         (mapv (fn [[hour minute]]
                                     (:id (limits/current-entry
                                           schedule
                                           (at 15 hour minute))))
                                   [[0 0] [8 29] [8 30] [19 29] [19 30]])
            :next-transition (str (limits/next-transition
                                   schedule
                                   (at 15 8 30)))
            :equal-start     (:id (limits/current-entry equal-starts
                                                        (at 15 12 0)))
            :legacy-starts   (mapv :starts-at-minute legacy)
            :capped-volumes  (mapv #(get-in % [:limits :audio/max-volume])
                                   capped)
            :clamped-brightness
            (mapv #(get-in % [:limits :led/max-brightness]) clamped)
            :defaulted-brightness
            (mapv #(get-in % [:limits :led/max-brightness]) defaulted)}))))

(deftest rejects-malformed-canonical-time
  (let [error (try
                (limits/schedule
                 (assoc base-settings :day-start "8:30"))
                (catch clojure.lang.ExceptionInfo error
                  error))]
    (is (= {:message "Wall-clock time must use HH:mm"
            :data    {:expected-format "HH:mm"
                      :value           "8:30"
                      :setting-key     :day-start}}
           {:message (ex-message error)
            :data    (ex-data error)}))))

(deftest refreshes-synchronously-for-settings-and-boundaries
  (let [clock_ (atom (at 15 19 29))
        db-conn (atom {:settings {:audio base-settings}})
        {:keys [scheduler events_]} (scheduler-double)
        policy (limits/start-policy! {:db-conn   db-conn
                                      :now-fn    #(deref clock_)
                                      :scheduler scheduler})
        delivered_                  (atom [])]
    (limits/subscribe! policy ::failure
                       #(throw (ex-info "subscriber failed" {:snapshot %})))
    (limits/subscribe! policy ::observer
                       #(swap! delivered_ conj
                               (select-keys % [:active-period :limits])))
    (let [initial             @delivered_
          _irrelevant         (swap! db-conn assoc :unrelated :ignored)
          after-unrelated     @delivered_
          _maximum            (swap! db-conn assoc-in
                                     [:settings :audio :max-volume-day]
                                     60)
          _boundary           (swap! db-conn assoc-in
                                     [:settings :audio :night-start]
                                     "17:45")
          _clock              (reset! clock_ (at 16 8 30))
          _timer              ((->> @events_
                                    (filter #(= :scheduled (:event %)))
                                    last
                                    :callback))
          before-stop-watches (count (.getWatches
                                      ^clojure.lang.IRef db-conn))
          _first-stop         (stop-policy! policy)
          _second-stop        (stop-policy! policy)]
      (is (= {:initial
              [{:active-period :day
                :limits        {:audio/max-volume   80
                                :led/max-brightness 0.75}}]
              :unrelated-published? false
              :deliveries
              [{:active-period :day
                :limits        {:audio/max-volume   80
                                :led/max-brightness 0.75}}
               {:active-period :day
                :limits        {:audio/max-volume   60
                                :led/max-brightness 0.75}}
               {:active-period :night
                :limits        {:audio/max-volume   50
                                :led/max-brightness 0.2}}
               {:active-period :day
                :limits        {:audio/max-volume   60
                                :led/max-brightness 0.75}}]
              :watches-before-stop  1
              :watches-after-stop   0
              :scheduled-delays     [60000 60000 46860000 33300000]
              :cancelled-tokens     [1 2 3 4]
              :shutdown-count       1}
             {:initial              initial
              :unrelated-published? (not= initial after-unrelated)
              :deliveries           @delivered_
              :watches-before-stop  before-stop-watches
              :watches-after-stop   (count (.getWatches
                                            ^clojure.lang.IRef db-conn))
              :scheduled-delays     (->> @events_
                                         (filter #(= :scheduled (:event %)))
                                         (mapv :delay-ms))
              :cancelled-tokens     (->> @events_
                                         (filter #(= :cancelled (:event %)))
                                         (mapv :token))
              :shutdown-count       (count (filter #(= :shutdown (:event %))
                                                   @events_))})))))