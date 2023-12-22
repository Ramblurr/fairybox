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

(defn tween [leds & {:keys [delay
                            from
                            to
                            duration
                            easing-fn]
                     :or {from 0.0
                          to 1.0
                          duration 500
                          delay 0
                          easing-fn ease-linear}}]
  (let [times (concat (range 0 1 (/ 50 duration)) [1])]
    {:from from
     :to to
     :duration duration
     :delay delay
     :leds leds
     :times times
     :deltas (normalized-deltas easing-fn (count times))}))

(defn update-tween [t {:keys [from to duration delay leds deltas] :as tween}]
  (let [v         (first deltas)
        direction (if (< from to) 1 -1)
        closed?   (nil? v)
        finished? closed?
        started? (>= t delay)]

    (if closed?
      nil
      (if started?
        (-> tween
            (update :value (fnil + from) (* direction v))
            (assoc :deltas (rest deltas)))
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
  ([apply-fn! cancel-ch tweens]
   (animate! apply-fn! cancel-ch nil tweens))
  ([apply-fn! cancel-ch finished-ch tweens]
   (async/go-loop [state {:tweens tweens :step 1 :t 0}]
     (let [new-tweens (update-tweens state)]
       (doseq [tween new-tweens]
         (apply-fn! tween))
       (if (empty? new-tweens)
         (when finished-ch
           (async/put! finished-ch :finished))
         (async/alt!
           cancel-ch ([_]
                      (when finished-ch
                        (async/put! finished-ch :cancelled))
                      nil)
           (async/timeout 50) ([_]
                               (recur {:tweens new-tweens :step (inc (:step state)) :t (+ (:t state) 50)}))))))))
