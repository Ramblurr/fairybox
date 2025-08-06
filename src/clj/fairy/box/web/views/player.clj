(ns fairy.box.web.views.player
  (:require
   [fairy.box.audio.current :as player]
   [fairy.box.switchboard :as switchboard]
   [fairy.box.web.views.common :as uic :refer [cs]]
   [fairy.box.web.views.icon :as icon]
   [hifi.datastar :as datastar]
   [hifi.html :as html]
   [shadow.css :refer (css)]))

(defn duration-data
  [^long duration-in-millis]
  (let [milliseconds (mod duration-in-millis 1000),
        duration-in-secs (quot duration-in-millis 1000),
        seconds (mod duration-in-secs 60),
        duration-in-mins (quot duration-in-secs 60),
        minutes (mod duration-in-mins 60),
        duration-in-hours (quot duration-in-mins 60),
        hours (mod duration-in-hours 24),
        days (quot duration-in-hours 24)]
    {:milliseconds milliseconds,
     :seconds seconds,
     :minutes minutes,
     :hours hours,
     :days days}))

(defn format-duration [milliseconds]
  (when milliseconds
    (let [{:keys [days hours minutes seconds milliseconds]} (duration-data milliseconds)
          rounded-seconds (if (> milliseconds 0)
                            (inc seconds)
                            seconds)]
      (str (when (> days 0) (format "%02dd " days)) (when (> hours 0) (format "%02d:" hours)) (format "%02d" minutes) ":" (format "%02d" rounded-seconds)))))

(defn progress-bar [current-position]
  (let [dur-str (if (float? current-position) (format "%.2f" (* 100 current-position)) "0")]
    [:div {:id "progress-bar" :class "progress-container"
           :data-signals-progress dur-str}
     [:input {:type :range :class (cs "progress-bar") :min 0 :max 100 :step 1
              :data-on-change (uic/player-cmd "set-position" :position "$progress")
              :data-bind "progress"
              :data-style "{'--value': $progress + '%'}"}]]))

(defn the-time [current-time]
  [:div {:id "current-time" :class (css :transition-all :duration-500)}
   (format-duration (or current-time 0))])

(defn current-artwork []
  [:img {:id "current-artwork" :class (css :object-cover :w-64 :h-64)
         :style "transform: translateZ(0)"
         :src "/api/current-artwork"}])

(defn current-meta [c]
  [:div {:id "current-meta" :class (css  :flex :flex-col :gap-y-1)}
   [:div {:class (css :text-center :text-xl :text-smoky-800 :font-bold [:dark :text-white])} (player/track-title c)]
   [:div {:class (css :text-center :text-lg :text-smoky-800 :font-semibold [:dark :text-gray-400])} (player/album c)]
   [:div {:class (css :text-center :text-sm :text-smoky-800 :font-semibold [:dark :text-gray-400])} (player/artist c)]])

(defn the-length [duration]
  [:div {:id "current-length" :data-length (str duration) :class (css :transition-all :duration-500  :text-smoky-500 [:dark :text-smoky-500])}
   (format-duration duration)])

(defn time-left [current-time duration]
  (when duration
    [:div {:id "current-length" :data-length (str duration) :class (css :transition-all :duration-500  :text-smoky-500 [:dark :text-smoky-500])}
     (if (not current-time)
       (format-duration duration)
       (str "-" (format-duration (- duration current-time))))]))

