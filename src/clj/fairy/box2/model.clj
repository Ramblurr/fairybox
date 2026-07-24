(ns fairy.box2.model
  "Fairybox 2 application statechart and its documented vocabulary.

  This namespace specifies the broad application topology and the executable
  card-request walking skeleton. Qualified effect annotations document intended
  external work across the full chart; executable sends currently cover card
  preparation and player activation. The chart remains the authority for state
  hierarchy and legal transitions; [[vocabulary]] documents identifier meaning
  and payload shape."
  (:require
   [clojure.set :as set]
   [com.fulcrologic.statecharts :as sc]
   [com.fulcrologic.statecharts.chart :as chart]
   [com.fulcrologic.statecharts.data-model.operations :as ops]
   [com.fulcrologic.statecharts.elements :refer [In Send data-model final parallel script state transition]]
   [malli.core :as m]))

(def payload-schema-registry
  "Reusable Malli schemas for event and effect payloads."
  {::button-id        [:enum
                       :audio/next
                       :audio/play-pause
                       :audio/previous
                       :audio/volume-down
                       :audio/volume-up
                       :system/sleep]
   ::changed-paths    [:set [:vector :keyword]]
   ::error            [:map
                       [:category {:optional true} :keyword]
                       [:message {:optional true} :string]]
   ::operation        [:enum
                       :system/poweroff
                       :system/reboot
                       :system/restart-fairybox]
   ::playback-context [:map
                       [:presence-epoch [:int {:min 1}]]
                       [:request-id :uuid]
                       [:uid [:string {:min 1}]]]
   ::player-state     [:enum
                       :error
                       :finished
                       :opening
                       :paused
                       :playing
                       :stopped]
   ::request-id       :uuid
   ::settings         :map
   ::timer-token      [:or :keyword :string :uuid]})

(def effect-send-type
  "Statecharts send type used for immutable Box2 effect commands."
  ::effect)

(defn- payload [schema]
  schema)

(defn- effect [effect-type content]
  (Send {:content content
         :event   effect-type
         :type    effect-send-type}))

(def initial-data
  "Initial non-secret orchestration data for the Box2 chart.

  The complete `db.edn` value and credentials never enter this data model. The
  database adapter projects only operational settings into chart data. Present
  observations attach only the safe linked-media projection needed by the chart."
  {:audio       {:active-request   nil
                 :one-shot         nil
                 :pending-request  nil
                 :playback-context nil
                 :playback-state   :stopped
                 :playback-time-ms 0}
   :interaction {:feedback       nil
                 :identification nil}
   :power       {:auto-shutdown-token nil
                 :sleep-token         nil}
   :rfid        {:presence-epoch 0
                 :present-uid    nil}
   :settings    {:revision 0
                 :values   {}}
   :system      {:operation nil}})

(def states
  {:audio.st/available    {:description "The audio resources are usable and their independent regions are active."}
   :audio.st/card-request {:description "The lifecycle of selecting and starting media for the current card."}
   :audio.st/main-player  {:description "The observed lifecycle of the long-lived queue player."}
   :audio.st/one-shot     {:description "The independently operating channel for short sounds and speech."}
   :audio.st/unavailable  {:description "The audio resources cannot currently accept playback work."}

   :auto-shutdown.st/armed            {:description "The device is idle and a correlated auto-shutdown deadline is active."}
   :auto-shutdown.st/disabled         {:description "Automatic shutdown is disabled by current settings."}
   :auto-shutdown.st/requested        {:description "The auto-shutdown deadline fired and system cooling was requested."}
   :auto-shutdown.st/waiting-for-idle {:description "Automatic shutdown is enabled but the device is not yet durably idle."}

   :card-request.st/active               {:description "The current card owns the installed queue and playback has started."}
   :card-request.st/failed               {:description "The current card request failed and its child-facing problem was reported."}
   :card-request.st/idle                 {:description "No card request is being resolved, prepared, or activated."}
   :card-request.st/preparing            {:description "Media expansion and optional track-announcement generation are running."}
   :card-request.st/suspended            {:description "Card-owned playback was paused after removal and may resume on return."}
   :card-request.st/unlinked             {:description "The presented RFID UID has no linked media path in `db.edn`."}
   :card-request.st/waiting-for-playback {:description "The prepared queue was dispatched and awaits a correlated opening event."}

   :cooling.st/releasing            {:description "The shutdown sound has ended and application resources are being released."}
   :cooling.st/waiting-for-one-shot {:description "Main playback is stopped while the shutdown one-shot finishes."}

   :feedback.st/idle             {:description "No card-related LED or speech feedback owns the interaction surface."}
   :feedback.st/known-card       {:description "A linked card was recognized and acknowledgement feedback is visible."}
   :feedback.st/playback-started {:description "The current card produced its first correlated playback opening."}
   :feedback.st/preparing        {:description "Preparation exceeded the acknowledgement interval and waiting feedback is visible."}
   :feedback.st/problem          {:description "The current eligible card request has a preparation or playback problem."}
   :feedback.st/unknown-card     {:description "The presented card is not linked and unknown-card feedback is visible."}

   :identification.st/failed           {:description "Metadata reading failed for the current card-identification request."}
   :identification.st/idle             {:description "Card-identification mode is waiting for a card."}
   :identification.st/reading-metadata {:description "The audio adapter is reading metadata for the resolved media path."}
   :identification.st/speaking         {:description "One-shot speech is describing the identified card contents."}

   :interaction.st/card-identification {:description "Buttons and RFID input are being used to identify card contents, not play them."}
   :interaction.st/normal              {:description "RFID cards and buttons control ordinary playback and card feedback."}

   :lifecycle.st/cooling    {:description "The application is stopping playback, presenting shutdown feedback, and releasing resources."}
   :lifecycle.st/ready      {:description "The device accepts normal user interaction."}
   :lifecycle.st/warming-up {:description "Components are available and startup feedback is still running."}

   :one-shot.st/failed  {:description "The current one-shot request failed."}
   :one-shot.st/idle    {:description "The one-shot player has no active sound."}
   :one-shot.st/playing {:description "The one-shot player owns one correlated sound or speech request."}

   :player.st/error   {:description "The long-lived queue player reported an error."}
   :player.st/opening {:description "The long-lived player is opening media from its installed queue."}
   :player.st/paused  {:description "The long-lived player retains its queue at a paused position."}
   :player.st/playing {:description "The long-lived player is actively playing its queue."}
   :player.st/stopped {:description "The long-lived player is not currently playing or opening media."}

   :region.st/lifecycle {:description "The exclusive application lifecycle branch inside the active system."}

   :rfid.st/absent  {:description "No RFID card is currently present at the reader."}
   :rfid.st/faulted {:description "The RFID adapter reported a hardware or polling failure."}
   :rfid.st/present {:description "One RFID UID is currently present at the reader."}

   :settings.st/current  {:description "The chart holds the latest non-secret operational settings projection."}
   :settings.st/failed   {:description "The most recent settings persistence request failed."}
   :settings.st/updating {:description "A settings update is being persisted to the unchanged database format."}

   :power.st/auto-shutdown {:description "The automatic-shutdown policy lifecycle."}
   :power.st/sleep         {:description "The user-controlled sleep countdown and fade lifecycle."}

   :sleep.st/counting-down {:description "A user-selected sleep duration is counting down before fade begins."}
   :sleep.st/fading        {:description "Main playback volume is being reduced over the sleep fade interval."}
   :sleep.st/inactive      {:description "No manual sleep countdown or fade is active."}
   :sleep.st/shutdown-wait {:description "The sleep fade finished and an optional shutdown delay is active."}

   :subsystem.st/audio       {:description "The application-level audio lifecycle and its owned parallel regions."}
   :subsystem.st/interaction {:description "The mutually exclusive normal and card-identification interaction modes."}
   :subsystem.st/power       {:description "Independent auto-shutdown and manual sleep policies."}
   :subsystem.st/rfid        {:description "The observed card-presence and reader-health lifecycle."}
   :subsystem.st/settings    {:description "The operational settings projection and persistence lifecycle."}

   :system.st/active   {:description "The parallel application subsystems are running."}
   :system.st/failed   {:description "Startup or an unrecoverable application failure prevented operation."}
   :system.st/starting {:description "Donut components, including the compatible database adapter, are starting."}
   :system.st/stopped  {:description "Resources are released and no more application events are accepted."}})

