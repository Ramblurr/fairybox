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

(def ^:private audio-init-state {:playback             {:state nil}
                                 :mixer                {:muted? nil
                                                        :volume nil}
                                 :queue                nil
                                 :card-playback        nil
                                 :active-card-playback nil
                                 :config               {:volume-up-step   5
                                                        :volume-down-step -5}})

(def ^:private card-playback-messages
  {:missing-media
   "Uh-oh. I know this card, but I cannot find what it should play."
   :unreadable-media
   "Uh-oh. I found what this card should play, but I am having trouble playing it."
   :unexpected
   "Uh-oh. I am having a problem, and I cannot play this card. Please get Daddy."})

(defonce audio-state (atom audio-init-state))

(defn mrl->title [mrl]
  (->
   (io/as-url mrl)
   .toURI
   fs/path
   fs/file-name
   fs/strip-ext))

(defn- play-now [{:keys [player] :as _sys} paths]
  (let [paths (some-> paths vec)]
    (if (seq paths)
      (do
        (tap> [:playing-now paths])
        (mp/dispatch player :playback/clear-all)
        (mp/dispatch player :playback/append :paths paths)
        (mp/dispatch player :playback/advance))
      (let [{:keys [playback queue card-playback]} @audio-state
            context (cond-> {:error          :audio/empty-playback-paths
                             :stage          :queue-dispatch
                             :reason         :empty-path-list
                             :paths          paths
                             :playback-state (:state playback)
                             :current-track  (:current-track playback)
                             :source-path    (:source-path queue)
                             :source-type    (:source-type queue)}
                      (:uid card-playback)
                      (assoc :uid (:uid card-playback)))]
        (log/error "Refusing to replace the playback queue with no paths"
                   context)
        (throw (ex-info "Playback requires at least one path" context))))))

