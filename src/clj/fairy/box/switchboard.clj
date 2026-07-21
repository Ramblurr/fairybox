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

(def ^:private init-state {:system-state    :system-state/booting
                           :system-mode     :system-mode/normal
                           :rfid            nil
                           :active-card-uid nil
                           :removed-card    nil})
(defonce ^:private state (atom init-state))

(defn system-state! []
  (:system-state @state))

(defn emit-system! [emitter event]
  (async/put! emitter {:path "/system" :value event}))

(defn emit-player! [emitter event]
  (async/put! emitter {:path "/player/commands" :value event}))

(defn emit-tts! [emitter event]
  (async/put! emitter {:path "/tts/commands" :value event}))

(defn emit-led! [emitter event]
  (async/put! emitter {:path "/hardware/output/leds" :value event}))

(defn change-mode! [sys new-mode]
  (emit-player! (:emitter sys) {:action :audio/clear})
  (swap! state (fn [s]
                 (-> s
                     (assoc :system-mode new-mode)
                     (assoc :rfid nil)
                     (assoc :active-card-uid nil)
                     (assoc :removed-card nil)))))

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

(defn speak-card-contents [{:keys [emitter] :as sys} item-path]
  (let [metadata (audio/metadata-for sys item-path)
        text     (tts/metadata->ssml metadata)]
    #_(tap> [:card-contents metadata text])
    (emit-tts! emitter {:action :tts/speak :text text})))

(defn rfid-placed-card-id-mode [{:keys [emitter db-conn settings] :as sys} {:keys [uid]}]
  (emit-led! emitter {:action :led/pulse :names [:audio/volume-up :audio/volume-down] :after-set 0.0 :repeat-times 2})
  (if-let [item-path (browse/absoluteify settings (db/linked-folder @db-conn uid))]
    (speak-card-contents sys item-path)
    (emit-tts! emitter {:action              :tts/speak
                        :audio/play-one-shot false
                        :text                "This one is empty."})))

(defn rfid-placed-play-mode
  [{:keys [emitter db-conn settings]} {:keys [uid]}]
  (let [database  @db-conn
        item-path (browse/absoluteify settings (db/linked-folder database uid))]
    (if item-path
      (let [{removed-uid      :uid
             removal-behavior :removal-behavior} (:removed-card @state)
            returning-card? (= uid removed-uid)]
        (emit-led! emitter {:action       :led/pulse
                            :names        [:audio/volume-up :audio/volume-down]
                            :after-set    1.0
                            :repeat-times 2})
        (cond
          (and returning-card?
               (= :keep-playing removal-behavior))
          nil

          (and returning-card?
               (= :resume (db/card-return-behavior database)))
          (emit-player! emitter {:action :audio/play})

          :else
          (emit-player! emitter {:action              :audio/play-path
                                 :announce-per-track? nil
                                 :item-path           item-path
                                 :uid                 uid}))
        (swap! state assoc
               :active-card-uid uid
               :removed-card nil))
      (do
        (swap! state assoc
               :active-card-uid nil
               :removed-card nil)
        (emit-led! emitter {:action       :led/pulse
                            :names        [:audio/prev :audio/next]
                            :after-set    1.0
                            :repeat-times 2})))))

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
  (when (= :system-state/ready (system-state!))
    (swap! state assoc :rfid value)
    (cap-volume! emitter)
    (condp = (:action value)
      :placed (condp = (:system-mode @state)
                :system-mode/normal (rfid-placed-play-mode sys value)
                :system-mode/card-identification (rfid-placed-card-id-mode sys value))
      :removed (when (= :system-mode/normal (:system-mode @state))
                 (rfid-removed-play-mode sys value))
      :error (do
               (log/error "RFID error" (:error value))
               (emit-led! emitter {:action :led/pulse :names [:audio/play-pause :audio/prev :audio/next :audio/volume-up :audio/volume-down] :after-set 0.0 :repeat-times 9})))))

