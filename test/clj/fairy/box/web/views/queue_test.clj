(ns fairy.box.web.views.queue-test
  (:require
   [babashka.fs :as fs]
   [clojure.core.async :as async]
   [clojure.string :as str]
   [clojure.test :refer [deftest is use-fixtures]]
   [fairy.box.audio.current :as current]
   [fairy.box.audio.system2 :as audio-system]
   [fairy.box.media-test-utils :as media]
   [fairy.box.web.views.queue :as queue]
   [hyperlith.core :as h]))

(defn- action-fn []
  (ns-resolve 'fairy.box.web.views.queue 'play-queue-item-fn))

(defn- action-path []
  (var-get (ns-resolve 'fairy.box.web.views.queue 'play-queue-item)))

(defn- render-fn []
  (ns-resolve 'fairy.box.web.views.queue 'render-queue-fn))

(defn- sample-state []
  {:playback {:state :playing}
   :mixer    {:muted? false :volume 40}
   :queue
   {:source-type :folder
    :source-path "audiobooks/Author One/Book One"
    :tracks
    [{:id    "past"
      :mrl   "file:///past.mp3"
      :index -1
      :meta  #:meta{:title  "Past Track"
                    :artist "Past Artist"
                    :album  "Past Album"}}
     {:id    "current"
      :mrl   "file:///current.mp3"
      :index 0
      :meta  #:meta{:title  "Current Track"
                    :artist "Current Artist"
                    :album  "Current Album"}}
     {:id    "next"
      :mrl   "file:///next.mp3"
      :index 1
      :meta  #:meta{:title  "Next Track"
                    :artist ""
                    :album  "Next Album"}}]}
   :config   {:volume-up-step 5 :volume-down-step -5}})

(defn- with-restored-audio-state [f]
  (let [original @audio-system/audio-state]
    (try
      (f)
      (finally
        (reset! audio-system/audio-state original)))))

(use-fixtures :each with-restored-audio-state)

(defn- queue-request [tree]
  (assoc (media/request tree nil)
         :uri "/queue"
         :url-for {:page/home            "/"
                   :page/queue           "/queue"
                   :page/settings        "/settings"
                   :page.settings/browse "/settings/browse"}))

(deftest renders-empty-queue-state
  (let [html (h/html->str
              (queue/play-queue-list
               {:tracks  []
                :url-for {:page/settings "/settings"}}))]
    (is (= {:queue-target         true
            :message              true
            :settings-link        true
            :populated-ui-hidden? true}
           {:queue-target  (str/includes? html "id=\"play-queue\"")
            :message       (str/includes? html "The play queue is empty.")
            :settings-link (str/includes? html
                                          "href=\"/settings\"")
            :populated-ui-hidden?
            (not (or (str/includes? html ">Folder</p>")
                     (str/includes? html ">Tracks</p>")
                     (str/includes? html "role=\"list\"")))}))))

(deftest renders-legacy-layout-active-state-and-datastar-controls
  (fs/with-temp-dir [temp-dir {:prefix "fairybox-queue-render-"}]
    (let [tree           (media/populate-media-tree! temp-dir)
          state          (sample-state)
          _              (reset! audio-system/audio-state state)
          html           (h/html->str ((render-fn) (queue-request tree)))
          tracks         (get-in state [:queue :tracks])
          inactive-class (get-in (queue/play-queue-item-view
                                  0 0 (first tracks))
                                 [1 :class])
          active-class   (get-in (queue/play-queue-item-view
                                  0 1 (second tracks))
                                 [1 :class])]
      (is (= {:layout                 true
              :labels                 true
              :metadata               true
              :active-state           true
              :full-row-button        true
              :datastar-action        true
              :legacy-command-removed true
              :htmx-removed           true}
             {:layout          (and (str/includes? html "id=\"active-tab\"")
                                    (str/includes? html "id=\"play-queue\"")
                                    (str/includes? html "role=\"list\""))
              :labels          (and (str/includes? html ">Folder</p>")
                                    (str/includes? html ">Tracks</p>"))
              :metadata        (every? #(str/includes? html %)
                                       ["Past Track" "Past Artist" "Past Album"
                                        "Current Track" "Next Track" "Next Album"])
              :active-state    (not= inactive-class active-class)
              :full-row-button
              (every?
               true?
               (map-indexed
                (fn [idx track]
                  (= :button
                     (get-in (queue/play-queue-item-view 0 idx track) [2 0])))
                tracks))
              :datastar-action (and (str/includes? html "data-on:click=")
                                    (str/includes? html (action-path)))
              :legacy-command-removed
              (not (str/includes? html "/player-cmd"))
              :htmx-removed    (not (str/includes? html "hx-"))})))))