(defn- expand-path [{:keys [player] :as _sys} path]
  (let [result @(mp/parse-meta player [path])]
    (when (instance? Throwable result)
      (throw (ex-info "Failed to parse media tracks"
                      {:error     :audio/media-read-failed
                       :item-path path}
                      result)))
    (let [tracks (vec result)]
      (if (every? #(= :media-parsed-status/done (:parse-status %)) tracks)
        (filterv (comp seq :audio-tracks) tracks)
        (throw (ex-info "Failed to parse media tracks"
                        {:error        :audio/media-read-failed
                         :item-path    path
                         :parse-result tracks}))))))

(defn- non-blank-string [value]
  (some-> value str str/trim not-empty))

(defn- track->metadata [{:keys [meta mrl]}]
  {:title  (or (non-blank-string (:meta/title meta))
               (str (mrl->title mrl)))
   :album  (non-blank-string (:meta/album meta))
   :artist (non-blank-string (:meta/artist meta))})

(def ^:private track-number-pattern
  #"\s*0*(\d+)(?:\s*/\s*\d+)?\s*")

(defn- normalized-track-number [value]
  (when-let [[_ number] (some->> value
                                 str
                                 (re-matches track-number-pattern))]
    (parse-long number)))

(defn- track-number [{:keys [meta]}]
  (normalized-track-number (:meta/track-number meta)))

(defn announcement-for-track [track]
  (tts/tts-track-speech
   (assoc (track->metadata track)
          :track-number (track-number track))
   {}))

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

(defn- tracks-with-announcements [sys tracks]
  (let [tracks        (sort-by #(or (track-number %) Long/MAX_VALUE)
                               tracks)
        tts-system    (assoc sys
                             :tts-cache-dir
                             (tts/tts-cache-dir (:settings sys)))
        announcements (mapv #(tts/tts tts-system
                                      (announcement-for-track %))
                            tracks)]
    (vec (interleave announcements tracks))))

(defn- set-queue-source! [settings path]
  (swap! audio-state update :queue merge
         {:source-type (if (= :playlist (browse/playable-type settings path))
                         :playlist
                         :folder)
          :source-path (or (browse/media-relative-path settings path) path)}))

(defn- clear-queue-source! []
  (swap! audio-state update :queue dissoc :source-type :source-path))

(defn- play-source! [sys path paths]
  (swap! audio-state assoc :active-card-playback nil)
  (set-queue-source! (:settings sys) path)
  (play-now sys paths))

(defn- announced-playback-paths [sys path tracks context]
  (try
    (tracks-with-announcements sys tracks)
    (catch Exception error
      (log/error error
                 "Error generating track announcements; playing normally"
                 (assoc context
                        :stage :announcement-generation
                        :reason :announcement-generation-failed))
      [path])))

(defn- log-empty-media-expansion! [context]
  (log/error "Media expansion produced no playable tracks"
             (assoc context
                    :stage :media-expansion
                    :reason :empty-track-list)))

(defn- announce-then-play [sys path context]
  (util/thread
    (let [{:keys [tracks error]}
          (try
            {:tracks (expand-path sys path)}
            (catch Exception error
              {:error error}))]
      (cond
        error
        (do
          (log/error error
                     "Error expanding track announcements; playing normally"
                     (assoc context
                            :stage :media-expansion
                            :reason :media-expansion-failed))
          (play-source! sys path [path]))

        (seq tracks)
        (play-source! sys
                      path
                      (announced-playback-paths sys
                                                path
                                                tracks
                                                context))

        :else
        (log-empty-media-expansion! context)))))

(defn- invalidate-card-playback! []
  (locking audio-state
    (swap! audio-state assoc :card-playback nil)))

(defn- clear-active-card-playback! []
  (locking audio-state
    (swap! audio-state assoc :active-card-playback nil)))

(defn- begin-card-playback! [uid request-id]
  (locking audio-state
    (swap! audio-state assoc
           :card-playback {:request-id        request-id
                           :uid               uid
                           :started?          false
                           :problem-reported? false})))

(defn- current-card-request? [request-id]
  (= request-id (get-in @audio-state [:card-playback :request-id])))

(defn- claim-card-problem! [request-id require-started?]
  (locking audio-state
    (let [{:keys [problem-reported? started?] :as request}
          (:card-playback @audio-state)]
      (when (and (= request-id (:request-id request))
                 (not problem-reported?)
                 (or (not require-started?) started?))
        (swap! audio-state assoc-in
               [:card-playback :problem-reported?]
               true)
        request))))

(defn- report-card-playback-problem!
  [{:keys [emitter]} request-id problem require-started?]
  (when-let [{:keys [uid]} (claim-card-problem! request-id
                                                require-started?)]
    (log/warn "Reporting RFID playback problem"
              {:uid uid :problem problem})
    (async/put! emitter
                {:path  "/tts/commands"
                 :value {:action              :tts/speak
                         :feedback/type       :card-playback-problem
                         :request-id          request-id
                         :uid                 uid
                         :problem             problem
                         :audio/play-one-shot true
                         :text                (get card-playback-messages
                                                   problem
                                                   (:unexpected
                                                    card-playback-messages))}})))

(defn- report-current-card-playback-problem!
  [sys problem require-started?]
  (when-let [request-id (get-in @audio-state
                                [:card-playback :request-id])]
    (report-card-playback-problem! sys
                                   request-id
                                   problem
                                   require-started?)))

(defn- playback-source [settings item-path]
  (try
    (let [resolved-path (when (some? item-path)
                          (browse/canonicalize-path settings item-path))
          file          (some-> resolved-path io/file)]
      (cond
        (nil? item-path)
        {:resolved-path nil :reason :missing-source}

        (nil? resolved-path)
        {:resolved-path nil :reason :invalid-source-path}

        (not (.exists file))
        {:resolved-path resolved-path :reason :missing-source}

        (or (not (.canRead file))
            (and (.isDirectory file) (not (.canExecute file))))
        {:resolved-path resolved-path :reason :unreadable-source}

        :else
        (if-let [playable-type (browse/playable-type settings resolved-path)]
          {:resolved-path resolved-path :playable-type playable-type}
          {:resolved-path resolved-path
           :reason        (if (.isDirectory file)
                            :empty-source
                            :unsupported-source)})))
    (catch SecurityException error
      {:resolved-path    nil
       :reason           :unreadable-source
       :validation-error error})
    (catch Exception error
      {:resolved-path    nil
       :reason           :source-validation-failed
       :validation-error error})))

(defn- playback-request-context [item-path resolved-path uid]
  (cond-> {:requested-path item-path
           :resolved-path  (some-> resolved-path str)}
    (some? uid) (assoc :uid uid)))

(defn- source-reason->card-problem [reason]
  (case reason
    (:missing-source
     :invalid-source-path
     :empty-source
     :unsupported-source) :missing-media
    :unreadable-media))

(defn- play-prepared-card! [sys request-id path tracks]
  (locking audio-state
    (when (current-card-request? request-id)
      (let [request (:card-playback @audio-state)]
        (swap! audio-state
               (fn [state]
                 (-> state
                     (assoc-in [:card-playback :started?] true)
                     (assoc :active-card-playback
                            (select-keys request [:request-id :uid])))))
        (set-queue-source! (:settings sys) path)
        (play-now sys tracks)
        true))))

(defn- prepare-card-playback!
  [sys request-id path context tts-announce?]
  (util/thread
    (try
      (if-not tts-announce?
        (play-prepared-card! sys request-id path [path])
        (let [{:keys [tracks error]}
              (try
                {:tracks (expand-path sys path)}
                (catch Exception error
                  {:error error}))]
          (cond
            error
            (do
              (log/error error
                         "Error expanding track announcements; playing normally"
                         (assoc context
                                :stage :media-expansion
                                :reason :media-expansion-failed))
              (play-prepared-card! sys request-id path [path]))

            (seq tracks)
            (play-prepared-card!
             sys
             request-id
             path
             (announced-playback-paths sys path tracks context))

            :else
            (do
              (log-empty-media-expansion! context)
              (report-card-playback-problem! sys
                                             request-id
                                             :unreadable-media
                                             false)))))
      (catch Throwable error
        (log/error error
                   "Unable to prepare RFID media playback"
                   (assoc context
                          :stage :playback-preparation
                          :reason :playback-preparation-failed))
        (report-card-playback-problem! sys
                                       request-id
                                       :unexpected
                                       false)))))

(defn play-path!
  [{:keys [settings] :as sys}
   {:keys [item-path uid request-id] :as _value}]
  (let [{:keys [resolved-path playable-type reason validation-error]}
        (playback-source settings item-path)
        request-id (when (some? uid)
                     (or request-id (random-uuid)))
        context (playback-request-context item-path resolved-path uid)
        tts-result (when-not reason
                     (try
                       {:announce? (db/announce-path? sys resolved-path)}
                       (catch Exception error
                         {:error error})))
        tts-announce? (:announce? tts-result)]
    (if request-id
      (begin-card-playback! uid request-id)
      (invalidate-card-playback!))
    (log/info (if request-id
                "Attempting RFID media playback"
                "Attempting media playback")
              (assoc context
                     :playable-type playable-type
                     :tts-announce? tts-announce?))
    (cond
      reason
      (let [failure-context (assoc context
                                   :stage :source-validation
                                   :reason reason)]
        (if validation-error
          (log/error validation-error
                     "Playback source validation failed"
                     failure-context)
          (log/error "Playback source validation failed" failure-context))
        (when request-id
          (report-card-playback-problem!
           sys
           request-id
           (source-reason->card-problem reason)
           false))
        nil)

      (:error tts-result)
      (let [failure-context (assoc context
                                   :stage :playback-preparation
                                   :reason :announcement-policy-failed)]
        (log/error (:error tts-result)
                   "Unable to resolve track announcement policy"
                   failure-context)
        (when request-id
          (report-card-playback-problem! sys
                                         request-id
                                         :unexpected
                                         false))
        nil)

      request-id
      (prepare-card-playback! sys
                              request-id
                              resolved-path
                              context
                              tts-announce?)

      tts-announce?
      (announce-then-play sys resolved-path context)

      :else
      (play-source! sys resolved-path [resolved-path]))))

(defn maximum-volume [policy]
  (int (playback-limits/current-limit policy :audio/max-volume)))

(def ^:private one-shot-volume-limit-keys
  {:startup-sound  :audio/startup-volume
   :shutdown-sound :audio/shutdown-volume})

(defn one-shot-volume [policy id]
  (int (playback-limits/current-limit
        policy
        (get one-shot-volume-limit-keys id :audio/max-volume))))

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
  (let [path   (browse/canonicalize-path settings item-path)
        volume (one-shot-volume playback-limits id)]
    (tap> [:one-shot-play item-path id :final-path path])
    (log/info "Attempting one-shot media playback"
              {:id             id
               :requested-path item-path
               :resolved-path  (some-> path str)
               :volume         volume})
    (if path
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
                         :level volume)
            (mp/dispatch one-shot-player :playback/clear-all)
            (mp/dispatch one-shot-player :playback/append :paths [path])
            (mp/dispatch one-shot-player :playback/play)
            (deref unsubscribe_)
            (mp/unsubscribe! one-shot-player subscription-id))
          (catch Throwable e
            (log/error e "one-shot player error"))))
      (throw (ex-info "Could not play. Requested path could not be found in media-dir"
                      {:requested-path item-path
                       :media-dir      (browse/media-dir settings)})))))

