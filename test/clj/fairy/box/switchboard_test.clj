(ns fairy.box.switchboard-test
  (:require
   [babashka.fs :as fs]
   [clojure.core.async :as async]
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

(defn- behavior-result [state_ settings removal-behavior return-behavior]
  (let [emitter (async/chan 10)
        db-conn (atom {:linked-tags {"card-a" {:folder "audiobooks/Author One/Book One"}}
                       :settings    {:audio {:card-removal-behavior removal-behavior
                                             :card-return-behavior  return-behavior}}})
        system  {:emitter  emitter
                 :db-conn  db-conn
                 :settings settings}]
    (reset! state_ {:system-state    :system-state/ready
                    :system-mode     :system-mode/normal
                    :rfid            {:action :placed :uid "card-a"}
                    :active-card-uid "card-a"
                    :removed-card    nil})
    (swap! audio-system/audio-state assoc-in [:playback :state] :playing)
    (switchboard/rfid-removed-play-mode system {:uid "card-a"})
    (let [removed-actions (player-actions (drain-events emitter))]
      (switchboard/rfid-placed-play-mode system {:uid "card-a"})
      (let [result {:removed  removed-actions
                    :returned (player-actions (drain-events emitter))}]
        (async/close! emitter)
        result))))

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
      (reset! state_ {:system-state    :system-state/ready
                      :system-mode     :system-mode/normal
                      :rfid            {:action :placed :uid "card-a"}
                      :active-card-uid "card-a"
                      :removed-card    nil})
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
      (reset! state_ {:system-state    :system-state/ready
                      :system-mode     :system-mode/normal
                      :rfid            {:action :removed :uid "card-a"}
                      :active-card-uid "card-a"
                      :removed-card    {:uid              "card-a"
                                        :removal-behavior :pause}})
      (let [instance (switchboard/init-switchboard! {:bus bus})]
        (try
          (is (= {:system-state    :system-state/booting
                  :system-mode     :system-mode/normal
                  :rfid            nil
                  :active-card-uid nil
                  :removed-card    nil}
                 @state_))
          (finally
            (switchboard/halt-switchboard! instance))))
      (finally
        (ev/close! bus)
        (reset! state_ previous-state)))))
