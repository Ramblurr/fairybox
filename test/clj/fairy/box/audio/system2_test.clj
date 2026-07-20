(ns fairy.box.audio.system2-test
  (:require
   [babashka.fs :as fs]
   [clojure.core.async :as async]
   [clojure.test :refer [deftest is use-fixtures]]
   [fairy.box.audio.system2 :as audio]
   [fairy.box.media-test-utils :as media]
   [ol.vinyl :as mp]))

(defn- with-restored-audio-state [f]
  (let [original @audio/audio-state]
    (try
      (f)
      (finally
        (reset! audio/audio-state original)))))

(use-fixtures :each with-restored-audio-state)

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
  #_{:clj-kondo/ignore [:invalid-arity]}
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
