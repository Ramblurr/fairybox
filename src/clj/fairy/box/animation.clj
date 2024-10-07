(ns fairy.box.animation
  (:require
   [clojure.core.async :as async]))

(def ease-linear identity)

(defn ease-sigmoid
  [x]
  (if (<= x 0.5)
    0
    1))

;;; Sinusoidal easing functions

(defn ease-in-sine
  [x]
  (- 1 (Math/cos (* x Math/PI 1/2))))

(defn ease-out-sine
  [x]
  (Math/sin (* x Math/PI 1/2)))

(defn ease-in-out-sine
  [x]
  (* (- (Math/cos (* x Math/PI)) 1) -1/2))

;;; Quadratic easing functions

(defn ease-in-quad
  [x]
  (* x x))

(defn ease-out-quad
  [x]
  (- 1 (* (- 1 x) (- 1 x))))

(defn ease-in-out-quad
  [x]
  (cond
    (< x 0.5) (* 2 x x)
    :else (- 1 (/ (Math/pow (+ (* x -2) 2) 2) 2))))

;;; Cubic easing functions

(defn ease-in-cubic
  [x]
  (* x x x))

(defn ease-out-cubic
  [x]
  (- 1 (Math/pow (- 1 x) 3)))

(defn ease-in-out-cubic
  [x]
  (cond
    (< x 0.5) (* 4 x x x)
    :else (- 1 (/ (Math/pow (+ (* x -2) 2) 3) 2))))

;;; Quartic easing functions

(defn ease-in-quart
  [x]
  (* x x x x))

(defn ease-out-quart
  [x]
  (- 1 (Math/pow (- 1 x) 4)))

(defn ease-in-out-quart
  [x]
  (cond
    (< x 0.5) (* 8 x x x x)
    :else (- 1 (/ (Math/pow (+ (* x -2) 2) 4) 2))))

;;; Quintic easing functions

(defn ease-in-quint
  [x]
  (* x x x x x))

(defn ease-out-quint
  [x]
  (- 1 (Math/pow (- 1 x) 5)))

(defn ease-in-out-quint
  [x]
  (cond
    (< x 0.5) (* 16 x x x x x)
    :else (- 1 (/ (Math/pow (+ (* x -2) 2) 5) 2))))

;;; Exponential easing functions

(defn ease-in-expo
  [x]
  (cond
    (zero? x) 0
    :else (Math/pow 2 (- (* x 10) 10))))

(defn ease-out-expo
  [x]
  (cond
    (= 1 x) 1
    :else (- 1 (Math/pow 2, (* x -10)))))

(defn ease-in-out-expo
  [x]
  (cond
    (zero? x) 0
    (= 1 x) 1
    (< x 0.5) (/ (Math/pow 2 (- (* x 20) 10)) 2)
    :else (/ (- 2 (Math/pow 2 (+ (* x -20) 10))) 2)))

;;; Circular easing functions

(defn ease-in-circ
  [x]
  (- 1 (Math/sqrt (- 1 (Math/pow x 2)))))

(defn ease-out-circ
  [x]
  (Math/sqrt (- 1 (Math/pow (- x 1) 2))))

(defn ease-in-out-circ
  [x]
  (cond
    (< x 0.5) (/ (- 1 (Math/sqrt (- 1 (Math/pow (* x 2) 2)))) 2)
    :else (/ (+ (Math/sqrt (- 1 (Math/pow (+ (* x -2) 2) 2))) 1) 2)))

;;; Back easing functions

(defn ease-in-back
  [x]
  (let [c1 1.70158
        c2 (+ c1 1)]
    (- (* c2 x x x)
       (* c1 x x))))

(defn ease-out-back
  [x]
  (let [c1 1.70158
        c2 (+ c1 1)]
    (+ 1
       (* c2 (Math/pow (- x 1) 3))
       (* c1 (Math/pow (- x 1) 2)))))

