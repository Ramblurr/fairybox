(ns fairy.box.web.views.home
  (:require
   [clojure.core.async :as async]
   [fairy.box.db :as db]
   [fairy.box.audio :as audio]
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

(defn current-rfid [rfid-uid linked-folder]
  [:div {:id "current-rfid"}
   [:input {:type :hidden :value  rfid-uid
            :name "rfid-uid"}]
   [:input {:type :text :disabled true
            :class (css :block :flex-1 :border-0 :bg-transparent :py-1.5 :pl-1 :text-gray-900  :focus:ring-0 :sm:text-sm :sm:leading-6 :opacity-50 :cursor-not-allowed)
            :value (or rfid-uid "RFID Tag Not Present")}]

   (when (and rfid-uid linked-folder)
     [:input {:type :text :disabled true
              :class (css :block :flex-1 :border-0 :bg-transparent :py-1.5 :pl-1 :text-gray-900  :focus:ring-0 :sm:text-sm :sm:leading-6 :opacity-50 :cursor-not-allowed)
              :value linked-folder}])])

(defn broadcast-rfid-change! [db uid action]
  (reset! rfid-cache {:uid uid :action action})
  (broadcast! (partial-htmx (current-rfid (when (= action :placed) uid) (db/linked-folder db uid)))))

(declare progress-bar)
(declare the-time)

(defn broadcast-player-event! [event]
  ;; (tap> {:event event})
  (condp = (:event event)
    :player/position-changed (broadcast! (partial-htmx (progress-bar (:position event))))
    :player/time-changed (broadcast! (partial-htmx (the-time (:time event))))
    nil))

(defn ^:export ws-handler [{:keys [emitter]} {:keys [channel data]}]
  (let [payload (<-json data)]
    (tap> {:channel channel :data payload})
    (condp = (:action payload)
      "play-pause" (async/put! emitter {:path "/player/commands"
                                        :value {:action :audio/play-pause}})
      nil)

    #_(ws/send "<div id=\"thing\"> WOWOWWW</div>" channel)))

(defn folder-list [idx {:keys [name]}]
  [:div {:class (css :flex :items-center :gap-x-3)}
   [:input {:id (str idx name), :name "folder-item", :type "radio", :class (css :h-4 :w-4 :border-gray-300)
            :required true
            :value name}]
   [:label {:for (str idx name), :class (css :block :text-sm :font-medium :leading-6 :text-gray-900)} name]])

(defn rfid-link-form [uid linked-folder]
  (let  []
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
           (current-rfid uid linked-folder)]]]]

       [:div {:class (css :mt-6 :flex :items-center :justify-end :gap-x-6)}
        [:button {:type "button", :class (css :text-sm :font-semibold :leading-6 :text-gray-900)} "Cancel"]
        [:button {:type "submit", :class (css :rounded-md :bg-indigo-600 :px-3 :py-2 :text-sm :font-semibold :text-white :shadow-sm [:hover :bg-indigo-500] [:focus-visible :outline :outline-2 :outline-offset-2 :outline-indigo-600])}
         "Link To Folder"]]]]]))

(defcomponent ^:endpoint rfid-link [req folder-item rfid-uid]
  (tap> {:rfid rfid-uid :folder folder-item})
  (when (and (seq rfid-uid) (seq folder-item))
    (db/link-rfid-tag! (:db-conn (util/route-data req)) rfid-uid folder-item))
  (let [{:keys [uid action]} @rfid-cache
        rfid-uid (when (= action :placed) uid)
        linked-folder (db/linked-folder (util/req-db req) uid)]
    (rfid-link-form rfid-uid linked-folder)))

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
  (let [{:keys [days hours minutes seconds milliseconds]} (duration-data milliseconds)
        rounded-seconds (if (> milliseconds 0)
                          (inc seconds)
                          seconds)]
    (str (when (> days 0) (format "%02dd " days)) (when (> hours 0) (format "%02d:" hours)) (format "%02d" minutes) ":" (format "%02d" rounded-seconds))))

(defn progress-bar [current-position]
  (let [dur-str (if (float? current-position) (format "%.2f%%" (* 100 current-position)) "0%")
        left-str (format "left: %s" dur-str)
        width-str (format "width: %s" dur-str)]
    ;; (tap> dur-str)
    [:div {:id "progress-bar" :class (css :relative)}
     [:div {:class (css :bg-slate-100 :transition-all :duration-500 :dark:bg-slate-700 :rounded-full :overflow-hidden)}
      [:div {:class (css :bg-cyan-500 :transition-all :duration-500 :dark:bg-cyan-400 :h-2), :role "progressbar", :aria-label "music :progress", :aria-valuenow "1456", :aria-valuemin "0", :aria-valuemax "4550"
             :style width-str}]]
     [:div {:class (css :ring-cyan-500 :transition-all :duration-500 :dark:ring-cyan-400 :ring-2 :absolute :top-half :w-4 :h-4 :-mt-2 :-ml-2 :flex :items-center :justify-center :bg-white :rounded-full :shadow)
            :id "progress-bar-point"
            :style left-str}
      [:div {:class (css :w-1.5 :h-1.5 :bg-cyan-500 :transition-all :duration-500 :dark:bg-cyan-400 :rounded-full :ring-1 :ring-inset :ring-slate-900)}]]]))