(def events {:audio.ev/faulted   {:description "The long-lived audio resources became unavailable."
                                  :payload     (payload [:map [:error ::error]])}
             :audio.ev/recovered {:description "The audio resources were recreated and can accept work."}

             :button.ev/held    {:description "A supported physical or synthetic button was held."
                                 :payload     (payload [:map [:button-id ::button-id]])}
             :button.ev/pressed {:description "A supported physical or synthetic button was pressed once."
                                 :payload     (payload [:map [:button-id ::button-id]])}

             :interaction.ev/problem-reported {:description "An eligible card request needs child-facing problem feedback."
                                               :payload     (payload
                                                             [:map
                                                              [:category :keyword]
                                                              [:request-id ::request-id]])}

             :media.ev/preparation-failed {:description "A correlated media-preparation job failed."
                                           :payload     (payload
                                                         [:map
                                                          [:error ::error]
                                                          [:presence-epoch [:int {:min 1}]]
                                                          [:request-id ::request-id]])}
             :media.ev/prepared           {:description "A correlated media-preparation job produced playable paths."
                                           :payload     (payload
                                                         [:map
                                                          [:paths [:vector [:string {:min 1}]]]
                                                          [:presence-epoch [:int {:min 1}]]
                                                          [:request-id ::request-id]])}

             :metadata.ev/read-failed   {:description "Metadata reading failed for a card-identification request."
                                         :payload     (payload
                                                       [:map
                                                        [:error ::error]
                                                        [:request-id ::request-id]
                                                        [:uid :string]])}
             :metadata.ev/read-finished {:description "Metadata reading completed for a card-identification request."
                                         :payload     (payload [:map
                                                                [:metadata [:vector :map]]
                                                                [:request-id ::request-id]
                                                                [:uid :string]])}

             :one-shot.ev/completed {:description "The correlated one-shot sound reached its normal end."
                                     :payload     (payload
                                                   [:map
                                                    [:purpose :keyword]
                                                    [:request-id ::request-id]])}
             :one-shot.ev/failed    {:description "The correlated one-shot sound failed or could not start."
                                     :payload     (payload
                                                   [:map
                                                    [:error ::error]
                                                    [:purpose :keyword]
                                                    [:request-id ::request-id]])}
             :one-shot.ev/requested {:description "Application behavior requested one short sound or speech item."
                                     :payload     (payload
                                                   [:map
                                                    [:item-path {:optional true} [:string {:min 1}]]
                                                    [:purpose :keyword]
                                                    [:request-id ::request-id]
                                                    [:speech {:optional true} :any]])}
             :one-shot.ev/started   {:description "The one-shot adapter accepted the correlated request."
                                     :payload     (payload
                                                   [:map
                                                    [:purpose :keyword]
                                                    [:request-id ::request-id]])}

             :player.ev/state-changed  {:description "Vinyl observed a state change for a concrete playback context."
                                        :payload     (payload
                                                      [:map
                                                       [:playback-context {:optional true} ::playback-context]
                                                       [:state ::player-state]])}
             :player.ev/stop-requested {:description "User or system behavior requested terminal main-player stop."}
             :player.ev/time-changed   {:description "Vinyl observed playback time for a concrete playback context."
                                        :payload     (payload
                                                      [:map
                                                       [:playback-context {:optional true} ::playback-context]
                                                       [:time-ms [:int {:min 0}]]])}

             :rfid.ev/presence-observed {:description "The RFID adapter reported a changed card-presence level."
                                         :payload     (payload
                                                       [:multi {:dispatch :status}
                                                        [:absent
                                                         [:map
                                                          [:presence-epoch [:int {:min 0}]]
                                                          [:status [:enum :absent]]]]
                                                        [:present
                                                         [:map
                                                          [:item-path {:optional true} [:string {:min 1}]]
                                                          [:presence-epoch [:int {:min 1}]]
                                                          [:request-id ::request-id]
                                                          [:status [:enum :present]]
                                                          [:uid [:string {:min 1}]]]]])}
             :rfid.ev/faulted           {:description "The RFID adapter reported a hardware or polling failure."
                                         :payload     (payload [:map [:error ::error]])}
             :rfid.ev/recovered         {:description "The RFID adapter recovered after a fault."}

             :settings.ev/changed          {:description "The database watch emitted a newer non-secret settings projection."
                                            :payload     (payload
                                                          [:map
                                                           [:changed-paths ::changed-paths]
                                                           [:revision [:int {:min 1}]]
                                                           [:settings ::settings]])}
             :settings.ev/update-failed    {:description "A correlated settings persistence request failed."
                                            :payload     (payload
                                                          [:map
                                                           [:error ::error]
                                                           [:request-id ::request-id]])}
             :settings.ev/update-requested {:description "A UI or adapter requested a settings update."
                                            :payload     (payload
                                                          [:map
                                                           [:patch :map]
                                                           [:request-id ::request-id]])}

             :sleep.ev/cancelled {:description "The active manual sleep lifecycle was cancelled."}
             :sleep.ev/requested {:description "A user requested a manual sleep countdown."
                                  :payload     (payload
                                                [:map
                                                 [:duration-minutes [:int {:min 1}]]
                                                 [:timer-token ::timer-token]])}

             :system.ev/cooling-requested  {:description "A user or power policy requested controlled system shutdown."
                                            :payload     (payload [:map [:operation ::operation]])}
             :system.ev/fatal-error        {:description "An unrecoverable application error requires shutdown."
                                            :payload     (payload [:map [:error ::error]])}
             :system.ev/initialized        {:description "Required Box2 components and the compatible database are ready."
                                            :payload     (payload
                                                          [:map
                                                           [:settings-revision [:int {:min 0}]]
                                                           [:settings ::settings]])}
             :system.ev/resources-released {:description "Every component required for controlled shutdown has released its resources."}
             :system.ev/start-failed       {:description "A required Box2 component failed during startup."
                                            :payload     (payload [:map [:error ::error]])}

             :timer.ev/auto-shutdown-fired  {:description "The correlated automatic-shutdown deadline elapsed."
                                             :payload     (payload [:map [:timer-token ::timer-token]])}
             :timer.ev/card-feedback-fired  {:description "The correlated preparation-feedback delay elapsed."
                                             :payload     (payload
                                                           [:map
                                                            [:request-id ::request-id]
                                                            [:timer-token ::timer-token]])}
             :timer.ev/sleep-fade-fired     {:description "The correlated manual sleep countdown reached its fade phase."
                                             :payload     (payload [:map [:timer-token ::timer-token]])}
             :timer.ev/sleep-shutdown-fired {:description "The correlated post-fade shutdown delay elapsed."
                                             :payload     (payload [:map [:timer-token ::timer-token]])}})

