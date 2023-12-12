(ns fairy.box.hardware
  (:require
   [clojure.core.async :as async]
   [fairy.box.bus :as bus]
   [fairy.box.hardware.rfid :as rfid]
   [fairy.box.hardware.buttons :as button]
   [fairy.box.hardware.led :as led]
   [clojure.tools.logging :as log]
   [integrant.core :as ig]))

(defmethod ig/init-key ::rfid [_ opts]
  (log/info "\n-=[starting rfid]=-")
  (rfid/init-rfid! opts))

(defmethod ig/halt-key! ::rfid [_ opts]
  (log/info "\n-=[goodbye rfid]=-")
  (rfid/release-rfid! opts))

(defmethod ig/init-key ::buttons [_ {:keys [bus] :as opts}]
  (log/info "\n-=[starting buttons]=-")
  (button/init-buttons! opts))

(defmethod ig/halt-key! ::buttons [_ opts]
  (log/info "\n-=[goodbye buttons]=-")
  (button/release-buttons! opts))

(defmethod ig/init-key ::leds [_ opts]
  (log/info "\n-=[starting rfid]=-")
  (led/init-leds! opts))

(defmethod ig/halt-key! ::leds [_ opts]
  (log/info "\n-=[goodbye leds]=-")
  (led/release-leds! opts))
