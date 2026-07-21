;; Copyright © 2026 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2
(ns fairy.box.tts.speech
  "Provider-aware semantic speech plans and prepared TTS input.

  Speech plans contain only literal text and timed pauses. [[prepared-input]]
  validates and normalizes a plan, selects a conservative provider profile, and
  renders the value consumed by both provider requests and cache identities."
  (:require
   [clojure.string :as str]
   [dev.onionpancakes.chassis.core :as chassis]))

(def renderer-version 1)
(def cache-identity-keys
  [:speech-renderer/version
   :speech-input/profile
   :speech-input/field
   :speech-input/value])
(def maximum-pause-ms 3000)
(def elevenlabs-break-models
  #{"eleven_multilingual_v2"
    "eleven_flash_v2_5"
    "eleven_flash_v2"
    "eleven_turbo_v2_5"
    "eleven_turbo_v2"})
(def google-ssml-families
  #{"Standard" "Wavenet" "Neural2" "Studio" "News" "Polyglot"})
(def google-plain-families
  #{"Chirp3-HD" "Gemini" "Journey"})
(def plain-profiles
  #{:google/plain
    :openai/plain
    :elevenlabs-v3/plain
    :home-assistant/plain
    :unknown/plain})

(defn invalid-input! [reason data]
  (throw (ex-info "Invalid speech input"
                  (merge {:speech/error  :invalid-input
                          :speech/reason reason}
                         data))))

(defn validate-text-segment! [index segment]
  (when-not (= #{:segment/type :segment/text} (set (keys segment)))
    (invalid-input! :invalid-text-segment
                    {:segment/index index
                     :segment/value segment}))
  (when-not (string? (:segment/text segment))
    (invalid-input! :text-not-string
                    {:segment/index index
                     :segment/value segment})))

(defn validate-pause-segment! [index segment]
  (when-not (= #{:segment/type :pause/duration-ms} (set (keys segment)))
    (invalid-input! :invalid-pause-segment
                    {:segment/index index
                     :segment/value segment}))
  (let [duration-ms (:pause/duration-ms segment)]
    (when-not (and (integer? duration-ms)
                   (<= 1 duration-ms maximum-pause-ms))
      (invalid-input! :invalid-pause-duration
                      {:segment/index index
                       :segment/value segment}))))

(defn validate-segment! [index segment]
  (when-not (map? segment)
    (invalid-input! :segment-not-map
                    {:segment/index index
                     :segment/value segment}))
  (case (:segment/type segment)
    :text (validate-text-segment! index segment)
    :pause (validate-pause-segment! index segment)
    (invalid-input! :unknown-segment-type
                    {:segment/index index
                     :segment/value segment})))

(defn input-plan [input]
  (let [plan (if (string? input)
               {:speech/type     :plan
                :speech/segments [{:segment/type :text
                                   :segment/text input}]}
               input)]
    (when-not (and (map? plan)
                   (= #{:speech/type :speech/segments} (set (keys plan)))
                   (= :plan (:speech/type plan))
                   (vector? (:speech/segments plan)))
      (invalid-input! :invalid-plan {:speech/value input}))
    (doseq [[index segment] (map-indexed vector (:speech/segments plan))]
      (validate-segment! index segment))
    plan))

(defn append-normalized-segment [{:keys [segments degraded] :as result}
                                 segment]
  (let [previous (peek segments)]
    (cond
      (and (= :text (:segment/type previous))
           (= :text (:segment/type segment)))
      (assoc result
             :segments
             (conj (pop segments)
                   (update previous :segment/text str (:segment/text segment))))

      (and (= :pause (:segment/type previous))
           (= :pause (:segment/type segment)))
      (let [duration-ms (+ (:pause/duration-ms previous)
                           (:pause/duration-ms segment))]
        {:segments (conj (pop segments)
                         (assoc previous
                                :pause/duration-ms
                                (min maximum-pause-ms duration-ms)))
         :degraded (cond-> degraded
                     (> duration-ms maximum-pause-ms)
                     (conj :exact-pause-timing))})

      :else
      (update result :segments conj segment))))

(defn normalization [input]
  (let [segments (->> (:speech/segments (input-plan input))
                      (remove #(and (= :text (:segment/type %))
                                    (empty? (:segment/text %)))))
        result   (reduce append-normalized-segment
                         {:segments []
                          :degraded #{}}
                         segments)]
    (when (empty? (:segments result))
      (invalid-input! :empty-plan {:speech/value input}))
    {:plan     {:speech/type     :plan
                :speech/segments (:segments result)}
     :degraded (:degraded result)}))

(defn plan?
  "Returns true when `value` is a valid, non-empty speech plan."
  [value]
  (try
    (when-not (string? value)
      (normalization value)
      true)
    (catch clojure.lang.ExceptionInfo _
      false)))

(defn text
  "Returns a literal text segment for `value`."
  [value]
  (let [segment {:segment/type :text
                 :segment/text value}]
    (validate-text-segment! 0 segment)
    segment))

(defn pause
  "Returns a timed pause segment for `duration-ms` from 1 through 3000."
  [duration-ms]
  (let [segment {:segment/type      :pause
                 :pause/duration-ms duration-ms}]
    (validate-pause-segment! 0 segment)
    segment))

(defn plan
  "Returns a validated speech plan containing `segments`."
  [segments]
  (let [speech-plan {:speech/type     :plan
                     :speech/segments segments}]
    (normalization speech-plan)
    speech-plan))

(defn normalize
  "Returns `input` as a validated and normalized speech plan.

  Strings become one literal text segment. Maps must use the explicit speech
  plan shape and may contain only text and pause segments."
  [input]
  (:plan (normalization input)))

(defn google-voice-family
  "Returns the family segment from a well-formed Google Cloud `voice` ID."
  [voice]
  (when (string? voice)
    (when-let [[_ suffix]
               (re-matches #"^[a-z]{2,3}-(?:[A-Z]{2}|[0-9]{3})-(.+)$"
                           voice)]
      (let [parts (str/split suffix #"-")]
        (when (< 1 (count parts))
          (str/join "-" (butlast parts)))))))

(defn profile
  "Returns the conservative speech-input profile for `provider` and `options`."
  [provider options]
  (case provider
    :google-cloud
    (let [family (google-voice-family (:voice options))]
      (cond
        (google-ssml-families family) :google/ssml
        (google-plain-families family) :google/plain
        :else :unknown/plain))

    :openai
    :openai/plain

    :elevenlabs
    (let [model (:model options)]
      (cond
        (= "eleven_v3" model) :elevenlabs-v3/plain
        (elevenlabs-break-models model) :elevenlabs/breaks
        :else :unknown/plain))

    :mimic3
    :mimic3/ssml

    :ha
    :home-assistant/plain

    :unknown/plain))

(defn xml-text [value]
  (-> (chassis/html value)
      (str/replace "\"" "&quot;")
      (str/replace "'" "&apos;")))

(defn ssml-value [segments]
  (str "<speak>"
       (apply str
              (map (fn [segment]
                     (case (:segment/type segment)
                       :text
                       (xml-text (:segment/text segment))

                       :pause
                       (str "<break time=\""
                            (:pause/duration-ms segment)
                            "ms\"/>")))
                   segments))
       "</speak>"))

(defn elevenlabs-value [segments]
  (apply str
         (map (fn [segment]
                (case (:segment/type segment)
                  :text
                  (chassis/html (:segment/text segment))

                  :pause
                  (str "<break time=\""
                       (:pause/duration-ms segment)
                       "ms\" />")))
              segments)))

(defn pause-whitespace [duration-ms]
  (cond
    (<= duration-ms 500) " "
    (<= duration-ms 1500) "\n"
    :else "\n\n"))

(defn append-plain-pause [value duration-ms]
  (let [value (str/replace value #"[ \t]+$" "")
        value (if (re-find #"[.!?…]$" value)
                value
                (str value "..."))]
    (str value (pause-whitespace duration-ms))))

(defn plain-value [segments]
  (:value
   (reduce (fn [{:keys [value after-pause?]} segment]
             (case (:segment/type segment)
               :text
               {:value        (str value
                                   (cond-> (:segment/text segment)
                                     after-pause?
                                     (str/replace #"^[ \t]+" "")))
                :after-pause? false}

               :pause
               {:value        (append-plain-pause value
                                                  (:pause/duration-ms segment))
                :after-pause? true}))
           {:value        ""
            :after-pause? false}
           segments)))

(defn prepared-input
  "Returns provider-ready speech input for `input`, `provider`, and `options`.

  The result contains no credentials or runtime values and is suitable for
  provider request construction and cache identity."
  [input provider options]
  (let [{:keys [plan degraded]} (normalization input)
        input-profile           (profile provider options)
        segments                (:speech/segments plan)
        plain?                  (plain-profiles input-profile)
        value                   (cond
                                  plain? (plain-value segments)
                                  (= :elevenlabs/breaks input-profile)
                                  (elevenlabs-value segments)
                                  :else (ssml-value segments))
        degraded                (cond-> degraded
                                  (and plain?
                                       (some #(= :pause (:segment/type %))
                                             segments))
                                  (conj :exact-pause-timing))]
    {:speech-renderer/version renderer-version
     :speech-input/profile    input-profile
     :speech-input/field      (if (#{:google/ssml :mimic3/ssml}
                                   input-profile)
                                :ssml
                                :text)
     :speech-input/value      value
     :speech-input/degraded   degraded}))

(defn cache-identity
  "Returns the output-affecting cache identity from `prepared-input`."
  [prepared-input]
  (select-keys prepared-input cache-identity-keys))

(comment
  (def example
    (plan [(text "This one is Stories")
           (pause 1000)
           (text "1, Introduction")]))

  (prepared-input example :google-cloud {:voice "en-US-Polyglot-1"})
  (prepared-input example :elevenlabs {:model "eleven_v3"})
  (prepared-input "2 < 3 & 5 > 4" :openai {})

  :rcf)
