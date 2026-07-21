(ns fairy.box.web.views.settings
  (:require
   [babashka.fs :as fs]
   [fairy.box.audio.browse :as browse]
   [fairy.box.db :as db]
   [fairy.box.settings :as app-settings]
   [fairy.box.switchboard :as switchboard]
   [fairy.box.timers :as timers]
   [fairy.box.util :as util]
   [fairy.box.web.refresh :as refresh]
   [fairy.box.web.views.common :as uic]
   [fairy.box.web.views.icon :as icon]
   [fairy.box.web.views.ui :as ui]
   [hyperlith.core :as h :refer [defaction defview]]
   [shadow.css :refer [css]]))

(defaction play-audio-path [{:fairy.box/keys [component] :as req}]
  (let [selected-path (get-in req [:query-params "path"])
        settings      (app-settings/settings req)
        controller    (component :fairy.box.switchboard/switchboard)]
    (when (and controller (seq selected-path))
      (when-let [canonical-path
                 (browse/canonicalize-path settings selected-path)]
        (when (browse/playable-type settings canonical-path)
          (switchboard/emit-player!
           (:emitter controller)
           {:action    :audio/play-path
            :item-path canonical-path
            :uid       nil})
          (h/execute-expr
           (format "window.location.assign('%s')"
                   ((:url-for req) :page/home))))))))

(defn- announcement-path? [settings path]
  (let [{:keys [dir? media-file? playlist-file?]}
        (browse/dir-item (fs/path (browse/media-dir settings))
                         (fs/file path))]
    (or dir? media-file? playlist-file?)))

(defaction cycle-path-announcement
  [{:keys [query-params] :fairy.box/keys [component] :as req}]
  (let [selected-path (get query-params "path")
        settings      (app-settings/settings req)
        db-conn       (component :fairy.box.db/db)]
    (when (and db-conn (seq selected-path))
      (when-let [canonical-path (browse/canonicalize-path settings
                                                          selected-path)]
        (when (announcement-path? settings canonical-path)
          (db/cycle-announcement! {:db-conn db-conn :settings settings}
                                  canonical-path)))))
  nil)

(defaction link-rfid-folder [{:fairy.box/keys [component] :as req}]
  (let [presence        (component :fairy.box.web/refresh)
        uid             (refresh/current-uid presence)
        selected-folder (get-in req [:body :selected_folder])
        settings        (app-settings/settings req)
        db-conn         (component :fairy.box.db/db)]
    (when (and uid (seq selected-folder))
      (when-let [canonical-path
                 (browse/canonicalize-path settings selected-folder)]
        (when (browse/playable-type settings canonical-path)
          (db/link-rfid-tag! db-conn
                             uid
                             (browse/media-relative-path settings
                                                         canonical-path)))))))

(def ^:private device-number-fields
  {:min_volume               :min-volume
   :max_volume               :max-volume
   :max_volume_day           :max-volume-day
   :max_volume_night         :max-volume-night
   :max_led_brightness_day   :max-led-brightness-day
   :max_led_brightness_night :max-led-brightness-night})

(defn- device-settings-update [body]
  (let [numbers               (reduce-kv (fn [update input-key setting-key]
                                           (assoc update
                                                  setting-key
                                                  (get body input-key)))
                                         {}
                                         device-number-fields)
        day-start             (:day_start body)
        night-start           (:night_start body)
        card-removal-behavior (keyword (:card_removal_behavior body))
        card-return-behavior  (keyword (:card_return_behavior body))
        sleep-keys-present?   (or (contains? body :sleep_shutdown)
                                  (contains? body
                                             :sleep_shutdown_delay_minutes))
        sleep-update          (when sleep-keys-present?
                                {:shutdown?
                                 (:sleep_shutdown body)
                                 :shutdown-delay-minutes
                                 (:sleep_shutdown_delay_minutes body)})]
    (when (and (every? (fn [[_ value]]
                         (and (integer? value)
                              (<= 0 value 100)))
                       numbers)
               (util/valid-wall-clock-time? day-start)
               (util/valid-wall-clock-time? night-start)
               (or (not sleep-keys-present?)
                   (and (boolean? (:shutdown? sleep-update))
                        (integer? (:shutdown-delay-minutes sleep-update))
                        (<= 0 (:shutdown-delay-minutes sleep-update)))))
      {:audio (assoc numbers
                     :day-start day-start
                     :night-start night-start
                     :card-removal-behavior card-removal-behavior
                     :card-return-behavior card-return-behavior)
       :sleep sleep-update})))

