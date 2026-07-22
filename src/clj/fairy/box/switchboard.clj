;; Copyright © 2025 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2
(ns fairy.box.switchboard
  (:require
   [clojure.core.async :as async]
   [clojure.java.shell :as shell]
   [clojure.tools.logging :as log]
   [fairy.box.audio :as audio]
   [fairy.box.audio.browse :as browse]
   [fairy.box.db :as db]
   [fairy.box.tts :as tts]
   [jp.nijohando.event :as ev]
   [medley.core :as m]))

(def ^:private init-state {:system-state             :system-state/booting
                           :system-mode              :system-mode/normal
                           :rfid nil
                           :active-card-uid          nil
                           :removed-card             nil
                           :card-feedback            nil
                           :pending-system-operation nil})
(defonce ^:private state (atom init-state))

(def ^:private card-feedback-animation-id
  :card-playback-feedback)

(def ^:private preparation-delay-ms 500)

(def ^:private known-card-pattern
  {:action             :led/animate
   :animation-id       card-feedback-animation-id
   :repeat-times       2
   :relative-to-limit? true
   :after-set          1.0
   :tweens             [{:names    [:audio/volume-up :audio/volume-down]
                         :from     1.0
                         :to       0.15
                         :duration 80
                         :easing   :out-sine}
                        {:names    [:audio/volume-up :audio/volume-down]
                         :from     0.15
                         :to       1.0
                         :duration 120
                         :delay    80
                         :easing   :out-sine}]})

(def ^:private preparation-pattern
  {:action             :led/animate
   :animation-id       card-feedback-animation-id
   :repeat-times       :forever
   :relative-to-limit? true
   :after-set          1.0
   :tweens             [{:names    [:audio/play-pause]
                         :from     1.0
                         :to       0.25
                         :duration 700
                         :easing   :in-out-sine}
                        {:names    [:audio/play-pause]
                         :from     0.25
                         :to       1.0
                         :duration 700
                         :delay    700
                         :easing   :in-out-sine}]})

(def ^:private here-we-go-pattern
  {:action             :led/animate
   :animation-id       card-feedback-animation-id
   :relative-to-limit? true
   :after-set          1.0
   :tweens             [{:groups   [:all]
                         :from     1.0
                         :to       0.15
                         :duration 100
                         :easing   :out-sine}
                        {:groups   [:all]
                         :from     0.15
                         :to       1.0
                         :duration 200
                         :delay    150
                         :easing   :out-sine}]})

(def ^:private unknown-card-pattern
  {:action             :led/animate
   :animation-id       card-feedback-animation-id
   :repeat-times       3
   :relative-to-limit? true
   :after-set          1.0
   :tweens             [{:names    [:audio/prev]
                         :from     1.0
                         :to       0.15
                         :duration 100
                         :easing   :out-sine}
                        {:names    [:audio/prev]
                         :from     0.15
                         :to       1.0
                         :duration 100
                         :delay    100
                         :easing   :out-sine}
                        {:names    [:audio/next]
                         :from     1.0
                         :to       0.15
                         :duration 100
                         :delay    100
                         :easing   :out-sine}
                        {:names    [:audio/next]
                         :from     0.15
                         :to       1.0
                         :duration 100
                         :delay    200
                         :easing   :out-sine}]})

(def ^:private playback-problem-pattern
  {:action             :led/animate
   :animation-id       card-feedback-animation-id
   :repeat-times       3
   :relative-to-limit? true
   :after-set          1.0
   :tweens             [{:groups   [:all]
                         :from     1.0
                         :to       0.05
                         :duration 80
                         :easing   :out-sine}
                        {:groups   [:all]
                         :from     0.05
                         :to       1.0
                         :duration 100
                         :delay    80
                         :easing   :out-sine}]})

(defn system-state! []
  (:system-state @state))

(defn emit-system! [emitter event]
  (async/put! emitter {:path "/system" :value event}))

