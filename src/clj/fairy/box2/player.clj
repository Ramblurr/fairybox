(ns fairy.box2.player
  "Vinyl adapter which publishes observations through the Box2 runtime boundary."
  (:require
   [ol.vinyl :as vinyl]))

(def ^:private state-events
  {:vlc/error  :error  :vlc/finished :finished :vlc/opening :opening
   :vlc/paused :paused :vlc/playing  :playing  :vlc/stopped :stopped})

(defn start!
  "Creates a Vinyl player. Its callbacks only call the supplied `dispatch!`."
  [dispatch!]
  (let [player   (vinyl/create-player)
        context_ (atom nil)
        subscription-id
        (vinyl/subscribe!
         player
         (fn [{:keys [new-time ol.vinyl/event]}]
           (when-let [context @context_]
             (cond
               (state-events event)
               (dispatch! {:name :player.ev/state-changed
                           :data {:playback-context context :state (state-events event)}})
               (= :vlc/time-changed event)
               (dispatch! {:name :player.ev/time-changed
                           :data {:playback-context context :time-ms (long new-time)}})))))]
    {:context_ context_ :player player :subscription-id subscription-id}))

(defn install-queue! [{:keys [context_ player]} {:keys [paths playback-context]}]
  (reset! context_ playback-context)
  (vinyl/dispatch player :playback/clear-all)
  (vinyl/dispatch player :playback/append :paths paths))

(defn start-playback! [{:keys [player]} _]
  (vinyl/dispatch player :playback/play))

(defn stop! [{:keys [player subscription-id]}]
  (vinyl/unsubscribe! player subscription-id)
  (vinyl/release-player! player))