(def effects {:database.fx/update-settings {:description "Persist a settings patch while preserving every unrelated database key."
                                            :payload     (payload
                                                          [:map
                                                           [:patch :map]
                                                           [:request-id ::request-id]])}

              :led.fx/show-card-known       {:description "Present the linked-card acknowledgement pattern."}
              :led.fx/show-card-problem     {:description "Present child-facing failure feedback for the current card."}
              :led.fx/show-card-unknown     {:description "Present child-facing feedback for an unlinked card."}
              :led.fx/show-cooling          {:description "Transfer LED ownership to the controlled cooling lifecycle."}
              :led.fx/show-identification   {:description "Present the card-identification mode pattern."}
              :led.fx/show-playback-started {:description "Present the first-opening acknowledgement for the current card."}
              :led.fx/show-preparing        {:description "Present feedback for preparation that outlasted acknowledgement."}
              :led.fx/show-ready            {:description "Present the normal ready-state LED baseline."}
              :led.fx/show-system-problem   {:description "Present an unrecoverable system problem."}
              :led.fx/show-warming          {:description "Present the startup LED state."}
              :led.fx/turn-off              {:description "Turn off all LEDs after controlled resource release."}

              :media.fx/cancel-preparation {:description "Request cancellation of the state-owned preparation job."
                                            :payload     (payload
                                                          [:map
                                                           [:presence-epoch [:int {:min 1}]]
                                                           [:request-id ::request-id]])}
              :media.fx/prepare            {:description "Prepare playable paths without blocking the serialized chart runtime."
                                            :payload     (payload
                                                          [:map
                                                           [:announce-tracks? :boolean]
                                                           [:item-path [:string {:min 1}]]
                                                           [:presence-epoch [:int {:min 1}]]
                                                           [:request-id ::request-id]
                                                           [:uid :string]])}

              :metadata.fx/read {:description "Read media metadata asynchronously for card-identification speech."
                                 :payload     (payload
                                               [:map
                                                [:item-path [:string {:min 1}]]
                                                [:request-id ::request-id]
                                                [:uid :string]])}

              :one-shot.fx/play {:description "Start or replace the one-shot channel with a correlated request."
                                 :payload     (payload
                                               [:map
                                                [:item-path {:optional true} [:string {:min 1}]]
                                                [:purpose :keyword]
                                                [:request-id ::request-id]
                                                [:speech {:optional true} :any]])}
              :one-shot.fx/stop {:description "Stop the currently owned one-shot request if it is still active."
                                 :payload     (payload [:map [:request-id ::request-id]])}

              :player.fx/adjust-volume {:description "Adjust main-player volume within the current operational limit."
                                        :payload     (payload [:map [:delta :int]])}
              :player.fx/next          {:description "Advance the long-lived player to its next queue item."}
              :player.fx/pause         {:description "Pause the long-lived player without discarding its queue."}
              :player.fx/play-queue    {:description "Replace the queue and start it with immutable playback context."
                                        :payload     (payload
                                                      [:map
                                                       [:paths [:vector [:string {:min 1}]]]
                                                       [:playback-context ::playback-context]])}
              :player.fx/previous      {:description "Move the long-lived player to its previous queue item."}
              :player.fx/resume        {:description "Resume the retained long-lived player queue."}
              :player.fx/stop          {:description "Stop main playback and invalidate pending preparation authority."}
              :player.fx/toggle        {:description "Toggle the long-lived player between playing and paused."}

              :runtime.fx/release-resources {:description "Release Box2 component resources outside the chart processing thread."
                                             :payload     (payload [:map [:operation ::operation]])}

              :system.fx/execute-operation {:description "Execute the accepted host poweroff, reboot, or application restart."
                                            :payload     (payload [:map [:operation ::operation]])}
              :system.fx/request-operation {:description "Request controlled cooling for a power policy decision."
                                            :payload     (payload [:map [:operation ::operation]])}

              :timer.fx/cancel-all              {:description "Cancel every application-owned timer during cooling."}
              :timer.fx/cancel-auto-shutdown    {:description "Cancel the current automatic-shutdown deadline."}
              :timer.fx/cancel-card-feedback    {:description "Cancel the current delayed card-feedback timer."}
              :timer.fx/cancel-sleep            {:description "Cancel the current manual sleep countdown or shutdown delay."}
              :timer.fx/schedule-auto-shutdown  {:description "Schedule a correlated automatic-shutdown deadline."
                                                 :payload     (payload
                                                               [:map
                                                                [:duration-minutes [:int {:min 1}]]
                                                                [:timer-token ::timer-token]])}
              :timer.fx/schedule-card-feedback  {:description "Schedule correlated preparation feedback."
                                                 :payload     (payload
                                                               [:map
                                                                [:delay-ms [:int {:min 0}]]
                                                                [:request-id ::request-id]
                                                                [:timer-token ::timer-token]])}
              :timer.fx/schedule-sleep-fade     {:description "Schedule the fade boundary for a manual sleep request."
                                                 :payload     (payload
                                                               [:map
                                                                [:duration-minutes [:int {:min 1}]]
                                                                [:timer-token ::timer-token]])}
              :timer.fx/schedule-sleep-shutdown {:description "Schedule optional system shutdown after the sleep fade."
                                                 :payload     (payload
                                                               [:map
                                                                [:delay-minutes [:int {:min 0}]]
                                                                [:timer-token ::timer-token]])}

              :tts.fx/speak {:description "Synthesize speech asynchronously and submit it to the selected audio channel."
                             :payload     (payload
                                           [:map
                                            [:one-shot? :boolean]
                                            [:speech :any]])}})
