(ns fairy.box.hardware.buttons
  (:require
   [clojure.core.async :as async])
  (:import
   [java.util.function LongConsumer]
   [com.diozero.api GpioPullUpDown]
   [com.diozero.util Diozero]
   [com.diozero.devices  Button]
   [com.diozero.util SleepUtil]))

(def double-press-timeout (* 500 1000000))
(def debounce-delay (* 50 1000000))     ;; 50 ms in nanoseconds

(def hold-threshold 500)                    ;; miliseconds

(defonce button-states (atom {}))

(defn set-interval [callback ms]
  (future (while true
            (Thread/sleep ms)
            (callback))))

(defn ->publish-button-event [publisher]
  (fn  [button-id action]
    (async/put! publisher
                {:topic :button
                 :value {:button-id button-id
                         :action action}})))

;; These are low level handlers that try to smooth out the the electrical noise

(defn long-press-handler [publish-button-event button-id orig-nanotime]
  (let [{:keys [state at]} (get @button-states button-id)]
    (when (and (= :pressed state) (= at orig-nanotime))
      (publish-button-event button-id :hold)
      (prn "HOLD" button-id (/  (- (System/nanoTime) orig-nanotime) 1000000.0))
      (swap! button-states assoc button-id {:state :held :at at}))))

(defn press-handler [publish-button-event button-id nanotime]
  (let [{:keys [state at]} (get @button-states button-id)]
    (when (= :released state)
      (swap! button-states assoc button-id {:state :pressed :at nanotime})
      (set-interval (fn [] (long-press-handler publish-button-event button-id nanotime)) hold-threshold))))

(defn release-handler [publish-button-event button-id nanotime]
  (let [{:keys [state at]} (get @button-states button-id)]
    (when (#{:held :pressed} state)
      (let [delta (- nanotime at)]
        (when (>= delta debounce-delay)
          (if (= :held state)
            (do
              (prn "HOLD_release" button-id (/ delta 1000000.0))
              (publish-button-event button-id :hold-release))
            (do
              (prn "SINGLE PRESS" button-id (/ delta 1000000.0))
              (publish-button-event button-id :single-press)))
          (swap! button-states assoc button-id {:state :released :at nanotime}))))))

(defn button-listener! [publish-button-event ^Button button action]
  (prn "start button-listener" action)
  (.whenPressed button (reify LongConsumer
                         (accept [this value]
                           (press-handler publish-button-event action value))))
  (.whenReleased button (reify LongConsumer
                          (accept [this value]
                            (release-handler publish-button-event action value)))))

(defn release-button-listener! [^Button button action]
  (prn "stop button-listener" action)
  (.whenPressed button nil)
  (.whenReleased button nil))

(defn init-button [publish-button-event {:keys [gpio action pull-up-down]}]
  (let [button (Button. gpio (case (or pull-up-down :up)
                               :up GpioPullUpDown/PULL_UP
                               :down GpioPullUpDown/PULL_DOWN))]
    (swap! button-states assoc action {:state :released :at 0})
    (Diozero/registerForShutdown (into-array Button [button]))
    {:button button :gpio gpio :action action
     :listener (button-listener! publish-button-event button action)}))

(defn init-buttons [{:keys [buttons bus]}]
  (let [publish-button-event (->publish-button-event (:publisher bus))]
    (reset! button-states {})
    {:buttons (doall
               (map (partial init-button publish-button-event) buttons))}))

(defn release-buttons! [{:keys [buttons]}]
  (doseq [{:keys [^Button button action]} buttons]
    (when button
      (release-button-listener! button action)
      (.close button))))
