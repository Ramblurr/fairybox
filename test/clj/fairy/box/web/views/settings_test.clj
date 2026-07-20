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

(defn- save-playback-action-fn []
  (ns-resolve 'fairy.box.web.views.settings
              'save-playback-settings-fn))

(defn- playback-request [db-conn]
  {:url-for {:page/home "/"
             :page/queue "/queue"
             :page/settings "/settings"}
   :fairy.box/component {:fairy.box.db/db db-conn}})

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

(deftest playback-settings-use-legacy-layout-and-datastar-form
  (let [db-conn
        (atom {:settings
               {:audio {:min-volume 1
                        :max-volume 90
                        :max-volume-day 80
                        :max-volume-night 50
                        :hour-day-start 8
                        :hour-night-start 19}}})
        req (playback-request db-conn)
        action-path (ns-resolve 'fairy.box.web.views.settings
                                'save-playback-settings)
        render-page (ns-resolve 'fairy.box.web.views.settings
                                'render-playback-fn)]
    (is (= {:action true :render true}
           {:action (some? action-path)
            :render (some? render-page)}))
    (when (and action-path render-page)
      (let [html (h/html->str (render-page req))
            inputs (re-seq #"<input[^>]+>" html)
            signal-names ["min_volume" "max_volume"
                          "max_volume_day" "max_volume_night"
                          "hour_day_start" "hour_night_start"]
            labels ["Min Volume" "Max Volume"
                    "Max Volume (Day)" "Max Volume (Night)"
                    "Day Starts At" "Night Starts At"]]
        (is (= {:legacy-layout true
                :labels true
                :signals true
                :bindings true
                :volume-limits true
                :hour-limits true
                :submit-action true
                :ordinary-back-link true
                :settings-placeholder-removed true
                :htmx-removed true}
               {:legacy-layout
                (and (str/includes? html "id=\"active-tab\"")
                     (str/includes? html "id=\"playback-settings\"")
                     (str/includes? html "Playback Settings"))
                :labels (every? #(str/includes? html %) labels)
                :signals
                (every? #(str/includes?
                          html
                          (str "data-signals:" % "__ifmissing="))
                        signal-names)
                :bindings
                (every? #(str/includes? html (str "data-bind=\"" % "\""))
                        signal-names)
                :volume-limits
                (every? #(and (str/includes? % "min=\"0\"")
                              (str/includes? % "max=\"100\"")
                              (str/includes? % "step=\"1\""))
                        (take 4 inputs))
                :hour-limits
                (every? #(and (str/includes? % "min=\"0\"")
                              (str/includes? % "max=\"23\"")
                              (str/includes? % "step=\"1\""))
                        (drop 4 inputs))
                :submit-action
                (and (str/includes? html "data-on:submit=")
                     (str/includes? html (var-get action-path)))
                :ordinary-back-link
                (str/includes? html "href=\"/settings\"")
                :settings-placeholder-removed
                (not (str/includes? html ">Settings</h1>"))
                :htmx-removed (not (str/includes? html "hx-"))}))))))

(deftest saves-playback-settings-through-component-lookup
  (let [db-conn (atom {:settings {:audio {:max-volume 95}}
                       :unrelated :preserved})
        req (playback-request db-conn)
        save! (save-playback-action-fn)]
    (is (some? save!))
    (when save!
      (let [_full-save
            (save! (assoc req :body
                          {:min_volume "2"
                           :max_volume 91
                           :max_volume_day "81"
                           :max_volume_night 51
                           :hour_day_start "7"
                           :hour_night_start 20}))
            after-full-save @db-conn
            _partial-save
            (save! (assoc req :body
                          {:min_volume ""
                           :max_volume_day "82"}))
            after-partial-save @db-conn]
        (is (= {:after-full-save
                {:settings
                 {:audio {:min-volume 2
                          :max-volume 91
                          :max-volume-day 81
                          :max-volume-night 51
                          :hour-day-start 7
                          :hour-night-start 20}}
                 :unrelated :preserved}
                :after-partial-save
                {:settings {:audio {:max-volume-day 82}}
                 :unrelated :preserved}}
               {:after-full-save after-full-save
                :after-partial-save after-partial-save}))))))