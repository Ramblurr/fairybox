(ns fairy.box.hardware.led
  (:require
   [medley.core :as m]
   [clojure.tools.logging :as log]
   [clojure.core.async :as async]
   [jp.nijohando.event :as ev])
  (:import
   [com.diozero.util Diozero]
   [com.diozero.util Diozero]
   [com.diozero.devices LED PwmLed]))

(defn init-led-led! [{:keys [gpio name active-high?]
                      :or {active-high? true}}]
  (let [led (LED. gpio
                  active-high?)]
    (Diozero/registerForShutdown (into-array LED [led]))
    (.off led)
    {:handle led :gpio gpio :name name :led-type :led}))

(defn init-pwm-led! [{:keys [gpio name]}]
  (let [led (PwmLed. gpio 0.0)]
    (Diozero/registerForShutdown (into-array PwmLed [led]))
    (.off led)
    (.setValue 1.0)
    {:handle led :gpio gpio :name name :led-type :pwm}))

(defn init-led! [{:keys [led-type] :as opts}]
  ;; (prn "init LED" name)
  (case led-type
    :led (init-led-led! opts)
    :pwm (init-pwm-led! opts)))

{:path "/hardware/output/leds"
 :value  {:action :led/set
          :names [:audio/prev]
          :value  0.5}}

(defn events-handler! [{:keys [leds]} {:keys [value] :as ev}]
  (tap> [:LEDS ev])
  (condp = (:action value)
    :led/set (let [{:keys [names value]} value
                   value (max 0.0 (min 1.0 value))]
               (doseq [name names]
                 (when-let [{:keys [led-type handle]} (get leds name)]
                   (if (= led-type :pwm)
                     (.setValue handle value)
                     (if (> value 0.0)
                       (.on handle)
                       (.off handle))))))))

(defn start-led-loop! [opts listener]
  (async/go-loop []
    (when-some [event (async/<! listener)]
      (try
        (events-handler! opts event)
        (catch Exception e
          (log/error e "Encountered exception when stopping rfid poller")))
      (recur))))

(defn open-handles! [defs]
  (m/index-by :name
              (map init-led! defs)))

(defn dupes [seq]
  (for [[id freq] (frequencies seq)
        :when (> freq 1)]
    id))

(defn validate-led-def! [defs]
  (let [names (map :name defs)
        dup (dupes names)]
    (when (seq dup)
      (throw (ex-info "Duplicate LED names" {:names dup})))))

(defn init-leds! [{:keys [leds bus] :as opts}]
  (let [listener (async/chan)
        leds (open-handles! leds)]
    (ev/listen bus "/hardware/output/leds" listener)
    (start-led-loop! (assoc opts :leds leds) listener)
    {:listener listener
     :leds leds}))

(defn release-leds! [{:keys [leds listener]}]
  (async/close! listener)
  (doseq [{:keys [^LED handle]} (vals leds)]
    (when handle
      (.off handle)
      (.close handle))))
