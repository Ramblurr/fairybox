(ns fairy.box.web.views.settings
  (:require
   [fairy.box.audio.current :as player]
   [fairy.box.web.views.common :as uic]
   [hifi.datastar :as datastar]
   [hifi.html :as html]))

(defn render [req]
  (let [state {:current (player/current!)}]
    (html/->str
     [:main#morph.main
      [:div
       (uic/player-tabs req :page/settings)]])))

(datastar/rerender-all!)
