;; Copyright © 2026 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2
(ns fairy.box.tts.speech-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [fairy.box.tts.speech :as speech]))

(def sample-plan
  (speech/plan [(speech/text "Hello & <friends>")
                (speech/pause 1000)
                (speech/text " <speak>Next</speak>")]))

(def plain-value
  "Hello & <friends>...\n<speak>Next</speak>")

(def ssml-value
  "<speak>Hello &amp; &lt;friends&gt;<break time=\"1000ms\"/> &lt;speak&gt;Next&lt;/speak&gt;</speak>")

(deftest normalizes-plain-strings-to-literal-text
  (is (= {:speech/type     :plan
          :speech/segments [{:segment/type :text
                             :segment/text "2 < 3 & 5 > 4"}]}
         (speech/normalize "2 < 3 & 5 > 4"))))

(deftest rejects-invalid-speech-plans
  (doseq [[label input expected-reason]
          [[:empty "" :empty-plan]
           [:invalid-shape
            {:speech/type     :plan
             :speech/segments []
             :unexpected      true}
            :invalid-plan]
           [:unknown-segment
            {:speech/type     :plan
             :speech/segments [{:segment/type :phoneme
                                :segment/text "hello"}]}
            :unknown-segment-type]
           [:invalid-pause
            {:speech/type     :plan
             :speech/segments [{:segment/type      :pause
                                :pause/duration-ms 0}]}
            :invalid-pause-duration]]]
    (testing (name label)
      (let [error (try
                    (speech/normalize input)
                    nil
                    (catch clojure.lang.ExceptionInfo e
                      e))]
        (is (= {:message "Invalid speech input"
                :error   :invalid-input
                :reason  expected-reason}
               {:message (ex-message error)
                :error   (:speech/error (ex-data error))
                :reason  (:speech/reason (ex-data error))}))))))

