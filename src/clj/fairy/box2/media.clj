(ns fairy.box2.media
  "Box2 local-media preparation, adapted from Box1's Vinyl-backed expansion."
  (:require
   [babashka.fs :as fs]
   [ol.vinyl :as vinyl]))

(defn canonical-path
  "Returns `item-path` below `media-dir`, rejecting paths outside that directory."
  [media-dir item-path]
  (let [base (fs/canonicalize (fs/path media-dir))
        path (fs/canonicalize (fs/path base item-path))]
    (when (fs/starts-with? path base)
      (str path))))

(defn prepare!
  "Expands one linked local folder, file, or playlist through Vinyl metadata parsing."
  [player media-dir item-path]
  (let [path   (or (canonical-path media-dir item-path)
                   (throw (ex-info "Media path is outside the configured media directory"
                                   {:item-path item-path})))
        tracks @(vinyl/parse-meta player [path])]
    (when (instance? Throwable tracks)
      (throw (ex-info "Failed to parse media tracks" {:item-path item-path} tracks)))
    (let [playable (->> tracks
                        (filter #(= :media-parsed-status/done (:parse-status %)))
                        (filter (comp seq :audio-tracks))
                        (mapv :mrl))]
      (when-not (seq playable)
        (throw (ex-info "Media expansion produced no playable tracks" {:item-path item-path})))
      playable)))
