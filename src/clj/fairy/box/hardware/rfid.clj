(ns fairy.box.hardware.rfid
  (:require
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
                  :get-card-uid-fn mfrc522-get-card-uid}))))

(defn poller-loop [publisher device get-card-uid-fn]
  (let [{:keys [poll-delay uid status at] :as state} @rfid-state]
    (SleepUtil/sleepMillis poll-delay)
    (let [now (System/nanoTime)]
      ;; (prn "rfid :: poller wakeup")
      (reset! rfid-state
              (if-let [uid (get-card-uid-fn device)]
                (condp = status
                  :present state
                  :absent (do
                            ;; (prn "CARD PLACED" uid)
                            (async/put! publisher
                                        {:topic :rfid
                                         :value {:uid uid :action :placed :at now}})
                            (-> state
                                (assoc :uid uid)
                                (assoc :status :present)
                                (assoc :poll-delay present-poll-delay)
                                (assoc :at now))))
                (condp = status
                  :present (do
                             ;; (prn "CARD REMOVED" uid)
                             (async/put! publisher
                                         {:topic :rfid
                                          :value {:uid uid :action :removed :at now}})
                             (-> state
                                 (assoc :uid nil)
                                 (assoc :status :absent)
                                 (assoc :poll-delay absent-poll-delay)
                                 (assoc :at now)))
                  :absent state)))))
  ;; (prn "rfid :: poller sleeping")
  @poller-active?)

(defn start-poller! [opts publisher]
  (reset! poller-active? true)
  (reset! rfid-state {:at 0 :status :absent :uid nil :poll-delay absent-poll-delay})
  (future
    (prn "rfid :: poller started")
    (let [{:keys [device get-card-uid-fn]} (->rfid opts)]
      (assert device)
      (assert get-card-uid-fn)
      (with-open [device device]
        (loop []
          (if (poller-loop publisher device get-card-uid-fn)
            (recur)
            (do
              (prn "rfid :: poller stopping")
              nil)))))))

(defn init-rfid! [{:keys [rfid-type bus] :as opts}]
  (when-not (SUPPORTED-RFID-TYPES rfid-type)
    (throw (Exception. (format "Unsupported RFID type: %s" rfid-type))))
  {:poller-future (start-poller! opts (:publisher bus))})

(defn release-rfid! [{:keys [poller-future]}]
  (prn "releasing rfid")
  (reset! poller-active? false)
  (SleepUtil/sleepMillis absent-poll-delay)
  (try
    (future-cancel poller-future)
    (deref poller-future 10000 nil)
    (catch Exception e
      (log/error e "Encountered exception when stopping rfid poller"))))
