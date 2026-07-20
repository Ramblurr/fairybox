(ns fairy.box.media-test-utils
  (:require
   [babashka.fs :as fs]))

(defn populate-media-tree! [root]
  (let [root (str root)]
    (doseq [dir ["audiobooks/Author One/Book One"
                 "audiobooks/Author Two"
                 "playlists"
                 "sfx"]]
      (fs/create-dirs (fs/path root dir)))
    (spit (fs/file root "audiobooks/Author One/Book One/01 Track.mp3") "audio")
    (spit (fs/file root "audiobooks/Author Two/Story.mp3") "audio")
    (spit (fs/file root "playlists/Favorites.m3u") "playlist")
    (spit (fs/file root "sfx/Chime.wav") "audio")
    (spit (fs/file root "notes.txt") "not audio")
    {:root root
     :settings {:media {:media-dir root}}}))

(defn request
  [{:keys [settings]} dir]
  (let [components
        {:fairy.box/settings settings
         :fairy.box.db/db (atom {:linked-tags {}})
         :fairy.box.web/rfid-presence
         {:state (atom {:action :removed :uid nil})
          :refresh! (constantly nil)}}]
    {:uri "/settings/rfid"
     :query-params (cond-> {} dir (assoc "dir" dir))
     :fairy.box/component components}))
