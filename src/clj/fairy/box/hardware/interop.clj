(ns fairy.box.hardware.interop
  (:import
   [com.diozero.api GpioPullUpDown]
   [com.diozero.util Diozero]
   [com.diozero.devices LED  MFRC522]
   [com.diozero.util SleepUtil Hex]))

(defn get-card-uid [^com.diozero.devices.MFRC522 rfid]
  (when (.isNewCardPresent rfid)
    (when-let [uid (.readCardSerial rfid)]
      (.haltA rfid)
      (.stopCrypto1 rfid)
      (Hex/encodeHexString (.getUidBytes uid)))))

(def SUPPORTED-RFID-TYPES #{:mfrc522})

(defn init-rfid [{:keys [rfid-type] :as opts}]
  (when-not (SUPPORTED-RFID-TYPES rfid-type)
    (throw (Exception. (format "Unsupported RFID type: %s" rfid-type))))
  #_(let [config (rfid-type opts)]
      (case rfid-type
        :mfrc522 (let [rfid (MFRC522. (:controller config 0)
                                      (:chip-select config 0)
                                      (:reset-gpio config 25))]
                   (Diozero/registerForShutdown (into-array MFRC522 [rfid]))
                   {:rfid rfid}))))
(defn release-rfid! [{:keys [rfid]}]
  (prn "releasing rfid" rfid)
  #_(when rfid
      (try
      ;; (.haltA rfid)
      ;; (.stopCrypto1 rfid)

        (prn "releasing rfid-post-stop" rfid)
        (finally
          (.close rfid)
          (prn "releasing rfid-post-close" rfid)))))

(defn init-led [{:keys [gpio name]}]
  (let [led (LED. gpio)]
    (.on led)
    {:led led :gpio gpio :name name}))

(defn init-leds [{:keys [leds]}]
  {:leds
   (doall
    (map init-led leds))})

(defn release-leds! [{:keys [leds]}]
  (doseq [{:keys [^LED led]} leds]
    (when led
      (.close led))))
