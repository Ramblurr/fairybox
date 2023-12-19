(ns fairy.box.web.views.home
  (:require
   [fairy.box.web.views.icon :as icons]
   [clojure.string :as str]
   [clojure.core.async :as async]
   [fairy.box.db :as db]
   [fairy.box.audio :as audio]
   [fairy.box.web.routes.utils :as util]
   [ring.adapter.undertow.websocket :as ws]
   [cheshire.core :as cheshire]
   [fairy.box.audio.browse :as browse]
   [shadow.css :refer (css)]
   [simpleui.core :as simpleui :refer [defcomponent]]
   [fairy.box.web.htmx :refer [page-htmx partial-htmx htmx? trigger-response]]))

(defn cs [& names]
  (str/join " " (filter identity names)))

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

(declare rfid-link-form)
(declare progress-bar)
(declare the-time)
(declare time-left)
(declare play-pause-button)
(declare current-meta)
(declare volume-bar)
(declare volume-icon)
(declare play-queue-list)

(defn broadcast-rfid-change! [db uid action]
  (reset! rfid-cache {:uid uid :action action})
  (broadcast! (partial-htmx
               (if (= action :placed)
                 (rfid-link-form uid (db/linked-folder db uid))
                 (rfid-link-form nil nil)))))

(defn broadcast-player-event! [event]
  ;; (tap> {:event event})
  (condp = (:event event)
    ;; :player/position-changed (broadcast! (partial-htmx (progress-bar (:position event))))
    :player/muted (broadcast! (partial-htmx (volume-icon (-> (audio/current-playback!) :current-volume) (:muted? event))))
    :player/volume-changed (broadcast! (partial-htmx
                                        (volume-icon (:volume event) nil)
                                        (volume-bar (:volume event))))
    :player/media-changed (broadcast! (partial-htmx
                                       (play-queue-list)
                                       (current-meta (:info event))))
    :player/state-changed (broadcast! (partial-htmx (play-pause-button (:state event))))
    :player/time-changed (let [{:keys [current-time current-position]} (audio/current-playback!)
                               {:keys [duration]} (-> (audio/current-track!))]
                           (broadcast! (partial-htmx
                                        (progress-bar current-position)
                                        (time-left current-time duration)
                                        (the-time current-time))))
    nil))

(defn ^:export ws-handler [{:keys [emitter]} {:keys [channel data]}]
  (let [payload (<-json data)]
    (tap> {:data payload})
    (condp = (:action payload)
      "play-pause" (async/put! emitter {:path "/player/commands"
                                        :value {:action :audio/play-pause}})
      "previous" (async/put! emitter {:path "/player/commands"
                                      :value {:action :audio/prev}})
      "next" (async/put! emitter {:path "/player/commands"
                                  :value {:action :audio/next}})
      "skip-back" (async/put! emitter {:path "/player/commands"
                                       :value {:action :audio/skip-time
                                               :milliseconds (* -10 1000)}})
      "skip-forward" (async/put! emitter {:path "/player/commands"
                                          :value {:action :audio/skip-time
                                                  :milliseconds (* 10 1000)}})

      "set-time" (async/put! emitter {:path "/player/commands"
                                      :value {:action :audio/set-time
                                              :milliseconds (:milliseconds payload)}})
      "volume-up-step" (async/put! emitter {:path "/player/commands"
                                            :value {:action :audio/adjust-volume
                                                    :delta 5}})
      "volume-down-step" (async/put! emitter {:path "/player/commands"
                                              :value {:action :audio/adjust-volume
                                                      :delta -5}})
      "set-volume" (async/put! emitter {:path "/player/commands"
                                        :value {:action :audio/set-volume
                                                :volume (parse-double (:volume payload))}})
      "toggle-mute" (async/put! emitter {:path "/player/commands"
                                         :value {:action :audio/toggle-mute}})
      "play-queue-item" (async/put! emitter {:path "/player/commands"
                                             :value {:action :audio/play-queue-index :item-index (parse-long (:item-index payload))}})

      nil)))

(defn current-rfid [rfid-uid linked-folder]
  [:div {:id "current-rfid"}
   [:input {:type :hidden :value  rfid-uid :name "rfid-uid"}]
   [:dl {:class ""}
    [:div {:class (css :px-1 :py-2 [:sm :grid :grid-cols-3 :gap-4 :px-0])}
     [:dt {:class (css :text-sm :font-medium :leading-6)} "RFID UID"]
     [:dd {:class (css :mt-1 :text-sm :leading-6  [:sm  :col-span-2 :mt-0])} (or rfid-uid "RFID Tag Not Present")]]
    (when (and rfid-uid linked-folder)
      [:div {:class (css :px-1 :py-2 [:sm :grid :grid-cols-3 :gap-4 :px-0])}
       [:dt {:class (css :text-sm :font-medium :leading-6)} "Linked Folder"]
       [:dd {:class (css :mt-1 :text-sm :leading-6  [:sm  :col-span-2 :mt-0])} linked-folder]])]])

