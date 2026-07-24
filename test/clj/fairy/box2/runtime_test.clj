(ns fairy.box2.runtime-test
  (:require
   [clojure.test :refer [deftest is]]
   [fairy.box2.runtime :as runtime]
   [taoensso.trove :as trove]))

(def ^:private settings
  {:audio         {:card-removal-behavior :pause
                   :card-return-behavior  :resume}
   :auto-shutdown {:enabled? false}
   :tts           {:announce-tracks? false}})

(def ^:private initialized-event
  {:name :system.ev/initialized
   :data {:settings          settings
          :settings-revision 0}})

(defn- present-event [presence-epoch]
  {:name :rfid.ev/presence-observed
   :data {:item-path      "story"
          :presence-epoch presence-epoch
          :request-id     (random-uuid)
          :status         :present
          :uid            "CARD-A"}})

(defn- absent-event [presence-epoch]
  {:name :rfid.ev/presence-observed
   :data {:presence-epoch presence-epoch
          :status         :absent}})

(defn- error-log-ids [logs]
  (->> logs
       (filter #(= :error (first %)))
       (mapv second)))

(defn- recording-log-fn [logs_]
  (fn [_config _ns level id lazy-data]
    (swap! logs_ conj [level id (force lazy-data)])))

(deftest required-ingress-overflow-fails-runtime-test
  (let [entered (java.util.concurrent.CountDownLatch. 1)
        release (java.util.concurrent.CountDownLatch. 1)
        logs_   (atom [])]
    (binding [trove/*log-fn* (recording-log-fn logs_)]
      (let [run (runtime/start!
                 (fn [_effect]
                   (.countDown entered)
                   (.await release)
                   {:accepted? true}))]
        (try
          (runtime/submit-and-await! run initialized-event)
          (let [placement (runtime/submit! run (present-event 1))]
            (is (.await entered 1 java.util.concurrent.TimeUnit/SECONDS))
            (let [accepted (mapv (fn [_]
                                   (:accepted?
                                    (runtime/submit!
                                     run
                                     {:name :rfid.ev/recovered})))
                                 (range 64))
                  overflow (runtime/submit! run {:name :rfid.ev/recovered})]
              (is (= 64 (count (filter true? accepted))))
              (is (= {:accepted? false :reason :runtime-failed}
                     (select-keys overflow [:accepted? :reason])))
              (is (= :required-ingress-full
                     (:reason (runtime/fatal run)))))
            (.countDown release)
            (runtime/await! run placement)
            (runtime/stop! run)
            (is (= [:fairy.box2.runtime/runtime-failed]
                   (error-log-ids @logs_))))
          (finally
            (.countDown release)
            (runtime/stop! run)))))))

(deftest progress-ingress-retains-only-newest-value-test
  (let [entered (java.util.concurrent.CountDownLatch. 1)
        release (java.util.concurrent.CountDownLatch. 1)
        run     (runtime/start!
                 (fn [_effect]
                   (.countDown entered)
                   (.await release)
                   {:accepted? true}))]
    (try
      (runtime/submit-and-await! run initialized-event)
      (let [placement (runtime/submit! run (present-event 1))]
        (is (.await entered 1 java.util.concurrent.TimeUnit/SECONDS))
        (is (:accepted?
             (runtime/submit-latest!
              run
              {:name :player.ev/time-changed
               :data {:time-ms 10}})))
        (is (:accepted?
             (runtime/submit-latest!
              run
              {:name :player.ev/time-changed
               :data {:time-ms 20}})))
        (.countDown release)
        (runtime/await! run placement)
        (loop [attempt 0]
          (when (and (empty? (filter #(= :player.ev/time-changed
                                         (get-in % [:event :name]))
                                     (runtime/history run)))
                     (< attempt 200))
            (Thread/sleep 5)
            (recur (inc attempt))))
        (is (= [20]
               (->> (runtime/history run)
                    (filter #(= :player.ev/time-changed
                                (get-in % [:event :name])))
                    (mapv #(get-in % [:event :data :time-ms]))))))
      (finally
        (.countDown release)
        (runtime/stop! run)))))

(deftest diagnostics-are-bounded-and-event-errors-recover-test
  (let [logs_ (atom [])]
    (binding [trove/*log-fn* (recording-log-fn logs_)]
      (let [run (runtime/start! (constantly {:accepted? true}))]
        (try
          (dotimes [index 70]
            (try
              (runtime/submit-and-await!
               run
               {:name :test.ev/invalid
                :data {:index index}})
              (catch Exception _)))
          (runtime/submit-and-await! run initialized-event)
          (doseq [presence-epoch (range 1 66)]
            (runtime/submit-and-await! run (present-event presence-epoch))
            (runtime/submit-and-await! run (absent-event presence-epoch)))
          (dotimes [_ 126]
            (runtime/submit-and-await! run {:name :rfid.ev/recovered}))
          (is (= {:effects 128
                  :errors  64
                  :history 256}
                 {:effects (count (runtime/effects run))
                  :errors  (count (runtime/errors run))
                  :history (count (runtime/history run))}))
          (is (= 70
                 (count (filter #{:fairy.box2.runtime/event-processing-failed}
                                (error-log-ids @logs_)))))
          (is (contains? (set (:configuration (runtime/snapshot run)))
                         :system.st/active))
          (is (nil? (runtime/fatal run)))
          (finally
            (runtime/stop! run)))))))