(defn internal-event-handler [{:keys [emitter] :as sys} event]
  (let [event-name    (or (:ol.vinyl/event event) (:event event))
        event-request (if (contains? event ::active-card-playback)
                        (::active-card-playback event)
                        (:active-card-playback @audio-state))
        emit-player   (fn [event-type & {:as extra}]
                        (let [request (when (= :player/state-changed
                                               event-type)
                                        event-request)]
                          (async/put! emitter
                                      (player-event
                                       (merge request
                                              extra
                                              {:event event-type})))))]
    (try
      (condp = event-name
        #_#_:vlc/media-changed (tap> event)
        :vlc/error
        (do
          (let [{:keys [playback queue]} @audio-state
                context                  (cond-> {:stage          :native-playback
                                                  :reason         :vlc-error
                                                  :playback-state (:state playback)
                                                  :current-track  (:current-track playback)
                                                  :source-path    (:source-path queue)
                                                  :source-type    (:source-type queue)}
                                           (:uid event-request)
                                           (assoc :uid (:uid event-request)))]
            (log/error "Main player received VLC error" context))
          (report-current-card-playback-problem! sys
                                                 :unreadable-media
                                                 true))
        :vlc/muted
        (do
          (set-mixer :muted? (:muted? event))
          (emit-player :player/muted :muted? :muted? event))
        :vlc/volume-changed
        (let [;; vlc sends volume in range 0.0 to 1.0, we convert it to 0-100
              ;; this is strange, because the volume setter expects 0-100
              new-volume (int (* 100 (:new-volume event)))]
          (when (>= new-volume 0)
            ;; sometimes vlc sends -1 which we should just ignore
            (set-mixer :volume new-volume)
            (emit-player :player/volume-changed :volume new-volume)))
        :vlc/playing
        (do
          (set-playback :state :playing)
          (emit-player :player/state-changed :state :playing))
        :vlc/paused
        (do
          (set-playback :state :paused)
          (emit-player :player/state-changed :state :paused))
        :vlc/stopped
        (do
          (set-playback :state :stopped)
          (emit-player :player/state-changed :state :stopped))
        :vlc/opening
        (do
          (set-playback :state :opening)
          (emit-player :player/state-changed :state :opening))
        :vlc/finished
        (do
          (set-playback :state :finished)
          (emit-player :player/state-changed :state :finished))
        :vlc/position-changed
        (do
          (set-playback :position (:new-position event))
          (emit-player :player/position-changed :position (:new-position event)))
        :vlc/time-changed
        (do
          (set-playback :time (:new-time event))
          (emit-player :player/time-changed :time (:new-time event)))
        :ol.vinyl.playback/repeat-changed
        (do
          (set-playback :repeat-mode (:mode-after event))
          (emit-player :player/repeat-changed :mode (:mode-after event)))
        :ol.vinyl.playback/shuffle-changed
        (do
          (set-playback :shuffle? (:shuffle? event))
          (emit-player :player/shuffle-changed :shuffle? (:shuffle? event)))
        :ol.vinyl.playback/current-track-changed
        (do
          (set-playback :current-track (:current-track event))
          (emit-player :player/current-track-changed
                       :current-track (:current-track event)))
        :ol.vinyl.playback/queue-changed
        (do
          (set-queue (:after-queue event))
          (emit-player :player/queue-changed))

        nil)
      (catch Exception error
        (log/error error "internal event error")))))

