(ns fairy.box.web.front-panel
  (:require
   [clojure.core.async :as async]
   [clojure.tools.logging :as log]
   [donut.system :as ds]
   [fairy.box.hardware.led :as led]
   [fairy.box.util :as util]
   [hyperlith.core :as h]))

(def refresh-subscriber-id
  ::fat-morph)

(def refresh-interval-ms
  250)

(defn start-refresh!
  [{:keys [leds refresh! interval-ms]
    :or   {interval-ms refresh-interval-ms}}]
  (let [controller (:controller leds)]
    (assert controller "LED output controller is required")
    (assert refresh! "Hyperlith refresh function is required")
    (let [changes   (async/chan (async/sliding-buffer 1))
          throttled (util/throttle changes interval-ms)
          worker    (async/thread
                      (loop []
                        (when-some [_ (async/<!! throttled)]
                          (try
                            (refresh!)
                            (catch Throwable error
                              (log/error error
                                         "Front panel refresh failed")))
                          (recur))))]
      (led/subscribe! controller
                      refresh-subscriber-id
                      #(async/put! changes %))
      {:changes       changes
       :controller    controller
       :subscriber-id refresh-subscriber-id
       :throttled     throttled
       :worker        worker})))

(defn stop-refresh!
  [{:keys [changes controller subscriber-id throttled worker]}]
  (when (and controller subscriber-id)
    (led/unsubscribe! controller subscriber-id))
  (async/close! changes)
  (async/close! throttled)
  (when worker
    (async/alts!! [worker (async/timeout 1000)]))
  nil)

(def FrontPanelRefreshComponent
  {::ds/start
   (fn [{config ::ds/config}]
     (start-refresh! config))
   ::ds/stop
   (fn [{instance ::ds/instance}]
     (stop-refresh! instance))
   ::ds/config
   {:leds        (ds/ref [:fairy.box/components
                          :fairy.box.hardware/leds])
    :refresh!    h/refresh-all!
    :interval-ms refresh-interval-ms}})
