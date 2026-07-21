;; Copyright © 2025 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2
(ns fairy.box.hardware.buttons
  (:require
   [clojure.core.async :as async]
   [donut.system :as ds]
   [fairy.box.util :refer [debounce]]
   [jp.nijohando.event :as ev])
  (:import
   [com.diozero.api GpioPullUpDown]
   [com.diozero.devices Button]
   [com.diozero.util Diozero SleepUtil]
   [java.util.function LongConsumer]))

(def debounce-delay
  "Time in nanoseconds to wait before a press->release is considered a single press."
  (* 50 1000000))     ;; 50 ms in nanoseconds

(def hold-threshold
  "Time in milliseconds to wait before a button press is considered a hold."
  1000)

(def minimum-simulated-press-ms
  60)

(defn set-interval [callback ms]
  (future
    (SleepUtil/sleepMillis ms)
    (callback)))

(defn button-event
  "Constructs a valid event map for a button event"
  [button-id action]
  {:path  "/hardware/input/buttons"
   :value {:button-id button-id
           :action    action}})

;; These are low level handlers that try to smooth out the the electrical noise from the raw events
;; they must return the button-states map and an optional external event that will be published on the bus

(defn long-press-handler [button-states button-id _at orig-nanotime]
  (let [{:keys [state at]} (get button-states button-id)]
    (if (and (= :pressed state) (= at orig-nanotime))
      [(assoc button-states button-id {:state :held :at at})
       (button-event button-id :button/hold)]
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
  (let [{:keys [state]} (get button-states button-id)]
    (if (#{:held :pressed} state)
      [(assoc button-states button-id {:state :released :at nanotime})
       (button-event button-id (condp = state :held :button/hold-release :pressed :button/single-press))]
      [button-states nil])))

(defn init-button-event-handler!
  "Starts the loop that turns debounced raw events into bus events."
  [emitter button-event-chan exit-chan button-states_]
  (async/go-loop []
    (async/alt!
      exit-chan ([_]
                 nil)
      button-event-chan ([{:keys [event button-id at orig-at]}]
                         (let [states                 @button-states_
                               [after external-event] (case event
                                                        :press (press-handler button-event-chan states button-id at)
                                                        :release (release-handler states button-id at)
                                                        :check-hold (long-press-handler states button-id at orig-at))]
                           (assert (some? after)
                                   (format "NIL STATE  e=%s " event))
                           (reset! button-states_ after)
                           (when external-event
                             (async/>! emitter external-event))
                           (recur))))))

(defn raw-press-handler [raw-button-event-chan button-id nanotime]
  ;; (tap> [button-id :RAW-DOWN])
  (async/put! raw-button-event-chan {:event :press :button-id button-id :at nanotime}))

(defn raw-release-handler [raw-button-event-chan button-id nanotime]
  ;; (tap> [button-id :RAW-UP])
  (async/put! raw-button-event-chan {:event :release :button-id button-id :at nanotime}))

(defn raw-button-listener!
  "Connect the raw button press listeners"
  [button-event-chan ^Button button action]
  (.whenPressed button (reify LongConsumer
                         (accept [_this value]
                           (raw-press-handler button-event-chan action value))))
  (.whenReleased button (reify LongConsumer
                          (accept [_this value]
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
    (Diozero/registerForShutdown (into-array Button [button]))
    (raw-button-listener! button-event-chan button action)
    {:button button :gpio gpio :action action}))

(defn init-buttons!
  [{:keys [buttons bus]} hardware-enabled?]
  (let [exit-chan            (async/chan)
        button-event-chan    (async/chan (async/sliding-buffer 100))
        debounced-event-chan (debounce button-event-chan
                                       (/ debounce-delay 1000000.0))
        emitter              (async/chan)
        button-ids           (set (map :action buttons))
        button-states_       (atom (zipmap button-ids
                                           (repeat {:state :released :at 0})))
        press-times_         (atom {})]
    (ev/emitize bus emitter)
    (let [event-handler (init-button-event-handler!
                         emitter
                         debounced-event-chan
                         exit-chan
                         button-states_)]
      {:event-handler-exit-chan exit-chan
       :event-handler           event-handler
       :button-event-chan       button-event-chan
       :debounced-event-chan    debounced-event-chan
       :emitter                 emitter
       :button-ids              button-ids
       :button-states_          button-states_
       :press-times_            press-times_
       :buttons                 (if hardware-enabled?
                                  (doall
                                   (map (partial init-button!
                                                 button-event-chan)
                                        buttons))
                                  [])})))

(defn- valid-button? [{:keys [button-event-chan button-ids]} button-id]
  (and button-event-chan
       (contains? button-ids button-id)))

(defn- submit-button-event!
  [{:keys [button-event-chan] :as buttons} button-id event at]
  (when (valid-button? buttons button-id)
    (async/put! button-event-chan
                {:event     event
                 :button-id button-id
                 :at        at})))

(defn press! [{:keys [press-times_] :as buttons} button-id]
  (when (valid-button? buttons button-id)
    (let [at (System/nanoTime)]
      (swap! press-times_ assoc button-id at)
      (submit-button-event! buttons button-id :press at))))

(defn release! [{:keys [press-times_] :as buttons} button-id]
  (when (valid-button? buttons button-id)
    (let [at         (System/nanoTime)
          pressed-at (get @press-times_ button-id)
          elapsed-ms (when pressed-at
                       (/ (- at pressed-at) 1000000.0))
          delay-ms   (if elapsed-ms
                       (max 0
                            (long (Math/ceil
                                   (- minimum-simulated-press-ms
                                      elapsed-ms))))
                       0)]
      (swap! press-times_ dissoc button-id)
      (if (pos? delay-ms)
        (do
          (set-interval #(submit-button-event!
                          buttons
                          button-id
                          :release
                          (System/nanoTime))
                        delay-ms)
          true)
        (submit-button-event! buttons button-id :release at)))))

(defn release-buttons!
  [{:keys [buttons emitter button-event-chan event-handler-exit-chan
           debounced-event-chan event-handler button-states_ press-times_]}]
  (async/put! event-handler-exit-chan true)
  (async/close! button-event-chan)
  (async/close! emitter)
  (async/close! debounced-event-chan)
  (async/close! event-handler-exit-chan)
  (doseq [{:keys [^Button button] _action :action} buttons]
    (when button
      (release-raw-button-listener! button)
      (.close button)))
  (when event-handler
    (async/alts!! [event-handler (async/timeout 1000)]))
  (when button-states_
    (reset! button-states_ {}))
  (when press-times_
    (reset! press-times_ {}))
  nil)

(defn start-component! [{:keys [hardware-enablement] :as config}]
  (assoc (init-buttons! config (:buttons hardware-enablement))
         :enabled? (boolean (:buttons hardware-enablement))))

(defn stop-component! [instance]
  (when (:event-handler-exit-chan instance)
    (release-buttons! instance)))

(def ButtonsComponent
  {::ds/start  (fn [{config ::ds/config}]
                 (start-component! config))
   ::ds/stop   (fn [{instance ::ds/instance}]
                 (stop-component! instance))
   ::ds/config {:hardware-enablement (ds/ref [:config
                                              :fairy.box/components
                                              :fairy.box.hardware/enabled])
                :bus                 (ds/ref [:fairy.box/components
                                              :fairy.box.bus/bus])
                :buttons             (ds/ref [:config
                                              :fairy.box/components
                                              :fairy.box.hardware/buttons
                                              :buttons])}})