(declare invalidate-card-feedback!)

(defn- invalidates-card-feedback? [{:keys [action uid]}]
  (or (#{:audio/clear :audio/stop} action)
      (and (= :audio/play-path action) (nil? uid))))

(defn emit-player! [emitter event]
  (when (invalidates-card-feedback? event)
    (invalidate-card-feedback! emitter true))
  (async/put! emitter {:path "/player/commands" :value event}))

(defn emit-tts! [emitter event]
  (async/put! emitter {:path "/tts/commands" :value event}))

(defn emit-led! [emitter event]
  (async/put! emitter {:path "/hardware/output/leds" :value event}))

(defn- invalidate-card-feedback! [emitter restore?]
  (locking state
    (when (:card-feedback @state)
      (swap! state assoc :card-feedback nil)
      (emit-led! emitter {:action       :led/animation-cancel
                          :animation-id card-feedback-animation-id})
      (when (and restore?
                 (= :system-mode/normal (:system-mode @state)))
        (emit-led! emitter {:action :led/set
                            :groups [:all]
                            :value  1.0})))))

(defn disable-card-feedback! [{:keys [emitter]}]
  (locking state
    (swap! state update :card-feedback
           #(when % (assoc %
                           :led-eligible? false
                           :preparation-token nil)))
    (when (= :system-mode/normal (:system-mode @state))
      (emit-led! emitter {:action       :led/animation-cancel
                          :animation-id card-feedback-animation-id})
      (emit-led! emitter {:action :led/set
                          :groups [:all]
                          :value  1.0}))))

(defn- feedback-led-enabled? [db-conn feedback]
  (and (:led-eligible? feedback)
       (db/led-language? (some-> db-conn deref))))

(defn- schedule-preparation!
  [{:keys [emitter db-conn]} request-id preparation-token]
  (async/go
    (async/<! (async/timeout preparation-delay-ms))
    (locking state
      (let [feedback (:card-feedback @state)]
        (when (and (= request-id (:request-id feedback))
                   (= preparation-token (:preparation-token feedback))
                   (:awaiting-start? feedback)
                   (= :system-state/ready (:system-state @state))
                   (= :system-mode/normal (:system-mode @state))
                   (feedback-led-enabled? db-conn feedback))
          (swap! state assoc-in
                 [:card-feedback :preparation-visible?]
                 true)
          (emit-led! emitter preparation-pattern))))))

(defn- begin-card-feedback!
  [{:keys [emitter db-conn] :as sys} uid request-id]
  (let [preparation-token (random-uuid)
        led-eligible?     (db/led-language? @db-conn)]
    (locking state
      (swap! state assoc
             :card-feedback {:request-id           request-id
                             :uid                  uid
                             :awaiting-start?      true
                             :problem-handled?     false
                             :led-eligible?        led-eligible?
                             :preparation-token    preparation-token
                             :preparation-visible? false})
      (when led-eligible?
        (emit-led! emitter known-card-pattern)))
    (schedule-preparation! sys request-id preparation-token)))

(defn- acknowledge-known-card! [emitter database]
  (when (db/led-language? database)
    (emit-led! emitter known-card-pattern)))

(defn- claim-card-start! [request-id]
  (locking state
    (let [feedback (:card-feedback @state)]
      (when (and (= request-id (:request-id feedback))
                 (:awaiting-start? feedback))
        (swap! state update :card-feedback
               assoc
               :awaiting-start? false
               :preparation-token nil)
        feedback))))

(defn- handle-card-opening! [{:keys [emitter db-conn]} request-id]
  (if-let [feedback (claim-card-start! request-id)]
    (do
      (emit-led! emitter {:action       :led/animation-cancel
                          :animation-id card-feedback-animation-id})
      (when (feedback-led-enabled? db-conn feedback)
        (emit-led! emitter here-we-go-pattern)))
    (when (and (nil? request-id)
               (:card-feedback @state))
      (invalidate-card-feedback! emitter true))))

