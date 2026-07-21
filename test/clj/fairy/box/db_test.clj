(ns fairy.box.db-test
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.test :refer [deftest is]]
   [donut.system :as ds]
   [fairy.box.db :as db])
  (:import
   [java.nio.file Files LinkOption]
   [java.nio.file.attribute FileTime]))

(defn- database-system [path]
  {::ds/defs
   {:config {:fairy.box/components
             {:fairy.box.db/db {:path (str path)}}}
    :fairy.box/components
    {:fairy.box.db/db db/DbComponent}}})

(defn- database-instance [running]
  (ds/instance running [:fairy.box/components :fairy.box.db/db]))

(deftest migrates-legacy-database-during-component-start
  (fs/with-temp-dir [temp-dir {:prefix "fairybox-db-migration-"}]
    (let [path           (fs/path temp-dir "db.edn")
          original       {:_version       1
                          :linked-tags    {"tag" {:folder "kept"}}
                          :settings       {:audio {:min-volume       2
                                                   :max-volume       95
                                                   :max-volume-day   80
                                                   :max-volume-night 50
                                                   :day-start        "08:30"
                                                   :hour-day-start   7
                                                   :hour-night-start 20
                                                   :unknown          :kept}}
                          :media-metadata {}}
          _              (spit (str path) (pr-str original))
          system         (database-system path)
          first-running  (ds/start system)
          first-value    @(database-instance first-running)
          first-file     (edn/read-string (slurp (str path)))
          expected       {:_version       1
                          :linked-tags    {"tag" {:folder "kept"}}
                          :settings
                          {:audio {:min-volume               2
                                   :max-volume               95
                                   :max-volume-day           80
                                   :max-volume-night         50
                                   :max-led-brightness-day   100
                                   :max-led-brightness-night 100
                                   :day-start                "08:30"
                                   :night-start              "20:00"
                                   :card-removal-behavior    :pause
                                   :card-return-behavior     :restart
                                   :unknown                  :kept}}
                          :media-metadata {}}
          _              (ds/stop first-running)
          fixed-time     (FileTime/fromMillis 946684800000)
          _              (Files/setLastModifiedTime path fixed-time)
          second-running (ds/start system)
          second-value   @(database-instance second-running)
          second-file    (edn/read-string (slurp (str path)))
          second-time    (Files/getLastModifiedTime
                          path
                          (make-array LinkOption 0))]
      (ds/stop second-running)
      (is (= {:first-memory       expected
              :first-file         expected
              :second-memory      expected
              :second-file        expected
              :second-write-free? true}
             {:first-memory       first-value
              :first-file         first-file
              :second-memory      second-value
              :second-file        second-file
              :second-write-free? (= fixed-time second-time)})))))

(deftest chooses-canonical-start-values
  (let [cases    {:new              {:settings {:audio {:day-start        "09:15"
                                                        :night-start      "21:45"
                                                        :hour-day-start   7
                                                        :hour-night-start 20}}}
                  :legacy           {:settings {:audio {:hour-day-start   8
                                                        :hour-night-start 19}}}
                  :malformed-new    {:settings {:audio {:day-start        "9:15"
                                                        :night-start      "24:00"
                                                        :hour-day-start   6
                                                        :hour-night-start 22}}}
                  :malformed-legacy {:settings {:audio {:hour-day-start   -1
                                                        :hour-night-start 24}}}
                  :missing          {:unrelated :kept}}
        migrated (update-vals cases db/migrate-db)]
    (is (= {:starts
            {:new              {:day-start "09:15" :night-start "21:45"}
             :legacy           {:day-start "08:00" :night-start "19:00"}
             :malformed-new    {:day-start "06:00" :night-start "22:00"}
             :malformed-legacy {:day-start "08:00" :night-start "19:00"}
             :missing          {:day-start "08:00" :night-start "19:00"}}
            :brightness
            {:max-led-brightness-day   100
             :max-led-brightness-night 100}
            :card-behavior {:card-removal-behavior :pause
                            :card-return-behavior  :restart}
            :unrelated     :kept
            :idempotent?   true}
           {:starts        (update-vals migrated
                                        #(select-keys (db/audio-settings %)
                                                      [:day-start :night-start]))
            :brightness    (select-keys
                            (db/audio-settings (:missing migrated))
                            [:max-led-brightness-day
                             :max-led-brightness-night])
            :card-behavior (select-keys
                            (db/audio-settings (:missing migrated))
                            [:card-removal-behavior
                             :card-return-behavior])
            :unrelated     (:unrelated (:missing migrated))
            :idempotent?   (every? (fn [[_ database]]
                                     (= database (db/migrate-db database)))
                                   migrated)}))))
