(ns fairy.box.hardware
  (:require
   [clojure.core.async :as async]
   [fairy.box.bus :as bus]
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

(defmethod ig/init-key ::rfid [_ opts]
  (bus/init-system opts "rfid" init-rfid! release-rfid! rfid-handler :rfid))

(defmethod ig/halt-key! ::rfid [_ opts]
  (bus/halt-system! opts))

(defmethod ig/init-key ::buttons [_ opts]
  (bus/init-system opts "buttons" init-buttons! release-buttons! buttons-handler :buttons))

(defmethod ig/halt-key! ::buttons [_ opts]
  (bus/halt-system! opts))

(defmethod ig/init-key ::leds [_ opts]
  (bus/init-system opts "leds" init-leds! release-leds! leds-handler :leds))

(defmethod ig/halt-key! ::leds [_ opts]
  (bus/halt-system! opts))
