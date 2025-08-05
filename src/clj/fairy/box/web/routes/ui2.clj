(ns fairy.box.web.routes.ui2
  (:require
   [fairy.box.web.views.home2 :as home]
   [hifi.config :as config]
   [hifi.datastar :as datastar]
   [hifi.datastar.http-kit :as d*http-kit]
   [hifi.html :as html]
   [hifi.system.middleware :as hifi.mw]
   [hifi.util.assets :as assets]))

(defn pages []
  {:app.home/home {:path   "/"
                   :render home/render}})

(def static-asset (partial assets/static-asset (config/dev?)))
(def !css0 (static-asset {:resource-path "public/css/tailwind.css" :route-path "/tailwind.css" :content-type "text/css"}))
(def !css1 (static-asset {:resource-path "public/css/generated.css" :route-path "/generated.css" :content-type "text/css"}))
(def !css2 (static-asset {:resource-path "public/css/vars.css" :route-path "/vars.css" :content-type "text/css"}))
(def !css3 (static-asset {:resource-path "public/css/range.css" :route-path "/range.css" :content-type "text/css"}))
(def !css4 (static-asset {:resource-path "public/css/fairybox.css" :route-path "/fairybox.css" :content-type "text/css"}))
(def !datastar datastar/!datastar-asset)
(def !datastar-inspector (static-asset {:resource-path "public/js/datastar-inspector.js" :route-path "/datastar-inspector.js" :content-type "application/javascript"}))
(def !fairybox-js (static-asset {:resource-path "public/js/fairybox.js" :route-path "/fairybox.js" :content-type "application/javascript"}))
(def shim-assets (list (html/script {:defer true :type "module" :!asset !datastar})
                       (html/script {:defer true :type "module" :!asset !fairybox-js})
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
   (assets/asset->route !fairybox-js)
   (when (config/dev?)
     (assets/asset->route !datastar-inspector))])
