(ns fairy.box.audio.system2
  (:require
   [babashka.fs :as fs]
   [clojure.core.async :as async]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [donut.system :as ds]
   [fairy.box.audio.browse :as browse]
   [fairy.box.db :as db]
   [fairy.box.playback-limits :as playback-limits]
   [fairy.box.tts :as tts]
   [fairy.box.util :as util]
   [jp.nijohando.event :as ev]
   [ol.vinyl :as mp]))

(defonce factory (mp/init!))

(def ^:private audio-init-state {:playback {:state nil}
                                 :mixer    {:muted? nil
                                            :volume nil}
                                 :queue    nil
                                 :config   {:volume-up-step   5
                                            :volume-down-step -5}})

(defonce audio-state (atom audio-init-state))

(defn announce-mrl? [sys mrl]
  (when (str/starts-with? (or mrl "") "file://")
    (db/announce-file? sys (-> (io/as-url mrl) .toURI fs/path str))))

(defn announce-track? [sys {:keys [genre duration mrl]}]
  (or
   ;; audiobook genre
   (str/includes? (str/lower-case (or genre "")) "book")
   ;; longer than 10 minutes
   (>= (or duration 0) (* 10 60 1000))
   (announce-mrl? sys mrl)))

(defn mrl->title [mrl]
  (->
   (io/as-url mrl)
   .toURI
   fs/path
   fs/file-name
   fs/strip-ext))

(defn announcment-for-track [{:keys [track-number title mrl]}]
  (let [title (if (str/blank? title)
                (mrl->title mrl)
                title)]
    (if (str/blank? track-number)
      (str "<speak>" title "</speak>")
      (str "<speak><say-as interpret-as=\"cardinal\">" track-number "</say-as> " title "</speak>"))))

(defn- play-now [{:keys [player] :as _sys} paths]
  (tap> [:playing-now paths])
  (mp/dispatch player :playback/clear-all)
  (mp/dispatch player :playback/append :paths paths)
  (mp/dispatch player :playback/advance))

