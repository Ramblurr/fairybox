(ns fairy.box.switchboard-test
  (:require
   [babashka.fs :as fs]
   [clojure.core.async :as async]
   [clojure.test :refer [deftest is]]
   [fairy.box.media-test-utils :as media]
   [fairy.box.switchboard :as switchboard]))

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
    (switchboard/rfid-removed-play-mode system {:uid "card-a"})
    (let [removed-actions (player-actions (drain-events emitter))]
      (switchboard/rfid-placed-play-mode system {:uid "card-a"})
      (let [result {:removed  removed-actions
                    :returned (player-actions (drain-events emitter))}]
        (async/close! emitter)
        result))))

(deftest applies-card-removal-and-return-settings
  (fs/with-temp-dir [temp-dir {:prefix "fairybox-card-behavior-"}]
    (let [{:keys [settings]} (media/populate-media-tree! temp-dir)
          state_             (var-get (ns-resolve 'fairy.box.switchboard
                                                  'state))
          previous-state     @state_]
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
          (reset! state_ previous-state))))))