(defn ease-in-out-back
  [x]
  (let [c1 1.70158
        c2 (+ c1 1.525)]
    (cond
      (< x 0.5) (/ (* (Math/pow (* x 2) 2) (- (* (+ c2 1) x 2) c2)) 2)
      :else (/ (+ (* (Math/pow (- (* x 2) 2) 2) (+ (* (+ c2 1) (- (* x 2) 2)) c2)) 2) 2))))

;;; Elastic easing functions

(defn ease-in-elastic
  [x]
  (cond
    (zero? x) 0
    (= 1 x) 1
    :else (* (- (Math/pow 2 (- (* x 10) 10))) (Math/sin (* (- (* x 10) 10.75) (* 2 Math/PI 1/3))))))

(defn ease-out-elastic
  [x]
  (cond
    (zero? x) 0
    (= 1 x) 1
    :else (+ (* (Math/pow 2 (* x -10)) (Math/sin (* (- (* x 10) 0.75) (* 2 Math/PI 1/3)))) 1)))

(defn ease-in-out-elastic
  [x]
  (cond
    (zero? x) 0
    (= 1 x) 1
    (< x 0.5) (/ (- (* (Math/pow 2 (- (* x 20) 10)) (Math/sin (* (- (* x 10) 11.125) (* 2 Math/PI 5/4))))) 2)
    :else (+ (/ (* (Math/pow 2 (+ (* x -20) 10)) (Math/sin (* (- (* x 10) 11.125) (* 2 Math/PI 5/4)))) 2) 1)))

;;; Bouncing easing functions

(declare ease-out-bounce)

(defn ease-in-bounce
  [x]
  (- 1 (ease-out-bounce (- 1 x))))

(defn ease-out-bounce
  [x]
  (let [n 7.5625
        d 2.75]
    (cond
      (< x (/ 1 d)) (* n x x)
      (< x (/ 2 d)) (+ (* n (- x (/ 1.5 d)) (- x (/ 1.5 d))) 0.75)
      (< x (/ 2.5 d)) (+ (* n (- x (/ 2.25 d)) (- x (/ 2.25 d))) 0.9375)
      :else (+ (* n (- x (/ 2.625 d)) (- x (/ 2.625 d))) 0.984375))))

(defn ease-in-out-bounce
  [x]
  (cond
    (< x 0.5) (/ (- 1 (ease-out-bounce (- 1 (* x 2)))) 2)
    :else (/ (+ 1 (ease-out-bounce (- (* x 2) 1))) 2)))

(defn normalized-deltas
  [easing-fn step-count]
  ;; get step-count [s-min s-max] pairs from 0 to 1
  (let [steps (map (fn [i]
                     [(* i (/ 1 step-count))
                      (* (inc i) (/ 1 step-count))])
                   (range step-count))]
    ;; delta = f(s-max) - f(s-min)
    (map (fn [[s-min s-max]]
           (- (easing-fn s-max)
              (easing-fn s-min)))
         steps)))

(def FPS 30)

(def DT (* 1000 (float (/ 1 FPS))))

(defn tween
  "Create a tween (a map of values).

  - data - an opaque value that will be accessible to the apply-fn! function

  Optional keyword values:
  - from (default 0.0) - the starting value
  - to (default 1.0) - the ending value
  - duration (default 500) - the duration of the tween in milliseconds
  - repeat-times (default 1) - the number of times to repeat the tween
  - delay (default 0) - the delay before the tween starts in milliseconds
  - easing-fn (default ease-linear) - the easing function to use"
  [data & {:keys [delay
                  from
                  to
                  duration
                  repeat-times
                  easing-fn]
           :or {from 0.0
                to 1.0
                duration 500
                repeat-times 1
                delay 0
                easing-fn ease-linear}}]
  (let [steps (int  (* FPS (/ duration 1000)))]
    {:from from
     :to to
     :duration duration
     :orig-delay delay
     :delay delay
     :data data
     :steps steps
     :orig-deltas (normalized-deltas easing-fn steps)
     :current-iteration 1
     :total-times (max 1 repeat-times)
     :deltas (normalized-deltas easing-fn steps)}))

