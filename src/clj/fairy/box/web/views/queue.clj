(ns fairy.box.web.views.queue
  (:require
   [clojure.string :as str]
   [fairy.box.audio.browse :as browse]
   [fairy.box.audio.current :as player]
   [fairy.box.settings :as app-settings]
   [fairy.box.switchboard :as switchboard]
   [fairy.box.ui3 :as ui3]
   [fairy.box.web.views.common :as uic :refer [cs]]
   [hyperlith.core :as h :refer [defaction defview]]
   [shadow.css :refer [css]]))

(defaction play-queue-item
  [{:fairy.box/keys [component] :as req}]
  (let [requested-index (get-in req [:query-params "item-index"])
        item-index (when (string? requested-index)
                     (parse-long requested-index))
        tracks (player/full-queue (player/current!))]
    (when (and (some? item-index)
               (some #(= item-index (:index %)) tracks))
      (when-let [controller
                 (component :fairy.box.switchboard/switchboard)]
        (switchboard/emit-player!
         (:emitter controller)
         {:action :audio/play-queue-index
          :item-index item-index})))))

(defn icon-dot [$class]
  [:svg {:viewbox "0 0 2 2"
         :class (cs (css :fill-current) $class)}
   [:circle {:cx "1" :cy "1" :r "1"}]])

(defn artist-dot-album [artist album]
  (let [artist? (not (str/blank? artist))
        album? (not (str/blank? album))]
    (cond
      (and artist? album?) (list
                            [:div artist]
                            [:div (icon-dot (css :h-2 :w-2))]
                            [:div album])
      artist? artist
      album? album
      :else nil)))

(defn play-queue-item-view [nat-idx {:keys [meta index]}]
  (let [{:meta/keys [title album artist]} meta
        $base (css :flex :items-center :justify-between :gap-x-6 :gap-y-2
                   :py-2 :pl-2 :rounded-lg :shadow)
        $current (css :bg-smoky-300 [:dark :bg-smoky-900])]
    [:li {:class (cs $base (when (= 0 index) $current))}
     [:button
      {:class (css :w-full :text-left)
       :data-on:click (str "@post('"
                           play-queue-item
                           (h/url-query-string {:item-index index})
                           "')")}
      [:div {:class (css :flex :flex-row :gap-x-2)}
       [:div {:class (css :self-center)}
        [:span
         {:class (css :inline-flex :items-center :rounded-full :px-2 :py-1
                      :text-xs :font-medium :ring-1 :ring-offset-0 :ring-inset
                      :ring-smoky-600 :text-smoky-600)}
         (inc nat-idx)]]
       [:div {:class (css :flex :flex-col)}
        [:div
         {:class (css :font-bold :text-base :text-smoky-800
                      [:dark :text-smoky-300])}
         title]
        [:div
         {:class (css :flex :items-center :gap-x-1 :text-sm :font-semibold
                      :text-smoky-700 [:dark :text-smoky-400])}
         (artist-dot-album artist album)]]]]]))

(defn source-path-breadcrumb
  [{:fairy.box/keys [component] :keys [url-for] :as req} source-path]
  (if-not (and (seq source-path) (ifn? component) (ifn? url-for))
    source-path
    (let [settings (app-settings/settings req)
          canonical-path (browse/canonicalize-path settings source-path)]
      (if-not canonical-path
        source-path
        (let [relative-path (browse/media-relative-path settings canonical-path)
              names (str/split relative-path #"/")
              relative-paths (reductions #(str %1 "/" %2) names)]
          (interpose
           " / "
           (map (fn [name relative-path]
                  (let [path (browse/canonicalize-path settings relative-path)]
                    (if (browse/valid-dir? settings path)
                      [:a
                       {:href (str (url-for :page.settings/browse)
                                   (h/url-query-string {:dir relative-path}))
                        :class (css :ml-0 [:hover :text-smoky-600])}
                       name]
                      name)))
                names
                relative-paths)))))))

(defn play-queue-list
  [{:keys [tracks source-type source-path] :as req}]
  [:div {:id "play-queue" :class "fade-in-out"}
   [:div
    {:class (css :flex :flex-col :mx-2 :gap-y-2 :text-smoky-800
                 [:dark :text-smoky-300])}
    [:div
     [:p {:class (css :text-lg :font-bold)}
      (if (= source-type :playlist) "Playlist" "Folder")]
     [:p
      {:class (css :ml-2 :text-smoky-700 [:dark :text-smoky-400])}
      (source-path-breadcrumb req source-path)]]
    [:div
     [:p {:class (css :text-lg :font-bold)} "Tracks"]]
    [:ul {:role "list" :class (css :flex :flex-col :gap-y-2)}
     (map-indexed play-queue-item-view tracks)]]])

(defview render-queue {:path "/queue" :shim-headers ui3/shim-headers}
  [req]
  (let [current (player/current!)
        req (assoc req
                   :tracks (player/full-queue current)
                   :source-type (player/queue-source-type current)
                   :source-path (player/queue-source-path current))]
    (h/html
     (ui3/css-reload)
     [:main#morph.main
      [:div {}
       (uic/player-tabs req :page/queue)
       [:div {:id "active-tab"}
        [:div {:class "fade-in-out"}
         (play-queue-list req)]]]])))

(h/refresh-all!)