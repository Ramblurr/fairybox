(ns fairy.box.web.views.common-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [fairy.box.media-test-utils :as media]
   [fairy.box.web.views.common :as common]
   [hyperlith.core :as h]))

(defn- browser-html [tree dir]
  (-> (common/browse-media-folder
       (media/request tree dir)
       {:mode :choose :active-value nil}
       dir)
      h/html->str))

(deftest renders-requested-directory-with-ordinary-links
  (fs/with-temp-dir [temp-dir {:prefix "fairybox-browser-"}]
    (let [tree (media/populate-media-tree! temp-dir)
          html (browser-html tree "audiobooks")]
      (is (= {:current-contents true
              :root-contents-hidden true
              :directory-link true
              :root-breadcrumb-link true
              :selected-folder-binding true
              :htmx-removed true}
             {:current-contents (and (str/includes? html "Author One")
                                     (str/includes? html "Author Two"))
              :root-contents-hidden (not (str/includes? html "Favorites.m3u"))
              :directory-link
              (str/includes?
               html
               "href=\"/settings/rfid?dir=audiobooks%2FAuthor+One\"")
              :root-breadcrumb-link
              (str/includes? html "href=\"/settings/rfid\"")
              :selected-folder-binding
              (str/includes? html "data-bind=\"selected_folder\"")
              :htmx-removed (not (str/includes? html "hx-"))})))))

(deftest breadcrumb-links-preserve-deep-directory
  (fs/with-temp-dir [temp-dir {:prefix "fairybox-breadcrumb-"}]
    (let [tree (media/populate-media-tree! temp-dir)
          html (browser-html tree "audiobooks/Author One")]
      (is (= {:shows-child true
              :links-parent true
              :links-current true}
             {:shows-child (str/includes? html "Book One")
              :links-parent
              (str/includes? html "href=\"/settings/rfid?dir=audiobooks\"")
              :links-current
              (str/includes?
               html
               "href=\"/settings/rfid?dir=audiobooks%2FAuthor+One\"")})))))

(deftest invalid-directory-falls-back-to-media-root
  (fs/with-temp-dir [temp-dir {:prefix "fairybox-browser-fallback-"}]
    (let [tree (media/populate-media-tree! temp-dir)
          missing-html (browser-html tree "missing")
          escape-html (browser-html tree "../../etc")]
      (is (= {:missing-shows-root true
              :escape-shows-root true
              :outside-hidden true}
             {:missing-shows-root
              (str/includes? missing-html "audiobooks")
              :escape-shows-root
              (str/includes? escape-html "audiobooks")
              :outside-hidden
              (not (str/includes? escape-html "passwd"))})))))
