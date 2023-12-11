(ns fairy.box.hardware.rfid
  (:require   [clojure.tools.logging :as log]
              [clojure.core.async :as async])

  (:import
   [com.diozero.util Diozero]
   [com.diozero.devices MFRC522]
   [com.diozero.util SleepUtil Hex]))

(def SUPPORTED-RFID-TYPES #{:mfrc522})
(def poll-delay 500)                    ; ms between checks for new rfid cards

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
  (SleepUtil/sleepMillis poll-delay)

  ;; (prn "rfid :: poller wakeup")
  (when-let [uid (get-card-uid-fn device)]
    (prn "CARD " uid)
    (async/put! publisher
                {:topic :rfid
                 :value {:uid uid :action :added}}))
  ;; (prn "rfid :: poller sleeping")
  @poller-active?)

(defn start-poller! [opts publisher]
  (reset! poller-active? true)
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
  (SleepUtil/sleepMillis poll-delay)
  (try
    (future-cancel poller-future)
    (deref poller-future 10000 nil)
    (catch Exception e
      (log/error e "Encountered exception when stopping rfid poller"))))
