(ns fairy.box.web.player-progress-test
  (:require
   [clojure.test :refer [deftest is use-fixtures]]
   [fairy.box.audio.system2 :as audio-system]
   [hyperlith.impl.router :as router]
   [starfederation.datastar.clojure.adapter.http-kit :as d*http-kit]
   [starfederation.datastar.clojure.adapter.test :as d*test]
   [starfederation.datastar.clojure.protocols :as d*protocols]))

(def progress-ns 'fairy.box.web.player-progress)

(defn- progress-var [sym]
  (try
    (requiring-resolve (symbol (str progress-ns) (name sym)))
    (catch Throwable _
      nil)))

(defn- with-restored-audio-state [f]
  (let [original @audio-system/audio-state]
    (try
      (f)
      (finally
        (reset! audio-system/audio-state original)))))

(use-fixtures :each with-restored-audio-state)

(deftest calculates-clamped-progress-signals
  (let [progress-signals (progress-var 'progress-signals)]
    (is (= [{:_server_progress 0.0}
            {:_server_progress 0.0}
            {:_server_progress 25.0}
            {:_server_progress 100.0}]
           (when progress-signals
             (mapv #(select-keys
                     (progress-signals {:playback {:position %}})
                     [:_server_progress])
                   [nil -0.5 0.25 1.5]))))))

(deftest calculates-server-driven-time-labels
  (let [progress-signals (progress-var 'progress-signals)]
    (is (= {:_server_time "01:01"
            :_server_time_left "-03:03"}
           (when progress-signals
             (select-keys
              (progress-signals
               {:playback
                {:time 61000
                 :current-track {:duration 244000}}})
              [:_server_time :_server_time_left]))))))

(deftest preserves-empty-and-zero-duration-labels
  (let [progress-signals (progress-var 'progress-signals)
        signal-keys [:_server_time :_server_time_left]
        states [{:playback {}}
                {:playback {:time 1000
                            :current-track {:duration 0}}}]]
    (is (= [{:_server_time "00:00"
             :_server_time_left nil}
            {:_server_time "00:01"
             :_server_time_left "00:00"}]
           (when progress-signals
             (mapv #(select-keys (progress-signals %) signal-keys)
                   states))))))

(deftest broadcasts-progress-as-a-datastar-signal-event
  (let [start! (progress-var 'start-progress-stream!)
        register! (progress-var 'register!)
        broadcast! (progress-var 'broadcast!)
        stop! (progress-var 'stop-progress-stream!)]
    (if (every? ifn? [start! register! broadcast! stop!])
      (let [stream (start!)
            first-client (d*test/->sse-recorder)
            second-client (d*test/->sse-recorder)
            expected-event
            (str "event: datastar-patch-signals\n"
                 "data: signals "
                 "{\"_server_progress\":25.0,"
                 "\"_server_time\":\"01:01\","
                 "\"_server_time_left\":\"-03:03\"}\n\n")]
        (try
          (register! stream first-client)
          (register! stream second-client)
          (reset! audio-system/audio-state
                  {:playback
                   {:position 0.25
                    :time 61000
                    :current-track {:duration 244000}}})
          (broadcast! stream)
          (is (= [[expected-event] [expected-event]]
                 [(mapv identity @(:!rec first-client))
                  (mapv identity @(:!rec second-client))]))
          (finally
            (stop! stream))))
      (is (= :available :missing)))))

(deftest removes-failed-clients-without-blocking-healthy-clients
  (let [start! (progress-var 'start-progress-stream!)
        register! (progress-var 'register!)
        broadcast! (progress-var 'broadcast!)
        stop! (progress-var 'stop-progress-stream!)]
    (if (every? ifn? [start! register! broadcast! stop!])
      (let [stream (start!)
            healthy-client (d*test/->sse-recorder)
            closed-client
            (reify d*protocols/SSEGenerator
              (send-event! [_ _ _ _] false)
              (get-lock [_] nil)
              (close-sse! [_] nil)
              (sse-gen? [_] true))
            failed-client
            (reify d*protocols/SSEGenerator
              (send-event! [_ _ _ _]
                (throw (ex-info "send failed" {})))
              (get-lock [_] nil)
              (close-sse! [_] nil)
              (sse-gen? [_] true))]
        (try
          (doseq [client [healthy-client closed-client failed-client]]
            (register! stream client))
          (reset! audio-system/audio-state
                  {:playback
                   {:position 0.75
                    :time 183000
                    :current-track {:duration 244000}}})
          (broadcast! stream)
          (is (= {:connections #{healthy-client}
                  :healthy-events
                  [(str "event: datastar-patch-signals\n"
                        "data: signals "
                        "{\"_server_progress\":75.0,"
                        "\"_server_time\":\"03:03\","
                        "\"_server_time_left\":\"-01:01\"}\n\n")]}
                 {:connections @(:connections stream)
                  :healthy-events
                  (mapv identity @(:!rec healthy-client))}))
          (finally
            (stop! stream))))
      (is (= :available :missing)))))

(deftest stream-sends-current-progress-on-open-and-unregisters-on-close
  (let [start! (progress-var 'start-progress-stream!)
        stop! (progress-var 'stop-progress-stream!)
        handler (progress-var 'stream-handler)]
    (if (every? ifn? [start! stop! handler])
      (let [stream (start!)]
        (try
          (reset! audio-system/audio-state
                  {:playback
                   {:position 0.5
                    :time 122000
                    :current-track {:duration 244000}}})
          (let [response
                (with-redefs [d*http-kit/->sse-response
                              (fn [_req opts]
                                (let [client (d*test/->sse-recorder)]
                                  ((get opts d*http-kit/on-open) client)
                                  {:client client :opts opts}))]
                  (handler {:fairy.box/component (constantly stream)}))
                client (:client response)
                initial-events (mapv identity @(:!rec client))]
            ((get (:opts response) d*http-kit/on-close) client :normal)
            (is (= {:initial-events
                    [(str "event: datastar-patch-signals\n"
                          "data: signals "
                          "{\"_server_progress\":50.0,"
                          "\"_server_time\":\"02:02\","
                          "\"_server_time_left\":\"-02:02\"}\n\n")]
                    :connections-after-close #{}}
                   {:initial-events initial-events
                    :connections-after-close @(:connections stream)})))
          (finally
            (stop! stream))))
      (is (= :available :missing)))))

(deftest stopping-stream-closes-connected-clients
  (let [start! (progress-var 'start-progress-stream!)
        register! (progress-var 'register!)
        stop! (progress-var 'stop-progress-stream!)]
    (if (every? ifn? [start! register! stop!])
      (let [stream (start!)
            client (d*test/->sse-recorder)]
        (register! stream client)
        (stop! stream)
        (is (= {:connections #{} :client-open? false}
               {:connections @(:connections stream)
                :client-open? @(:!open? client)})))
      (is (= :available :missing)))))

(deftest registers-progress-stream-route
  (let [stream-handler (progress-var 'stream-handler)
        registered-handler
        (get-in @router/routes_ [:get "/api/player/progress-stream"])]
    (is (= {:handler? true :registered? true}
           {:handler? (ifn? stream-handler)
            :registered? (and stream-handler
                              (identical? stream-handler
                                          registered-handler))}))))
