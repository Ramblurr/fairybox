(ns fairy.box.web.front-panel-test
  (:require
   [clojure.core.async :as async]
   [clojure.test :refer [deftest is]]
   [fairy.box.hardware.led :as led]
   [fairy.box.web.front-panel :as front-panel]))

(defn- await-value [channel timeout-ms]
  (let [[value port] (async/alts!! [channel (async/timeout timeout-ms)])]
    (when (= port channel)
      value)))

(deftest coalesces-led-changes-into-four-hertz-fat-morphs
  (let [controller (led/output-controller
                    (led/virtual-handles
                     [{:name :audio/play-pause :led-type :pwm}]))
        refreshes  (async/chan 4)
        instance   (front-panel/start-refresh!
                    {:leds     {:controller controller}
                     :refresh! #(async/put! refreshes (System/nanoTime))})]
    (try
      (let [first-refresh (await-value refreshes 1000)]
        (led/set-led! controller :audio/play-pause 1.0)
        (led/set-led! controller :audio/play-pause 0.0)
        (led/set-led! controller :audio/play-pause 1.0)
        (let [premature-refresh (await-value refreshes 150)
              second-refresh    (or premature-refresh
                                    (await-value refreshes 350))
              elapsed-ms        (when second-refresh
                                  (quot (- second-refresh first-refresh)
                                        1000000))
              final-value       (get (led/current-values controller)
                                     :audio/play-pause)]
          (front-panel/stop-refresh! instance)
          (led/set-led! controller :audio/play-pause 0.0)
          (is (= {:premature-refresh? false
                  :elapsed-ms-valid?  true
                  :final-value        1.0
                  :post-stop-refresh  nil}
                 {:premature-refresh? (some? premature-refresh)
                  :elapsed-ms-valid?  (and elapsed-ms
                                           (<= 200 elapsed-ms 450))
                  :final-value        final-value
                  :post-stop-refresh  (await-value refreshes 350)}))))
      (finally
        (front-panel/stop-refresh! instance)
        (led/stop-controller! controller)
        (async/close! refreshes)))))