(defaction save-device-settings
  [{:fairy.box/keys [component] :as request}]
  (when-let [{:keys [audio sleep]}
             (device-settings-update (:body request))]
    (swap! (component :fairy.box.db/db)
           (fn [database]
             (cond-> (update-in database [:settings :audio] merge audio)
               sleep (update-in [:settings :sleep] merge sleep)))))
  nil)

(def ^:private system-control-events
  {"poweroff"         :system/poweroff
   "reboot"           :system/reboot
   "restart-fairybox" :system/restart-fairybox})

(defaction control-system
  [{:keys [query-params] :fairy.box/keys [component]}]
  (when-let [event (get system-control-events
                        (get query-params "operation"))]
    (when-let [controller (component :fairy.box.switchboard/switchboard)]
      (switchboard/emit-system! (:emitter controller) {:event event})))
  nil)

(def ^:private timer-cycle-directions
  {"next"     :next
   "previous" :previous})

(defn- timer-component
  [{:fairy.box/keys [component]} component-key]
  (when (ifn? component)
    (component component-key)))

(defn- toggle-timer! [timer]
  (if (timers/enabled? timer)
    (timers/disable! timer)
    (timers/enable! timer)))

(defaction cycle-sleep-duration
  [{:keys [query-params] :as request}]
  (when-let [direction (timer-cycle-directions
                        (get query-params "direction"))]
    (when-let [timer (timer-component request :fairy.box.sleep/timer)]
      (timers/cycle! timer direction)))
  nil)

(defaction toggle-sleep-timer [request]
  (when-let [timer (timer-component request :fairy.box.sleep/timer)]
    (toggle-timer! timer))
  nil)

(defaction cycle-auto-shutdown-duration
  [{:keys [query-params] :as request}]
  (when-let [direction (timer-cycle-directions
                        (get query-params "direction"))]
    (when-let [timer (timer-component
                      request
                      :fairy.box.auto-shutdown/timer)]
      (timers/cycle! timer direction)))
  nil)

(defaction toggle-auto-shutdown-timer [request]
  (when-let [timer (timer-component
                    request
                    :fairy.box.auto-shutdown/timer)]
    (toggle-timer! timer))
  nil)

(defn current-rfid [rfid-uid linked-folder]
  [:div {:id "current-rfid"}
   [:dl {:class (css :max-w-2xl :border-dashed :border-2 :border-gray-300)}
    [:div {:class (css :px-2 :py-2 [:sm :grid :grid-cols-3 :gap-4])}
     [:dt {:class (css :text-sm :font-medium :leading-6 :text-gray-600
                       [:dark :text-gray-400])}
      "RFID Tag"]
     [:dd {:class (css :mt-1 :text-sm :leading-6 [:sm :col-span-2 :mt-0]
                       :text-gray-600 [:dark :text-gray-400])}
      (or rfid-uid "Not Present")]]
    (when (and rfid-uid linked-folder)
      [:div {:class (css :px-1 :py-2 [:sm :grid :grid-cols-3 :gap-4 :px-0])}
       [:dt {:class (css :text-sm :font-medium :leading-6)} "Linked Folder"]
       [:dd {:class (css :mt-1 :text-sm :leading-6
                         [:sm :col-span-2 :mt-0])}
        linked-folder]])]])

(defn rfid-link-form [req uid linked-folder]
  [:form {:class [ui/$page-margin]
          :data-signals:selected_folder__ifmissing (or linked-folder "")
          :data-on:submit (str "evt.preventDefault(); @post('" link-rfid-folder "')")}
   (ui/setting-heading :label "RFID Tags")
   [:div
    [:div {:class (css :mt-8 :space-y-10)}
     [:fieldset
      [:legend {:class (css :text-sm :font-semibold :leading-6
                            :text-gray-900 [:dark :text-gray-300])}
       "Current RFID Tag"]
      [:p {:class (css :mt-1 :text-sm :leading-6 :text-gray-600
                       [:dark :text-gray-400])}
       "Place an RFID tag on your Fairybox, and the ID number will appear here. Then you can link it to a folder or playlist below using the file browser."]
      [:div {:class (css :mt-6 :space-y-2)}
       (current-rfid uid linked-folder)]]]
    [:div {:class (css :mt-2 :space-y-2)}
     [:fieldset
      (uic/browse-media-folder req
                               {:mode :choose :active-value linked-folder}
                               (get-in req [:query-params "dir"]))]]

    [:div {:class (css :mt-6 :flex :items-center :justify-end :gap-x-6)}
     (ui/button :tag :a
                :href "/settings"
                :priority :link
                :label "Back")
     (ui/button :type :submit
                :priority :primary
                :disabled? (nil? uid)
                :label "Link To Folder")]]])

