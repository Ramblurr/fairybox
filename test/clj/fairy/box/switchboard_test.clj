(ns fairy.box.switchboard-test
  (:require
   [babashka.fs :as fs]
   [clojure.core.async :as async]
   [clojure.java.shell :as shell]
   [clojure.test :refer [deftest is]]
   [fairy.box.audio.system2 :as audio-system]
   [fairy.box.media-test-utils :as media]
   [fairy.box.switchboard :as switchboard]
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
                       :value)]
      (async/close! emitter)
      command)))

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
          expected-playback  {:action    :audio/play-path
                              :item-path (str (fs/canonicalize
                                               (fs/path
                                                temp-dir
                                                "audiobooks/Author One/Book One")))
                              :uid       "card-a"}]
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