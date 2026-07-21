(ns fairy.box.web.views.settings-tts-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [fairy.box.db :as db]
   [fairy.box.tts :as tts]
   [fairy.box.web.views.settings-tts :as view]
   [hyperlith.core :as h]))

(def catalog-snapshot
  {:providers
   {:google-cloud
    {:catalog {:languages ["de-DE" "en-US"]
               :voices    [{:id "de-DE-Standard-A" :language-codes ["de-DE"]}
                           {:id "de-DE-Wavenet-A" :language-codes ["de-DE"]}
                           {:id "en-US-Polyglot-1" :language-codes ["en-US"]}
                           {:id "en-US-Standard-A" :language-codes ["en-US"]}
                           {:id "en-US-Standard-B" :language-codes ["en-US"]}
                           {:id "en-US-Wavenet-A" :language-codes ["en-US"]}]}
     :source  :remote}
    :elevenlabs
    {:catalog    {:models [{:id "eleven_multilingual_v2"
                            :name                   "Eleven Multilingual v2"
                            :can-use-style?         false
                            :can-use-speaker-boost? false}]
                  :voices [{:id "voice-test" :name "Test Voice"}]}
     :source     :built-in
     :last-error {:kind    :unauthorized
                  :message "Provider rejected the catalog request."}}}})

(defn- request
  ([database]
   (request database {:db-conn database}))
  ([database tts-system]
   {:fairy.box/component
    (fn [key]
      (case key
        :fairy.box.db/db database
        :fairy.box.tts/tts tts-system
        nil))
    :url-for             {:page/home     "/"
                          :page/queue    "/queue"
                          :page/settings "/settings"}}))

