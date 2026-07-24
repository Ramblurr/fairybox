(ns fairy.box2.player
  "Vinyl adapter which publishes observations through the Box2 runtime boundary."
  (:require
   [ol.vinyl :as vinyl]))

(def ^:private state-events
  {:vlc/error  :error  :vlc/finished :finished :vlc/opening :opening
   :vlc/paused :paused :vlc/playing  :playing  :vlc/stopped :stopped})

(defn start!
  "Creates a Vinyl player whose callbacks only offer immutable Box2 events.

  Options:

  | key               | description
  | ----------------- | -----------
  | `:submit!`         | Required non-blocking Box2 event submission function
  | `:submit-latest!`  | Replaceable non-blocking Box2 progress submission function"
  [{:keys [submit! submit-latest!]}]
  (let [player (vinyl/create-player)
        subscription-id
        (vinyl/subscribe!
         player
         (fn [{:keys [new-time ol.vinyl/event] :as observation}]
           (let [context (:ol.vinyl/playback-context observation)]
             (cond
               (state-events event)
               (submit! {:name :player.ev/state-changed
                         :data (cond-> {:state (state-events event)}
                                 context
                                 (assoc :playback-context context))})

               (= :vlc/time-changed event)
               (submit-latest!
                {:name :player.ev/time-changed
                 :data (cond-> {:time-ms (long new-time)}
                         context
                         (assoc :playback-context context))})))))]
    {:player player :subscription-id subscription-id}))

(defn- accepted []
  {:accepted? true})

(defn play-queue!
  "Asynchronously dispatches clear, append, and correlated play in chart order."
  [{:keys [player]} {:keys [paths playback-context]}]
  (vinyl/dispatch player :playback/clear-all)
  (vinyl/dispatch player :playback/append :paths paths)
  (vinyl/dispatch player
                  {:ol.vinyl/command          :playback/play
                   :ol.vinyl/playback-context playback-context})
  (accepted))

(defn pause-playback! [{:keys [player]}]
  (vinyl/dispatch player :playback/set-pause :paused? true)
  (accepted))

(defn resume-playback! [{:keys [player]}]
  (vinyl/dispatch player :playback/play)
  (accepted))

(defn stop-playback! [{:keys [player]}]
  (vinyl/dispatch player :playback/stop)
  (accepted))

(defn dispatch-effect!
  "Dispatches one player effect directly to Vinyl's ordered control loop."
  [adapter {:effect/keys [data type]}]
  (case type
    :player.fx/pause      (pause-playback! adapter)
    :player.fx/play-queue (play-queue! adapter data)
    :player.fx/resume     (resume-playback! adapter)
    :player.fx/stop       (stop-playback! adapter)
    {:accepted? false :reason :unsupported-effect}))

(defn stop! [{:keys [player subscription-id]}]
  (vinyl/unsubscribe! player subscription-id)
  (vinyl/release-player! player))
