(ns fairy.box.util
  (:require
   [clojure.core.async :as async]))

(defn debounce [in ms]
  (let [out (async/chan)]
    (async/go-loop [last-val nil]
      (let [val   (if (nil? last-val) (async/<! in) last-val)
            timer (async/timeout ms)
            [new-val ch] (async/alts! [timer in] :priority true)]
        (condp = ch
          timer (do
                  (tap> {:sending val})
                  (when-not
                   (async/>! out val)
                    (async/close! in))
                  (recur nil))
          in (when new-val (recur new-val)))))
    out))

(defn throttle
  "Pipes values from in to out (the ret val), but at most once every ms milliseconds.
  in should probably be a sliding buffer chan."
  [in ms]
  (let [out (async/chan)]
    (async/go-loop []
      (let [val (async/<! in)
            timer  (async/timeout ms)]
        (when val
          (if-not (async/>! out val)
            (do
              (async/close! in)
              nil)
            (do
              (async/<! timer)
              (recur))))))
    out))
