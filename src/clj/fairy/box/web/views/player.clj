(ns fairy.box.web.views.player
  (:require
   [fairy.box.audio.current :as player]
   [fairy.box.hardware.buttons :as buttons]
   [fairy.box.hardware.led :as led]
   [fairy.box.switchboard :as switchboard]
   [fairy.box.timers :as timers]
   [fairy.box.web.views.ui :as ui]
   [fairy.box.web.controllers.artwork :as artwork]
   [fairy.box.web.player-progress :as progress]
   [fairy.box.web.views.common :as uic :refer [cs]]
   [fairy.box.web.views.icon :as icon]
   [hyperlith.core :as h :refer [defaction defview]]
   [hyperlith.impl.router :as router]
   [shadow.css :refer [css]]))

(def button-commands
  {"next"             {:action :audio/next}
   "play-pause"       {:action :audio/play-pause}
   "previous"         {:action :audio/prev}
   "skip-back"        {:action :audio/skip-time :milliseconds -10000}
   "skip-forward"     {:action :audio/skip-time :milliseconds 10000}
   "toggle-mute"      {:action :audio/toggle-mute}
   "volume-down-step" {:action :audio/adjust-volume :delta -5}
   "volume-up-step"   {:action :audio/adjust-volume :delta 5}})

(def front-panel-buttons
  [{:button-id :audio/volume-down
    :color     "green"
    :icon      icon/volume-down
    :id        "volume-down"
    :label     "Volume down"
    :signal    "front_panel_volume_down_pressed"}
   {:button-id :audio/prev
    :color     "red"
    :icon      icon/prev
    :id        "previous"
    :label     "Previous"
    :signal    "front_panel_previous_pressed"}
   {:button-id :audio/play-pause
    :color     "orange"
    :icon      (icon/play {})
    :id        "play-pause"
    :label     "Play or pause"
    :signal    "front_panel_play_pause_pressed"}
   {:button-id :audio/next
    :color     "red"
    :icon      icon/skip
    :id        "next"
    :label     "Next"
    :signal    "front_panel_next_pressed"}
   {:button-id :audio/volume-up
    :color     "green"
    :icon      icon/volume-up
    :id        "volume-up"
    :label     "Volume up"
    :signal    "front_panel_volume_up_pressed"}])

(def front-panel-button-ids
  (into {} (map (juxt :id :button-id)) front-panel-buttons))

(def repeat-modes
  {"list"  :list
   "none"  :none
   "track" :track})

(def shuffle-values
  {"false" false
   "true"  true})

(defn- emit-player-command!
  [{:fairy.box/keys [component]} command]
  (when-let [controller
             (when (ifn? component)
               (component :fairy.box.switchboard/switchboard))]
    (switchboard/emit-player! (:emitter controller) command)))

