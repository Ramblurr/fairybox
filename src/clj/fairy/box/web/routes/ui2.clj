(ns fairy.box.web.routes.ui2
  (:require

   [clojure.tools.logging :as log]
   [clojure.core.async :as async]
   [jp.nijohando.event :as ev]
   [fairy.box.web.routes.commands :as commands]
   [fairy.box.web.views.player :as player]
   [fairy.box.web.views.settings :as settings]
   [fairy.box.web.views.queue :as queue]
   [hifi.config :as config]
   [hifi.datastar :as datastar]
   [hifi.datastar.http-kit :as d*http-kit]
   [hifi.html :as html]
   [hifi.system.middleware :as hifi.mw]
   [hifi.util.assets :as assets]))

(defn pages []
  {:page/home {:path  "/" :render #'player/render}
   :page/queue {:path  "/queue" :render #'queue/render}
   :page/settings {:path  "/settings" :render #'settings/render}})

(def static-asset (partial assets/static-asset (config/dev?)))
(def !css0 (static-asset {:resource-path "public/css/tailwind.css" :route-path "/tailwind.css" :content-type "text/css"}))
(def !css1 (static-asset {:resource-path "public/css/generated.css" :route-path "/generated.css" :content-type "text/css"}))
(def !css2 (static-asset {:resource-path "public/css/vars.css" :route-path "/vars.css" :content-type "text/css"}))
(def !css3 (static-asset {:resource-path "public/css/range.css" :route-path "/range.css" :content-type "text/css"}))
(def !css4 (static-asset {:resource-path "public/css/fairybox.css" :route-path "/fairybox.css" :content-type "text/css"}))
(def !datastar datastar/!datastar-asset)
(def !datastar-inspector (static-asset {:resource-path "public/js/datastar-inspector.js" :route-path "/datastar-inspector.js" :content-type "application/javascript"}))
(def shim-assets (list
                  [:meta {:name "color-scheme" :content "dark"}]
                  [:meta {:name "darkreader-lock"}]
                  (html/script {:defer true :type "module" :!asset !datastar})
                  (html/stylesheet {:!asset !css0})
                  (html/stylesheet {:!asset !css1})
                  (html/stylesheet {:!asset !css2})
                  (html/stylesheet {:!asset !css3})
                  (html/stylesheet {:!asset !css4})
                  (when (config/dev?)
                    (html/script {:defer true :type "module" :!asset !datastar-inspector}))))

(def shim-response (html/shim-page-resp {:body
                                         (html/shim-document
                                          {:title          "Fairy Box"
                                           :csrf-cookie-js (when (config/dev?) html/csrf-cookie-js-dev)
                                           :head           shim-assets
                                           :body-post      (html/compile
                                                            [:div
                                                             [:datastar-inspector]
                                                             [:svg {:style "display: none"}
                                                              [:symbol {:id "svg-sprite-spinner" :fill "none", :viewbox "0 0 24 24"}
                                                               [:circle {:class "opacity-25", :cx "12", :cy "12", :r "10", :stroke "currentColor", :stroke-width "4"}]
                                                               [:path {:class "opacity-75", :fill "currentColor", :d "M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"}]]]])})}))

(def shim-handler (html/shim-handler shim-response))

(defn pages->routes [pages]
  (->> (pages)
       (mapv (fn [[page-route-name {:keys [path render]}]]
               [path {:name page-route-name
                      :get  shim-handler
                      :post (d*http-kit/render-handler render)}]))

       (into [""])))

(defn routes []
  ["" {:middleware (conj hifi.mw/hypermedia-chain :fairy.box/middleware)}
   (pages->routes pages)
   (assets/asset->route !css0)
   (assets/asset->route !css1)
   (assets/asset->route !css2)
   (assets/asset->route !css3)
   (assets/asset->route !css4)
   (assets/asset->route !datastar)
   commands/commands
   (when (config/dev?)
     (assets/asset->route !datastar-inspector))])

(defn handle-event [opts event]
  (datastar/rerender-all!))

(defn start-loop! [{:keys [bus db-conn settings env <emitter <listener] :as opts}]
  (async/go-loop []
    (when-some [event (async/<! <listener)]
      (try
        (handle-event opts event)
        (catch Exception e
          (log/error e "sse broadcaster: error handling event" event)))
      (recur))))

(defn init-sse-broacast! [{:keys [bus db-conn settings env] :as opts}]
  (let [<listener (async/chan)
        <emitter (async/chan)]
    (ev/emitize bus <emitter)
    (ev/listen bus "/hardware/input/rfid" <listener)
    (ev/listen bus "/player/events" <listener)

    (start-loop! (-> opts
                     (assoc
                      :<listener <listener
                      :<emitter <emitter)))
    {:<listener <listener
     :<emitter <emitter}))

(defn stop-sse-broadcast! [{:keys [<emitter <listener]}]
  (when <emitter
    (async/close! <emitter))
  (when <listener
    (async/close! <listener)))

(def SSEBroadcastComponent
  "Component that listens to events from the bus and emits them over SSE to clients"
  {:donut.system/start  (fn [{config :donut.system/config}]
                          (init-sse-broacast! config))
   :donut.system/stop   (fn [{:donut.system/keys [instance]}]
                          (stop-sse-broadcast! instance))
   :donut.system/config {:env        [:donut.system/ref [:env]]
                         :bus        [:donut.system/ref [:fairy.box/components :fairy.box.bus/bus]]
                         :settings   [:donut.system/ref [:fairy.box/components :fairy.box/settings]]
                         :db-conn    [:donut.system/ref [:fairy.box/components :fairy.box.db/db]]}})
