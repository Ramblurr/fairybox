(ns fairy.box2.player-test
  (:require
   [clojure.test :refer [deftest is]]
   [fairy.box2.player :as player]
   [ol.vinyl :as vinyl]))

(deftest play-queue-dispatches-correlated-commands-in-order-test
  (let [calls_  (atom [])
        context {:presence-epoch 7
                 :request-id     #uuid "00000000-0000-0000-0000-000000000007"}]
    (with-redefs [vinyl/dispatch
                  (fn [_player & command]
                    (swap! calls_ conj (vec command))
                    true)]
      (is (= {:accepted? true}
             (player/play-queue! {:player :fake}
                                 {:paths            ["/one.mp3" "/two.mp3"]
                                  :playback-context context})))
      (is (= [[:playback/clear-all]
              [:playback/append :paths ["/one.mp3" "/two.mp3"]]
              [{:ol.vinyl/command          :playback/play
                :ol.vinyl/playback-context context}]]
             @calls_)))))

(deftest callbacks-consume-event-scoped-vinyl-context-test
  (let [callback_ (atom nil)
        latest_   (atom [])
        required_ (atom [])
        released_ (atom [])
        context-a {:presence-epoch 1
                   :request-id     #uuid "00000000-0000-0000-0000-000000000001"}
        context-b {:presence-epoch 2
                   :request-id     #uuid "00000000-0000-0000-0000-000000000002"}]
    (with-redefs [vinyl/create-player   (constantly :fake-player)
                  vinyl/release-player! #(swap! released_ conj [:release %])
                  vinyl/subscribe!      (fn [_player callback]
                                          (reset! callback_ callback)
                                          :subscription)
                  vinyl/unsubscribe!    #(swap! released_ conj [:unsubscribe %1 %2])]
      (let [adapter (player/start! {:submit!        #(swap! required_ conj %)
                                    :submit-latest! #(swap! latest_ conj %)})]
        (@callback_ {:ol.vinyl/event            :vlc/opening
                     :ol.vinyl/playback-context context-a})
        (@callback_ {:new-time                  42
                     :ol.vinyl/event            :vlc/time-changed
                     :ol.vinyl/playback-context context-b})
        (is (= [{:name :player.ev/state-changed
                 :data {:playback-context context-a
                        :state            :opening}}]
               @required_))
        (is (= [{:name :player.ev/time-changed
                 :data {:playback-context context-b
                        :time-ms          42}}]
               @latest_))
        (player/stop! adapter)
        (is (= [[:unsubscribe :fake-player :subscription]
                [:release :fake-player]]
               @released_))))))