(defn rfid-link [{:fairy.box/keys [component] :as req}]
  (let [presence      (component :fairy.box.web/refresh)
        uid           (refresh/current-uid presence)
        db-conn       (component :fairy.box.db/db)
        linked-folder (db/linked-folder @db-conn uid)]
    [:div {:id "active-tab"}
     (rfid-link-form req uid linked-folder)]))

(defn settings-option [label icon href]
  [:li #_"<!-- Current: \"bg-gray-50 text-indigo-600\", Default: \"text-gray-700 hover:text-indigo-600 hover:bg-gray-50\" -->"
   (ui/button :priority :link
              :label label
              :href href
              :tag :a
              :icon icon
              :icon-class (css :h-8 :w-8 :shrink-0 :text-smoky-800 [:dark :text-smoky-400])
              :class (css :group :flex :items-center :gap-x-3 :rounded-md :p-2 :text-sm :leading-6 :font-semibold :cursor-pointer))])

(defn- system-control-expression [operation]
  (str "document.getElementById('power-dialog').close(); @post('"
       control-system
       (h/url-query-string {:operation operation})
       "')"))

(defn- power-control-action
  [controls-enabled? operation label description dangerous?]
  [:button
   (cond-> {:type     "button"
            :class    (cond-> ["power-dialog-action"]
                        dangerous? (conj "power-dialog-action--danger"))
            :disabled (not controls-enabled?)}
     controls-enabled?
     (assoc :data-on:click (system-control-expression operation)))
   [:span {:class "power-dialog-action__label"} label]
   [:span {:class "power-dialog-action__description"} description]])