(defn- claim-card-problem! [request-id]
  (locking state
    (let [feedback (:card-feedback @state)]
      (when (and (= request-id (:request-id feedback))
                 (not (:problem-handled? feedback)))
        (swap! state update :card-feedback
               assoc
               :awaiting-start? false
               :problem-handled? true
               :preparation-token nil)
        feedback))))

(defn tts-command-handler
  [{:keys [emitter db-conn]} {:keys [value] :as _event}]
  (when (= :card-playback-problem (:feedback/type value))
    (when-let [feedback (claim-card-problem! (:request-id value))]
      (emit-led! emitter {:action       :led/animation-cancel
                          :animation-id card-feedback-animation-id})
      (when (feedback-led-enabled? db-conn feedback)
        (emit-led! emitter playback-problem-pattern)))))

(defn change-mode! [sys new-mode]
  (emit-player! (:emitter sys) {:action :audio/clear})
  (let [[before after] (swap-vals! state
                                   (fn [s]
                                     (-> s
                                         (assoc :system-mode new-mode)
                                         (assoc :rfid nil)
                                         (assoc :active-card-uid nil)
                                         (assoc :removed-card nil))))
        previous-mode  (:system-mode before)
        current-mode   (:system-mode after)]
    (when-not (= previous-mode current-mode)
      (log/info "System mode changed"
                {:from previous-mode
                 :to   current-mode}))
    current-mode))

(defn exit-card-id-mode [{:keys [emitter] :as sys}]
  (change-mode! sys :system-mode/normal)
  (emit-led! emitter {:action :led/animation-cancel :animation-id :card-identification-mode})
  (emit-led! emitter {:action :led/set :groups [:all] :value 1.0}))

(defn enter-card-id-mode [{:keys [emitter] :as sys}]
  (change-mode! sys :system-mode/card-identification)
  (emit-led! emitter {:action :led/set :names [:audio/prev :audio/next :audio/volume-up :audio/volume-down] :value 0.0})
  (emit-led! emitter {:action :led/pulse :names [:audio/play-pause] :after-set 1.0 :repeat-times 10 :animation-id :card-identification-mode}))

(defn handle-card-id-mode [sys {:keys [button-id]}]
  (when (= button-id :audio/play-pause)
    (if (= :system-mode/normal (:system-mode @state))
      (when (not= :playing (-> (audio/current-playback!) :state))
        (enter-card-id-mode sys))
      (exit-card-id-mode sys))))

(defn handle-button-press [{:keys [emitter] :as _sys} {:keys [button-id]}]
  (let [{:keys [system-mode rfid]} @state
        normal-mode?               (= :system-mode/normal system-mode)
        rfid-present?              (= :placed  (:action rfid))]
    (condp = button-id
      :audio/play-pause (when (and rfid-present? normal-mode?)
                          (async/put! emitter {:path  "/player/commands"
                                               :value {:action :audio/play-pause}}))
      :audio/next (when (and rfid-present? normal-mode?)
                    (async/put! emitter {:path  "/player/commands"
                                         :value {:action :audio/next}}))
      :audio/prev (when (and rfid-present? normal-mode?)
                    (async/put! emitter {:path  "/player/commands"
                                         :value {:action :audio/prev}}))
      :audio/volume-up (async/put! emitter {:path  "/player/commands"
                                            :value {:action :audio/volume-up}})
      :audio/volume-down (async/put! emitter {:path  "/player/commands"
                                              :value {:action :audio/volume-down}}))))

(defn button-handler [{_emitter :emitter :as sys} {:keys [value] :as _ev}]
  (when (= :system-state/ready (system-state!))
    (let [{:keys [action] _button-id :button-id} value]
      #_(tap> [:button button-id action])
      (condp = action
        :button/single-press (handle-button-press sys value)
        :button/hold (handle-card-id-mode sys value)
        nil))))

