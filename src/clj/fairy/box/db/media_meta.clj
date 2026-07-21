;; Copyright © 2025 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2
(ns fairy.box.db.media-meta
  (:require
   [babashka.fs :as fs]
   [fairy.box.audio.browse :as browse]))

(defn metadata-path
  "Returns the canonical media-root-relative key for `path` when contained."
  [settings path]
  (when (some? path)
    (browse/media-relative-path settings path)))

(defn- ancestor-paths [normalized-path]
  (reductions (fn [parent component]
                (if (empty? parent)
                  component
                  (str parent "/" component)))
              ""
              (map str (fs/components (fs/path normalized-path)))))

(defn get-exact-metadata
  "Returns metadata stored at exactly `path`, without inherited values."
  [{:keys [db-conn settings]} path]
  (when-let [normalized (metadata-path settings path)]
    (get-in @db-conn [:media-metadata normalized])))

(defn update-exact-metadata!
  "Atomically applies `f` to the metadata stored at exactly `path`.

  Empty resulting metadata removes the exact path entry. Returns `nil` without
  updating the database when `path` is outside the configured media directory."
  [{:keys [db-conn settings]} path f]
  (when-let [normalized (metadata-path settings path)]
    (swap! db-conn
           update
           :media-metadata
           (fn [metadata-by-path]
             (let [metadata-by-path (or metadata-by-path {})
                   metadata         (f (get metadata-by-path normalized {}))]
               (if (seq metadata)
                 (assoc metadata-by-path normalized metadata)
                 (dissoc metadata-by-path normalized)))))))

(defn- inheritable-metadata [metadata]
  (if (and (map? metadata)
           (contains? metadata :announce?)
           (not (boolean? (:announce? metadata))))
    (dissoc metadata :announce?)
    metadata))

(defn get-metadata
  "Returns root-to-leaf inherited metadata for `path`.

  Deeper maps override shallower maps. A malformed nonboolean `:announce?`
  value behaves as an absent value and does not override an ancestor."
  [{:keys [db-conn settings]} path]
  (when-let [normalized (metadata-path settings path)]
    (let [metadata-by-path (get @db-conn :media-metadata)
          metadata         (->> (ancestor-paths normalized)
                                (keep #(get metadata-by-path %))
                                (map inheritable-metadata))]
      (when (seq metadata)
        (apply merge metadata)))))