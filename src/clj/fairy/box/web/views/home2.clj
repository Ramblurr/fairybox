(ns fairy.box.web.views.home2
  (:require
   [hifi.html :as html]))

(defn render [_req]
  (tap> [:render-home2 :wutf])
  (html/->str
   [:main#morph.main
    [:h1 "Hello Fairy Box"]]))
