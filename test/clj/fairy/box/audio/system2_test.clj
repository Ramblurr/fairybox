(ns fairy.box.audio.system2-test
  (:require
   [babashka.fs :as fs]
   [clojure.core.async :as async]
   [clojure.test :refer [deftest is use-fixtures]]
   [donut.system :as ds]
   [fairy.box.audio.system2 :as audio]
   [fairy.box.media-test-utils :as media]
   [fairy.box.playback-limits :as limits]
   [fairy.box.tts :as tts]
   [fairy.box.tts.speech :as speech]
   [jp.nijohando.event :as ev]
   [ol.vinyl :as mp])
  (:import
   [java.time ZoneId ZonedDateTime]))

(defn- with-restored-audio-state [f]
  (let [original @audio/audio-state]
    (try
      (f)
      (finally
        (reset! audio/audio-state original)))))

(use-fixtures :each with-restored-audio-state)

(deftest initializes-vlc-during-audio-component-start
  (let [bus            (ev/bus)
        init-count_    (atom 0)
        created-with_  (atom [])
        release-count_ (atom 0)
        released       (promise)]
    (try
      (with-redefs [mp/factory           (constantly nil)
                    mp/init!             (fn []
                                           (swap! init-count_ inc)
                                           ::factory)
                    mp/create-player     (fn [opts]
                                           (swap! created-with_ conj opts)
                                           [::player (count @created-with_)])
                    mp/subscribe!        (constantly nil)
                    mp/dispatch          (constantly nil)
                    mp/release-player!   (fn [_]
                                           (when (= 2 (swap! release-count_ inc))
                                             (deliver released true)))
                    audio/maximum-volume (constantly 50)
                    limits/subscribe!    (constantly nil)
                    limits/unsubscribe!  (constantly nil)]
        (let [instance ((::ds/start audio/AudioSystemComponent)
                        {::ds/config {:bus             bus
                                      :settings        {}
                                      :db-conn         (atom {})
                                      :playback-limits ::policy}})]
          (try
            (is (= {:init-count   1
                    :created-with [{:media-player-factory ::factory}
                                   {:media-player-factory ::factory}]}
                   {:init-count   @init-count_
                    :created-with @created-with_}))
            (finally
              ((::ds/stop audio/AudioSystemComponent)
               {::ds/instance instance})))
          (is (true? (deref released 1000 false)))))
      (finally
        (ev/close! bus)))))

(deftest metadata-excludes-parsed-files-without-audio-tracks
  (fs/with-temp-dir [temp-dir {:prefix "fairybox-audio-metadata-"}]
    (let [{:keys [settings]} (media/populate-media-tree! temp-dir)
          parsed             [{:mrl          "file:///media/01-Introduction.ogg"
                               :meta         #:meta{:album        "Days with Frog and Toad"
                                                    :artist       "Arnold Lobel"
                                                    :title        "Introduction"
                                                    :track-number "1"}
                               :duration     60914
                               :audio-tracks [{:bit-rate          224000
                                               :channels          2
                                               :codec             1651666806
                                               :codec-description "Vorbis Audio"
                                               :codec-name        "vorb"
                                               :description       nil
                                               :id                0
                                               :language          nil
                                               :level             -1
                                               :profile           -1
                                               :rate              44100}]
                               :media-type   :media-type/file
                               :parse-status :media-parsed-status/done}
                              {:mrl          "file:///media/folder.conf"
                               :meta         #:meta{:title "folder.conf"}
                               :duration     0
                               :audio-tracks []
                               :media-type   :media-type/file
                               :parse-status :media-parsed-status/done}]
          metadata           (with-redefs [mp/parse-meta
                                           (fn [_ _]
                                             (doto (promise)
                                               (deliver parsed)))]
                               (audio/metadata-for
                                {:player   :player
                                 :settings settings}
                                "audiobooks/Author One/Book One"))]
      (is (= [{:title  "Introduction"
               :album  "Days with Frog and Toad"
               :artist "Arnold Lobel"}]
             metadata)))))

