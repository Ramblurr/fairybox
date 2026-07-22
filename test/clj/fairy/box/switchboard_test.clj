(ns fairy.box.switchboard-test
  (:require
   [babashka.fs :as fs]
   [clojure.core.async :as async]
   [clojure.java.shell :as shell]
   [clojure.test :refer [deftest is]]
   [clojure.tools.logging.test :as log-test]
   [fairy.box.audio :as audio]
   [fairy.box.audio.system2 :as audio-system]
   [fairy.box.media-test-utils :as media]
   [fairy.box.switchboard :as switchboard]
   [fairy.box.tts.speech :as speech]
   [jp.nijohando.event :as ev]))

(defn- drain-events [channel]
  (loop [events []]
    (if-let [event (async/poll! channel)]
      (recur (conj events event))
      events)))

(defn- player-actions [events]
  (->> events
       (filter #(= "/player/commands" (:path %)))
       (mapv #(get-in % [:value :action]))))

(defn- graceful-system-control-result [state_ settings operation]
  (let [emitter   (async/chan 12)
        commands_ (atom [])]
    (try
      (with-redefs [shell/sh
                    (fn [& command]
                      (swap! commands_ conj (vec command))
                      {:exit 0 :out "" :err ""})]
        (reset! state_ {:system-state             :system-state/ready
                        :system-mode              :system-mode/normal
                        :rfid nil
                        :active-card-uid          nil
                        :removed-card             nil
                        :pending-system-operation nil})
        (switchboard/system-handler
         {:emitter emitter :settings settings}
         {:value {:event operation}})
        (let [request-events    (drain-events emitter)
              pending-operation (:pending-system-operation @state_)]
          (switchboard/system-handler
           {:emitter emitter :settings settings}
           {:value {:event :system/cooling-down}})
          (let [cooling-events (drain-events emitter)]
            (switchboard/player-handler
             {:emitter emitter}
             {:value {:event :player/one-shot-finished
                      :id    :shutdown-sound}})
            (let [sound-finished-events (drain-events emitter)
                  early-commands        @commands_]
              (switchboard/system-handler
               {:emitter emitter :settings settings}
               {:value {:event :system/shutdown}})
              {:request-events        request-events
               :pending-operation     pending-operation
               :cooling-events        cooling-events
               :sound-finished-events sound-finished-events
               :early-commands        early-commands
               :shutdown-events       (drain-events emitter)
               :final-state           (select-keys
                                       @state_
                                       [:system-state
                                        :pending-system-operation])
               :commands              @commands_}))))
      (finally
        (async/close! emitter)))))

(defn- behavior-result [state_ settings removal-behavior return-behavior]
  (let [emitter (async/chan 10)
        db-conn (atom {:linked-tags {"card-a" {:folder "audiobooks/Author One/Book One"}}
                       :settings    {:audio {:card-removal-behavior removal-behavior
                                             :card-return-behavior  return-behavior}}})
        system  {:emitter  emitter
                 :db-conn  db-conn
                 :settings settings}]
    (reset! state_ {:system-state             :system-state/ready
                    :system-mode              :system-mode/normal
                    :rfid {:action :placed :uid "card-a"}
                    :active-card-uid          "card-a"
                    :removed-card             nil
                    :pending-system-operation nil})
    (swap! audio-system/audio-state assoc-in [:playback :state] :playing)
    (switchboard/rfid-removed-play-mode system {:uid "card-a"})
    (let [removed-actions (player-actions (drain-events emitter))]
      (switchboard/rfid-placed-play-mode system {:uid "card-a"})
      (let [result {:removed  removed-actions
                    :returned (player-actions (drain-events emitter))}]
        (async/close! emitter)
        result))))

(defn- playback-command [state_ settings announce?]
  (let [emitter (async/chan 4)
        db-conn (atom {:linked-tags
                       {"card-a"
                        {:folder "audiobooks/Author One/Book One"}}
                       :settings    {:tts {:announce-tracks? announce?}}})]
    (reset! state_ {:system-state             :system-state/ready
                    :system-mode              :system-mode/normal
                    :rfid nil
                    :active-card-uid          nil
                    :removed-card             nil
                    :pending-system-operation nil})
    (switchboard/rfid-placed-play-mode
     {:emitter emitter :db-conn db-conn :settings settings}
     {:uid "card-a"})
    (let [command (->> (drain-events emitter)
                       (filter #(= "/player/commands" (:path %)))
                       first
                       :value)
          result  (assoc (dissoc command :request-id)
                         :request-id?
                         (uuid? (:request-id command)))]
      (async/close! emitter)
      result)))

(defn- card-identification-command [announce?]
  (let [emitter (async/chan 4)
        db-conn (atom {:linked-tags {}
                       :settings    {:tts {:announce-tracks? announce?}}})]
    (switchboard/rfid-placed-card-id-mode
     {:emitter emitter :db-conn db-conn :settings {}}
     {:uid "empty-card"})
    (let [command (->> (drain-events emitter)
                       (filter #(= "/tts/commands" (:path %)))
                       first
                       :value)]
      (async/close! emitter)
      command)))

(deftest applies-card-removal-and-return-settings
  (fs/with-temp-dir [temp-dir {:prefix "fairybox-card-behavior-"}]
    (let [{:keys [settings]}   (media/populate-media-tree! temp-dir)
          state_               (var-get (ns-resolve 'fairy.box.switchboard
                                                    'state))
          previous-state       @state_
          previous-audio-state @audio-system/audio-state]
      (try
        (is (= {:pause-and-restart {:removed  [:audio/pause]
                                    :returned [:audio/play-path]}
                :pause-and-resume  {:removed  [:audio/pause]
                                    :returned [:audio/play]}
                :keep-playing      {:removed  []
                                    :returned []}}
               {:pause-and-restart (behavior-result state_
                                                    settings
                                                    :pause
                                                    :restart)
                :pause-and-resume  (behavior-result state_
                                                    settings
                                                    :pause
                                                    :resume)
                :keep-playing      (behavior-result state_
                                                    settings
                                                    :keep-playing
                                                    :restart)}))
        (finally
          (reset! state_ previous-state)
          (reset! audio-system/audio-state previous-audio-state))))))

(deftest emits-policy-free-playback-intent-for-rfid-cards
  (fs/with-temp-dir [temp-dir {:prefix "fairybox-track-announcement-"}]
    (let [{:keys [settings]} (media/populate-media-tree! temp-dir)
          state_             (var-get (ns-resolve 'fairy.box.switchboard
                                                  'state))
          previous-state     @state_
          expected-playback  {:action      :audio/play-path
                              :item-path   (str (fs/canonicalize
                                                 (fs/path
                                                  temp-dir
                                                  "audiobooks/Author One/Book One")))
                              :uid         "card-a"
                              :request-id? true}]
      (try
        (is (= {:normal-playback
                {:disabled expected-playback
                 :enabled  expected-playback}
                :card-identification
                {:disabled {:action              :tts/speak
                            :audio/play-one-shot false
                            :text                "This one is empty."}
                 :enabled  {:action              :tts/speak
                            :audio/play-one-shot false
                            :text                "This one is empty."}}}
               {:normal-playback
                {:disabled (playback-command state_ settings false)
                 :enabled  (playback-command state_ settings true)}
                :card-identification
                {:disabled (card-identification-command false)
                 :enabled  (card-identification-command true)}}))
        (finally
          (reset! state_ previous-state))))))

(deftest card-identification-uses-audio-component-for-metadata
  (let [emitter         (async/chan 1)
        audio-component ::audio-component
        item-path       "/srv/media/audiobooks/From Nonna"
        metadata        [{:title  "Berenstain Bears and the Truth"
                          :album  nil
                          :artist nil}]]
    (try
      (with-redefs [audio/metadata-for
                    (fn [actual-audio-component actual-item-path]
                      (if (= [audio-component item-path]
                             [actual-audio-component actual-item-path])
                        metadata
                        (throw (ex-info "Unexpected metadata source"
                                        {:audio-component
                                         actual-audio-component
                                         :item-path
                                         actual-item-path}))))]
        (switchboard/speak-card-contents
         {:audio-system audio-component
          :emitter      emitter
          :player       ::wrong-player}
         item-path)
        (is (= {:command
                {:path  "/tts/commands"
                 :value {:action :tts/speak
                         :text
                         (speech/plan
                          [(speech/text "This one has ")
                           (speech/pause 1000)
                           (speech/text
                            "\"Berenstain Bears and the Truth\"")])}}
                :audio-ref
                [:donut.system/ref
                 [:fairy.box/components
                  :fairy.box.audio.system2/player]]}
               {:command   (async/<!! emitter)
                :audio-ref (get-in switchboard/SwitchboardComponent
                                   [:donut.system/config
                                    :audio-system])})))
      (finally
        (async/close! emitter)))))

(deftest ignores-card-removal-when-playback-is-manually-paused
  (let [state_               (var-get (ns-resolve 'fairy.box.switchboard
                                                  'state))
        previous-state       @state_
        previous-audio-state @audio-system/audio-state
        emitter              (async/chan 10)
        db-conn              (atom {:settings {:audio {:card-removal-behavior :pause
                                                       :card-return-behavior  :resume}}})
        system               {:emitter emitter :db-conn db-conn}]
    (try
      (reset! state_ {:system-state             :system-state/ready
                      :system-mode              :system-mode/normal
                      :rfid {:action :placed :uid "card-a"}
                      :active-card-uid          "card-a"
                      :removed-card             nil
                      :pending-system-operation nil})
      (swap! audio-system/audio-state assoc-in [:playback :state] :paused)

      (switchboard/rfid-handler system
                                {:value {:action :removed :uid "card-a"}})

      (is (= {:player-actions [:audio/adjust-volume]
              :playback-state :paused
              :removed-card   {:uid              "card-a"
                               :removal-behavior :pause}}
             {:player-actions (player-actions (drain-events emitter))
              :playback-state (get-in @audio-system/audio-state
                                      [:playback :state])
              :removed-card   (:removed-card @state_)}))
      (finally
        (async/close! emitter)
        (reset! state_ previous-state)
        (reset! audio-system/audio-state previous-audio-state)))))

(deftest logs-rfid-card-transitions
  (let [state_         (var-get (ns-resolve 'fairy.box.switchboard 'state))
        previous-state @state_
        emitter        (async/chan 16)
        system         {:emitter  emitter
                        :db-conn  (atom {})
                        :settings {}}]
    (try
      (reset! state_ {:system-state             :system-state/booting
                      :system-mode              :system-mode/normal
                      :rfid nil
                      :active-card-uid          nil
                      :removed-card             nil
                      :pending-system-operation nil})
      (log-test/with-log
        (switchboard/rfid-handler
         system
         {:value {:action :placed :uid "card-a"}})
        (switchboard/rfid-handler
         system
         {:value {:action :removed :uid "card-a"}})
        (is (= [{:level   :info
                 :message "RFID card placed {:uid card-a}"}
                {:level   :info
                 :message "RFID card removed {:uid card-a}"}]
               (mapv #(select-keys % [:level :message])
                     (log-test/the-log)))))
      (finally
        (async/close! emitter)
        (reset! state_ previous-state)))))

(deftest logs-system-state-and-mode-transitions
  (let [state_         (var-get (ns-resolve 'fairy.box.switchboard 'state))
        previous-state @state_
        emitter        (async/chan 32)
        settings       {:shutdown {:poweroff-enabled? true}}
        system         {:emitter emitter :settings settings}]
    (try
      (with-redefs [shell/sh (constantly {:exit 0 :out "" :err ""})]
        (reset! state_ {:system-state             :system-state/booting
                        :system-mode              :system-mode/normal
                        :rfid nil
                        :active-card-uid          nil
                        :removed-card             nil
                        :pending-system-operation nil})
        (log-test/with-log
          (doseq [event [:system/initialized
                         :system/warming-up
                         :system/warmed-up
                         :system/poweroff
                         :system/cooling-down
                         :system/shutdown]]
            (switchboard/system-handler system {:value {:event event}}))
          (switchboard/change-mode!
           system :system-mode/card-identification)
          (switchboard/change-mode! system :system-mode/normal)
          (switchboard/change-mode! system :system-mode/normal)
          (is (= [{:level :info
                   :message
                   (str "System state changed {:from :system-state/booting, "
                        ":to :system-state/initialized}")}
                  {:level :info
                   :message
                   (str "System state changed {:from :system-state/initialized, "
                        ":to :system-state/warming-up}")}
                  {:level :info
                   :message
                   (str "System state changed {:from :system-state/warming-up, "
                        ":to :system-state/ready}")}
                  {:level :info
                   :message
                   (str "System state changed {:from :system-state/ready, "
                        ":to :system-state/cooling-down}")}
                  {:level :info
                   :message
                   (str "System state changed {:from :system-state/cooling-down, "
                        ":to :system-state/shutdown}")}
                  {:level :info
                   :message
                   (str "System mode changed {:from :system-mode/normal, "
                        ":to :system-mode/card-identification}")}
                  {:level :info
                   :message
                   (str "System mode changed {:from :system-mode/card-identification, "
                        ":to :system-mode/normal}")}]
                 (->> (log-test/the-log)
                      (filter #(re-find #"^System (?:state|mode) changed"
                                        (:message %)))
                      (mapv #(select-keys % [:level :message])))))))
      (finally
        (async/close! emitter)
        (reset! state_ previous-state)))))

(deftest resets-card-state-when-switchboard-starts
  (let [state_         (var-get (ns-resolve 'fairy.box.switchboard 'state))
        previous-state @state_
        bus            (ev/bus)]
    (try
      (reset! state_ {:system-state             :system-state/ready
                      :system-mode              :system-mode/normal
                      :rfid {:action :removed :uid "card-a"}
                      :active-card-uid          "card-a"
                      :removed-card             {:uid              "card-a"
                                                 :removal-behavior :pause}
                      :pending-system-operation :system/reboot})
      (let [instance (switchboard/init-switchboard! {:bus bus})]
        (try
          (is (= {:system-state             :system-state/booting
                  :system-mode              :system-mode/normal
                  :rfid nil
                  :active-card-uid          nil
                  :removed-card             nil
                  :card-feedback            nil
                  :pending-system-operation nil}
                 @state_))
          (finally
            (switchboard/halt-switchboard! instance))))
      (finally
        (ev/close! bus)
        (reset! state_ previous-state)))))

(deftest immediate-poweroff-skips-shutdown-audio-and-honors-the-safety-switch
  (let [state_         (var-get (ns-resolve 'fairy.box.switchboard 'state))
        previous-state @state_
        emitter        (async/chan 8)
        commands_      (atom [])
        enabled        {:shutdown {:poweroff-enabled? true}}
        disabled       {:shutdown {:poweroff-enabled? false}}]
    (try
      (with-redefs [shell/sh
                    (fn [& command]
                      (swap! commands_ conj (vec command))
                      {:exit 0 :out "" :err ""})]
        (reset! state_ (assoc previous-state
                              :system-state :system-state/ready
                              :pending-system-operation nil))
        (switchboard/system-handler
         {:emitter emitter :settings enabled}
         {:value {:event :system/poweroff-now :reason :sleep}})
        (let [enabled-events (drain-events emitter)
              enabled-state  (:system-state @state_)]
          (reset! state_ (assoc previous-state
                                :system-state :system-state/ready
                                :pending-system-operation nil))
          (switchboard/system-handler
           {:emitter emitter :settings disabled}
           {:value {:event :system/poweroff-now :reason :sleep}})
          (is (= {:enabled-events
                  [{:path  "/player/commands"
                    :value {:action :audio/stop}}
                   {:path  "/hardware/output/leds"
                    :value {:action :led/set
                            :groups [:all]
                            :value  0.0}}]
                  :enabled-state  :system-state/shutdown
                  :disabled-events
                  [{:path  "/player/commands"
                    :value {:action :audio/stop}}
                   {:path  "/hardware/output/leds"
                    :value {:action :led/set
                            :groups [:all]
                            :value  0.0}}]
                  :disabled-state :system-state/ready
                  :commands       [["systemctl" "poweroff"]]}
                 {:enabled-events  enabled-events
                  :enabled-state   enabled-state
                  :disabled-events (drain-events emitter)
                  :disabled-state  (:system-state @state_)
                  :commands        @commands_}))))
      (finally
        (async/close! emitter)
        (reset! state_ previous-state)))))

(deftest manual-system-controls-play-the-shutdown-sound-before-the-command
  (let [state_         (var-get (ns-resolve 'fairy.box.switchboard 'state))
        previous-state @state_
        settings       {:media    {:media-dir "/tmp"}
                        :sfx      {:shutdown "shutdown.mp3"}
                        :shutdown {:poweroff-enabled? true}}
        operations     [:system/poweroff
                        :system/reboot
                        :system/restart-fairybox]
        flow-keys      [:request-events
                        :cooling-events
                        :sound-finished-events
                        :early-commands
                        :shutdown-events
                        :final-state]
        expected-flow  {:request-events
                        [{:path  "/system"
                          :value {:event :system/cooling-down}}]
                        :cooling-events
                        [{:path  "/player/commands"
                          :value {:action :audio/stop}}
                         {:path  "/hardware/output/leds"
                          :value {:action      :led/fade
                                  :groups      [:all]
                                  :duration    3000
                                  :from        1.0
                                  :to          0.0
                                  :after-set   0.0
                                  :start-delay 14000}}
                         {:path  "/player/commands"
                          :value {:action    :audio/play-one-shot
                                  :id        :shutdown-sound
                                  :item-path "/tmp/shutdown.mp3"}}]
                        :sound-finished-events
                        [{:path  "/system"
                          :value {:event :system/shutdown}}]
                        :early-commands []
                        :shutdown-events
                        [{:path  "/hardware/output/leds"
                          :value {:action :led/set
                                  :groups [:all]
                                  :value  0.0}}]
                        :final-state
                        {:system-state             :system-state/shutdown
                         :pending-system-operation nil}}]
    (try
      (let [results (mapv #(graceful-system-control-result
                            state_ settings %)
                          operations)]
        (is (= {:pending-operations operations
                :flows              (repeat 3 expected-flow)
                :commands
                [[["systemctl" "poweroff"]]
                 [["systemctl" "reboot"]]
                 [["systemctl" "--user" "restart"
                   "fairybox.service"]]]}
               {:pending-operations (mapv :pending-operation results)
                :flows              (mapv #(select-keys % flow-keys)
                                          results)
                :commands           (mapv :commands results)})))
      (finally
        (reset! state_ previous-state)))))

(deftest manual-system-controls-honor-the-profile-safety-gate
  (let [state_         (var-get (ns-resolve 'fairy.box.switchboard 'state))
        previous-state @state_
        emitter        (async/chan 8)
        commands_      (atom [])
        settings       {:shutdown {:poweroff-enabled? false}}]
    (try
      (with-redefs [shell/sh
                    (fn [& command]
                      (swap! commands_ conj (vec command))
                      {:exit 0 :out "" :err ""})]
        (reset! state_ (assoc previous-state
                              :system-state :system-state/ready
                              :pending-system-operation nil))
        (doseq [operation [:system/poweroff
                           :system/reboot
                           :system/restart-fairybox]]
          (switchboard/system-handler
           {:emitter emitter :settings settings}
           {:value {:event operation}}))
        (is (= {:events   []
                :state    {:system-state             :system-state/ready
                           :pending-system-operation nil}
                :commands []}
               {:events   (drain-events emitter)
                :state    (select-keys @state_
                                       [:system-state
                                        :pending-system-operation])
                :commands @commands_})))
      (finally
        (async/close! emitter)
        (reset! state_ previous-state)))))

(deftest graceful-system-control-completes-without-a-shutdown-sound
  (let [state_         (var-get (ns-resolve 'fairy.box.switchboard 'state))
        previous-state @state_
        emitter        (async/chan 8)
        commands_      (atom [])
        settings       {:shutdown {:poweroff-enabled? true}}]
    (with-redefs [shell/sh
                  (fn [& command]
                    (swap! commands_ conj (vec command))
                    {:exit 0 :out "" :err ""})]
      (reset! state_ (assoc previous-state
                            :system-state :system-state/ready
                            :pending-system-operation nil))
      (switchboard/system-handler
       {:emitter emitter :settings settings}
       {:value {:event :system/poweroff}})
      (let [request-events (drain-events emitter)]
        (switchboard/system-handler
         {:emitter emitter :settings settings}
         {:value (:value (first request-events))})
        (let [cooling-events (drain-events emitter)
              early-commands @commands_]
          (switchboard/system-handler
           {:emitter emitter :settings settings}
           {:value (:value (last cooling-events))})
          (is (= {:request-events
                  [{:path  "/system"
                    :value {:event :system/cooling-down}}]
                  :cooling-events
                  [{:path  "/player/commands"
                    :value {:action :audio/stop}}
                   {:path  "/hardware/output/leds"
                    :value {:action      :led/fade
                            :groups      [:all]
                            :duration    3000
                            :from        1.0
                            :to          0.0
                            :after-set   0.0
                            :start-delay 14000}}
                   {:path  "/system"
                    :value {:event :system/shutdown}}]
                  :early-commands []
                  :shutdown-events
                  [{:path  "/hardware/output/leds"
                    :value {:action :led/set
                            :groups [:all]
                            :value  0.0}}]
                  :commands       [["systemctl" "poweroff"]]}
                 {:request-events  request-events
                  :cooling-events  cooling-events
                  :early-commands  early-commands
                  :shutdown-events (drain-events emitter)
                  :commands        @commands_}))))
      (async/close! emitter)
      (reset! state_ previous-state))))

(deftest first-matching-opening-shows-here-we-go-once
  (let [state_         (var-get (ns-resolve 'fairy.box.switchboard 'state))
        previous-state @state_
        emitter        (async/chan 16)
        db-conn        (atom {:settings {:led-language? true}})
        system         {:emitter emitter :db-conn db-conn}
        here-we-go     (var-get (ns-resolve 'fairy.box.switchboard
                                            'here-we-go-pattern))]
    (try
      (reset! state_ {:system-state  :system-state/ready
                      :system-mode   :system-mode/normal
                      :card-feedback {:request-id       :request-b
                                      :uid              "card-b"
                                      :awaiting-start?  true
                                      :problem-handled? false
                                      :led-eligible?    true}})
      (switchboard/player-handler
       system
       {:value {:event      :player/state-changed
                :state      :opening
                :request-id :request-a}})
      (let [old-opening (drain-events emitter)]
        (switchboard/player-handler
         system
         {:value {:event      :player/state-changed
                  :state      :opening
                  :request-id :request-b}})
        (let [first-opening (drain-events emitter)]
          (switchboard/player-handler
           system
           {:value {:event      :player/state-changed
                    :state      :opening
                    :request-id :request-b}})
          (is (= {:old-opening     []
                  :first-opening
                  [{:path  "/hardware/output/leds"
                    :value {:action       :led/animation-cancel
                            :animation-id :card-playback-feedback}}
                   {:path  "/hardware/output/leds"
                    :value here-we-go}]
                  :later-opening   []
                  :awaiting-start? false}
                 {:old-opening   old-opening
                  :first-opening first-opening
                  :later-opening (drain-events emitter)
                  :awaiting-start?
                  (get-in @state_ [:card-feedback :awaiting-start?])}))))
      (finally
        (async/close! emitter)
        (reset! state_ previous-state)))))

(deftest linked-card-breathes-after-acknowledgement-delay
  (fs/with-temp-dir [temp-dir {:prefix "fairybox-feedback-delay-"}]
    (let [{:keys [settings]} (media/populate-media-tree! temp-dir)
          state_             (var-get (ns-resolve 'fairy.box.switchboard
                                                  'state))
          previous-state     @state_
          emitter            (async/chan 16)
          db-conn            (atom {:linked-tags
                                    {"card-a"
                                     {:folder
                                      "audiobooks/Author One/Book One"}}
                                    :settings    {:led-language? true
                                                  :audio
                                                  {:card-return-behavior
                                                   :restart}}})
          system             {:emitter  emitter
                              :db-conn  db-conn
                              :settings settings}
          known-pattern      (var-get
                              (ns-resolve 'fairy.box.switchboard
                                          'known-card-pattern))
          preparation-pattern
          (var-get (ns-resolve 'fairy.box.switchboard
                               'preparation-pattern))]
      (try
        (reset! state_ {:system-state  :system-state/ready
                        :system-mode   :system-mode/normal
                        :card-feedback nil
                        :removed-card  nil})
        (switchboard/rfid-placed-play-mode system {:uid "card-a"})
        (let [initial    (drain-events emitter)
              command    (->> initial
                              (filter #(= "/player/commands" (:path %)))
                              first
                              :value)
              request-id (:request-id command)]
          (Thread/sleep 550)
          (is (= {:initial-led known-pattern
                  :command     {:action      :audio/play-path
                                :item-path   (str (fs/canonicalize
                                                   (fs/path
                                                    temp-dir
                                                    "audiobooks/Author One/Book One")))
                                :uid         "card-a"
                                :request-id? true}
                  :delayed     [{:path  "/hardware/output/leds"
                                 :value preparation-pattern}]
                  :state       {:request-id           request-id
                                :awaiting-start?      true
                                :preparation-visible? true}}
                 {:initial-led (->> initial
                                    (filter #(= "/hardware/output/leds"
                                                (:path %)))
                                    first
                                    :value)
                  :command     (assoc (dissoc command :request-id)
                                      :request-id?
                                      (uuid? request-id))
                  :delayed     (drain-events emitter)
                  :state       (select-keys (:card-feedback @state_)
                                            [:request-id
                                             :awaiting-start?
                                             :preparation-visible?])})))
        (finally
          (async/close! emitter)
          (reset! state_ previous-state))))))

(deftest unknown-card-speech-and-led-language-are-independent
  (let [state_         (var-get (ns-resolve 'fairy.box.switchboard 'state))
        previous-state @state_
        unknown-pattern
        (var-get (ns-resolve 'fairy.box.switchboard
                             'unknown-card-pattern))
        run-case
        (fn [led-language?]
          (let [emitter (async/chan 8)
                system  {:emitter  emitter
                         :db-conn  (atom {:linked-tags {}
                                          :settings
                                          {:led-language? led-language?}})
                         :settings {}}]
            (try
              (reset! state_ {:system-state  :system-state/ready
                              :system-mode   :system-mode/normal
                              :card-feedback nil})
              (switchboard/rfid-placed-play-mode system {:uid "unknown"})
              (drain-events emitter)
              (finally
                (async/close! emitter)))))]
    (try
      (is (= {:enabled
              [{:path  "/player/commands"
                :value {:action :audio/clear}}
               {:path  "/tts/commands"
                :value {:action              :tts/speak
                        :feedback/type       :unknown-card
                        :audio/play-one-shot true
                        :text                "Uh-oh. This card is new to me."}}
               {:path  "/hardware/output/leds"
                :value unknown-pattern}]
              :disabled
              [{:path  "/player/commands"
                :value {:action :audio/clear}}
               {:path  "/tts/commands"
                :value {:action              :tts/speak
                        :feedback/type       :unknown-card
                        :audio/play-one-shot true
                        :text                "Uh-oh. This card is new to me."}}]}
             {:enabled  (run-case true)
              :disabled (run-case false)}))
      (finally
        (reset! state_ previous-state)))))

(deftest playback-problem-leds-are-independently-gated
  (let [state_         (var-get (ns-resolve 'fairy.box.switchboard 'state))
        previous-state @state_
        problem-pattern
        (var-get (ns-resolve 'fairy.box.switchboard
                             'playback-problem-pattern))
        run-case
        (fn [led-language?]
          (let [emitter (async/chan 8)
                system  {:emitter emitter
                         :db-conn (atom {:settings
                                         {:led-language? led-language?}})}
                command {:value {:action        :tts/speak
                                 :feedback/type :card-playback-problem
                                 :request-id    :request-a
                                 :problem       :missing-media}}]
            (try
              (reset! state_
                      {:system-state  :system-state/ready
                       :system-mode   :system-mode/normal
                       :card-feedback {:request-id       :request-a
                                       :awaiting-start?  true
                                       :problem-handled? false
                                       :led-eligible?    true}})
              (switchboard/tts-command-handler system command)
              (switchboard/tts-command-handler system command)
              {:events   (drain-events emitter)
               :feedback (select-keys (:card-feedback @state_)
                                      [:awaiting-start?
                                       :problem-handled?])}
              (finally
                (async/close! emitter)))))]
    (try
      (is (= {:enabled
              {:events
               [{:path  "/hardware/output/leds"
                 :value {:action       :led/animation-cancel
                         :animation-id :card-playback-feedback}}
                {:path  "/hardware/output/leds"
                 :value problem-pattern}]
               :feedback {:awaiting-start?  false
                          :problem-handled? true}}
              :disabled
              {:events
               [{:path  "/hardware/output/leds"
                 :value {:action       :led/animation-cancel
                         :animation-id :card-playback-feedback}}]
               :feedback {:awaiting-start?  false
                          :problem-handled? true}}}
             {:enabled  (run-case true)
              :disabled (run-case false)}))
      (finally
        (reset! state_ previous-state)))))

(deftest existing-bus-events-drive-one-card-start-and-problem
  (let [state_          (var-get (ns-resolve 'fairy.box.switchboard 'state))
        previous-state  @state_
        bus             (ev/bus)
        source          (async/chan 8)
        led-events      (async/chan 8)
        db-conn         (atom {:settings {:led-language? true}})
        here-we-go      (var-get (ns-resolve 'fairy.box.switchboard
                                             'here-we-go-pattern))
        problem-pattern (var-get (ns-resolve 'fairy.box.switchboard
                                             'playback-problem-pattern))
        take-event      (fn []
                          (let [[event port]
                                (async/alts!! [led-events
                                               (async/timeout 1000)])]
                            (when (= port led-events)
                              (select-keys event [:path :value]))))
        instance        (switchboard/init-switchboard!
                         {:bus bus :db-conn db-conn})]
    (try
      (ev/emitize bus source)
      (ev/listen bus "/hardware/output/leds" led-events)
      (reset! state_ {:system-state  :system-state/ready
                      :system-mode   :system-mode/normal
                      :card-feedback {:request-id       :request-a
                                      :awaiting-start?  true
                                      :problem-handled? false
                                      :led-eligible?    true}})
      (async/>!! source
                 {:path  "/player/events"
                  :value {:event      :player/state-changed
                          :state      :opening
                          :request-id :request-a}})
      (let [start-events [(take-event) (take-event)]]
        (async/>!! source
                   {:path  "/player/events"
                    :value {:event      :player/state-changed
                            :state      :opening
                            :request-id :request-a}})
        (let [[later-opening port]
              (async/alts!! [led-events (async/timeout 50)])]
          (async/>!! source
                     {:path  "/tts/commands"
                      :value {:action        :tts/speak
                              :feedback/type :card-playback-problem
                              :request-id    :request-a
                              :problem       :missing-media}})
          (is (= {:start
                  [{:path  "/hardware/output/leds"
                    :value {:action       :led/animation-cancel
                            :animation-id :card-playback-feedback}}
                   {:path  "/hardware/output/leds"
                    :value here-we-go}]
                  :later-opening            nil
                  :later-opening-timed-out? true
                  :problem
                  [{:path  "/hardware/output/leds"
                    :value {:action       :led/animation-cancel
                            :animation-id :card-playback-feedback}}
                   {:path  "/hardware/output/leds"
                    :value problem-pattern}]}
                 {:start                    start-events
                  :later-opening            later-opening
                  :later-opening-timed-out? (not= port led-events)
                  :problem                  [(take-event) (take-event)]}))))
      (finally
        (switchboard/halt-switchboard! instance)
        (async/close! source)
        (async/close! led-events)
        (ev/close! bus)
        (reset! state_ previous-state)))))

(deftest card-feedback-patterns-preserve-the-visual-vocabulary
  (let [summary
        (fn [symbol]
          (let [pattern (var-get (ns-resolve 'fairy.box.switchboard symbol))]
            {:repeat-times       (get pattern :repeat-times 1)
             :relative-to-limit? (:relative-to-limit? pattern)
             :after-set          (:after-set pattern)
             :tweens
             (mapv #(select-keys %
                                 [:names :groups :from :to
                                  :duration :delay :easing])
                   (:tweens pattern))}))]
    (is (= {:known
            {:repeat-times       2
             :relative-to-limit? true
             :after-set          1.0
             :tweens
             [{:names    [:audio/volume-up :audio/volume-down]
               :from     1.0
               :to       0.15
               :duration 80
               :easing   :out-sine}
              {:names    [:audio/volume-up :audio/volume-down]
               :from     0.15
               :to       1.0
               :duration 120
               :delay    80
               :easing   :out-sine}]}
            :preparation
            {:repeat-times       :forever
             :relative-to-limit? true
             :after-set          1.0
             :tweens
             [{:names    [:audio/play-pause]
               :from     1.0
               :to       0.25
               :duration 700
               :easing   :in-out-sine}
              {:names    [:audio/play-pause]
               :from     0.25
               :to       1.0
               :duration 700
               :delay    700
               :easing   :in-out-sine}]}
            :here-we-go
            {:repeat-times       1
             :relative-to-limit? true
             :after-set          1.0
             :tweens
             [{:groups   [:all]
               :from     1.0
               :to       0.15
               :duration 100
               :easing   :out-sine}
              {:groups   [:all]
               :from     0.15
               :to       1.0
               :duration 200
               :delay    150
               :easing   :out-sine}]}
            :unknown
            {:repeat-times       3
             :relative-to-limit? true
             :after-set          1.0
             :tweens
             [{:names    [:audio/prev]
               :from     1.0
               :to       0.15
               :duration 100
               :easing   :out-sine}
              {:names    [:audio/prev]
               :from     0.15
               :to       1.0
               :duration 100
               :delay    100
               :easing   :out-sine}
              {:names    [:audio/next]
               :from     1.0
               :to       0.15
               :duration 100
               :delay    100
               :easing   :out-sine}
              {:names    [:audio/next]
               :from     0.15
               :to       1.0
               :duration 100
               :delay    200
               :easing   :out-sine}]}
            :problem
            {:repeat-times       3
             :relative-to-limit? true
             :after-set          1.0
             :tweens
             [{:groups   [:all]
               :from     1.0
               :to       0.05
               :duration 80
               :easing   :out-sine}
              {:groups   [:all]
               :from     0.05
               :to       1.0
               :duration 100
               :delay    80
               :easing   :out-sine}]}}
           {:known       (summary 'known-card-pattern)
            :preparation (summary 'preparation-pattern)
            :here-we-go  (summary 'here-we-go-pattern)
            :unknown     (summary 'unknown-card-pattern)
            :problem     (summary 'playback-problem-pattern)}))))