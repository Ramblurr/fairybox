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
          :linked-tags {}
          :settings {:audio {:max-volume 80}}}))

(defn link-rfid-tag! [conn tag-uid folder-path]
  (assert tag-uid)
  (assert folder-path)
  (assert conn)
  (swap! conn update-in [:linked-tags tag-uid] assoc :folder folder-path))

(defn linked-folder [db tag-uid]
  (when tag-uid
    (get-in db [:linked-tags tag-uid :folder])))

(comment
  (do
    (require '[integrant.repl.state :as state])
    (def system state/system)
    (def db-conn (:fairy.box.db/db system)))
  @db-conn
  ;;
  )