(def vocabulary
  "Plain-English definitions and payload schemas for the Box2 chart.

  Payload schemas describe the `:data` carried by an event or effect, not its
  runtime envelope. State hierarchy and transitions are intentionally absent;
  inspect [[application-chart]] for those relationships."
  {:states states :events events :effects effects})

(defn- with-schema-doc [schema description]
  (if (vector? schema)
    (let [[schema-type & children] schema
          [properties children]    (if (map? (first children))
                                     [(first children) (rest children)]
                                     [{} children])]
      (into [schema-type (assoc properties :doc description)] children))
    [:schema {:doc description} schema]))

(defn- payload-registry [entries]
  (into {}
        (keep (fn [[id {:keys [description payload]}]]
                (when payload
                  [id (with-schema-doc payload description)])))
        entries))

(def malli-registry
  (merge payload-schema-registry
         {::effect-id (into [:enum] (sort (keys effects)))
          ::event-id  (into [:enum] (sort (keys events)))
          ::state-id  (into [:enum] (sort (keys states)))}
         (payload-registry events)
         (payload-registry effects)))

(defn vocabulary-entry
  "Returns the vocabulary entry for `kind` and literal identifier `id`."
  [kind id]
  (get-in vocabulary [kind id]))

(defn payload-schema
  "Returns the registered payload schema for vocabulary `kind` and identifier `id`."
  [kind id]
  (when (:payload (vocabulary-entry kind id))
    (get malli-registry id)))

(defn valid-payload?
  "Returns whether `value` satisfies the registered payload schema.

  Identifiers without a payload schema accept only `nil` or an empty map."
  [kind id value]
  (if (payload-schema kind id)
    (m/validate [:schema {:registry malli-registry} id] value)
    (or (nil? value) (= {} value))))

(defn- event-data [data]
  (get-in data [:_event :data]))

(defn- initialize-settings [_ data]
  (let [{:keys [settings settings-revision]} (event-data data)]
    [(ops/assign [:settings]
                 {:revision settings-revision
                  :values   settings})]))

(defn- begin-card-request [_ data]
  (let [{:keys [item-path presence-epoch request-id uid]} (event-data data)
        request {:announce-tracks?
                 (true? (get-in data
                                [:settings :values :tts :announce-tracks?]))
                 :item-path        item-path
                 :presence-epoch   presence-epoch
                 :request-id       request-id
                 :uid              uid}]
    [(ops/assign [:audio :pending-request] request)]))

(defn- clear-pending-request [_ _]
  [(ops/assign [:audio :pending-request] nil)])

(defn- clear-card-request [_ _]
  [(ops/assign [:audio :active-request] nil)
   (ops/assign [:audio :pending-request] nil)])

(defn- request-playback-context [request]
  (select-keys request [:presence-epoch :request-id :uid]))

(defn- preparation-effect-data [_ data]
  (select-keys (get-in data [:audio :pending-request])
               [:announce-tracks?
                :item-path
                :presence-epoch
                :request-id
                :uid]))

(defn- accept-preparation [_ data]
  (let [{:keys [paths]} (event-data data)
        pending         (get-in data [:audio :pending-request])]
    [(ops/assign [:audio :playback-context]
                 (request-playback-context pending))
     (ops/assign [:audio :pending-request] (assoc pending :paths paths))]))

(defn- cancel-preparation-effect-data [_ data]
  (select-keys (get-in data [:audio :pending-request])
               [:presence-epoch :request-id]))

(defn- play-queue-effect-data [_ data]
  (let [pending (get-in data [:audio :pending-request])]
    {:paths            (:paths pending)
     :playback-context (request-playback-context pending)}))

(defn- activate-card-request [_ data]
  (let [pending (get-in data [:audio :pending-request])
        request (select-keys pending
                             [:item-path
                              :presence-epoch
                              :request-id
                              :uid])]
    [(ops/assign [:audio :active-request] request)
     (ops/assign [:audio :playback-context]
                 (request-playback-context request))
     (ops/assign [:audio :pending-request] nil)]))

(defn- present-observation? [_ data]
  (= :present (:status (event-data data))))

(defn- absent-observation? [_ data]
  (= :absent (:status (event-data data))))

(defn- new-presence? [env data]
  (and (present-observation? env data)
       (< (get-in data [:rfid :presence-epoch] 0)
          (:presence-epoch (event-data data)))))

(defn- store-rfid-observation [_ data]
  (let [{:keys [presence-epoch status uid]} (event-data data)]
    [(ops/assign [:rfid]
                 {:presence-epoch presence-epoch
                  :present-uid    (when (= :present status) uid)})]))

(defn- clear-present-card [_ _]
  [(ops/assign [:rfid :present-uid] nil)])

(defn- event-value? [key value]
  (fn [_ data]
    (= value (get (event-data data) key))))

(defn- linked-card? [env data]
  (and (new-presence? env data)
       (some? (:item-path (event-data data)))))

(defn- unlinked-card? [env data]
  (and (new-presence? env data)
       (nil? (:item-path (event-data data)))))

(defn- normal-linked-card? [env data]
  (and ((In :interaction.st/normal) env data)
       (linked-card? env data)))

(defn- normal-unlinked-card? [env data]
  (and ((In :interaction.st/normal) env data)
       (unlinked-card? env data)))

(defn- one-shot-purpose? [purpose]
  (event-value? :purpose purpose))

(defn- current-preparation? [_ data]
  (let [pending (get-in data [:audio :pending-request])
        event   (event-data data)]
    (and (= (:presence-epoch pending) (:presence-epoch event))
         (= (:request-id pending) (:request-id event)))))

(defn- event-playback-context [data]
  (:playback-context (event-data data)))

(defn- current-pending-playback? [data]
  (= (request-playback-context (get-in data [:audio :pending-request]))
     (event-playback-context data)))

(defn- current-active-playback? [data]
  (= (request-playback-context (get-in data [:audio :active-request]))
     (event-playback-context data)))

(defn- current-playback? [_ data]
  (current-active-playback? data))

