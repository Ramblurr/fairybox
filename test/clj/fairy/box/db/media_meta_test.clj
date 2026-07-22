(ns fairy.box.db.media-meta-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [fairy.box.db :as db]
   [fairy.box.db.media-meta :as media-meta]
   [fairy.box.media-test-utils :as media]))

(defn- media-system [tree database]
  {:db-conn  database
   :settings (:settings tree)})

(deftest cycles-exact-state-atomically-and-preserves-unrelated-metadata
  (fs/with-temp-dir [temp-dir {:prefix "fairybox-media-metadata-cycle-"}]
    (let [tree     (media/populate-media-tree! temp-dir)
          book     "audiobooks/Author One/Book One"
          story    "audiobooks/Author Two/Story.mp3"
          database (atom {:settings       {:tts {:announce-tracks? true}}
                          :media-metadata {book {:other :kept}}})
          system   (media-system tree database)
          writes_  (atom 0)]
      (add-watch database ::writes
                 (fn [_ _ _ _]
                   (swap! writes_ inc)))
      (let [initial         (db/announcement-status system book)
            _               (db/cycle-announcement! system book)
            announce        (db/announcement-status system book)
            absolute-status (db/announcement-status
                             system
                             (str (fs/path temp-dir book)))
            _               (db/cycle-announcement! system book)
            quiet           (db/announcement-status system book)
            _               (db/cycle-announcement! system book)
            inherit         (db/announcement-status system book)
            after-book      (:media-metadata @database)
            _               (dotimes [_ 3]
                              (db/cycle-announcement! system story))
            before-invalid  @database
            writes-before   @writes_
            invalid-result  (db/cycle-announcement! system "../escape")]
        (remove-watch database ::writes)
        (is (= {:cycle
                [{:state          :inherit
                  :path-announce? true
                  :announce?      true}
                 {:state          :announce
                  :path-announce? true
                  :announce?      true}
                 {:state          :do-not-announce
                  :path-announce? false
                  :announce?      false}
                 {:state          :inherit
                  :path-announce? true
                  :announce?      true}]
                :absolute-status
                {:state          :announce
                 :path-announce? true
                 :announce?      true}
                :after-book
                {book {:other :kept}}
                :after-empty-cycle
                {book {:other :kept}}
                :writes-before-invalid 6
                :invalid-result        nil
                :invalid-write-count   0
                :invalid-changed?      false}
               {:cycle                 [initial announce quiet inherit]
                :absolute-status       absolute-status
                :after-book            after-book
                :after-empty-cycle     (:media-metadata @database)
                :writes-before-invalid writes-before
                :invalid-result        invalid-result
                :invalid-write-count   (- @writes_ writes-before)
                :invalid-changed?      (not= before-invalid @database)}))))))

(deftest resolves-nearest-boolean-and-treats-malformed-values-as-inherit
  (fs/with-temp-dir [temp-dir {:prefix "fairybox-media-metadata-inherit-"}]
    (let [tree             (media/populate-media-tree! temp-dir)
          parent           "audiobooks"
          child            "audiobooks/Author One/Book One"
          malformed        "audiobooks/Author Two"
          playlist         "playlists/Favorites.m3u"
          database         (atom {:settings {:tts {:announce-tracks? true}}
                                  :media-metadata
                                  {parent      {:announce? true :kind :books}
                                   child       {:announce? false :other :kept}
                                   malformed   {:announce? :legacy}
                                   "playlists" {:announce? true}}})
          system           (media-system tree database)
          child-before     (db/announcement-status system child)
          child-exact      (media-meta/get-exact-metadata system child)
          _                (db/cycle-announcement! system child)
          child-after      (db/announcement-status system child)
          child-effective  (media-meta/get-metadata system child)
          malformed-status (db/announcement-status system malformed)
          playlist-status  (db/announcement-status system playlist)
          absolute-playlist-status
          (db/announcement-status system (str (fs/path temp-dir playlist)))]
      (swap! database assoc-in [:settings :tts :announce-tracks?] "yes")
      (let [malformed-global (db/announcement-status system playlist)]
        (is (= {:child-before
                {:state          :do-not-announce
                 :path-announce? false
                 :announce?      false}
                :child-exact
                {:announce? false :other :kept}
                :child-after
                {:state          :inherit
                 :path-announce? true
                 :announce?      true}
                :child-effective
                {:announce? true :kind :books :other :kept}
                :child-stored
                {:other :kept}
                :malformed-local
                {:state          :inherit
                 :path-announce? true
                 :announce?      true}
                :playlist
                {:state          :inherit
                 :path-announce? true
                 :announce?      true}
                :absolute-playlist
                {:state          :inherit
                 :path-announce? true
                 :announce?      true}
                :malformed-global
                {:state          :inherit
                 :path-announce? true
                 :announce?      false}
                :strict-predicate false}
               {:child-before      child-before
                :child-exact       child-exact
                :child-after       child-after
                :child-effective   child-effective
                :child-stored      (media-meta/get-exact-metadata system child)
                :malformed-local   malformed-status
                :playlist          playlist-status
                :absolute-playlist absolute-playlist-status
                :malformed-global  malformed-global
                :strict-predicate  (db/announce-path? system playlist)}))))))