(defn command-handler [{:keys [player] :as sys} {:keys [value] :as _event}]
  (try
    (let [{:keys [action]} value
          {:keys [config]} @audio-state
          d!               (fn [command-name & {:as payload}]
                             (mp/dispatch player command-name payload))]

      (condp = action
        :audio/clear            (do
                                  (invalidate-card-playback!)
                                  (clear-active-card-playback!)
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
    (catch Throwable error
      (log/error error "audio command error"))))

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

(defn- media-player-factory! []
  (or (mp/factory)
      (let [started-at-nanos (System/nanoTime)
            factory          (mp/init!)
            elapsed-ms       (quot (- (System/nanoTime) started-at-nanos)
                                   1000000)]
        (log/info "Initialized VLC media player factory"
                  {:elapsed-ms elapsed-ms})
        factory)))

(defn new-player! [factory policy handler]
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
  (let [factory     (media-player-factory!)
        emitter     (async/chan (async/sliding-buffer 512))
        commands-ch (async/chan (async/sliding-buffer 512))
        internal-ch (async/chan (async/sliding-buffer 512))
        exit-ch     (async/chan)
        volume-lock (Object.)
        player      (new-player! factory
                                 playback-limits
                                 (fn [event]
                                   (try
                                     (async/put!
                                      internal-ch
                                      (assoc event
                                             ::active-card-playback
                                             (:active-card-playback
                                              @audio-state)))
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