(defn- current-player-state? [player-state]
  (fn [_ data]
    (and (or (current-pending-playback? data)
             (current-active-playback? data))
         (= player-state (:state (event-data data))))))

(defn- current-terminal-player-state? [player-state]
  (fn [_ data]
    (and (= (get-in data [:audio :playback-context])
            (event-playback-context data))
         (= player-state (:state (event-data data))))))

(defn- current-feedback-timer? [_ data]
  (let [feedback (get-in data [:interaction :feedback])
        event    (event-data data)]
    (and (= (:request-id feedback) (:request-id event))
         (= (:timer-token feedback) (:timer-token event)))))

(defn- current-auto-shutdown-timer? [_ data]
  (= (get-in data [:power :auto-shutdown-token])
     (:timer-token (event-data data))))

(defn- current-sleep-timer? [_ data]
  (= (get-in data [:power :sleep-token])
     (:timer-token (event-data data))))

(defn- setting? [path value]
  (fn [_ data]
    (= value (get-in data (into [:settings :values] path)))))

(defn- current-request-absence? [request-path]
  (fn [env data]
    (and (absent-observation? env data)
         (= (get-in data (conj request-path :presence-epoch))
            (:presence-epoch (event-data data))))))

(def ^:private current-active-card-absence?
  (current-request-absence? [:audio :active-request]))

(def ^:private current-pending-card-absence?
  (current-request-absence? [:audio :pending-request]))

(defn- current-card-removal? [removal-behavior]
  (fn [env data]
    (and (current-active-card-absence? env data)
         ((setting? [:audio :card-removal-behavior] removal-behavior)
          env
          data))))

(defn- returning-active-card? [env data]
  (and (new-presence? env data)
       (= (get-in data [:audio :active-request :uid])
          (:uid (event-data data)))))

(defn- rebind-active-card [_ data]
  [(ops/assign [:audio :active-request :presence-epoch]
               (:presence-epoch (event-data data)))])

(defn- resume-returning-card? [env data]
  (and (returning-active-card? env data)
       ((setting? [:audio :card-return-behavior] :resume) env data)))

(defn- new-linked-card-request? [env data]
  (and (linked-card? env data)
       (not (resume-returning-card? env data))))

(defn- button? [button-id]
  (event-value? :button-id button-id))

(defn- idle-for-auto-shutdown? [env data]
  (and ((setting? [:auto-shutdown :enabled?] true) env data)
       ((In :lifecycle.st/ready) env data)
       ((In :player.st/stopped) env data)
       ((In :one-shot.st/idle) env data)))

(defn- lifecycle-region []
  (state {:id      :region.st/lifecycle
          :initial :lifecycle.st/warming-up}
         (state {:id :lifecycle.st/warming-up}
                (transition {:cond              (one-shot-purpose? :startup)
                             :diagram/condition "startup one-shot"
                             :event             :one-shot.ev/completed
                             :target            :lifecycle.st/ready
                             ::effects          [:led.fx/show-ready]})
                (transition {:cond              (one-shot-purpose? :startup)
                             :diagram/condition "startup one-shot"
                             :event             :one-shot.ev/failed
                             :target            :lifecycle.st/ready
                             ::effects          [:led.fx/show-ready]}))
         (state {:id :lifecycle.st/ready}
                (transition {:event    :system.ev/cooling-requested
                             :target   :lifecycle.st/cooling
                             ::effects [:player.fx/stop
                                        :timer.fx/cancel-all
                                        :one-shot.fx/play
                                        :led.fx/show-cooling]}))
         (state {:id      :lifecycle.st/cooling
                 :initial :cooling.st/waiting-for-one-shot}
                (state {:id :cooling.st/waiting-for-one-shot}
                       (transition {:cond              (one-shot-purpose? :shutdown)
                                    :diagram/condition "shutdown one-shot"
                                    :event             :one-shot.ev/completed
                                    :target            :cooling.st/releasing
                                    ::effects          [:runtime.fx/release-resources]})
                       (transition {:cond              (one-shot-purpose? :shutdown)
                                    :diagram/condition "shutdown one-shot"
                                    :event             :one-shot.ev/failed
                                    :target            :cooling.st/releasing
                                    ::effects          [:runtime.fx/release-resources]}))
                (state {:id :cooling.st/releasing}
                       (transition {:event    :system.ev/resources-released
                                    :target   :system.st/stopped
                                    ::effects [:system.fx/execute-operation
                                               :led.fx/turn-off]})))))

(defn- settings-subsystem []
  (state {:id      :subsystem.st/settings
          :initial :settings.st/current}
         (state {:id :settings.st/current}
                (transition {:event    :settings.ev/update-requested
                             :target   :settings.st/updating
                             ::effects [:database.fx/update-settings]})
                (transition {:event :settings.ev/changed
                             :type  :internal}))
         (state {:id :settings.st/updating}
                (transition {:event  :settings.ev/changed
                             :target :settings.st/current})
                (transition {:event  :settings.ev/update-failed
                             :target :settings.st/failed}))
         (state {:id :settings.st/failed}
                (transition {:event    :settings.ev/update-requested
                             :target   :settings.st/updating
                             ::effects [:database.fx/update-settings]})
                (transition {:event  :settings.ev/changed
                             :target :settings.st/current}))))

(defn- rfid-subsystem []
  (state {:id      :subsystem.st/rfid
          :initial :rfid.st/absent}
         (transition {:event  :rfid.ev/faulted
                      :target :rfid.st/faulted
                      :type   :internal}
                     (script {:expr clear-present-card}))
         (state {:id :rfid.st/absent}
                (transition {:cond              present-observation?
                             :diagram/condition "present observation"
                             :event             :rfid.ev/presence-observed
                             :target            :rfid.st/present}
                            (script {:expr store-rfid-observation}))
                (transition {:cond              absent-observation?
                             :diagram/condition "absent observation"
                             :event             :rfid.ev/presence-observed}
                            (script {:expr store-rfid-observation})))
         (state {:id :rfid.st/present}
                (transition {:cond              present-observation?
                             :diagram/condition "present observation"
                             :event             :rfid.ev/presence-observed
                             :target            :rfid.st/present
                             :type              :internal}
                            (script {:expr store-rfid-observation}))
                (transition {:cond              absent-observation?
                             :diagram/condition "absent observation"
                             :event             :rfid.ev/presence-observed
                             :target            :rfid.st/absent}
                            (script {:expr store-rfid-observation})))
         (state {:id :rfid.st/faulted}
                (transition {:event  :rfid.ev/recovered
                             :target :rfid.st/absent}))))

