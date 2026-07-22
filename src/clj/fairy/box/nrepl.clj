;; Copyright © 2025 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2
(ns fairy.box.nrepl
  (:require
   [clojure.tools.logging :as log]
   [donut.system :as ds]
   [nrepl.server :as nrepl-server]))

(defn start-server! [{:keys [bind port]}]
  (log/info "Starting production nREPL server" {:bind bind :port port})
  (nrepl-server/start-server :bind bind :port port))

(defn stop-server! [server]
  (log/info "Stopping production nREPL server" {:port (:port server)})
  (nrepl-server/stop-server server))

(def NreplComponent
  {::ds/start  (fn [{config ::ds/config}]
                 (start-server! config))
   ::ds/stop   (fn [{instance ::ds/instance}]
                 (stop-server! instance))
   ::ds/config {:bind (ds/ref [:config
                               :fairy.box/components
                               :fairy.box.nrepl/server
                               :bind])
                :port (ds/ref [:config
                               :fairy.box/components
                               :fairy.box.nrepl/server
                               :port])}})
