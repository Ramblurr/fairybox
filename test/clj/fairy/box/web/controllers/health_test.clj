(ns fairy.box.web.controllers.health-test
  (:require
   [clojure.core.async :as async]
   [clojure.test :refer [deftest is]]
   [fairy.box.switchboard :as switchboard]
   [fairy.box.web.api :as api]
   [hyperlith.impl.router :as router]))

(deftest reports-switchboard-readiness
  (is (= {:ready    {:status  200
                     :headers {"Content-Type"
                               "application/json; charset=utf-8"}
                     :body    "{\"system-state\":\"ready\"}"}
          :starting {:status  503
                     :headers {"Content-Type"
                               "application/json; charset=utf-8"}
                     :body    "{\"system-state\":\"warming-up\"}"}}
         {:ready
          (with-redefs [switchboard/system-state!
                        (constantly :system-state/ready)]
            (api/ready? {}))
          :starting
          (with-redefs [switchboard/system-state!
                        (constantly :system-state/warming-up)]
            (api/ready? {}))})))

(deftest hands-led-control-to-application
  (let [emitter  (async/chan 1)
        response (api/leds-on!
                  {:fairy.box/component
                   {:fairy.box.switchboard/switchboard
                    {:emitter emitter}}})]
    (is (= {:response {:status 204 :headers {} :body ""}
            :event    {:path  "/hardware/output/leds"
                       :value {:action :led/set
                               :groups [:all]
                               :value  1.0}}}
           {:response response
            :event    (async/<!! emitter)}))
    (async/close! emitter)))

(deftest registers-startup-handshake-routes
  (is (= {:ready?   true
          :leds-on! true}
         {:ready?
          (identical? #'api/ready?
                      (get-in @router/routes_ [:get "/api/ready"]))
          :leds-on!
          (identical? #'api/leds-on!
                      (get-in @router/routes_ [:get "/api/leds-on"]))})))
