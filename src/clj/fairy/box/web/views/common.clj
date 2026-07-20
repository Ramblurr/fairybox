(ns fairy.box.web.views.common
  (:require
   [clojure.string :as str]
   [fairy.box.audio.browse :as browse]
   [fairy.box.settings :as settings]
   [fairy.box.web.views.icon :as icon]
   [hyperlith.core :as h]
   [shadow.css :refer (css)]))

(defn cs [& names]
  (str/join " " (filter identity names)))

(defn tab [name href label active-tab extra-css]
  (let [$tab-base (css :rounded-lg :group :relative :min-w-0 :flex-1 :overflow-hidden
                       :py-4 :px-1 :text-center :text-lg :leading-normal :font-medium  [:focus :z-10]
                       :text-smoky-800
                       ;; [:hover :bg-smoky-800]
                       [:dark :text-smoky-500])
        $active-tab "tab-active"]
    [:a {:href href
         :data-tab-name name
         :class (cs $tab-base (when (= name active-tab) $active-tab) extra-css)
         :aria-current "page"}
     [:span label]]))

(defn player-tabs [{:keys [url-for] :as _req} active-tab]
  [:div {:id "player-tabs" :class (css :pt-2 :px-2 :max-w-5xl)}
   [:nav {:class (css :isolate :flex  :rounded-lg :shadow :mb-2), :aria-label "Tabs"}
    (tab :page/controls (url-for :page/home) "Now Playing" active-tab nil)
    (tab :page/queue (url-for :page/queue) "Play Queue" active-tab nil)
    (tab :page/settings (url-for :page/settings)
         (icon/cog {:class (css :w-8 :h-8)})
         active-tab
         (css :grow-0 :shrink :min-w-min))]])

(defn file-icon-for [{:keys [dir? media-file? playlist-file?]}]
  (cond
    dir? icon/folder-solid
    playlist-file? icon/file-audio
    media-file? icon/file-audio
    :else icon/file-solid))

(defn- directory-href [req rel-path]
  (str (:uri req)
       (when (seq rel-path)
         (h/url-query-string {:dir rel-path}))))

(defn file-row
  [req {:keys [mode active-value play-action]} idx
   {:keys [name rel-path abs-path dir?] :as file}]
  (let [$icon-color (css :text-smoky-900 [:dark :text-smoky-300])
        $icon-size (css :h-5 :w-5)
        $hover (css [:hover :bg-smoky-300]
                    [:dark [:hover :bg-smoky-800]])]
    [:tr
     [:td {:class (cs (css :py-4 :pl-0 :pr-3 :text-sm :font-medium
                           [:sm :pl-6] [:lg :pl-8]
                           :text-smoky-900
                           [:dark :text-smoky-300])
                      (when dir? $hover))}
      [(if dir? :a :div)
       (cond-> {:class (cs (if dir?
                             (css :cursor-pointer)
                             (css :cursor-default))
                           (css :flex :w-full))}
         dir? (assoc :href (directory-href req rel-path)))
       ((file-icon-for file)
        {:class (cs $icon-color $icon-size (css :mr-2))})
       name]]

     [:td {:class (css :whitespace-nowrap :px-3 :py-4 :text-sm :text-gray-500)}
      (condp = mode
        :play
        (when (browse/playable-type (settings/settings req) abs-path)
          [:button
           {:class (css :p-1 :transform-all :duration-200
                        [:hover-mouse [:hover :scale-125]])
            :data-on:click (str "@post('" play-action
                                (h/url-query-string {:path rel-path})
                                "')")}
           (icon/play {:class (cs $icon-color $icon-size)})])

        :choose
        (when (browse/playable-type (settings/settings req) abs-path)
          [:input {:id (str idx name)
                   :name "folder-item"
                   :type "radio"
                   :class (css :h-4 :w-4 :border-gray-300)
                   :required true
                   :checked (= active-value rel-path)
                   :value rel-path
                   :data-bind "selected_folder"}]))]]))

(defn file-table [req target-params files]
  [:table {:class (css :min-w-full :divide-y :divide-smoky-400)}
   [:thead {:class (css :text-smoky-900 [:dark :text-smoky-300])}
    [:th {:class (css :py-3.5 :pl-0 :pr-3 :text-left :text-sm :font-semibold
                      [:sm :pl-6] [:lg :pl-8])}
     "Name"]
    [:th {:class (css :px-3 :py-3.5 :text-left :text-sm :font-semibold)} ""]]
   [:tbody {:class (css :divide-y :divide-smoky-400)}
    (map-indexed (partial file-row req target-params) files)]])

(defn- breadcrumb-items [app-settings root-dir current-dir]
  (let [relative-path (browse/media-relative-path app-settings current-dir)
        components (if (seq relative-path)
                     (str/split relative-path #"/")
                     [])]
    (into [{:name (browse/basename root-dir) :rel-path nil}]
          (map-indexed
           (fn [idx name]
             {:name name
              :rel-path (str/join "/" (take (inc idx) components))})
           components))))

(defn file-breadcrumb [req app-settings root-dir current-dir]
  [:nav {:class (css :flex :pl-0 :py-2 [:sm :pl-6])
         :aria-label "Breadcrumb"}
   [:ol {:role "list" :class (css :flex :items-center :space-x-0)}
    (map (fn [{:keys [name rel-path]}]
           [:li
            [:div
             {:class (css :flex :items-center :text-sm :font-medium
                          :text-smoky-900 [:dark :text-smoky-300])}
             [:svg {:class (css :h-5 :w-5 :flex-shrink-0 :text-gray-300)
                    :xmlns "http://www.w3.org/2000/svg"
                    :fill "currentColor"
                    :viewbox "0 0 20 20"
                    :aria-hidden "true"}
              [:path {:d "M5.555 17.776l8-16 .894.448-8 16-.894-.448z"}]]
             [:a {:href (directory-href req rel-path)
                  :class (css :ml-0 [:hover :text-smoky-600])}
              name]]])
         (breadcrumb-items app-settings root-dir current-dir))]])

(defn- file-picker-main
  [req target-params root-dir current-dir]
  (let [app-settings (settings/settings req)
        current-dir (if (and current-dir
                             (browse/valid-dir? app-settings current-dir))
                      current-dir
                      root-dir)
        files (browse/list-contents root-dir current-dir)]
    [:div {:id "file-picker"}
     (file-breadcrumb req app-settings root-dir current-dir)
     (file-table req target-params files)]))

(defn browse-media-folder [req target-params current-dir]
  (let [app-settings (settings/settings req)
        media-base-path (browse/media-dir app-settings)
        current-dir (when (some? current-dir)
                      (browse/canonicalize-path app-settings current-dir))]
    (file-picker-main req
                      (merge {:mode :play} target-params)
                      media-base-path
                      current-dir)))

(h/refresh-all!)
