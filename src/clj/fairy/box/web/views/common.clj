(ns fairy.box.web.views.common
  (:require
   [clojure.string :as str]
   [fairy.box.web.views.icon :as icon]
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

;; @post('/player-cmd?action=set-volume'+'&volume='evt.target.value)
(defn player-cmd [cmd & {:as args}]
  (let [expr (format "'/player-cmd?action=%s'" cmd)]
    (format "@post(%s)"
            (if (seq args)
              (apply str expr (map #(format "+'&%s='+%s" (name (first %)) (second %)) args))
              expr))))