(defn folder-list [active-path idx {:keys [abs-path name]}]
  [:div {:class (css :flex :items-center :gap-x-3)}
   [:input {:id (str idx name), :name "folder-item", :type "radio", :class (css :h-4 :w-4 :border-gray-300)
            :required true
            :checked (= active-path name)
            :value name}]
   [:label {:for (str idx name), :class (css :block :text-sm :font-medium :leading-6 :text-gray-900 [:dark :text-gray-300])} name]])

(defn audio-folder-select [selected-path]
  [:div {:id "audio-folder-select" :class (css :mt-6 :space-y-2)}
   (map-indexed (partial folder-list selected-path)  (browse/list-media-dir))])

(defn rfid-link-form [uid linked-folder]
  [:form {:hx-target "#rfid-link" :hx-post "rfid-link" :id "rfid-link"}
   [:div {:class (css   :pb-12)}
    [:h2 {:class (css :text-base :font-semibold :leading-7)} "RFID Tag Link"]
    [:div
     [:div {:class (css :mt-8 :space-y-10)}
      [:fieldset
       [:legend {:class (css :text-sm :font-semibold :leading-6 :text-gray-900 [:dark :text-gray-300])} "Current RFID Tag"]
       [:p {:class (css :mt-1 :text-sm :leading-6 :text-gray-600 [:dark :text-gray-400])} "Place an RFID tag on your Fairybox, and the ID number will appear here."]
       [:div {:class (css :mt-6 :space-y-2)}
        (current-rfid uid linked-folder)]]]
     [:div {:class (css :mt-8 :space-y-10)}
      [:fieldset
       [:legend {:class (css :text-sm :font-semibold :leading-6 :text-gray-900 [:dark :text-gray-300])} "Audio Folders"]
       [:p {:class (css :mt-1 :text-sm :leading-6 :text-gray-600 [:dark :text-gray-400])} "Choose a folder below to link the current RFID tag."]
       (audio-folder-select linked-folder)]]

     [:div {:class (css :mt-6 :flex :items-center :justify-end :gap-x-6)}
      [:button {:type "button" :class (css :text-sm :font-semibold :leading-6 :text-gray-900
                                           [:dark :text-smoky-300])
                :hx-get "settings" :hx-target "#active-tab"}
       "Cancel"]
      [:button {:type "submit" :class (css :rounded-md  :px-3 :py-2 :text-sm :font-semibold :text-white :shadow-sm [:hover :bg-cloud-burst-500] [:focus-visible :outline :outline-2 :outline-offset-2 :outline-cloud-burst-600]
                                           :bg-smoky-600
                                           [:dark :bg-cloud-burst-600])}
       "Link To Folder"]]]]])

(defcomponent ^:endpoint rfid-link-form-view [req]
  (let [{:keys [uid action]} @rfid-cache
        linked-folder (db/linked-folder (util/req-db req) uid)]
    (if (= action :placed)
      (rfid-link-form uid linked-folder)
      (rfid-link-form nil nil))))

(defcomponent ^:endpoint rfid-link [req folder-item rfid-uid]
  ;; (tap> {:rfid rfid-uid :folder folder-item})
  (when (and (seq rfid-uid) (seq folder-item))
    (db/link-rfid-tag! (:db-conn (util/route-data req)) rfid-uid folder-item))
  (rfid-link-form-view req))

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
  (let [dur-str (if (float? current-position) (format "%.2f%%" (* 100 current-position)) "0%")
        left-str (format "left: %s" dur-str)
        width-str (format "width: %s" dur-str)]
    [:div {:id "progress-bar" :class (css :relative)}
     [:div {:class (css :bg-smoky-900 :transition-all :duration-500  :rounded-full :overflow-hidden)}
      [:div {:id "progress-bar-val" :class (css :bg-smoky-500 [:dark :bg-smoky-400] :h-2), :role "progressbar"
             :style width-str}]]
     [:div {:class (css :ring-smoky-500   [:dark :ring-smoky-400] :ring-2 :absolute :top-half :w-4 :h-4 :-mt-2 :-ml-2 :flex :items-center :justify-center
                        :rounded-full :shadow :bg-smoky-500)
            :id "progress-bar-point"
            :style left-str}]]))

(defn the-time [current-time]
  [:div {:id "current-time" :class (css :transition-all :duration-500)}
   (format-duration (or current-time 0))])

(defn the-length [duration]
  [:div {:id "current-length" :data-length (str duration) :class (css :transition-all :duration-500  :text-smoky-500 [:dark :text-smoky-500])}
   (format-duration duration)])

(defn time-left [current-time duration]
  ;; (tap> {:current-time current-time :duration duration})
  [:div {:id "current-length" :data-length (str duration) :class (css :transition-all :duration-500  :text-smoky-500 [:dark :text-smoky-500])}
   (if (not current-time)
     (format-duration duration)
     (str "-" (format-duration (- duration current-time))))])