(defn- next-delay
  "Return the new delay value for the current-iteration"
  [duration delay total-repeats current-iteration]
  (let [total-duration (* duration total-repeats)
        total-delay    (* delay total-repeats)
        current-delay  (* delay current-iteration)]
    (- total-duration total-delay current-delay))
  ;;     new_delay = delay + current_iteration * (duration + delay)
  (+ delay
     (* current-iteration
        (+ duration delay))))

(next-delay 1000 500 3 1)
(next-delay 1000 0 3 3)
(next-delay 200 400 2 1)

(defn update-tween [t {:keys [from to duration delay orig-delay orig-deltas deltas current-iteration total-times] :as tween}]
  (let [v         (first deltas)
        direction (if (< from to) 1 -1)
        closed?   (nil? v)
        finished? (and closed? (>= current-iteration total-times))
        started?  (>= t delay)]

    (if finished?
      nil
      (if started?
        (if closed?
          (-> tween
              (assoc :t t)
              (update :current-iteration inc)
              (assoc :value from)
              (assoc :deltas orig-deltas)
              (assoc :delay (next-delay duration orig-delay total-times (inc current-iteration))))
          (-> tween
              (update :value (fnil + from) (* direction v))
              (assoc :deltas (rest deltas))))
        tween))))

(defn update-tweens [{:keys [tweens t]}]
  (let [new-tweens (filter some? (map #(update-tween t %) tweens))]
    (if (empty? new-tweens)
      nil
      new-tweens)))

#_(-> [(tween 0.0 1.0 [:audio/prev] 500 0)]
      (animation-step)
      (animation-step)
      (animation-step)
      (animation-step)
      (animation-step)
      (animation-step)
      (animation-step)
      (animation-step)
      (animation-step)
      (animation-step)
      (animation-step)
      (animation-step))

(defn animate!
  "Executes an animation in a go-loop.

    - apply-fn! - a 1-arity function that receives a single tween for side-effects
    - tweens      - a list of tweens. (see fairy.box.animation/tween)
    - optional keyword arguments:
       - :finished-ch - (default: nil) a channel that :finished will be written to upon termination
       - :cancel-ch - (default: nil) a channel that can cancel or shorten the animation. if :finish-current is taken from the channel then the
                     animation will terminate after the current iteration is complete. any other value taken will
                     immediately terminate the animation
       - :repeat-times  - (default 1) the number of times to run the animation
  Returns a channel that will be closed when the animation is finished or canceled."
  ([apply-fn! tweens & {:keys [repeat-times finished-ch cancel-ch]
                        :or   {repeat-times 1
                               cancel-ch nil
                               finished-ch nil}}]
   (let [orig-tweens tweens]
     (async/go-loop [state {:tweens orig-tweens :step 1 :t 0 :iteration 1}]
       (let [new-tweens (update-tweens state)
             closed?    (empty? new-tweens)
             repeat?    (< (:iteration state) repeat-times)
             finished?  (and closed? (not repeat?))]
         (doseq [tween new-tweens]
           (apply-fn! tween))
         (cond
           finished?             (when finished-ch (async/put! finished-ch :finished) nil)
           (and closed? repeat?) (recur {:tweens orig-tweens :step 1 :t 0 :iteration (inc (:iteration state))})
           :else                 (let [timeout (async/timeout DT)
                                       ports (concat [timeout] (when cancel-ch [cancel-ch]))
                                       [op port] (async/alts! ports)]
                                   (cond
                                     (= port timeout) (recur {:tweens new-tweens :step (inc (:step state)) :t (+ (:t state) DT) :iteration (:iteration state)})
                                     (= op :finish-current) (recur {:tweens new-tweens :step (inc (:step state)) :t (+ (:t state) DT) :iteration repeat-times})
                                     :else
                                     (when finished-ch (async/put! finished-ch :cancelled))))))))))