(defn initiate-shutdown!
  ([emitter]
   (initiate-shutdown! emitter true))
  ([emitter poweroff?]
   (emit-system! emitter {:event :system/cooling-down :poweroff? poweroff?})))

(defn poweroff-enabled? [settings]
  (true? (get-in settings [:shutdown :poweroff-enabled?])))

(defn poweroff-host! [settings]
  (if (poweroff-enabled? settings)
    (shell/sh "systemctl" "poweroff")
    (do
      (log/warn "Skipping host poweroff because it is disabled for this profile")
      nil)))

(defn system-handler [{:keys [emitter settings]} {:keys [value] :as _ev}]
  ;; (tap> {:system ev})
  (let [{:keys [event poweroff? reason]} value]
    (condp = event
      :system/initialized  (do
                             (swap! state assoc :system-state :system-state/initialized)
                             (emit-system! emitter {:event :system/warming-up}))
      :system/warming-up   (do
                             (swap! state assoc :system-state :system-state/warming-up)
                             (emit-led! emitter {:action :led/set :groups [:all] :value 1.0})
                             (when-let [sfx (browse/sfx-path settings :startup)]
                               (emit-player! emitter {:action :audio/play-one-shot :id :startup-sound :item-path sfx})))
      :system/warmed-up    (when (= :system-state/warming-up (system-state!))
                             (swap! state assoc :system-state :system-state/ready)
                             (emit-system! emitter {:event :system/ready}))
      :system/cooling-down (when  (= :system-state/ready (system-state!))
                             (swap! state assoc :system-state :system-state/cooling-down)
                             (emit-player! emitter {:action :audio/stop})
                             (emit-led! emitter {:action :led/fade :groups [:all] :duration 3000 :from 1.0 :to 0.0 :after-set 0.0 :start-delay 14000})
                             (when-let [sfx (browse/sfx-path settings :shutdown)]
                               (emit-player! emitter {:action :audio/play-one-shot :id (if poweroff? :shutdown-sound :shutdown-sound-no-poweroff) :item-path sfx})))
      :system/shutdown     (when (= :system-state/cooling-down (system-state!))
                             (emit-led! emitter {:action :led/set :groups [:all] :value 0.0})
                             (swap! state assoc :system-state :system-state/shutdown)
                             (when poweroff?
                               (poweroff-host! settings)))
      :system/poweroff-now (do
                             (emit-player! emitter {:action :audio/stop})
                             (emit-led! emitter {:action :led/set :groups [:all] :value 0.0})
                             (if (poweroff-enabled? settings)
                               (do
                                 (swap! state assoc :system-state :system-state/shutdown)
                                 (poweroff-host! settings))
                               (log/warn "Ignoring immediate host poweroff" {:reason reason})))
      nil)))

(defn player-handler [{:keys [emitter]} {:keys [value] :as _ev}]
  (when (#{:system-state/warming-up :system-state/cooling-down} (system-state!))
    ;; (prn "player-handler " ev)
    (when (= :player/one-shot-finished (:event value))
      (condp = (:id value)
        :startup-sound  (emit-system! emitter {:event :system/warmed-up})
        :shutdown-sound-no-poweroff (emit-system! emitter {:event :system/shutdown :poweroff? false})
        :shutdown-sound (emit-system! emitter {:event :system/shutdown :poweroff? true})))))

(def ^:private patch-ports {:rfid    {:handler #'rfid-handler
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
                                      :path    "/player/events"}})

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
  (async/put! exit-ch true)
  (async/close! emitter)
  (doseq [channel (keys channels)] (async/close! channel)))

(def SwitchboardComponent
  {:donut.system/start  (fn [{config :donut.system/config}]
                          (init-switchboard! config))
   :donut.system/stop   (fn [{:donut.system/keys [instance]}]
                          (halt-switchboard! instance))
   :donut.system/config {:bus      [:donut.system/ref [:fairy.box/components :fairy.box.bus/bus]]
                         :settings [:donut.system/ref [:fairy.box/components :fairy.box/settings]]
                         :db-conn  [:donut.system/ref [:fairy.box/components :fairy.box.db/db]]}})
