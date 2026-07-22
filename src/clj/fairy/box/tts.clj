;; Copyright © 2025 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2
(ns fairy.box.tts
  "Text-to-speech synthesis, caching, and playback integration.

  ## Engine selection

  [[tts]] selects the provider from `[:settings :tts :engine]`. Supported engine
  keywords are `:ha`, `:mimic3`, `:google-cloud`, `:openai`, and `:elevenlabs`;
  the database default is `:google-cloud`. Stream-returning providers are cached
  below the configured media directory in `tts-cache/`.

  ## Home Assistant (`:ha`)

  [[home-assistant-tts]] calls Home Assistant's `/api/tts_get_url` endpoint with
  the fixed engine ID `tts.piper`. Database settings are
  `[:settings :homeassistant :ha-url]` and
  `[:settings :homeassistant :ha-bearer-token]`. Home Assistant chooses the
  Piper model and voice; this namespace exposes no additional model settings.

  ## Mimic 3 (`:mimic3`)

  [[mimic3-tts]] uses the fixed endpoint `http://10.9.4.3:59125/api/tts` and
  voice `en_US/cmu-arctic_low#clb`. Its current fixed query settings are
  `noiseScale=0.677`, `noiseW=0.8`, `ssml=true`, and `audioTarget=client`.
  The endpoint, voice, and synthesis settings are not runtime-configurable.

  ## Configurable providers

  Google Cloud, OpenAI, and ElevenLabs read provider options from
  `[:settings :tts :providers]`. Credentials prefer a provider's database
  `:api-key`; Google Cloud then checks its legacy database key, while OpenAI and
  ElevenLabs check their environment variable and `~/.llm-keys`.

  [[effective-provider-config]] separates the request credential from normalized,
  output-affecting options. Normal synthesis keeps Google Cloud and OpenAI on
  MP3 and uses the saved ElevenLabs Opus/WAV format. Browser previews use Opus
  without mutating persisted settings. OpenAI instructions apply only to
  `gpt-4o-mini-tts`; speed applies to every listed OpenAI model and is bounded
  from `0.25` to `4.0`.

  Fairybox renders semantic speech plans for each selected provider and model.
  Streamed audio is cached below the media directory with bounded provider and
  cache-write timeouts; failed writes are never published as cache hits.

  ## REPL

  The rich comment form at the bottom of this namespace contains cached OpenAI
  and ElevenLabs synthesis examples that play the result through Vinyl."
  (:require
   [chime.core :as chime]
   [clojure.core.async :as async]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [donut.system :as ds]
   [exoscale.cloak :as cloak]
   [fairy.box.audio.browse :as browse]
   [fairy.box.db :as db]
   [fairy.box.tts.catalog :as catalog]
   [fairy.box.tts.speech :as speech]
   [hato.client :as hc]
   [fairy.box.util :refer [->json <-json]]
   [jp.nijohando.event :as ev])
  (:import
   [java.io File]
   [java.lang AutoCloseable]
   [java.nio.file CopyOption Files Paths StandardCopyOption]
   [java.time Duration Instant]
   [java.util Base64]))