(defn- main-player-region []
  (state {:id      :audio.st/main-player
          :initial :player.st/stopped}
         (transition {:cond              (current-player-state? :opening)
                      :diagram/condition "current playback"
                      :event             :player.ev/state-changed
                      :target            :player.st/opening
                      :type              :internal})
         (transition {:cond              (current-player-state? :playing)
                      :diagram/condition "current playback"
                      :event             :player.ev/state-changed
                      :target            :player.st/playing
                      :type              :internal})
         (transition {:cond              (current-player-state? :paused)
                      :diagram/condition "current playback"
                      :event             :player.ev/state-changed
                      :target            :player.st/paused
                      :type              :internal})
         (transition {:cond              (current-terminal-player-state? :stopped)
                      :diagram/condition "current or stopping playback"
                      :event             :player.ev/state-changed
                      :target            :player.st/stopped
                      :type              :internal})
         (transition {:cond              (current-terminal-player-state? :error)
                      :diagram/condition "current or stopping playback"
                      :event             :player.ev/state-changed
                      :target            :player.st/error
                      :type              :internal})
         (transition {:cond              current-playback?
                      :diagram/condition "current playback"
                      :event             :player.ev/time-changed
                      :type              :internal})
         (state {:id :player.st/stopped})
         (state {:id :player.st/opening})
         (state {:id :player.st/playing})
         (state {:id :player.st/paused})
         (state {:id :player.st/error})))

(defn- card-request-region []
  (state {:id      :audio.st/card-request
          :initial :card-request.st/idle}
         (transition {:cond              normal-linked-card?
                      :diagram/condition "linked card in normal interaction mode"
                      :event             :rfid.ev/presence-observed
                      :target            :card-request.st/preparing
                      :type              :internal}
                     (script {:expr begin-card-request})
                     (effect :media.fx/prepare preparation-effect-data))
         (transition {:cond              normal-unlinked-card?
                      :diagram/condition "unlinked card in normal interaction mode"
                      :event             :rfid.ev/presence-observed
                      :target            :card-request.st/unlinked
                      :type              :internal}
                     (script {:expr clear-pending-request}))
         (transition {:event    :player.ev/stop-requested
                      :target   :card-request.st/idle
                      :type     :internal
                      ::effects [:media.fx/cancel-preparation
                                 :player.fx/stop]}
                     (script {:expr clear-card-request}))
         (state {:id :card-request.st/idle})
         (state {:id             :card-request.st/preparing
                 ::entry-effects [:media.fx/prepare]
                 ::exit-effects  [:media.fx/cancel-preparation]}
                (transition {:cond              current-preparation?
                             :diagram/condition "current preparation"
                             :event             :media.ev/prepared
                             :target            :card-request.st/waiting-for-playback
                             ::effects          [:player.fx/play-queue]}
                            (script {:expr accept-preparation})
                            (effect :player.fx/play-queue
                                    play-queue-effect-data))
                (transition {:cond              current-preparation?
                             :diagram/condition "current preparation"
                             :event             :media.ev/preparation-failed
                             :target            :card-request.st/failed})
                (transition {:cond              current-pending-card-absence?
                             :diagram/condition "current card removed during preparation"
                             :event             :rfid.ev/presence-observed
                             :target            :card-request.st/idle}
                            (effect :media.fx/cancel-preparation
                                    cancel-preparation-effect-data)
                            (script {:expr clear-pending-request})))
         (state {:id :card-request.st/waiting-for-playback}
                (transition {:cond              (current-player-state? :opening)
                             :diagram/condition "current playback"
                             :event             :player.ev/state-changed
                             :target            :card-request.st/active}
                            (script {:expr activate-card-request}))
                (transition {:cond              current-pending-card-absence?
                             :diagram/condition "current card removed before playback"
                             :event             :rfid.ev/presence-observed
                             :target            :card-request.st/idle}
                            (effect :player.fx/stop (constantly {}))
                            (script {:expr clear-card-request})))
         (state {:id :card-request.st/active}
                (transition {:cond              (current-card-removal? :pause)
                             :diagram/condition "active card pauses on removal"
                             :event             :rfid.ev/presence-observed
                             :target            :card-request.st/suspended
                             ::effects          [:player.fx/pause]}
                            (effect :player.fx/pause (constantly {})))
                (transition {:cond              (current-card-removal? :keep-playing)
                             :diagram/condition "active card keeps playing on removal"
                             :event             :rfid.ev/presence-observed
                             :target            :card-request.st/active
                             :type              :internal})
                (transition {:cond              returning-active-card?
                             :diagram/condition "same active card returns in a new presence epoch"
                             :event             :rfid.ev/presence-observed
                             :type              :internal}
                            (script {:expr rebind-active-card})))
         (state {:id :card-request.st/suspended}
                (transition {:cond              resume-returning-card?
                             :diagram/condition "same paused card resumes"
                             :event             :rfid.ev/presence-observed
                             :target            :card-request.st/active
                             ::effects          [:player.fx/resume]}
                            (script {:expr rebind-active-card})
                            (effect :player.fx/resume (constantly {})))
                (transition {:cond              new-linked-card-request?
                             :diagram/condition "linked card starts a new request"
                             :event             :rfid.ev/presence-observed
                             :target            :card-request.st/preparing}
                            (script {:expr begin-card-request})
                            (effect :media.fx/prepare
                                    preparation-effect-data)))
         (state {:id             :card-request.st/unlinked
                 ::entry-effects [:led.fx/show-card-unknown
                                  :tts.fx/speak]}
                (transition {:cond              absent-observation?
                             :diagram/condition "current unlinked card removed"
                             :event             :rfid.ev/presence-observed
                             :target            :card-request.st/idle}
                            (script {:expr clear-pending-request})))

         (state {:id             :card-request.st/failed
                 ::entry-effects [:led.fx/show-card-problem
                                  :tts.fx/speak]}
                (transition {:cond              absent-observation?
                             :diagram/condition "current failed card removed"
                             :event             :rfid.ev/presence-observed
                             :target            :card-request.st/idle}
                            (script {:expr clear-pending-request})))))

(defn- one-shot-region []
  (state {:id      :audio.st/one-shot
          :initial :one-shot.st/idle}
         (transition {:event    :one-shot.ev/requested
                      :target   :one-shot.st/playing
                      :type     :internal
                      ::effects [:one-shot.fx/play]})
         (state {:id :one-shot.st/idle})
         (state {:id            :one-shot.st/playing
                 ::exit-effects [:one-shot.fx/stop]}
                (transition {:event :one-shot.ev/started
                             :type  :internal})
                (transition {:event  :one-shot.ev/completed
                             :target :one-shot.st/idle})
                (transition {:event  :one-shot.ev/failed
                             :target :one-shot.st/failed}))
         (state {:id :one-shot.st/failed}
                (transition {:event    :one-shot.ev/requested
                             :target   :one-shot.st/playing
                             ::effects [:one-shot.fx/play]}))))

