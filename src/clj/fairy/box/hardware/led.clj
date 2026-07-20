;; Copyright © 2025 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2
(ns fairy.box.hardware.led
  (:require
   [clojure.core.async :as async]
   [clojure.tools.logging :as log]
   [donut.system :as ds]
   [fairy.box.animation :as anim]
   [jp.nijohando.event :as ev]
   [medley.core :as m])
  (:import
   [com.diozero.devices LED PwmLed]
   [com.diozero.util Diozero]))

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

(defonce ^:private animation-state (atom {:animations {}}))

(defn add-animation! [{:keys [animation-id] :as anim}]
  (assert animation-id)
  (swap! animation-state (fn [s]
                           ;; cancel the old one, if it exists
                           (let [{:keys [cancel-ch]} (get-in s [:animations animation-id])]
                             (when cancel-ch
                               (async/put! cancel-ch :cancel)))
                           (assoc-in s [:animations animation-id] anim))))

(defn cancel-animation! [animation-id]
  (swap! animation-state (fn [s]
                           (let [{:keys [cancel-ch]} (get-in s [:animations animation-id])]
                             (when cancel-ch
                               (async/put! cancel-ch :cancel)))
                           (assoc-in s [:animations animation-id] nil))))

(defn cancel-all-animations! []
  (swap! animation-state (fn [s]
                           (doseq [{:keys [cancel-ch]} (vals (:animations s))]
                             (when cancel-ch
                               (async/put! cancel-ch :cancel)))
                           (assoc s :animations {}))))

(defn pulse
  ([led-handles leds repeat-times on-time off-time animation-id]
   (let [cancel-ch (async/chan)
         animation-id (or animation-id (random-uuid))]
     (add-animation! {:animation-id animation-id :cancel-ch cancel-ch})
     (anim/animate! (partial apply-tween! led-handles)
                    [(anim/tween leds :from 0.0 :to 1.0 :duration on-time)
                     (anim/tween leds :from 1.0 :to 0.0 :duration off-time :delay on-time)]
                    :repeat-times repeat-times
                    :cancel-ch cancel-ch)))
  ([led-handles leds repeat-times animation-id]
   (pulse led-handles leds repeat-times 500 500 animation-id)))

(defn clamp [v]
  (max (min v 1.0) 0))

(defn fade
  ([led-handles leds repeat-times from to duration start-delay animation-id]
   (let [cancel-ch (async/chan)
         animation-id (or animation-id (random-uuid))]
     (add-animation! {:animation-id animation-id :cancel-ch cancel-ch})
     (anim/animate! (partial apply-tween! led-handles)
                    [(anim/tween leds :from (clamp from) :to (clamp to) :duration duration :delay start-delay)]
                    :repeat-times repeat-times
                    :cancel-ch cancel-ch))))

(comment

  (:animations @animation-state)
  (cancel-all-animations!)

  (do
    (require '[fairy.box.system :as system])
    (def sys (ds/instance @system/app_
                          [:fairy.box/components
                           :fairy.box.hardware/leds]))
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

  (let [affected-groups [:all :others]
        groups {:all [:audio/prev :audio/next]
                :others [:audio/something]}
        names [:audio/volume-up]]
    (set (distinct (reduce into names (map groups affected-groups))))))

(defn events-handler! [{:keys [groups leds]} {:keys [value] :as ev}]
  (let [led-names (set (distinct (reduce into (:names value) (map groups (:groups value)))))]
    ;; (tap> [:LEDS ev groups leds])
    (condp = (:action value)
      :led/animation-cancel (cancel-animation! (:animation-id value))
      :led/pulse (let [{:keys [after-set repeat-times animation-id]
                        :or {repeat-times 1
                             after-set 1.0}} value]
                   (async/go
                     (let [pulse-chan (pulse leds led-names repeat-times animation-id)]
                       ;; run the after-set, but only if the animation wasn't cancelled
                       (when (nil? (async/<! pulse-chan))
                         (doseq [name led-names]
                           (led-value! (get leds name) after-set))))))

      :led/fade (let [{:keys [after-set repeat-times animation-id from to duration start-delay]
                       :or {repeat-times 1
                            from 1.0
                            to 0.0
                            duration 1000
                            start-delay 0
                            after-set 0.0}} value]
                  (async/go
                    (let [fade-chan (fade leds led-names repeat-times from to duration start-delay animation-id)]
                      ;; run the after-set, but only if the animation wasn't cancelled
                      (when (nil? (async/<! fade-chan))
                        (doseq [name led-names]
                          (led-value! (get leds name) after-set))))))
      :led/set (let [{:keys [value animation-id]} value]
                 (doseq [name led-names]
                   (led-value! (get leds name) value))))))

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
  (cancel-all-animations!)
  (async/close! listener)
  (doseq [led (vals leds)]
    (release-led! led)))

(defn start-component! [{:keys [hardware-enablement] :as config}]
  (if (:leds hardware-enablement)
    (assoc (init-leds! config) :enabled? true)
    {:enabled? false
     :groups {}
     :leds {}}))

(defn stop-component! [{:keys [enabled?] :as instance}]
  (when enabled?
    (release-leds! instance)))

(def LedsComponent
  {::ds/start (fn [{config ::ds/config}]
                (start-component! config))
   ::ds/stop (fn [{instance ::ds/instance}]
               (stop-component! instance))
   ::ds/config {:hardware-enablement (ds/ref [:config
                                              :fairy.box/components
                                              :fairy.box.hardware/enabled])
                :bus (ds/ref [:fairy.box/components
                              :fairy.box.bus/bus])
                :leds (ds/ref [:config
                               :fairy.box/components
                               :fairy.box.hardware/leds
                               :leds])
                :groups (ds/ref [:config
                                 :fairy.box/components
                                 :fairy.box.hardware/leds
                                 :groups])}})
