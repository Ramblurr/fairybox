(ns fairy.box.web.views.home
  (:require
   [fairy.box.bus :as bus]
   [shadow.css :refer (css)]
   [simpleui.core :as simpleui :refer [defcomponent]]
   [fairy.box.web.htmx :refer [page-htmx]]))

(defcomponent ^:endpoint current-rfid [req]
  [:div {:hx-trigger "every 2s" :hx-get "current-rfid" :hx-target "#current-rfid" :id "current-rfid"}
   [:p {:class (css :text-base [:dark :text-xl])} "Current RFID: " [:span (bus/current-rfid!)]]])

(defn ui-routes [base-path]
  (simpleui/make-routes
   base-path
   (fn [req]
     (page-htmx
      [:div {:class (css :px-10)}
       [:h1 {:class (css :text-2xl)} "Settings"]
       (current-rfid req)]))))