(defn- action [symbol]
  (or (ns-resolve 'fairy.box.web.views.settings-tts symbol)
      (throw (ex-info "TTS settings action not found" {:symbol symbol}))))

(deftest renders-dedicated-live-save-page-without-secrets
  (let [database (atom
                  (-> (db/migrate-db {:settings {}})
                      (assoc-in [:settings :tts :providers :google-cloud
                                 :api-key]
                                "view-secret-probe")
                      (assoc-in [:settings :tts :providers :elevenlabs
                                 :voice-id]
                                "voice-test")
                      (assoc-in [:settings :tts :providers :elevenlabs
                                 :output-format]
                                "unsupported-format")))
        req      (request database {:db @database})]
    (with-redefs [tts/ensure-provider-catalogs! (constantly nil)
                  tts/provider-catalog-snapshot (constantly catalog-snapshot)
                  tts/credential-status
                  (fn [_ provider]
                    (case provider
                      :google-cloud {:configured? true :source :database}
                      :openai {:configured? true :source :key-file}
                      {:configured? false :source nil}))]
      (let [render-page (action 'render-tts-fn)
            html        (h/html->str (render-page req))]
        (is (= {:dedicated-route               "/settings/tts"
                :providers true
                :provider-visibility-bindings  5
                :live-save true
                :track-announcement-control    true
                :passwords 3
                :password-autocomplete         3
                :credential-clearing-explained true
                :preview-default-fairybox      true
                :external-key-placeholder      true
                :speed-sliders                 2
                :numeric-speed-inputs-absent   true
                :slider-values                 5
                :field-hints                   true
                :catalog-source-preserved      true
                :fallback-catalog-explained    true
                :browser-audio-loader          true
                :preview-button                true
                :save-button-absent            true
                :secret-absent                 true
                :provider-controls             true
                :unsupported-controls-disabled 2
                :unsupported-output-warning    true
                :ordinary-back-link            true}
               {:dedicated-route    view/render-tts
                :providers          (every? #(str/includes? html %)
                                            ["Google Cloud" "OpenAI" "ElevenLabs"])
                :provider-visibility-bindings
                (count (re-seq #"data-show=\"\$tts_engine" html))
                :live-save          (str/includes? html "data-on:change")
                :track-announcement-control
                (and (str/includes? html "Announce tracks before playback")
                     (str/includes? html "tts_announce_tracks")
                     (< (str/index-of html "Track announcements")
                        (str/index-of html "Active provider")))
                :passwords          (count (re-seq #"type=\"password\"" html))
                :password-autocomplete
                (count (re-seq #"autocomplete=\"new-password\"" html))
                :credential-clearing-explained
                (str/includes? html
                               "This field clears after every save")
                :preview-default-fairybox
                (str/includes? html
                               "value=\"fairybox\" selected")
                :external-key-placeholder
                (str/includes? html "~/.llm-keys key active")
                :speed-sliders
                (count
                 (filter #(and (str/includes? % "type=\"range\"")
                               (or (str/includes? % "name=\"openai-speed\"")
                                   (str/includes? % "name=\"elevenlabs-speed\"")))
                         (re-seq #"<input[^>]+>" html)))
                :numeric-speed-inputs-absent
                (not-any? #(and (str/includes? % "type=\"number\"")
                                (str/includes? % "speed"))
                          (re-seq #"<input[^>]+>" html))
                :slider-values      (count (re-seq #"<output[^>]+>" html))
                :field-hints
                (every? #(str/includes? html %)
                        ["Speech rate from 0.25× to 4×"
                         "Lower values add variation"
                         "Higher values follow the original voice more closely"])
                :catalog-source-preserved
                (str/includes? html "Provider catalog is current.")
                :fallback-catalog-explained
                (str/includes? html "Showing built-in fallback options.")
                :browser-audio-loader
                (and (str/includes? html "FileReader")
                     (str/includes? html "tts_preview_seq")
                     (str/includes? html "preload=\"metadata\"")
                     (not (str/includes? html "data-attr:src")))
                :preview-button     (str/includes? html ">Preview</button>")
                :save-button-absent (not (str/includes? html ">Save</span>"))
                :secret-absent      (not (str/includes? html "view-secret-probe"))
                :provider-controls
                (every? #(str/includes? html %)
                        ["google_language_code" "google_family" "openai_instructions"
                         "openai_speed" "elevenlabs_output_format"
                         "elevenlabs_speed"])
                :unsupported-controls-disabled
                (count (filter #(and (or (str/includes? % "name=\"elevenlabs-style\"")
                                         (str/includes? % "name=\"elevenlabs-speaker-boost\""))
                                     (str/includes? % "disabled"))
                               (re-seq #"<input[^>]+>" html)))
                :unsupported-output-warning
                (str/includes? html
                               "saved output format is unsupported")
                :ordinary-back-link
                (str/includes? html "href=\"/settings\"")}))))))

(deftest renders-and-clears-audio-cache-with-human-readable-size
  (fs/with-temp-dir [cache-dir {:prefix "fairybox-settings-audio-cache-"}]
    (let [database   (atom (db/migrate-db {:settings {}}))
          tts-system {:db-conn       database
                      :tts-cache-dir (str cache-dir)}
          req        (request database tts-system)
          clear!     (action 'clear-tts-audio-cache-fn)]
      (fs/write-bytes (fs/path cache-dir (tts/hash-text :normal))
                      (byte-array 1024))
      (fs/write-bytes (fs/path cache-dir
                               (tts/hash-text :preview tts/preview-cache-suffix))
                      (byte-array 512))
      (spit (fs/file cache-dir "provider-catalogs.edn") "catalog")
      (with-redefs [tts/ensure-provider-catalogs! (constantly nil)
                    tts/provider-catalog-snapshot (constantly catalog-snapshot)
                    tts/credential-status
                    (fn [_ _] {:configured? false :source nil})]
        (let [page          (h/html->str (view/tts-settings req))
              response      (h/html->str (clear! req))
              remaining     (->> (fs/list-dir cache-dir)
                                 (map fs/file-name)
                                 set)
              response-data (str/includes?
                             response
                             "0 cached audio files \\u00b7 0 bytes")]
          (is (= {:card-rendered?       true
                  :human-readable-size? true
                  :clear-action?        true
                  :dynamic-disabled?    true
                  :cleared-signals?     true
                  :clear-status?        true
                  :cache-stats          {:file-count 0 :total-bytes 0}
                  :remaining            #{"provider-catalogs.edn"}}
                 {:card-rendered?       (str/includes? page "id=\"tts-audio-cache\"")
                  :human-readable-size? (str/includes?
                                         page
                                         "2 cached audio files · 1.5 KB")
                  :clear-action?        (str/includes? page ">Clear audio cache</button>")
                  :dynamic-disabled?    (str/includes?
                                         page
                                         "data-attr:disabled=\"$_tts_cache_file_count === 0\"")
                  :cleared-signals?     response-data
                  :clear-status?        (str/includes?
                                         response
                                         "Cleared 2 cached audio files.")
                  :cache-stats          (tts/audio-cache-stats tts-system)
                  :remaining            remaining})))))))

(deftest renders-refresh-started-event-as-transient-status
  (let [database          (atom (db/migrate-db {:settings {}}))
        tts-system        {:db-conn database}
        current-event_    (atom {:path  "/tts/events"
                                 :value {:event    :tts/catalog-refresh-started
                                         :provider :google-cloud}})
        refresh-component {:current-event_ current-event_}
        req               (assoc
                           (request database tts-system)
                           :fairy.box/component
                           (fn [key]
                             (case key
                               :fairy.box.db/db database
                               :fairy.box.tts/tts tts-system
                               :fairy.box.web/refresh refresh-component)))]
    (with-redefs [tts/ensure-provider-catalogs! (constantly nil)
                  tts/provider-catalog-snapshot (constantly catalog-snapshot)
                  tts/credential-status
                  (fn [_ _] {:configured? true :source :database})]
      (let [during-refresh (h/html->str (view/tts-settings req))
            _              (reset! current-event_ nil)
            after-refresh  (h/html->str (view/tts-settings req))]
        (is (= {:during-refresh true
                :after-refresh  false}
               {:during-refresh
                (str/includes? during-refresh
                               "Refreshing provider catalog…")
                :after-refresh
                (str/includes? after-refresh
                               "Refreshing provider catalog…")}))))))

(deftest actions-allowlist-and-persist-one-typed-setting
  (let [database     (atom (assoc (db/migrate-db {:settings {}})
                                  :unrelated :kept))
        req          (request database {:db-conn       database
                                        :catalog-store ::catalog-store})
        save-engine  (action 'save-tts-engine-fn)
        save-setting (action 'save-tts-provider-setting-fn)
        save-target  (action 'save-tts-preview-target-fn)]
    (with-redefs [h/refresh-all!                (constantly nil)
                  tts/provider-catalog-snapshot (constantly catalog-snapshot)]
      (save-engine (assoc req :body {:tts_engine "openai"}))
      (save-engine (assoc req :body {:tts_engine "invalid"}))
      (save-target (assoc req :body {:tts_preview_target "both"}))
      (save-target (assoc req :body {:tts_preview_target "invalid"}))
      (save-setting (assoc req
                           :query-params {"provider" "openai"
                                          "field"    "model"}
                           :body {:openai_model "tts-1-hd"
                                  :untrusted    "ignored"}))
      (save-setting (assoc req
                           :query-params {"provider" "openai"
                                          "field"    "speed"}
                           :body {:openai_speed 3.25}))
      (save-setting (assoc req
                           :query-params {"provider" "openai"
                                          "field"    "speed"}
                           :body {:openai_speed 9.0}))
      (save-setting (assoc req
                           :query-params {"provider" "elevenlabs"
                                          "field"    "speed"}
                           :body {:elevenlabs_speed 1.1}))
      (save-setting (assoc req
                           :query-params {"provider" "elevenlabs"
                                          "field"    "speed"}
                           :body {:elevenlabs_speed 9.0}))
      (save-setting (assoc req
                           :query-params {"provider" "google-cloud"
                                          "field"    "language-code"}
                           :body {:google_language_code "de-DE"}))
      (save-setting (assoc req
                           :query-params {"provider" "openai"
                                          "field"    "not-allowed"}
                           :body {:openai_model "gpt-4o-mini-tts"}))
      (is (= {:engine            :openai
              :preview-target    :both
              :model             "tts-1-hd"
              :voice             "alloy"
              :speed             1.1
              :openai-speed      3.25
              :google-language   "de-DE"
              :google-voice      "de-DE-Standard-A"
              :untrusted-absent? true
              :unrelated         :kept}
             {:engine          (db/tts-engine @database)
              :preview-target  (db/tts-preview-target @database)
              :model           (get-in @database
                                       [:settings :tts :providers :openai :model])
              :voice           (get-in @database
                                       [:settings :tts :providers :openai :voice])
              :openai-speed    (get-in @database
                                       [:settings :tts :providers :openai :speed])
              :speed           (get-in @database
                                       [:settings :tts :providers :elevenlabs
                                        :voice-settings :speed])
              :google-language (get-in @database
                                       [:settings :tts :providers :google-cloud
                                        :language-code])
              :google-voice    (get-in @database
                                       [:settings :tts :providers :google-cloud
                                        :voice])
              :untrusted-absent?
              (not (contains? (get-in @database
                                      [:settings :tts :providers :openai])
                              :untrusted))
              :unrelated       (:unrelated @database)})))))

(deftest renders-new-google-options-after-live-save
  (let [database     (atom (db/migrate-db {:settings {}}))
        tts-system   {:db-conn       database
                      :catalog-store ::catalog-store}
        req          (request database tts-system)
        save-setting (action 'save-tts-provider-setting-fn)]
    (with-redefs [tts/ensure-provider-catalogs! (constantly nil)
                  tts/provider-catalog-snapshot (constantly catalog-snapshot)
                  tts/credential-status
                  (fn [_ _] {:configured? true :source :database})]
      (let [initial-html
            (h/html->str (view/tts-settings req))
            family-response
            (h/html->str
             (save-setting
              (assoc req
                     :query-params {"provider" "google-cloud"
                                    "field"    "family"}
                     :body {:google_family "Standard"})))
            family-html
            (h/html->str (view/tts-settings req))
            language-response
            (h/html->str
             (save-setting
              (assoc req
                     :query-params {"provider" "google-cloud"
                                    "field"    "language-code"}
                     :body {:google_language_code "de-DE"})))
            language-html
            (h/html->str (view/tts-settings req))]
        (is (= {:initial-polyglot?         true
                :family-signal?            true
                :family-selected?          true
                :family-voices-replaced?   true
                :language-signal?          true
                :language-selected?        true
                :language-voices-replaced? true}
               {:initial-polyglot?
                (str/includes? initial-html "en-US-Polyglot-1")
                :family-signal?
                (str/includes? family-response "en-US-Standard-A")
                :family-selected?
                (str/includes? family-html "value=\"Standard\" selected")
                :family-voices-replaced?
                (and (str/includes? family-html "en-US-Standard-A")
                     (str/includes? family-html "en-US-Standard-B")
                     (not (str/includes? family-html "en-US-Wavenet-A"))
                     (not (str/includes? family-html "en-US-Polyglot-1")))
                :language-signal?
                (str/includes? language-response "de-DE-Standard-A")
                :language-selected?
                (str/includes? language-html "value=\"de-DE\" selected")
                :language-voices-replaced?
                (and (str/includes? language-html "de-DE-Standard-A")
                     (not (str/includes? language-html "de-DE-Wavenet-A"))
                     (not (str/includes? language-html "en-US-Standard-A")))}))))))

(deftest saves-only-boolean-track-announcement-values
  (let [database (atom (db/migrate-db {:settings {}}))
        req      (request database)
        save!    (action 'save-track-announcements-fn)]
    (with-redefs [h/refresh-all! (constantly nil)]
      (save! (assoc req :body {:tts_announce_tracks true}))
      (let [after-enable (db/announce-tracks? @database)]
        (save! (assoc req :body {:tts_announce_tracks "false"}))
        (let [after-invalid (db/announce-tracks? @database)]
          (save! (assoc req :body {:tts_announce_tracks false}))
          (is (= {:after-enable  true
                  :after-invalid true
                  :after-disable false}
                 {:after-enable  after-enable
                  :after-invalid after-invalid
                  :after-disable (db/announce-tracks? @database)})))))))

(deftest replaces-clears-and-redacts-credentials
  (let [database       (atom (db/migrate-db {:settings {}}))
        invalidations_ (atom [])
        req            (request database)
        replace!       (action 'replace-tts-credential-fn)
        clear!         (action 'clear-tts-credential-fn)]
    (with-redefs [h/refresh-all! (constantly nil)
                  tts/invalidate-provider-catalog!
                  (fn [_ provider] (swap! invalidations_ conj provider))]
      (let [response      (replace!
                           (assoc req
                                  :query-params {"provider" "google-cloud"}
                                  :body {:google_api_key "action-secret-probe"
                                         :openai_api_key "ignored-secret-probe"}))
            response-html (h/html->str response)
            stored?       (= "action-secret-probe"
                             (get-in @database
                                     [:settings :tts :providers :google-cloud
                                      :api-key]))]
        (clear! (assoc req :query-params {"provider" "google-cloud"}))
        (is (= {:stored-before-clear?     true
                :cleared?                 true
                :response-redacted?       true
                :password-signal-cleared? true
                :invalidations            [:google-cloud :google-cloud]}
               {:stored-before-clear? stored?
                :cleared?             (not (contains?
                                            (get-in @database
                                                    [:settings :tts :providers
                                                     :google-cloud])
                                            :api-key))
                :response-redacted?
                (and (not (str/includes? response-html "action-secret-probe"))
                     (not (str/includes? response-html "ignored-secret-probe")))
                :password-signal-cleared?
                (str/includes? response-html "google_api_key")
                :invalidations        @invalidations_}))))))

(deftest preview-is-explicit-and-returns-only-safe-browser-url
  (let [database   (atom (assoc-in (db/migrate-db {:settings {}})
                                   [:settings :tts :engine]
                                   :openai))
        syntheses_ (atom [])
        emissions_ (atom [])
        req        (request database)
        preview!   (action 'preview-tts-fn)]
    (with-redefs [tts/browser-preview-tts
                  (fn [_ text]
                    (swap! syntheses_ conj [:browser text])
                    {:path     "/private/cache/browser.preview.opus.tts-cache"
                     :basename "YWJj.preview.opus.tts-cache"})
                  tts/tts
                  (fn [_ text]
                    (swap! syntheses_ conj [:fairybox text])
                    "/private/cache/normal.tts-cache")
                  tts/emit-player!
                  (fn [_ event] (swap! emissions_ conj event))]
      (let [before      @syntheses_
            browser     (h/html->str
                         (preview! (assoc req :body {:tts_preview_target "browser"
                                                     :tts_preview_text   "Hello"})))
            fairybox    (h/html->str
                         (preview! (assoc req :body {:tts_preview_target "fairybox"
                                                     :tts_preview_text   "Hello"})))
            before-both (count @syntheses_)
            both        (h/html->str
                         (preview! (assoc req :body {:tts_preview_target "both"
                                                     :tts_preview_text   "Hello"})))
            after-both  (count @syntheses_)
            call-count  after-both
            invalid     (h/html->str
                         (preview! (assoc req :body {:tts_preview_target "invalid"
                                                     :tts_preview_text   "Hello"})))
            sequence    #(second (re-find #"tts_preview_seq&quot;:(\d+)" %))]
        (is (= {:before                   []
                :calls                    [[:browser "Hello"]
                                           [:fairybox "Hello"]
                                           [:browser "Hello"]]
                :emitted-paths            ["/private/cache/normal.tts-cache"
                                           "/private/cache/browser.preview.opus.tts-cache"]
                :both-one-synthesis?      true
                :replay-sequences-change? true
                :browser-route?           true
                :paths-hidden?            true
                :invalid-made-no-call?    true
                :invalid-safe?            true}
               {:before                before
                :calls                 @syntheses_
                :emitted-paths         (mapv :item-path @emissions_)
                :both-one-synthesis?   (= (inc before-both) after-both)
                :replay-sequences-change?
                (and (sequence browser)
                     (sequence both)
                     (not= (sequence browser) (sequence both)))
                :browser-route?
                (str/includes? browser "preview-audio?file=YWJj.preview.opus.tts-cache")
                :paths-hidden?
                (every? #(not (str/includes? % "/private/cache"))
                        [browser fairybox both])
                :invalid-made-no-call? (= call-count (count @syntheses_))
                :invalid-safe?         (str/includes? invalid
                                                      "Choose a configured cloud provider")}))))))

(deftest preview-route-rejects-invalid-and-hides-cache-paths
  (fs/with-temp-dir [cache-dir {:prefix "fairybox-preview-route-test-"}]
    (let [name       (tts/hash-text :route tts/preview-cache-suffix)
          file       (fs/file cache-dir name)
          tts-system {:tts-cache-dir (str cache-dir)}
          component  (fn [key]
                       (when (= key :fairy.box.tts/tts) tts-system))
          call       (fn [filename]
                       (view/tts-preview-audio
                        {:fairy.box/component component
                         :query-params        {"file" filename}}))]
      (spit file "opus-bytes")
      (let [ok        (call name)
            content   (with-open [^java.io.InputStream body (:body ok)]
                        (slurp body))
            missing   (call (tts/hash-text :missing tts/preview-cache-suffix))
            traversal (call "../x.preview.opus.tts-cache")
            normal    (call (tts/hash-text :normal))]
        (is (= {:ok-status        200
                :content-type     "audio/ogg"
                :disposition      "inline; filename=\"preview.opus\""
                :nosniff          "nosniff"
                :cache-control    "private, no-store"
                :content          "opus-bytes"
                :missing-status   404
                :traversal-status 400
                :normal-status    400
                :paths-hidden?    true}
               {:ok-status        (:status ok)
                :content-type     (get-in ok [:headers "Content-Type"])
                :disposition      (get-in ok [:headers "Content-Disposition"])
                :nosniff          (get-in ok [:headers "X-Content-Type-Options"])
                :cache-control    (get-in ok [:headers "Cache-Control"])
                :content          content
                :missing-status   (:status missing)
                :traversal-status (:status traversal)
                :normal-status    (:status normal)
                :paths-hidden?
                (not-any? #(str/includes? (str %) (str cache-dir))
                          [missing traversal normal])}))))))
