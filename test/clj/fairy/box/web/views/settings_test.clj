(ns fairy.box.web.views.settings-test
  (:require
   [babashka.fs :as fs]
   [clojure.core.async :as async]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [fairy.box.media-test-utils :as media]
   [fairy.box.web.views.settings :as settings-view]
   [hyperlith.core :as h]))

(defn- action-fn []
  (ns-resolve 'fairy.box.web.views.settings 'link-rfid-folder-fn))

(defn- play-action-fn []
  (ns-resolve 'fairy.box.web.views.settings 'play-audio-path-fn))

(defn- browse-request [tree dir]
  (assoc (media/request tree dir)
         :uri "/settings/browse"
         :url-for {:page/home "/"
                   :page/queue "/queue"
                   :page/settings "/settings"}))


(defn- action-request [tree selected-folder rfid]
  (let [req (media/request tree "audiobooks/Author One")
        presence ((:fairy.box/component req)
                  :fairy.box.web/rfid-presence)]
    (reset! (:state presence) rfid)
    (assoc req
           :body {:selected_folder selected-folder
                  :rfid_uid "stale-browser-tag"})))

(deftest rfid-form-uses-datastar-action-and-ordinary-back-link
  (fs/with-temp-dir [temp-dir {:prefix "fairybox-rfid-form-"}]
    (let [tree (media/populate-media-tree! temp-dir)
          req (media/request tree "audiobooks/Author Two")
          action-path (ns-resolve 'fairy.box.web.views.settings
                                  'link-rfid-folder)
          html (-> (settings-view/rfid-link-form
                    req
                    "tag-1"
                    "audiobooks/Author Two")
                   h/html->str)]
      (is (some? action-path))
      (when action-path
        (is (= {:form true
                :selection-signal true
                :submit-action true
                :radio-binding true
                :ordinary-back-link true
                :client-rfid-removed true
                :htmx-removed true}
               {:form (str/starts-with? html "<form")
                :selection-signal
                (str/includes?
                 html
                 "data-signals:selected_folder__ifmissing=")
                :submit-action
                (and (str/includes? html "data-on:submit=")
                     (str/includes? html (var-get action-path)))
                :radio-binding
                (str/includes? html "data-bind=\"selected_folder\"")
                :ordinary-back-link
                (str/includes? html "href=\"/settings\"")
                :client-rfid-removed
                (not (str/includes? html "name=\"rfid-uid\""))
                :htmx-removed (not (str/includes? html "hx-"))}))))))

(deftest links-selected-folder-to-current-rfid
  (fs/with-temp-dir [temp-dir {:prefix "fairybox-rfid-action-"}]
    (let [tree (media/populate-media-tree! temp-dir)
          req (action-request tree
                              "audiobooks/Author One/../Author Two"
                              {:action :placed :uid "current-tag"})
          db-conn ((:fairy.box/component req) :fairy.box.db/db)
          link! (action-fn)]
      (is (some? link!))
      (when link!
        (link! req)
        (is (= {:linked-tags
                {"current-tag"
                 {:folder "audiobooks/Author Two"}}}
               @db-conn))))))

(deftest rejects-link-without-current-rfid
  (fs/with-temp-dir [temp-dir {:prefix "fairybox-rfid-no-tag-"}]
    (let [tree (media/populate-media-tree! temp-dir)
          req (action-request tree
                              "audiobooks/Author Two"
                              {:action :removed :uid "old-tag"})
          db-conn ((:fairy.box/component req) :fairy.box.db/db)
          link! (action-fn)]
      (when link!
        (link! req)
        (is (= {:linked-tags {}} @db-conn))))))

(deftest rejects-missing-and-escaped-link-paths
  (fs/with-temp-dir [temp-dir {:prefix "fairybox-rfid-invalid-path-"}]
    (let [tree (media/populate-media-tree! temp-dir)
          link! (action-fn)]
      (when link!
        (let [db-states
              (mapv (fn [selected-folder]
                      (let [req (action-request tree
                                                selected-folder
                                                {:action :placed :uid "tag-1"})
                            db-conn ((:fairy.box/component req)
                                     :fairy.box.db/db)]
                        (link! req)
                        @db-conn))
                    ["missing" "../../etc"])]
          (is (= [{:linked-tags {}} {:linked-tags {}}]
                 db-states)))))))

(deftest browse-audio-uses-relative-navigation-and-datastar-action
  (fs/with-temp-dir [temp-dir {:prefix "fairybox-audio-browser-"}]
    (let [tree (media/populate-media-tree! temp-dir)
          action-path (ns-resolve 'fairy.box.web.views.settings
                                  'play-audio-path)
          render-page (ns-resolve 'fairy.box.web.views.settings
                                  'render-browse-fn)]
      (is (= {:action true :render true}
             {:action (some? action-path)
              :render (some? render-page)}))
      (when (and action-path render-page)
        (let [nav-html (-> (render-page (browse-request tree "audiobooks"))
                           h/html->str)
              play-html (-> (render-page
                             (browse-request tree
                                             "audiobooks/Author Two"))
                            h/html->str)]
          (is (= {:legacy-layout true
                  :heading true
                  :requested-directory true
                  :ordinary-directory-link true
                  :datastar-play-action true
                  :relative-play-path true
                  :settings-placeholder-removed true
                  :absolute-path-hidden true
                  :htmx-removed true}
                 {:legacy-layout
                  (str/includes? nav-html
                                 "<div id=\"active-tab\"><div class=")
                  :heading
                  (str/includes? nav-html "Fairybox Audio Folders")
                  :requested-directory
                  (str/includes? play-html "Story.mp3")
                  :ordinary-directory-link
                  (str/includes?
                   nav-html
                   "href=\"/settings/browse?dir=audiobooks%2FAuthor+One\"")
                  :datastar-play-action
                  (and (str/includes? play-html "data-on:click=")
                       (str/includes? play-html (var-get action-path)))
                  :relative-play-path
                  (str/includes?
                   play-html
                   "path=audiobooks%2FAuthor+Two%2FStory.mp3")
                  :settings-placeholder-removed
                  (not (str/includes? nav-html ">Settings</h1>"))
                  :absolute-path-hidden
                  (not (or (str/includes? nav-html (:root tree))
                           (str/includes? play-html (:root tree))))
                  :htmx-removed
                  (not (or (str/includes? nav-html "hx-")
                           (str/includes? play-html "hx-")))})))))))

(deftest plays-canonical-media-path-and-redirects-home
  (fs/with-temp-dir [temp-dir {:prefix "fairybox-audio-action-"}]
    (let [tree (media/populate-media-tree! temp-dir)
          commands (async/chan 1)
          req (-> (browse-request tree "audiobooks/Author Two")
                  (assoc-in [:query-params "path"]
                            "audiobooks/Author Two/Story.mp3")
                  (assoc-in [:fairy.box/component
                             :fairy.box.switchboard/switchboard]
                            {:emitter commands}))
          play! (play-action-fn)]
      (is (some? play!))
      (when play!
        (let [response (play! req)
              [command port] (async/alts!! [commands (async/timeout 1000)])
              result {:command command
                      :received? (= port commands)
                      :redirect? (str/includes?
                                  (h/html->str response)
                                  "window.location.assign(&apos;/&apos;)")}]
          (async/close! commands)
          (is (= {:command
                  {:path "/player/commands"
                   :value
                   {:action :audio/play-path
                    :item-path (str (fs/canonicalize
                                     (fs/path (:root tree)
                                              "audiobooks/Author Two/Story.mp3")))
                    :uid nil}}
                  :received? true
                  :redirect? true}
                 result)))))))

(deftest rejects-missing-escaped-and-unplayable-audio-paths
  (fs/with-temp-dir [temp-dir {:prefix "fairybox-invalid-audio-action-"}]
    (let [tree (media/populate-media-tree! temp-dir)
          play! (play-action-fn)]
      (is (some? play!))
      (when play!
        (let [results
              (mapv
               (fn [path]
                 (let [commands (async/chan 1)
                       req (-> (browse-request tree nil)
                               (assoc-in [:query-params "path"] path)
                               (assoc-in [:fairy.box/component
                                          :fairy.box.switchboard/switchboard]
                                         {:emitter commands}))
                       response (play! req)
                       [command port]
                       (async/alts!! [commands (async/timeout 100)])
                       result {:response? (some? response)
                               :command command
                               :emitted? (= port commands)}]
                   (async/close! commands)
                   result))
               ["missing" "../../etc/passwd" "audiobooks/Author One"])]
          (is (= (repeat 3 {:response? false
                            :command nil
                            :emitted? false})
                 results)))))))
