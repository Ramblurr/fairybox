(ns fairy.box.web.views
  (:require
   [fairy.box.web.views.player]
   [fairy.box.web.views.queue]
   [fairy.box.web.views.settings]))

(def pages
  {:page/home               {:path "/"}
   :page/queue              {:path "/queue"}
   :page/settings           {:path "/settings"}
   :page.settings/rfid-link {:path "/settings/rfid"}
   :page.settings/browse    {:path "/settings/browse"}
   :page.settings/playback  {:path "/settings/playback"}})

(defn url-for [r]
  (get-in pages [r :path] "/404"))