(deftest builds-semantic-announcements-from-vinyl-metadata
  (let [announcement (fn [meta mrl]
                       (audio/announcement-for-track
                        {:mrl  mrl
                         :meta meta}))]
    (is (= {:leading-zero
            (speech/plan
             [(speech/text "Number 1, \"Introduction\"")])
            :numbered-total
            (speech/plan
             [(speech/text "Number 1, \"Introduction\"")])
            :numeric
            (speech/plan
             [(speech/text "Number 8, \"Introduction\"")])
            :invalid-number
            (speech/plan
             [(speech/text "\"Introduction\"")])
            :missing-number
            (speech/plan
             [(speech/text "\"Introduction\"")])
            :fallback-title
            (speech/plan
             [(speech/text "\"Fallback Title\"")])}
           {:leading-zero
            (announcement #:meta{:title        "Introduction"
                                 :track-number "01"}
                          "file:///media/01-Introduction.ogg")
            :numbered-total
            (announcement #:meta{:title        "Introduction"
                                 :track-number "01/22"}
                          "file:///media/01-Introduction.ogg")
            :numeric
            (announcement #:meta{:title        "Introduction"
                                 :track-number 8}
                          "file:///media/08-Introduction.ogg")
            :invalid-number
            (announcement #:meta{:title        "Introduction"
                                 :track-number "side-a"}
                          "file:///media/Introduction.ogg")
            :missing-number
            (announcement #:meta{:title "Introduction"}
                          "file:///media/Introduction.ogg")
            :fallback-title
            (announcement {}
                          "file:///media/Fallback%20Title.ogg")}))))

(deftest announces-and-plays-tracks-in-numeric-order
  (fs/with-temp-dir [temp-dir {:prefix "fairybox-track-announcements-"}]
    (let [{:keys [settings]} (media/populate-media-tree! temp-dir)
          audio-track        {:bit-rate          224000
                              :channels          2
                              :codec             1651666806
                              :codec-description "Vorbis Audio"
                              :codec-name        "vorb"
                              :description       nil
                              :id                0
                              :language          nil
                              :level             -1
                              :profile           -1
                              :rate              44100}
          track              (fn [filename title track-number]
                               {:mrl          (str "file:///media/" filename)
                                :meta         (cond-> #:meta{:title title}
                                                track-number
                                                (assoc :meta/track-number
                                                       track-number))
                                :duration     60914
                                :audio-tracks [audio-track]
                                :media-type   :media-type/file
                                :parse-status :media-parsed-status/done})
          two                (track "two.ogg" "Two" "02/12")
          ten                (track "ten.ogg" "Ten" "10")
          unknown            (track "unknown.ogg" "Unknown" nil)
          dispatches_        (atom [])]
      (with-redefs [mp/parse-meta
                    (fn [_ _]
                      (doto (promise)
                        (deliver [ten unknown two])))
                    tts/tts
                    (fn [_ plan]
                      (str "tts://"
                           (get-in plan
                                   [:speech/segments 0 :segment/text])))
                    mp/dispatch
                    (fn [player command & {:as options}]
                      (swap! dispatches_ conj [player command options]))]
        (.join ^Thread
         (audio/play-path!
          {:player   :player
           :settings settings}
          {:item-path           "audiobooks/Author One/Book One"
           :announce-per-track? true}))
        (is (= [[:player :playback/clear-all nil]
                [:player :playback/append
                 {:paths ["tts://Number 2, \"Two\"" two
                          "tts://Number 10, \"Ten\"" ten
                          "tts://\"Unknown\"" unknown]}]
                [:player :playback/advance nil]]
               @dispatches_))))))

