(ns fairy.box.web.player-progress-test
  (:require
   [clojure.test :refer [deftest is]]
   [fairy.box.web.player-progress :as progress]
   [hyperlith.impl.router :as router]))

(deftest calculates-clamped-progress-signals
  (is (= [{:_server_progress 0.0}
          {:_server_progress 0.0}
          {:_server_progress 25.0}
          {:_server_progress 100.0}]
         (mapv #(select-keys
                 (progress/progress-signals {:playback {:position %}})
                 [:_server_progress])
               [nil -0.5 0.25 1.5]))))

(deftest calculates-server-driven-time-labels
  (is (= {:_server_time      "01:01"
          :_server_time_left "-03:03"}
         (select-keys
          (progress/progress-signals
           {:playback
            {:time          61000
             :current-track {:duration 244000}}})
          [:_server_time :_server_time_left]))))

(deftest preserves-empty-and-zero-duration-labels
  (let [signal-keys [:_server_time :_server_time_left]
        states      [{:playback {}}
                     {:playback {:time          1000
                                 :current-track {:duration 0}}}]]
    (is (= [{:_server_time      "00:00"
             :_server_time_left nil}
            {:_server_time      "00:01"
             :_server_time_left "00:00"}]
           (mapv #(select-keys (progress/progress-signals %) signal-keys)
                 states)))))

(deftest removes-the-dedicated-progress-stream
  (let [removed-vars '[stream-path
                       component-key
                       start-progress-stream!
                       register!
                       unregister!
                       broadcast!
                       stop-progress-stream!
                       stream-handler
                       ProgressStreamComponent]]
    (is (= {:remaining-stream-vars #{}
            :registered-route?     false}
           {:remaining-stream-vars
            (set (keep #(ns-resolve 'fairy.box.web.player-progress %)
                       removed-vars))
            :registered-route?
            (contains? (get @router/routes_ :get)
                       "/api/player/progress-stream")}))))