(defn- audio-subsystem []
  (state {:id      :subsystem.st/audio
          :initial :audio.st/available}
         (parallel {:id :audio.st/available}
                   (transition {:event    :audio.ev/faulted
                                :target   :audio.st/unavailable
                                ::effects [:media.fx/cancel-preparation
                                           :one-shot.fx/stop
                                           :player.fx/stop
                                           :led.fx/show-system-problem]})
                   (main-player-region)
                   (card-request-region)
                   (one-shot-region))
         (state {:id :audio.st/unavailable}
                (transition {:event  :audio.ev/recovered
                             :target :audio.st/available}))))

(defn- normal-feedback []
  (state {:id      :interaction.st/normal
          :initial :feedback.st/idle}
         (transition {:cond              (button? :audio/play-pause)
                      :diagram/condition "play/pause button"
                      :event             :button.ev/pressed
                      :type              :internal
                      ::effects          [:player.fx/toggle]})
         (transition {:cond              (button? :audio/next)
                      :diagram/condition "next button"
                      :event             :button.ev/pressed
                      :type              :internal
                      ::effects          [:player.fx/next]})
         (transition {:cond              (button? :audio/previous)
                      :diagram/condition "previous button"
                      :event             :button.ev/pressed
                      :type              :internal
                      ::effects          [:player.fx/previous]})
         (transition {:cond              (button? :audio/volume-up)
                      :diagram/condition "volume-up button"
                      :event             :button.ev/pressed
                      :type              :internal
                      ::effects          [:player.fx/adjust-volume]})
         (transition {:cond              (button? :audio/volume-down)
                      :diagram/condition "volume-down button"
                      :event             :button.ev/pressed
                      :type              :internal
                      ::effects          [:player.fx/adjust-volume]})
         (transition {:cond              (button? :audio/play-pause)
                      :diagram/condition "play/pause held"
                      :event             :button.ev/held
                      :target            :interaction.st/card-identification
                      ::effects          [:player.fx/stop
                                          :led.fx/show-identification]})
         (transition {:cond              linked-card?
                      :diagram/condition "new linked card presence"
                      :event             :rfid.ev/presence-observed
                      :target            :feedback.st/known-card
                      :type              :internal})
         (transition {:cond              unlinked-card?
                      :diagram/condition "new unlinked card presence"
                      :event             :rfid.ev/presence-observed
                      :target            :feedback.st/unknown-card
                      :type              :internal})
         (transition {:event  :interaction.ev/problem-reported
                      :target :feedback.st/problem
                      :type   :internal})
         (transition {:cond              (current-player-state? :opening)
                      :diagram/condition "current playback"
                      :event             :player.ev/state-changed
                      :target            :feedback.st/playback-started
                      :type              :internal})
         (transition {:cond              absent-observation?
                      :diagram/condition "newer absent observation"
                      :event             :rfid.ev/presence-observed
                      :target            :feedback.st/idle
                      :type              :internal})
         (transition {:event  :player.ev/stop-requested
                      :target :feedback.st/idle
                      :type   :internal})
         (state {:id :feedback.st/idle})
         (state {:id             :feedback.st/known-card
                 ::entry-effects [:led.fx/show-card-known
                                  :timer.fx/schedule-card-feedback]
                 ::exit-effects  [:timer.fx/cancel-card-feedback]}
                (transition {:cond              current-feedback-timer?
                             :diagram/condition "current feedback timer"
                             :event             :timer.ev/card-feedback-fired
                             :target            :feedback.st/preparing}))
         (state {:id             :feedback.st/preparing
                 ::entry-effects [:led.fx/show-preparing]})
         (state {:id             :feedback.st/playback-started
                 ::entry-effects [:led.fx/show-playback-started]})
         (state {:id             :feedback.st/unknown-card
                 ::entry-effects [:led.fx/show-card-unknown
                                  :tts.fx/speak]})
         (state {:id             :feedback.st/problem
                 ::entry-effects [:led.fx/show-card-problem
                                  :tts.fx/speak]})))

(defn- card-identification []
  (state {:id      :interaction.st/card-identification
          :initial :identification.st/idle}
         (transition {:cond              (button? :audio/play-pause)
                      :diagram/condition "play/pause held"
                      :event             :button.ev/held
                      :target            :interaction.st/normal
                      ::effects          [:led.fx/show-ready]})
         (state {:id :identification.st/idle}
                (transition {:cond              linked-card?
                             :diagram/condition "new linked card presence"
                             :event             :rfid.ev/presence-observed
                             :target            :identification.st/reading-metadata
                             ::effects          [:metadata.fx/read]})
                (transition {:cond              unlinked-card?
                             :diagram/condition "new unlinked card presence"
                             :event             :rfid.ev/presence-observed
                             :target            :identification.st/speaking
                             ::effects          [:tts.fx/speak]}))
         (state {:id :identification.st/reading-metadata}
                (transition {:event    :metadata.ev/read-finished
                             :target   :identification.st/speaking
                             ::effects [:tts.fx/speak]})
                (transition {:event  :metadata.ev/read-failed
                             :target :identification.st/failed}))
         (state {:id :identification.st/speaking}
                (transition {:cond              (one-shot-purpose?
                                                 :card-identification)
                             :diagram/condition "identification speech"
                             :event             :one-shot.ev/completed
                             :target            :identification.st/idle})
                (transition {:cond              (one-shot-purpose?
                                                 :card-identification)
                             :diagram/condition "identification speech"
                             :event             :one-shot.ev/failed
                             :target            :identification.st/failed}))
         (state {:id             :identification.st/failed
                 ::entry-effects [:led.fx/show-card-problem]}
                (transition {:cond              absent-observation?
                             :diagram/condition "newer absent observation"
                             :event             :rfid.ev/presence-observed
                             :target            :identification.st/idle}))))

(defn- interaction-subsystem []
  (state {:id      :subsystem.st/interaction
          :initial :interaction.st/normal}
         (normal-feedback)
         (card-identification)))

