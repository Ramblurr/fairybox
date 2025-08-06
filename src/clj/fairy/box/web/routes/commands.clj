(ns fairy.box.web.routes.commands
  (:require
   [clojure.core.async :as async]
   [clojure.tools.logging :as log]
   [hifi.datastar :as datastar]
   [hifi.datastar.http-kit :as d*http-kit]
   [medley.core :as medley]
   [starfederation.datastar.clojure.api :as d*]))

(defn emit! [{:fairy.box/keys [http-bus-emitter] :as _req} action & {:as  payload}]
  (assert http-bus-emitter "HTTP bus emitter is required")
  #_(tap> [:emit! {:path "/player/commands"
                   :value (merge payload {:action action})}])
  (async/put! http-bus-emitter {:path "/player/commands"
                                :value (merge payload {:action action})}))

(defn dispatch-player-command [req payload]
  (try
    (condp = (:action payload)
      "play-pause"       (emit! req :audio/play-pause)
      "previous"         (emit! req :audio/prev)
      "next"             (emit! req :audio/next)
      "skip-back"        (emit! req :audio/skip-time :milliseconds (* -10 1000))
      "skip-forward"     (emit! req :audio/skip-time :milliseconds (* 10 1000))
      "set-position"     (emit! req :audio/set-position :position (/ (parse-long (:position payload)) 100.0))
      "volume-up-step"   (emit! req :audio/adjust-volume :delta 5)
      "volume-down-step" (emit! req :audio/adjust-volume :delta -5)
      "set-volume"       (emit! req :audio/set-volume :volume (parse-long (:volume payload)))
      "toggle-mute"      (emit! req :audio/toggle-mute)
      "play-queue-item"  (emit! req :audio/play-queue-index :item-index (parse-long (:item-index payload)))
      nil)
    (catch Exception e
      (log/error e))))

(defn cmd [cmd-handler]
  {:post {:handler (d*http-kit/action-handler-async
                    (fn cmd-handler* [req sse-gen]
                      (let [signals      (::datastar/signals req)
                            query-params (medley/map-keys keyword (:query-params req))]
                        (cmd-handler req (merge signals query-params))
                        (d*/close-sse! sse-gen)
                        nil)))}})

(def commands
  [""
   ["/player-cmd" (cmd dispatch-player-command)]])
