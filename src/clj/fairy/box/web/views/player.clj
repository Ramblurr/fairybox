(ns fairy.box.web.views.player
  (:require
   [fairy.box.audio.current :as player]
   [fairy.box.switchboard :as switchboard]
   [fairy.box.ui3 :as ui3]
   [fairy.box.web.controllers.artwork :as artwork]
   [fairy.box.web.views.common :as uic :refer [cs]]
   [fairy.box.web.views.icon :as icon]
   [hyperlith.core :as h :refer [defaction defview]]
   [hyperlith.impl.router :as router]
   [shadow.css :refer [css]]))

(def button-commands
  {"next" {:action :audio/next}
   "play-pause" {:action :audio/play-pause}
   "previous" {:action :audio/prev}
   "skip-back" {:action :audio/skip-time :milliseconds -10000}
   "skip-forward" {:action :audio/skip-time :milliseconds 10000}
   "toggle-mute" {:action :audio/toggle-mute}
   "volume-down-step" {:action :audio/adjust-volume :delta -5}
   "volume-up-step" {:action :audio/adjust-volume :delta 5}})

(def repeat-modes
  {"list" :list
   "none" :none
   "track" :track})

(def shuffle-values
  {"false" false
   "true" true})

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

(defaction seek-player
  [{:keys [query-params] :as req}]
  (when-let [percentage (strict-decimal (get query-params "position"))]
    (when (<= 0.0 percentage 100.0)
      (emit-player-command!
       req
       {:action :audio/set-position
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
      :mode mode})))

(defaction set-player-shuffle
  [{:keys [query-params] :as req}]
  (let [shuffle (get query-params "shuffle")]
    (when (and (string? shuffle)
               (contains? shuffle-values shuffle))
      (emit-player-command!
       req
       {:action :audio/set-shuffle
        :shuffle? (shuffle-values shuffle)}))))

(router/add-route! [:get "/api/current-artwork"] #'artwork/current-artwork)

(defn duration-data
  [^long duration-in-millis]
  (let [milliseconds (mod duration-in-millis 1000)
        duration-in-secs (quot duration-in-millis 1000)
        seconds (mod duration-in-secs 60)
        duration-in-mins (quot duration-in-secs 60)
        minutes (mod duration-in-mins 60)
        duration-in-hours (quot duration-in-mins 60)
        hours (mod duration-in-hours 24)
        days (quot duration-in-hours 24)]
    {:milliseconds milliseconds
     :seconds seconds
     :minutes minutes
     :hours hours
     :days days}))

(defn format-duration [milliseconds]
  (when milliseconds
    (let [{:keys [days hours minutes seconds milliseconds]}
          (duration-data milliseconds)
          rounded-seconds (if (> milliseconds 0)
                            (inc seconds)
                            seconds)]
      (str (when (> days 0) (format "%02dd " days))
           (when (> hours 0) (format "%02d:" hours))
           (format "%02d" minutes)
           ":"
           (format "%02d" rounded-seconds)))))

(defn- progress-percentage [current-position]
  (if (number? current-position)
    (-> (* 100.0 current-position)
        (max 0.0)
        (min 100.0))
    0.0))

(defn- command-expression [command]
  (str "@post('" control-player
       (h/url-query-string {:command command})
       "')"))

(defn- player-signals [current]
  (let [progress (progress-percentage (player/position current))
        volume (or (player/volume current) 0)]
    {:data-signals:progress__ifmissing progress
     :data-signals:volume__ifmissing volume
     :data-signals:seeking__ifmissing "false"
     :data-signals:adjusting_volume__ifmissing "false"
     :data-signals:_server_progress progress
     :data-signals:_server_volume volume
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
    [:div {:id "progress-bar"
           :class "progress-container"}
     [:input {:type :range
              :class (cs "progress-bar")
              :min 0
              :max 100
              :step 1
              :data-on:pointerdown "$seeking = true"
              :data-on:pointerup__window end-expression
              :data-on:pointercancel__window end-expression
              :data-on:keydown
              (start-key-interaction-expression "$seeking")
              :data-on:keyup end-expression
              :data-on:blur end-expression
              :data-on:input__throttle.100ms.trailing seek-expression
              :data-on:change (str end-expression "; " seek-expression)
              :data-bind "progress"
              :data-style "{'--value': $progress + '%'}"}]]))

(defn the-time [current-time]
  [:div {:id "current-time"
         :class (css :transition-all :duration-500)}
   (format-duration (or current-time 0))])

(defn current-artwork [current]
  [:img {:id "current-artwork"
         :class (css :object-cover :w-64 :h-64)
         :style "transform: translateZ(0)"
         :src (str "/api/current-artwork?v="
                   (hash (get-in current [:playback :current-track])))}])

(defn current-meta [c]
  [:div {:id "current-meta" :class (css :flex :flex-col :gap-y-1)}
   [:div {:class (css :text-center :text-xl :text-smoky-800 :font-bold
                      [:dark :text-white])}
    (player/track-title c)]
   [:div {:class (css :text-center :text-lg :text-smoky-800 :font-semibold
                      [:dark :text-gray-400])}
    (player/album c)]
   [:div {:class (css :text-center :text-sm :text-smoky-800 :font-semibold
                      [:dark :text-gray-400])}
    (player/artist c)]])

(defn the-length [duration]
  [:div {:id "current-length"
         :data-length (str duration)
         :class (css :transition-all :duration-500 :text-smoky-500
                     [:dark :text-smoky-500])}
   (format-duration duration)])

(defn time-left [current-time duration]
  (when duration
    [:div {:id "current-length"
           :data-length (str duration)
           :class (css :transition-all :duration-500 :text-smoky-500
                       [:dark :text-smoky-500])}
     (if (not current-time)
       (format-duration duration)
       (str "-" (format-duration (- duration current-time))))]))

(defn play-pause-button [state]
  (let [button-icon (condp = state
                      :paused :play
                      :stopped :play
                      :playing :pause
                      :opening :pause
                      :finished :play)]
    [:button {:id "play-pause"
              :data-on:click (command-expression "play-pause")
              :class (css :transition-all :ease-out :duration-100 :flex-none
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
              :aria-label "Pause"}
     (button-icon
      {:play
       [:svg {:width "30"
              :height "30"
              :fill "currentColor"
              :xmlns "http://www.w3.org/2000/svg"
              :viewBox "0 0 24 24"}
        [:path {:fill-rule "evenodd"
                :d "M4.5 5.653c0-1.426 1.529-2.33 2.779-1.643l11.54 6.348c1.295.712 1.295 2.573 0 3.285L7.28 19.991c-1.25.687-2.779-.217-2.779-1.643V5.653z"
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
             :data-on:pointerdown "$adjusting_volume = true"
             :data-on:pointerup__window end-expression
             :data-on:pointercancel__window end-expression
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
          :loud [:svg {:width "35" :height "35" :fill "currentColor" :xmlns "http://www.w3.org/2000/svg" :viewBox "0 0 30 30"} [:path {:d "M16.019 4.989v20a1 1 0 0 1-1.53.85l-8-5a3 3 0 0 0-1.47-.38h-4a1 1 0 0 1-1-1v-8.94a1 1 0 0 1 1-1h4a3 3 0 0 0 1.49-.4l8-5a1 1 0 0 1 1.51.87Zm10.21 21a18 18 0 0 0 0-22 1 1 0 1 0-1.58 1.2 16 16 0 0 1 0 19.61 1 1 0 1 0 1.58 1.19zm-2.83-2.88a14 14 0 0 0 0-16.31 1.005 1.005 0 0 0-1.62 1.19 12 12 0 0 1 0 14 1.003 1.003 0 0 0 1.63 1.17zm-2.85-3a10 10 0 0 0 0-10.4 1 1 0 0 0-1.71 1 8 8 0 0 1 0 8.31 1 1 0 1 0 1.71 1z" :data-name "Layer 16"}]]})
        [:p (volume-human volume)]]))))

(defn player [{:keys [url-for current]}]
  (let [ready? (= (switchboard/system-state!) :system-state/ready)
        track-loaded? (and (player/mrl current) (player/state current))
        $button-base (css :transition-all :duration-500
                          :text-smoky-800
                          [:hover-mouse [:hover :scale-110]]
                          [:pointer-fine
                           [:active :text-smoky-950 :scale-105]]
                          [:pointer-coarse
                           [:active :text-smoky-950 :scale-125 :duration-500]]
                          [:dark :text-smoky-100
                           [:active :text-smoky-500]])]
    [:div (cond-> {:id "player-controls"}
            track-loaded? (merge (player-signals current)))
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
                    :class (cs $button-base)
                    :aria-label "Previous"
                    :title "Previous"}
           icon/prev]
          [:button {:data-on:click (command-expression "skip-back")
                    :aria-label "Rewind 10 seconds"
                    :title "Rewind 10 seconds"
                    :class (cs $button-base)}
           icon/skip-back]
          (play-pause-button (player/state current))
          [:button {:data-on:click (command-expression "skip-forward")
                    :aria-label "Skip 10 seconds"
                    :title "Skip 10 seconds"
                    :class (cs $button-base)}
           icon/skip-forward]
          [:button {:data-on:click (command-expression "next")
                    :class (cs $button-base)
                    :aria-label "Next"
                    :title "Next"}
           icon/skip]]
         [:div {:class (cs "volume-bar"
                           (css :my-6 :flex :flex-row :items-center
                                :gap-x-4))}
          [:button {:data-on:click (command-expression "toggle-mute")
                    :class (cs $button-base)}
           (volume-icon (player/volume current) (player/muted? current))]
          (volume-bar (player/volume current))
          [:button {:data-on:click
                    (command-expression "volume-down-step")
                    :class (cs $button-base)}
           icon/volume-down]
          [:button {:data-on:click
                    (command-expression "volume-up-step")
                    :class (cs $button-base)}
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

(defn render [_req])

(defview render-home {:path "/" :shim-headers ui3/shim-headers}
  [req]
  (let [req (assoc req :current (player/current!))]
    (h/html
     (ui3/css-reload)
     [:main#morph.main
      [:div {}
       (uic/player-tabs req :page/controls)
       (player-controls-tab req)]])))

(h/refresh-all!)
