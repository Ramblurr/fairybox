(ns fairy.box.hardware.led
  (:import
   [com.diozero.util Diozero]
   [com.diozero.util Diozero]
   [com.diozero.devices LED PwmLed]))

(defn init-led-led! [{:keys [gpio name active-high?]
                      :or {active-high? true}}]
  (let [led (LED. gpio
                  active-high?
                  false)]
    (Diozero/registerForShutdown (into-array LED [led]))
    (.on led)
    {:led led :gpio gpio :name name}))

(defn init-pwm-led! [{:keys [gpio name]}]
  (let [led (PwmLed. gpio 0.0)]
    (Diozero/registerForShutdown (into-array PwmLed [led]))
    (.on led)
    (.setValue 1.0)
    {:led led :gpio gpio :name name}))

(defn init-led! [{:keys [led-type] :as opts}]
  ;; (prn "init LED" name)
  (case led-type
    :led (init-led-led! opts)
    :pwm (init-pwm-led! opts)))

(defn init-leds! [{:keys [leds]}]
  {:leds
   (doall
    (map init-led! leds))})

(defn release-leds! [{:keys [leds]}]
  (doseq [{:keys [^LED led]} leds]
    (when led
      (.off led)
      (.close led))))
