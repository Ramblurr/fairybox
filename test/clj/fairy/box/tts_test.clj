;; Copyright © 2025 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2
(ns fairy.box.tts-test
  (:require
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [clojure.tools.logging.test :as log-test]
   [exoscale.cloak :as cloak]
   [fairy.box.db :as db]
   [fairy.box.tts :as tts]
   [fairy.box.tts.speech :as speech]
   [fairy.box.util :as util]
   [hato.client :as hc])
  (:import
   [java.time Duration]))

(defn- private-var [symbol]
  (or (ns-resolve 'fairy.box.tts symbol)
      (throw (ex-info "TTS private var not found" {:symbol symbol}))))

(deftest creates-semantic-metadata-speech-plans
  (let [one-title      (tts/metadata->speech [{:title "Introduction"}])
        shared-album   (tts/metadata->speech [{:album "Stories"
                                               :title "Introduction"}
                                              {:album "Stories"
                                               :title "Tomorrow"}])
        mixed-albums   (tts/metadata->speech [{:album "Stories"
                                               :title "Introduction"}
                                              {:album "Other"
                                               :title "Tomorrow"}])
        special        (tts/metadata->speech [{:album "2 < 3 & 5 > 4"
                                               :title "A < B & C"}])
        indexed-track  (tts/tts-track-speech {:title "Introduction"}
                                             {:index 0})
        numbered-track (tts/tts-track-speech
                        {:album        "Stories"
                         :artist       "Arnold Lobel"
                         :title        "Introduction"
                         :track-number 7}
                        {:index        0
                         :with-album?  true
                         :with-artist? true})
        unnumbered     (tts/tts-track-speech {:title "Introduction"} {})]
    (is (= {:one-title
            (speech/plan [(speech/text "This one has ")
                          (speech/pause 1000)
                          (speech/text "\"Introduction\"")])
            :shared-album
            (speech/plan [(speech/text "This one is \"Stories\"")
                          (speech/pause 1000)
                          (speech/text "1, ")
                          (speech/pause 500)
                          (speech/text "\"Introduction\"")
                          (speech/pause 500)
                          (speech/text " and 2, ")
                          (speech/pause 500)
                          (speech/text "\"Tomorrow\"")])
            :mixed-albums
            (speech/plan [(speech/text "This one has ")
                          (speech/pause 1000)
                          (speech/text "1, ")
                          (speech/pause 500)
                          (speech/text "\"Introduction\"")
                          (speech/pause 500)
                          (speech/text " and 2, ")
                          (speech/pause 500)
                          (speech/text "\"Tomorrow\"")])
            :special
            (speech/plan [(speech/text
                           "This one is \"2 < 3 & 5 > 4\"")
                          (speech/pause 1000)
                          (speech/text "\"A < B & C\"")])
            :indexed-track
            (speech/plan
             [(speech/text "Number 1, \"Introduction\"")])
            :numbered-track
            (speech/plan
             [(speech/text "Number 7, \"Introduction\"")
              (speech/text " by Arnold Lobel")
              (speech/text " from the album \"Stories\"")])
            :unnumbered
            (speech/plan [(speech/text "\"Introduction\"")])}
           {:one-title      one-title
            :shared-album   shared-album
            :mixed-albums   mixed-albums
            :special        special
            :indexed-track  indexed-track
            :numbered-track numbered-track
            :unnumbered     unnumbered}))))

(deftest reads-openai-key-from-development-key-file
  (fs/with-temp-dir [temp-dir {:prefix "fairybox-openai-key-"}]
    (spit (str (fs/path temp-dir ".llm-keys"))
          (str "ANTHROPIC_API_KEY=not-the-key\n"
               "export OPENAI_API_KEY='test-openai-key'\n"))
    (let [original-home (System/getProperty "user.home")]
      (try
        (System/setProperty "user.home" (str temp-dir))
        (is (= "test-openai-key"
               ((private-var 'openai-api-key-from-file))))
        (finally
          (System/setProperty "user.home" original-home))))))

(deftest creates-openai-speech-request
  (let [requests_ (atom [])
        responses (with-redefs-fn
                    {(private-var 'openai-api-key) (constantly "test-api-key")
                     #'hc/post
                     (fn [url opts]
                       (swap! requests_ conj
                              {:url           url
                               :body          (util/<-json (:body opts))
                               :as            (:as opts)
                               :content-type  (:content-type opts)
                               :authorization (get-in opts [:headers "Authorization"])})
                       {:body ::audio-stream})}
                    #(vector
                      (tts/openai-tts
                       {:prepared-input
                        (speech/prepared-input "Default" :openai {})})
                      (tts/openai-tts
                       {:model          "test-model"
                        :voice          "onyx"
                        :instructions   "Speak slowly."
                        :speed          1.5
                        :prepared-input (speech/prepared-input
                                         "Custom" :openai {})})))]
    (is (= {:requests
            [{:url           "https://api.openai.com/v1/audio/speech"
              :body          {:model           "gpt-4o-mini-tts"
                              :input           "Default"
                              :voice           "marin"
                              :speed           1.0
                              :instructions    "Speak naturally."
                              :response_format "mp3"}
              :as            :stream
              :content-type  :json
              :authorization "Bearer test-api-key"}
             {:url           "https://api.openai.com/v1/audio/speech"
              :body          {:model           "gpt-4o-mini-tts"
                              :input           "Custom"
                              :voice           "onyx"
                              :speed           1.5
                              :instructions    "Speak slowly."
                              :response_format "mp3"}
              :as            :stream
              :content-type  :json
              :authorization "Bearer test-api-key"}]
            :responses [::audio-stream ::audio-stream]}
           {:requests  @requests_
            :responses responses}))))

(deftest selects-google-input-field-from-prepared-speech
  (let [requests_ (atom [])
        plan      (speech/plan [(speech/text "Hello <friend>")
                                (speech/pause 1000)
                                (speech/text "again")])
        responses
        (with-redefs [hc/post
                      (fn [url opts]
                        (swap! requests_ conj
                               {:url          url
                                :body         (util/<-json (:body opts))
                                :content-type (:content-type opts)
                                :api-key
                                (get-in opts [:headers "X-Goog-Api-Key"])})
                        {:body (util/->json {:audioContent "YQ=="})})]
          [(tts/google-cloud-tts
            {:credential     (cloak/mask "test-api-key")
             :language-code  "en-US"
             :voice          "en-US-Standard-A"
             :prepared-input (speech/prepared-input
                              plan
                              :google-cloud
                              {:voice "en-US-Standard-A"})})
           (tts/google-cloud-tts
            {:credential     (cloak/mask "test-api-key")
             :language-code  "en-US"
             :voice          "en-US-Chirp3-HD-Algieba"
             :prepared-input (speech/prepared-input
                              plan
                              :google-cloud
                              {:voice "en-US-Chirp3-HD-Algieba"})})])]
    (is (= [{:url          "https://texttospeech.googleapis.com/v1/text:synthesize"
             :body         {:input       {:ssml
                                          "<speak>Hello &lt;friend&gt;<break time=\"1000ms\"/>again</speak>"}
                            :voice       {:languageCode "en-US"
                                          :name         "en-US-Standard-A"}
                            :audioConfig {:audioEncoding "MP3"}}
             :content-type :json
             :api-key      "test-api-key"}
            {:url          "https://texttospeech.googleapis.com/v1/text:synthesize"
             :body         {:input       {:text "Hello <friend>...\nagain"}
                            :voice       {:languageCode "en-US"
                                          :name         "en-US-Chirp3-HD-Algieba"}
                            :audioConfig {:audioEncoding "MP3"}}
             :content-type :json
             :api-key      "test-api-key"}]
           @requests_))
    (is (every? #(instance? java.io.InputStream %) responses))))

(deftest sends-generated-ssml-to-mimic3
  (let [request_ (atom nil)
        plan     (speech/plan [(speech/text "Hello <friend>")
                               (speech/pause 1000)
                               (speech/text "again")])
        response
        (with-redefs [hc/get
                      (fn [url opts]
                        (reset! request_
                                {:url          url
                                 :query-params (:query-params opts)
                                 :as           (:as opts)})
                        {:body ::audio-stream})]
          (tts/mimic3-tts
           {:prepared-input (speech/prepared-input plan :mimic3 {})}))]
    (is (= {:request
            {:url "http://10.9.4.3:59125/api/tts"
             :query-params
             {"text"
              "<speak>Hello &lt;friend&gt;<break time=\"1000ms\"/>again</speak>"
              "voice"       "en_US/cmu-arctic_low#clb"
              "noiseScale"  "0.677"
              "noiseW"      "0.8"
              "ssml"        "true"
              "audioTarget" "client"}
             :as  :stream}
            :response ::audio-stream}
           {:request  @request_
            :response response}))))

(deftest dispatches-openai-engine-to-existing-cache
  (fs/with-temp-dir [cache-dir {:prefix "fairybox-openai-cache-"}]
    (let [text       "Already synthesized"
          database   {:settings {:tts {:engine :openai}}}
          sys        {:db-conn       (atom database)
                      :tts-cache-dir (str cache-dir)}
          options    (:options (tts/effective-provider-config
                                {:db database} :openai :normal))
          prepared   (speech/prepared-input text :openai options)
          cache-key  [:fairy.box.tts/openai
                      options
                      (speech/cache-identity prepared)]
          cache-file (io/file (str cache-dir) (tts/hash-text cache-key))]
      (spit cache-file "cached audio")
      (with-redefs [tts/openai-tts
                    (fn [_]
                      (throw (ex-info "Cache miss unexpectedly reached OpenAI" {})))]
        (is (= (.getAbsolutePath cache-file)
               (tts/tts sys text)))))))

(deftest logs-readable-tts-cache-outcomes
  (fs/with-temp-dir [cache-dir {:prefix "fairybox-tts-log-"}]
    (let [input  (speech/plan [(speech/text "Hello")
                               (speech/pause 1000)
                               (speech/text "again")])
          system {:db-conn       (atom {:settings {:tts {:engine :openai}}})
                  :tts-cache-dir (str cache-dir)}]
      (log-test/with-log
        (with-redefs [tts/openai-tts
                      (fn [_]
                        (java.io.ByteArrayInputStream.
                         (.getBytes "audio")))]
          (tts/tts system input)
          (tts/tts system input))
        (is (= [{:level :info
                 :message
                 (str "TTS audio ready {:provider :openai, :mode :normal, "
                      ":source :synthesized, :text Hello... again}")}
                {:level :info
                 :message
                 (str "TTS audio ready {:provider :openai, :mode :normal, "
                      ":source :cache, :text Hello... again}")}]
               (mapv #(select-keys % [:level :message])
                     (log-test/the-log))))))))

(deftest renderer-identity-misses-legacy-openai-cache-entry
  (fs/with-temp-dir [cache-dir {:prefix "fairybox-openai-legacy-cache-"}]
    (let [input       "<speak>Hello</speak>"
          database    {:settings {:tts {:engine :openai}}}
          sys         {:db-conn       (atom database)
                       :tts-cache-dir (str cache-dir)}
          options     (:options (tts/effective-provider-config
                                 {:db database} :openai :normal))
          legacy-key  [:fairy.box.tts/openai options input]
          legacy-file (io/file (str cache-dir) (tts/hash-text legacy-key))
          prepared_   (atom nil)
          _           (spit legacy-file "legacy audio")
          result      (with-redefs [tts/openai-tts
                                    (fn [{:keys [prepared-input]}]
                                      (reset! prepared_ prepared-input)
                                      (java.io.ByteArrayInputStream.
                                       (.getBytes "new audio")))]
                        (tts/tts sys input))]
      (is (= {:legacy-reused? false
              :content        "new audio"
              :cache-files    2
              :prepared       {:profile :openai/plain
                               :field   :text
                               :value   input
                               :version 1}}
             {:legacy-reused? (= (.getAbsolutePath legacy-file) result)
              :content        (slurp result)
              :cache-files    (count (fs/list-dir cache-dir))
              :prepared       {:profile (:speech-input/profile @prepared_)
                               :field   (:speech-input/field @prepared_)
                               :value   (:speech-input/value @prepared_)
                               :version (:speech-renderer/version @prepared_)}})))))

(deftest reads-elevenlabs-key-from-development-key-file
  (fs/with-temp-dir [temp-dir {:prefix "fairybox-elevenlabs-key-"}]
    (spit (str (fs/path temp-dir ".llm-keys"))
          (str "OPENAI_API_KEY=not-the-key\n"
               "export ELEVENLABS_API_KEY='test-elevenlabs-key'\n"))
    (let [original-home (System/getProperty "user.home")]
      (try
        (System/setProperty "user.home" (str temp-dir))
        (is (= "test-elevenlabs-key"
               ((private-var 'elevenlabs-api-key-from-file))))
        (finally
          (System/setProperty "user.home" original-home))))))

(deftest creates-elevenlabs-speech-request
  (let [requests_   (atom [])
        http-client @(private-var 'tts-http-client)
        responses   (with-redefs-fn
                      {(private-var 'elevenlabs-api-key) (constantly "test-api-key")
                       #'hc/post
                       (fn [url opts]
                         (swap! requests_ conj
                                {:url                 url
                                 :body                (util/<-json (:body opts))
                                 :as                  (:as opts)
                                 :content-type        (:content-type opts)
                                 :api-key             (get-in opts [:headers "xi-api-key"])
                                 :query-params        (:query-params opts)
                                 :shared-http-client? (identical? http-client (:http-client opts))
                                 :timeout             (:timeout opts)})
                         {:body ::audio-stream})}
                      #(vector
                        (tts/elevenlabs-tts
                         {:prepared-input
                          (speech/prepared-input
                           "Default"
                           :elevenlabs
                           {:model "eleven_multilingual_v2"})})
                        (tts/elevenlabs-tts
                         {:model          "test-model"
                          :voice-id       "test-voice"
                          :output-format  "mp3_22050_32"
                          :voice-settings {:stability 0.4
                                           :speed     0.9}
                          :prepared-input (speech/prepared-input
                                           "Custom"
                                           :elevenlabs
                                           {:model "test-model"})})))]
    (is (= {:requests
            [{:url                 "https://api.elevenlabs.io/v1/text-to-speech/JBFqnCBsd6RMkjVDRZzb"
              :body                {:text           "Default"
                                    :model_id       "eleven_multilingual_v2"
                                    :voice_settings {:stability         0.5
                                                     :similarity_boost  0.75
                                                     :style             0.0
                                                     :use_speaker_boost true
                                                     :speed             1.0}}
              :as                  :stream
              :content-type        :json
              :api-key             "test-api-key"
              :query-params        {"output_format" "opus_48000_128"}
              :shared-http-client? true
              :timeout             30000}
             {:url                 "https://api.elevenlabs.io/v1/text-to-speech/test-voice"
              :body                {:text           "Custom"
                                    :model_id       "test-model"
                                    :voice_settings {:stability         0.4
                                                     :similarity_boost  0.75
                                                     :style             0.0
                                                     :use_speaker_boost true
                                                     :speed             0.9}}
              :as                  :stream
              :content-type        :json
              :api-key             "test-api-key"
              :query-params        {"output_format" "opus_48000_128"}
              :shared-http-client? true
              :timeout             30000}]
            :responses [::audio-stream ::audio-stream]}
           {:requests  @requests_
            :responses responses}))))

(deftest dispatches-elevenlabs-engine-to-existing-cache
  (fs/with-temp-dir [cache-dir {:prefix "fairybox-elevenlabs-cache-"}]
    (let [text       "Already synthesized"
          database   {:settings {:tts {:engine :elevenlabs}}}
          sys        {:db-conn       (atom database)
                      :tts-cache-dir (str cache-dir)}
          options    (:options (tts/effective-provider-config
                                {:db database} :elevenlabs :normal))
          prepared   (speech/prepared-input text :elevenlabs options)
          cache-key  [:fairy.box.tts/elevenlabs
                      options
                      (speech/cache-identity prepared)]
          cache-file (io/file (str cache-dir) (tts/hash-text cache-key))]
      (spit cache-file "cached audio")
      (with-redefs [tts/elevenlabs-tts
                    (fn [_]
                      (throw (ex-info "Cache miss unexpectedly reached ElevenLabs" {})))]
        (is (= (.getAbsolutePath cache-file)
               (tts/tts sys text)))))))

(deftest writes-and-reuses-complete-elevenlabs-cache-file
  (fs/with-temp-dir [cache-dir {:prefix "fairybox-elevenlabs-write-cache-"}]
    (let [closed?_      (atom false)
          synthesized?_ (atom false)
          text          "Cache this stream"
          input         (proxy [java.io.ByteArrayInputStream] [(.getBytes "complete audio")]
                          (close []
                            (reset! closed?_ true)))
          args          {:tts-cache-dir  (str cache-dir)
                         :model          "eleven_multilingual_v2"
                         :voice-settings {:speed 0.9}
                         :prepared-input (speech/prepared-input
                                          text
                                          :elevenlabs
                                          {:model "eleven_multilingual_v2"})}
          paths         (with-redefs [tts/elevenlabs-tts
                                      (fn [_]
                                        (if (compare-and-set! synthesized?_ false true)
                                          input
                                          (throw (ex-info "Cache hit synthesized twice" {}))))]
                          [(tts/caching-elevenlabs-tts args)
                           (tts/caching-elevenlabs-tts args)])]
      (is (= {:same-path?     true
              :content        "complete audio"
              :stream-closed? true
              :cache-files    1}
             {:same-path?     (apply = paths)
              :content        (slurp (first paths))
              :stream-closed? @closed?_
              :cache-files    (count (fs/list-dir cache-dir))})))))

(deftest removes-incomplete-elevenlabs-cache-file
  (fs/with-temp-dir [cache-dir {:prefix "fairybox-elevenlabs-broken-cache-"}]
    (let [compressed     (let [out (java.io.ByteArrayOutputStream.)]
                           (with-open [gzip (java.util.zip.GZIPOutputStream. out)]
                             (.write gzip (.getBytes "partial audio")))
                           (.toByteArray out))
          truncated      (byte-array (take (- (alength compressed) 4) compressed))
          input          (java.util.zip.GZIPInputStream.
                          (java.io.ByteArrayInputStream. truncated))
          prepared-input (speech/prepared-input
                          "Broken stream"
                          :elevenlabs
                          {:model "eleven_multilingual_v2"})
          error          (with-redefs [tts/elevenlabs-tts (fn [_] input)]
                           (try
                             (tts/caching-elevenlabs-tts
                              {:tts-cache-dir  (str cache-dir)
                               :prepared-input prepared-input})
                             nil
                             (catch Exception e
                               e)))]
      (is (= {:error-class java.io.EOFException
              :cache-files 0}
             {:error-class (class error)
              :cache-files (count (fs/list-dir cache-dir))})))))

(deftest abandons-timed-out-elevenlabs-cache-write
  (fs/with-temp-dir [cache-dir {:prefix "fairybox-elevenlabs-timeout-cache-"}]
    (let [output         (java.io.PipedOutputStream.)
          input          (java.io.PipedInputStream. output)
          prepared-input (speech/prepared-input
                          "Blocked stream"
                          :elevenlabs
                          {:model "eleven_multilingual_v2"})
          error          (try
                           (with-redefs-fn
                             {(private-var 'tts-cache-timeout-ms) 20
                              #'tts/elevenlabs-tts                (fn [_] input)}
                             #(try
                                (tts/caching-elevenlabs-tts
                                 {:tts-cache-dir  (str cache-dir)
                                  :prepared-input prepared-input})
                                nil
                                (catch Exception e
                                  e)))
                           (finally
                             (.close output)))]
      (Thread/sleep 50)
      (is (= {:message     "TTS cache write timed out"
              :data        {:timeout-ms 20}
              :cache-files 0}
             {:message     (ex-message error)
              :data        (ex-data error)
              :cache-files (count (fs/list-dir cache-dir))})))))

(deftest resolves-redacted-effective-provider-configurations
  (let [database {:settings
                  {:tts {:providers
                         {:openai     {:api-key      "database-probe-key"
                                       :model        "tts-1"
                                       :voice        "cedar"
                                       :instructions "Must be omitted."
                                       :speed        1.5}
                          :elevenlabs {:output-format "unsupported"}}}}}
        sys      {:db database
                  :fallback-credentials
                  {:openai {:credential (cloak/mask "fallback-probe-key")
                            :source     :environment}}}
        openai   (tts/effective-provider-config sys :openai :normal)
        eleven   (tts/effective-provider-config
                  sys :elevenlabs :browser-preview
                  {:can-use-style?         false
                   :can-use-speaker-boost? false})
        google   (tts/effective-provider-config sys :google-cloud
                                                :browser-preview)]
    (is (= {:openai-options     {:model           "tts-1"
                                 :voice           "alloy"
                                 :speed           1.5
                                 :response-format "mp3"}
            :openai-source      :database
            :credential-masked? true
            :print-redacted?    true
            :eleven-output      "opus_48000_128"
            :eleven-settings
            {:stability 0.5 :similarity-boost 0.75 :speed 1.0}
            :warning-kind       :unsupported-value
            :google-encoding    "OGG_OPUS"}
           {:openai-options     (:options openai)
            :openai-source      (get-in openai [:credential-status :source])
            :credential-masked? (cloak/secret? (:credential openai))
            :print-redacted?
            (and (= "database-probe-key" (cloak/reveal (:credential openai)))
                 (not (str/includes? (pr-str openai) "database-probe-key"))
                 (not (str/includes? (pr-str openai) "fallback-probe-key")))
            :eleven-output      (get-in eleven [:options :output-format])
            :eleven-settings    (get-in eleven [:options :voice-settings])
            :warning-kind       (get-in eleven [:warnings 0 :kind])
            :google-encoding    (get-in google [:options :audio-encoding])}))))

(deftest cache-identity-includes-output-options-but-excludes-credentials
  (let [database-a {:settings {:tts {:providers
                                     {:openai {:api-key "first-probe-key"}}}}}
        database-b {:settings {:tts {:providers
                                     {:openai {:api-key "second-probe-key"}}}}}
        database-c (assoc-in database-b
                             [:settings :tts :providers :openai :voice]
                             "onyx")
        database-d (assoc-in database-b
                             [:settings :tts :providers :openai :speed]
                             1.5)
        normal-a   (tts/effective-provider-config {:db database-a}
                                                  :openai :normal)
        normal-b   (tts/effective-provider-config {:db database-b}
                                                  :openai :normal)
        normal-c   (tts/effective-provider-config {:db database-c}
                                                  :openai :normal)
        normal-d   (tts/effective-provider-config {:db database-d}
                                                  :openai :normal)
        preview-a  (tts/effective-provider-config {:db database-a}
                                                  :openai :browser-preview)
        identity   (fn [config suffix]
                     (let [options  (:options config)
                           prepared (speech/prepared-input "Hello" :openai options)]
                       (tts/hash-text [:fairy.box.tts/openai
                                       options
                                       (speech/cache-identity prepared)]
                                      suffix)))]
    (is (= {:credential-change-same?   true
            :voice-change-different?   true
            :speed-change-different?   true
            :mode-change-different?    true
            :credential-not-cacheable? true}
           {:credential-change-same?
            (= (identity normal-a ".tts-cache")
               (identity normal-b ".tts-cache"))
            :voice-change-different?
            (not= (identity normal-b ".tts-cache")
                  (identity normal-c ".tts-cache"))
            :speed-change-different?
            (not= (identity normal-b ".tts-cache")
                  (identity normal-d ".tts-cache"))
            :mode-change-different?
            (not= (identity normal-a ".tts-cache")
                  (identity preview-a tts/preview-cache-suffix))
            :credential-not-cacheable?
            (every? #(not (contains? % :credential))
                    [(:options normal-a) (:options normal-b)])}))))

(deftest reports-and-clears-only-tts-audio-cache-files
  (fs/with-temp-dir [cache-dir {:prefix "fairybox-audio-cache-test-"}]
    (let [normal-name  (tts/hash-text :normal)
          preview-name (tts/hash-text :preview tts/preview-cache-suffix)
          system       {:tts-cache-dir (str cache-dir)}]
      (spit (fs/file cache-dir normal-name) "1234")
      (spit (fs/file cache-dir preview-name) "123456")
      (spit (fs/file cache-dir "provider-catalogs.edn") "catalog")
      (spit (fs/file cache-dir "cache-write.tmp") "temporary")
      (fs/create-dir (fs/path cache-dir "nested.tts-cache"))
      (let [before        (tts/audio-cache-stats system)
            removed-count (tts/clear-audio-cache! system)
            after         (tts/audio-cache-stats system)
            remaining     (->> (fs/list-dir cache-dir)
                               (map fs/file-name)
                               set)
            missing-dir   {:tts-cache-dir
                           (str (fs/path cache-dir "missing"))}]
        (is (= {:before          {:file-count 2 :total-bytes 10}
                :removed-count   2
                :after           {:file-count 0 :total-bytes 0}
                :remaining       #{"provider-catalogs.edn"
                                   "cache-write.tmp"
                                   "nested.tts-cache"}
                :missing-stats   {:file-count 0 :total-bytes 0}
                :missing-removed 0}
               {:before          before
                :removed-count   removed-count
                :after           after
                :remaining       remaining
                :missing-stats   (tts/audio-cache-stats missing-dir)
                :missing-removed (tts/clear-audio-cache! missing-dir)}))))))

(deftest validates-contained-preview-cache-files
  (fs/with-temp-dir [cache-dir {:prefix "fairybox-preview-cache-test-"}]
    (let [name    (tts/hash-text :valid-preview tts/preview-cache-suffix)
          file    (fs/file cache-dir name)
          missing (tts/hash-text :missing-preview tts/preview-cache-suffix)
          system  {:tts-cache-dir (str cache-dir)}]
      (spit file "opus")
      (is (= {:valid        :ok
              :missing      :missing
              :traversal    :malformed
              :normal-cache :malformed}
             {:valid        (:result (tts/preview-cache-file system name))
              :missing      (:result (tts/preview-cache-file system missing))
              :traversal    (:result
                             (tts/preview-cache-file
                              system "../x.preview.opus.tts-cache"))
              :normal-cache (:result
                             (tts/preview-cache-file
                              system (tts/hash-text :normal)))})))))

(deftest cleans-only-browser-preview-cache-files
  (fs/with-temp-dir [cache-dir {:prefix "fairybox-preview-cleanup-"}]
    (let [cleanup!       (private-var 'cleanup-preview-cache!)
          cleanup-period @(private-var 'preview-cache-cleanup-period)
          preview-name   (tts/hash-text :preview tts/preview-cache-suffix)
          normal-name    (tts/hash-text :normal)
          preview-file   (fs/file cache-dir preview-name)]
      (spit preview-file "preview")
      (spit (fs/file cache-dir normal-name) "normal")
      (spit (fs/file cache-dir "provider-catalogs.edn") "catalog")
      (is (= {:cleanup-period (Duration/ofMinutes 5)
              :deleted        1
              :remaining      (set [normal-name "provider-catalogs.edn"])}
             {:cleanup-period cleanup-period
              :deleted        (cleanup! (str cache-dir))
              :remaining      (->> (fs/list-dir cache-dir)
                                   (map fs/file-name)
                                   set)})))))

(deftest runs-preview-cache-cleanup-on-chime-and-stops-with-tts
  (fs/with-temp-dir [cache-dir {:prefix "fairybox-preview-chime-"}]
    (let [start!       (private-var 'start-preview-cache-cleanup!)
          period-var   (private-var 'preview-cache-cleanup-period)
          preview-file (fs/file cache-dir
                                (tts/hash-text :scheduled-preview
                                               tts/preview-cache-suffix))
          _            (spit preview-file "preview")
          schedule     (with-redefs-fn {period-var (Duration/ofMillis 10)}
                         #(start! (str cache-dir)))
          result       (try
                         {:removed?
                          (loop [attempts 100]
                            (cond
                              (not (fs/exists? preview-file)) true
                              (zero? attempts) false
                              :else (do
                                      (Thread/sleep 10)
                                      (recur (dec attempts)))))}
                         (finally
                           (tts/stop-tts!
                            {:preview-cache-cleanup schedule})))]
      (is (= {:removed? true
              :stopped? true}
             (assoc result :stopped? (realized? schedule)))))))

(deftest reveals-masked-home-assistant-token-only-at-http-boundary
  (let [request_ (atom nil)
        result
        (with-redefs [hc/post
                      (fn [url opts]
                        (reset! request_
                                {:url  url
                                 :body (util/<-json (:body opts))
                                 :authorization
                                 (get-in opts [:headers "authorization"])})
                        {:body (util/->json
                                {:url "http://audio.test/tts.mp3"})})]
          (tts/home-assistant-tts
           {:db {:settings
                 {:homeassistant
                  {:ha-url          "http://ha.test"
                   :ha-bearer-token "ha-probe-token"}}}
            :prepared-input
            (speech/prepared-input
             (speech/plan [(speech/text "Hello")
                           (speech/pause 1000)
                           (speech/text "again")])
             :ha
             {})}))]
    (is (= {:request                   {:url           "http://ha.test/api/tts_get_url"
                                        :body          {:message   "Hello...\nagain"
                                                        :engine_id "tts.piper"}
                                        :authorization "Bearer ha-probe-token"}
            :result                    "http://audio.test/tts.mp3"
            :database-access-redacted? true}
           {:request @request_
            :result  result
            :database-access-redacted?
            (not (str/includes?
                  (pr-str (db/ha-bearer-token
                           {:settings
                            {:homeassistant
                             {:ha-bearer-token "ha-probe-token"}}}))
                  "ha-probe-token"))}))))

(deftest gates-only-categorized-card-error-speech
  (let [spoken_ (atom [])
        handle! (fn [enabled? value]
                  (tts/events-handler!
                   {:db-conn
                    (atom {:settings
                           {:tts-error-messages? enabled?}})}
                   {:value value}))]
    (with-redefs [tts/tts-speak (fn [_ value]
                                  (swap! spoken_ conj value))]
      (handle! false
               {:action              :tts/speak
                :feedback/type       :card-playback-problem
                :audio/play-one-shot true
                :text                "disabled problem"})
      (handle! false
               {:action        :tts/speak
                :feedback/type :unknown-card
                :text          "disabled unknown"})
      (handle! false
               {:action :tts/speak
                :text   "ordinary speech"})
      (handle! true
               {:action              :tts/speak
                :feedback/type       :card-playback-problem
                :audio/play-one-shot true
                :text                "enabled problem"}))
    (is (= [{:text "ordinary speech"}
            {:text                "enabled problem"
             :audio/play-one-shot true}]
           @spoken_))))