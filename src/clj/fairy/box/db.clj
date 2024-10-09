(ns fairy.box.db
  (:require

   [clojure.tools.logging :as log]
   [integrant.core :as ig]
   [duratom.core :as duratom]))

(def DEFAULT_MAX_VOLUME 95)

(defmethod ig/init-key ::db [_ {:keys [path]}]
  (log/info "\n-=[starting db]=-")
  (duratom/duratom
   :local-file
   :file-path path
   :init {:_version 1
          :linked-tags {}
          :settings {:audio
                     {:max-volume DEFAULT_MAX_VOLUME
                      :min-volume 0
                      :max-volume-day DEFAULT_MAX_VOLUME
                      :max-volume-night DEFAULT_MAX_VOLUME
                      :hour-day-start 8
                      :hour-night-start 19}}}))

(defn link-rfid-tag! [conn tag-uid folder-path]
  (assert tag-uid)
  (assert folder-path)
  (assert conn)
  (swap! conn update-in [:linked-tags tag-uid] assoc :folder folder-path))

(defn linked-folder [db tag-uid]
  (when tag-uid
    (get-in db [:linked-tags tag-uid :folder])))

(defn settings [db]
  (get-in db [:settings]))

(defn audio-settings [db]
  (get-in db [:settings :audio]))

(defn max-volume [db]
  (:max-volume (audio-settings db)))

(defn min-volume [db]
  (:min-volume (audio-settings db)))

(defn max-volume-day [db]
  (:max-volume-day (audio-settings db)))

(defn max-volume-night [db]
  (:max-volume-night (audio-settings db)))

(defn hour-day-start [db]
  (:hour-day-start (audio-settings db)))

(defn hour-night-start [db]
  (:hour-night-start (audio-settings db)))

(defn ha-url [db]
  (get-in db [:settings :homeassistant :ha-url]))

(defn ha-bearer-token [db]
  (get-in db [:settings :homeassistant :ha-bearer-token]))

(defn upsert-settings! [conn settings]
  (swap! conn assoc :settings settings))

(defn upsert-audio-settings! [conn audio-settings]
  (swap! conn assoc-in [:settings :audio] audio-settings))

(comment
  (do
    (require '[integrant.repl.state :as state])
    (def system state/system)
    (def db-conn (:fairy.box.db/db system)))
  @db-conn
  ;;
  )
