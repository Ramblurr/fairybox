(ns fairy.box.hardware.rfid
  (:require
   [jp.nijohando.event :as ev]
   [clojure.tools.logging :as log]
   [clojure.core.async :as async])
  (:import
   [com.diozero.util Diozero]
   [com.diozero.devices MFRC522]
   [com.diozero.util SleepUtil Hex]))

(def SUPPORTED-RFID-TYPES #{:mfrc522})

(def absent-poll-delay 500)                    ; ms between checks for new rfid cards
(def present-poll-delay 200)                   ; ms between checks for rfid card removal

;; strictly speaking this doesn't need to be an atom, but its useful for debugging to be able to
;; inspect the state
(defonce rfid-state (atom {}))

;; this needs to be an atom cause we use it to communicate across threads
(defonce poller-active? (atom false))

(defn mfrc522-test
  "Test function to check if the RFID device is working.
  Returns :ok if the device is working, otherwise a map with :msg key and optionally other data"
  [^com.diozero.devices.MFRC522 rfid]
  (let [version (.getVersion rfid)]
    ;; When 0x00 or 0xFF is returned, communication probably failed
    (if (or (= version 0x00) (= version 0xff))
      {:msg "Communication with MFRC522 failed" :version version}
      :ok)))

(defn mfrc522-get-card-uid [^com.diozero.devices.MFRC522 rfid]
  (when (.isNewCardPresent rfid)
    (when-let [uid (.readCardSerial rfid)]
      (.haltA rfid)
      (.stopCrypto1 rfid)
      (Hex/encodeHexString (.getUidBytes uid)))))

(defn ->rfid [{:keys [rfid-type] :as opts}]
  (let [config (rfid-type opts)]
    (case rfid-type
      :mfrc522 (let [rfid (MFRC522. (:controller config 0)
                                    (:chip-select config 0)
                                    (:reset-gpio config 25))]
                 (Diozero/registerForShutdown (into-array MFRC522 [rfid]))
                 {:device rfid
                  :rfid-type rfid-type
                  :config config
                  :test-fn mfrc522-test
                  :get-card-uid-fn mfrc522-get-card-uid}))))

(defn rfid-event
  ([uid action at error]
   {:path "/hardware/input/rfid"
    :value {:uid uid :action action :at at :error error}})
  ([uid action at]
   {:path "/hardware/input/rfid"
    :value {:uid uid :action action :at at}}))

(defn rfid-uid-detected [status state now uid]
  (condp = status
    :present [state nil]
    :absent [(-> state
                 (assoc :uid uid)
                 (assoc :status :present)
                 (assoc :poll-delay present-poll-delay)
                 (assoc :at now))
             (rfid-event uid :placed now)]))

(defn rfid-uid-not-detected [status state now old-uid]
  (condp = status
    :present [(-> state
                  (assoc :uid nil)
                  (assoc :status :absent)
                  (assoc :poll-delay absent-poll-delay)
                  (assoc :at now))
              (rfid-event old-uid :removed now)]
    :absent [state nil]))

(defn rfid-device-error [state now test-result]
  (let [errored-at (:error-at state 0)
        raise-new-error? (> (- now errored-at) 10000000000)]
    [(-> state
         (assoc :status :error)
         (assoc :error test-result)
         (assoc :error-at (if raise-new-error? now errored-at))
         (assoc :at now))
     (when raise-new-error?
       (rfid-event nil :error now test-result))]))

(defn poller-loop [{:keys [poll-delay uid status at] :as state} device get-card-uid-fn test-fn]
  (SleepUtil/sleepMillis poll-delay)
  (let [now (System/nanoTime)
        test-result (test-fn device)]
    (if (= :ok test-result)
      (if-let [uid (get-card-uid-fn device)]
        (rfid-uid-detected status state now uid)
        (rfid-uid-not-detected status state now uid))
      (rfid-device-error state now test-result))))

(defn start-poller! [{:keys [bus] :as opts}]
  (reset! poller-active? true)
  (reset! rfid-state {:at 0 :status :absent :uid nil :poll-delay absent-poll-delay})
  (future
    (log/debug "rfid :: poller started")
    (let [{:keys [device test-fn get-card-uid-fn]} (->rfid opts)]
      (assert device)
      (assert get-card-uid-fn)
      (let [emitter (async/chan)]
        (try
          (ev/emitize bus emitter)
          (with-open [device device]
            (loop []
              (let [[new-state external-event] (poller-loop @rfid-state device get-card-uid-fn test-fn)]
                (when @poller-active?
                  (reset! rfid-state new-state)
                  (when external-event
                    (async/put! emitter external-event))
                  (recur)))))
          (catch java.util.concurrent.CancellationException _e
            (log/debug "rfid :: poller cancelled"))
          (catch Exception e
            (log/error e "rfid poller error"))
          (finally
            (async/close! emitter)
            (log/debug "rfid :: poller stopping")))))))

(defn init-rfid! [{:keys [rfid-type bus] :as opts}]
  (when-not (SUPPORTED-RFID-TYPES rfid-type)
    (throw (Exception. (format "Unsupported RFID type: %s" rfid-type))))
  {:poller-future (start-poller! opts)})

(defn release-rfid! [{:keys [poller-future]}]
  (reset! poller-active? false)
  (SleepUtil/sleepMillis absent-poll-delay)
  (try
    (future-cancel poller-future)
    (deref poller-future 10000 nil)
    (catch Exception e
      (log/error e "Encountered exception when stopping rfid poller"))))
