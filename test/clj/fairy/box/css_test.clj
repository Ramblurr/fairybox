;; Copyright © 2025 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2
(ns fairy.box.css-test
  (:require
   [clojure.test :refer [deftest is]]
   [fairy.box.css :as css]
   [fairy.box.web.views.ui :as ui]
   [hyperlith.core :as h]))

(deftest uses-precompiled-stylesheets-without-live-generation
  (with-redefs [css/precompiled?  (constantly true)
                css/load-compiled {css/fairybox-css-resource "compiled fairybox"
                                   css/shadow-css-resource   "compiled shadow"}
                css/compile-css!  (fn [& _]
                                    (throw (ex-info "Unexpected live CSS compilation" {})))
                css/generate-css  (fn [& _]
                                    (throw (ex-info "Unexpected live Shadow CSS generation" {})))
                h/static-asset    identity]
    (is (= [{:body "compiled fairybox" :content-type "text/css"}
            {:body "compiled shadow" :content-type "text/css"}]
           [(ui/fairybox-css) (ui/shadow-css)]))))