(defn speak-card-contents [{:keys [audio-system emitter]} item-path]
  (let [metadata (audio/metadata-for audio-system item-path)
        speech   (tts/metadata->speech metadata)]
    (tap> [:speak metadata :speech speech])
    (emit-tts! emitter {:action :tts/speak :text speech})))

(defn rfid-placed-card-id-mode [{:keys [emitter db-conn settings] :as sys} {:keys [uid]}]
  (emit-led! emitter {:action :led/pulse :names [:audio/volume-up :audio/volume-down] :after-set 0.0 :repeat-times 2})
  (if-let [item-path (browse/absoluteify settings (db/linked-folder @db-conn uid))]
    (speak-card-contents sys item-path)
    (emit-tts! emitter {:action              :tts/speak
                        :audio/play-one-shot false
                        :text                "This one is empty."})))

(def ^:private unknown-card-message
  "Uh-oh. This card is new to me.")

(defn rfid-placed-play-mode
  [{:keys [emitter db-conn settings] :as sys} {:keys [uid]}]
  (let [database  @db-conn
        item-path (browse/absoluteify settings
                                      (db/linked-folder database uid))]
    (if item-path
      (let [{removed-uid      :uid
             removal-behavior :removal-behavior} (:removed-card @state)
            returning-card? (= uid removed-uid)]
        (cond
          (and returning-card?
               (= :keep-playing removal-behavior))
          (acknowledge-known-card! emitter database)

          (and returning-card?
               (= :resume (db/card-return-behavior database)))
          (do
            (acknowledge-known-card! emitter database)
            (emit-player! emitter {:action :audio/play}))

          :else
          (let [request-id (random-uuid)]
            (invalidate-card-feedback! emitter true)
            (begin-card-feedback! sys uid request-id)
            (emit-player! emitter {:action     :audio/play-path
                                   :item-path  item-path
                                   :uid        uid
                                   :request-id request-id})))
        (swap! state assoc
               :active-card-uid uid
               :removed-card nil))
      (do
        (swap! state assoc
               :active-card-uid nil
               :removed-card nil)
        (emit-player! emitter {:action :audio/clear})
        (emit-tts! emitter {:action              :tts/speak
                            :feedback/type       :unknown-card
                            :audio/play-one-shot true
                            :text                unknown-card-message})
        (when (db/led-language? database)
          (locking state
            (swap! state assoc
                   :card-feedback {:kind          :unknown-card
                                   :led-eligible? true})
            (emit-led! emitter unknown-card-pattern)))))))

(defn rfid-removed-play-mode
  [{:keys [emitter db-conn]} {:keys [uid]}]
  (let [database         @db-conn
        active-card?     (= uid (:active-card-uid @state))
        removal-behavior (db/card-removal-behavior database)]
    (swap! state assoc
           :removed-card (when active-card?
                           {:uid              uid
                            :removal-behavior removal-behavior}))
    (when (and active-card?
               (= :pause removal-behavior)
               (= :playing (:state (audio/current-playback!))))
      (emit-player! emitter {:action :audio/pause}))))

(defn cap-volume! [emitter]
  ;; calls adjust volume with a 0 delta, which will ensure the volume is within the limits
  (async/put! emitter {:path  "/player/commands"
                       :value {:action :audio/adjust-volume :delta 0}}))

(defn rfid-handler [{:keys [emitter] :as sys} {:keys [value]}]
  (let [{:keys [action uid]} value]
    (case action
      :placed (log/info "RFID card placed" {:uid uid})
      :removed (log/info "RFID card removed" {:uid uid})
      nil)
    (when (= :system-state/ready (system-state!))
      (swap! state assoc :rfid value)
      (cap-volume! emitter)
      (condp = action
        :placed (condp = (:system-mode @state)
                  :system-mode/normal (rfid-placed-play-mode sys value)
                  :system-mode/card-identification (rfid-placed-card-id-mode sys value))
        :removed (when (= :system-mode/normal (:system-mode @state))
                   (rfid-removed-play-mode sys value))
        :error (do
                 (log/error "RFID error" (:error value))
                 (invalidate-card-feedback! emitter true)
                 (emit-led! emitter
                            {:action       :led/pulse
                             :names        [:audio/play-pause
                                            :audio/prev
                                            :audio/next
                                            :audio/volume-up
                                            :audio/volume-down]
                             :after-set    0.0
                             :repeat-times 9}))))))

