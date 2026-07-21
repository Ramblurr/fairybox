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
