(ns fairy.box.db
  (:require

   [clojure.tools.logging :as log]
   [integrant.core :as ig]
   [duratom.core :as duratom]))

(defmethod ig/init-key ::db [_ {:keys [path]}]
  (log/info "\n-=[starting db]=-")
  (duratom/duratom
   :local-file
   :file-path path
   :init {:_version 1
          :rfid {}
          :audio {:max-volume 80}}))