(def ^:private system-control-commands
  {:system/poweroff         ["systemctl" "poweroff"]
   :system/reboot           ["systemctl" "reboot"]
   :system/restart-fairybox ["systemctl" "--user" "restart"
                             "fairybox.service"]})

(defn poweroff-enabled? [settings]
  (true? (get-in settings [:shutdown :poweroff-enabled?])))

(defn- execute-system-control! [settings operation]
  (if-let [command (get system-control-commands operation)]
    (if (poweroff-enabled? settings)
      (try
        (let [{:keys [exit err] :as result} (apply shell/sh command)]
          (if (zero? exit)
            (log/info "System control command completed"
                      {:operation operation})
            (log/error "System control command failed"
                       {:operation operation :exit exit :stderr err}))
          result)
        (catch Exception error
          (log/error error
                     "Unable to execute system control command"
                     {:operation operation})
          nil))
      (do
        (log/warn "Skipping system control because it is disabled for this profile"
                  {:operation operation})
        nil))
    (do
      (log/warn "Ignoring unknown system control operation"
                {:operation operation})
      nil)))

(defn- request-system-control! [emitter settings operation]
  (if (poweroff-enabled? settings)
    (when (= :system-state/ready (system-state!))
      (swap! state assoc :pending-system-operation operation)
      (emit-system! emitter {:event :system/cooling-down}))
    (log/warn "Ignoring system control request"
              {:operation operation :reason :disabled-for-profile})))

(defn- set-system-state! [new-state & kvs]
  (let [[before after] (apply swap-vals! state assoc
                              :system-state new-state
                              kvs)
        previous-state (:system-state before)
        current-state  (:system-state after)]
    (when-not (= previous-state current-state)
      (log/info "System state changed"
                {:from previous-state
                 :to   current-state}))
    current-state))

(defn system-handler [{:keys [emitter settings]} {:keys [value] :as _ev}]
  ;; (tap> {:system ev})
  (let [{:keys [event reason]} value]
    (condp contains? event
      #{:system/initialized}      (do
                                    (set-system-state! :system-state/initialized)
                                    (emit-system! emitter {:event :system/warming-up}))
      #{:system/warming-up}       (do
                                    (set-system-state! :system-state/warming-up)
                                    (emit-led! emitter {:action :led/set :groups [:all] :value 1.0})
                                    (when-let [sfx (browse/sfx-path settings :startup)]
                                      (emit-player! emitter {:action :audio/play-one-shot :id :startup-sound :item-path sfx})))
      #{:system/warmed-up}        (when (= :system-state/warming-up (system-state!))
                                    (set-system-state! :system-state/ready)
                                    (emit-system! emitter {:event :system/ready}))
      #{:system/poweroff :system/reboot :system/restart-fairybox}
      (request-system-control! emitter settings event)
      #{:system/cooling-down}     (when (and (= :system-state/ready (system-state!))
                                             (:pending-system-operation @state))
                                    (set-system-state! :system-state/cooling-down)
                                    (emit-player! emitter {:action :audio/stop})
                                    (emit-led! emitter {:action :led/fade :groups [:all] :duration 3000 :from 1.0 :to 0.0 :after-set 0.0 :start-delay 14000})
                                    (if-let [sfx (browse/sfx-path settings :shutdown)]
                                      (emit-player! emitter {:action    :audio/play-one-shot
                                                             :id        :shutdown-sound
                                                             :item-path sfx})
                                      (emit-system! emitter {:event :system/shutdown})))
      #{:system/shutdown}         (when (= :system-state/cooling-down (system-state!))
                                    (let [operation (:pending-system-operation @state)]
                                      (emit-led! emitter {:action :led/set :groups [:all] :value 0.0})
                                      (set-system-state! :system-state/shutdown
                                                         :pending-system-operation nil)
                                      (when operation
                                        (execute-system-control! settings operation))))
      #{:system/poweroff-now}     (do
                                    (emit-player! emitter {:action :audio/stop})
                                    (emit-led! emitter {:action :led/set :groups [:all] :value 0.0})
                                    (if (poweroff-enabled? settings)
                                      (do
                                        (set-system-state! :system-state/shutdown
                                                           :pending-system-operation nil)
                                        (execute-system-control! settings :system/poweroff))
                                      (log/warn "Ignoring immediate host poweroff" {:reason reason})))
      nil)))