(defn- expand-path [{:keys [player] :as _sys} path]
  (let [result @(mp/parse-meta player [path])]
    (when (instance? Throwable result)
      (throw (ex-info "Failed to parse media tracks"
                      {:item-path path}
                      result)))
    (let [tracks (vec result)]
      (if (every? #(= :media-parsed-status/done (:parse-status %)) tracks)
        (filterv (comp seq :audio-tracks) tracks)
        (throw (ex-info "Failed to parse media tracks"
                        {:item-path    path
                         :parse-result tracks}))))))

(defn- non-blank-string [value]
  (some-> value str str/trim not-empty))

(defn- track->metadata [{:keys [meta mrl]}]
  {:title  (or (non-blank-string (:meta/title meta))
               (str (mrl->title mrl)))
   :album  (non-blank-string (:meta/album meta))
   :artist (non-blank-string (:meta/artist meta))})

(defn metadata-for [{:keys [settings] :as sys} item-path]
  (let [path (when item-path
               (browse/canonicalize-path settings item-path))]
    (when-not path
      (throw (ex-info "Media path is outside the configured media directory"
                      {:error     :audio/invalid-media-path
                       :item-path item-path
                       :media-dir (browse/media-dir settings)})))
    (let [playable-type (browse/playable-type settings path)]
      (when-not (#{:dir :file :playlist} playable-type)
        (throw (ex-info "Media path is not a supported metadata source"
                        {:error          :audio/not-playable-type
                         :item-path      item-path
                         :canonical-path path
                         :playable-type  playable-type})))
      (mapv track->metadata (expand-path sys path)))))

(defn- announce-then-play [sys path]
  (util/thread
    (try
      (when-let [tracks (expand-path sys path)]
        (let [announcements (->> tracks
                                 (sort-by #(-> % :meta :track-number))
                                 (map announcment-for-track)
                                 (mapv  #(tts/tts (assoc sys
                                                         :tts-cache-dir (tts/tts-cache-dir (:settings sys))) %)))
              new-tracks    (interleave announcements tracks)]
          (play-now sys new-tracks)))
      (catch Exception e
        (log/error e "Error parsing track metadata for announcement")))))

(defn- set-queue-source! [settings path]
  (swap! audio-state update :queue merge
         {:source-type (if (= :playlist (browse/playable-type settings path))
                         :playlist
                         :folder)
          :source-path (or (browse/media-relative-path settings path) path)}))

(defn- clear-queue-source! []
  (swap! audio-state update :queue dissoc :source-type :source-path))

(defn play-path!
  [{:keys [settings] :as sys}
   {:keys [item-path announce-per-track?] :as _value}]
  (assert item-path "Path must not be nil")
  (if-let [path (browse/canonicalize-path settings item-path)]
    (do
      (set-queue-source! settings path)
      (if announce-per-track?
        (announce-then-play sys path)
        (play-now sys [path])))
    (throw (ex-info
            "Could not play. Requested path could not be found in media-dir"
            {:requested-path item-path
             :media-dir      (browse/media-dir settings)}))))

(defn maximum-volume [policy]
  (int (playback-limits/current-limit policy :audio/max-volume)))

(defn- player-event
  "Constructs a valid event map for a player event"
  [event]
  {:path "/player/events" :value event})

(defn- set-mixer [k val]
  (swap! audio-state assoc-in [:mixer k] val))

(defn- set-playback [k val]
  (swap! audio-state assoc-in [:playback k] val))

(defn- set-queue [val]
  (swap! audio-state update :queue
         #(merge (select-keys % [:source-type :source-path]) val)))

(defn wrap-volume [db-conn policy new-volume]
  (let [minimum (db/min-volume @db-conn)
        maximum (maximum-volume policy)]
    (int (min maximum (max minimum new-volume)))))

(defn set-volume!
  [{:keys [player db-conn playback-limits volume-lock]} volume]
  (locking volume-lock
    (mp/dispatch player
                 :mixer/set-volume
                 :level (wrap-volume db-conn playback-limits volume))))

(defn sleep-fade-step!
  [{:keys [player volume-lock]} volume stop?]
  (locking volume-lock
    (mp/dispatch player
                 :mixer/set-volume
                 :level (int (max 0 (min 100 volume))))
    (when stop?
      (mp/dispatch player :playback/stop))))

(defn adjust-volume!
  [{:keys [player db-conn playback-limits volume-lock]} delta]
  (locking volume-lock
    (let [current    (mp/get-volume player)
          new-volume (wrap-volume db-conn
                                  playback-limits
                                  (+ current delta))]
      (mp/dispatch player :mixer/set-volume :level new-volume))))

(defn play-one-shot!
  [{:keys [emitter one-shot-player playback-limits settings]}
   {:keys [item-path id]}]
  ;; this one-shot function creates a new player, plays the item, and then releases the player
  ;; we do this because we want to handle the events separate from the normal player
  (tap> [:one-shot-play item-path id :final-path (browse/canonicalize-path settings item-path)])
  (if-let [path (browse/canonicalize-path settings item-path)]
    (util/thread
      (try
        (let [unsubscribe_    (promise)
              handler         (fn [{:ol.vinyl/keys [event]}]
                                (try
                                  (case event
                                    :vlc/finished
                                    (async/put! emitter (player-event {:event :player/one-shot-finished :id id}))

                                    :vlc/error
                                    (do
                                      (log/error "one-shot received vlc error event")
                                      (async/put! emitter (player-event {:event :player/one-shot-finished :id id}))))

                                  (catch Exception e
                                    (log/error e "one-shot event error"))
                                  (finally (deliver unsubscribe_ true))))
              subscription-id (mp/subscribe! one-shot-player handler #{:vlc/finished :vlc/error})]

          (mp/dispatch one-shot-player
                       :mixer/set-volume
                       :level (maximum-volume playback-limits))
          (mp/dispatch one-shot-player :playback/clear-all)
          (mp/dispatch one-shot-player :playback/append :paths [path])
          (mp/dispatch one-shot-player :playback/play)
          (deref unsubscribe_)
          (mp/unsubscribe! one-shot-player subscription-id))
        (catch Throwable e
          (log/error e "one-shot player error"))))
    (throw (ex-info "Could not play. Requested path could not be found in media-dir" {:requested-path item-path
                                                                                      :media-dir      (browse/media-dir settings)}))))

(defn internal-event-handler [{:keys [emitter]} event]
  (let [event-name  (or (:ol.vinyl/event event) (:event event))
        emit-player (fn [k & {:as extra}]
                      (async/put! emitter (player-event (merge extra {:event k}))))]
    (try
      (condp = event-name
        #_#_:vlc/media-changed  (tap> event)
        :vlc/muted            (do
                                (set-mixer :muted? (:muted? event))
                                (emit-player :player/muted :muted? :muted? event))
        :vlc/volume-changed   (let [;; vlc sends volume in range 0.0 to 1.0, we convert it to 0-100
                                    ;; this is strange, because the volume setter expects 0-100
                                    new-volume (int (* 100 (:new-volume event)))]
                                (when (>= new-volume 0) ;; sometimes vlc sends -1 which we should just ignore
                                  (set-mixer :volume new-volume)
                                  (emit-player :player/volume-changed :volume new-volume)))
        :vlc/playing          (do
                                (set-playback :state :playing)
                                (emit-player :player/state-changed :state :playing))
        :vlc/paused           (do
                                (set-playback :state :paused)
                                (emit-player :player/state-changed :state :paused))
        :vlc/stopped          (do
                                (set-playback :state :stopped)
                                (emit-player :player/state-changed :state :stopped))
        :vlc/opening          (do (set-playback :state :opening)
                                  (emit-player :player/state-changed :state :opening))
        :vlc/finished         (do  (set-playback :state :finished)
                                   (emit-player :player/state-changed :state :finished))
        :vlc/position-changed (do
                                (set-playback :position (:new-position event))
                                (emit-player :player/position-changed :position (:new-position event)))
        :vlc/time-changed     (do
                                (set-playback :time (:new-time event))
                                (emit-player :player/time-changed :time (:new-time event)))
        :ol.vinyl.playback/repeat-changed (do
                                            (set-playback :repeat-mode (:mode-after event))
                                            (emit-player :player/repeat-changed :mode (:mode-after event)))
        :ol.vinyl.playback/shuffle-changed (do
                                             (set-playback :shuffle? (:shuffle? event))
                                             (emit-player :player/shuffle-changed :shuffle? (:shuffle? event)))
        :ol.vinyl.playback/current-track-changed (do
                                                   (set-playback :current-track (:current-track event))
                                                   (emit-player :player/current-track-changed :current-track (:current-track event)))
        :ol.vinyl.playback/queue-changed
        (do
          (set-queue (:after-queue event))
          (emit-player :player/queue-changed))

        nil)
      (catch Exception e
        (log/error e "internal event error")))))

(defn command-handler [{:keys [player] :as sys} {:keys [value] :as _event}]
  (try
    (let [{:keys [action]} value
          {:keys [config]} @audio-state
          d!               (fn [command-name & {:as payload}]
                             (mp/dispatch player command-name payload))]

      (condp = action
        :audio/clear            (do
                                  (clear-queue-source!)
                                  (d! :playback/clear-all))
        :audio/play-one-shot    (play-one-shot! sys value)
        :audio/play-path        (play-path! sys value)
        :audio/stop             (d! :playback/stop)
        :audio/play-pause       (if (mp/playing? player)
                                  (d! :playback/pause)
                                  (d! :playback/play))
        :audio/next             (d! :playback/next-track)
        :audio/prev             (d! :playback/previous-track)
        :audio/volume-up        (adjust-volume! sys (get-in config [:volume-up-step]))
        :audio/volume-down      (adjust-volume! sys (get-in config [:volume-down-step]))
        :audio/adjust-volume    (adjust-volume! sys (get-in value [:delta]))
        :audio/set-volume       (set-volume! sys (get-in value [:volume]))
        :audio/sleep-fade-step  (sleep-fade-step! sys
                                                  (:volume value)
                                                  (:stop? value))
        :audio/skip-time        (d! :playback/skip-time :delta-ms (get-in value [:milliseconds]))
        :audio/set-time         (d! :playback/set-time :time-ms (get-in value [:milliseconds]))
        :audio/set-position     (d! :playback/set-position :position (get-in value [:position]))
        :audio/set-pause        (d! :playback/set-pause :paused? (:paused? value))
        :audio/play             (d! :playback/play)
        :audio/pause            (d! :playback/pause)
        :audio/set-mute         (d! :playback/set-mute :muted? (:muted? value))
        :audio/toggle-mute      (d! :mixer/mute)
        :audio/play-queue-index (d! :playback/play-from :index (:item-index value))
        :audio/set-repeat       (d! :playback/set-repeat :mode (:mode value))
        :audio/set-shuffle      (d! :playback/set-shuffle :shuffle? (:shuffle? value))

        (do
          (log/error "Unknown audio command" action)
          nil)))
    (catch Throwable e
      (log/error e "audio command error"))))

(def audio-subscriber-id
  ::main-player)

(defn enforce-volume-limit! [player volume-lock snapshot]
  (locking volume-lock
    (let [actual-volume (mp/get-volume player)
          maximum       (get-in snapshot [:limits :audio/max-volume])]
      (when (and (number? actual-volume)
                 (> actual-volume maximum))
        (mp/dispatch player
                     :mixer/set-volume
                     :level (int maximum))))))

(defn new-player! [policy handler]
  (let [player (mp/create-player {:media-player-factory factory})]
    (mp/subscribe! player handler)
    (mp/dispatch player
                 :mixer/set-volume
                 :level (maximum-volume policy))
    player))

(defn- release-all-resources! [{:keys [player one-shot-player]}]
  (mp/release-player! player)
  (tap> [:info "Released main player"])
  (mp/release-player! one-shot-player))

(defn- audio-loop [{:keys [exit-ch internal-ch commands-ch] :as sys}]
  (async/go-loop []
    (async/alt!
    ;; NOTE - all functions called here must not throw exceptions
      exit-ch ([_]
               (release-all-resources! sys)
               (async/close! exit-ch)
               nil)
      internal-ch ([ev]
                   (internal-event-handler sys ev)
                   (recur))
      commands-ch ([ev]
                   (command-handler sys ev)
                   (recur)))))

(defn- init-audio! [{:keys [bus settings db-conn playback-limits]}]
  (reset! audio-state audio-init-state)
  (let [emitter     (async/chan (async/sliding-buffer 512))
        commands-ch (async/chan (async/sliding-buffer 512))
        internal-ch (async/chan (async/sliding-buffer 512))
        exit-ch     (async/chan)
        volume-lock (Object.)
        player      (new-player! playback-limits
                                 (fn [event]
                                   (try
                                     (async/put! internal-ch event)
                                     (catch Exception error
                                       (log/error error
                                                  "player put internal-ch error")))))
        sys         {:emitter         emitter
                     :settings        settings
                     :db-conn         db-conn
                     :playback-limits playback-limits
                     :subscriber-id   audio-subscriber-id
                     :commands-ch     commands-ch
                     :internal-ch     internal-ch
                     :exit-ch         exit-ch
                     :volume-lock     volume-lock
                     :player          player
                     :one-shot-player (mp/create-player
                                       {:media-player-factory factory})}]
    (playback-limits/subscribe! playback-limits
                                audio-subscriber-id
                                (partial enforce-volume-limit!
                                         player
                                         volume-lock))
    (ev/listen bus "/player/commands" commands-ch)
    (ev/emitize bus emitter)
    (assoc sys :audio-loop (audio-loop sys))))

(defn- halt-player!
  [{:keys [internal-ch exit-ch commands-ch emitter audio-loop
           playback-limits subscriber-id]}]
  (when (and playback-limits subscriber-id)
    (playback-limits/unsubscribe! playback-limits subscriber-id))
  (async/put! exit-ch true)
  (async/close! commands-ch)
  (async/close! internal-ch)
  (async/close! emitter)
  (async/close! audio-loop)
  (reset! audio-state audio-init-state))

(def AudioSystemComponent
  {::ds/start  (fn [{config ::ds/config}]
                 (init-audio! config))
   ::ds/stop   (fn [{instance ::ds/instance}]
                 (halt-player! instance))
   ::ds/config {:bus             (ds/ref [:fairy.box/components
                                          :fairy.box.bus/bus])
                :settings        (ds/ref [:fairy.box/components
                                          :fairy.box/settings])
                :db-conn         (ds/ref [:fairy.box/components
                                          :fairy.box.db/db])
                :playback-limits (ds/ref [:fairy.box/components
                                          :fairy.box.playback-limits/policy])}})