(defn- auto-shutdown-region []
  (state {:id      :power.st/auto-shutdown
          :initial :auto-shutdown.st/disabled}
         (state {:id :auto-shutdown.st/disabled}
                (transition {:cond              (setting?
                                                 [:auto-shutdown :enabled?]
                                                 true)
                             :diagram/condition "auto-shutdown enabled"
                             :event             :settings.ev/changed
                             :target            :auto-shutdown.st/waiting-for-idle}))
         (state {:id :auto-shutdown.st/waiting-for-idle}
                (transition {:cond              idle-for-auto-shutdown?
                             :diagram/condition "device idle"
                             :event             :player.ev/state-changed
                             :target            :auto-shutdown.st/armed})
                (transition {:cond              (setting?
                                                 [:auto-shutdown :enabled?]
                                                 false)
                             :diagram/condition "auto-shutdown disabled"
                             :event             :settings.ev/changed
                             :target            :auto-shutdown.st/disabled}))
         (state {:id             :auto-shutdown.st/armed
                 ::entry-effects [:timer.fx/schedule-auto-shutdown]
                 ::exit-effects  [:timer.fx/cancel-auto-shutdown]}
                (transition {:event  :button.ev/pressed
                             :target :auto-shutdown.st/waiting-for-idle})
                (transition {:cond              new-presence?
                             :diagram/condition "new card presence"
                             :event             :rfid.ev/presence-observed
                             :target            :auto-shutdown.st/waiting-for-idle})
                (transition {:event  :one-shot.ev/started
                             :target :auto-shutdown.st/waiting-for-idle})
                (transition {:cond              current-auto-shutdown-timer?
                             :diagram/condition "current shutdown timer"
                             :event             :timer.ev/auto-shutdown-fired
                             :target            :auto-shutdown.st/requested
                             ::effects          [:system.fx/request-operation]}))
         (state {:id :auto-shutdown.st/requested}
                (transition {:event  :system.ev/cooling-requested
                             :target :auto-shutdown.st/disabled}))))

(defn- sleep-region []
  (state {:id      :power.st/sleep
          :initial :sleep.st/inactive}
         (state {:id :sleep.st/inactive}
                (transition {:event    :sleep.ev/requested
                             :target   :sleep.st/counting-down
                             ::effects [:timer.fx/schedule-sleep-fade]}))
         (state {:id            :sleep.st/counting-down
                 ::exit-effects [:timer.fx/cancel-sleep]}
                (transition {:cond              current-sleep-timer?
                             :diagram/condition "current sleep timer"
                             :event             :timer.ev/sleep-fade-fired
                             :target            :sleep.st/fading})
                (transition {:event  :sleep.ev/cancelled
                             :target :sleep.st/inactive}))
         (state {:id :sleep.st/fading}
                (transition {:event    :timer.ev/sleep-shutdown-fired
                             :target   :sleep.st/shutdown-wait
                             ::effects [:timer.fx/schedule-sleep-shutdown]})
                (transition {:event  :sleep.ev/cancelled
                             :target :sleep.st/inactive}))
         (state {:id            :sleep.st/shutdown-wait
                 ::exit-effects [:timer.fx/cancel-sleep]}
                (transition {:cond              current-sleep-timer?
                             :diagram/condition "current sleep timer"
                             :event             :timer.ev/sleep-shutdown-fired
                             :target            :sleep.st/inactive
                             ::effects          [:system.fx/request-operation]})
                (transition {:event  :sleep.ev/cancelled
                             :target :sleep.st/inactive}))))

(defn- power-subsystem []
  (parallel {:id :subsystem.st/power}
            (auto-shutdown-region)
            (sleep-region)))

(def application-chart
  "Breadth-first Box2 application topology.

  Effects in `::effects`, `::entry-effects`, and `::exit-effects` document
  intended external work and vocabulary coverage. The card-request walking
  skeleton emits executable preparation and player commands; remaining effect
  annotations await their production actions and payload construction."
  (chart/statechart {:initial :system.st/starting :name :fairy-box-2}
                    (data-model {:expr initial-data})
                    (state {:id :system.st/starting}
                           (transition {:event    :system.ev/initialized
                                        :target   :system.st/active
                                        ::effects [:one-shot.fx/play
                                                   :led.fx/show-warming]}
                                       (script {:expr initialize-settings}))
                           (transition {:event    :system.ev/start-failed
                                        :target   :system.st/failed
                                        ::effects [:led.fx/show-system-problem]}))
                    (parallel {:id :system.st/active}
                              (transition {:event    :system.ev/fatal-error
                                           :target   :system.st/failed
                                           ::effects [:media.fx/cancel-preparation
                                                      :one-shot.fx/stop
                                                      :player.fx/stop
                                                      :timer.fx/cancel-all
                                                      :led.fx/show-system-problem]})
                              (lifecycle-region)
                              (settings-subsystem)
                              (rfid-subsystem)
                              (audio-subsystem)
                              (interaction-subsystem)
                              (power-subsystem))
                    (final {:id :system.st/stopped})
                    (state {:id :system.st/failed})))

(defn- chart-elements []
  (vals (::sc/elements-by-id application-chart)))

(defn- modeled-state-ids []
  (into #{}
        (comp
         (filter #(contains? #{:final :parallel :state} (:node-type %)))
         (remove :initial?)
         (map :id))
        (chart-elements)))

(defn- modeled-event-ids []
  (into #{}
        (comp
         (filter #(= :transition (:node-type %)))
         (mapcat #(let [event (:event %)]
                    (if (sequential? event) event [event])))
         (remove nil?))
        (chart-elements)))

(defn- modeled-effect-ids []
  (into #{}
        (mapcat #(concat (::effects %)
                         (::entry-effects %)
                         (::exit-effects %)))
        (chart-elements)))

(defn- invalid-payload-schema-ids []
  (into #{}
        (mapcat
         (fn [[kind entries]]
           (keep (fn [[id {:keys [payload]}]]
                   (when payload
                     (try
                       (m/schema [:schema {:registry malli-registry} id])
                       nil
                       (catch Throwable _
                         [kind id]))))
                 entries)))
        (select-keys vocabulary [:effects :events])))

(defn vocabulary-problems
  "Returns missing, unused, or invalid vocabulary entries for the chart."
  []
  (let [modeled-states  (modeled-state-ids)
        modeled-events  (modeled-event-ids)
        modeled-effects (modeled-effect-ids)
        states          (set (keys (:states vocabulary)))
        events          (set (keys (:events vocabulary)))
        effects         (set (keys (:effects vocabulary)))]
    {:invalid-payload-schemas (invalid-payload-schema-ids)
     :undocumented-effects    (set/difference modeled-effects effects)
     :undocumented-events     (set/difference modeled-events events)
     :undocumented-states     (set/difference modeled-states states)
     :unused-effects          (set/difference effects modeled-effects)
     :unused-events           (set/difference events modeled-events)
     :unused-states           (set/difference states modeled-states)}))

(comment
  (vocabulary-problems)

  (valid-payload?
   :events
   :rfid.ev/presence-observed
   {:item-path      "/media/synthetic-card"
    :presence-epoch 1
    :request-id     (random-uuid)
    :status         :present
    :uid            "SYNTHETIC-CARD"})

  :rcf)