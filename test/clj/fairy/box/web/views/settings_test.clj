(ns fairy.box.web.views.settings-test
  (:require
   [babashka.fs :as fs]
   [clojure.core.async :as async]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [fairy.box.timers :as timers]
   [fairy.box.media-test-utils :as media]
   [fairy.box.web.views :as views]
   [fairy.box.web.views.settings :as settings-view]
   [hyperlith.core :as h]))

(defn- action-fn []
  (ns-resolve 'fairy.box.web.views.settings 'link-rfid-folder-fn))

(defn- play-action-fn []
  (ns-resolve 'fairy.box.web.views.settings 'play-audio-path-fn))

(defn- cycle-announcement-action-fn []
  (ns-resolve 'fairy.box.web.views.settings
              'cycle-path-announcement-fn))

(defn- save-device-action-fn []
  (ns-resolve 'fairy.box.web.views.settings
              'save-device-settings-fn))

(defn- control-system-action-fn []
  (ns-resolve 'fairy.box.web.views.settings 'control-system-fn))

(defn- settings-request [controls-enabled?]
  {:url-for
   {:page.settings/rfid-link "/settings/rfid"
    :page.settings/browse    "/settings/browse"
    :page.settings/device    "/settings/device"
    :page.settings/tts       "/settings/tts"}
   :fairy.box/component
   {:fairy.box/settings
    {:shutdown {:poweroff-enabled? controls-enabled?}}}})

(defn- device-request [db-conn]
  {:url-for
   {:page/home            "/"
    :page/queue           "/queue"
    :page/settings        "/settings"
    :page.settings/device "/settings/device"}
   :fairy.box/component
   {:fairy.box.auto-shutdown/timer
    {:fairy.box.timers/current
     (constantly {:enabled? true :selected-minutes 45 :idle? true})}
    :fairy.box.db/db               db-conn
    :fairy.box.sleep/timer
    {:fairy.box.timers/current
     (constantly {:enabled? false :selected-minutes 30 :phase :off})}}})

(defn- browse-request [tree dir]
  (assoc (media/request tree dir)
         :uri "/settings/browse"
         :url-for {:page/home     "/"
                   :page/queue    "/queue"
                   :page/settings "/settings"}))

(defn- action-request [tree selected-folder rfid]
  (let [req               (media/request tree "audiobooks/Author One")
        refresh-component ((:fairy.box/component req)
                           :fairy.box.web/refresh)]
    (reset! (:rfid-presence refresh-component) rfid)
    (assoc req
           :body {:selected_folder selected-folder
                  :rfid_uid        "stale-browser-tag"})))

(deftest rfid-form-uses-datastar-action-and-ordinary-back-link
  (fs/with-temp-dir [temp-dir {:prefix "fairybox-rfid-form-"}]
    (let [tree        (media/populate-media-tree! temp-dir)
          req         (media/request tree "audiobooks/Author Two")
          action-path (ns-resolve 'fairy.box.web.views.settings
                                  'link-rfid-folder)
          html        (-> (settings-view/rfid-link-form
                           req
                           "tag-1"
                           "audiobooks/Author Two")
                          h/html->str)]
      (is (some? action-path))
      (when action-path
        (is (= {:form                  true
                :selection-signal      true
                :submit-action         true
                :radio-binding         true
                :ordinary-back-link    true
                :client-rfid-removed   true
                :announcement-controls false
                :htmx-removed          true}
               {:form         (str/starts-with? html "<form")
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
                :announcement-controls
                (str/includes? html "data-announcement-state=")
                :htmx-removed (not (str/includes? html "hx-"))}))))))

(deftest links-selected-folder-to-current-rfid
  (fs/with-temp-dir [temp-dir {:prefix "fairybox-rfid-action-"}]
    (let [tree    (media/populate-media-tree! temp-dir)
          req     (action-request tree
                                  "audiobooks/Author One/../Author Two"
                                  {:action :placed :uid "current-tag"})
          db-conn ((:fairy.box/component req) :fairy.box.db/db)
          link!   (action-fn)]
      (is (some? link!))
      (when link!
        (link! req)
        (is (= {:linked-tags
                {"current-tag"
                 {:folder "audiobooks/Author Two"}}}
               @db-conn))))))

(deftest rejects-link-without-current-rfid
  (fs/with-temp-dir [temp-dir {:prefix "fairybox-rfid-no-tag-"}]
    (let [tree    (media/populate-media-tree! temp-dir)
          req     (action-request tree
                                  "audiobooks/Author Two"
                                  {:action :removed :uid "old-tag"})
          db-conn ((:fairy.box/component req) :fairy.box.db/db)
          link!   (action-fn)]
      (when link!
        (link! req)
        (is (= {:linked-tags {}} @db-conn))))))

(deftest rejects-missing-and-escaped-link-paths
  (fs/with-temp-dir [temp-dir {:prefix "fairybox-rfid-invalid-path-"}]
    (let [tree  (media/populate-media-tree! temp-dir)
          link! (action-fn)]
      (when link!
        (let [db-states
              (mapv (fn [selected-folder]
                      (let [req     (action-request tree
                                                    selected-folder
                                                    {:action :placed :uid "tag-1"})
                            db-conn ((:fairy.box/component req)
                                     :fairy.box.db/db)]
                        (link! req)
                        @db-conn))
                    ["missing" "../../etc"])]
          (is (= [{:linked-tags {}} {:linked-tags {}}]
                 db-states)))))))

(deftest browse-audio-preserves-navigation-and-adds-announcement-controls
  (fs/with-temp-dir [temp-dir {:prefix "fairybox-audio-browser-"}]
    (let [tree        (media/populate-media-tree! temp-dir)
          play-action (ns-resolve 'fairy.box.web.views.settings
                                  'play-audio-path)
          announcement-action
          (ns-resolve 'fairy.box.web.views.settings
                      'cycle-path-announcement)
          render-page (ns-resolve 'fairy.box.web.views.settings
                                  'render-browse-fn)
          request     (browse-request tree nil)
          db-conn     ((:fairy.box/component request)
                       :fairy.box.db/db)]
      (swap! db-conn assoc
             :settings {:tts {:announce-tracks? false}}
             :media-metadata
             {"audiobooks" {:announce? true}
              "audiobooks/Author One"           {:announce? false}
              "audiobooks/Author Two/Story.mp3" {:announce? true}})
      (let [root-html     (h/html->str (render-page request))
            nav-html      (h/html->str
                           (render-page
                            (assoc request
                                   :query-params {"dir" "audiobooks"})))
            play-html     (h/html->str
                           (render-page
                            (assoc request
                                   :query-params
                                   {"dir" "audiobooks/Author Two"})))
            playlist-html (h/html->str
                           (render-page
                            (assoc request
                                   :query-params {"dir" "playlists"})))
            labeled?      (fn [html title]
                            (and (str/includes? html
                                                (str "title=\"" title "\""))
                                 (str/includes?
                                  html
                                  (str "aria-label=\"" title "\""))))
            inherit-enabled-title
            (str "Inherit track announcement setting; currently announces"
                 "; global track announcements are off")
            announce-disabled-title
            (str "Announce tracks for this path"
                 "; global track announcements are off")]
        (is (= {:registered
                {:play         true
                 :announcement true
                 :render       true}
                :legacy-layout                true
                :heading true
                :requested-directory          true
                :ordinary-directory-link      true
                :datastar-play-action         true
                :relative-play-path           true
                :announcement-controls
                {:root      3
                 :directory 2
                 :file      1
                 :playlist  1}
                :announcement-states
                {:announce        true
                 :do-not-announce true
                 :inherit         true}
                :announcement-titles
                {:announce        true
                 :do-not-announce true
                 :inherit-enabled true
                 :inherit-quiet   true}
                :relative-announcement-paths  true
                :button-types                 true
                :settings-placeholder-removed true
                :absolute-path-hidden         true
                :htmx-removed                 true}
               {:registered
                {:play         (some? play-action)
                 :announcement (some? announcement-action)
                 :render       (some? render-page)}
                :legacy-layout
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
                     (str/includes? play-html (var-get play-action)))
                :relative-play-path
                (str/includes?
                 play-html
                 "path=audiobooks%2FAuthor+Two%2FStory.mp3")
                :announcement-controls
                {:root      (count (re-seq #"data-announcement-state="
                                           root-html))
                 :directory (count (re-seq #"data-announcement-state="
                                           nav-html))
                 :file      (count (re-seq #"data-announcement-state="
                                           play-html))
                 :playlist  (count (re-seq #"data-announcement-state="
                                           playlist-html))}
                :announcement-states
                {:announce
                 (str/includes? play-html
                                "data-announcement-state=\"announce\"")
                 :do-not-announce
                 (str/includes? nav-html
                                "data-announcement-state=\"do-not-announce\"")
                 :inherit
                 (and (str/includes? nav-html
                                     "data-announcement-state=\"inherit\"")
                      (str/includes? playlist-html
                                     "data-announcement-state=\"inherit\""))}
                :announcement-titles
                {:announce
                 (labeled? play-html announce-disabled-title)
                 :do-not-announce
                 (labeled? nav-html "Do not announce tracks for this path")
                 :inherit-enabled
                 (labeled? nav-html inherit-enabled-title)
                 :inherit-quiet
                 (labeled?
                  playlist-html
                  (str "Inherit track announcement setting; currently "
                       "does not announce"))}
                :relative-announcement-paths
                (and (str/includes?
                      nav-html
                      (str (var-get announcement-action)
                           "?path=audiobooks%2FAuthor+One"))
                     (str/includes?
                      play-html
                      (str (var-get announcement-action)
                           "?path=audiobooks%2FAuthor+Two%2FStory.mp3"))
                     (str/includes?
                      playlist-html
                      (str (var-get announcement-action)
                           "?path=playlists%2FFavorites.m3u")))
                :button-types
                (>= (count (re-seq #"type=\"button\"" play-html)) 2)
                :settings-placeholder-removed
                (not (str/includes? nav-html ">Settings</h1>"))
                :absolute-path-hidden
                (not (some #(str/includes? % (:root tree))
                           [root-html nav-html play-html playlist-html]))
                :htmx-removed
                (not (some #(str/includes? % "hx-")
                           [root-html nav-html play-html playlist-html]))}))))))

(deftest cycles-valid-announcement-paths-and-rejects-invalid-paths
  (fs/with-temp-dir [temp-dir {:prefix "fairybox-announcement-action-"}]
    (let [tree       (media/populate-media-tree! temp-dir)
          request    (browse-request tree nil)
          db-conn    ((:fairy.box/component request) :fairy.box.db/db)
          cycle!     (cycle-announcement-action-fn)
          writes_    (atom 0)
          refreshes_ (atom 0)
          invoke!    (fn [path]
                       (cycle! (assoc request
                                      :query-params {"path" path})))]
      (add-watch db-conn ::announcement-writes
                 (fn [_ _ _ _]
                   (swap! writes_ inc)))
      (with-redefs [h/refresh-all! (fn [& _]
                                     (swap! refreshes_ inc))]
        (let [r1                 (invoke! "audiobooks")
              s1                 (get-in @db-conn [:media-metadata "audiobooks"])
              r2                 (invoke! "audiobooks")
              s2                 (get-in @db-conn [:media-metadata "audiobooks"])
              r3                 (invoke! "audiobooks")
              s3                 (get-in @db-conn [:media-metadata "audiobooks"])
              file-result        (invoke! "audiobooks/Author Two/Story.mp3")
              playlist-result    (invoke! "playlists/Favorites.m3u")
              after-valid        @db-conn
              writes-after-valid @writes_
              invalid-results    (mapv invoke!
                                       ["missing" "../escape" "notes.txt"])]
          (remove-watch db-conn ::announcement-writes)
          (is (= {:action-available?   true
                  :directory-cycle     [{:announce? true}
                                        {:announce? false}
                                        nil]
                  :returns             [nil nil nil nil nil]
                  :database-after-valid
                  {:linked-tags {}
                   :media-metadata
                   {"audiobooks/Author Two/Story.mp3" {:announce? true}
                    "playlists/Favorites.m3u"         {:announce? true}}}
                  :valid-write-count   5
                  :invalid-results     [nil nil nil]
                  :invalid-write-count 0
                  :invalid-changed?    false
                  :direct-refreshes    0}
                 {:action-available?    (some? cycle!)
                  :directory-cycle      [s1 s2 s3]
                  :returns              [r1 r2 r3 file-result playlist-result]
                  :database-after-valid after-valid
                  :valid-write-count    writes-after-valid
                  :invalid-results      invalid-results
                  :invalid-write-count  (- @writes_ writes-after-valid)
                  :invalid-changed?     (not= after-valid @db-conn)
                  :direct-refreshes     @refreshes_})))))))

(deftest plays-canonical-media-path-and-redirects-home
  (fs/with-temp-dir [temp-dir {:prefix "fairybox-audio-action-"}]
    (let [tree     (media/populate-media-tree! temp-dir)
          commands (async/chan 1)
          req      (-> (browse-request tree "audiobooks/Author Two")
                       (assoc-in [:query-params "path"]
                                 "audiobooks/Author Two/Story.mp3")
                       (assoc-in [:fairy.box/component
                                  :fairy.box.switchboard/switchboard]
                                 {:emitter commands}))
          play!    (play-action-fn)]
      (is (some? play!))
      (when play!
        (let [response       (play! req)
              [command port] (async/alts!! [commands (async/timeout 1000)])
              result         {:command   command
                              :received? (= port commands)
                              :redirect? (str/includes?
                                          (h/html->str response)
                                          "window.location.assign(&apos;/&apos;)")}]
          (async/close! commands)
          (is (= {:command
                  {:path "/player/commands"
                   :value
                   {:action    :audio/play-path
                    :item-path (str (fs/canonicalize
                                     (fs/path (:root tree)
                                              "audiobooks/Author Two/Story.mp3")))
                    :uid       nil}}
                  :received? true
                  :redirect? true}
                 result)))))))

(deftest rejects-missing-escaped-and-unplayable-audio-paths
  (fs/with-temp-dir [temp-dir {:prefix "fairybox-invalid-audio-action-"}]
    (let [tree  (media/populate-media-tree! temp-dir)
          play! (play-action-fn)]
      (is (some? play!))
      (when play!
        (let [results
              (mapv
               (fn [path]
                 (let [commands (async/chan 1)
                       req      (-> (browse-request tree nil)
                                    (assoc-in [:query-params "path"] path)
                                    (assoc-in [:fairy.box/component
                                               :fairy.box.switchboard/switchboard]
                                              {:emitter commands}))
                       response (play! req)
                       [command port]
                       (async/alts!! [commands (async/timeout 100)])
                       result   {:response? (some? response)
                                 :command   command
                                 :emitted?  (= port commands)}]
                   (async/close! commands)
                   result))
               ["missing" "../../etc/passwd" "audiobooks/Author One"])]
          (is (= (repeat 3 {:response? false
                            :command   nil
                            :emitted?  false})
                 results)))))))

(deftest settings-power-controls-use-out-of-way-native-dialog
  (let [enabled-html  (h/html->str
                       (settings-view/settings-view
                        (settings-request true)))
        disabled-html (h/html->str
                       (settings-view/settings-view
                        (settings-request false)))
        list-end      (str/index-of enabled-html "</ul>")
        launcher      (str/index-of enabled-html
                                    "power-controls-launcher")]
    (is (= {:native-dialog                true
            :grouped-options              true
            :outside-settings-list        true
            :operation-actions            true
            :production-actions-enabled   true
            :development-actions-disabled true
            :development-notice           true
            :development-actions-removed  true}
           {:native-dialog
            (and (str/includes? enabled-html
                                "<dialog id=\"power-dialog\"")
                 (str/includes? enabled-html "method=\"dialog\"")
                 (str/includes? enabled-html ".showModal()"))
            :grouped-options
            (every? #(str/includes? enabled-html %)
                    [">Device</h3>" ">Fairybox</h3>"
                     ">Power off</span>" ">Reboot</span>"
                     ">Restart</span>"])
            :outside-settings-list
            (and (some? list-end)
                 (some? launcher)
                 (< list-end launcher))
            :operation-actions
            (every? #(str/includes? enabled-html %)
                    ["operation=poweroff"
                     "operation=reboot"
                     "operation=restart-fairybox"])
            :production-actions-enabled
            (not (str/includes? enabled-html
                                "power-dialog-action\" disabled"))
            :development-actions-disabled
            (= 3 (count (re-seq
                         #"power-dialog-action[^>]* disabled"
                         disabled-html)))
            :development-notice
            (str/includes? disabled-html
                           "disabled outside the Raspberry Pi production profile")
            :development-actions-removed
            (not (str/includes? disabled-html "operation="))}))))

(deftest system-control-action-emits-allowlisted-system-events
  (let [control! (control-system-action-fn)
        emitter  (async/chan 8)
        request  (fn [controls-enabled? operation]
                   (-> (settings-request controls-enabled?)
                       (assoc :query-params {"operation" operation})
                       (assoc-in [:fairy.box/component
                                  :fairy.box.switchboard/switchboard]
                                 {:emitter emitter})))
        results  {:poweroff (control! (request true "poweroff"))
                  :reboot   (control! (request true "reboot"))
                  :restart  (control! (request true "restart-fairybox"))
                  :unknown  (control! (request true "unknown"))
                  :development-poweroff
                  (control! (request false "poweroff"))
                  :development-reboot
                  (control! (request false "reboot"))}
        events   (loop [events []]
                   (if-let [event (async/poll! emitter)]
                     (recur (conj events event))
                     events))]
    (async/close! emitter)
    (is (= {:results {:poweroff             nil
                      :reboot               nil
                      :restart              nil
                      :unknown              nil
                      :development-poweroff nil
                      :development-reboot   nil}
            :events  [{:path  "/system"
                       :value {:event :system/poweroff}}
                      {:path  "/system"
                       :value {:event :system/reboot}}
                      {:path  "/system"
                       :value {:event :system/restart-fairybox}}
                      {:path  "/system"
                       :value {:event :system/poweroff}}
                      {:path  "/system"
                       :value {:event :system/reboot}}]}
           {:results results :events events}))))

(deftest device-settings-use-responsive-cards-and-sliders
  (let [db-conn
        (atom {:settings
               {:audio {:min-volume               1
                        :max-volume               90
                        :max-volume-day           80
                        :max-volume-night         50
                        :max-led-brightness-day   75
                        :max-led-brightness-night 20
                        :day-start                "08:30"
                        :night-start              "19:45"
                        :card-removal-behavior    :pause
                        :card-return-behavior     :restart}}})
        req         (device-request db-conn)
        action-path (ns-resolve 'fairy.box.web.views.settings
                                'save-device-settings)
        render-page (ns-resolve 'fairy.box.web.views.settings
                                'render-device-fn)]
    (is (= {:action true :render true}
           {:action (some? action-path)
            :render (some? render-page)}))
    (when (and action-path render-page)
      (let [html            (h/html->str (render-page req))
            menu-html       (h/html->str
                             (settings-view/settings-view req))
            inputs          (re-seq #"<input[^>]+>" html)
            range-inputs    (filter #(str/includes? % "type=\"range\"")
                                    inputs)
            number-inputs   (filter #(str/includes? % "type=\"number\"")
                                    inputs)
            time-inputs     (filter #(str/includes? % "type=\"time\"")
                                    inputs)
            outputs         (re-seq #"<output[^>]+>[^<]*</output>" html)
            slider-bindings ["min_volume" "max_volume"
                             "max_volume_day" "max_volume_night"
                             "max_led_brightness_day"
                             "max_led_brightness_night"]
            time-bindings   ["day_start" "night_start"]
            card-bindings   ["card_removal_behavior"
                             "card_return_behavior"]
            sleep-bindings  ["sleep_shutdown"
                             "sleep_shutdown_delay_minutes"]
            labels          ["Device Settings" "Overall volume"
                             "Minimum volume" "Safety maximum"
                             "Day &amp; night profiles" "Day" "Night"
                             "Starts at" "Maximum volume" "LED brightness"
                             "Sleep timer" "Fade-out time"
                             "Delay before shutdown" "Auto shutdown"
                             "Idle time" "45 minutes"
                             "Disable auto shutdown"]
            card-labels     ["RFID card behavior" "Keep playing"
                             "Pause playback" "Resume playback"
                             "Restart the playlist"]
            card-ids        ["overall-volume-settings"
                             "card-behavior-settings"
                             "day-night-settings"
                             "day-profile" "night-profile"
                             "sleep-timer-settings"
                             "auto-shutdown-settings"]
            radio-inputs    (filter #(str/includes? % "type=\"radio\"")
                                    inputs)]
        (is (= {:device-page            true
                :device-route           true
                :menu-link              true
                :responsive-cards       true
                :labels                 true
                :signals-initialized    true
                :bindings               true
                :sleep-chevron-icons    true
                :auto-shutdown-controls true
                :card-controls
                {:labels           true
                 :radio-count      4
                 :bindings         true
                 :checked-defaults true}
                :sliders
                {:count          6
                 :number-inputs  1
                 :limits         true
                 :current-values true
                 :endpoints      true
                 :shutdown-delay true}
                :time-inputs
                {:count       2
                 :day         true
                 :night       true
                 :minute-step true}
                :live-save-on-change    true
                :save-button-removed    true
                :ordinary-back-link     true
                :legacy-name-removed    true
                :htmx-removed           true}
               {:device-page
                (and (str/includes? html "id=\"active-tab\"")
                     (str/includes? html "id=\"device-settings\"")
                     (str/includes? html "Device Settings"))
                :device-route
                (and (= "/settings/device"
                        (views/url-for :page.settings/device))
                     (= "/404"
                        (views/url-for :page.settings/playback)))
                :menu-link
                (and (str/includes? menu-html "href=\"/settings/device\"")
                     (str/includes? menu-html ">Device</span>"))
                :responsive-cards
                (every? #(str/includes? html (str "id=\"" % "\""))
                        card-ids)
                :labels
                (every? #(str/includes? html %) labels)
                :signals-initialized
                (str/includes? html "data-signals__ifmissing=")
                :bindings
                (every? #(str/includes? html (str "data-bind=\"" % "\""))
                        (concat slider-bindings time-bindings
                                sleep-bindings))
                :sleep-chevron-icons
                (and (str/includes? html "aria-label=\"Previous sleep duration\"")
                     (str/includes? html "aria-label=\"Next sleep duration\"")
                     (str/includes? html "M439.1 297.4C451.6 309.9")
                     (str/includes? html "rotate(180 320 320)")
                     (not (or (str/includes? html "‹")
                              (str/includes? html "›"))))
                :auto-shutdown-controls
                (and (str/includes?
                      html
                      "aria-label=\"Previous auto shutdown duration\"")
                     (str/includes?
                      html
                      "aria-label=\"Next auto shutdown duration\"")
                     (str/includes? html ">45 minutes</p>")
                     (str/includes? html ">Disable auto shutdown</button>")
                     (not (str/includes? html "Auto shuts down at")))
                :card-controls
                {:labels      (every? #(str/includes? html %) card-labels)
                 :radio-count (count radio-inputs)
                 :bindings
                 (every? #(str/includes? html (str "data-bind=\"" % "\""))
                         card-bindings)
                 :checked-defaults
                 (every? (fn [value]
                           (some #(and (str/includes? % (str "value=\"" value "\""))
                                       (str/includes? % "checked"))
                                 radio-inputs))
                         ["pause" "restart"])}
                :sliders
                {:count         (count range-inputs)
                 :number-inputs (count number-inputs)
                 :limits
                 (every? #(and (str/includes? % "min=\"0\"")
                               (str/includes? % "max=\"100\"")
                               (str/includes? % "step=\"1\""))
                         range-inputs)
                 :current-values
                 (and (= 6 (count outputs))
                      (every? (fn [binding]
                                (some #(str/includes? % (str "$" binding))
                                      outputs))
                              slider-bindings))
                 :endpoints
                 (and (= 6 (count (re-seq #">0%</span>" html)))
                      (= 6 (count (re-seq #">100%</span>" html))))
                 :shutdown-delay
                 (some #(and (str/includes? % "min=\"0\"")
                             (str/includes? % "step=\"1\"")
                             (str/includes?
                              %
                              "data-bind=\"sleep_shutdown_delay_minutes\""))
                       number-inputs)}
                :time-inputs
                {:count       (count time-inputs)
                 :day         (some #(str/includes? % "name=\"day-profile-start\"")
                                    time-inputs)
                 :night       (some #(str/includes? % "name=\"night-profile-start\"")
                                    time-inputs)
                 :minute-step (every? #(str/includes? % "step=\"60\"")
                                      time-inputs)}
                :live-save-on-change
                (and (str/includes? html "data-on:change=")
                     (str/includes? html (var-get action-path)))
                :save-button-removed
                (and (not (str/includes? html "data-on:submit="))
                     (not (str/includes? html "type=\"submit\""))
                     (not (str/includes? html ">Save</span>")))
                :ordinary-back-link
                (str/includes? html "href=\"/settings\"")
                :legacy-name-removed
                (not (or (str/includes? html "Playback Settings")
                         (str/includes? menu-html ">Playback</span>")))
                :htmx-removed (not (str/includes? html "hx-"))}))))))

(deftest saves-one-complete-device-update
  (let [db-conn    (atom {:settings
                          {:audio {:min-volume               1
                                   :max-volume               95
                                   :max-volume-day           80
                                   :max-volume-night         50
                                   :max-led-brightness-day   100
                                   :max-led-brightness-night 100
                                   :day-start                "08:00"
                                   :night-start              "19:00"
                                   :card-removal-behavior    :pause
                                   :card-return-behavior     :restart
                                   :unknown                  :preserved}}
                          :unrelated :preserved})
        writes_    (atom 0)
        _          (add-watch db-conn ::write-count
                              (fn [_ _ _ _]
                                (swap! writes_ inc)))
        req        (device-request db-conn)
        save!      (save-device-action-fn)
        valid-body {:min_volume               2
                    :max_volume               91
                    :max_volume_day           81
                    :max_volume_night         51
                    :max_led_brightness_day   75
                    :max_led_brightness_night 20
                    :day_start                "08:30"
                    :night_start              "19:45"
                    :card_removal_behavior    "keep-playing"
                    :card_return_behavior     "resume"}]
    (is (some? save!))
    (when save!
      (save! (assoc req :body valid-body))
      (let [after-valid        @db-conn
            writes-after-valid @writes_]
        (doseq [invalid-body [(assoc valid-body :day_start "")
                              (assoc valid-body :night_start "19:60")
                              (assoc valid-body
                                     :max_led_brightness_day
                                     101)]]
          (save! (assoc req :body invalid-body)))
        (remove-watch db-conn ::write-count)
        (is (= {:database
                {:settings
                 {:audio {:min-volume               2
                          :max-volume               91
                          :max-volume-day           81
                          :max-volume-night         51
                          :max-led-brightness-day   75
                          :max-led-brightness-night 20
                          :day-start                "08:30"
                          :night-start              "19:45"
                          :card-removal-behavior    :keep-playing
                          :card-return-behavior     :resume
                          :unknown                  :preserved}}
                 :unrelated :preserved}
                :writes-after-valid  1
                :invalid-write-count 0
                :invalid-changed?    false}
               {:database            after-valid
                :writes-after-valid  writes-after-valid
                :invalid-write-count (- @writes_ writes-after-valid)
                :invalid-changed?    (not= after-valid @db-conn)}))))))

(deftest saves-zero-delay-sleep-settings-and-rejects-negative-delays
  (let [db-conn (atom {:settings {:audio {:unknown :preserved}
                                  :sleep {:unknown :preserved}}})
        save!   (save-device-action-fn)
        request (device-request db-conn)
        body    {:min_volume                   2
                 :max_volume                   91
                 :max_volume_day               81
                 :max_volume_night             51
                 :max_led_brightness_day       75
                 :max_led_brightness_night     20
                 :day_start                    "08:30"
                 :night_start                  "19:45"
                 :card_removal_behavior        "pause"
                 :card_return_behavior         "restart"
                 :sleep_shutdown               false
                 :sleep_shutdown_delay_minutes 0}]
    (save! (assoc request :body body))
    (let [after-valid @db-conn]
      (save! (assoc request
                    :body
                    (assoc body :sleep_shutdown_delay_minutes -1)))
      (is (= {:sleep                   {:unknown                :preserved
                                        :shutdown?              false
                                        :shutdown-delay-minutes 0}
              :negative-delay-changed? false}
             {:sleep                   (get-in @db-conn [:settings :sleep])
              :negative-delay-changed? (not= after-valid @db-conn)})))))

(deftest timer-actions-rely-on-change-events-for-page-refreshes
  (let [sleep-toggle! (ns-resolve 'fairy.box.web.views.settings
                                  'toggle-sleep-timer-fn)
        sleep-cycle!  (ns-resolve 'fairy.box.web.views.settings
                                  'cycle-sleep-duration-fn)
        auto-toggle!  (ns-resolve 'fairy.box.web.views.settings
                                  'toggle-auto-shutdown-timer-fn)
        auto-cycle!   (ns-resolve 'fairy.box.web.views.settings
                                  'cycle-auto-shutdown-duration-fn)
        states_       (atom {::sleep false ::auto true})
        calls_        (atom [])
        refreshes_    (atom 0)
        request       {:fairy.box/component
                       {:fairy.box.sleep/timer         ::sleep
                        :fairy.box.auto-shutdown/timer ::auto}}]
    (with-redefs [timers/enabled? #(get @states_ %)
                  timers/enable!  (fn [timer]
                                    (swap! states_ assoc timer true)
                                    (swap! calls_ conj [timer :enable]))
                  timers/disable! (fn [timer]
                                    (swap! states_ assoc timer false)
                                    (swap! calls_ conj [timer :disable]))
                  timers/cycle!   (fn [timer direction]
                                    (swap! calls_ conj
                                           [timer :cycle direction]))
                  h/refresh-all!  (fn [& _]
                                    (swap! refreshes_ inc))]
      (sleep-toggle! request)
      (auto-toggle! request)
      (sleep-cycle! (assoc request
                           :query-params {"direction" "next"}))
      (auto-cycle! (assoc request
                          :query-params {"direction" "previous"})))
    (is (= {:calls            [[::sleep :enable]
                               [::auto :disable]
                               [::sleep :cycle :next]
                               [::auto :cycle :previous]]
            :direct-refreshes 0}
           {:calls @calls_ :direct-refreshes @refreshes_}))))