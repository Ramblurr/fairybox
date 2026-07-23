(ns fairy.box2.rfid.mfrc522
  "Diozero-backed MFRC522 reader adapter for Box2."
  (:require
   [taoensso.trove :as trove]
   [clojure.core.async :as async]
   [clojure.tools.logging :as log]
   [fairy.box2.rfid :as rfid])
  (:import
   [com.diozero.devices MFRC522]
   [com.diozero.util Diozero Hex]))

(def absent-poll-delay-ms 500)
(def present-poll-delay-ms 200)

(defn- test-device [^MFRC522 device]
  (let [version (.getVersion device)]
    (if (or (= version 0x00) (= version 0xff))
      {:category :rfid/communication
       :message  (format "MFRC522 communication failed (version 0x%02x)"
                         version)
       :version  version}
      :ok)))

(defn- card-uid [^MFRC522 device]
  (when (.isNewCardPresent device)
    (when-let [uid (.readCardSerial device)]
      (.haltA device)
      (.stopCrypto1 device)
      (Hex/encodeHexString (.getUidBytes uid)))))

(defn- open-device
  ^MFRC522 [{:keys [chip-select controller reset-gpio]
             :or   {chip-select 0
                    controller  0
                    reset-gpio  25}}]
  (let [device (MFRC522. (int controller)
                         (int chip-select)
                         (int reset-gpio))]
    (Diozero/registerForShutdown (into-array MFRC522 [device]))
    device))

(defn- wait-to-poll? [stop poll-delay]
  (let [timeout       (async/timeout poll-delay)
        [_value port] (async/alts!! [stop timeout] :priority true)]
    (= port timeout)))

(defn- poll! [control_ {:keys [done stop] :as control} config report!]
  (trove/log! {:level :debug :id ::started :msg "Box2 MFRC522 poller started"})
  (try
    (with-open [^MFRC522 device (open-device config)]
      (loop [poll-delay absent-poll-delay-ms]
        (when (wait-to-poll? stop poll-delay)
          (let [test-result (test-device device)]
            (if (= :ok test-result)
              (let [uid (card-uid device)]
                (report! (if uid
                           {:status :present :uid uid}
                           {:status :absent}))
                (recur (if uid
                         present-poll-delay-ms
                         absent-poll-delay-ms)))
              (do
                (report! {:error test-result :status :faulted})
                (recur absent-poll-delay-ms)))))))
    (catch Throwable error
      (trove/log! {:level :error :id ::failed :msg "Box2 MFRC522 poller failed"})
      (throw error))
    (finally
      (async/offer! done :stopped)
      (compare-and-set! control_ control nil)
      (trove/log! {:level :debug :id ::stopped :msg "Box2 MFRC522 poller stopped"}))))

(defn- await-stopped! [done]
  (let [timeout       (async/timeout 5000)
        [_value port] (async/alts!! [done timeout] :priority true)]
    (when (= port timeout)
      (throw (ex-info "Timed out stopping MFRC522 reader"
                      {:timeout-ms 5000}))))
  :stopped)

(defrecord Mfrc522Reader [config control_]
  rfid/RfidReader
  (start-reader! [this report!]
    (let [control {:done (async/promise-chan)
                   :stop (async/chan)}]
      (when-not (compare-and-set! control_ nil control)
        (throw (ex-info "MFRC522 reader is already started" {})))
      (async/thread (poll! control_ control config report!)))
    this)
  (stop-reader! [this]
    (when-let [{:keys [done stop] :as control} @control_]
      (async/close! stop)
      (await-stopped! done)
      (compare-and-set! control_ control nil))
    this))

(defn reader
  "Creates a stopped Diozero MFRC522 reader.

  Options:

  | key            | description
  | -------------- | -----------
  | `:controller`  | SPI controller number (default `0`)
  | `:chip-select` | SPI chip-select number (default `0`)
  | `:reset-gpio`  | GPIO connected to MFRC522 reset (default `25`)"
  ([]
   (reader {}))
  ([config]
   (->Mfrc522Reader config (atom nil))))