(defn- strict-decimal [value]
  (when (and (string? value)
             (re-matches #"(?:0|[1-9]\d*)(?:\.\d+)?" value))
    (let [number (parse-double value)]
      (when (and number (Double/isFinite number))
        number))))

(defn- strict-integer [value]
  (when (and (string? value)
             (re-matches #"(?:0|[1-9]\d*)" value))
    (parse-long value)))

(defaction control-player
  [{:keys [query-params] :as req}]
  (when-let [command (button-commands (get query-params "command"))]
    (emit-player-command! req command)))

(defn- submit-hardware-button!
  [{:keys [query-params] :fairy.box/keys [component]} operation]
  (when-let [button-id (front-panel-button-ids
                        (get query-params "button"))]
    (when-let [button-component
               (when (ifn? component)
                 (component :fairy.box.hardware/buttons))]
      (operation button-component button-id))))

(defaction press-hardware-button
  [req]
  (submit-hardware-button! req buttons/press!))

(defaction release-hardware-button
  [req]
  (submit-hardware-button! req buttons/release!))

(defaction seek-player
  [{:keys [query-params] :as req}]
  (when-let [percentage (strict-decimal (get query-params "position"))]
    (when (<= 0.0 percentage 100.0)
      (emit-player-command!
       req
       {:action   :audio/set-position
        :position (/ percentage 100.0)}))))

(defaction set-player-volume
  [{:keys [query-params] :as req}]
  (when-let [volume (strict-integer (get query-params "volume"))]
    (when (<= 0 volume 100)
      (emit-player-command!
       req
       {:action :audio/set-volume
        :volume volume}))))

(defaction set-player-repeat
  [{:keys [query-params] :as req}]
  (when-let [mode (repeat-modes (get query-params "mode"))]
    (emit-player-command!
     req
     {:action :audio/set-repeat
      :mode   mode})))

(defaction set-player-shuffle
  [{:keys [query-params] :as req}]
  (let [shuffle (get query-params "shuffle")]
    (when (and (string? shuffle)
               (contains? shuffle-values shuffle))
      (emit-player-command!
       req
       {:action   :audio/set-shuffle
        :shuffle? (shuffle-values shuffle)}))))

(router/add-route! [:get "/api/current-artwork"] #'artwork/current-artwork)

(defn- command-expression [command]
  (str "@post('" control-player
       (h/url-query-string {:command command})
       "')"))

(defn- front-panel-action-expression [action button-id]
  (str "@post('" action
       (h/url-query-string {:button button-id})
       "')"))

(defn- press-expression [{:keys [id signal]}]
  (let [pressed (str "$" signal)]
    (str pressed " ? null : ("
         pressed " = true, "
         (front-panel-action-expression press-hardware-button id)
         ")")))

(defn- release-expression [{:keys [id signal]}]
  (let [pressed (str "$" signal)]
    (str pressed " ? ("
         pressed " = false, "
         (front-panel-action-expression release-hardware-button id)
         ") : null")))

(def front-panel-key-test
  "evt.key == ' ' || evt.key == 'Enter'")

(defn- key-press-expression [button]
  (str "((" front-panel-key-test ") && !evt.repeat) ? "
       "(evt.preventDefault(), "
       (press-expression button)
       ") : null"))

(defn- key-release-expression [button]
  (str "(" front-panel-key-test ") ? "
       "(evt.preventDefault(), "
       (release-expression button)
       ") : null"))

(defn- player-signals [current]
  (let [{:keys [_server_progress _server_time _server_time_left]}
        (progress/progress-signals current)
        volume (or (player/volume current) 0)]
    {:data-signals:progress__ifmissing         _server_progress
     :data-signals:volume__ifmissing           volume
     :data-signals:seeking__ifmissing          "false"
     :data-signals:adjusting_volume__ifmissing "false"
     :data-signals:_server_progress            _server_progress
     :data-signals:_server_time                (h/edn->json _server_time)
     :data-signals:_server_time_left           (h/edn->json _server_time_left)
     :data-signals:_server_volume              volume
     :data-effect
     (str "$progress = $seeking ? $progress : $_server_progress; "
          "$volume = $adjusting_volume ? $volume : $_server_volume")}))

(def range-key-test
  (str "evt.key.startsWith('Arrow') || evt.key == 'Home' || "
       "evt.key == 'End' || evt.key == 'PageUp' || evt.key == 'PageDown'"))

(defn- start-key-interaction-expression [interaction-signal]
  (str interaction-signal " = " range-key-test))

(defn- end-interaction-expression
  [server-signal value-signal interaction-signal]
  (str server-signal " = " value-signal "; " interaction-signal " = false"))

(defn progress-bar [_current-position]
  (let [seek-expression
        (str "@post('" seek-player "?position=' + $progress)")
        end-expression
        (end-interaction-expression
         "$_server_progress" "$progress" "$seeking")]
    [:div {:id    "progress-bar"
           :class "progress-container"}
     [:input {:type :range
              :class (cs "progress-bar")
              :min 0
              :max 100
              :step 1
              :data-on:pointerdown                    "$seeking = true"
              :data-on:pointerup__window              end-expression
              :data-on:pointercancel__window          end-expression
              :data-on:keydown
              (start-key-interaction-expression "$seeking")
              :data-on:keyup end-expression
              :data-on:blur end-expression
              :data-on:input__throttle.100ms.trailing seek-expression
              :data-on:change (str end-expression "; " seek-expression)
              :data-bind "progress"
              :data-style "{'--value': $progress + '%'}"}]]))

(defn the-time [current-time]
  [:div {:id        "current-time"
         :data-text "$_server_time"
         :class     (css :transition-all :duration-500)}
   (progress/time-label (or current-time 0))])

(defn current-artwork [current]
  [:img {:id    "current-artwork"
         :class (css :object-cover :w-64 :h-64)
         :style "transform: translateZ(0)"
         :src   (str "/api/current-artwork?v="
                     (hash (player/display-track current)))}])

(defn current-meta [current]
  (let [{:meta/keys [title album artist]} (:meta (player/display-track current))]
    [:div {:id "current-meta" :class (css :flex :flex-col :gap-y-1)}
     [:div {:class (css :text-center :text-xl :text-smoky-800 :font-bold
                        [:dark :text-white])}
      title]
     [:div {:class (css :text-center :text-lg :text-smoky-800 :font-semibold
                        [:dark :text-gray-400])}
      album]
     [:div {:class (css :text-center :text-sm :text-smoky-800 :font-semibold
                        [:dark :text-gray-400])}
      artist]]))

(defn the-length [duration]
  [:div {:id          "current-length"
         :data-length (str duration)
         :class       (css :transition-all :duration-500 :text-smoky-500
                           [:dark :text-smoky-500])}
   (progress/time-label duration)])

(defn time-left [current-time duration]
  (when duration
    [:div {:id          "current-length"
           :data-length (str duration)
           :data-text   "$_server_time_left"
           :class       (css :transition-all :duration-500 :text-smoky-500
                             [:dark :text-smoky-500])}
     (progress/time-left-label current-time duration)]))

(defn play-pause-button [state]
  (let [button-icon (condp = state
                      :paused :play
                      :stopped :play
                      :playing :pause
                      :opening :pause
                      :finished :play)]
    [:button {:id            "play-pause"
              :data-on:click (command-expression "play-pause")
              :class         (css :transition-all :ease-out :duration-100 :flex-none
                                  :w-20 :h-20 :rounded-full :ring-1 :shadow-md :flex
                                  :items-center :justify-center
                                  :bg-smoky-900 :text-smoky-200 :ring-smoky-900
                                  [:hover-mouse [:hover :scale-110]]
                                  [:pointer-fine [:active :scale-105]]
                                  [:pointer-coarse
                                   [:active :scale-125 :duration-500]]
                                  [:dark :text-smoky-900 :bg-smoky-100
                                   [:pointer-fine [:active :scale-105]]
                                   [:pointer-coarse [:active :scale-125]]])
              :aria-label    "Pause"}
     (button-icon
      {:play
       [:svg {:width   "30"
              :height  "30"
              :fill    "currentColor"
              :xmlns   "http://www.w3.org/2000/svg"
              :viewBox "0 0 24 24"}
        [:path {:fill-rule "evenodd"
                :d         "M4.5 5.653c0-1.426 1.529-2.33 2.779-1.643l11.54 6.348c1.295.712 1.295 2.573 0 3.285L7.28 19.991c-1.25.687-2.779-.217-2.779-1.643V5.653z"
                :clip-rule "evenodd"}]]
       :pause
       [:svg {:width "30" :height "30" :fill "currentColor"}
        [:rect {:x "4" :y "4" :width "8" :height "24" :rx "2"}]
        [:rect {:x "18" :y "4" :width "8" :height "24" :rx "2"}]]})]))

(defn volume-bar [_volume]
  (let [volume-expression
        (str "@post('" set-player-volume "?volume=' + $volume)")
        end-expression
        (end-interaction-expression
         "$_server_volume" "$volume" "$adjusting_volume")]
    [:input {:id "volume-slider"
             :type :range
             :min 0
             :max 100
             :step 1
             :data-on:pointerdown                    "$adjusting_volume = true"
             :data-on:pointerup__window              end-expression
             :data-on:pointercancel__window          end-expression
             :data-on:keydown
             (start-key-interaction-expression "$adjusting_volume")
             :data-on:keyup end-expression
             :data-on:blur end-expression
             :data-on:input__throttle.100ms.trailing volume-expression
             :data-on:change (str end-expression "; " volume-expression)
             :data-bind "volume"
             :name "volume"
             :class (cs
                     "range-sm"
                     (css :w-full :h-1 :rounded-lg :appearance-none
                          :cursor-pointer :bg-smoky-900
                          [:dark :bg-gray-700]))}]))

(defn volume-human [volume]
  (str volume "%"))

(defn volume-icon
  ([volume muted?]
   (when volume
     (let [volume-icon (cond
                         (or (== 0 volume) muted?) :muted
                         (< volume 50) :quiet
                         :else :loud)]
       [:div {:id "volume-icon"}
        (volume-icon
         {:muted [:svg {:width "35" :height "35" :fill "currentColor" :xmlns "http://www.w3.org/2000/svg" :viewBox "0 0 30 30"} [:path {:d "M16.53 5.004v20a1 1 0 0 1-1.53.85l-8-5a3 3 0 0 0-1.47-.38h-4a1 1 0 0 1-1-1v-8.94a1 1 0 0 1 1-1h4a3 3 0 0 0 1.49-.4l8-5a1 1 0 0 1 1.51.87Zm8.41 10 4.29-4.29a1 1 0 0 0-1.41-1.41l-4.29 4.29-4.29-4.29a1 1 0 0 0-1.41 1.41l4.29 4.29-4.29 4.29a1 1 0 1 0 1.41 1.41l4.29-4.29 4.29 4.29a1 1 0 0 0 1.41-1.41z" :data-name "Layer 13"}]]
          :quiet [:svg {:width "35" :height "35" :fill "currentColor" :xmlns "http://www.w3.org/2000/svg" :viewBox "0 0 30 30"} [:path {:d "M20.006 5.004v20a1 1 0 0 1-1.53.85l-8-5a3 3 0 0 0-1.47-.38h-4a1 1 0 0 1-1-1v-8.94a1 1 0 0 1 1-1h4a3 3 0 0 0 1.49-.4l8-5a1 1 0 0 1 1.51.87Zm4.53 15.2a10 10 0 0 0 0-10.4 1 1 0 0 0-1.71 1 8 8 0 0 1 0 8.31 1 1 0 1 0 1.71 1z" :data-name "Layer 14"}]]
          :loud  [:svg {:width "35" :height "35" :fill "currentColor" :xmlns "http://www.w3.org/2000/svg" :viewBox "0 0 30 30"} [:path {:d "M16.019 4.989v20a1 1 0 0 1-1.53.85l-8-5a3 3 0 0 0-1.47-.38h-4a1 1 0 0 1-1-1v-8.94a1 1 0 0 1 1-1h4a3 3 0 0 0 1.49-.4l8-5a1 1 0 0 1 1.51.87Zm10.21 21a18 18 0 0 0 0-22 1 1 0 1 0-1.58 1.2 16 16 0 0 1 0 19.61 1 1 0 1 0 1.58 1.19zm-2.83-2.88a14 14 0 0 0 0-16.31 1.005 1.005 0 0 0-1.62 1.19 12 12 0 0 1 0 14 1.003 1.003 0 0 0 1.63 1.17zm-2.85-3a10 10 0 0 0 0-10.4 1 1 0 0 0-1.71 1 8 8 0 0 1 0 8.31 1 1 0 1 0 1.71 1z" :data-name "Layer 16"}]]})
        [:p (volume-human volume)]]))))

(def ^:private sleep-countdown-expression
  (str "$_sleep_countdown = (() => {"
       "const total = Math.max(0, Math.ceil(("
       "$_sleep_deadline_ms - Date.now()) / 1000));"
       "const seconds = total % 60;"
       "const minutes = Math.floor(total / 60) % 60;"
       "const hours = Math.floor(total / 3600);"
       "const pad = value => String(value).padStart(2, '0');"
       "return (hours > 0 ? hours + ':' : '') + "
       "pad(minutes) + ':' + pad(seconds);"
       "})()"))

(defn- sleep-countdown [{:keys [active? countdown]}]
  (when active?
    [:aside {:id "sleep-countdown"
             :data-on-interval__duration.1s sleep-countdown-expression
             :class (css :mx-auto :mt-5 :flex :w-fit :items-center :gap-3
                         :rounded-full :border :border-cloud-burst-300
                         :bg-cloud-burst-50 :px-4 :py-2 :shadow-sm
                         :text-cloud-burst-950
                         [:dark :border-cloud-burst-700
                          :bg-cloud-burst-950 :text-cloud-burst-100])}
     [:span {:class (css :text-xs :font-semibold :uppercase
                         :tracking-wide)}
      "Sleeping in"]
     [:strong {:data-text "$_sleep_countdown"
               :class     (css :text-sm :tabular-nums)}
      countdown]]))

(defn player [{:keys [url-for current sleep-timer]}]
  (let [ready?                (= (switchboard/system-state!) :system-state/ready)
        track-loaded?         (and (player/mrl current) (player/state current))
        sleep-countdown-state (when sleep-timer
                                (timers/sleep-countdown-state sleep-timer))
        $button-base          (css :transition-all :duration-500
                                   :text-smoky-800
                                   [:hover-mouse [:hover :scale-110]]
                                   [:pointer-fine
                                    [:active :text-smoky-950 :scale-105]]
                                   [:pointer-coarse
                                    [:active :text-smoky-950 :scale-125 :duration-500]]
                                   [:dark :text-smoky-100
                                    [:active :text-smoky-500]])]
    [:div (cond-> {:id "player-controls"}
            track-loaded? (merge (player-signals current))
            sleep-timer
            (merge {:data-signals:_sleep_deadline_ms
                    (:deadline-ms sleep-countdown-state)
                    :data-signals:_sleep_countdown
                    (h/edn->json (:countdown sleep-countdown-state))}))
     (sleep-countdown sleep-countdown-state)
     (if track-loaded?
       [:div {:class (css :mt-6 :mb-10 :flex :flex-col
                          [:lg :flex-row :py-6 :max-w-5xl]
                          :relative :z-10 :rounded-xl :shadow-xl
                          :bg-white-rock-100
                          [:dark :bg-smoky-950])}
        [:div {:class (css :px-6 :flex :flex-col :items-center
                           :justify-center :py-6
                           [:lg :py-0 :w-1of3])}
         (current-artwork current)]
        [:div {:class (css :px-6 :flex :flex-col [:lg :w-half])}
         (current-meta current)
         [:div {:class (css :mt-6 :space-y-2)}
          (progress-bar (player/position current))
          [:div {:class (css :flex :justify-between :text-xs :leading-6
                             :font-medium :tabular-nums :text-smoky-500
                             [:dark :text-smoky-500])}
           (the-time (player/time current))
           (the-length (player/duration current))
           (time-left (player/time current) (player/duration current))]]
         [:div {:class (css :flex :justify-center :transition-all
                            :duration-500 :gap-x-8)}
          [:button {:data-on:click (command-expression "previous")
                    :class         (cs $button-base)
                    :aria-label    "Previous"
                    :title         "Previous"}
           icon/prev]
          [:button {:data-on:click (command-expression "skip-back")
                    :aria-label    "Rewind 10 seconds"
                    :title         "Rewind 10 seconds"
                    :class         (cs $button-base)}
           icon/skip-back]
          (play-pause-button (player/state current))
          [:button {:data-on:click (command-expression "skip-forward")
                    :aria-label    "Skip 10 seconds"
                    :title         "Skip 10 seconds"
                    :class         (cs $button-base)}
           icon/skip-forward]
          [:button {:data-on:click (command-expression "next")
                    :class         (cs $button-base)
                    :aria-label    "Next"
                    :title         "Next"}
           icon/skip]]
         [:div {:class (cs "volume-bar"
                           (css :my-6 :flex :flex-row :items-center
                                :gap-x-4))}
          [:button {:data-on:click (command-expression "toggle-mute")
                    :class         (cs $button-base)}
           (volume-icon (player/volume current) (player/muted? current))]
          (volume-bar (player/volume current))
          [:button {:data-on:click
                    (command-expression "volume-down-step")
                    :class         (cs $button-base)}
           icon/volume-down]
          [:button {:data-on:click
                    (command-expression "volume-up-step")
                    :class         (cs $button-base)}
           icon/volume-up]]]]
       [:div
        [:div {:class (css :flex :justify-center :items-center :mt-10)}
         [:div {:class (css :outline-dotted :p-6 :rounded-lg
                            :outline-smoky-400 :text-smoky-900
                            [:dark :text-smoky-300])}
          [:p {:class (css :text-lg)}
           (if ready?
             "Nothing is playing."
             "Fairybox is getting ready...")]
          (when ready?
            [:p {:class (css :text-sm :mt-2 :underline
                             :decoration-inherit)}
             [:a {:href (url-for :page/settings)}
              "Goto settings"]])]]])]))

(defn player-controls-tab [s]
  [:div {:id "active-tab"}
   [:div {:class "fade-in-out"}
    (player s)]])

(defn- current-front-panel-values
  [{:fairy.box/keys [component]}]
  (or (when (ifn? component)
        (some-> (component :fairy.box.hardware/leds)
                :controller
                led/current-values))
      {}))

(defn- hardware-button [values {:keys [button-id color icon label signal]
                                :as   button}]
  (let [value              (double (get values button-id 0.0))
        release            (release-expression button)
        pressed-signal-key (keyword
                            (str "data-signals:"
                                 signal
                                 "__ifmissing"))]
    [:button
     {pressed-signal-key             "false"
      :type "button"
      :class (str "arcade-button "
                  "arcade-button--" color)
      :style (str "--arcade-led-level: " value)
      :data-led-state                (if (pos? value) "on" "off")
      :data-on:pointerdown           (press-expression button)
      :data-on:pointerup__window     release
      :data-on:pointercancel__window release
      :data-on:keydown               (key-press-expression button)
      :data-on:keyup                 (key-release-expression button)
      :data-on:blur                  release
      :aria-label                    label
      :title label}
     icon]))

(defn hardware-buttons
  ([]
   (hardware-buttons {}))
  ([req]
   (let [values (current-front-panel-values req)]
     [:section.arcade-button-panel
      {:id "hardware-buttons" :aria-label "Fairybox hardware buttons"}
      (into [:div.arcade-button-row]
            (map (partial hardware-button values) front-panel-buttons))])))

(defn render [_req])

(defview render-home {:path "/" :shim-headers ui/shim-headers}
  [req]
  (let [component (:fairy.box/component req)
        req       (assoc req
                         :current (player/current!)
                         :sleep-timer
                         (when (ifn? component)
                           (component :fairy.box.sleep/timer)))]
    (h/html
     (ui/css-reload)
     [:main#morph.main
      [:div {}
       (uic/player-tabs req :page/controls)
       (player-controls-tab req)]
      (hardware-buttons req)])))

(h/refresh-all!)
