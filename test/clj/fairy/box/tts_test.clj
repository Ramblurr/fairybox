;; Copyright © 2025 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2
(ns fairy.box.tts-test
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as cheshire]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [exoscale.cloak :as cloak]
   [fairy.box.db :as db]
   [fairy.box.tts :as tts]
   [hato.client :as hc]))

(defn- private-var [symbol]
  (or (ns-resolve 'fairy.box.tts symbol)
      (throw (ex-info "TTS private var not found" {:symbol symbol}))))

(deftest renders-ssml-as-json-encodable-strings
  (let [ssmls {"card"  (tts/metadata->ssml [{:title "Introduction"}
                                            {:title "Tomorrow"}])
               "track" (tts/tts-track-text {:title "Introduction"} {:index 0})}]
    (is (= {:all-strings?    true
            :json-round-trip ssmls}
           {:all-strings?    (every? string? (vals ssmls))
            :json-round-trip (-> ssmls
                                 cheshire/generate-string
                                 cheshire/parse-string)}))))

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

(deftest normalizes-openai-speech-input
  (let [normalize (private-var 'openai-input)]
    (is (= {:ssml           "Hello & welcome.\nNext."
            :literal-angles "Read 2 < 3 and 5 > 4"
            :plain          "Hello there"}
           {:ssml           (normalize "<speak>Hello &amp; welcome.<break time=\"1s\"/>Next.</speak>")
            :literal-angles (normalize "Read 2 < 3 and 5 > 4")
            :plain          (normalize "Hello there")}))))

(deftest creates-openai-speech-request
  (let [requests_ (atom [])
        responses (with-redefs-fn
                    {(private-var 'openai-api-key) (constantly "test-api-key")
                     #'hc/post
                     (fn [url opts]
                       (swap! requests_ conj
                              {:url           url
                               :body          (cheshire/parse-string (:body opts) true)
                               :as            (:as opts)
                               :content-type  (:content-type opts)
                               :authorization (get-in opts [:headers "Authorization"])})
                       {:body ::audio-stream})}
                    #(vector
                      (tts/openai-tts {} "Default")
                      (tts/openai-tts {:model        "test-model"
                                       :voice        "onyx"
                                       :instructions "Speak slowly."
                                       :speed        1.5}
                                      "Custom")))]
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

(deftest dispatches-openai-engine-to-existing-cache
  (fs/with-temp-dir [cache-dir {:prefix "fairybox-openai-cache-"}]
    (let [text       "Already synthesized"
          database   {:settings {:tts {:engine :openai}}}
          sys        {:db-conn       (atom database)
                      :tts-cache-dir (str cache-dir)}
          options    (:options (tts/effective-provider-config
                                {:db database} :openai :normal))
          cache-key  [:fairy.box.tts/openai options text]
          cache-file (io/file (str cache-dir) (tts/hash-text cache-key))]
      (spit cache-file "cached audio")
      (with-redefs [tts/openai-tts
                    (fn [_ _]
                      (throw (ex-info "Cache miss unexpectedly reached OpenAI" {})))]
        (is (= (.getAbsolutePath cache-file)
               (tts/tts sys text)))))))

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

(deftest normalizes-elevenlabs-speech-input
  (let [normalize (private-var 'elevenlabs-input)]
    (is (= {:ssml           "Hello &amp; welcome. <break time=\"1s\"/> Next."
            :literal-angles "Read 2 < 3 and 5 > 4"
            :plain          "Hello there"}
           {:ssml           (normalize
                             "<speak><s>Hello &amp; welcome.</s><break time=\"1s\"/><s>Next.</s></speak>")
            :literal-angles (normalize "Read 2 < 3 and 5 > 4")
            :plain          (normalize "Hello there")}))))

(deftest creates-elevenlabs-speech-request
  (let [requests_   (atom [])
        http-client @(private-var 'tts-http-client)
        responses   (with-redefs-fn
                      {(private-var 'elevenlabs-api-key) (constantly "test-api-key")
                       #'hc/post
                       (fn [url opts]
                         (swap! requests_ conj
                                {:url                 url
                                 :body                (cheshire/parse-string (:body opts) true)
                                 :as                  (:as opts)
                                 :content-type        (:content-type opts)
                                 :api-key             (get-in opts [:headers "xi-api-key"])
                                 :query-params        (:query-params opts)
                                 :shared-http-client? (identical? http-client (:http-client opts))
                                 :timeout             (:timeout opts)})
                         {:body ::audio-stream})}
                      #(vector
                        (tts/elevenlabs-tts {} "Default")
                        (tts/elevenlabs-tts
                         {:model          "test-model"
                          :voice-id       "test-voice"
                          :output-format  "mp3_22050_32"
                          :voice-settings {:stability 0.4
                                           :speed     0.9}}
                         "<speak>Custom<break time=\"1s\"/></speak>")))]
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
              :body                {:text           "Custom<break time=\"1s\"/>"
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
          cache-key  [:fairy.box.tts/elevenlabs options text]
          cache-file (io/file (str cache-dir) (tts/hash-text cache-key))]
      (spit cache-file "cached audio")
      (with-redefs [tts/elevenlabs-tts
                    (fn [_ _]
                      (throw (ex-info "Cache miss unexpectedly reached ElevenLabs" {})))]
        (is (= (.getAbsolutePath cache-file)
               (tts/tts sys text)))))))

(deftest writes-and-reuses-complete-elevenlabs-cache-file
  (fs/with-temp-dir [cache-dir {:prefix "fairybox-elevenlabs-write-cache-"}]
    (let [closed?_      (atom false)
          synthesized?_ (atom false)
          input         (proxy [java.io.ByteArrayInputStream] [(.getBytes "complete audio")]
                          (close []
                            (reset! closed?_ true)))
          args          {:tts-cache-dir  (str cache-dir)
                         :voice-settings {:speed 0.9}}
          text          "Cache this stream"
          paths         (with-redefs [tts/elevenlabs-tts
                                      (fn [_ _]
                                        (if (compare-and-set! synthesized?_ false true)
                                          input
                                          (throw (ex-info "Cache hit synthesized twice" {}))))]
                          [(tts/caching-elevenlabs-tts args text)
                           (tts/caching-elevenlabs-tts args text)])]
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
    (let [compressed (let [out (java.io.ByteArrayOutputStream.)]
                       (with-open [gzip (java.util.zip.GZIPOutputStream. out)]
                         (.write gzip (.getBytes "partial audio")))
                       (.toByteArray out))
          truncated  (byte-array (take (- (alength compressed) 4) compressed))
          input      (java.util.zip.GZIPInputStream.
                      (java.io.ByteArrayInputStream. truncated))
          error      (with-redefs [tts/elevenlabs-tts (fn [_ _] input)]
                       (try
                         (tts/caching-elevenlabs-tts
                          {:tts-cache-dir (str cache-dir)}
                          "Broken stream")
                         nil
                         (catch Exception e
                           e)))]
      (is (= {:error-class java.io.EOFException
              :cache-files 0}
             {:error-class (class error)
              :cache-files (count (fs/list-dir cache-dir))})))))

(deftest abandons-timed-out-elevenlabs-cache-write
  (fs/with-temp-dir [cache-dir {:prefix "fairybox-elevenlabs-timeout-cache-"}]
    (let [output (java.io.PipedOutputStream.)
          input  (java.io.PipedInputStream. output)
          error  (try
                   (with-redefs-fn
                     {(private-var 'tts-cache-timeout-ms) 20
                      #'tts/elevenlabs-tts                (fn [_ _] input)}
                     #(try
                        (tts/caching-elevenlabs-tts
                         {:tts-cache-dir (str cache-dir)}
                         "Blocked stream")
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
                     (tts/hash-text [:fairy.box.tts/openai
                                     (:options config)
                                     "Hello"]
                                    suffix))]
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

(deftest reveals-masked-home-assistant-token-only-at-http-boundary
  (let [request_ (atom nil)
        result   (with-redefs [hc/post
                               (fn [url opts]
                                 (reset! request_
                                         {:url url
                                          :authorization
                                          (get-in opts
                                                  [:headers "authorization"])})
                                 {:body (cheshire/generate-string
                                         {:url "http://audio.test/tts.mp3"})})]
                   (tts/home-assistant-tts
                    {:db {:settings
                          {:homeassistant
                           {:ha-url          "http://ha.test"
                            :ha-bearer-token "ha-probe-token"}}}}
                    "Hello"))]
    (is (= {:request                   {:url           "http://ha.test/api/tts_get_url"
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