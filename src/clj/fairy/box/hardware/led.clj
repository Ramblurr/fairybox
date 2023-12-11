(ns fairy.box.hardware.led
  (:import
   [com.diozero.util Diozero]
   [com.diozero.util Diozero]
   [com.diozero.devices LED]))

(defn init-led! [{:keys [gpio name]}]
  (let [led (LED. gpio)]
    (Diozero/registerForShutdown (into-array LED [led]))
    (.on led)
    {:led led :gpio gpio :name name}))

(defn init-leds! [{:keys [leds]}]
  {:leds
   (doall
    (map init-led! leds))})

(defn release-leds! [{:keys [leds]}]
  (doseq [{:keys [^LED led]} leds]
    (when led
      (.off led)
      (.close led))))
