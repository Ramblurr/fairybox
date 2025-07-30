;; Copyright © 2025 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2
(ns fairy.box.hardware
  (:require
   [clojure.tools.logging :as log]
   [fairy.box.hardware.buttons :as button]
   [fairy.box.hardware.led :as led]
   [fairy.box.hardware.rfid :as rfid]
   [integrant.core :as ig]))

(defmethod ig/init-key ::enabled [_ opts]
  opts)

(defmethod ig/init-key ::rfid [_ {:keys [hardware-enablement] :as opts}]
  (when (:rfid hardware-enablement)
    (log/info "\n-=[starting rfid]=-")
    (rfid/init-rfid! opts)))

(defmethod ig/halt-key! ::rfid [_ state]
  (when state
    (log/info "\n-=[goodbye rfid]=-")
    (rfid/release-rfid! state)))

(defmethod ig/init-key ::buttons [_ {:keys [hardware-enablement] :as opts}]
  (when (:buttons hardware-enablement)
    (log/info "\n-=[starting buttons]=-")
    (button/init-buttons! opts)))

(defmethod ig/halt-key! ::buttons [_ state]
  (when state
    (log/info "\n-=[goodbye buttons]=-")
    (button/release-buttons! state)))

(defmethod ig/init-key ::leds [_ {:keys [hardware-enablement] :as opts}]
  (when (:leds hardware-enablement)
    (log/info "\n-=[starting rfid]=-")
    (led/init-leds! opts)))

(defmethod ig/halt-key! ::leds [_ state]
  (when state
    (log/info "\n-=[goodbye leds]=-")
    (led/release-leds! state)))