(def configurable-providers [:google-cloud :openai :elevenlabs])
(def synthesis-modes #{:normal :browser-preview})
(def openai-models ["gpt-4o-mini-tts" "tts-1" "tts-1-hd"])
(def openai-voices-by-model
  {"gpt-4o-mini-tts" ["alloy" "ash" "ballad" "coral" "echo" "fable"
                      "onyx" "nova" "sage" "shimmer" "verse" "marin" "cedar"]
   "tts-1"           ["alloy" "ash" "coral" "echo" "fable" "onyx"
                      "nova" "sage" "shimmer"]
   "tts-1-hd"        ["alloy" "ash" "coral" "echo" "fable" "onyx"
                      "nova" "sage" "shimmer"]})
(def google-cloud-default-options
  (:google-cloud db/default-tts-provider-settings))
(def openai-default-options
  (:openai db/default-tts-provider-settings))
(def elevenlabs-default-options
  (:elevenlabs db/default-tts-provider-settings))
(def elevenlabs-output-formats
  ["opus_48000_32"
   "opus_48000_64"
   "opus_48000_96"
   "opus_48000_128"
   "opus_48000_192"
   "wav_8000"
   "wav_16000"
   "wav_22050"
   "wav_24000"
   "wav_32000"
   "wav_44100"
   "wav_48000"])
(def preview-encodings
  {:google-cloud "OGG_OPUS"
   :openai       "opus"
   :elevenlabs   "opus_48000_128"})

(def normal-encodings
  {:google-cloud "MP3"
   :openai       "mp3"})
(def openai-model-set (set openai-models))
(def elevenlabs-output-format-set
  (set elevenlabs-output-formats))
(def openai-api-key-pattern
  #"^\s*(?:export\s+)?OPENAI_API_KEY\s*=\s*['\"]?([^'\"\s#]+)['\"]?\s*(?:#.*)?$")
(def openai-tts-url "https://api.openai.com/v1/audio/speech")
(def elevenlabs-api-key-pattern
  #"^\s*(?:export\s+)?ELEVENLABS_API_KEY\s*=\s*['\"]?([^'\"\s#]+)['\"]?\s*(?:#.*)?$")
(def elevenlabs-tts-url "https://api.elevenlabs.io/v1/text-to-speech")
(def preview-cache-suffix ".preview.opus.tts-cache")
(def normal-cache-suffix ".tts-cache")
(def tts-cache-timeout-ms 60000)
(def preview-cache-cleanup-period (Duration/ofMinutes 5))
(def tts-http-client
  (hc/build-http-client {:connect-timeout 10000}))
(def tts-request-timeout-ms 30000)
(def ^:private tts-log-text-limit 300)

(defn- tts-log-text [input]
  (let [text (-> (speech/prepared-input input :unknown {})
                 :speech-input/value
                 (str/replace #"\s+" " ")
                 str/trim)]
    (if (<= (count text) tts-log-text-limit)
      text
      (str (subs text 0 (- tts-log-text-limit 3)) "..."))))

(defn- nonblank-string [value]
  (let [value (cloak/unmask value)]
    (when (string? value)
      (some-> value str/trim not-empty))))

(defn- api-key-from-file [pattern]
  (let [key-file (io/file (System/getProperty "user.home") ".llm-keys")]
    (when (.isFile key-file)
      (with-open [reader (io/reader key-file)]
        (some (fn [line]
                (second (re-matches pattern line)))
              (line-seq reader))))))

(defn- openai-api-key-from-file []
  (api-key-from-file openai-api-key-pattern))

(defn- openai-api-key []
  (or (nonblank-string (System/getenv "OPENAI_API_KEY"))
      (openai-api-key-from-file)))

(defn- elevenlabs-api-key-from-file []
  (api-key-from-file elevenlabs-api-key-pattern))

(defn- elevenlabs-api-key []
  (or (nonblank-string (System/getenv "ELEVENLABS_API_KEY"))
      (elevenlabs-api-key-from-file)))

(defn- credential-entry [source value]
  (when-let [credential (nonblank-string value)]
    {:credential (cloak/mask credential)
     :source     source}))

(defn- fallback-credential [provider]
  (case provider
    :openai
    (or (credential-entry :environment (System/getenv "OPENAI_API_KEY"))
        (credential-entry :key-file (openai-api-key-from-file)))

    :elevenlabs
    (or (credential-entry :environment (System/getenv "ELEVENLABS_API_KEY"))
        (credential-entry :key-file (elevenlabs-api-key-from-file)))

    nil))

(defn- fallback-credentials []
  {:openai     (fallback-credential :openai)
   :elevenlabs (fallback-credential :elevenlabs)})

(defn- database [sys]
  (or (:db sys)
      (some-> (:db-conn sys) deref)))

(defn- resolved-fallback-credential [sys provider]
  (if (contains? (:fallback-credentials sys) provider)
    (get (:fallback-credentials sys) provider)
    (fallback-credential provider)))

(defn- effective-credential [sys provider]
  (let [database (database sys)]
    (or (credential-entry :database
                          (:api-key (db/tts-provider-settings database provider)))
        (case provider
          :google-cloud
          (credential-entry :legacy-database
                            (db/google-cloud-api-key database))

          (:openai :elevenlabs)
          (resolved-fallback-credential sys provider)

          (throw (ex-info "Unsupported configurable TTS provider"
                          {:provider provider})))
        {:credential nil
         :source     nil})))

(defn credential-status
  "Returns redacted credential state for `provider` without the credential value."
  [sys provider]
  (let [{:keys [credential source]} (effective-credential sys provider)]
    {:configured? (boolean credential)
     :source      source}))

(defn- bounded-number [value default minimum maximum]
  (if (and (number? value)
           (Double/isFinite (double value))
           (<= minimum value maximum))
    value
    default))

(defn- google-cloud-options [settings synthesis-mode]
  {:language-code  (or (nonblank-string (:language-code settings))
                       (:language-code google-cloud-default-options))
   :voice          (or (nonblank-string (:voice settings))
                       (:voice google-cloud-default-options))
   :audio-encoding (if (= :browser-preview synthesis-mode)
                     (:google-cloud preview-encodings)
                     (:google-cloud normal-encodings))})

(defn- openai-options [settings synthesis-mode]
  (let [model         (if (openai-model-set (:model settings))
                        (:model settings)
                        (:model openai-default-options))
        voices        (get openai-voices-by-model model)
        default-voice (if (= model (:model openai-default-options))
                        (:voice openai-default-options)
                        "alloy")
        voice         (if (some #{(:voice settings)} voices)
                        (:voice settings)
                        default-voice)
        instructions  (if (string? (:instructions settings))
                        (:instructions settings)
                        (:instructions openai-default-options))
        speed         (bounded-number (:speed settings)
                                      (:speed openai-default-options)
                                      0.25
                                      4.0)]
    (cond-> {:model           model
             :voice           voice
             :speed           speed
             :response-format (if (= :browser-preview synthesis-mode)
                                (:openai preview-encodings)
                                (:openai normal-encodings))}
      (= model "gpt-4o-mini-tts")
      (assoc :instructions instructions))))

(defn- normalized-elevenlabs-voice-settings [voice-settings model-capabilities]
  (let [defaults (:voice-settings elevenlabs-default-options)]
    (cond-> {:stability         (bounded-number (:stability voice-settings)
                                                (:stability defaults)
                                                0.0
                                                1.0)
             :similarity-boost  (bounded-number (:similarity-boost voice-settings)
                                                (:similarity-boost defaults)
                                                0.0
                                                1.0)
             :style             (bounded-number (:style voice-settings)
                                                (:style defaults)
                                                0.0
                                                1.0)
             :use-speaker-boost (if (boolean? (:use-speaker-boost voice-settings))
                                  (:use-speaker-boost voice-settings)
                                  (:use-speaker-boost defaults))
             :speed             (bounded-number (:speed voice-settings)
                                                (:speed defaults)
                                                0.7
                                                1.2)}
      (false? (:can-use-style? model-capabilities))
      (dissoc :style)

      (false? (:can-use-speaker-boost? model-capabilities))
      (dissoc :use-speaker-boost))))

(defn- elevenlabs-options-and-warnings
  [settings synthesis-mode model-capabilities]
  (let [saved-output-format (:output-format settings)
        supported-output?   (elevenlabs-output-format-set saved-output-format)
        output-format       (if (= :browser-preview synthesis-mode)
                              (:elevenlabs preview-encodings)
                              (if supported-output?
                                saved-output-format
                                (:output-format elevenlabs-default-options)))
        warning             (when-not supported-output?
                              {:field   :output-format
                               :kind    :unsupported-value
                               :message "The saved output format is unsupported; using Opus 128 kbps."})]
    {:options  {:model          (or (nonblank-string (:model settings))
                                    (:model elevenlabs-default-options))
                :voice-id       (or (nonblank-string (:voice-id settings))
                                    (:voice-id elevenlabs-default-options))
                :output-format  output-format
                :voice-settings (normalized-elevenlabs-voice-settings
                                 (:voice-settings settings)
                                 model-capabilities)}
     :warnings (cond-> [] warning (conj warning))}))

(defn- provider-options [database provider synthesis-mode model-capabilities]
  (let [settings (db/tts-provider-settings database provider)]
    (case provider
      :google-cloud
      {:options  (google-cloud-options settings synthesis-mode)
       :warnings []}

      :openai
      {:options  (openai-options settings synthesis-mode)
       :warnings []}

      :elevenlabs
      (elevenlabs-options-and-warnings settings synthesis-mode model-capabilities)

      (throw (ex-info "Unsupported configurable TTS provider"
                      {:provider provider})))))

(defn effective-provider-config
  "Returns one normalized provider configuration for `synthesis-mode`.

  The request credential is separate from cacheable `:options`, and
  `:credential-status` never contains the credential value. Optional
  `model-capabilities` controls ElevenLabs style and speaker-boost support."
  ([sys provider synthesis-mode]
   (effective-provider-config sys provider synthesis-mode nil))
  ([sys provider synthesis-mode model-capabilities]
   (when-not (synthesis-modes synthesis-mode)
     (throw (ex-info "Unsupported TTS synthesis mode"
                     {:synthesis-mode synthesis-mode})))
   (let [credential                  (effective-credential sys provider)
         {:keys [options warnings]}  (provider-options (database sys)
                                                       provider
                                                       synthesis-mode
                                                       model-capabilities)
         {:keys [credential source]} credential]
     {:provider          provider
      :credential        credential
      :credential-status {:configured? (boolean credential)
                          :source      source}
      :options           options
      :synthesis-mode    synthesis-mode
      :warnings          warnings})))

(defn- elevenlabs-options [sys]
  (:options (elevenlabs-options-and-warnings
             (merge elevenlabs-default-options
                    (select-keys sys [:model :output-format :voice-id :voice-settings]))
             (or (:synthesis-mode sys) :normal)
             (:model-capabilities sys))))

(defn- google-cloud-request-options [sys]
  (google-cloud-options
   (merge google-cloud-default-options
          (select-keys sys [:language-code :voice]))
   (or (:synthesis-mode sys) :normal)))

(defn- openai-request-options [sys]
  (openai-options
   (merge openai-default-options
          (select-keys sys [:model :voice :instructions :speed]))
   (or (:synthesis-mode sys) :normal)))

(defn- cache-suffix [sys]
  (if (= :browser-preview (:synthesis-mode sys))
    preview-cache-suffix
    normal-cache-suffix))

(defn tts-cache-dir [settings]
  (str (browse/media-dir settings) "/tts-cache"))

(defn- audio-cache-file? [^File file]
  (and (.isFile file)
       (str/ends-with? (.getName file) normal-cache-suffix)))

(defn- audio-cache-files [cache-dir]
  (if cache-dir
    (let [^File cache-root (io/file cache-dir)]
      (if (.isDirectory cache-root)
        (into [] (filter audio-cache-file?) (.listFiles cache-root))
        []))
    []))

(defn audio-cache-stats
  "Returns the generated TTS audio file count and total byte size.

  Non-audio cache entries, including the provider catalog, are excluded."
  [{:keys [tts-cache-dir]}]
  (let [files (audio-cache-files tts-cache-dir)]
    {:file-count  (count files)
     :total-bytes (reduce (fn [total ^File file]
                            (+ total (.length file)))
                          0
                          files)}))

(defn clear-audio-cache!
  "Deletes generated TTS audio files and returns the number removed.

  Non-audio cache entries, including the provider catalog, remain untouched."
  [{:keys [tts-cache-dir]}]
  (reduce (fn [deleted-count ^File file]
            (try
              (if (Files/deleteIfExists (.toPath file))
                (inc deleted-count)
                deleted-count)
              (catch Exception error
                (log/warn error
                          "Unable to delete TTS audio cache file"
                          (.getName file))
                deleted-count)))
          0
          (audio-cache-files tts-cache-dir)))

(defn hash-text
  ([text]
   (hash-text text normal-cache-suffix))
  ([text suffix]
   (str
    (.encodeToString (Base64/getUrlEncoder) (.getBytes ^String (str (hash text))))
    suffix)))

(defn b64->input-stream [^String b64]
  (io/input-stream (.decode (Base64/getDecoder) (.getBytes b64))))

(defn cache-get
  "Returns the readable cache path for `cache-key` from `cache-dir`, if present."
  ([cache-dir cache-key]
   (cache-get cache-dir cache-key normal-cache-suffix))
  ([cache-dir cache-key suffix]
   (assert cache-dir "cache-dir must be set")
   (let [maybe-file (.toFile (Paths/get cache-dir
                                        (into-array [(hash-text cache-key suffix)])))]
     (when (and (.exists maybe-file) (.canRead maybe-file))
       (.getAbsolutePath maybe-file)))))

(defn home-assistant-tts [{:keys [db prepared-input]}]
  (let [api-url      (str (db/ha-url db) "/api/tts_get_url")
        bearer-token (db/ha-bearer-token db)
        message      (:speech-input/value prepared-input)]
    (assert api-url "home assistant api url must be set in settings")
    (assert bearer-token "home assistant bearer token must be set in settings")
    (try
      (->
       (hc/post api-url
                {:body         (->json {"message" message "engine_id" "tts.piper"})
                 :content-type :json
                 :headers      {"authorization"
                                (str "Bearer " (cloak/unmask bearer-token))}})
       :body
       <-json
       :url)
      (catch Exception _
        (log/error "Home Assistant TTS request failed")
        nil))))

(defn cache-file [cache-dir text remote-url]
  (assert cache-dir)

  (with-open [in  (io/input-stream remote-url)
              out (io/output-stream (.toFile (Paths/get cache-dir (into-array [(hash-text text)]))))]
    (io/copy in out)))

(defn- move-cache-file! [^java.io.File temp-file ^java.io.File dest-file]
  (Files/move (.toPath temp-file)
              (.toPath dest-file)
              (into-array CopyOption
                          [StandardCopyOption/ATOMIC_MOVE
                           StandardCopyOption/REPLACE_EXISTING]))
  (.getAbsolutePath dest-file))

(defn- write-cache-input-stream! [cache-dir cache-key suffix in publish!]
  (assert cache-dir)
  (let [dest-file (.toFile (Paths/get cache-dir
                                      (into-array [(hash-text cache-key suffix)])))
        temp-file (java.io.File/createTempFile ".fairybox-tts-" ".tmp" (io/file cache-dir))]
    (try
      (with-open [^java.io.InputStream input   in
                  ^java.io.OutputStream output (io/output-stream temp-file)]
        (io/copy input output))
      (publish! temp-file dest-file)
      (finally
        (Files/deleteIfExists (.toPath temp-file))))))

(defn cache-input-stream
  ([cache-dir cache-key in]
   (cache-input-stream cache-dir cache-key normal-cache-suffix in))
  ([cache-dir cache-key suffix in]
   (write-cache-input-stream! cache-dir cache-key suffix in move-cache-file!)))

(defn- cache-input-stream-with-timeout
  [cache-dir cache-key suffix in timeout-ms]
  (let [state       (Object.)
        cancelled?_ (atom false)
        result_     (promise)
        worker_     (future
                      (try
                        (write-cache-input-stream!
                         cache-dir
                         cache-key
                         suffix
                         in
                         (fn [temp-file dest-file]
                           (locking state
                             (when @cancelled?_
                               (throw (ex-info "TTS cache write cancelled" {})))
                             (let [path (move-cache-file! temp-file dest-file)]
                               (deliver result_ {:value path})
                               path))))
                        (catch Throwable e
                          (deliver result_ {:error e}))))
        result      (deref result_ timeout-ms ::cache-write-timeout)
        result      (if (= ::cache-write-timeout result)
                      (locking state
                        (if (realized? result_)
                          @result_
                          (do
                            (reset! cancelled?_ true)
                            ::cancel-cache-write)))
                      result)]
    (if (= ::cancel-cache-write result)
      (do
        (try
          (.close ^java.io.InputStream in)
          (finally
            (future-cancel worker_)))
        (throw (ex-info "TTS cache write timed out" {:timeout-ms timeout-ms})))
      (if-let [error (:error result)]
        (throw error)
        (:value result)))))

(defn- log-tts-audio-ready! [provider synthesis-mode source text path]
  (when path
    (log/info "TTS audio ready"
              (cond-> {:provider provider
                       :mode     synthesis-mode
                       :source   source}
                (seq text) (assoc :text text))))
  path)

(defn caching-home-assistant-tts
  [{:keys [log-text prepared-input tts-cache-dir] :as sys}]
  (let [cache-key [::home-assistant (speech/cache-identity prepared-input)]]
    (if-let [local-url (cache-get tts-cache-dir cache-key)]
      (log-tts-audio-ready! :ha :normal :cache log-text local-url)
      (let [remote-url (home-assistant-tts sys)]
        (future (cache-file tts-cache-dir cache-key remote-url))
        (log-tts-audio-ready! :ha :normal :synthesized log-text remote-url)))))

(defn mimic3-tts [{:keys [prepared-input]}]
  (->
   (hc/get "http://10.9.4.3:59125/api/tts"
           {:query-params {"text"        (:speech-input/value prepared-input)
                           "voice"       "en_US/cmu-arctic_low#clb"
                           "noiseScale"  "0.677"
                           "noiseW"      "0.8"
                           ;; "lengthScale" "1.2"
                           "ssml"        "true"
                           "audioTarget" "client"}
            :as           :stream})
   :body))

(defn caching-mimic3-tts
  [{:keys [log-text prepared-input tts-cache-dir] :as sys}]
  (let [cache-key [::mimic3 (speech/cache-identity prepared-input)]]
    (if-let [local-url (cache-get tts-cache-dir cache-key)]
      (log-tts-audio-ready! :mimic3 :normal :cache log-text local-url)
      (let [in (mimic3-tts sys)]
        (log-tts-audio-ready! :mimic3
                              :normal
                              :synthesized
                              log-text
                              (cache-input-stream tts-cache-dir cache-key in))))))

(defn google-cloud-tts [{:keys [prepared-input] :as sys}]
  (let [{:keys [language-code voice audio-encoding]} (google-cloud-request-options sys)
        api-key (or (:credential sys)
                    (db/google-cloud-api-key (:db sys)))
        input-field (:speech-input/field prepared-input)
        input-value (:speech-input/value prepared-input)]
    (assert api-key "Google Cloud TTS credential is not configured")
    (-> (hc/post "https://texttospeech.googleapis.com/v1/text:synthesize"
                 {:body         (->json {"input"       {(name input-field) input-value}
                                         "voice"       {"languageCode" language-code
                                                        "name"         voice}
                                         "audioConfig" {"audioEncoding" audio-encoding}})
                  :content-type :json
                  :headers      {"X-Goog-Api-Key" (cloak/unmask api-key)}
                  :http-client  tts-http-client
                  :timeout      tts-request-timeout-ms})
        :body
        <-json
        :audioContent
        b64->input-stream)))

(defn caching-google-cloud-tts
  [{:keys [log-text prepared-input tts-cache-dir] :as sys}]
  (let [options        (google-cloud-request-options sys)
        cache-key      [::google-cloud options (speech/cache-identity prepared-input)]
        suffix         (cache-suffix sys)
        synthesis-mode (or (:synthesis-mode sys) :normal)]
    (if-let [local-url (cache-get tts-cache-dir cache-key suffix)]
      (log-tts-audio-ready! :google-cloud synthesis-mode :cache log-text local-url)
      (log-tts-audio-ready!
       :google-cloud
       synthesis-mode
       :synthesized
       log-text
       (cache-input-stream-with-timeout
        tts-cache-dir
        cache-key
        suffix
        (google-cloud-tts sys)
        tts-cache-timeout-ms)))))

(defn openai-tts [{:keys [prepared-input] :as sys}]
  (let [{:keys [model voice instructions speed response-format]}
        (openai-request-options sys)
        api-key (or (:credential sys)
                    (some-> (openai-api-key) cloak/mask))
        body (cond-> {"model"           model
                      "input"           (:speech-input/value prepared-input)
                      "voice"           voice
                      "speed"           speed
                      "response_format" response-format}
               instructions
               (assoc "instructions" instructions))]
    (assert api-key "OpenAI TTS credential is not configured")
    (-> (hc/post openai-tts-url
                 {:as           :stream
                  :body         (->json body)
                  :content-type :json
                  :headers      {"Authorization"
                                 (str "Bearer " (cloak/unmask api-key))}
                  :http-client  tts-http-client
                  :timeout      tts-request-timeout-ms})
        :body)))

(defn caching-openai-tts
  [{:keys [log-text prepared-input tts-cache-dir] :as sys}]
  (let [options        (openai-request-options sys)
        cache-key      [::openai options (speech/cache-identity prepared-input)]
        suffix         (cache-suffix sys)
        synthesis-mode (or (:synthesis-mode sys) :normal)]
    (if-let [local-url (cache-get tts-cache-dir cache-key suffix)]
      (log-tts-audio-ready! :openai synthesis-mode :cache log-text local-url)
      (log-tts-audio-ready!
       :openai
       synthesis-mode
       :synthesized
       log-text
       (cache-input-stream-with-timeout
        tts-cache-dir
        cache-key
        suffix
        (openai-tts sys)
        tts-cache-timeout-ms)))))

(defn- elevenlabs-voice-settings-body [voice-settings]
  (into {}
        (map (fn [[setting value]]
               [(case setting
                  :similarity-boost "similarity_boost"
                  :use-speaker-boost "use_speaker_boost"
                  (name setting))
                value]))
        voice-settings))

(defn elevenlabs-tts [{:keys [prepared-input] :as sys}]
  (let [{:keys [model output-format voice-id voice-settings]} (elevenlabs-options sys)
        api-key (or (:credential sys)
                    (some-> (elevenlabs-api-key) cloak/mask))
        body (cond-> {"text"     (:speech-input/value prepared-input)
                      "model_id" model}
               (seq voice-settings)
               (assoc "voice_settings"
                      (elevenlabs-voice-settings-body voice-settings)))]
    (assert api-key "ElevenLabs TTS credential is not configured")
    (-> (hc/post (str elevenlabs-tts-url "/" voice-id)
                 {:as           :stream
                  :body         (->json body)
                  :content-type :json
                  :headers      {"xi-api-key" (cloak/unmask api-key)}
                  :http-client  tts-http-client
                  :query-params {"output_format" output-format}
                  :timeout      tts-request-timeout-ms})
        :body)))

(defn caching-elevenlabs-tts
  [{:keys [log-text prepared-input tts-cache-dir] :as sys}]
  (let [options        (elevenlabs-options sys)
        cache-key      [::elevenlabs options (speech/cache-identity prepared-input)]
        suffix         (cache-suffix sys)
        synthesis-mode (or (:synthesis-mode sys) :normal)]
    (if-let [local-url (cache-get tts-cache-dir cache-key suffix)]
      (log-tts-audio-ready! :elevenlabs synthesis-mode :cache log-text local-url)
      (log-tts-audio-ready!
       :elevenlabs
       synthesis-mode
       :synthesized
       log-text
       (cache-input-stream-with-timeout
        tts-cache-dir
        cache-key
        suffix
        (elevenlabs-tts sys)
        tts-cache-timeout-ms)))))

(defn with-db [sys]
  (assoc sys :db (database sys)))

(defn- catalog-credentials [sys]
  (into {}
        (map (fn [provider]
               [provider (effective-credential sys provider)]))
        catalog/remote-providers))

(defn provider-catalog-snapshot
  "Returns normalized remote or built-in catalogs with redacted status."
  [sys]
  (catalog/snapshot (:catalog-store sys) (catalog-credentials sys)))

(defn ensure-provider-catalogs!
  "Schedules eligible stale Google Cloud and ElevenLabs catalog refreshes."
  [sys]
  (catalog/ensure-eligible-fresh! (:catalog-store sys)
                                  (catalog-credentials sys)))

(defn invalidate-provider-catalog!
  "Purges `provider` catalog data and refreshes under the effective credential."
  [sys provider]
  (catalog/invalidate-provider! (:catalog-store sys)
                                provider
                                (catalog-credentials sys)))

(defn- elevenlabs-model-capabilities [sys]
  (when-let [catalog-store (:catalog-store sys)]
    (let [model-id (:model (db/tts-provider-settings (database sys) :elevenlabs))]
      (->> (get-in (catalog/snapshot catalog-store (catalog-credentials sys))
                   [:providers :elevenlabs :catalog :models])
           (some (fn [model]
                   (when (= model-id (:id model))
                     (select-keys model
                                  [:can-use-style?
                                   :can-use-speaker-boost?]))))))))

(defn- synthesize-configurable [sys provider synthesis-mode input]
  (let [model-capabilities (or (:model-capabilities sys)
                               (when (= :elevenlabs provider)
                                 (elevenlabs-model-capabilities sys)))
        {:keys [credential credential-status options warnings]}
        (effective-provider-config sys
                                   provider
                                   synthesis-mode
                                   model-capabilities)
        prepared-input     (speech/prepared-input input provider options)
        request-system     (merge sys
                                  options
                                  {:credential         credential
                                   :credential-status  credential-status
                                   :model-capabilities model-capabilities
                                   :prepared-input     prepared-input
                                   :synthesis-mode     synthesis-mode
                                   :tts-warnings       warnings})]
    ((case provider
       :google-cloud caching-google-cloud-tts
       :openai caching-openai-tts
       :elevenlabs caching-elevenlabs-tts)
     request-system)))

(defn tts
  "Returns the local path to synthesized `input` using normal output settings."
  [sys input]
  (let [sys      (assoc (with-db sys) :log-text (tts-log-text input))
        provider (db/tts-engine (:db sys))]
    (case provider
      :mimic3
      (caching-mimic3-tts
       (assoc sys :prepared-input (speech/prepared-input input provider {})))

      :ha
      (caching-home-assistant-tts
       (assoc sys :prepared-input (speech/prepared-input input provider {})))

      (:google-cloud :openai :elevenlabs)
      (synthesize-configurable sys provider :normal input)

      nil)))

(def preview-cache-basename-pattern
  #"^[A-Za-z0-9_-]+={0,2}\.preview\.opus\.tts-cache$")

(defn browser-preview-tts
  "Synthesizes `text` as Opus and returns its cache file plus safe response metadata."
  [sys text]
  (let [sys      (assoc (with-db sys) :log-text (tts-log-text text))
        provider (db/tts-engine (:db sys))]
    (when-not (some #{provider} configurable-providers)
      (throw (ex-info "The selected TTS engine does not support browser previews"
                      {:provider provider})))
    (let [path     (synthesize-configurable sys provider :browser-preview text)
          basename (.getName (io/file path))]
      (when-not (re-matches preview-cache-basename-pattern basename)
        (throw (ex-info "TTS preview cache returned an invalid file"
                        {:provider provider})))
      {:path         path
       :basename     basename
       :content-type "audio/ogg"})))

(defn preview-cache-file
  "Validates `basename` and returns a contained readable preview cache file."
  [sys basename]
  (if-not (and (string? basename)
               (re-matches preview-cache-basename-pattern basename))
    {:result :malformed}
    (try
      (let [^java.io.File root      (.getCanonicalFile
                                     (io/file (:tts-cache-dir sys)))
            ^java.io.File candidate (io/file root basename)
            ^java.io.File canonical (.getCanonicalFile candidate)
            contained?              (.startsWith (.toPath canonical)
                                                 (.toPath root))]
        (cond
          (not contained?)
          {:result :malformed}

          (or (not (.isFile candidate))
              (not (.canRead candidate)))
          {:result :missing}

          :else
          {:result :ok
           :file   canonical}))
      (catch java.io.IOException _
        {:result :missing}))))

(defn- cleanup-preview-cache! [cache-dir]
  (let [^File cache-root (io/file cache-dir)]
    (->> (.listFiles cache-root)
         (filter (fn [^File file]
                   (and (.isFile file)
                        (re-matches preview-cache-basename-pattern
                                    (.getName file)))))
         (keep (fn [^File file]
                 (try
                   (when (Files/deleteIfExists (.toPath file))
                     file)
                   (catch Exception error
                     (log/warn error
                               "Unable to delete TTS preview cache file"
                               (.getName file))
                     nil))))
         count)))

(defn- start-preview-cache-cleanup! [cache-dir]
  (let [first-cleanup-at (.plus (Instant/now)
                                ^Duration preview-cache-cleanup-period)]
    (chime/chime-at
     (chime/periodic-seq first-cleanup-at
                         preview-cache-cleanup-period)
     (fn [_scheduled-time]
       (cleanup-preview-cache! cache-dir)))))

(defn emit-player! [{:keys [emitter]} event]
  (async/put! emitter {:path "/player/commands" :value event}))

(defn emit-tts! [emitter event]
  (async/put! emitter {:path "/tts/commands" :value event}))

(defn speak-problem! [{:keys [settings] :as sys}]
  (if-let [problem-path (browse/sfx-path settings :tts-problem)]
    (emit-player! sys
                  {:action    :audio/play-one-shot :id :error
                   :item-path problem-path}
                  #_{:action :audio/play-one-shot :id :tts :item-path url})
    (log/error "no tts problem sound found!")))

(defn tts-speak [sys {:keys [text :audio/play-one-shot]}]
  (try
    (assert text)
    (when-let [url (tts sys text)]
      (emit-player! sys
                    (if play-one-shot
                      {:action :audio/play-one-shot :id :tts :item-path url}
                      {:action    :audio/play-path
                       :item-path url
                       :uid       nil})))

    (catch Exception _
      (log/error "TTS speech request failed")
      (speak-problem! sys))))

(defn choose-album [metadata]
  (let [albums (->> metadata
                    (map :album)
                    (map #(some-> % str str/trim not-empty))
                    set)]
    (when (= 1 (count albums))
      (first albums))))

(defn- quoted-title [title]
  (str "\"" title "\""))

(defn metadata->speech [metadata]
  (let [album  (choose-album metadata)
        titles (mapv #(quoted-title (str (or (:title %) ""))) metadata)
        segments
        (into [(speech/text (if album
                              (str "This one is " (quoted-title album))
                              "This one has "))
               (speech/pause 1000)]
              (if (> (count titles) 1)
                (concat
                 (mapcat (fn [[index title]]
                           [(speech/text (str (inc index) ", "))
                            (speech/pause 500)
                            (speech/text title)
                            (speech/pause 500)])
                         (map-indexed vector (butlast titles)))
                 [(speech/text (str " and " (count titles) ", "))
                  (speech/pause 500)
                  (speech/text (last titles))])
                [(speech/text (or (first titles) ""))]))]
    (speech/plan segments)))

(defn tts-track-speech
  [{:keys [title artist album track-number]}
   {:keys [with-artist? with-album? index]
    :or   {with-artist? false
           with-album?  false}}]
  (let [number (or track-number (some-> index inc))]
    (speech/plan
     (cond-> [(speech/text
               (str (when (some? number)
                      (str "Number " number ", "))
                    (quoted-title (or title ""))))]
       (and with-artist? (some-> artist str str/trim not-empty))
       (conj (speech/text (str " by " artist)))
       (and with-album? (some-> album str str/trim not-empty))
       (conj (speech/text
              (str " from the album " (quoted-title album))))))))

(defn tts-track [sys metadata opts]
  (try
    (tts sys (tts-track-speech metadata opts))
    (catch Exception _
      (log/error "TTS track synthesis failed")
      nil)))

(def ^:private error-feedback-types
  #{:card-playback-problem :unknown-card})

(defn- speech-command-enabled? [{:keys [db-conn]} value]
  (or (not (contains? error-feedback-types (:feedback/type value)))
      (db/tts-error-messages? (some-> db-conn deref))))

(defn events-handler! [sys {:keys [value] :as _ev}]
  (condp = (:action value)
    :tts/speak (when (speech-command-enabled? sys value)
                 (tts-speak sys
                            (select-keys value
                                         [:text :audio/play-one-shot])))))

(defn start-tts-loop! [sys listener]
  (async/go-loop []
    (when-some [event (async/<! listener)]
      (try
        (events-handler! sys event)
        (catch Exception _
          (log/error "Encountered exception when handling TTS events")))
      (recur))))

(defn init-tts! [{:keys [bus db-conn settings]}]
  (let [cache-dir (tts-cache-dir settings)]
    (.mkdirs (io/file cache-dir))
    (let [listener              (async/chan)
          emitter               (async/chan)
          catalog-store         (catalog/create-store
                                 {:cache-file (io/file cache-dir "provider-catalogs.edn")
                                  :emitter    emitter})
          preview-cache-cleanup (start-preview-cache-cleanup! cache-dir)
          sys                   {:listener              listener
                                 :emitter               emitter
                                 :db-conn               db-conn
                                 :settings              settings
                                 :tts-cache-dir         cache-dir
                                 :fallback-credentials  (fallback-credentials)
                                 :catalog-store         catalog-store
                                 :preview-cache-cleanup preview-cache-cleanup}]

      (ev/emitize bus emitter)
      (ev/listen bus "/tts/commands" listener)
      (start-tts-loop! sys listener)
      sys)))

(defn stop-tts!
  [{:keys [catalog-store emitter listener preview-cache-cleanup]}]
  (when preview-cache-cleanup
    (.close ^AutoCloseable preview-cache-cleanup))
  (when catalog-store
    (catalog/stop! catalog-store))
  (when emitter
    (async/close! emitter))
  (when listener
    (async/close! listener)))

(def TTSComponent
  {::ds/start  (fn [{config ::ds/config}]
                 (init-tts! config))
   ::ds/stop   (fn [{instance ::ds/instance}]
                 (stop-tts! instance))
   ::ds/config {:bus      (ds/ref [:fairy.box/components
                                   :fairy.box.bus/bus])
                :settings (ds/ref [:fairy.box/components
                                   :fairy.box/settings])
                :db-conn  (ds/ref [:fairy.box/components
                                   :fairy.box.db/db])}})

(comment
  (do
    (require '[fairy.box.system :as system])
    (def sys (system/component :fairy.box.tts/tts)))

  (tts sys "This is literal text, including <speak> and <break>.")

  (tts-speak
   (with-db sys)
   {:text (speech/plan
           [(speech/text "This one is Piglet has a Bath")
            (speech/pause 1000)
            (speech/text "1, In which Kanga and Baby Roo come to the forest")
            (speech/pause 500)
            (speech/text "and 2, In which Tigger comes to the forest")])})

  (async/put! (:emitter sys)
              {:path  "/tts/commands"
               :value {:action :tts/speak
                       :text   "Hello"}})

  :rcf)

(comment
  (require '[ol.vinyl :as vinyl])

  (defn play-tts-test [provider tts-fn args prompt]
    (let [args            (assoc args
                                 :tts-cache-dir
                                 (System/getProperty "java.io.tmpdir"))
          prepared-input  (speech/prepared-input prompt provider args)
          audio-path      (tts-fn (assoc args :prepared-input prepared-input))
          player          (vinyl/create-player)
          playback-result (promise)]
      (try
        (vinyl/subscribe!
         player
         (fn [{:ol.vinyl/keys [event] :as event-data}]
           (when (#{:vlc/finished :vlc/error} event)
             (deliver playback-result event-data)))
         #{:vlc/finished :vlc/error})
        (vinyl/dispatch player :playback/append :paths [audio-path])
        (vinyl/dispatch player :playback/play)
        {:audio-path     audio-path
         :playback-event (:ol.vinyl/event
                          (deref playback-result
                                 30000
                                 {:ol.vinyl/event :timeout}))}
        (finally
          (vinyl/release-player! player)))))

  (play-tts-test
   :openai
   caching-openai-tts
   {:model        "gpt-4o-mini-tts"
    :voice        "marin"
    :instructions "Speak cheerfully."}
   "Hello world")

  (play-tts-test
   :elevenlabs
   caching-elevenlabs-tts
   {:model          "eleven_multilingual_v2"
    :voice-id       "JBFqnCBsd6RMkjVDRZzb"
    :output-format  "mp3_44100_128"
    :voice-settings {:stability        0.5
                     :similarity_boost 0.75
                     :speed            1.0}}
   "Hello from ElevenLabs")

  :rcf)
