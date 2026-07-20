;; Copyright © 2025 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2
(ns fairy.box.web.htmx
  (:require
   [cheshire.core :as cheshire]
   [simpleui.render :as render]
   [ring.util.http-response :as http-response]
   [hiccup2.core :as h2]
   [hiccup.core :as h]
   [hiccup.page :as p]
   [hiccup.util :as hiccup.util]))

(defmacro html5-safe
  "Create a HTML5 document with the supplied contents. Using hiccup2.core/html to auto escape strings"
  [options & contents]
  (if-not (map? options)
    `(html5-safe {} ~options ~@contents)
    (if (options :xml?)
      `(let [options# (dissoc ~options :xml?)]
         (str (h2/html {:mode :xml}
                       (p/xml-declaration (options# :encoding "UTF-8"))
                       (p/doctype :html5)
                       (p/xhtml-tag options# (options# :lang) ~@contents))))
      `(let [options# (dissoc ~options :xml?)]
         (str (h2/html {:mode :html}
                       (p/doctype :html5)
                       [:html options# ~@contents]))))))

(defn page [opts & content]
  (-> (html5-safe opts content)
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
    [:meta {:name "color-scheme" :content "dark"}]
    [:meta {:name "darkreader-lock"}]
    [:title "Fairybox"]
    [:link {:rel "stylesheet" :href "/css/tailwind.css?v4"}]
    [:link {:rel "stylesheet" :href "/css/fairybox.css?v4"}]
    [:script {:src "/js/htmx.org@1.9.9.js" :defer true}]
    [:script {:src "/js/htmx-ws@1.9.9.js" :defer true}]
    [:script {:src "/js/fairybox.js" :defer true}]]
   [:body (render/walk-attrs body)]))

(defn partial-htmx [& body]
  (str
   (h2/html {:mode :html}
            (render/walk-attrs body))))
(defn htmx? [req]
  (= "true"
     (get-in req [:headers "hx-request"] false)))

(def hx-trigger-types
  {:hx-trigger              "HX-Trigger"
   :hx-trigger-after-settle "HX-Trigger-After-Settle"
   :hx-trigger-after-swap   "HX-Trigger-After-Swap"})

(defn trigger-response
  ([trigger-name body]
   (trigger-response trigger-name body {}))
  ([trigger-name body {:keys [trigger-type data]
                       :or   {trigger-type :hx-trigger}}]
   {:status  200
    :headers {"Content-Type" "text/html" (get hx-trigger-types trigger-type)
              (if data
                (cheshire/generate-string {trigger-name data})
                trigger-name)}
    :body    (partial-htmx body)}))

(comment

  (page
   (render/walk-attrs
    [:button {:hx-vals {:action "play-pause"} :type :submit}]))
  ;; => {:body "<!DOCTYPE html>\n<html><button hx-vals=\"{&quot;action&quot;:&quot;play-pause&quot;}\" type=\"submit\"></button></html>",
  ;;     :headers {"Content-Type" "text/html"},
  ;;     :status 200}
  (render/walk-attrs
   [:button {:hx-vals {:action "play-pause"} :type :submit}])
  (require '[hiccup.util :as hiccup.util])
  (page
   [:button {:hx-vals (hiccup.util/raw-string "{\"action\":\"play-pause\"}"), :type :submit}])
  ;;
  )