(defn play-pause-button [state]
  (let [icon (condp = state
               :paused :play
               :stopped :play
               :playing :pause
               :opening :pause
               :finished :play)]
    [:button {:id "play-pause"
              :data-on-click (uic/player-cmd "play-pause")
              :class (css :transition-all :ease-out :duration-100 :flex-none
                          :w-20 :h-20 :rounded-full :ring-1  :shadow-md :flex :items-center :justify-center
                          ;; :bg-white
                          :bg-smoky-900 :text-smoky-200 :ring-smoky-900
                          [:hover-mouse [:hover :scale-110]]
                          [:pointer-fine [:active :scale-105]]
                          [:pointer-coarse [:active :scale-125 :duration-500]]
                          [:dark :text-smoky-900 :bg-smoky-100
                           [:pointer-fine [:active :scale-105]]
                           [:pointer-coarse [:active :scale-125]]])

              :aria-label "Pause"}
     (icon {:play
            [:svg {:width "30" :height "30" :fill "currentColor" :xmlns "http://www.w3.org/2000/svg" :viewBox "0 0 24 24"} [:path {:fill-rule "evenodd" :d "M4.5 5.653c0-1.426 1.529-2.33 2.779-1.643l11.54 6.348c1.295.712 1.295 2.573 0 3.285L7.28 19.991c-1.25.687-2.779-.217-2.779-1.643V5.653z" :clip-rule "evenodd"}]]
            :pause
            [:svg {:width "30", :height "30", :fill "currentColor"}
             [:rect {:x "4", :y "4", :width "8", :height "24", :rx "2"}]
             [:rect {:x "18", :y "4", :width "8", :height "24", :rx "2"}]]})]))

(defn volume-bar [volume]
  [:input {:id "volume-slider" :type :range
           :min 0 :max 100 :step 1
           :data-on-change (uic/player-cmd "set-volume" :volume "evt.target.value")
           :name "volume"
           :value volume
           :class (uic/cs "range-sm" (css :w-full :h-1 :rounded-lg  :appearance-none :cursor-pointer
                                          :bg-smoky-900 [:dark :bg-gray-700]))}])

(defn volume-human [volume]
  (str volume "%"))

(defn volume-icon
  ([volume muted?]
   (when volume
     (let [icon (cond
                  (or (== 0 volume) muted?) :muted
                  (< volume 50) :quiet
                  :else :loud)]
       [:div {:id "volume-icon"}
        (icon
         {:muted [:svg {:width "35" :height "35" :fill "currentColor" :xmlns "http://www.w3.org/2000/svg", :viewBox "0 0 30 30"} [:path {:d "M16.53 5.004v20a1 1 0 0 1-1.53.85l-8-5a3 3 0 0 0-1.47-.38h-4a1 1 0 0 1-1-1v-8.94a1 1 0 0 1 1-1h4a3 3 0 0 0 1.49-.4l8-5a1 1 0 0 1 1.51.87Zm8.41 10 4.29-4.29a1 1 0 0 0-1.41-1.41l-4.29 4.29-4.29-4.29a1 1 0 0 0-1.41 1.41l4.29 4.29-4.29 4.29a1 1 0 1 0 1.41 1.41l4.29-4.29 4.29 4.29a1 1 0 0 0 1.41-1.41z", :data-name "Layer 13"}]]
          :quiet [:svg {:width "35" :height "35" :fill "currentColor" :xmlns "http://www.w3.org/2000/svg", :viewBox "0 0 30 30"} [:path {:d "M20.006 5.004v20a1 1 0 0 1-1.53.85l-8-5a3 3 0 0 0-1.47-.38h-4a1 1 0 0 1-1-1v-8.94a1 1 0 0 1 1-1h4a3 3 0 0 0 1.49-.4l8-5a1 1 0 0 1 1.51.87Zm4.53 15.2a10 10 0 0 0 0-10.4 1 1 0 0 0-1.71 1 8 8 0 0 1 0 8.31 1 1 0 1 0 1.71 1z", :data-name "Layer 14"}]]
          :loud [:svg {:width "35" :height "35" :fill "currentColor" :xmlns "http://www.w3.org/2000/svg", :viewBox "0 0 30 30"} [:path {:d "M16.019 4.989v20a1 1 0 0 1-1.53.85l-8-5a3 3 0 0 0-1.47-.38h-4a1 1 0 0 1-1-1v-8.94a1 1 0 0 1 1-1h4a3 3 0 0 0 1.49-.4l8-5a1 1 0 0 1 1.51.87Zm10.21 21a18 18 0 0 0 0-22 1 1 0 1 0-1.58 1.2 16 16 0 0 1 0 19.61 1 1 0 1 0 1.58 1.19zm-2.83-2.88a14 14 0 0 0 0-16.31 1.005 1.005 0 0 0-1.62 1.19 12 12 0 0 1 0 14 1.003 1.003 0 0 0 1.63 1.17zm-2.85-3a10 10 0 0 0 0-10.4 1 1 0 0 0-1.71 1 8 8 0 0 1 0 8.31 1 1 0 1 0 1.71 1z", :data-name "Layer 16"}]]})
        [:p (volume-human volume)]]))))

