;; Copyright © 2025 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2
(ns fairy.box.tts-test
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as cheshire]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is]]
   [fairy.box.tts :as tts]
   [hato.client :as hc]))

(defn- private-var [symbol]
  (or (ns-resolve 'fairy.box.tts symbol)
      (throw (ex-info "TTS private var not found" {:symbol symbol}))))

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
                                       :instructions "Speak slowly."}
                                      "Custom")))]
    (is (= {:requests
            [{:url           "https://api.openai.com/v1/audio/speech"
              :body          {:model           "gpt-4o-mini-tts"
                              :input           "Default"
                              :voice           "marin"
                              :instructions    "Speak naturally."
                              :response_format "mp3"}
              :as            :stream
              :content-type  :json
              :authorization "Bearer test-api-key"}
             {:url           "https://api.openai.com/v1/audio/speech"
              :body          {:model           "test-model"
                              :input           "Custom"
                              :voice           "onyx"
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
          cache-key  [:fairy.box.tts/openai {} text]
          cache-file (io/file (str cache-dir) (tts/hash-text cache-key))]
      (spit cache-file "cached audio")
      (with-redefs [tts/openai-tts
                    (fn [_ _]
                      (throw (ex-info "Cache miss unexpectedly reached OpenAI" {})))]
        (is (= (.getAbsolutePath cache-file)
               (tts/tts {:db-conn       (atom {:settings {:tts {:engine :openai}}})
                         :tts-cache-dir (str cache-dir)}
                        text)))))))

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
        http-client @(private-var 'elevenlabs-http-client)
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
              :body                {:text     "Default"
                                    :model_id "eleven_multilingual_v2"}
              :as                  :stream
              :content-type        :json
              :api-key             "test-api-key"
              :query-params        {"output_format" "mp3_44100_128"}
              :shared-http-client? true
              :timeout             30000}
             {:url                 "https://api.elevenlabs.io/v1/text-to-speech/test-voice"
              :body                {:text           "Custom<break time=\"1s\"/>"
                                    :model_id       "test-model"
                                    :voice_settings {:stability 0.4
                                                     :speed     0.9}}
              :as                  :stream
              :content-type        :json
              :api-key             "test-api-key"
              :query-params        {"output_format" "mp3_22050_32"}
              :shared-http-client? true
              :timeout             30000}]
            :responses [::audio-stream ::audio-stream]}
           {:requests  @requests_
            :responses responses}))))

(deftest dispatches-elevenlabs-engine-to-existing-cache
  (fs/with-temp-dir [cache-dir {:prefix "fairybox-elevenlabs-cache-"}]
    (let [text       "Already synthesized"
          cache-key  [:fairy.box.tts/elevenlabs
                      {:model         "eleven_multilingual_v2"
                       :output-format "mp3_44100_128"
                       :voice-id      "JBFqnCBsd6RMkjVDRZzb"}
                      text]
          cache-file (io/file (str cache-dir) (tts/hash-text cache-key))]
      (spit cache-file "cached audio")
      (with-redefs [tts/elevenlabs-tts
                    (fn [_ _]
                      (throw (ex-info "Cache miss unexpectedly reached ElevenLabs" {})))]
        (is (= (.getAbsolutePath cache-file)
               (tts/tts {:db-conn       (atom {:settings {:tts {:engine :elevenlabs}}})
                         :tts-cache-dir (str cache-dir)}
                        text)))))))

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
                     {(private-var 'elevenlabs-cache-timeout-ms) 20
                      #'tts/elevenlabs-tts (fn [_ _] input)}
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
