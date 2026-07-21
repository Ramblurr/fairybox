(ns fairy.box.web.controllers.shutdown-test
  (:require
   [clojure.core.async :as async]
   [clojure.test :refer [deftest is]]
   [fairy.box.web.api :as api]
   [hyperlith.impl.blocker :as blocker]
   [hyperlith.impl.router :as router]
   [hyperlith.impl.session :as session]))

(defn- with-component [handler component]
  (fn [request]
    (handler (assoc request :fairy.box/component component))))

(deftest accepts-normal-api-request-from-non-loopback-client
  (let [emitter (async/chan 1)
        handler (-> router/router
                    (with-component
                      {:fairy.box.switchboard/switchboard
                       {:emitter emitter}})
                    session/wrap-session
                    blocker/wrap-blocker)]
    (try
      (let [response (handler
                      {:request-method :post
                       :uri            "/api/shutdown"
                       :remote-addr    "198.51.100.24"
                       :headers        {"accept-encoding" "br"
                                        "cookie"          "__Host-sid=onoff-shim-trigger"
                                        "sec-fetch-site"  "same-origin"}})]
        (is (= {:response {:status 202 :headers {} :body ""}
                :event    {:path  "/system"
                           :value {:event :system/poweroff}}}
               {:response response
                :event    (async/<!! emitter)})))
      (finally
        (async/close! emitter)))))

(deftest reports-unavailable-switchboard
  (let [closed-emitter (async/chan 1)]
    (async/close! closed-emitter)
    (is (= {:missing-component
            {:status  503
             :headers {"Content-Type"
                       "application/json; charset=utf-8"}
             :body    "{\"error\":\"Switchboard unavailable\"}"}
            :closed-emitter
            {:status  503
             :headers {"Content-Type"
                       "application/json; charset=utf-8"}
             :body    "{\"error\":\"Switchboard unavailable\"}"}}
           {:missing-component
            (api/shutdown! {:fairy.box/component {}})
            :closed-emitter
            (api/shutdown!
             {:fairy.box/component
              {:fairy.box.switchboard/switchboard
               {:emitter closed-emitter}}})}))))

(deftest registers-shutdown-route
  (is (identical? #'api/shutdown!
                  (get-in @router/routes_ [:post "/api/shutdown"]))))