(defn play-pause-button [state]
  (let [icon (condp = state
               :paused :play
               :playing :pause
               :opening :pause
               :finished :play)]
    [:button {:id "play-pause" :value "play-pause" :name "action" :type :submit
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

(defn icon-dot [$class]
  [:svg {:viewbox "0 0 2 2", :class (cs (css :fill-current) $class)} [:circle {:cx "1", :cy "1", :r "1"}]])

(defn artist-dot-album [artist album]
  (let [artist? (not (str/blank? artist))
        album? (not (str/blank? album))]

    (cond
      (and artist? album?) (list
                            [:div  artist]
                            [:div (icon-dot (css :h-2 :w-2))]
                            [:div  album])
      artist? artist
      album? album
      :else nil)))

(defn current-meta [{:keys [artist album title]}]
  [:div {:id "current-meta" :class (css  :flex :flex-col)}
   [:div {:class (css :text-xl :text-smoky-800 :font-bold [:dark :text-white])} title]
   [:div {:class (css :flex :items-center :gap-x-1 :text-base :text-smoky-800 :font-semibold [:dark :text-gray-500])}
    (artist-dot-album artist album)]])

(defn volume-bar [volume]
  [:input {:id "volume-slider" :type :range
           :min 0 :max 1 :step 0.01
           :hx-trigger "change"
           :ws-send true
           :hx-vals {:action "set-volume"}
           :name "volume"
           :value volume ;; :class "range range-xs"
           :class (cs "range-sm" (css :w-full :h-1 :rounded-lg  :appearance-none :cursor-pointer
                                      :bg-smoky-900 [:dark :bg-gray-700]))}])

(defn volume-icon
  ([volume muted?]
   (let [icon (cond
                (or (== 0 volume) (and (boolean? muted?) muted?)) :muted
                (< volume 0.5) :quiet
                :else :loud)]
     (icon
      {:muted [:svg {:id "volume-icon" :width "35" :height "35" :fill "currentColor" :xmlns "http://www.w3.org/2000/svg", :viewBox "0 0 30 30"} [:path {:d "M16.53 5.004v20a1 1 0 0 1-1.53.85l-8-5a3 3 0 0 0-1.47-.38h-4a1 1 0 0 1-1-1v-8.94a1 1 0 0 1 1-1h4a3 3 0 0 0 1.49-.4l8-5a1 1 0 0 1 1.51.87Zm8.41 10 4.29-4.29a1 1 0 0 0-1.41-1.41l-4.29 4.29-4.29-4.29a1 1 0 0 0-1.41 1.41l4.29 4.29-4.29 4.29a1 1 0 1 0 1.41 1.41l4.29-4.29 4.29 4.29a1 1 0 0 0 1.41-1.41z", :data-name "Layer 13"}]]
       :quiet [:svg {:id "volume-icon" :width "35" :height "35" :fill "currentColor" :xmlns "http://www.w3.org/2000/svg", :viewBox "0 0 30 30"} [:path {:d "M20.006 5.004v20a1 1 0 0 1-1.53.85l-8-5a3 3 0 0 0-1.47-.38h-4a1 1 0 0 1-1-1v-8.94a1 1 0 0 1 1-1h4a3 3 0 0 0 1.49-.4l8-5a1 1 0 0 1 1.51.87Zm4.53 15.2a10 10 0 0 0 0-10.4 1 1 0 0 0-1.71 1 8 8 0 0 1 0 8.31 1 1 0 1 0 1.71 1z", :data-name "Layer 14"}]]
       :loud [:svg {:id "volume-icon" :width "35" :height "35" :fill "currentColor" :xmlns "http://www.w3.org/2000/svg", :viewBox "0 0 30 30"} [:path {:d "M16.019 4.989v20a1 1 0 0 1-1.53.85l-8-5a3 3 0 0 0-1.47-.38h-4a1 1 0 0 1-1-1v-8.94a1 1 0 0 1 1-1h4a3 3 0 0 0 1.49-.4l8-5a1 1 0 0 1 1.51.87Zm10.21 21a18 18 0 0 0 0-22 1 1 0 1 0-1.58 1.2 16 16 0 0 1 0 19.61 1 1 0 1 0 1.58 1.19zm-2.83-2.88a14 14 0 0 0 0-16.31 1.005 1.005 0 0 0-1.62 1.19 12 12 0 0 1 0 14 1.003 1.003 0 0 0 1.63 1.17zm-2.85-3a10 10 0 0 0 0-10.4 1 1 0 0 0-1.71 1 8 8 0 0 1 0 8.31 1 1 0 1 0 1.71 1z", :data-name "Layer 16"}]]}))))

(defn player [{:keys [duration mrl track-number repeat-mode] :as current-track} {:keys [current-position current-volume current-time state muted?]}]
  (let [$button-base (css :transition-all :duration-500
                          :text-smoky-800
                          [:hover-mouse [:hover :scale-110]]
                          [:pointer-fine [:active :text-smoky-950 :scale-105]]
                          [:pointer-coarse [:active :text-smoky-950 :scale-125 :duration-500]]
                          [:dark :text-smoky-100 [:active :text-smoky-500]])]
    [:form {:id "player-controls" :ws-send true}
     [:div {:class (css :mt-6 :mb-10 :flex :flex-col
                        [:lg :flex-row :py-6 :max-w-5xl]
                        ;; [:sm :mt-10]
                        :relative :z-10 :rounded-xl :shadow-xl
                        :bg-white-rock-100
                        [:dark :bg-smoky-950])}
      ;; cover wrapper
      [:div {:class (css :px-6 :flex :flex-col :items-center :justify-center
                         :py-6
                         [:lg :py-0 :w-1of3])}
       [:img {:class (css :object-cover :w-64 :h-64)
              :style "transform: translateZ(0)"
              :src "/img/fairy.png"}]]
      ;; 2nd col
      [:div {:class (css :px-6 :flex :flex-col [:lg :w-half])}
       ;; meta wrapper
       (current-meta current-track)
       ;; progress bar
       [:div {:class (css :mt-6 :space-y-2)}
        (progress-bar current-position)
        [:div {:class (css :flex :justify-between :text-xs :leading-6 :font-medium :tabular-nums :text-smoky-500 [:dark :text-smoky-500])}
         (the-time current-time)
         ;; (the-length duration)
         (time-left current-time duration)]]
       ;; button wrapper
       [:div {:class (css :flex :justify-center :transition-all :duration-500 :gap-x-8)}
        ;; previous
        [:button {:value "previous" :name "action" :type :submit
                  :class (cs $button-base) :aria-label "Previous" :title "Previous"}
         [:svg {:width "25" :height "25" :fill "currentColor" :xmlns "http://www.w3.org/2000/svg", :viewBox "0 0 25 25"} [:path {:d "M18.5 5.63a1 1 0 0 0-1 0L8 11.11V6.5a1 1 0 0 0-2 0v12a1 1 0 0 0 2 0v-4.62l9.5 5.49a1 1 0 0 0 1.5-.87v-12a1 1 0 0 0-.5-.87Z", :data-name "Layer 25"}]]]
        ;; skip back
        [:button {:value "skip-back" :name "action" :type :submit :aria-label "Rewind 10 seconds" :title "Rewind 10 seconds"
                  :class (cs $button-base)}
         [:svg  {:width "25" :height "25" :fill "currentColor" :xmlns "http://www.w3.org/2000/svg", :viewBox "0 0 25 25"} [:path {:d "M23.39 5.635a1 1 0 0 0-1 0l-8.89 5.14v-4.27a1 1 0 0 0-1.5-.87l-10.39 6a1 1 0 0 0 0 1.73l10.39 6a1 1 0 0 0 1.5-.86v-4.27l8.89 5.13a1 1 0 0 0 1.5-.87V6.505a1 1 0 0 0-.5-.87z", :data-name "Layer 22"}]]]
        ;; play/pause
        (play-pause-button state)
        [:button {:value "skip-forward" :name "action" :type :submit :aria-label "Skip 10 seconds" :title "Skip 10 seconds"
                  :class (cs $button-base)}

         [:svg {:width "25" :height "25" :fill "currentColor" :xmlns "http://www.w3.org/2000/svg", :viewBox "0 0 25 25"} [:path {:d "M23.39 11.63 13 5.63a1 1 0 0 0-1.5.87v4.27L2.61 5.63a1 1 0 0 0-1.5.87v12a1 1 0 0 0 1.5.87l8.89-5.14v4.27a1 1 0 0 0 1.5.87l10.39-6a1 1 0 0 0 0-1.73z", :data-name "Layer 23"}]]]
        [:button {:value "next" :name "action" :type :submit :class (cs $button-base) :aria-label "Next" :title "Next"}
         [:svg {:width "25" :height "25" :fill "currentColor"  :xmlns "http://www.w3.org/2000/svg", :viewBox "0 0 25 25"} [:path {:d "M18 5.5a1 1 0 0 0-1 1v4.62L7.5 5.63A1 1 0 0 0 6 6.5v12a1 1 0 0 0 1.5.87l9.5-5.48v4.61a1 1 0 0 0 2 0v-12a1 1 0 0 0-1-1z", :data-name "Layer 26"}]]]]
       ;; volume slider wrapper
       [:div {:class (css :my-6 :flex :flex-row :items-center :gap-x-4)}
        [:button {:value "toggle-mute" :name "action" :type :submit :class (cs $button-base)} (volume-icon current-volume muted?)]
        (volume-bar current-volume)
        [:button {:value "volume-down-step" :name "action" :type :submit :class (cs $button-base)}
         [:svg {:width "35" :height "35" :fill "currentColor" :xmlns "http://www.w3.org/2000/svg", , :viewBox "0 0 30 30"} [:path {:d "M15 4C5.2 4 .293 15.849 7.222 22.778 14.152 29.707 26 24.8 26 15c0-6.075-4.935-11-11-11Zm4 12h-8c-1.333 0-1.333-2 0-2h8c1.333 0 1.333 2 0 2z"}]]]
        [:button {:value "volume-up-step" :name "action" :type :submit :class (cs $button-base)}
         [:svg {:width "35" :height "35" :fill "currentColor" :xmlns "http://www.w3.org/2000/svg", , :viewBox "0 0 30 30"} [:path {:d "M15 4a11 11 0 1 0 11 11A11 11 0 0 0 15 4Zm4 12h-3v3a1 1 0 0 1-2 0v-3h-3a1 1 0 0 1 0-2h3v-3a1 1 0 0 1 2 0v3h3a1 1 0 0 1 0 2z"}]]]]]]]))

(defn settings-option [label icon hx-get]
  [:li #_"<!-- Current: \"bg-gray-50 text-indigo-600\", Default: \"text-gray-700 hover:text-indigo-600 hover:bg-gray-50\" -->"
   [:a {:hx-get hx-get :hx-target "#settings-view" :hx-push-url hx-get
        :class (css :group :flex :gap-x-3 :rounded-md :p-2 :text-sm :leading-6 :font-semibold
                    :text-smoky-800
                    [:dark :text-smoky-400])}
    (icon {:class (css :h-6 :w-6 :shrink-0 :text-smoky-800 [:dark :text-smoky-400])})
    label]])

(defn settings-view [req]
  [:div {:class (css :px-10)}
   [:h1 {:class (css :text-2xl)} "Settings"]
   [:div {:id "settings-view"}
    [:nav {:class (css :flex :flex-1 :flex-col), :aria-label "Sidebar"}
     [:ul {:role "list", :class (css :-mx-2 :space-y-1)}
      (settings-option "RFID Tags" icons/radio-frequency "rfid-link-form-view")
      (settings-option "Files" icons/file-audio "rfid-link-form-view")
      (settings-option "Listening History" icons/clock-rotate-left "rfid-link-form-view")]]]])

(defn tab [name comp label active-tab extra-css]
  (let [$tab-base (css :rounded-lg :group :relative :min-w-0 :flex-1 :overflow-hidden
                       :py-2 :px-1 :text-center :text-sm :leading-normal :font-medium  [:focus :z-10]
                       :text-smoky-800
                       ;; [:hover :bg-smoky-800]
                       [:dark :text-smoky-500])
        $active-tab "tab-active"]
    [:a {:href "#"
         :data-tab-name name
         :hx-get comp :hx-target "#active-tab" :hx-push-url comp
         :hx-swap "outerHTML swap:0.1s settle:0.1s"
         :class (cs $tab-base (when (= name active-tab) $active-tab) extra-css)
         :aria-current "page"}
     [:span label]]))

(defn player-tabs [active-tab]
  [:div {:id "player-tabs" :hx-swap-oob "outerHTML :#player-tabs" :class (css :pt-2 :px-2 :max-w-5xl)}
   [:nav {:class (css :isolate :flex  :rounded-lg :shadow), :aria-label "Tabs"}
    (tab  :controls "player-controls" "Now Playing" active-tab nil)
    (tab  :play-queue "play-queue" "Play Queue" active-tab nil)
    (tab :settings "settings"
         [:svg {:xmlns "http://www.w3.org/2000/svg", :fill "none", :stroke "currentColor", :stroke-width "1.5", :class (css :w-6 :h-6) , :viewBox "0 0 24 24"} [:path {:stroke-linecap "round", :stroke-linejoin "round", :d "M9.594 3.94c.09-.542.56-.94 1.11-.94h2.593c.55 0 1.02.398 1.11.94l.213 1.281c.063.374.313.686.645.87.074.04.147.083.22.127.324.196.72.257 1.075.124l1.217-.456a1.125 1.125 0 0 1 1.37.49l1.296 2.247a1.125 1.125 0 0 1-.26 1.431l-1.003.827c-.293.24-.438.613-.431.992a6.759 6.759 0 0 1 0 .255c-.007.378.138.75.43.99l1.005.828c.424.35.534.954.26 1.43l-1.298 2.247a1.125 1.125 0 0 1-1.369.491l-1.217-.456c-.355-.133-.75-.072-1.076.124a6.57 6.57 0 0 1-.22.128c-.331.183-.581.495-.644.869l-.213 1.28c-.09.543-.56.941-1.11.941h-2.594c-.55 0-1.02-.398-1.11-.94l-.213-1.281c-.062-.374-.312-.686-.644-.87a6.52 6.52 0 0 1-.22-.127c-.325-.196-.72-.257-1.076-.124l-1.217.456a1.125 1.125 0 0 1-1.369-.49l-1.297-2.247a1.125 1.125 0 0 1 .26-1.431l1.004-.827c.292-.24.437-.613.43-.992a6.932 6.932 0 0 1 0-.255c.007-.378-.138-.75-.43-.99l-1.004-.828a1.125 1.125 0 0 1-.26-1.43l1.297-2.247a1.125 1.125 0 0 1 1.37-.491l1.216.456c.356.133.751.072 1.076-.124.072-.044.146-.087.22-.128.332-.183.582-.495.644-.869l.214-1.281z"}] [:path {:stroke-linecap "round", :stroke-linejoin "round", :d "M15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0z"}]]
         active-tab
         (css :grow-0 :shrink :min-w-min))]])

(defn play-queue-item [idx {:keys [meta current?] :as t}]
  (let [{:keys [title album artist]} meta
        $base (css :flex  :items-center :justify-between :gap-x-6 :gap-y-2 :py-2 :pl-2 :rounded-lg :shadow)
        $current (css :bg-white-rock-100 [:dark :bg-smoky-900])]
    [:li {:class (cs $base (when current? $current))}
     [:button {:type :submit :name "item-index" :value idx :class (css :text-left)}
      [:div {:class (css :flex :flex-col)}
       [:div {:class (css :font-bold :text-base :text-smoky-800 [:dark :text-smoky-300])} title]
       [:div {:class (css :flex :items-center :gap-x-1 :text-sm :font-semibold :text-smoky-700 [:dark :text-smoky-400])}
        (artist-dot-album artist album)]]]]))

(defn play-queue-list []
  (let
   [{:keys [tracks folder-path]} (audio/current-play-queue!)]
    [:form {:id "play-queue" :class "fade-in-out" :ws-send true :hx-vals {:action "play-queue-item"}}
     [:div {:class (css :flex :flex-col :mx-2 :gap-y-2 :text-smoky-800 [:dark :text-smoky-300])}
      [:div
       [:p {:class (css :text-lg :font-bold)} "Current Folder"]
       [:p {:class (css :ml-2 :text-smoky-700 [:dark :text-smoky-400])} "/" folder-path]]
      [:div
       [:p {:class (css :text-lg :font-bold)} "Folder Audio"]]
      [:ul {:role "list" :class (css :flex :flex-col :gap-y-2)}
       (map-indexed play-queue-item tracks)]]]))

(defn play-queue-tab []
  [:div {:id "active-tab"} (play-queue-list)])

(defn home-page [active-tab content]
  [:div {:id "home" :hx-ext "ws" :ws-connect "/api/ws"}
   (player-tabs active-tab)
   content])

(defcomponent ^:endpoint play-queue [req]
  (let [body (play-queue-tab)]
    (if (htmx? req)
      (trigger-response "tab-change" body {:data {:activeTab :play-queue}})
      (page-htmx (home-page :play-queue body)))))

(defcomponent ^:endpoint settings [req]
  (let [body [:div {:id "active-tab"} (settings-view req)]]
    (if (htmx? req)
      (trigger-response "tab-change" body {:data {:activeTab :settings}})
      (page-htmx (home-page :settings body)))))

(defn player-controls-tab []
  (let [current-track (audio/current-track!)
        current-playback (audio/current-playback!)]
    [:div {:id "active-tab"}
     [:div {:class "fade-in-out"}
      (player current-track current-playback)]]))

(defcomponent ^:endpoint player-controls [req]
  (let [body  (player-controls-tab)]
    (if (htmx? req)
      (trigger-response "tab-change" body {:data {:activeTab :controls}})
      (page-htmx (home-page :controls body)))))

(defcomponent ^:endpoint home [req]
  rfid-link play-queue player-controls play-queue-item settings
  (home-page :controls (player-controls-tab)))

(defn ui-routes [base-path]
  (simpleui/make-routes
   base-path
   (fn [req]
     (page-htmx (home req)))))

(comment

  :back-step
  [:svg {:xmlns "http://www.w3.org/2000/svg", :viewBox "0 0 25 25"} [:path {:d "M18.5 5.63a1 1 0 0 0-1 0L8 11.11V6.5a1 1 0 0 0-2 0v12a1 1 0 0 0 2 0v-4.62l9.5 5.49a1 1 0 0 0 1.5-.87v-12a1 1 0 0 0-.5-.87Z", :data-name "Layer 25"}]]
  :fast-forward
  [:svg {:xmlns "http://www.w3.org/2000/svg", :viewBox "0 0 25 25"} [:path {:d "M23.39 11.63 13 5.63a1 1 0 0 0-1.5.87v4.27L2.61 5.63a1 1 0 0 0-1.5.87v12a1 1 0 0 0 1.5.87l8.89-5.14v4.27a1 1 0 0 0 1.5.87l10.39-6a1 1 0 0 0 0-1.73z", :data-name "Layer 23"}]]
  :next-step
  [:svg {:xmlns "http://www.w3.org/2000/svg", :viewBox "0 0 25 25"} [:path {:d "M18 5.5a1 1 0 0 0-1 1v4.62L7.5 5.63A1 1 0 0 0 6 6.5v12a1 1 0 0 0 1.5.87l9.5-5.48v4.61a1 1 0 0 0 2 0v-12a1 1 0 0 0-1-1z", :data-name "Layer 26"}]]
  :pause
  [:svg {:xmlns "http://www.w3.org/2000/svg", :viewBox "0 0 30 30"} [:path {:d "M15 0a15 15 0 1 0 15 15A15 15 0 0 0 15 0Zm-1.56 19a1 1 0 0 1-1 1h-.89a1 1 0 0 1-1-1v-8a1 1 0 0 1 1-1h.89a1 1 0 0 1 1 1zm6 0a1 1 0 0 1-1 1h-.89a1 1 0 0 1-1-1v-8a1 1 0 0 1 1-1h.89a1 1 0 0 1 1 1z", :data-name "Layer 27"}]]
  :play
  [:svg {:xmlns "http://www.w3.org/2000/svg", :viewBox "0 0 30 30"} [:path {:d "M15 0a15 15 0 1 0 15 15A15 15 0 0 0 15 0Zm5 15.87-6.93 4a1 1 0 0 1-1.5-.87v-8a1 1 0 0 1 1.5-.87l6.93 4a1 1 0 0 1 0 1.73z", :data-name "Layer 28"}]]
  :rewind
  [:svg {:xmlns "http://www.w3.org/2000/svg", :viewBox "0 0 25 25"} [:path {:d "M23.39 5.635a1 1 0 0 0-1 0l-8.89 5.14v-4.27a1 1 0 0 0-1.5-.87l-10.39 6a1 1 0 0 0 0 1.73l10.39 6a1 1 0 0 0 1.5-.86v-4.27l8.89 5.13a1 1 0 0 0 1.5-.87V6.505a1 1 0 0 0-.5-.87z", :data-name "Layer 22"}]]
  :mute
  [:svg {:xmlns "http://www.w3.org/2000/svg", :viewBox "0 0 30 30"} [:path {:d "M16.53 5.004v20a1 1 0 0 1-1.53.85l-8-5a3 3 0 0 0-1.47-.38h-4a1 1 0 0 1-1-1v-8.94a1 1 0 0 1 1-1h4a3 3 0 0 0 1.49-.4l8-5a1 1 0 0 1 1.51.87Zm8.41 10 4.29-4.29a1 1 0 0 0-1.41-1.41l-4.29 4.29-4.29-4.29a1 1 0 0 0-1.41 1.41l4.29 4.29-4.29 4.29a1 1 0 1 0 1.41 1.41l4.29-4.29 4.29 4.29a1 1 0 0 0 1.41-1.41z", :data-name "Layer 13"}]])

:plus
[:svg {:xmlns "http://www.w3.org/2000/svg", :data-name "Layer 1", :viewBox "0 0 30 30"} [:path {:d "M15 4a11 11 0 1 0 11 11A11 11 0 0 0 15 4Zm4 12h-3v3a1 1 0 0 1-2 0v-3h-3a1 1 0 0 1 0-2h3v-3a1 1 0 0 1 2 0v3h3a1 1 0 0 1 0 2z"}]]
:minus
[:svg {:xmlns "http://www.w3.org/2000/svg", :data-name "Layer 1", :viewBox "0 0 30 30"} [:path {:d "M15 4C5.2 4 .293 15.849 7.222 22.778 14.152 29.707 26 24.8 26 15c0-6.075-4.925-11-11-11Zm4 12h-8c-1.333 0-1.333-2 0-2h8c1.333 0 1.333 2 0 2z"}]]

:volume-down
[:svg {:width "25" :height "25" :fill "currentColor" :xmlns "http://www.w3.org/2000/svg", :viewBox "0 0 30 30"} [:path {:d "M20.006 5.004v20a1 1 0 0 1-1.53.85l-8-5a3 3 0 0 0-1.47-.38h-4a1 1 0 0 1-1-1v-8.94a1 1 0 0 1 1-1h4a3 3 0 0 0 1.49-.4l8-5a1 1 0 0 1 1.51.87Zm4.53 15.2a10 10 0 0 0 0-10.4 1 1 0 0 0-1.71 1 8 8 0 0 1 0 8.31 1 1 0 1 0 1.71 1z", :data-name "Layer 14"}]]
:volume-up
[:svg {:width "25" :height "25" :fill "currentColor" :xmlns "http://www.w3.org/2000/svg", :viewBox "0 0 30 30"} [:path {:d "M16.019 4.989v20a1 1 0 0 1-1.53.85l-8-5a3 3 0 0 0-1.47-.38h-4a1 1 0 0 1-1-1v-8.94a1 1 0 0 1 1-1h4a3 3 0 0 0 1.49-.4l8-5a1 1 0 0 1 1.51.87Zm10.21 21a18 18 0 0 0 0-22 1 1 0 1 0-1.58 1.2 16 16 0 0 1 0 19.61 1 1 0 1 0 1.58 1.19zm-2.83-2.88a14 14 0 0 0 0-16.31 1.005 1.005 0 0 0-1.62 1.19 12 12 0 0 1 0 14 1.003 1.003 0 0 0 1.63 1.17zm-2.85-3a10 10 0 0 0 0-10.4 1 1 0 0 0-1.71 1 8 8 0 0 1 0 8.31 1 1 0 1 0 1.71 1z", :data-name "Layer 16"}]]
:download
[:svg {:width "25" :height "25" :fill "currentColor" :xmlns "http://www.w3.org/2000/svg", :viewBox "0 0 30 30"} [:path {:d "M15 0a15 15 0 1 0 15 15A15 15 0 0 0 15 0Zm7.51 15.9-6.81 6.81a1 1 0 0 1-1.41 0l-6.8-6.81a1 1 0 0 1 .71-1.71h3.08V8a1 1 0 0 1 1-1h5.44a1 1 0 0 1 1 1v6.19h3.08a1 1 0 0 1 .71 1.71z", :data-name "Layer 29"}]]
:playlist
[:svg {:width "25" :height "25" :fill "currentColor" :xmlns "http://www.w3.org/2000/svg", :viewBox "0 0 30 30"} [:path {:d "M30 1v4a1 1 0 0 1-1 1H9a1 1 0 0 1-1-1V1a1 1 0 0 1 1-1h20a1 1 0 0 1 1 1zm-1 7H9a1 1 0 0 0-1 1v4a1 1 0 0 0 1 1h20a1 1 0 0 0 1-1V9a1 1 0 0 0-1-1Zm0 8H9a1 1 0 0 0-1 1v4a1 1 0 0 0 1 1h20a1 1 0 0 0 1-1v-4a1 1 0 0 0-1-1zm0 8H9a1 1 0 0 0-1 1v4a1 1 0 0 0 1 1h20a1 1 0 0 0 1-1v-4a1 1 0 0 0-1-1ZM5.45 2.11l-4-2A1 1 0 0 0 0 1v4a1 1 0 0 0 1.45.89l4-2a1 1 0 0 0 0-1.79zm0 8-4-2A1 1 0 0 0 0 9v4a1 1 0 0 0 1.45.89l4-2a1 1 0 0 0 0-1.79zm0 8-4-2A1 1 0 0 0 0 17v4a1 1 0 0 0 1.45.89l4-2a1 1 0 0 0 0-1.79zm0 8-4-2A1 1 0 0 0 0 25v4a1 1 0 0 0 1.45.89l4-2a1 1 0 0 0 0-1.79z", :data-name "Layer 6"}]]
:volume-middle
[:svg {:width "25" :height "25" :fill "currentColor" :xmlns "http://www.w3.org/2000/svg", :viewBox "0 0 30 30"} [:path {:d "M18 5.004v20a1 1 0 0 1-1.53.85l-8-5a3 3 0 0 0-1.47-.38H3a1 1 0 0 1-1-1v-8.94a1 1 0 0 1 1-1h4a3 3 0 0 0 1.49-.4l8-5a1 1 0 0 1 1.51.87Zm7.38 18.15a14 14 0 0 0 0-16.31 1 1 0 0 0-1.62 1.16 12 12 0 0 1 0 14 1.003 1.003 0 0 0 1.63 1.17zm-2.85-3a10 10 0 0 0 0-10.4 1 1 0 0 0-1.71 1 8 8 0 0 1 0 8.31 1 1 0 1 0 1.71 1z", :data-name "Layer 15"}]]

(def credits [{:author "Yoyon Pujiyono"
               :link "https://thenounproject.com/icon/repeat-play-2447134/"
               :license "https://creativecommons.org/licenses/by/3.0/"}
              {:author "Yoyon Pujiyono"
               :link "https://thenounproject.com/icon/repeat-one-2447137/"
               :license "https://creativecommons.org/licenses/by/3.0/"}
              {:author "Astonish"
               :link "https://thenounproject.com/icon/album-cover-1433586/"
               :license "https://creativecommons.org/licenses/by/3.0/"}
              {:author "Yoyon Pujiyono"
               :link "https://thenounproject.com/icon/back-step-2506788/"
               :license "https://creativecommons.org/licenses/by/3.0/"}
              {:author "Yoyon Pujiyono"
               :link "https://thenounproject.com/icon/next-step-2506791/"
               :license "https://creativecommons.org/licenses/by/3.0/"}
              {:author "Yoyon Pujiyono"
               :link "https://thenounproject.com/icon/volume-mute-2506797/"
               :license "https://creativecommons.org/licenses/by/3.0/"}
              {:author "Yoyon Pujiyono"
               :link "https://thenounproject.com/icon/pause-2506789/"
               :license "https://creativecommons.org/licenses/by/3.0/"}
              {:author "Yoyon Pujiyono"
               :link "https://thenounproject.com/icon/play-2506787/"
               :license "https://creativecommons.org/licenses/by/3.0/"}
              {:author "Yoyon Pujiyono"
               :link "https://thenounproject.com/icon/fast-forward-2506785/"
               :license "https://creativecommons.org/licenses/by/3.0/"}
              {:license "https://creativecommons.org/licenses/by/3.0/"
               :author "Yoyon Pujiyono"
               :link "https://thenounproject.com/icon/rewind-2506784/"}
              {:license "https://creativecommons.org/licenses/by/3.0/"
               :author "Yoyon Pujiyono"
               :link "https://thenounproject.com/icon/middle-volume-2506798/"}
              {:license "https://creativecommons.org/licenses/by/3.0/"
               :author "Yoyon Pujiyono"
               :link "https://thenounproject.com/icon/volume-down-2506806/"}
              {:license "https://creativecommons.org/licenses/by/3.0/"
               :author "Yoyon Pujiyono"
               :link "https://thenounproject.com/icon/volume-up-2506805/"}
              {:license "https://creativecommons.org/licenses/by/3.0/"
               :author "Yoyon Pujiyono"
               :link "https://thenounproject.com/icon/download-2506781/"}
              {:license "https://creativecommons.org/licenses/by/3.0/"
               :author "Yoyon Pujiyono"
               :link "https://thenounproject.com/icon/play-list-2506807/"}

              {:author "Yoyon Pujiyono"
               :license "https://creativecommons.org/licenses/by/3.0/"
               :link "https://thenounproject.com/icon/new-3190873/"}
              {:author "Yoyon Pujiyono"
               :link "https://thenounproject.com/icon/disable-3190864/"
               :license "https://creativecommons.org/licenses/by/3.0/"}

              {:license "https://creativecommons.org/licenses/by/3.0/"
               :link "https://thenounproject.com/icon/radio-frequency-identification-4500829/"
               :author "Iconbunny"}])
