(ns fairy.box.web.views.settings
  (:require
   [fairy.box.audio.browse :as browse]
   [fairy.box.db :as db]
   [fairy.box.settings :as app-settings]
   [fairy.box.util :as util]
   [fairy.box.switchboard :as switchboard]
   [fairy.box.ui3 :as ui3]
   [fairy.box.web.rfid :as rfid]
   [fairy.box.web.views.common :as uic]
   [fairy.box.web.views.icon :as icon]
   [fairy.box.web.views.ui :as ui]
   [hyperlith.core :as h :refer [defaction defview]]
   [shadow.css :refer [css]]))

(defaction play-audio-path [{:fairy.box/keys [component] :as req}]
  (let [selected-path (get-in req [:query-params "path"])
        settings (app-settings/settings req)
        controller (component :fairy.box.switchboard/switchboard)]
    (when (and controller (seq selected-path))
      (when-let [canonical-path
                 (browse/canonicalize-path settings selected-path)]
        (when (browse/playable-type settings canonical-path)
          (switchboard/emit-player!
           (:emitter controller)
           {:action :audio/play-path
            :item-path canonical-path
            :uid nil})
          (h/execute-expr
           (format "window.location.assign('%s')"
                   ((:url-for req) :page/home))))))))

(defaction link-rfid-folder [{:fairy.box/keys [component] :as req}]
  (let [presence (component :fairy.box.web/rfid-presence)
        uid (rfid/current-uid presence)
        selected-folder (get-in req [:body :selected_folder])
        settings (app-settings/settings req)
        db-conn (component :fairy.box.db/db)]
    (when (and uid (seq selected-folder))
      (when-let [canonical-path
                 (browse/canonicalize-path settings selected-folder)]
        (when (browse/playable-type settings canonical-path)
          (db/link-rfid-tag! db-conn
                             uid
                             (browse/media-relative-path settings
                                                         canonical-path))
          (rfid/refresh! presence))))))

(defaction save-playback-settings
  [{:fairy.box/keys [component]
    {:keys [min_volume max_volume max_volume_day max_volume_night
            hour_day_start hour_night_start]} :body}]
  (let [audio (-> {:min-volume min_volume
                   :max-volume max_volume
                   :max-volume-day max_volume_day
                   :max-volume-night max_volume_night
                   :hour-day-start hour_day_start
                   :hour-night-start hour_night_start}
                  (update-vals #(if (string? %) (parse-long %) %))
                  util/remove-nils)]
    (db/upsert-audio-settings!
     (component :fairy.box.db/db)
     audio)
    nil))

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
  (let [presence (component :fairy.box.web/rfid-presence)
        uid (rfid/current-uid presence)
        db-conn (component :fairy.box.db/db)
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
              :class (css :group :flex :gap-x-3 :rounded-md :p-2 :text-sm :leading-6 :font-semibold :cursor-pointer))])

(defn settings-view [{:keys [url-for] :as _req}]
  [:div {:class [(css :max-w-5xl) ui/$page-margin]}
   [:h1 {:class (css :text-2xl :mb-2)} "Settings"]
   [:div
    [:nav {:class (css :flex :flex-1 :flex-col), :aria-label "Sidebar"}
     [:ul {:role "list", :class (css :-mx-2 :space-y-1 :max-w-lg)}
      (settings-option "RFID Tags" icon/radio-frequency (url-for :page.settings/rfid-link))
      (settings-option "Browse Audio" icon/file-audio (url-for :page.settings/browse))
      (settings-option "Playback" icon/play (url-for :page.settings/playback))]]]])

(defn browse-audio [req]
  [:div {:id "active-tab"}
   [:div {:class [(css :max-w-5xl) ui/$page-margin]}
    [:div
     [:p {:class (css :text-lg :font-bold :text-smoky-900
                      [:dark :text-smoky-300])}
      "Fairybox Audio Folders"]]
    (uic/browse-media-folder req
                             {:mode :play
                              :play-action play-audio-path}
                             (get-in req [:query-params "dir"]))]])

(defn playback-settings-form
  [{:keys [url-for]}
   {:keys [min-volume max-volume max-volume-day max-volume-night
           hour-day-start hour-night-start]}]
  [:form {:class [ui/$page-margin (css :max-w-5xl)]
          :id "playback-settings"
          :data-signals:min_volume__ifmissing (or min-volume "")
          :data-signals:max_volume__ifmissing (or max-volume "")
          :data-signals:max_volume_day__ifmissing (or max-volume-day "")
          :data-signals:max_volume_night__ifmissing (or max-volume-night "")
          :data-signals:hour_day_start__ifmissing (or hour-day-start "")
          :data-signals:hour_night_start__ifmissing (or hour-night-start "")
          :data-on:submit (str "evt.preventDefault(); @post('"
                               save-playback-settings
                               "')")}
   (ui/setting-heading :label "Playback Settings")
   [:div {:class (css :mt-10 :grid :grid-cols-1 :gap-x-6 :gap-y-8
                      [:sm :grid-cols-6])}
    (ui/integer-input :name "min-volume"
                      :label "Min Volume"
                      :value min-volume
                      :data-bind "min_volume")
    (ui/integer-input :name "max-volume"
                      :label "Max Volume"
                      :value max-volume
                      :data-bind "max_volume")
    (ui/integer-input :name "max-volume-day"
                      :label "Max Volume (Day)"
                      :value max-volume-day
                      :data-bind "max_volume_day")
    (ui/integer-input :name "max-volume-night"
                      :label "Max Volume (Night)"
                      :value max-volume-night
                      :data-bind "max_volume_night")
    (ui/integer-input :name "hour-day-start"
                      :label "Day Starts At"
                      :value hour-day-start
                      :min 0
                      :max 23
                      :data-bind "hour_day_start")
    (ui/integer-input :name "hour-night-start"
                      :label "Night Starts At"
                      :value hour-night-start
                      :min 0
                      :max 23
                      :data-bind "hour_night_start")]
   [:div {:class (css :mt-6 :flex :items-center :justify-end :gap-x-6)}
    (ui/button :tag :a
               :href (url-for :page/settings)
               :priority :link
               :label "Back")
    (ui/button :type :submit :label "Save")]])

(defn playback-settings [{:fairy.box/keys [component] :as req}]
  [:div {:id "active-tab"}
   (playback-settings-form
    req
    (db/audio-settings @(component :fairy.box.db/db)))])

(defview render-playback {:path "/settings/playback"
                          :shim-headers ui3/shim-headers}
  [req]
  (h/html
   (ui3/css-reload)
   [:main#morph.main
    [:div {}
     (uic/player-tabs req :page/settings)
     (playback-settings req)]]))

(defview render-browse {:path "/settings/browse" :shim-headers ui3/shim-headers} [req]
  (h/html
   (ui3/css-reload)
   [:main#morph.main
    [:div {}
     (uic/player-tabs req :page/settings)
     (browse-audio req)]]))

(defview render-rfid {:path "/settings/rfid" :shim-headers ui3/shim-headers}
  [req]
  (h/html
   (ui3/css-reload)
   [:main#morph.main
    [:div {}
     (uic/player-tabs req :page/settings)
     [:div {:id "active-tab"}
      [:div {:class "fade-in-out"}
       (rfid-link req)]]]]))

(defview render-settings {:path "/settings" :shim-headers ui3/shim-headers}
  [req]
  (h/html
   (ui3/css-reload)
   [:main#morph.main
    [:div {}
     (uic/player-tabs req :page/settings)
     [:div {:id "active-tab"}
      [:div {:class "fade-in-out"}
       (settings-view req)]]]]))

(h/refresh-all!)