(defn- power-controls [controls-enabled?]
  [:div {:class (css :mt-16 :flex :justify-end :border-t
                     :border-smoky-200 :pt-6
                     [:dark :border-smoky-800])}
   [:button#power-controls-launcher.power-controls-launcher
    {:type          "button"
     :aria-label    "Open power controls"
     :title         "Power controls"
     :data-on:click "document.getElementById('power-dialog').showModal()"}
    (icon/power {:class (css :h-5 :w-5)})]
   [:dialog#power-dialog.power-dialog
    {:aria-labelledby  "power-dialog-title"
     :aria-describedby "power-dialog-description"}
    [:div {:class "power-dialog__content"}
     [:header {:class "power-dialog__header"}
      [:h2#power-dialog-title "Power controls"]
      [:p#power-dialog-description
       "Choose what to restart or shut down."]]
     [:div {:class "power-dialog__groups"}
      [:section {:aria-labelledby "device-power-heading"}
       [:h3#device-power-heading "Device"]
       [:div {:class "power-dialog__actions"}
        (power-control-action controls-enabled?
                              "poweroff"
                              "Power off"
                              "Shut down the Fairybox device completely."
                              true)
        (power-control-action controls-enabled?
                              "reboot"
                              "Reboot"
                              "Restart the operating system and Fairybox."
                              true)]]
      [:section {:aria-labelledby "fairybox-power-heading"}
       [:h3#fairybox-power-heading "Fairybox"]
       [:div {:class "power-dialog__actions"}
        (power-control-action controls-enabled?
                              "restart-fairybox"
                              "Restart"
                              "Restart only the Fairybox systemd service."
                              false)]]]
     (when-not controls-enabled?
       [:p {:class "power-dialog__disabled-notice"}
        "Power controls are disabled outside the Raspberry Pi production profile."])
     [:form {:method "dialog" :class "power-dialog__footer"}
      [:button {:value "cancel" :class "power-dialog__cancel"}
       "Cancel"]]]]])

(defn settings-view [{:keys [url-for] :as req}]
  (let [controls-enabled? (switchboard/poweroff-enabled?
                           (app-settings/settings req))]
    [:div {:class [(css :max-w-5xl) ui/$page-margin]}
     [:h1 {:class (css :text-2xl :mb-2)} "Settings"]
     [:div
      [:nav {:class (css :flex :flex-1 :flex-col), :aria-label "Sidebar"}
       [:ul {:role "list", :class (css :-mx-2 :space-y-1 :max-w-lg)}
        (settings-option "RFID Tags" icon/radio-frequency (url-for :page.settings/rfid-link))
        (settings-option "Browse Audio" icon/file-audio (url-for :page.settings/browse))
        (settings-option "Device" icon/cog (url-for :page.settings/device))
        (settings-option "Text to Speech" icon/tts (url-for :page.settings/tts))]]
      (power-controls controls-enabled?)]]))

(defn browse-audio [{:fairy.box/keys [component] :as req}]
  (let [settings (app-settings/settings req)
        db-conn  (component :fairy.box.db/db)]
    [:div {:id "active-tab"}
     [:div {:class [(css :max-w-5xl) ui/$page-margin]}
      [:div
       [:p {:class (css :text-lg :font-bold :text-smoky-900
                        [:dark :text-smoky-300])}
        "Fairybox Audio Folders"]]
      (uic/browse-media-folder
       req
       {:mode                :play
        :play-action         play-audio-path
        :announcement-action cycle-path-announcement
        :announcement-status (partial db/announcement-status
                                      {:db-conn  db-conn
                                       :settings settings})}
       (get-in req [:query-params "dir"]))]]))

(defn- settings-card
  [{:keys [id title description class]} & content]
  (into
   [:section {:id              id
              :aria-labelledby (str id "-heading")
              :class           [(css :rounded-xl :border :border-smoky-300
                                     :bg-white-rock-50 :p-5 :shadow-sm
                                     [:dark :border-smoky-700 :bg-smoky-900]
                                     [:sm :p-6])
                                class]}
    [:div
     [:h3 {:id    (str id "-heading")
           :class (css :text-lg :font-bold :text-smoky-900
                       [:dark :text-smoky-100])}
      title]
     [:p {:class (css :mt-1 :max-w-3xl :text-sm :leading-6 :text-smoky-700
                      [:dark :text-smoky-300])}
      description]]]
   content))

(defn- card-behavior-choice
  [group-name signal value selected-value label description]
  [:label {:class ["card-behavior-choice"
                   (css :flex :cursor-pointer :gap-3 :rounded-lg :border
                        :border-smoky-300 :bg-white :p-3
                        [:dark :border-smoky-700 :bg-smoky-950]
                        [:sm :p-4])]}
   [:input {:class     (css :mt-1 :h-4 :w-4 :shrink-0 :border-smoky-400)
            :type      "radio"
            :name      group-name
            :value     value
            :checked   (= value selected-value)
            :data-bind signal}]
   [:span {:class (css :block)}
    [:span {:class (css :block :text-sm :font-semibold :text-smoky-900
                        [:dark :text-smoky-100])}
     label]
    [:span {:class (css :mt-1 :block :text-sm :leading-5 :text-smoky-700
                        [:dark :text-smoky-300])}
     description]]])

(defn- card-behavior-choice-group
  [{:keys [id legend description signal selected-value choices]}]
  [:fieldset {:class (css :space-y-3)}
   [:legend {:id    id
             :class (css :text-base :font-semibold :text-smoky-900
                         [:dark :text-smoky-100])}
    legend]
   [:p {:class (css :text-sm :leading-6 :text-smoky-700
                    [:dark :text-smoky-300])}
    description]
   [:div {:class (css :grid :grid-cols-1 :gap-3 [:sm :grid-cols-2])}
    (mapv (fn [{:keys [value label description]}]
            (card-behavior-choice id
                                  signal
                                  value
                                  selected-value
                                  label
                                  description))
          choices)]])

(defn- card-behavior-settings
  [{:keys [card-removal-behavior card-return-behavior]}]
  (settings-card
   {:id          "card-behavior-settings"
    :title       "RFID card behavior"
    :description "Choose what happens when the card that started playback is removed or placed back."
    :class       (css [:lg :col-span-8])}
   [:div {:class (css :mt-6 :grid :grid-cols-1 :gap-8 [:xl :grid-cols-2])}
    (card-behavior-choice-group
     {:id             "card-removal-behavior"
      :legend         "When the card is removed"
      :description    "Should Fairybox pause, or continue playing?"
      :signal         "card_removal_behavior"
      :selected-value (name card-removal-behavior)
      :choices        [{:value       "keep-playing"
                        :label       "Keep playing"
                        :description "Audio continues; removing the card does nothing."}
                       {:value       "pause"
                        :label       "Pause playback"
                        :description "Audio pauses when the card is removed."}]})
    [:div {:class "card-return-behavior"}
     (card-behavior-choice-group
      {:id             "card-return-behavior"
       :legend         "When the same card is placed back"
       :description    "This applies after playback was paused by removing the card."
       :signal         "card_return_behavior"
       :selected-value (name card-return-behavior)
       :choices        [{:value       "resume"
                         :label       "Resume playback"
                         :description "Continue the same track from the paused position."}
                        {:value       "restart"
                         :label       "Restart the playlist"
                         :description "Start again from the beginning of the playlist."}]})]]))

(defn- settings-slider
  [{:keys [name label value signal description]}]
  [:div {:class ["device-settings-slider" "volume-bar"]}
   [:div {:class (css :flex :items-center :justify-between :gap-4)}
    [:label {:for   name
             :class (css :text-sm :font-semibold :text-smoky-900
                         [:dark :text-smoky-100])}
     label]
    [:output {:for       name
              :data-text (str "$" signal " + '%'")
              :class     (css :min-w-12 :text-right :text-sm :font-bold
                              :tabular-nums :text-smoky-800
                              [:dark :text-smoky-200])}
     (str value "%")]]
   [:input {:id               name
            :name             name
            :type             "range"
            :min              0
            :max              100
            :step             1
            :value            value
            :data-bind        signal
            :aria-describedby (str name "-description")
            :class            (css :mt-2 :h-11 :w-full :cursor-pointer)}]
   [:div {:aria-hidden "true"
          :class       (css :-mt-1 :flex :justify-between :text-xs
                            :tabular-nums :text-smoky-500
                            [:dark :text-smoky-400])}
    [:span "0%"]
    [:span "100%"]]
   [:p {:id    (str name "-description")
        :class (css :mt-1 :text-xs :leading-5 :text-smoky-600
                    [:dark :text-smoky-400])}
    description]])

(defn- settings-time-input
  [{:keys [name label value signal]}]
  [:div
   [:label {:for   name
            :class (css :block :text-sm :font-semibold :text-smoky-900
                        [:dark :text-smoky-100])}
    label]
   [:input {:id        name
            :name      name
            :type      "time"
            :step      60
            :value     value
            :data-bind signal
            :class     (css :mt-2 :block :w-full :rounded-md :border
                            :border-smoky-300 :bg-white :px-3 :py-2
                            :text-smoky-900 :shadow-sm
                            [:focus :border-smoky-600 :ring-2 :ring-smoky-600]
                            [:dark :border-smoky-700 :bg-smoky-950
                             :text-smoky-100])}]])

(defn- overall-volume-settings
  [{:keys [min-volume max-volume]}]
  (settings-card
   {:id          "overall-volume-settings"
    :title       "Overall volume"
    :description "Set the usable volume range and a safety ceiling that every playback profile respects."
    :class       (css [:lg :col-span-4])}
   [:div {:class (css :mt-6 :grid :grid-cols-1 :gap-6
                      [:sm :grid-cols-2] [:lg :grid-cols-1])}
    (settings-slider
     {:name        "min-volume"
      :label       "Minimum volume"
      :value       min-volume
      :signal      "min_volume"
      :description "The quietest volume selectable from the player or hardware buttons."})
    (settings-slider
     {:name        "max-volume"
      :label       "Safety maximum"
      :value       max-volume
      :signal      "max_volume"
      :description "The loudest volume Fairybox will allow at any time."})]))

(defn- day-night-profile
  [{:keys [id title description start-time start-signal volume volume-signal
           brightness brightness-signal]}]
  [:section {:id              id
             :aria-labelledby (str id "-heading")
             :class           (css :rounded-lg :border :border-smoky-200
                                   :bg-white :p-4
                                   [:dark :border-smoky-700 :bg-smoky-950]
                                   [:sm :p-5])}
   [:div
    [:h4 {:id    (str id "-heading")
          :class (css :text-base :font-bold :text-smoky-900
                      [:dark :text-smoky-100])}
     title]
    [:p {:class (css :mt-1 :text-xs :leading-5 :text-smoky-600
                     [:dark :text-smoky-400])}
     description]]
   [:div {:class (css :mt-5 :space-y-6)}
    (settings-time-input
     {:name   (str id "-start")
      :label  "Starts at"
      :value  start-time
      :signal start-signal})
    (settings-slider
     {:name        (str id "-max-volume")
      :label       "Maximum volume"
      :value       volume
      :signal      volume-signal
      :description "This profile's cap, still limited by the overall safety maximum."})
    (settings-slider
     {:name        (str id "-led-brightness")
      :label       "LED brightness"
      :value       brightness
      :signal      brightness-signal
      :description "Maximum front-panel LED brightness during this profile."})]])

(defn- day-night-settings
  [{:keys [max-volume-day max-volume-night
           max-led-brightness-day max-led-brightness-night
           day-start night-start]}]
  (settings-card
   {:id          "day-night-settings"
    :title       "Day & night profiles"
    :description "Fairybox switches profiles at these times, changing both its volume cap and LED brightness."
    :class       (css [:lg :col-span-12])}
   [:div {:class (css :mt-6 :grid :grid-cols-1 :gap-5 [:sm :grid-cols-2])}
    (day-night-profile
     {:id                "day-profile"
      :title             "Day"
      :description       "Brighter lights and a daytime listening limit."
      :start-time        day-start
      :start-signal      "day_start"
      :volume            max-volume-day
      :volume-signal     "max_volume_day"
      :brightness        max-led-brightness-day
      :brightness-signal "max_led_brightness_day"})
    (day-night-profile
     {:id                "night-profile"
      :title             "Night"
      :description       "Quieter playback and dimmer lights for evenings."
      :start-time        night-start
      :start-signal      "night_start"
      :volume            max-volume-night
      :volume-signal     "max_volume_night"
      :brightness        max-led-brightness-night
      :brightness-signal "max_led_brightness_night"})]))

(defn- timer-cycle-expression [action direction]
  (str "@post('" action
       (h/url-query-string {:direction (name direction)})
       "')"))

(defn- timer-controls
  [{:keys [heading selected-minutes detail cycle-action toggle-action
           previous-label next-label enabled? timer-label]}]
  [:div {:class (css :rounded-lg :border :border-smoky-200 :bg-white :p-4
                     [:dark :border-smoky-700 :bg-smoky-950])}
   [:p {:class (css :text-sm :font-semibold :text-smoky-900
                    [:dark :text-smoky-100])}
    heading]
   [:div {:class (css :mt-4 :flex :items-center :justify-between :gap-3)}
    [:button {:type          "button"
              :aria-label    previous-label
              :data-on:click (timer-cycle-expression cycle-action :previous)
              :class         (css :flex :h-12 :w-12 :shrink-0 :items-center
                                  :justify-center :rounded-full :border
                                  :border-smoky-300 :text-smoky-800
                                  [:dark :border-smoky-700 :text-smoky-100])}
     (icon/chevron-left {:class (css :h-5 :w-5)})]
    [:div {:class (css :min-w-32 :text-center)}
     [:p {:class (css :text-xl :font-bold :tabular-nums :text-smoky-900
                      [:dark :text-smoky-100])}
      (timers/duration-label selected-minutes)]
     (when detail
       [:p {:class (css :mt-1 :text-xs :tabular-nums :text-smoky-600
                        [:dark :text-smoky-400])}
        detail])]
    [:button {:type          "button"
              :aria-label    next-label
              :data-on:click (timer-cycle-expression cycle-action :next)
              :class         (css :flex :h-12 :w-12 :shrink-0 :items-center
                                  :justify-center :rounded-full :border
                                  :border-smoky-300 :text-smoky-800
                                  [:dark :border-smoky-700 :text-smoky-100])}
     (icon/chevron-right {:class (css :h-5 :w-5)})]]
   [:button {:type          "button"
             :data-on:click (str "@post('" toggle-action "')")
             :class         [(css :mt-5 :w-full :rounded-md :px-4 :py-3
                                  :text-sm :font-bold :text-white)
                             (if enabled?
                               (css :bg-red-700)
                               (css :bg-cloud-burst-700))]}
    timer-label]])

(defn- sleep-timer-settings [timer sleep-settings]
  (let [{:keys [enabled? selected-minutes fade-at shutdown-at phase]}
        (if timer
          (timers/current timer)
          {:enabled? false :selected-minutes nil :phase :off})
        {:keys [shutdown? shutdown-delay-minutes]}                    sleep-settings]
    (settings-card
     {:id          "sleep-timer-settings"
      :title       "Sleep timer"
      :description "Fade playback to silence over the final two minutes, then optionally power off Fairybox."
      :class       (css [:lg :col-span-12])}
     [:div {:class (css :mt-6 :grid :grid-cols-1 :gap-6
                        [:md :grid-cols-2])}
      (timer-controls
       {:heading          "Fade-out time"
        :selected-minutes selected-minutes
        :detail           (when enabled?
                            (str (if (= :shutdown-wait phase)
                                   "Audio stopped at "
                                   "Audio stops at ")
                                 fade-at))
        :cycle-action     cycle-sleep-duration
        :toggle-action    toggle-sleep-timer
        :previous-label   "Previous sleep duration"
        :next-label       "Next sleep duration"
        :enabled?         enabled?
        :timer-label      (if enabled?
                            "Disable sleep timer"
                            "Enable sleep timer")})
      [:div {:class (css :space-y-5 :rounded-lg :border :border-smoky-200
                         :bg-white :p-4
                         [:dark :border-smoky-700 :bg-smoky-950])}
       [:label {:class (css :flex :items-start :gap-3)}
        [:input {:type      "checkbox"
                 :name      "sleep-shutdown"
                 :checked   shutdown?
                 :data-bind "sleep_shutdown"
                 :class     (css :mt-1 :h-5 :w-5 :rounded
                                 :border-smoky-300)}]
        [:span
         [:span {:class (css :block :text-sm :font-semibold :text-smoky-900
                             [:dark :text-smoky-100])}
          "Shut down after fade-out"]
         [:span {:class (css :mt-1 :block :text-xs :leading-5
                             :text-smoky-600 [:dark :text-smoky-400])}
          "Power off without playing the shutdown sound."]]]
       [:div
        [:label {:for   "sleep-shutdown-delay"
                 :class (css :block :text-sm :font-semibold :text-smoky-900
                             [:dark :text-smoky-100])}
         "Delay before shutdown"]
        [:div {:class (css :mt-2 :flex :items-center :gap-3)}
         [:input {:id                 "sleep-shutdown-delay"
                  :name               "sleep-shutdown-delay"
                  :type               "number"
                  :min                0
                  :step               1
                  :value              shutdown-delay-minutes
                  :data-bind          "sleep_shutdown_delay_minutes"
                  :data-attr:disabled "!$sleep_shutdown"
                  :class              (css :w-24 :rounded-md :border
                                           :border-smoky-300 :bg-white
                                           :px-3 :py-2 :text-smoky-900
                                           [:dark :border-smoky-700
                                            :bg-smoky-900 :text-smoky-100])}]
         [:span {:class (css :text-sm :text-smoky-700
                             [:dark :text-smoky-300])}
          "minutes"]]
        [:p {:class (css :mt-1 :text-xs :text-smoky-600
                         [:dark :text-smoky-400])}
         "Use 0 to power off immediately after playback stops."]
        (when (and enabled? shutdown-at)
          [:p {:class (css :mt-3 :text-sm :font-semibold :tabular-nums
                           :text-smoky-800 [:dark :text-smoky-200])}
           (str "Power off at " shutdown-at)])]]])))

(defn- auto-shutdown-settings [timer]
  (let [{:keys [enabled? selected-minutes]}
        (if timer
          (timers/current timer)
          {:enabled? false :selected-minutes nil})]
    (settings-card
     {:id          "auto-shutdown-settings"
      :title       "Auto shutdown"
      :description "Power off after Fairybox has had no interaction and no audio playing for the selected time."
      :class       (css [:lg :col-span-6])}
     [:div {:class (css :mt-6)}
      (timer-controls
       {:heading          "Idle time"
        :selected-minutes selected-minutes
        :cycle-action     cycle-auto-shutdown-duration
        :toggle-action    toggle-auto-shutdown-timer
        :previous-label   "Previous auto shutdown duration"
        :next-label       "Next auto shutdown duration"
        :enabled?         enabled?
        :timer-label      (if enabled?
                            "Disable auto shutdown"
                            "Enable auto shutdown")})])))

(defn device-settings-form
  [{:keys [url-for]}
   {:keys [min-volume max-volume max-volume-day max-volume-night
           max-led-brightness-day max-led-brightness-night
           day-start night-start card-removal-behavior
           card-return-behavior] :as audio-settings}
   sleep-settings
   sleep-timer
   auto-shutdown-timer]
  [:div {:class [ui/$page-margin (css :mx-auto :max-w-6xl)]}
   [:div
    [:h2 {:class (css :text-2xl :font-bold :text-smoky-900
                      [:dark :text-smoky-100])}
     "Device Settings"]
    [:p {:class (css :mt-1 :max-w-3xl :text-sm :leading-6 :text-smoky-700
                     [:dark :text-smoky-300])}
     "Configure cards, listening limits, lights, schedules, and power behavior."]]
   [:form {:id             "device-settings"
           :data-signals__ifmissing
           (h/edn->json
            {:min_volume               min-volume
             :max_volume               max-volume
             :max_volume_day           max-volume-day
             :max_volume_night         max-volume-night
             :max_led_brightness_day   max-led-brightness-day
             :max_led_brightness_night max-led-brightness-night
             :day_start                day-start
             :night_start              night-start
             :card_removal_behavior    (name card-removal-behavior)
             :card_return_behavior     (name card-return-behavior)
             :sleep_shutdown           (:shutdown? sleep-settings)
             :sleep_shutdown_delay_minutes
             (:shutdown-delay-minutes sleep-settings)})
           :data-on:change (str "@post('" save-device-settings "')")}
    [:div {:class (css :mt-6 :grid :grid-cols-1 :gap-6 [:lg :grid-cols-12])}
     (overall-volume-settings
      {:min-volume min-volume :max-volume max-volume})
     (card-behavior-settings
      {:card-removal-behavior card-removal-behavior
       :card-return-behavior  card-return-behavior})
     (day-night-settings audio-settings)
     (sleep-timer-settings sleep-timer sleep-settings)
     (auto-shutdown-settings auto-shutdown-timer)]
    [:div {:class (css :mt-6 :flex :items-center :justify-end)}
     (ui/button :tag :a
                :href (url-for :page/settings)
                :priority :link
                :label "Back")]]])

(defn device-settings [{:fairy.box/keys [component] :as req}]
  (let [database @(component :fairy.box.db/db)]
    [:div {:id "active-tab"}
     (device-settings-form
      req
      (db/audio-settings database)
      (db/sleep-settings database)
      (component :fairy.box.sleep/timer)
      (component :fairy.box.auto-shutdown/timer))]))

(defview render-device {:path         "/settings/device"
                        :shim-headers ui/shim-headers}
  [req]
  (h/html
   (ui/css-reload)
   [:main#morph.main
    [:div {}
     (uic/player-tabs req :page/settings)
     (device-settings req)]]))

(defview render-browse {:path "/settings/browse" :shim-headers ui/shim-headers} [req]
  (h/html
   (ui/css-reload)
   [:main#morph.main
    [:div {}
     (uic/player-tabs req :page/settings)
     (browse-audio req)]]))

(defview render-rfid {:path "/settings/rfid" :shim-headers ui/shim-headers}
  [req]
  (h/html
   (ui/css-reload)
   [:main#morph.main
    [:div {}
     (uic/player-tabs req :page/settings)
     [:div {:id "active-tab"}
      [:div {:class "fade-in-out"}
       (rfid-link req)]]]]))

(defview render-settings {:path "/settings" :shim-headers ui/shim-headers}
  [req]
  (h/html
   (ui/css-reload)
   [:main#morph.main
    [:div {}
     (uic/player-tabs req :page/settings)
     [:div {:id "active-tab"}
      [:div {:class "fade-in-out"}
       (settings-view req)]]]]))

(h/refresh-all!)
