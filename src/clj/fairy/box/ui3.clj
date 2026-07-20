(ns fairy.box.ui3
  (:require
   [clojure.java.shell :as shell]
   [fairy.box.css :as css]
   [hyperlith.core :as h]))

#_(def css
    (h/static-css
     [["*, *::before, *::after"
       {:box-sizing :border-box
        :margin     0
        :padding    0}]]))

(defn compile-css! [in]
  (let [{:keys [exit err out]} (shell/sh "lightningcss"
                                         "--bundle"
                                         "--custom-media"
                                         "--targets" "defaults"
                                         in)]
    (when-not (zero? exit)
      (throw (ex-info "Lightning CSS compilation failed"
                      {:exit exit :stderr err})))
    out))

#_(def tailwind-css (h/static-asset {:body (h/load-resource "public/css/tailwind.css") :content-type "text/css"}))
(defn fairybox-css [] (h/static-asset {:body (compile-css! "resources/public/css/fairybox.css") :content-type "text/css"}))
(defn shadow-css [] (h/static-asset {:body (css/generate-css) :content-type "text/css"}))

(def shim-headers
  (h/html
   #_[:link#css {:rel "stylesheet" :type "text/css" :href css}]
   #_[:link#css {:rel "stylesheet" :type "text/css" :href tailwind-css}]
   [:link#css1 {:rel "stylesheet" :type "text/css" :href (shadow-css)}]
   [:link#css2 {:rel "stylesheet" :type "text/css" :href (fairybox-css)}]
   [:title nil "Fairy Box"]
   [:meta {:content "Fairy Box" :name "description"}]))

(defn css-reload []
  [[:link#css1 {:rel "stylesheet" :type "text/css" :href (shadow-css)}]
   [:link#css2 {:rel "stylesheet" :type "text/css" :href (fairybox-css)}]])

(h/refresh-all!)
