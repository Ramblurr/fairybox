(ns fairy.box.web.views.home
  (:require
   [fairy.box.db :as db]
   [fairy.box.web.routes.utils :as util]
   [ring.adapter.undertow.websocket :as ws]
   [cheshire.core :as cheshire]
   [fairy.box.audio.browse :as browse]
   [shadow.css :refer (css)]
   [simpleui.core :as simpleui :refer [defcomponent]]
   [fairy.box.web.htmx :refer [page-htmx partial-htmx]]))

(def ->json cheshire/generate-string)
(def <-json #(cheshire/parse-string % true))

(def ws-clients (atom #{}))

(defn new-ws-client [channel]
  (swap! ws-clients conj channel))

(defn remove-ws-client [channel msg]
  (swap! ws-clients disj channel))

(defonce ^:private rfid-cache (atom {}))

(defn init-ws! []
  (reset! ws-clients #{})
  (reset! rfid-cache {}))

(defn broadcast! [msg]
  (doseq [channel @ws-clients]
    (ws/send msg channel)))

(defn current-rfid [rfid-uid]
  [:div {:id "current-rfid"}
   [:input {:type :hidden :value  rfid-uid
            :name "rfid-uid"}]
   [:input {:type :text :disabled true
            :class (css :block :flex-1 :border-0 :bg-transparent :py-1.5 :pl-1 :text-gray-900  :focus:ring-0 :sm:text-sm :sm:leading-6 :opacity-50 :cursor-not-allowed)
            :value (or rfid-uid "RFID Tag Not Present")}]])

(defn broadcast-rfid-change! [uid action]
  (reset! rfid-cache {:uid uid :action action})
  (broadcast! (partial-htmx (current-rfid (when (= action :placed) uid)))))

(defn ws-handler [{:keys [channel data]}]
  (let [payload (<-json data)]
    (tap> {:channel channel :data payload})
    #_(ws/send "<div id=\"thing\"> WOWOWWW</div>" channel)))

(defn folder-list [idx {:keys [name]}]
  [:div {:class (css :flex :items-center :gap-x-3)}
   [:input {:id (str idx name), :name "folder-item", :type "radio", :class (css :h-4 :w-4 :border-gray-300)
            :required true
            :value name}]
   [:label {:for (str idx name), :class (css :block :text-sm :font-medium :leading-6 :text-gray-900)} name]])

(defn rfid-link-form [req]
  (let  [{:keys [uid action]} @rfid-cache
         rfid-uid (when (= action :placed) uid)]
    [:form {:hx-target "#rfid-link" :hx-post "rfid-link" :id "rfid-link"}
     [:div {:class (css   :pb-12)}
      [:h2 {:class (css :text-base :font-semibold :leading-7 :text-gray-900)} "RFID Tag Link"]
      [:div
       [:div {:class (css :mt-8 :space-y-10)}
        [:fieldset
         [:legend {:class (css :text-sm :font-semibold :leading-6 :text-gray-900)} "Audio Folders"]
         [:p {:class (css :mt-1 :text-sm :leading-6 :text-gray-600)} "Every card can be mapped to an audio folder under the media root."]
         [:div {:class (css :mt-6 :space-y-2)}
          (map-indexed folder-list  (browse/list-media-dir))]]]
       [:div {:class (css :mt-4 :grid :grid-cols-1 :gap-x-6 :gap-y-8 [:sm :grid-cols-6])}
        [:div {:class (css  [:sm :col-span-4])}
         [:label {:for "username", :class (css :block :text-sm :font-medium :leading-6 :text-gray-900)} "Current RFID Tag"]
         [:div {:class (css :mt-2)}
          [:div {:class (css :flex :rounded-md :shadow-sm :ring-1 :ring-inset :ring-gray-300 [:focus-within :ring-2 :ring-inset :ring-indigo-600] [:sm :max-w-md])}
           (current-rfid rfid-uid)]]]]

       [:div {:class (css :mt-6 :flex :items-center :justify-end :gap-x-6)}
        [:button {:type "button", :class (css :text-sm :font-semibold :leading-6 :text-gray-900)} "Cancel"]
        [:button {:type "submit", :class (css :rounded-md :bg-indigo-600 :px-3 :py-2 :text-sm :font-semibold :text-white :shadow-sm [:hover :bg-indigo-500] [:focus-visible :outline :outline-2 :outline-offset-2 :outline-indigo-600])}
         "Link To Folder"]]]]]))

(defcomponent ^:endpoint rfid-link [req folder-item rfid-uid]
  (tap> {:rfid rfid-uid :folder folder-item})
  (when (and (seq rfid-uid) (seq folder-item))
    (db/link-rfid-tag! (:db-conn (util/route-data req)) rfid-uid folder-item))
  (rfid-link-form req))

(defcomponent ^:endpoint home [req]
  rfid-link
  (tap> {:route-data (util/route-data req)})
  [:div {:id "home" :class (css :px-10)}
   [:div {:hx-ext "ws"  :hx-ws "connect:/api/ws"}]
   [:h1 {:class (css :text-2xl)} "Settings"]
   (rfid-link-form req)
   #_[:div
      "WEBSOCK"
      [:div {:hx-ext "ws"  :hx-ws "connect:/api/ws"}
       [:div {:id "thing"}]

       [:form {:hx-ws "send" :id "ws-form"}
        [:input {:name "input" :value "hello"}]
        [:button {:type :submit} "Send"]]]]])

(defn ui-routes [base-path]
  (simpleui/make-routes
   base-path
   (fn [req]
     (page-htmx (home req)))))