(deftest normalizes-adjacent-segments-and-records-degradation
  (let [input (speech/plan [(speech/text "Hello")
                            (speech/text " there")
                            (speech/pause 2000)
                            (speech/pause 2000)
                            (speech/text "")
                            (speech/text "friend")])]
    (is (= {:speech-renderer/version 1
            :speech-input/profile    :openai/plain
            :speech-input/field      :text
            :speech-input/value      "Hello there...\n\nfriend"
            :speech-input/degraded   #{:exact-pause-timing}}
           (speech/prepared-input input :openai {})))
    (is (= {:speech/type :plan
            :speech/segments
            [(speech/text "Hello there")
             (speech/pause 3000)
             (speech/text "friend")]}
           (speech/normalize input)))))

(deftest renders-complete-provider-inputs
  (doseq [[label provider options expected]
          [[:google-ssml
            :google-cloud
            {:voice "en-US-Standard-A"}
            {:speech-renderer/version 1
             :speech-input/profile    :google/ssml
             :speech-input/field      :ssml
             :speech-input/value      ssml-value
             :speech-input/degraded   #{}}]
           [:google-plain
            :google-cloud
            {:voice "en-US-Chirp3-HD-Algieba"}
            {:speech-renderer/version 1
             :speech-input/profile    :google/plain
             :speech-input/field      :text
             :speech-input/value      plain-value
             :speech-input/degraded   #{:exact-pause-timing}}]
           [:google-unknown
            :google-cloud
            {:voice "en-US-Casual-K"}
            {:speech-renderer/version 1
             :speech-input/profile    :unknown/plain
             :speech-input/field      :text
             :speech-input/value      plain-value
             :speech-input/degraded   #{:exact-pause-timing}}]
           [:openai
            :openai
            {:model "tts-1"}
            {:speech-renderer/version 1
             :speech-input/profile    :openai/plain
             :speech-input/field      :text
             :speech-input/value      plain-value
             :speech-input/degraded   #{:exact-pause-timing}}]
           [:elevenlabs-v2
            :elevenlabs
            {:model "eleven_multilingual_v2"}
            {:speech-renderer/version 1
             :speech-input/profile    :elevenlabs/breaks
             :speech-input/field      :text
             :speech-input/value
             "Hello &amp; &lt;friends&gt;<break time=\"1000ms\" /> &lt;speak&gt;Next&lt;/speak&gt;"
             :speech-input/degraded   #{}}]
           [:elevenlabs-v3
            :elevenlabs
            {:model "eleven_v3"}
            {:speech-renderer/version 1
             :speech-input/profile    :elevenlabs-v3/plain
             :speech-input/field      :text
             :speech-input/value      plain-value
             :speech-input/degraded   #{:exact-pause-timing}}]
           [:elevenlabs-unknown
            :elevenlabs
            {:model "future-model"}
            {:speech-renderer/version 1
             :speech-input/profile    :unknown/plain
             :speech-input/field      :text
             :speech-input/value      plain-value
             :speech-input/degraded   #{:exact-pause-timing}}]
           [:mimic3
            :mimic3
            {}
            {:speech-renderer/version 1
             :speech-input/profile    :mimic3/ssml
             :speech-input/field      :ssml
             :speech-input/value      ssml-value
             :speech-input/degraded   #{}}]
           [:home-assistant
            :ha
            {}
            {:speech-renderer/version 1
             :speech-input/profile    :home-assistant/plain
             :speech-input/field      :text
             :speech-input/value      plain-value
             :speech-input/degraded   #{:exact-pause-timing}}]
           [:unknown-provider
            :future-provider
            {}
            {:speech-renderer/version 1
             :speech-input/profile    :unknown/plain
             :speech-input/field      :text
             :speech-input/value      plain-value
             :speech-input/degraded   #{:exact-pause-timing}}]]]
    (testing (name label)
      (is (= expected (speech/prepared-input sample-plan provider options))))))

(deftest renders-every-openai-model-as-plain-text
  (doseq [model ["gpt-4o-mini-tts" "tts-1" "tts-1-hd"]]
    (testing model
      (is (= {:speech-renderer/version 1
              :speech-input/profile    :openai/plain
              :speech-input/field      :text
              :speech-input/value      plain-value
              :speech-input/degraded   #{:exact-pause-timing}}
             (speech/prepared-input sample-plan :openai {:model model}))))))

(deftest treats-literal-markup-as-text
  (let [input "<speak>A & B<break time=\"1s\"/></speak>"]
    (is (= {:speech-renderer/version 1
            :speech-input/profile    :openai/plain
            :speech-input/field      :text
            :speech-input/value      input
            :speech-input/degraded   #{}}
           (speech/prepared-input input :openai {})))
    (is (= {:speech-renderer/version 1
            :speech-input/profile    :google/ssml
            :speech-input/field      :ssml
            :speech-input/value
            "<speak>&lt;speak&gt;A &amp; B&lt;break time=&quot;1s&quot;/&gt;&lt;/speak&gt;</speak>"
            :speech-input/degraded   #{}}
           (speech/prepared-input
            input :google-cloud {:voice "en-US-Standard-A"})))
    (is (= {:speech-renderer/version 1
            :speech-input/profile    :elevenlabs/breaks
            :speech-input/field      :text
            :speech-input/value
            "&lt;speak&gt;A &amp; B&lt;break time=\"1s\"/&gt;&lt;/speak&gt;"
            :speech-input/degraded   #{}}
           (speech/prepared-input
            input :elevenlabs {:model "eleven_multilingual_v2"})))))

(deftest cache-identity-includes-rendered-input-and-excludes-diagnostics
  (let [openai (speech/prepared-input sample-plan :openai {})
        google (speech/prepared-input
                sample-plan :google-cloud {:voice "en-US-Chirp3-HD-Algieba"})]
    (is (= {:speech-renderer/version 1
            :speech-input/profile    :openai/plain
            :speech-input/field      :text
            :speech-input/value      plain-value}
           (speech/cache-identity openai)))
    (is (not= (speech/cache-identity openai)
              (speech/cache-identity google)))
    (is (not= (speech/cache-identity openai)
              (speech/cache-identity
               (assoc openai :speech-renderer/version 2))))
    (is (= (speech/cache-identity openai)
           (speech/cache-identity
            (assoc openai :speech-input/degraded #{:diagnostic-only}))))))
