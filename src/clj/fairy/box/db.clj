;; Copyright © 2025 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2
(ns fairy.box.db
  (:require
   [clojure.pprint :as pp]
   [donut.system :as ds]
   [duratom.core :as duratom]
   [duratom.utils :as dut]
   [fairy.box.db.media-meta :as mm]
   [fairy.box.util :as util]))

(def default-audio-settings
  {:min-volume               0
   :max-volume               95
   :max-volume-day           95
   :max-volume-night         95
   :max-led-brightness-day   100
   :max-led-brightness-night 100
   :day-start                "08:00"
   :night-start              "19:00"
   :card-removal-behavior    :pause
   :card-return-behavior     :restart})

(defn- valid-legacy-hour? [value]
  (and (integer? value)
       (<= 0 value 23)))

(defn- canonical-start [audio-settings new-key legacy-key]
  (let [new-value    (get audio-settings new-key)
        legacy-value (get audio-settings legacy-key)]
    (cond
      (util/valid-wall-clock-time? new-value) new-value
      (valid-legacy-hour? legacy-value) (format "%02d:00" legacy-value)
      :else (get default-audio-settings new-key))))

(defn migrate-db [database]
  (let [audio-settings (get-in database [:settings :audio] {})
        migrated       (-> (merge (select-keys default-audio-settings
                                               [:max-led-brightness-day
                                                :max-led-brightness-night
                                                :card-removal-behavior
                                                :card-return-behavior])
                                  audio-settings)
                           (assoc :day-start
                                  (canonical-start audio-settings
                                                   :day-start
                                                   :hour-day-start)
                                  :night-start
                                  (canonical-start audio-settings
                                                   :night-start
                                                   :hour-night-start))
                           (dissoc :hour-day-start :hour-night-start))]
    (assoc-in database [:settings :audio] migrated)))

(def DbComponent
  {::ds/start  (fn [{config ::ds/config}]
                 (let [path (get-in config [:opts :path])]
                   (assert path "Path is required for the db component")
                   (tap> [:db-start :path path :config config])
                   (let [conn     (duratom/duratom
                                   :local-file
                                   :file-path path
                                   :rw {:commit-mode :sync
                                        :read        dut/read-edn-object
                                        :write       (fn [filepath data]
                                                       (spit filepath
                                                             (with-out-str
                                                               (pp/pprint data))))}
                                   :init {:_version       1
                                          :linked-tags    {}
                                          :settings       {:audio default-audio-settings}
                                          :media-metadata {}})
                         current  @conn
                         migrated (migrate-db current)]
                     (when-not (= current migrated)
                       (reset! conn migrated))
                     conn)))
   ::ds/config {:opts (ds/ref [:config
                               :fairy.box/components
                               :fairy.box.db/db])}})

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

(defn day-start [db]
  (:day-start (audio-settings db)))

(defn night-start [db]
  (:night-start (audio-settings db)))

(defn max-led-brightness-day [db]
  (:max-led-brightness-day (audio-settings db)))

(defn max-led-brightness-night [db]
  (:max-led-brightness-night (audio-settings db)))

(defn card-removal-behavior [db]
  (:card-removal-behavior (audio-settings db)))

(defn card-return-behavior [db]
  (:card-return-behavior (audio-settings db)))

(defn ha-url [db]
  (get-in db [:settings :homeassistant :ha-url]))

(defn ha-bearer-token [db]
  (get-in db [:settings :homeassistant :ha-bearer-token]))

(defn tts-engine [db]
  (get-in db [:settings :tts :engine] :google-cloud))

(defn google-cloud-api-key [db]
  (get-in db [:settings :google-cloud-api-key]))

(defn upsert-settings! [conn settings]
  (swap! conn assoc :settings settings))

(defn upsert-audio-settings! [conn audio-settings]
  (swap! conn assoc-in [:settings :audio] audio-settings))

(defn set-announce! [sys path]
  (mm/set-metadata! sys path
                    (merge (mm/get-metadata sys (str path))
                           {:announce? true})))

(defn announce-file? [sys file]
  (let [res (:announce? (mm/get-metadata sys (str file)))]
    (tap> [:announce-file? :result res :file file])
    res))

(comment
  (require '[fairy.box.system :as system])
  (def db-conn (system/component :fairy.box.db/db))
  @db-conn
  :rcf)