(defn player-handler [{:keys [emitter] :as sys} {:keys [value] :as _ev}]
  (when (and (= :player/state-changed (:event value))
             (= :opening (:state value)))
    (handle-card-opening! sys (:request-id value)))
  (when (#{:system-state/warming-up :system-state/cooling-down}
         (system-state!))
    (when (= :player/one-shot-finished (:event value))
      (condp = (:id value)
        :startup-sound  (emit-system! emitter {:event :system/warmed-up})
        :shutdown-sound (emit-system! emitter {:event :system/shutdown})))))

(def ^:private patch-ports
  {:rfid    {:handler #'rfid-handler
             :name    :rfid
             :path    "/hardware/input/rfid"}
   :buttons {:handler #'button-handler
             :name    :buttons
             :path    "/hardware/input/buttons"}
   :system  {:handler #'system-handler
             :name    :system
             :path    "/system"}
   :player  {:handler #'player-handler
             :name    :player
             :path    "/player/events"}
   :tts     {:handler #'tts-command-handler
             :name    :tts
             :path    "/tts/commands"}})

(defn init-switchboard! [{:keys [bus] :as opts}]
  (reset! state init-state)
  (let [channels (m/map-keys (fn [_] (async/chan)) patch-ports)
        exit-ch  (async/chan)
        emitter  (async/chan)]
    (ev/emitize bus emitter)
    (doseq [[channel {:keys [path]}] channels]
      (ev/listen bus path channel))
    (async/go-loop []
      (let [[event channel] (async/alts! (concat [exit-ch] (keys channels)))]
        (if (= exit-ch channel)
          (do
            (async/close! exit-ch)
            nil)
          (let [{:keys [handler name]} (channels channel)]
            (when event
              (try
                (handler (assoc  opts :emitter emitter) event)
                (catch Exception e
                  (log/error e "Switchboard event handler error" {:event event :handler name}))))
            (recur)))))
    {:channels channels
     :emitter  emitter
     :exit-ch  exit-ch}))

(defn halt-switchboard! [{:keys [channels exit-ch emitter]}]
  (invalidate-card-feedback! emitter false)
  (async/put! exit-ch true)
  (async/close! emitter)
  (doseq [channel (keys channels)]
    (async/close! channel)))

(def SwitchboardComponent
  {:donut.system/start  (fn [{config :donut.system/config}]
                          (init-switchboard! config))
   :donut.system/stop   (fn [{:donut.system/keys [instance]}]
                          (halt-switchboard! instance))
   :donut.system/config {:audio-system [:donut.system/ref
                                        [:fairy.box/components
                                         :fairy.box.audio.system2/player]]
                         :bus          [:donut.system/ref
                                        [:fairy.box/components
                                         :fairy.box.bus/bus]]
                         :settings     [:donut.system/ref
                                        [:fairy.box/components
                                         :fairy.box/settings]]
                         :db-conn      [:donut.system/ref
                                        [:fairy.box/components
                                         :fairy.box.db/db]]}})