;; Copyright © 2025 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2
(ns fairy.box.hardware.led
  (:require
   [clojure.core.async :as async]
   [clojure.tools.logging :as log]
   [donut.system :as ds]
   [fairy.box.animation :as anim]
   [fairy.box.playback-limits :as playback-limits]
   [jp.nijohando.event :as ev]
   [medley.core :as m])
  (:import
   [com.diozero.devices LED PwmLed]
   [com.diozero.util Diozero]))

(defn init-led-led! [{:keys [gpio name active-high?]
                      :or   {active-high? true}}]
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

{:path  "/hardware/output/leds"
 :value {:action :led/set
         :names  [:audio/prev]
         :groups [:all]
         :value  0.5}}

{:path  "/hardware/output/leds"
 :value {:action :led/animate
         :tweens [{:from     0.0
                   :to       1.0
                   :duration 500
                   :leds     [:audio/prev]}]}}
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

(defn clamp [value]
  (double (max 0.0 (min 1.0 value))))

(defn set-led! [controller led-name value]
  ((::set-led! controller) led-name value))

(defn refresh-limit! [controller limit]
  ((::refresh-limit! controller) limit))

(defn cancel-animation! [controller animation-id]
  ((::cancel-animation! controller) animation-id))

(defn stop-controller! [controller]
  ((::stop! controller)))

(defn output-controller [led-handles]
  (let [state_ (atom {:desired-values (zipmap (keys led-handles)
                                              (repeat 0.0))
                      :applied-limit  1.0
                      :closed?        false
                      :animations     {}
                      :animation-runs {}})
        lock   (Object.)]
    {::set-led!
     (fn [led-name value]
       (locking lock
         (when-not (:closed? @state_)
           (if-let [led (get led-handles led-name)]
             (let [desired-value (clamp value)
                   state         (swap! state_ assoc-in
                                        [:desired-values led-name]
                                        desired-value)
                   applied-value (min desired-value (:applied-limit state))]
               (led-value! led applied-value)
               applied-value)
             (do
               (log/debug "Ignoring unknown LED" {:led-name led-name})
               nil)))))
     ::refresh-limit!
     (fn [limit]
       (locking lock
         (when-not (:closed? @state_)
           (let [limit (clamp limit)
                 state (swap! state_ assoc :applied-limit limit)]
             (doseq [[led-name desired-value] (:desired-values state)]
               (led-value! (get led-handles led-name)
                           (min desired-value limit)))
             limit))))
     ::register-animation!
     (fn [animation-id run-id run]
       (locking lock
         (when-not (:closed? @state_)
           (when-let [old-run-id (get-in @state_
                                         [:animation-runs animation-id])]
             (when-let [cancel-ch (get-in @state_
                                          [:animations old-run-id :cancel-ch])]
               (async/put! cancel-ch :cancel)))
           (swap! state_
                  (fn [state]
                    (-> state
                        (assoc-in [:animations run-id] run)
                        (assoc-in [:animation-runs animation-id] run-id))))
           true)))
     ::finish-animation!
     (fn [animation-id run-id]
       (locking lock
         (swap! state_
                (fn [state]
                  (cond-> (update state :animations dissoc run-id)
                    (= run-id (get-in state
                                      [:animation-runs animation-id]))
                    (update :animation-runs dissoc animation-id))))))
     ::cancel-animation!
     (fn [animation-id]
       (locking lock
         (when-let [run-id (get-in @state_ [:animation-runs animation-id])]
           (when-let [cancel-ch (get-in @state_
                                        [:animations run-id :cancel-ch])]
             (async/put! cancel-ch :cancel)))))
     ::stop!
     (fn []
       (let [runs (locking lock
                    (let [runs (vals (:animations @state_))]
                      (swap! state_ assoc :closed? true)
                      runs))]
         (doseq [{:keys [cancel-ch]} runs]
           (async/put! cancel-ch :cancel))
         (doseq [{:keys [finished-ch]} runs]
           (async/<!! finished-ch))
         nil))}))

(defn apply-tween! [controller {:keys [value data]}]
  (when value
    (doseq [led-name data]
      (set-led! controller led-name value))))

(defn- animate-leds! [controller animation-id tweens repeat-times]
  (let [animation-id (or animation-id (random-uuid))
        run-id       (random-uuid)
        cancel-ch    (async/chan)
        finished-ch  (async/promise-chan)
        run          {:cancel-ch cancel-ch :finished-ch finished-ch}]
    (if ((::register-animation! controller) animation-id run-id run)
      (do
        (anim/animate! (partial apply-tween! controller)
                       tweens
                       :repeat-times repeat-times
                       :cancel-ch cancel-ch
                       :finished-ch finished-ch)
        (async/go
          (async/<! finished-ch)
          ((::finish-animation! controller) animation-id run-id)))
      (async/put! finished-ch :cancelled))
    finished-ch))

(defn pulse
  ([controller leds repeat-times on-time off-time animation-id]
   (animate-leds! controller
                  animation-id
                  [(anim/tween leds :from 0.0 :to 1.0 :duration on-time)
                   (anim/tween leds
                               :from 1.0
                               :to 0.0
                               :duration off-time
                               :delay on-time)]
                  repeat-times))
  ([controller leds repeat-times animation-id]
   (pulse controller leds repeat-times 500 500 animation-id)))

(defn fade
  ([controller leds repeat-times from to duration start-delay animation-id]
   (animate-leds! controller
                  animation-id
                  [(anim/tween leds
                               :from (clamp from)
                               :to (clamp to)
                               :duration duration
                               :delay start-delay)]
                  repeat-times)))

(comment

  (do
    (require '[fairy.box.system :as system])
    (def sys (ds/instance @system/app_
                          [:fairy.box/components
                           :fairy.box.hardware/leds]))
    (def leds (:leds sys))
    (def controller (:controller sys))
    (def groups (:groups sys))
    (def cancel-ch (async/chan))
    (def finished-ch (async/chan)))
  ;; rcf
  ;;

  (pulse controller [:audio/prev] 1 nil)
  (set-led! controller :audio/prev 1.0)

  (let [pulse-chan (pulse controller [:audio/play-pause] 1 nil)]
    (async/<!! pulse-chan)
    (set-led! controller :audio/play-pause 1.0))

  (.pulse (get-in leds [:audio/prev :handle]) 1 25 1 false)
  (let [value    1.0
        fps      25
        duration 2
        delta    (/ 1 (* fps duration))]
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
  (anim/animate! (partial apply-tween! controller)
                 [(anim/tween [:audio/prev] :from 0.0 :to 1.0 :duration 00)
                  (anim/tween [:audio/prev] :from 1.0 :to 0.0 :duration 500 :delay 500)
                  ;; (anim/tween [:audio/prev] :from 1.0 :to 0.0 :duration 1000 :delay 200 :repeat-times 3)
                  #_(anim/tween [:audio/prev] :from 1.0 :to 0.0 :duration 1000 :delay 1000)]
                 :repeat-times 3)

  (let [ease-fn anim/ease-in-sine]
    (anim/animate! (partial apply-tween! controller) [(anim/tween [:audio/play-pause] :from 0.0 :to 1.0 :duration 200 :delay 0 :easing-fn ease-fn)
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
        groups          {:all    [:audio/prev :audio/next]
                         :others [:audio/something]}
        names           [:audio/volume-up]]
    (set (distinct (reduce into names (map groups affected-groups))))))

(defn events-handler! [{:keys [controller groups]} {:keys [value] :as _ev}]
  (let [led-names (set (distinct (reduce into
                                         (:names value)
                                         (map groups (:groups value)))))]
    (condp = (:action value)
      :led/animation-cancel (cancel-animation! controller
                                               (:animation-id value))
      :led/pulse (let [{:keys [after-set repeat-times animation-id]
                        :or   {repeat-times 1
                               after-set    1.0}}                   value
                       result-ch (pulse controller
                                        led-names
                                        repeat-times
                                        animation-id)]
                   (async/go
                     (when (= :finished (async/<! result-ch))
                       (doseq [led-name led-names]
                         (set-led! controller led-name after-set)))))

      :led/fade (let [{:keys [after-set repeat-times animation-id
                              from to duration start-delay]
                       :or   {repeat-times 1
                              from         1.0
                              to           0.0
                              duration     1000
                              start-delay  0
                              after-set    0.0}}                  value
                      result-ch (fade controller
                                      led-names
                                      repeat-times
                                      from
                                      to
                                      duration
                                      start-delay
                                      animation-id)]
                  (async/go
                    (when (= :finished (async/<! result-ch))
                      (doseq [led-name led-names]
                        (set-led! controller led-name after-set)))))
      :led/set (doseq [led-name led-names]
                 (set-led! controller led-name (:value value))))))

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
        :when     (> freq 1)]
    id))

(defn validate-led-def! [defs]
  (let [names (map :name defs)
        dup   (dupes names)]
    (when (seq dup)
      (throw (ex-info "Duplicate LED names" {:names dup})))))

(def led-subscriber-id
  ::output-controller)

(defn init-leds! [{:keys [groups leds bus playback-limits]}]
  (let [listener   (async/chan)
        leds       (open-handles! leds)
        groups     (merge groups {:all (keys leds)})
        controller (output-controller leds)
        sys        {:listener        listener
                    :groups          groups
                    :leds            leds
                    :controller      controller
                    :playback-limits playback-limits
                    :subscriber-id   led-subscriber-id}]
    (playback-limits/subscribe!
     playback-limits
     led-subscriber-id
     #(refresh-limit! controller
                      (get-in % [:limits :led/max-brightness])))
    (ev/listen bus "/hardware/output/leds" listener)
    (assoc sys :led-loop (start-led-loop! sys listener))))

(defn release-led! [{:keys [led-type handle]}]
  (condp = led-type
    :led (do (.off handle)
             (.close handle))
    :pwm (do (.off handle)
             (.close handle))))

(defn release-leds!
  [{:keys [leds listener led-loop controller
           playback-limits subscriber-id]}]
  (when (and playback-limits subscriber-id)
    (playback-limits/unsubscribe! playback-limits subscriber-id))
  (async/close! listener)
  (when controller
    (stop-controller! controller))
  (when led-loop
    (async/<!! led-loop))
  (doseq [led (vals leds)]
    (release-led! led)))

(defn start-component! [{:keys [hardware-enablement] :as config}]
  (if (:leds hardware-enablement)
    (assoc (init-leds! config) :enabled? true)
    {:enabled? false
     :groups   {}
     :leds     {}}))

(defn stop-component! [{:keys [enabled?] :as instance}]
  (when enabled?
    (release-leds! instance)))

(def LedsComponent
  {::ds/start  (fn [{config ::ds/config}]
                 (start-component! config))
   ::ds/stop   (fn [{instance ::ds/instance}]
                 (stop-component! instance))
   ::ds/config {:hardware-enablement (ds/ref [:config
                                              :fairy.box/components
                                              :fairy.box.hardware/enabled])
                :bus                 (ds/ref [:fairy.box/components
                                              :fairy.box.bus/bus])
                :leds                (ds/ref [:config
                                              :fairy.box/components
                                              :fairy.box.hardware/leds
                                              :leds])
                :groups              (ds/ref [:config
                                              :fairy.box/components
                                              :fairy.box.hardware/leds
                                              :groups])
                :playback-limits     (ds/ref [:fairy.box/components
                                              :fairy.box.playback-limits/policy])}})
