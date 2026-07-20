(ns fairy.box.audio.browse-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [fairy.box.audio.browse :as browse]
   [fairy.box.media-test-utils :as media]))

(deftest canonical-path-containment
  (fs/with-temp-dir [temp-dir {:prefix "fairybox-browse-"}]
    (let [{:keys [root settings]} (media/populate-media-tree! temp-dir)
          sibling (str root "-outside")]
      (try
        (fs/create-dirs sibling)
        (is (= {:root true
                :descendant true
                :parent-escape false
                :absolute-escape false
                :sibling-prefix false}
               {:root (browse/validate-base-path root root)
                :descendant (browse/validate-base-path
                             root
                             (fs/path root "audiobooks/Author One"))
                :parent-escape (boolean
                                (browse/canonicalize-path
                                 settings
                                 "../../outside"))
                :absolute-escape (boolean
                                  (browse/canonicalize-path settings "/etc"))
                :sibling-prefix (boolean
                                 (browse/canonicalize-path settings sibling))}))
        (finally
          (fs/delete-tree sibling))))))

(deftest media-relative-path-is-canonical
  (fs/with-temp-dir [temp-dir {:prefix "fairybox-relative-"}]
    (let [{:keys [root settings]} (media/populate-media-tree! temp-dir)
          relative-path (ns-resolve 'fairy.box.audio.browse
                                    'media-relative-path)]
      (is (some? relative-path))
      (when relative-path
        (is (= {:relative "audiobooks/Author One/Book One"
                :normalized "audiobooks/Author Two"
                :outside nil}
               {:relative (relative-path
                           settings
                           (fs/path root "audiobooks/Author One/Book One"))
                :normalized (relative-path
                             settings
                             "audiobooks/Author One/../Author Two")
                :outside (relative-path settings "/etc")}))))))
