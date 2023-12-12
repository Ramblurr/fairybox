(ns fairy.box.hardware.buttons
  (:require
   [jp.nijohando.event :as ev]
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
(defonce ^:private button-states (atom {}))

(defn set-interval [callback ms]
  (future
    (SleepUtil/sleepMillis ms)
    (callback)))

(defn button-event
  "Constructs a valid event map for a button event"
  [button-id action]
  {:path "/hardware/input/buttons"
   :value {:button-id button-id
           :action action}})

;; These are low level handlers that try to smooth out the the electrical noise from the raw events
;; they must return the button-states map and an optional external event that will be published on the bus

(defn long-press-handler [button-states button-id at orig-nanotime]
  (let [{:keys [state at] :as wtf} (get button-states button-id)]
    (if (and (= :pressed state) (= at orig-nanotime))
      [(assoc button-states button-id {:state :held :at at})
       (button-event button-id :hold)]
      [button-states nil])))

(defn press-handler [button-event-chan button-states button-id nanotime]
  (let [{:keys [state]} (get button-states button-id)]
    (if (= :released state)
      (do
        ;; (tap> {:msg "doing press" :at nanotime :old-state state})
        (set-interval (fn []
                        (async/put! button-event-chan {:event :check-hold :button-id button-id :at nanotime :orig-at nanotime})) hold-threshold)
        [(assoc button-states button-id {:state :pressed :at nanotime}) nil])
      [button-states nil])))

(defn release-handler [button-states button-id nanotime]
  (let [{:keys [state at]} (get button-states button-id)
        delta (- nanotime at)]
    (if (#{:held :pressed} state)
      [(assoc button-states button-id {:state :released :at nanotime})
       (button-event button-id (condp = state :held :hold-release :pressed :single-press))]
      [button-states nil])))

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
  "Initializes the go loop that handles the button events from the event channel.
    * emitter is the external channel to which we will emit actual button events to the rest of the application
    * button-event-chan is the channel from which we will receive raw, but debounced, button events - this is an internal channel
    * exit-ch is a channel that when a message is receieved upon will cause the listener to exit"
  [emitter button-event-chan exit-chan]
  (async/go-loop []
    (async/alt!
      exit-chan ([_]
                 nil)
      button-event-chan ([{:keys [event button-id at orig-at]}]
                         ;; (prn "got" event button-id at)
                         (let [states @button-states
                               [after external-event] (case event
                                                        :press (press-handler button-event-chan states button-id at)
                                                        :release (release-handler states button-id at)
                                                        :check-hold (long-press-handler states button-id at orig-at))]
                           (assert (some? after) (format  "NIL STATE  e=%s " event))
                           (reset! button-states after)
                           (when external-event
                             (async/>! emitter external-event))
                           (recur))))))

(defn raw-press-handler [raw-button-event-chan button-id nanotime]
  ;; (prn "raw press")
  ;;
  (async/put! raw-button-event-chan {:event :press :button-id button-id :at nanotime}))

(defn raw-release-handler [raw-button-event-chan button-id nanotime]
  ;; (prn "raw release")
  (async/put! raw-button-event-chan {:event :release :button-id button-id :at nanotime}))

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
  (let [exit-chan (async/chan)
        button-event-chan (async/chan (async/sliding-buffer 100))
        debounced-event-chan (debounce button-event-chan (/ debounce-delay 1000000.0))
        emitter (async/chan)]
    (ev/emitize bus emitter)
    (reset! button-states {})
    (init-button-event-handler! emitter debounced-event-chan exit-chan)
    {:event-handler-exit-chan exit-chan
     :button-event-chan button-event-chan
     :debounced-event-chan debounced-event-chan
     :emitter emitter
     :buttons (doall
               (map (partial init-button! button-event-chan) buttons))}))

(defn release-buttons! [{:keys [buttons emitter button-event-chan event-handler-exit-chan debounced-event-chan]}]
  (async/put! event-handler-exit-chan true)
  (async/close! button-event-chan)
  (async/close! emitter)
  (async/close! debounced-event-chan)
  (async/close! event-handler-exit-chan)
  (doseq [{:keys [^Button button action]} buttons]
    (when button
      (release-raw-button-listener! button)
      (.close button))))
