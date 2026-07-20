(ns fairy.box.web.views.settings
  (:require
   [fairy.box.ui3 :as ui3]
   [fairy.box.db :as db]
   [fairy.box.web.views.common :as uic]
   [fairy.box.web.views.icon :as icon]
   [fairy.box.web.views.ui :as ui]
   [hyperlith.core :as h :refer [defaction defview]]
   [shadow.css :refer [css]]))

(defonce ^:private rfid-cache (atom {}))

(defn current-rfid [rfid-uid linked-folder]
  [:div {:id "current-rfid"}
   [:input {:type :hidden :value  rfid-uid :name "rfid-uid"}]
   [:dl {:class (css :max-w-2xl :border-dashed :border-2 :border-gray-300)}
    [:div {:class (css :px-2 :py-2 [:sm :grid :grid-cols-3 :gap-4])}
     [:dt {:class (css :text-sm :font-medium :leading-6 :text-gray-600 [:dark :text-gray-400])} "RFID Tag"]
     [:dd {:class (css :mt-1 :text-sm :leading-6 [:sm  :col-span-2 :mt-0] :text-gray-600 [:dark :text-gray-400])} (or rfid-uid "Not Present")]]
    (when (and rfid-uid linked-folder)
      [:div {:class (css :px-1 :py-2 [:sm :grid :grid-cols-3 :gap-4 :px-0])}
       [:dt {:class (css :text-sm :font-medium :leading-6)} "Linked Folder"]
       [:dd {:class (css :mt-1 :text-sm :leading-6  [:sm  :col-span-2 :mt-0])} linked-folder]])]])

(defn rfid-link-form [req uid linked-folder]
  [:div {:class [ui/$page-margin]}
   (ui/setting-heading :label "RFID Tags")
   [:div
    [:div {:class (css :mt-8 :space-y-10)}
     [:fieldset
      [:legend {:class (css :text-sm :font-semibold :leading-6 :text-gray-900 [:dark :text-gray-300])} "Current RFID Tag"]
      [:p {:class (css :mt-1 :text-sm :leading-6 :text-gray-600 [:dark :text-gray-400])} "Place an RFID tag on your Fairybox, and the ID number will appear here. Then you can link it to a folder or playlist below using the file browser."]
      [:div {:class (css :mt-6 :space-y-2)}
       (current-rfid uid linked-folder)]]]
    [:div {:class (css :mt-2 :space-y-2)}
     [:fieldset
       ;; [:legend {:class (css :text-sm :font-semibold :leading-6 :text-gray-900 [:dark :text-gray-300])} "Audio Folders"]

       ;; [:p {:class (css :mt-1 :text-sm :leading-6 :text-gray-600 [:dark :text-gray-400])} "Choose a folder below to link the current RFID tag."]
      (uic/browse-media-folder req
                               {:mode :choose :active-value linked-folder}
                               (get-in req [:query-params "dir"] nil))
      #_(audio-folder-select settings linked-folder)]]

    [:div {:class (css :mt-6 :flex :items-center :justify-end :gap-x-6)}
     (ui/button :priority :link :label "Back"
                :hx-get "settings" :hx-target "#active-tab"
                :hx-push-url "settings")
     (ui/button :type :submit :priority :primary
                :disabled? (nil? uid)
                :label "Link To Folder")]]])

(defn rfid-link [{:fairy.box/keys [db-conn] :as req}]
  (let [{:keys [uid action]} @rfid-cache
        linked-folder (db/linked-folder db-conn uid)]
    [:div {:id "active-tab"} (if (= action :placed)
                               (rfid-link-form req uid linked-folder)
                               (rfid-link-form req nil nil))]))

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

(defview render-playback {:path "/settings/playback" :shim-headers ui3/shim-headers} [req]
  (h/html
   (ui3/css-reload)
   [:main#morph.main
    [:div
     (uic/player-tabs req :page/settings)
     [:div {:id "active-tab"}
      [:div {:class "fade-in-out"}
       ;; TODO port playback settings from htmx
       (settings-view req)]]]]))

(defview render-browse {:path "/settings/browse" :shim-headers ui3/shim-headers} [req]
  (h/html
   (ui3/css-reload)
   [:main#morph.main
    [:div
     (uic/player-tabs req :page/settings)
     [:div {:id "active-tab"}
      [:div {:class "fade-in-out"}
       ;; TODO port browse page from htmx
       (settings-view req)]]]]))

(defview render-rfid {:path "/settings/rfid" :shim-headers ui3/shim-headers}
  [req]
  (h/html
   (ui3/css-reload)
   [:main#morph.main
    [:div
     (uic/player-tabs req :page/settings)
     [:div {:id "active-tab"}
      [:div {:class "fade-in-out"}
       (rfid-link req)]]]]))

(defview render-settings {:path "/settings" :shim-headers ui3/shim-headers}
  [req]
  (h/html
   (ui3/css-reload)
   [:main#morph.main
    [:div
     (uic/player-tabs req :page/settings)
     [:div {:id "active-tab"}
      [:div {:class "fade-in-out"}
       (settings-view req)]]]]))

(h/refresh-all!)
