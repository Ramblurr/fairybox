;; Copyright © 2025 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2
(ns fairy.box.db.media-meta
  (:require [babashka.fs :as fs]
            [fairy.box.audio.browse :as browse]
            [clojure.string :as str]))

(defn- normalize-path
  "Normalizes a path relative to media-dir.
   - Converts absolute paths to relative by removing media-dir prefix
   - Removes trailing slashes
   - Handles special cases like '.' and empty string as root"
  [media-dir path]
  (let [media-dir-path (fs/path media-dir)
        input-path (fs/path path)
        ;; Convert to relative path if absolute
        relative-path (if (fs/absolute? input-path)
                        (fs/relativize media-dir-path input-path)
                        input-path)
        ;; Convert to string and clean up
        path-str (str relative-path)]
    ;; Handle edge cases
    (cond
      (or (= path-str ".") (= path-str ""))  ""
      ;; Remove trailing slashes
      (and (not= path-str "/") (str/ends-with? path-str "/"))
      (subs path-str 0 (dec (count path-str)))
      :else path-str)))

(defn- get-ancestor-paths
  "Returns a sequence of all ancestor paths from root to the given path.
   E.g. 'audiobooks/Dr. Seuss/Book' -> ['audiobooks' 'audiobooks/Dr. Seuss' 'audiobooks/Dr. Seuss/Cat in the Hat']"
  [normalized-path]
  (if (empty? normalized-path)
    [""]
    (let [components (fs/components (fs/path normalized-path))
          ;; Convert Path objects to strings
          component-strs (map str components)]
      ;; Build incremental paths
      (reduce (fn [paths component]
                (let [parent (peek paths)
                      new-path (if (empty? parent)
                                 component
                                 (str parent "/" component))]
                  (conj paths new-path)))
              []
              component-strs))))

(defn set-metadata!
  "Sets metadata for a specific path.
   Path can be absolute or relative to media-dir."
  [{:keys [db-conn settings]} path metadata]
  (let [media-dir (browse/media-dir settings)
        normalized (normalize-path media-dir path)]
    (swap! db-conn
           update-in [:media-metadata]
           (fn [m]
             (if (nil? metadata)
               ;; Remove metadata if nil
               (dissoc m normalized)
               ;; Set metadata
               (assoc m normalized metadata))))))

(defn get-metadata
  "Retrieves the effective metadata for a path, including all inherited values.
   Returns nil if no metadata exists in the entire path hierarchy."
  [{:keys [db-conn settings]} path]
  (let [media-dir (browse/media-dir settings)
        normalized (normalize-path media-dir path)
        metadata-map (get-in @db-conn [:media-metadata])
        ancestor-paths (get-ancestor-paths normalized)
        ;; Collect metadata for all ancestors
        ancestor-metadata (keep #(get metadata-map %) ancestor-paths)]
    ;; Merge all metadata, with deeper paths taking precedence
    (when (seq ancestor-metadata)
      (apply merge ancestor-metadata))))