(deftest hides-tts-tracks-without-renumbering-queue-controls-test
  (fs/with-temp-dir [temp-dir {:prefix "fairybox-queue-tts-render-"}]
    (let [tts              {:id   "announcement"
                            :mrl  "file:///media/tts-cache/introduction.tts-cache"
                            :meta #:meta{:title "introduction.tts-cache"}}
          introduction     {:id    "introduction"
                            :mrl   "file:///media/Introduction.mp3"
                            :index 1
                            :meta  #:meta{:title "Introduction"}}
          tomorrow         {:id    "tomorrow"
                            :mrl   "file:///media/Tomorrow.mp3"
                            :index 3
                            :meta  #:meta{:title "Tomorrow"}}
          base-state       (sample-state)
          state            (assoc base-state
                                  :playback {:state         :playing
                                             :current-track tts}
                                  :queue (assoc (:queue base-state)
                                                :tracks
                                                [(assoc tts :index 0)
                                                 introduction
                                                 (assoc tts
                                                        :id "second-announcement"
                                                        :index 2
                                                        :mrl
                                                        "file:///media/tts-cache/tomorrow.tts-cache")
                                                 tomorrow]))
          _                (reset! audio-system/audio-state state)
          tree             (media/populate-media-tree! temp-dir)
          html             (h/html->str ((render-fn) (queue-request tree)))
          display          (current/display-queue (current/full-queue state))
          current-index    (:index (current/display-track state))
          active-class     (get-in (queue/play-queue-item-view
                                    current-index 0 introduction)
                                   [1 :class])
          inactive-class   (get-in (queue/play-queue-item-view
                                    current-index 1 tomorrow)
                                   [1 :class])
          rows             (re-seq #"<li .*?</li>" html)
          introduction-row (some #(when (str/includes? % "Introduction") %)
                                 rows)
          tomorrow-row     (some #(when (str/includes? % "Tomorrow") %) rows)]
      (is (= {:titles-visible?       true
              :cache-names-hidden?   true
              :physical-indices      [1 3]
              :physical-controls?    true
              :announced-highlighted true}
             {:titles-visible?
              (every? #(str/includes? html %) ["Introduction" "Tomorrow"])
              :cache-names-hidden?
              (not (or (str/includes? html "introduction.tts-cache")
                       (str/includes? html "tomorrow.tts-cache")))
              :physical-indices (mapv :index display)
              :physical-controls?
              (every? #(str/includes? html (str "item-index=" %)) [1 3])
              :announced-highlighted
              (and (str/includes? introduction-row active-class)
                   (str/includes? tomorrow-row inactive-class))})))))

(deftest links-each-media-directory-to-browse
  (fs/with-temp-dir [temp-dir {:prefix "fairybox-queue-source-"}]
    (let [tree          (media/populate-media-tree! temp-dir)
          req           (queue-request tree)
          folder-html   (h/html->str
                         (queue/source-path-breadcrumb
                          req
                          "audiobooks/Author One/Book One"))
          playlist-html (h/html->str
                         (queue/source-path-breadcrumb
                          req
                          "playlists/Favorites.m3u"))
          outside-html  (h/html->str
                         (queue/source-path-breadcrumb req "/etc/passwd"))]
      (is (= {:folder-links             3
              :root-link                true
              :parent-link              true
              :folder-link              true
              :playlist-directory-links 1
              :playlist-name-plain      true
              :outside-path-plain       true}
             {:folder-links       (count (re-seq #"<a " folder-html))
              :root-link
              (str/includes? folder-html
                             "href=\"/settings/browse?dir=audiobooks\"")
              :parent-link
              (str/includes?
               folder-html
               "href=\"/settings/browse?dir=audiobooks%2FAuthor+One\"")
              :folder-link
              (str/includes?
               folder-html
               (str "href=\"/settings/browse?dir="
                    "audiobooks%2FAuthor+One%2FBook+One\""))
              :playlist-directory-links
              (count (re-seq #"<a " playlist-html))
              :playlist-name-plain
              (and (str/includes? playlist-html "Favorites.m3u")
                   (not (str/includes? playlist-html
                                       "dir=playlists%2FFavorites.m3u")))
              :outside-path-plain (= "/etc/passwd" outside-html)})))))

(deftest emits-selected-queue-index-through-switchboard
  (let [commands (async/chan 1)
        req      {:query-params {"item-index" "-1"}
                  :fairy.box/component
                  {:fairy.box.switchboard/switchboard {:emitter commands}}}]
    (try
      (reset! audio-system/audio-state (sample-state))
      ((action-fn) req)
      (let [[command port] (async/alts!! [commands (async/timeout 1000)])]
        (is (= {:command   {:path  "/player/commands"
                            :value {:action     :audio/play-queue-index
                                    :item-index -1}}
                :received? true}
               {:command   command
                :received? (= port commands)})))
      (finally
        (async/close! commands)))))

(deftest rejects-malformed-and-stale-queue-indices
  (let [commands  (async/chan 1)
        component {:fairy.box.switchboard/switchboard {:emitter commands}}]
    (try
      (reset! audio-system/audio-state (sample-state))
      (doseq [item-index [nil "" "wat" "1.5" "2" 0]]
        ((action-fn) {:query-params        {"item-index" item-index}
                      :fairy.box/component component}))
      (let [[command port] (async/alts!! [commands (async/timeout 100)])]
        (is (= {:command nil :emitted? false}
               {:command command :emitted? (= port commands)})))
      (finally
        (async/close! commands)))))