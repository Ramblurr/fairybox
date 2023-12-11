(ns fairy.box.hardware
  (:require
   [clojure.core.async :as async]
   [fairy.box.hardware.rfid :as rfid]
   [fairy.box.hardware.buttons :as button]
   [fairy.box.hardware.led :as led]
   [clojure.tools.logging :as log]
   [integrant.core :as ig]))

(defn rfid-handler [rfid publisher {:keys [topic value] :as event}]
  (println "rfid got: " value))

(defn buttons-handler [buttons publisher {:keys [topic value] :as event}]
  (println "buttons got: " value))

(defn leds-handler [leds publisher {:keys [topic value] :as event}]
  (println "leds got: " value))

(defn init-rfid! [opts]
  (rfid/init-rfid! opts))

(defn init-buttons! [opts]
  (button/init-buttons! opts))

(defn init-leds! [opts]
  (led/init-leds! opts))

(defn release-rfid! [rfid]
  (rfid/release-rfid! rfid))

(defn release-buttons! [buttons]
  (button/release-buttons! buttons))

(defn release-leds! [leds]
  (led/release-leds! leds))

(defn system-loop [name handler topic exit-fn exit-ch subscriber {:keys [publication publisher] :as bus} init-state]
  (async/go-loop [state init-state]
    (async/alt!
      exit-ch ([_]
               ;; (prn "pre goodbye " name)
               (exit-fn state)
               (async/unsub publication topic subscriber)
               (async/close! exit-ch)
               (async/close! subscriber)
               (log/info (format "\n-=[goodbye %s]=-" name))
               nil)
      subscriber ([ev]
                  (handler state publisher ev)
                  (recur state)))))

(defn init-system [{:keys [bus] :as opts} name init-fn exit-fn handler topic]
  (log/info (format "\n-=[starting %s]=-" name))
  (let [{:keys [publication]} bus
        subscriber          (async/chan)
        exit-ch             (async/chan)
        sub                 (async/sub publication topic subscriber)
        init-state          (init-fn opts)]
    (system-loop name handler topic exit-fn exit-ch subscriber bus init-state)
    {:subscriber subscriber
     :name name
     :sub        sub
     :exit-ch    exit-ch
     :state      init-state}))

(defn halt-system! [{:keys [exit-ch name]}]
  (async/put! exit-ch true))

(defmethod ig/init-key ::rfid [_ opts]
  (init-system opts "rfid" init-rfid! release-rfid! rfid-handler :rfid))

(defmethod ig/halt-key! ::rfid [_ opts]
  (halt-system! opts))

(defmethod ig/init-key ::buttons [_ opts]
  (init-system opts "buttons" init-buttons! release-buttons! buttons-handler :buttons))

(defmethod ig/halt-key! ::buttons [_ opts]
  (halt-system! opts))

(defmethod ig/init-key ::leds [_ opts]
  (init-system opts "leds" init-leds! release-leds! leds-handler :leds))

(defmethod ig/halt-key! ::leds [_ opts]
  (halt-system! opts))
