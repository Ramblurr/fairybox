;; Copyright © 2025 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2
(ns fairy.box.nrepl-test
  (:require
   [clojure.test :refer [deftest is]]
   [donut.system :as ds]
   [fairy.box.nrepl :as app-nrepl]
   [nrepl.core :as nrepl]))

(deftest serves-evaluations-and-stops-with-system
  (let [config  {:fairy.box/components
                 {:fairy.box.nrepl/server {:bind "127.0.0.1"
                                           :port 0}}}
        running (ds/start
                 {::ds/defs
                  {:config config
                   :fairy.box/components
                   {:fairy.box.nrepl/server app-nrepl/NreplComponent}}})
        server  (ds/instance running
                             [:fairy.box/components
                              :fairy.box.nrepl/server])
        response
        (try
          (with-open [^java.io.Closeable connection
                      (nrepl/connect :host "127.0.0.1"
                                     :port (:port server))]
            (-> (nrepl/client connection 1000)
                (nrepl/message {:op "eval" :code "(+ 20 22)"})
                doall))
          (finally
            (ds/stop running)))]
    (is (= {:eval-value           "42"
            :server-socket-closed true}
           {:eval-value           (some :value response)
            :server-socket-closed (.isClosed ^java.net.ServerSocket
                                   (:server-socket server))}))))
