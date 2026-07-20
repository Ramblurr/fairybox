(ns fairy.box.web.views.player-test
  (:require
   [babashka.fs :as fs]
   [clojure.core.async :as async]
   [clojure.string :as str]
   [clojure.test :refer [deftest is use-fixtures]]
   [fairy.box.audio.current :as current]
   [fairy.box.audio.system2 :as audio-system]
   [fairy.box.web.controllers.artwork :as artwork]
   [fairy.box.web.views.player :as player]
   [hyperlith.core :as h]
   [hyperlith.impl.router :as router]))

(defn- resolved-var [sym]
  (ns-resolve 'fairy.box.web.views.player sym))

(defn- action-fn [sym]
  (resolved-var (symbol (str sym "-fn"))))

(defn- action-path [sym]
  (some-> (resolved-var sym) var-get))

(defn- render-fn []
  (resolved-var 'render-home-fn))

(defn- sample-state []
  {:playback
   {:state       :playing
    :time        61000
    :position    0.25
    :repeat-mode :track
    :shuffle?    true
    :current-track
    {:id       "track-1"
     :mrl      "file:///music/track.mp3"
     :duration 244000
     :meta     #:meta{:title  "Track title"
                      :artist "Track artist"
                      :album  "Track album"}}}
   :mixer    {:muted? false :volume 40}
   :queue    {:history  []
              :current  nil
              :priority []
              :normal   []
              :tracks   []}
   :config   {:volume-up-step 5 :volume-down-step -5}})

(defn- player-request []
  {:url-for {:page/home     "/"
             :page/queue    "/queue"
             :page/settings "/settings"}})

(defn- with-restored-audio-state [f]
  (let [original @audio-system/audio-state]
    (try
      (f)
      (finally
        (reset! audio-system/audio-state original)))))

(use-fixtures :each with-restored-audio-state)

(defn- take-values [channel n]
  (loop [values []]
    (if (= n (count values))
      values
      (let [[value port] (async/alts!! [channel (async/timeout 1000)])]
        (if (= port channel)
          (recur (conj values value))
          values)))))

(deftest renders-legacy-layout-with-hyperlith-actions-and-slider-signals
  (reset! audio-system/audio-state (sample-state))
  (let [actions (mapv action-path
                      '[control-player seek-player set-player-volume])
        html    (h/html->str ((render-fn) (player-request)))]
    (is (= {:layout true
            :metadata                   true
            :labels-and-icons           true
            :actions-present            true
            :datastar-actions           true
            :ephemeral-slider-signals   true
            :server-pushed-progress     true
            :server-driven-time-updates true
            :legacy-command-removed     true
            :htmx-removed               true}
           {:layout                 (every? #(str/includes? html %)
                                            ["id=\"active-tab\""
                                             "id=\"player-controls\""
                                             "id=\"progress-bar\""
                                             "id=\"volume-slider\""
                                             "src=\"/api/current-artwork?v="])
            :metadata               (every? #(str/includes? html %)
                                            ["Track title" "Track artist" "Track album"])
            :labels-and-icons
            (every? #(str/includes? html %)
                    ["aria-label=\"Previous\""
                     "aria-label=\"Rewind 10 seconds\""
                     "aria-label=\"Pause\""
                     "aria-label=\"Skip 10 seconds\""
                     "aria-label=\"Next\""
                     "<rect"])
            :actions-present        (every? string? actions)
            :datastar-actions
            (and (every? #(and (string? %)
                               (str/includes? html %))
                         actions)
                 (str/includes? html "data-on:click=")
                 (str/includes? html "data-on:change="))
            :ephemeral-slider-signals
            (every? #(str/includes? html %)
                    ["data-signals:progress__ifmissing="
                     "data-signals:volume__ifmissing="
                     "data-signals:_server_progress="
                     "data-bind=\"progress\""
                     "data-bind=\"volume\""])
            :server-pushed-progress
            (every? #(str/includes? html %)
                    ["data-init=\"@get("
                     "/api/player/progress-stream"
                     "retryMaxCount: Infinity"
                     "openWhenHidden: false"
                     "requestCancellation: &apos;cleanup&apos;"])
            :server-driven-time-updates
            (and (str/includes? html ">01:01</div>")
                 (not (str/includes? html
                                     "data-on-interval__duration.1s="))
                 (str/includes? html
                                "data-text=\"$_server_time\"")
                 (str/includes? html
                                "data-text=\"$_server_time_left\""))
            :legacy-command-removed (not (str/includes? html "/player-cmd"))
            :htmx-removed           (not (str/includes? html "hx-"))}))))

(deftest range-controls-handle-track-clicks-and-drags-without-rerender-overrides
  (reset! audio-system/audio-state (sample-state))
  (let [progress-input (get-in (player/progress-bar 0.25) [2 1])
        volume-input   (get-in (player/volume-bar 40) [1])
        progress-input-action
        (:data-on:input__throttle.100ms.trailing progress-input)
        volume-input-action
        (:data-on:input__throttle.100ms.trailing volume-input)
        html           (h/html->str ((render-fn) (player-request)))]
    (is (= {:progress-interaction
            {:data-bind           "progress"
             :data-on:pointerdown "$seeking = true"
             :data-on:pointerup__window
             "$_server_progress = $progress; $seeking = false"
             :data-on:pointercancel__window
             "$_server_progress = $progress; $seeking = false"
             :data-on:keydown
             (str "$seeking = evt.key.startsWith('Arrow') || "
                  "evt.key == 'Home' || evt.key == 'End' || "
                  "evt.key == 'PageUp' || evt.key == 'PageDown'")
             :data-on:keyup
             "$_server_progress = $progress; $seeking = false"
             :data-on:blur
             "$_server_progress = $progress; $seeking = false"}
            :volume-interaction
            {:data-bind           "volume"
             :data-on:pointerdown "$adjusting_volume = true"
             :data-on:pointerup__window
             "$_server_volume = $volume; $adjusting_volume = false"
             :data-on:pointercancel__window
             "$_server_volume = $volume; $adjusting_volume = false"
             :data-on:keydown
             (str "$adjusting_volume = evt.key.startsWith('Arrow') || "
                  "evt.key == 'Home' || evt.key == 'End' || "
                  "evt.key == 'PageUp' || evt.key == 'PageDown'")
             :data-on:keyup
             "$_server_volume = $volume; $adjusting_volume = false"
             :data-on:blur
             "$_server_volume = $volume; $adjusting_volume = false"}
            :progress-input-action     true
            :progress-final-action     true
            :volume-input-action       true
            :volume-final-action       true
            :volume-value-not-rendered true
            :interaction-signals       true
            :guarded-server-sync       true}
           {:progress-interaction
            (select-keys progress-input
                         [:data-bind
                          :data-on:pointerdown
                          :data-on:pointerup__window
                          :data-on:pointercancel__window
                          :data-on:keydown
                          :data-on:keyup
                          :data-on:blur])
            :volume-interaction
            (select-keys volume-input
                         [:data-bind
                          :data-on:pointerdown
                          :data-on:pointerup__window
                          :data-on:pointercancel__window
                          :data-on:keydown
                          :data-on:keyup
                          :data-on:blur])
            :progress-input-action
            (and (string? progress-input-action)
                 (str/includes? progress-input-action
                                (action-path 'seek-player)))
            :progress-final-action
            (let [expression (:data-on:change progress-input)]
              (and (str/starts-with?
                    expression
                    "$_server_progress = $progress; $seeking = false; ")
                   (str/includes? expression (action-path 'seek-player))))
            :volume-input-action
            (and (string? volume-input-action)
                 (str/includes? volume-input-action
                                (action-path 'set-player-volume)))
            :volume-final-action
            (let [expression (:data-on:change volume-input)]
              (and (str/starts-with?
                    expression
                    (str "$_server_volume = $volume; "
                         "$adjusting_volume = false; "))
                   (str/includes?
                    expression
                    (action-path 'set-player-volume))))
            :volume-value-not-rendered (not (contains? volume-input :value))
            :interaction-signals
            (every? #(str/includes? html %)
                    ["data-signals:seeking__ifmissing="
                     "data-signals:adjusting_volume__ifmissing="])
            :guarded-server-sync
            (and (str/includes? html
                                "$seeking ? $progress : $_server_progress")
                 (str/includes?
                  html
                  "$adjusting_volume ? $volume : $_server_volume"))}))))

(deftest renders-loaded-track-controls-for-non-playing-state
  (let [state (assoc-in (sample-state) [:playback :state] :stopped)
        html  (h/html->str (player/player
                            (assoc (player-request) :current state)))]
    (is (= {:track-visible          true
            :play-control-visible   true
            :nothing-playing-hidden true}
           {:track-visible        (str/includes? html "Track title")
            :play-control-visible (str/includes? html "id=\"play-pause\"")
            :nothing-playing-hidden
            (not (str/includes? html "Nothing is playing."))}))))

(deftest exposes-current-track-and-playback-state
  (let [state (sample-state)]
    (is (= {:artist      "Track artist"
            :mrl         "file:///music/track.mp3"
            :repeat-mode :track
            :shuffle?    true}
           {:artist      (current/artist state)
            :mrl         (current/mrl state)
            :repeat-mode (current/repeat-mode state)
            :shuffle?    (current/shuffle? state)}))))

(deftest emits-button-commands-through-injected-switchboard
  (let [commands  (async/chan 16)
        action    (action-fn 'control-player)
        component {:fairy.box.switchboard/switchboard {:emitter commands}}
        requested ["play-pause" "previous" "next" "skip-back"
                   "skip-forward" "volume-down-step" "volume-up-step"
                   "toggle-mute"]]
    (try
      (when action
        (doseq [command requested]
          (action {:query-params        {"command" command}
                   :fairy.box/component component})))
      (is (= [{:path  "/player/commands"
               :value {:action :audio/play-pause}}
              {:path  "/player/commands"
               :value {:action :audio/prev}}
              {:path  "/player/commands"
               :value {:action :audio/next}}
              {:path  "/player/commands"
               :value {:action       :audio/skip-time
                       :milliseconds -10000}}
              {:path  "/player/commands"
               :value {:action       :audio/skip-time
                       :milliseconds 10000}}
              {:path  "/player/commands"
               :value {:action :audio/adjust-volume
                       :delta  -5}}
              {:path  "/player/commands"
               :value {:action :audio/adjust-volume
                       :delta  5}}
              {:path  "/player/commands"
               :value {:action :audio/toggle-mute}}]
             (take-values commands (count requested))))
      (finally
        (async/close! commands)))))

(deftest emits-validated-seek-volume-repeat-and-shuffle-commands
  (let [commands  (async/chan 8)
        component {:fairy.box.switchboard/switchboard {:emitter commands}}
        requests  [['seek-player "position" "25.5"]
                   ['set-player-volume "volume" "75"]
                   ['set-player-repeat "mode" "list"]
                   ['set-player-shuffle "shuffle" "true"]]]
    (try
      (doseq [[action-name parameter value] requests]
        (when-let [action (action-fn action-name)]
          (action {:query-params        {parameter value}
                   :fairy.box/component component})))
      (is (= [{:path  "/player/commands"
               :value {:action   :audio/set-position
                       :position 0.255}}
              {:path  "/player/commands"
               :value {:action :audio/set-volume
                       :volume 75}}
              {:path  "/player/commands"
               :value {:action :audio/set-repeat
                       :mode   :list}}
              {:path  "/player/commands"
               :value {:action   :audio/set-shuffle
                       :shuffle? true}}]
             (take-values commands (count requests))))
      (finally
        (async/close! commands)))))

(deftest rejects-malformed-and-out-of-range-action-input
  (let [commands  (async/chan 32)
        component {:fairy.box.switchboard/switchboard {:emitter commands}}
        actions   (mapv action-fn
                        '[control-player seek-player set-player-volume
                          set-player-repeat set-player-shuffle])]
    (try
      (when-let [action (first actions)]
        (doseq [command [nil "" "pause" "PLAY" 1]]
          (action {:query-params        {"command" command}
                   :fairy.box/component component})))
      (when-let [action (second actions)]
        (doseq [position [nil "" "wat" "NaN" "Infinity" "-1"
                          "100.1" " 20" 20]]
          (action {:query-params        {"position" position}
                   :fairy.box/component component})))
      (when-let [action (nth actions 2)]
        (doseq [volume [nil "" "1.5" "-1" "101" " 20" 20]]
          (action {:query-params        {"volume" volume}
                   :fairy.box/component component})))
      (when-let [action (nth actions 3)]
        (doseq [mode [nil "" "all" "repeat-one" :track]]
          (action {:query-params        {"mode" mode}
                   :fairy.box/component component})))
      (when-let [action (nth actions 4)]
        (doseq [shuffle [nil "" "yes" "TRUE" true]]
          (action {:query-params        {"shuffle" shuffle}
                   :fairy.box/component component})))
      (let [[command port] (async/alts!! [commands (async/timeout 100)])]
        (is (= {:actions-present? true
                :command          nil
                :emitted?         false}
               {:actions-present? (every? ifn? actions)
                :command          command
                :emitted?         (= port commands)})))
      (finally
        (async/close! commands)))))

(deftest changes-artwork-url-when-the-current-track-changes
  (let [state       (sample-state)
        next-state  (assoc-in state
                              [:playback :current-track :mrl]
                              "file:///music/next-track.mp3")
        artwork-src #(get-in (player/current-artwork %) [1 :src])]
    (is (= {:current-url-versioned?    true
            :track-change-updates-url? true}
           {:current-url-versioned?
            (str/starts-with? (artwork-src state)
                              "/api/current-artwork?v=")
            :track-change-updates-url?
            (not= (artwork-src state) (artwork-src next-state))}))))

(deftest resolves-artwork-from-current-hyperlith-player-state
  (fs/with-temp-dir [temp-dir]
    (let [image-path (fs/path temp-dir "cover.png")]
      (spit (str image-path) "png")
      (reset! audio-system/audio-state
              (assoc-in (sample-state)
                        [:playback :current-track :meta :meta/artwork-url]
                        (str (.toUri image-path))))
      (is (= (str image-path)
             (some-> (artwork/actual-artwork) first str))))))

(deftest registers-current-artwork-with-hyperlith-router
  (let [handler  (get-in @router/routes_ [:get "/api/current-artwork"])
        response (when handler (handler {}))]
    (is (= {:handler?     true
            :status       200
            :content-type "image/png"}
           {:handler?     (ifn? handler)
            :status       (:status response)
            :content-type (get-in response [:headers "Content-Type"])}))))
