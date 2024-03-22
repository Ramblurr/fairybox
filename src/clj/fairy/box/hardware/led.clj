(ns fairy.box.hardware.led
  (:require
   [medley.core :as m]
   [clojure.tools.logging :as log]
   [clojure.core.async :as async]
   [fairy.box.animation :as anim]
   [jp.nijohando.event :as ev])
  (:import
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
  (let [led (PwmLed. gpio (float 0.0))]
    (Diozero/registerForShutdown (into-array PwmLed [led]))
    (.off led)
    {:handle led :gpio gpio :name name :led-type :pwm}))

(defn init-led! [{:keys [led-type] :as opts}]
  ;; (prn "init LED" name)
  (case led-type
    :led (init-led-led! opts)
    :pwm (init-pwm-led! opts)))

{:path "/hardware/output/leds"
 :value  {:action :led/set
          :names [:audio/prev]
          :groups [:all]
          :value  0.5}}

{:path "/hardware/output/leds"
 :value  {:action :led/animate
          :tweens [{:from 0.0
                    :to 1.0
                    :duration 500
                    :leds [:audio/prev]}]}}
(defn led-value! [led value]
  (let [value (max 0.0 (min 1.0 value))
        {:keys [led-type handle]} led]
    (when handle
      (try
        (if (= led-type :pwm)
          (.setValue handle (float value))
          (if (> value 0.0)
            (.on handle)
            (.off handle)))
        (catch Exception e
          (log/error e "Error setting LED value" {:led led :value value}))))))

(defn apply-tween! [led-handles {:keys [value data]}]
  (when value
    (doseq [led-name data]
      (when-let [handle (get led-handles led-name)]
        (led-value! handle value)))))

(defn pulse
  ([led-handles leds repeat-times on-time off-time]
   (anim/animate! (partial apply-tween! led-handles)
                  [(anim/tween leds :from 0.0 :to 1.0 :duration on-time)
                   (anim/tween leds :from 1.0 :to 0.0 :duration off-time :delay on-time)]
                  :repeat-times repeat-times))
  ([led-handles leds repeat-times]
   (pulse led-handles leds repeat-times 500 500))
  ([led-handles leds]
   (pulse led-handles leds 1 500 500)))

(comment

  (do

    (require '[fairy.box.core :as main])
    ;; (require '[integrant.repl.state :as state])
    (def sys (:fairy.box.hardware/leds
              ;; state/system
              @main/system))
    (def leds (:leds sys))
    (def groups (:groups sys))
    (def cancel-ch (async/chan))
    (def finished-ch (async/chan)))
  ;; rcf
  ;;

  (pulse leds [:audio/prev])
  (led-value! (get leds :audio/prev) 1.0)

  (let [pulse-chan (pulse leds [:audio/play-pause])]
    (async/<!! pulse-chan)
    (led-value! (get leds :audio/play-pause) 1.0))

  (.pulse (get-in leds [:audio/prev :handle]) 1 25 1 false)
  (let [value 1.0
        fps 25
        duration 2
        delta (/ 1 (* fps duration))]
    (.setValue (get-in leds [:audio/prev :handle]) value)
    (prn "starting")
    (doseq [i (range 1 (* fps duration))]
      (let [value (- value (* i delta))]
        (prn value)
        (Thread/sleep (* delta 1000))
        (.setValue (get-in leds [:audio/prev :handle]) value))))

  (do
    (when cancel-ch (async/close! cancel-ch))
    (when finished-ch (async/close! finished-ch)))
  (anim/animate! (partial apply-tween! leds)
                 [(anim/tween [:audio/prev] :from 0.0 :to 1.0 :duration 00)
                  (anim/tween [:audio/prev] :from 1.0 :to 0.0 :duration 500 :delay 500)
                  ;; (anim/tween [:audio/prev] :from 1.0 :to 0.0 :duration 1000 :delay 200 :repeat-times 3)
                  #_(anim/tween [:audio/prev] :from 1.0 :to 0.0 :duration 1000 :delay 1000)]
                 :repeat-times 3)

  (let [ease-fn anim/ease-in-sine]
    (anim/animate! (partial apply-tween! leds) [(anim/tween [:audio/play-pause] :from 0.0 :to 1.0 :duration 200 :delay 0 :easing-fn ease-fn)
                                                (anim/tween [:audio/play-pause] :from 1.0 :to 0.0 :duration 800 :delay 200 :easing-fn ease-fn)

                                                (anim/tween [:audio/prev :audio/next] :from 0.0 :to 1.0 :duration 200 :delay 200 :easing-fn ease-fn)
                                                (anim/tween [:audio/prev :audio/next] :from 1.0 :to 0.0 :duration 800 :delay 400 :easing-fn ease-fn)

                                                (anim/tween [:audio/volume-down :audio/volume-up] :from 0.0 :to 1.0 :duration 200 :delay 400 :easing-fn ease-fn)
                                                (anim/tween [:audio/volume-down :audio/volume-up] :from 1.0 :to 0.0 :duration 800 :delay 600 :easing-fn ease-fn)]
                   :cancel-ch cancel-ch :finished-ch finished-ch :repeat-times 3))
  (async/put! cancel-ch :CANCEL)
  (async/put! cancel-ch :finish-current)

  ;;
  )
(let [affected-groups [:all :others]
      groups {:all [:audio/prev :audio/next]
              :others [:audio/something]}
      names [:audio/volume-up]]
  (set (distinct (reduce into names (map groups affected-groups)))))

(defn events-handler! [{:keys [groups leds]} {:keys [value] :as ev}]
  ;; (tap> [:LEDS ev groups leds])
  (condp = (:action value)
    :led/pulse (let [{:keys [names after-set repeat-times]
                      :or {repeat-times 1
                           after-set 1.0}} value]
                 (async/go
                   (let [pulse-chan (pulse leds names repeat-times)]
                     (async/<! pulse-chan)
                     (doseq [name names]
                       (led-value! (get leds name) after-set)))))
    :led/set (let [{names :names value :value affected-groups :groups :keys [names value] :or {names [] affected-groups []}} value
                   led-names (set (distinct (reduce into names (map groups affected-groups))))]
               #_(tap> {:got-names led-names
                      :affected-groups affected-groups
                      :names names})
               (doseq [name led-names]
                 (led-value! (get leds name) value)))))

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

(defn init-leds! [{:keys [groups leds bus] :as opts}]
  (let [listener (async/chan)
        leds (open-handles! leds)
        groups (merge groups {:all (keys leds)})
        sys {:listener listener
             :groups   groups
             :leds     leds}]
    (ev/listen bus "/hardware/output/leds" listener)
    (start-led-loop! sys listener)
    sys))

(defn release-led! [{:keys [led-type handle]}]
  (condp = led-type
    :led (do (.off handle)
             (.close handle))
    :pwm (do (.off handle)
             (.close handle))))

(defn release-leds! [{:keys [leds listener]}]
  (async/close! listener)
  (doseq [led (vals leds)]
    (release-led! led)))