(deftest publishes-queue-change-after-updating-state
  (let [events (async/chan 1)
        queue  {:history  []
                :current  {:id   "current"
                           :mrl  "file:///current.mp3"
                           :meta #:meta{:title "Current"}}
                :priority []
                :normal   []
                :tracks   [{:id    "current"
                            :mrl   "file:///current.mp3"
                            :index 0
                            :meta  #:meta{:title "Current"}}]}]
    (try
      (reset! audio/audio-state
              {:playback {:state :playing}
               :mixer    {:muted? false :volume 40}
               :queue    {:source-type :folder :source-path "audiobooks/Book"}
               :config   {}})
      (audio/internal-event-handler
       {:emitter events}
       {:ol.vinyl/event :ol.vinyl.playback/queue-changed
        :after-queue    queue})
      (let [[event port] (async/alts!! [events (async/timeout 1000)])]
        (is (= {:event     {:path  "/player/events"
                            :value {:event :player/queue-changed}}
                :received? true
                :queue     (assoc queue
                                  :source-type :folder
                                  :source-path "audiobooks/Book")}
               {:event     event
                :received? (= port events)
                :queue     (:queue @audio/audio-state)})))
      (finally
        (async/close! events)))))

(deftest records-and-clears-media-relative-queue-source
  (fs/with-temp-dir [temp-dir {:prefix "fairybox-audio-queue-source-"}]
    (let [tree (media/populate-media-tree! temp-dir)
          sys  {:settings (:settings tree) :player :fake-player}]
      (reset! audio/audio-state
              {:playback {:state nil}
               :mixer    {}
               :queue    nil
               :config   {}})
      (with-redefs [mp/dispatch (fn [& _] nil)]
        (audio/play-path! sys {:item-path "audiobooks/Author One/Book One"})
        (let [folder-source (select-keys (:queue @audio/audio-state)
                                         [:source-type :source-path])]
          (audio/play-path! sys {:item-path "playlists/Favorites.m3u"})
          (let [playlist-source (select-keys (:queue @audio/audio-state)
                                             [:source-type :source-path])]
            (audio/command-handler
             sys
             {:value {:action :audio/clear}})
            (is (= {:folder-source
                    {:source-type :folder
                     :source-path "audiobooks/Author One/Book One"}
                    :playlist-source
                    {:source-type :playlist
                     :source-path "playlists/Favorites.m3u"}
                    :source-after-clear {}}
                   {:folder-source   folder-source
                    :playlist-source playlist-source
                    :source-after-clear
                    (select-keys (:queue @audio/audio-state)
                                 [:source-type :source-path])}))))))))

(deftest publishes-shuffle-change-after-updating-state
  (let [events (async/chan 1)]
    (try
      (reset! audio/audio-state
              {:playback {:state :playing :shuffle? false}
               :mixer    {}
               :queue    nil
               :config   {}})
      (audio/internal-event-handler
       {:emitter events}
       {:ol.vinyl/event :ol.vinyl.playback/shuffle-changed
        :shuffle?       true})
      (let [[event port] (async/alts!! [events (async/timeout 1000)])]
        (is (= {:event     {:path  "/player/events"
                            :value {:event    :player/shuffle-changed
                                    :shuffle? true}}
                :received? true
                :shuffle?  true}
               {:event     event
                :received? (= port events)
                :shuffle?  (get-in @audio/audio-state
                                   [:playback :shuffle?])})))
      (finally
        (async/close! events)))))

(deftest dispatches-repeat-and-shuffle-commands
  (let [dispatches (atom [])
        sys        {:player :player}]
    (with-redefs [mp/dispatch
                  (fn [player command payload]
                    (swap! dispatches conj [player command payload]))]
      (audio/command-handler
       sys
       {:value {:action :audio/set-repeat :mode :track}})
      (audio/command-handler
       sys
       {:value {:action :audio/set-shuffle :shuffle? true}}))
    (is (= [[:player :playback/set-repeat {:mode :track}]
            [:player :playback/set-shuffle {:shuffle? true}]]
           @dispatches))))

(deftest enforces-policy-limits-without-raising-volume
  (let [zone               (ZoneId/of "Europe/Berlin")
        clock_             (atom (ZonedDateTime/of 2025 1 15 12 0 0 0 zone))
        db-conn            (atom {:settings
                                  {:audio {:min-volume               2
                                           :max-volume               95
                                           :max-volume-day           80
                                           :max-volume-night         50
                                           :max-led-brightness-day   100
                                           :max-led-brightness-night 100
                                           :day-start                "08:00"
                                           :night-start              "19:30"}}})
        scheduler          {:schedule! (fn [_ _] ::pending)
                            :cancel!   (constantly nil)
                            :shutdown! (constantly nil)}
        policy             (limits/start-policy! {:db-conn   db-conn
                                                  :now-fn    #(deref clock_)
                                                  :scheduler scheduler})
        actual-volume_     (atom 90)
        dispatched-levels_ (atom [])
        volume-lock        (Object.)]
    (try
      (with-redefs [mp/get-volume (fn [_] @actual-volume_)
                    mp/dispatch   (fn [player command _ level]
                                    (when (= command :mixer/set-volume)
                                      (swap! dispatched-levels_ conj
                                             [player level])
                                      (reset! actual-volume_ level)))]
        (limits/subscribe! policy
                           audio/audio-subscriber-id
                           (partial audio/enforce-volume-limit!
                                    :player
                                    volume-lock))
        (let [after-initial @dispatched-levels_]
          (swap! db-conn assoc-in [:settings :audio :max-volume-day] 90)
          (let [after-raise @dispatched-levels_]
            (swap! db-conn assoc-in [:settings :audio :max-volume-day] 60)
            (reset! actual-volume_ 55)
            (swap! db-conn assoc-in [:settings :audio :night-start] "11:45")
            (is (= {:dispatched-levels       [[:player 80]
                                              [:player 60]
                                              [:player 50]]
                    :raise-dispatched?       false
                    :actual-volume           50
                    :wrapped-low             2
                    :wrapped-high            50
                    :conflicting-min-clamped 50}
                   {:dispatched-levels @dispatched-levels_
                    :raise-dispatched? (not= after-initial after-raise)
                    :actual-volume     @actual-volume_
                    :wrapped-low       (audio/wrap-volume db-conn policy -10)
                    :wrapped-high      (audio/wrap-volume db-conn policy 100)
                    :conflicting-min-clamped
                    (audio/wrap-volume
                     (atom (assoc-in @db-conn
                                     [:settings :audio :min-volume]
                                     80))
                     policy
                     40)})))))
      (finally
        ((::ds/stop limits/PlaybackLimitsComponent)
         {::ds/instance policy})))))

(deftest serializes-policy-enforcement-with-volume-commands
  (let [volume-lock  (Object.)
        read-started (promise)
        continue     (promise)
        dispatches_  (atom [])
        sys          {:player          :player
                      :db-conn         (atom {:settings
                                              {:audio {:min-volume 0}}})
                      :playback-limits :policy
                      :volume-lock     volume-lock}]
    (with-redefs [mp/get-volume        (fn [_]
                                         (deliver read-started true)
                                         @continue
                                         90)
                  mp/dispatch          (fn [_ _ _ level]
                                         (swap! dispatches_ conj level))
                  audio/maximum-volume (constantly 50)]
      (let [enforcement (future
                          (audio/enforce-volume-limit!
                           :player
                           volume-lock
                           {:limits {:audio/max-volume 50}}))
            command     (future
                          @read-started
                          (audio/set-volume! sys 40))]
        @read-started
        (deliver continue true)
        @enforcement
        @command
        (is (= [50 40] @dispatches_))))))

(deftest sleep-fade-bypasses-the-minimum-volume-and-stops-at-zero
  (let [dispatches_ (atom [])
        sys         {:player      :player
                     :volume-lock (Object.)}]
    (with-redefs [mp/dispatch (fn [& args]
                                (swap! dispatches_ conj args))]
      (audio/command-handler
       sys
       {:value {:action :audio/sleep-fade-step
                :volume 0
                :stop?  true}})
      (audio/command-handler
       sys
       {:value {:action :audio/sleep-fade-step
                :volume 110
                :stop?  false}}))
    (is (= [[:player :mixer/set-volume :level 0]
            [:player :playback/stop]
            [:player :mixer/set-volume :level 100]]
           @dispatches_))))