(defn player [{:keys [url-for current]}]
  (let [ready? (= (switchboard/system-state!) :system-state/ready)
        $button-base (css :transition-all :duration-500
                          :text-smoky-800
                          [:hover-mouse [:hover :scale-110]]
                          [:pointer-fine [:active :text-smoky-950 :scale-105]]
                          [:pointer-coarse [:active :text-smoky-950 :scale-125 :duration-500]]
                          [:dark :text-smoky-100 [:active :text-smoky-500]])]
    [:div {:id "player-controls"}
     (if (player/playing? current)
       [:div {:class (css :mt-6 :mb-10 :flex :flex-col
                          [:lg :flex-row :py-6 :max-w-5xl]
                          :relative :z-10 :rounded-xl :shadow-xl
                          :bg-white-rock-100
                          [:dark :bg-smoky-950])}
       ;; cover wrapper
        [:div {:class (css :px-6 :flex :flex-col :items-center :justify-center
                           :py-6
                           [:lg :py-0 :w-1of3])}
         (current-artwork)]
       ;; 2nd col
        [:div {:class (css :px-6 :flex :flex-col [:lg :w-half])}
        ;; meta wrapper
         (current-meta current)
        ;; progress bar
         [:div {:class (css :mt-6 :space-y-2)}
          (progress-bar (player/position current))
          [:div {:class (css :flex :justify-between :text-xs :leading-6 :font-medium :tabular-nums :text-smoky-500 [:dark :text-smoky-500])}
           (the-time (player/time current))
           (the-length (player/duration current))
           (time-left (player/time current) (player/duration current))]]
        ;; button wrapper
         [:div {:class (css :flex :justify-center :transition-all :duration-500 :gap-x-8)}
         ;; previous
          [:button {:data-on-click (uic/player-cmd "previous")
                    :class (cs $button-base) :aria-label "Previous" :title "Previous"}
           icon/prev]
         ;; skip back
          [:button {:data-on-click (uic/player-cmd "skip-back") :aria-label "Rewind 10 seconds" :title "Rewind 10 seconds"
                    :class (cs $button-base)}
           icon/skip-back]
         ;; play/pause
          (play-pause-button (player/state current))
          [:button {:data-on-click (uic/player-cmd "skip-forward") :aria-label "Skip 10 seconds" :title "Skip 10 seconds"
                    :class (cs $button-base)}
           icon/skip-forward]

          [:button {:data-on-click (uic/player-cmd "next") :class (cs $button-base) :aria-label "Next" :title "Next"}
           icon/next]]
        ;; volume slider wrapper
         [:div {:class (cs "volume-bar" (css :my-6 :flex :flex-row :items-center :gap-x-4))}
          [:button {:data-on-click (uic/player-cmd "toggle-mute") :class (cs $button-base)} (volume-icon (player/volume current) (player/muted? current))]
          (volume-bar (player/volume current))
          [:button {:data-on-click (uic/player-cmd "volume-down-step") :class (cs $button-base)}
           icon/volume-down]
          [:button {:data-on-click (uic/player-cmd "volume-up-step") :class (cs $button-base)}
           icon/volume-up]]]]
       [:div
        [:div {:class (css :flex :justify-center :items-center :mt-10)}
         [:div {:class (css :outline-dotted :p-6 :rounded-lg :outline-smoky-400 :text-smoky-900 [:dark :text-smoky-300])}
          [:p {:class (css :text-lg)}
           (if ready?
             "Nothing is playing."
             "Fairybox is getting ready...")]
          (when ready?
            [:p {:class (css :text-sm :mt-2 :underline :decoration-inherit)}
             [:a {:href (url-for :page/settings)}
              "Goto settings"]])]]])]))

(defn player-controls-tab [s]
  [:div {:id "active-tab"}
   [:div {:class "fade-in-out"}
    (player s)]])

(defn render [req]
  (let [req  (assoc req :current (player/current!))]
    (html/->str
     [:main#morph.main
      [:div
       (uic/player-tabs req :page/controls)
       (player-controls-tab req)]])))

(datastar/rerender-all!)
