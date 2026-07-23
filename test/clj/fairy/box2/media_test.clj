(ns fairy.box2.media-test
  (:require
   [clojure.core.async :as async]
   [clojure.test :refer [deftest is]]
   [fairy.box2.media :as media]
   [taoensso.trove :as trove]))

(defn- effect [item-path presence-epoch request-id]
  {:effect/type :media.fx/prepare
   :effect/data {:item-path      item-path
                 :presence-epoch presence-epoch
                 :request-id     request-id}})

(defn- take-with-timeout [channel timeout-ms]
  (let [timeout      (async/timeout timeout-ms)
        [value port] (async/alts!! [channel timeout] :priority true)]
    (if (= port timeout) ::timeout value)))

(defn- recording-log-fn [logs_]
  (fn [_config _ns level id lazy-data]
    (swap! logs_ conj [level id (force lazy-data)])))

(deftest preparation-failure-emits-correlated-event-and-worker-continues-test
  (let [events (async/chan 2)
        logs_  (atom [])
        calls_ (atom 0)]
    (binding [trove/*log-fn* (recording-log-fn logs_)]
      (with-redefs [media/prepare!
                    (fn [_player _media-dir _item-path]
                      (if (= 1 (swap! calls_ inc))
                        (throw (ex-info "first failed" {}))
                        ["/ok.mp3"]))]
        (let [adapter   (media/start! {:media-dir "/media"
                                       :player    :fake
                                       :submit!   #(async/offer! events %)})
              request-a #uuid "00000000-0000-0000-0000-000000000001"
              request-b #uuid "00000000-0000-0000-0000-000000000002"]
          (try
            (is (:accepted? (media/offer! adapter
                                          (effect "first" 1 request-a))))
            (is (:accepted? (media/offer! adapter
                                          (effect "second" 2 request-b))))
            (let [failed   (take-with-timeout events 1000)
                  prepared (take-with-timeout events 1000)]
              (is (= [:media.ev/preparation-failed :media.ev/prepared]
                     [(:name failed) (:name prepared)]))
              (is (= {:presence-epoch 1 :request-id request-a}
                     (select-keys (:data failed)
                                  [:presence-epoch :request-id])))
              (is (= {:paths          ["/ok.mp3"]
                      :presence-epoch 2
                      :request-id     request-b}
                     (:data prepared))))
            (is (= [:fairy.box2.media/preparation-failed]
                   (->> @logs_
                        (filter #(= :error (first %)))
                        (mapv second))))
            (is (nil? (media/fatal adapter)))
            (finally
              (media/stop! adapter))))))))

(deftest correlated-cancellation-suppresses-running-completion-test
  (let [entered    (java.util.concurrent.CountDownLatch. 1)
        release    (java.util.concurrent.CountDownLatch. 1)
        events     (async/chan 1)
        request-id (random-uuid)]
    (with-redefs [media/prepare!
                  (fn [_player _media-dir _item-path]
                    (.countDown entered)
                    (.await release)
                    ["/cancelled.mp3"])]
      (let [adapter (media/start! {:media-dir "/media"
                                   :player    :fake
                                   :submit!   #(async/offer! events %)})]
        (try
          (is (:accepted? (media/offer! adapter
                                        (effect "blocked" 1 request-id))))
          (is (.await entered 1 java.util.concurrent.TimeUnit/SECONDS))
          (is (:accepted?
               (media/offer! adapter
                             {:effect/type :media.fx/cancel-preparation
                              :effect/data {:presence-epoch 1
                                            :request-id     request-id}})))
          (.countDown release)
          (is (= ::timeout (take-with-timeout events 100)))
          (finally
            (.countDown release)
            (media/stop! adapter)))))))

(deftest bounded-stop-reports-uninterruptible-work-and-prevents-late-completion-test
  (let [entered (java.util.concurrent.CountDownLatch. 1)
        release (java.util.concurrent.CountDownLatch. 1)
        events  (async/chan 1)]
    (with-redefs-fn
      {#'media/prepare!
       (fn [_player _media-dir _item-path]
         (.countDown entered)
         (.await release)
         ["/late.mp3"])
       #'media/stop-timeout-ms 50}
      (fn []
        (let [adapter (media/start! {:media-dir "/media"
                                     :player    :fake
                                     :submit!   #(async/offer! events %)})]
          (try
            (is (:accepted? (media/offer! adapter
                                          (effect "blocked" 1 (random-uuid)))))
            (is (.await entered 1 java.util.concurrent.TimeUnit/SECONDS))
            (is (thrown-with-msg?
                 clojure.lang.ExceptionInfo
                 #"library call may still be running"
                 (media/stop! adapter)))
            (.countDown release)
            (is (= :stopped (take-with-timeout (:done adapter) 1000)))
            (is (= ::timeout (take-with-timeout events 100)))
            (is (= :stopped (media/stop! adapter)))
            (finally
              (.countDown release))))))))
