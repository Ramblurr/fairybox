(ns fairy.box.web.refresh
  (:require
   [clojure.core.async :as async]
   [clojure.tools.logging :as log]
   [donut.system :as ds]
   [fairy.box.util :as util]
   [hyperlith.core :as h]
   [jp.nijohando.event :as ev]))

(def refresh-interval-ms 250)

(def ^:private refresh-token ::refresh)
(def ^:private rfid-path "/hardware/input/rfid")
(def ^:private rfid-actions #{:placed :removed})
(def ^:private refresh-events-by-path
  {"/player/events"               #{:player/current-track-changed
                                    :player/muted
                                    :player/position-changed
                                    :player/queue-changed
                                    :player/repeat-changed
                                    :player/shuffle-changed
                                    :player/state-changed
                                    :player/time-changed
                                    :player/volume-changed}
   "/hardware/output/leds/events" #{:led/changed}
   "/sleep/events"                #{:sleep/changed}
   "/auto-shutdown/events"        #{:auto-shutdown/changed}
   "/tts/events"                  #{:tts/catalog-refresh-failed
                                    :tts/catalog-refresh-started
                                    :tts/catalog-updated}
   "/system" #{:system/initialized
               :system/warming-up
               :system/ready
               :system/cooling-down
               :system/shutdown
               :system/poweroff-now}})

(defn refresh-event? [{:keys [path value]}]
  (if (= rfid-path path)
    (contains? rfid-actions (:action value))
    (contains? (get refresh-events-by-path path) (:event value))))

(defn current-uid [{:keys [rfid-presence]}]
  (let [{:keys [action uid]} (some-> rfid-presence deref)]
    (when (= :placed action)
      uid)))

(defn current-event [{:keys [current-event_]}]
  (some-> current-event_ deref))

(defn request! [{:keys [requests]}]
  (when requests
    (async/offer! requests refresh-token))
  nil)

(defn- event->request [rfid-presence event]
  (cond
    (= refresh-token event)
    refresh-token

    (not (refresh-event? event))
    nil

    (= rfid-path (:path event))
    (do
      (reset! rfid-presence (:value event))
      event)

    :else
    event))

(defn start-refresh! [{:keys [bus db-conn refresh! interval-ms]
                       :or   {interval-ms refresh-interval-ms}}]
  (assert bus "Event bus is required")
  (assert db-conn "Database connection is required")
  (assert refresh! "Hyperlith refresh function is required")
  (assert (pos? interval-ms) "Refresh interval must be positive")
  (let [rfid-presence  (atom {:action :removed :uid nil})
        current-event_ (atom nil)
        requests       (async/chan
                        (async/dropping-buffer 1)
                        (keep (partial event->request rfid-presence)))
        throttled      (util/throttle requests interval-ms)
        worker         (async/thread
                         (loop []
                           (when-some [event (async/<!! throttled)]
                             (reset! current-event_
                                     (when (map? event) event))
                             (try
                               (refresh!)
                               (catch Throwable error
                                 (log/error error
                                            "Hyperlith refresh failed"))
                               (finally
                                 (reset! current-event_ nil)))
                             (recur))))
        watch-key      (Object.)
        instance       {:current-event_ current-event_
                        :db-conn        db-conn
                        :requests       requests
                        :rfid-presence  rfid-presence
                        :throttled      throttled
                        :watch-key      watch-key
                        :worker         worker}]
    (doseq [path (keys refresh-events-by-path)]
      (ev/listen bus path requests))
    (ev/listen bus rfid-path requests)
    (add-watch db-conn watch-key
               (fn [_ _ old-state new-state]
                 (when-not (= old-state new-state)
                   (request! instance))))
    instance))

(defn stop-refresh!
  [{:keys [db-conn requests throttled watch-key worker]}]
  (when (and db-conn watch-key)
    (remove-watch db-conn watch-key))
  (when requests
    (async/close! requests))
  (when throttled
    (async/close! throttled))
  (when worker
    (async/alts!! [worker (async/timeout 1000)]))
  nil)

(def RefreshComponent
  {::ds/start  (fn [{config ::ds/config}]
                 (start-refresh! config))
   ::ds/stop   (fn [{instance ::ds/instance}]
                 (stop-refresh! instance))
   ::ds/config {:bus         (ds/ref [:fairy.box/components
                                      :fairy.box.bus/bus])
                :db-conn     (ds/ref [:fairy.box/components
                                      :fairy.box.db/db])
                :refresh!    h/refresh-all!
                :interval-ms refresh-interval-ms}})
