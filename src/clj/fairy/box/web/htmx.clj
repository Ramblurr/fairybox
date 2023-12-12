(ns fairy.box.web.htmx
  (:require
   [simpleui.render :as render]
   [ring.util.http-response :as http-response]
   [hiccup2.core :as h2]
   [hiccup.core :as h]
   [hiccup.page :as p]))

(defn page [opts & content]
  (-> (p/html5 opts content)
      http-response/ok
      (http-response/content-type "text/html")))

(defn ui [opts & content]
  (-> (h/html opts content)
      http-response/ok
      (http-response/content-type "text/html")))

(defn page-htmx [& body]
  (page
   [:head
    [:meta {:charset "UTF-8"}]
    [:meta {:name    "viewport"
            :content "width=device-width, initial-scale=1, shrink-to-fit=no"}]
    [:title "Htmx + Kit"]
    [:link {:rel "stylesheet" :href "/css/tailwind.css"}]
    [:link {:rel "stylesheet" :href "/css/fairybox.css"}]
    [:script {:src "/js/htmx.org@1.9.9.js" :defer true}]
    [:script {:src "/js/htmx-ws@1.9.9.js" :defer true}]]
   [:body (render/walk-attrs body)]))

(defn partial-htmx [body]
  (str
   (h2/html {:mode :html}
            (render/walk-attrs body))))
