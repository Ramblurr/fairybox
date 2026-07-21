(ns fairy.box.web.views.settings
  (:require
   [fairy.box.audio.browse :as browse]
   [fairy.box.db :as db]
   [fairy.box.settings :as app-settings]
   [fairy.box.switchboard :as switchboard]
   [fairy.box.util :as util]
   [fairy.box.web.rfid :as rfid]
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

(defaction link-rfid-folder [{:fairy.box/keys [component] :as req}]
  (let [presence        (component :fairy.box.web/rfid-presence)
        uid             (rfid/current-uid presence)
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
                                                         canonical-path))
          (rfid/refresh! presence))))))

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
        card-return-behavior  (keyword (:card_return_behavior body))]
    (when (and (every? (fn [[_ value]]
                         (and (integer? value)
                              (<= 0 value 100)))
                       numbers)
               (util/valid-wall-clock-time? day-start)
               (util/valid-wall-clock-time? night-start))
      (assoc numbers
             :day-start day-start
             :night-start night-start
             :card-removal-behavior card-removal-behavior
             :card-return-behavior card-return-behavior))))

(defaction save-device-settings
  [{:fairy.box/keys [component] :as request}]
  (when-let [update (device-settings-update (:body request))]
    (swap! (component :fairy.box.db/db)
           update-in
           [:settings :audio]
           merge
           update))
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
  (let [presence      (component :fairy.box.web/rfid-presence)
        uid           (rfid/current-uid presence)
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

(defn settings-view [{:keys [url-for] :as _req}]
  [:div {:class [(css :max-w-5xl) ui/$page-margin]}
   [:h1 {:class (css :text-2xl :mb-2)} "Settings"]
   [:div
    [:nav {:class (css :flex :flex-1 :flex-col), :aria-label "Sidebar"}
     [:ul {:role "list", :class (css :-mx-2 :space-y-1 :max-w-lg)}
      (settings-option "RFID Tags" icon/radio-frequency (url-for :page.settings/rfid-link))
      (settings-option "Browse Audio" icon/file-audio (url-for :page.settings/browse))
      (settings-option "Device" icon/cog (url-for :page.settings/device))
      (settings-option "Text to Speech" icon/tts (url-for :page.settings/tts))]]]])

(defn browse-audio [req]
  [:div {:id "active-tab"}
   [:div {:class [(css :max-w-5xl) ui/$page-margin]}
    [:div
     [:p {:class (css :text-lg :font-bold :text-smoky-900
                      [:dark :text-smoky-300])}
      "Fairybox Audio Folders"]]
    (uic/browse-media-folder req
                             {:mode        :play
                              :play-action play-audio-path}
                             (get-in req [:query-params "dir"]))]])

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

(defn device-settings-form
  [{:keys [url-for]}
   {:keys [min-volume max-volume max-volume-day max-volume-night
           max-led-brightness-day max-led-brightness-night
           day-start night-start card-removal-behavior
           card-return-behavior] :as audio-settings}]
  [:div {:class [ui/$page-margin (css :mx-auto :max-w-6xl)]}
   [:div
    [:h2 {:class (css :text-2xl :font-bold :text-smoky-900
                      [:dark :text-smoky-100])}
     "Device Settings"]
    [:p {:class (css :mt-1 :max-w-3xl :text-sm :leading-6 :text-smoky-700
                     [:dark :text-smoky-300])}
     "Configure how Fairybox responds to cards, protects hearing, and adapts its sound and lights throughout the day."]]
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
             :card_return_behavior     (name card-return-behavior)})
           :data-on:change (str "@post('" save-device-settings "')")}
    [:div {:class (css :mt-6 :grid :grid-cols-1 :gap-6 [:lg :grid-cols-12])}
     (overall-volume-settings
      {:min-volume min-volume :max-volume max-volume})
     (card-behavior-settings
      {:card-removal-behavior card-removal-behavior
       :card-return-behavior  card-return-behavior})
     (day-night-settings audio-settings)]
    [:div {:class (css :mt-6 :flex :items-center :justify-end)}
     (ui/button :tag :a
                :href (url-for :page/settings)
                :priority :link
                :label "Back")]]])

(defn device-settings [{:fairy.box/keys [component] :as req}]
  [:div {:id "active-tab"}
   (device-settings-form
    req
    (db/audio-settings @(component :fairy.box.db/db)))])

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
