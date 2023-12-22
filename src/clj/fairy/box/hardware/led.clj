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
    (when [handle]
      (if (= led-type :pwm)
        (.setValue handle (float value))
        (if (> value 0.0)
          (.on handle)
          (.off handle))))))

(defn apply-tween! [led-handles {:keys [value leds]}]
  (when value
    (doseq [led-name leds]
      (led-value! (get led-handles led-name) value))))

(comment

  (do
    (require '[integrant.repl.state :as state])
    (def sys (:fairy.box.hardware/leds state/system))
    (def leds (:leds sys))
    (def groups (:groups sys))
    (def cancel-ch (async/chan))
    (def finished-ch (async/chan)))

  (do
    (when cancel-ch (async/close! cancel-ch))
    (when finished-ch (async/close! finished-ch))
    (reset! cancel-animation true)
    (reset! cancel-animation false))
  (anim/animate! (partial apply-tween! leds) [(anim/tween [:audio/prev] :from 0.0 :to 1.0 :duration 1000)
                                              #_(tween 1.0 0.0 [:audio/prev] 1000 0)
                                              (anim/tween [:audio/prev] :from 1.0 :to 0.0 :duration 1000 :delay 1000)])

  (anim/animate! (partial apply-tween! leds) cancel-ch finished-ch
                 [(anim/tween [:audio/play-pause] :from 0.0 :to 1.0 :duration 200 :delay 0)
                  (anim/tween [:audio/play-pause] :from 1.0 :to 0.0 :duration 800 :delay 200)

                  (anim/tween [:audio/prev :audio/next] :from 0.0 :to 1.0 :duration 200 :delay 200)
                  (anim/tween [:audio/prev :audio/next] :from 1.0 :to 0.0 :duration 800 :delay 400)

                  (anim/tween [:audio/volume-down :audio/volume-up] :from 0.0 :to 1.0 :duration 200 :delay 400)
                  (anim/tween [:audio/volume-down :audio/volume-up] :from 1.0 :to 0.0 :duration 800 :delay 600)])

;;
  )
(let [affected-groups [:all :others]
      groups {:all [:audio/prev :audio/next]
              :others [:audio/something]}
      names [:audio/volume-up]]
  (set (distinct (reduce into names (map groups affected-groups)))))

(defn events-handler! [{:keys [groups leds]} {:keys [value] :as ev}]
  (tap> [:LEDS ev groups leds])
  (condp = (:action value)
    :led/set (let [{names :names value :value affected-groups :groups :keys [names value] :or {names [] affected-groups []}} value
                   led-names (set (distinct (reduce into names (map groups affected-groups))))]
               (tap> {:got-names led-names
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
