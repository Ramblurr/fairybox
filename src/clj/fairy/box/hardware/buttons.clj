(ns fairy.box.hardware.buttons
  (:require
   [clojure.core.async :as async])
  (:import
   [java.util.function LongConsumer]
   [com.diozero.util SleepUtil]
   [com.diozero.api GpioPullUpDown]
   [com.diozero.util Diozero]
   [com.diozero.devices Button]))

(def debounce-delay
  "Time in nanoseconds to wait before a press->release is considered a single press."
  (* 50 1000000))     ;; 50 ms in nanoseconds

(def hold-threshold
  "Time in milliseconds to wait before a button press is considered a hold."
  500)

;; This is here just for debugging
(defonce button-states (atom {}))

(defn set-interval [callback ms]
  (future
    (SleepUtil/sleepMillis ms)
    (callback)))

(defn ->publish-button-event
  "Return a function that publishes button events to the given publisher."
  [publisher]
  (fn  [button-id action]
    (async/put! publisher
                {:topic :buttons
                 :value {:button-id button-id
                         :action action}})))

;; These are low level handlers that try to smooth out the the electrical noise from the raw events

(defn long-press-handler [button-states publish-button-event button-id at orig-nanotime]
  (let [{:keys [state at] :as wtf} (get button-states button-id)]
    (if (and (= :pressed state) (= at orig-nanotime))
      (do
        ;; (tap> {:msg "doing hold" :orig orig-nanotime :old-state state})
        (publish-button-event button-id :hold)
        ;; (prn "HOLD" button-id (/  (- (System/nanoTime) orig-nanotime) 1000000.0) at orig-nanotime wtf)
        (assoc button-states button-id {:state :held :at at}))
      button-states)))

(defn press-handler [button-event-chan button-states button-id nanotime]
  (let [{:keys [state]} (get button-states button-id)]
    (if (= :released state)
      (do
        ;; (tap> {:msg "doing press" :at nanotime :old-state state})
        (set-interval (fn []
                        (async/put! button-event-chan {:event :check-hold :button-id button-id :at nanotime :orig-at nanotime})) hold-threshold)
        (assoc button-states button-id {:state :pressed :at nanotime}))
      button-states)))

(defn release-handler [button-states publish-button-event button-id nanotime]
  (let [{:keys [state at]} (get button-states button-id)
        delta (- nanotime at)]
    (if (#{:held :pressed} state)
      (do
        ;; (prn "RELEASE after debounce")
        ;; (tap> {:msg "doing release" :at nanotime :old-state state})
        (condp = state
          :held (do
                  ;; (prn "HOLD_release" button-id (/ delta 1000000.0))
                  (publish-button-event button-id :hold-release))
          :pressed (do
                     ;; (prn "SINGLE PRESS" button-id (/ delta 1000000.0))
                     (publish-button-event button-id :single-press)))
        (assoc button-states button-id {:state :released :at nanotime}))
      button-states)))

(defn debounce [in ms]
  (let [out (async/chan)]
    (async/go-loop [last-val nil]
      (let [val   (if (nil? last-val) (async/<! in) last-val)
            timer (async/timeout ms)
            [new-val ch] (async/alts! [in timer])]
        (condp = ch
          timer (do (when-not
                     (async/>! out val)
                      (async/close! in))
                    (recur nil))
          in (when new-val (recur new-val)))))
    out))

(defn init-button-event-handler!
  "Initializes the go loop that handles the button events from the event channel."
  [{:keys [publisher] :as bus} button-event-chan exit-chan]
  (async/go-loop []
    (async/alt!
      exit-chan ([_]
                 nil)
      button-event-chan ([{:keys [event button-id at orig-at]}]
                         ;; (prn "got" event button-id at)
                         (let [states @button-states
                               after (case event
                                       :press (press-handler button-event-chan states button-id at)
                                       :release (release-handler states (->publish-button-event publisher) button-id at)
                                       :check-hold (long-press-handler states (->publish-button-event publisher) button-id at orig-at))]
                           (assert (some? after) (format  "NIL STATE  e=%s " event))
                           (reset! button-states after))
                         (recur)))))

(defn raw-press-handler [button-event-chan button-id nanotime]
  ;; (prn "raw press")
  (async/put! button-event-chan {:event :press :button-id button-id :at nanotime}))

(defn raw-release-handler [button-event-chan button-id nanotime]
  ;; (prn "raw release")
  (async/put! button-event-chan {:event :release :button-id button-id :at nanotime}))

(defn raw-button-listener!
  "Connect the raw button press listeners"
  [button-event-chan ^Button button action]
  (.whenPressed button (reify LongConsumer
                         (accept [this value]
                           (raw-press-handler button-event-chan action value))))
  (.whenReleased button (reify LongConsumer
                          (accept [this value]
                            (raw-release-handler button-event-chan action value)))))

(defn release-raw-button-listener!
  "Remove the raw button press listeners"
  [^Button button]
  (.whenPressed button nil)
  (.whenReleased button nil))

(defn init-button! [button-event-chan {:keys [gpio action pull-up-down]}]
  (let [button (Button. gpio (case (or pull-up-down :up)
                               :up GpioPullUpDown/PULL_UP
                               :down GpioPullUpDown/PULL_DOWN))]
    (swap! button-states assoc action {:state :released :at 0})
    (Diozero/registerForShutdown (into-array Button [button]))
    {:button button :gpio gpio :action action
     :listener (raw-button-listener! button-event-chan button action)}))

(defn init-buttons! [{:keys [buttons bus]}]
  (let [;; publish-button-event (->publish-button-event (:publisher bus))
        exit-chan (async/chan)
        button-event-chan (async/chan (async/sliding-buffer 100))
        debounced-event-chan (debounce button-event-chan (/ debounce-delay 1000000.0))]
    (reset! button-states {})
    (init-button-event-handler! bus debounced-event-chan exit-chan)
    {:event-handler-exit-chan exit-chan
     :button-event-chan button-event-chan
     :debounced-event-chan debounced-event-chan
     :buttons (doall
               (map (partial init-button! button-event-chan) buttons))}))

(defn release-buttons! [{:keys [buttons button-event-chan event-handler-exit-chan debounced-event-chan]}]
  (async/put! event-handler-exit-chan true)
  (async/close! event-handler-exit-chan)
  (async/close! button-event-chan)
  (async/close! debounced-event-chan)
  (doseq [{:keys [^Button button action]} buttons]
    (when button
      (release-raw-button-listener! button)
      (.close button))))