(defn the-time [current-time]
  [:div {:id "current-time" :class (css :text-cyan-500 :transition-all :duration-500 :dark:text-slate-100)}
   (format-duration (or current-time 0))])

(defn player [{:keys [artist album duration title mrl track-number current-position current-time]}]
  [:form {:id "player-controls" :ws-send true}
   [:div {:class (css :mt-6 :sm:mt-10 :relative :z-10 :rounded-xl :shadow-xl)}
    [:div {:class (css :bg-white :border-slate-100 :transition-all :duration-500 :dark:bg-slate-800 :transition-all :duration-500 :dark:border-slate-500 :border-b :rounded-t-xl :p-4 :pb-6 :sm:p-10 :sm:pb-8 :lg:p-6 :xl:p-10 :xl:pb-8 :space-y-6 :sm:space-y-8 :lg:space-y-6 :xl:space-y-8)}
     [:div {:class (css :flex :items-center :space-x-4)}
      [:div {:class (css :flex-none :rounded-lg :bg-slate-100)}
       [:svg {:width "88" :height "88"  :xmlns "http://www.w3.org/2000/svg" :viewBox "0 0 88.441 74"} [:g  [:path {:d "M52 16.7a2 2 0 0 0-1.7-.3L25.6 23a2 2 0 0 0-1.4 1.9v20.2a9.3 9.3 0 0 0-4.5-1.1c-4.6 0-8.4 3.3-8.4 7.3s3.8 7.3 8.4 7.3c4.6 0 8.5-3.2 8.5-7.3v-16l20.6-5.6v9.9a9.3 9.3 0 0 0-4.4-1.1c-4.6 0-8.4 3.3-8.4 7.3s3.8 7.3 8.4 7.3c4.6 0 8.4-3.3 8.4-7.3V18.3a2 2 0 0 0-.8-1.6zm-32.3 38c-2.4 0-4.4-1.5-4.4-3.3 0-1.8 2-3.3 4.4-3.3 2.4 0 4.5 1.5 4.5 3.2 0 1.7-2.1 3.4-4.5 3.4zm8.5-23.6v-4.6l20.6-5.6v4.7zm16.2 18c-2.4 0-4.4-1.5-4.4-3.3 0-1.8 2-3.3 4.4-3.3 2.4 0 4.4 1.5 4.4 3.3 0 1.8-2.1 3.3-4.5 3.3z" :data-name "Compound Path"}] [:path {:d "M66 0H8a8 8 0 0 0-8 8v58a8 8 0 0 0 8 8h58a8 8 0 0 0 8-8v-1.4a33.5 33.5 0 0 0 0-55.1V8a8 8 0 0 0-8-8zm4 66a4 4 0 0 1-4 4H8a4 4 0 0 1-4-4V8a4 4 0 0 1 4-4h58a4 4 0 0 1 4 4v15.4a14.4 14.4 0 0 0 0 27.1zm14.5-29A29.4 29.4 0 0 1 74 59.6V49.1a2 2 0 0 0-1.5-1.9 10.4 10.4 0 0 1 0-20.3A2 2 0 0 0 74 25V14.4A29.4 29.4 0 0 1 84.5 37Z"}]]]]
      [:div {:class (css :min-w-0 :flex-auto :space-y-1 :font-semibold)}
       [:p {:class (css :text-cyan-500 :transition-all :duration-500 :dark:text-cyan-400 :text-sm :leading-6)}
        artist]
       [:h2 {:class (css :text-slate-500 :transition-all :duration-500 :dark:text-slate-400 :text-sm :leading-6 :truncate)} album]
       [:p {:class (css :text-slate-900 :transition-all :duration-500 :dark:text-slate-50 :text-lg)}
        (when track-number (str track-number " - "))
        title]]]
     [:div {:class (css :space-y-2)}
      (progress-bar current-position)
      [:div {:class (css :flex :justify-between :text-sm :leading-6 :font-medium :tabular-nums)}
       (the-time current-time)
       [:div {:class (css :text-slate-500 :transition-all :duration-500 :dark:text-slate-400)}
        (format-duration duration)]]]]
    [:div {:class (css :bg-slate-50 :text-slate-500 :transition-all :duration-500 :dark:bg-slate-600 :transition-all :duration-500 :dark:text-slate-200 :rounded-b-xl :flex :items-center)}
     [:div {:class (css :flex-auto :flex :items-center :justify-evenly)}
      #_[:button {:type "button" :aria-label "Add :to :favorites"}
         [:svg {:width "24" :height "24"}
          [:path {:d "M7 6.931C7 5.865 7.853 5 8.905 5h6.19C16.147 5 17 5.865 17 6.931V19l-5-4-5 4V6.931Z" :fill "currentColor" :stroke "currentColor" :stroke-width "2" :stroke-linecap "round" :stroke-linejoin "round"}]]]
      [:button {:value "previous" :name "action" :type :submit :class (css :sm:block :lg:hidden :xl:block) :aria-label "Previous" :title "Previous"}
       [:svg {:width "25" :height "25" :fill "currentColor" :xmlns "http://www.w3.org/2000/svg", :viewBox "0 0 25 25"} [:path {:d "M18.5 5.63a1 1 0 0 0-1 0L8 11.11V6.5a1 1 0 0 0-2 0v12a1 1 0 0 0 2 0v-4.62l9.5 5.49a1 1 0 0 0 1.5-.87v-12a1 1 0 0 0-.5-.87Z", :data-name "Layer 25"}]]
       #_[:svg {:width "24" :height "24" :fill "none"}
          [:path {:d "m10 12 8-6v12l-8-6Z" :fill "currentColor" :stroke "currentColor" :stroke-width "2" :stroke-linecap "round" :stroke-linejoin "round"}]
          [:path {:d "M6 6v12" :stroke "currentColor" :stroke-width "2" :stroke-linecap "round" :stroke-linejoin "round"}]]]
      [:button {:value "skip-back" :name "action" :type :submit :aria-label "Rewind 10 seconds" :title "Rewind 10 seconds"}
       [:svg  {:width "25" :height "25" :fill "currentColor" :xmlns "http://www.w3.org/2000/svg", :viewBox "0 0 25 25"} [:path {:d "M23.39 5.635a1 1 0 0 0-1 0l-8.89 5.14v-4.27a1 1 0 0 0-1.5-.87l-10.39 6a1 1 0 0 0 0 1.73l10.39 6a1 1 0 0 0 1.5-.86v-4.27l8.89 5.13a1 1 0 0 0 1.5-.87V6.505a1 1 0 0 0-.5-.87z", :data-name "Layer 22"}]]]]
     [:button {:value "play-pause" :name "action" :type :submit :class (css :bg-white :text-slate-900 :transition-all :duration-500 :dark:bg-slate-100 :transition-all :duration-500 :dark:text-slate-700 :flex-none :-my-2 :mx-auto :w-20 :h-20 :rounded-full :ring-1 :ring-slate-900 :shadow-md :flex :items-center :justify-center) :aria-label "Pause"}
      [:svg {:fill "currentColor" :xmlns "http://www.w3.org/2000/svg", :viewBox "0 0 30 30"} [:path {:d "M15 0a15 15 0 1 0 15 15A15 15 0 0 0 15 0Zm-1.56 19a1 1 0 0 1-1 1h-.89a1 1 0 0 1-1-1v-8a1 1 0 0 1 1-1h.89a1 1 0 0 1 1 1zm6 0a1 1 0 0 1-1 1h-.89a1 1 0 0 1-1-1v-8a1 1 0 0 1 1-1h.89a1 1 0 0 1 1 1z", :data-name "Layer 27"}]]]
     [:div {:class (css :flex-auto :flex :items-center :justify-evenly)}
      [:button {:value "skip-forward" :name "action" :type :submit :aria-label "Skip 10 seconds" :title "Skip 10 seconds"}
       [:svg {:width "25" :height "25" :fill "currentColor" :xmlns "http://www.w3.org/2000/svg", :viewBox "0 0 25 25"} [:path {:d "M23.39 11.63 13 5.63a1 1 0 0 0-1.5.87v4.27L2.61 5.63a1 1 0 0 0-1.5.87v12a1 1 0 0 0 1.5.87l8.89-5.14v4.27a1 1 0 0 0 1.5.87l10.39-6a1 1 0 0 0 0-1.73z", :data-name "Layer 23"}]]
       #_[:svg {:width "24" :height "24" :fill "none"}
          [:path {:d "M17.509 16.95c-2.862 2.733-7.501 2.733-10.363 0-2.861-2.734-2.861-7.166 0-9.9 2.862-2.733 7.501-2.733 10.363 0 .38.365.711.759.991 1.176" :stroke "currentColor" :stroke-width "2" :stroke-linecap "round" :stroke-linejoin "round"}]
          [:path {:d "M19 5v3.111c0 .491-.398.889-.889.889H15" :stroke "currentColor" :stroke-width "2" :stroke-linecap "round" :stroke-linejoin "round"}]]]
      [:button {:value "next" :name "action" :type :submit :class (css :sm:block :lg:hidden :xl:block) :aria-label "Next" :title "Next"}
       [:svg {:width "25" :height "25" :fill "currentColor"  :xmlns "http://www.w3.org/2000/svg", :viewBox "0 0 25 25"} [:path {:d "M18 5.5a1 1 0 0 0-1 1v4.62L7.5 5.63A1 1 0 0 0 6 6.5v12a1 1 0 0 0 1.5.87l9.5-5.48v4.61a1 1 0 0 0 2 0v-12a1 1 0 0 0-1-1z", :data-name "Layer 26"}]]
       #_[:svg {:width "24" :height "24" :fill "none"}
          [:path {:d "M14 12 6 6v12l8-6Z" :fill "currentColor" :stroke "currentColor" :stroke-width "2" :stroke-linecap "round" :stroke-linejoin "round"}]
          [:path {:d "M18 6v12" :stroke "currentColor" :stroke-width "2" :stroke-linecap "round" :stroke-linejoin "round"}]]]
      [:button {:value "repeat" :name "action" :type :submit :class (css :rounded-lg :text-xs :leading-6 :font-semibold :px-2 :ring-2 :ring-inset :ring-slate-500 :text-slate-500 :transition-all :duration-500 :dark:text-slate-100 :transition-all :duration-500 :dark:ring-0 :transition-all :duration-500 :dark:bg-slate-500)}
       [:svg {:width "24" :height "24" :fill "currentColor"
              :xmlns "http://www.w3.org/2000/svg", :viewBox "0 0 30 30"} [:path {:d "M23 3.976H9l1.12-1.12a1 1 0 0 0-1.41-1.42l-2.83 2.83a1 1 0 0 0 0 1.41l2.83 2.84a1 1 0 0 0 1.41-1.41L9 5.976h14a5 5 0 0 1 5 5v8a1 1 0 0 0 2 0v-8a7 7 0 0 0-7-7Zm-1.71 17.46a1 1 0 0 0-1.41 1.41l1.12 1.13H7a5 5 0 0 1-5-5v-8a1 1 0 0 0-2 0v8a7 7 0 0 0 7 7h14l-1.12 1.12a1 1 0 1 0 1.41 1.41l2.83-2.83a1 1 0 0 0 0-1.41z"}]]]
      [:button {:value "repeat-one" :name "action" :type :submit :class (css :rounded-lg :text-xs :leading-6 :font-semibold :px-2 :ring-2 :ring-inset :ring-slate-500 :text-slate-500 :transition-all :duration-500 :dark:text-slate-100 :transition-all :duration-500 :dark:ring-0 :transition-all :duration-500 :dark:bg-slate-500)}
       [:svg {:width "24" :height "24" :fill "currentColor"
              :xmlns "http://www.w3.org/2000/svg", :viewBox "0 0 30 30"} [:g {:data-name "Layer 3"} [:path {:d "M23 3.976H9l1.12-1.12a1 1 0 0 0-1.41-1.42l-2.83 2.83a1 1 0 0 0 0 1.41l2.83 2.83a1 1 0 0 0 1.41-1.41L9 5.976h14a5 5 0 0 1 5 5v8a1 1 0 0 0 2 0v-8a7 7 0 0 0-7-7Zm-1.71 17.46a1 1 0 0 0-1.41 1.41l1.12 1.13H7a5 5 0 0 1-5-5v-8a1 1 0 0 0-2 0v8a7 7 0 0 0 7 7h14l-1.12 1.12a1 1 0 1 0 1.41 1.41l2.83-2.83a1 1 0 0 0 0-1.41z"}] [:path {:d "M15 19.976a1 1 0 0 0 1-1v-8a1 1 0 0 0-1.71-.71l-2 2a1 1 0 0 0 1.41 1.41l.29-.29v5.59a1 1 0 0 0 1.01 1Z"}]]]]]]]])

(defcomponent ^:endpoint home [req]
  rfid-link
  (tap> {:route-data (util/route-data req)})
  (let [{:keys [uid action]} @rfid-cache
        rfid-uid (when (= action :placed) uid)
        linked-folder (db/linked-folder (util/req-db req) uid)
        current-track (audio/current-track!)]
    [:div {:id "home" :class (css :px-10) :hx-ext "ws" :ws-connect "/api/ws"}
     (player current-track)
     [:h1 {:class (css :text-2xl)} "Settings"]
     (rfid-link-form rfid-uid linked-folder)
     #_[:div
        "WEBSOCK"
        [:div {:hx-ext "ws"  :ws-connect "/api/ws"}
         [:div {:id "thing"}]

         [:form {:hx-ws "send" :id "ws-form"}
          [:input {:name "input" :value "hello"}]
          [:button {:type :submit} "Send"]]]]]))

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
               :link "https://thenounproject.com/icon/rewind-2506784/"}])
