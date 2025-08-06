(ns fairy.box.web.views.queue
  (:require
   [clojure.string :as str]
   [shadow.css :refer [css]]
   [fairy.box.audio.current :as player]
   [fairy.box.web.views.common :as uic :refer [cs]]
   [hifi.datastar :as datastar]
   [hifi.html :as html]))

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

(defn play-queue-item [nat-idx {:keys [meta index]}]
  (let [{:meta/keys [title album artist]} meta
        $base (css :flex  :items-center :justify-between :gap-x-6 :gap-y-2 :py-2 :pl-2 :rounded-lg :shadow)
        $current (css :bg-smoky-300 [:dark :bg-smoky-900])]
    [:li {:class (cs $base (when (= 0 index) $current))}
     [:button {:class (css :text-left) :data-on-click (uic/player-cmd "play-queue-item" :item-index index)}
      [:div {:class (css :flex :flex-row :gap-x-2)}
       [:div {:class (css :self-center)}
        [:span {:class (css :inline-flex :items-center :rounded-full :px-2 :py-1 :text-xs :font-medium :ring-1 :ring-offset-0 :ring-inset
                            :ring-smoky-600 :text-smoky-600)} (inc nat-idx)]]
       [:div {:class (css :flex :flex-col)}
        [:div {:class (css :font-bold :text-base :text-smoky-800 [:dark :text-smoky-300])} title]
        [:div {:class (css :flex :items-center :gap-x-1 :text-sm :font-semibold :text-smoky-700 [:dark :text-smoky-400])}
         (artist-dot-album artist album)]]]]]))

(defn play-queue-list [{:keys [tracks]}]
  [:div {:id "play-queue" :class "fade-in-out"}
   [:div {:class (css :flex :flex-col :mx-2 :gap-y-2 :text-smoky-800 [:dark :text-smoky-300])}
    [:div
     [:p {:class (css :text-lg :font-bold)} "Tracks"]]
    [:ul {:role "list" :class (css :flex :flex-col :gap-y-2)}
     (map-indexed play-queue-item tracks)]]])

(defn render [req]
  (let [req  (assoc req :tracks (player/full-queue (player/current!)))]
    (html/->str
     [:main#morph.main
      [:div
       (uic/player-tabs req :page/queue)
       [:div {:id "active-tab"}
        [:div {:class "fade-in-out"}
         (play-queue-list req)]]]])))

(datastar/rerender-all!)
