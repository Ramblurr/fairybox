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

  ## Google Cloud (`:google-cloud`)

  [[google-cloud-tts]] reads `[:settings :google-cloud-api-key]`, sends SSML,
  and requests MP3 audio with the fixed `en-US-Polyglot-1` voice and `en-US`
  language code. This provider does not expose a model, voice, speaking-rate,
  pitch, or audio-encoding option through the Fairybox system map.

  ## OpenAI (`:openai`)

  [[openai-tts]] reads `OPENAI_API_KEY` from the environment or `~/.llm-keys`.
  It accepts these keys in the system map:

  | key             | description
  | ----------------|------------
  | `:model`        | Speech model; default `gpt-4o-mini-tts`
  | `:voice`        | Built-in voice; default `marin`
  | `:instructions` | Delivery prompt; default `Speak naturally.`

  Current speech models are `gpt-4o-mini-tts`, `tts-1`, and `tts-1-hd`. Current
  built-in voices are `alloy`, `ash`, `ballad`, `coral`, `echo`, `fable`,
  `nova`, `onyx`, `sage`, `shimmer`, `verse`, `marin`, and `cedar`; voice
  availability varies by model. `:instructions` can direct accent, emotion,
  intonation, speed, tone, or whispering. Output is currently fixed to MP3.
  Fairybox SSML wrappers are converted to plain text before synthesis.

  ## ElevenLabs (`:elevenlabs`)

  [[elevenlabs-tts]] reads `ELEVENLABS_API_KEY` from the environment or
  `~/.llm-keys`. It accepts these keys in the system map:

  | key               | description
  | ------------------|------------
  | `:model`          | Speech model; default `eleven_multilingual_v2`
  | `:voice-id`       | ElevenLabs voice ID; default `JBFqnCBsd6RMkjVDRZzb`
  | `:output-format`  | Codec, sample rate, and bitrate; default `mp3_44100_128`
  | `:voice-settings` | Per-request voice overrides; default uses stored settings

  Current TTS models are `eleven_v3`, `eleven_multilingual_v2`,
  `eleven_flash_v2_5`, and `eleven_flash_v2`. Output formats follow the
  `codec_sample_rate_bitrate` convention and include MP3, Opus, PCM, WAV,
  μ-law, and A-law variants.

  `:voice-settings` accepts `:stability` (API default `0.5`),
  `:similarity_boost` (`0.75`), `:style` (`0`), `:use_speaker_boost` (`true`),
  and `:speed` (`1.0`; lower is slower and higher is faster). Fairybox removes
  its `<speak>` and `<s>` wrappers but preserves `<break>` markup; Eleven v3
  does not support SSML break tags, while the default v2 model does.

  Effective ElevenLabs options are part of the cache key. Requests use bounded
  connect, response, and cache-write timeouts, and streamed audio is published
  atomically so failed or timed-out downloads do not become cache hits.

  ## REPL

  The rich comment form at the bottom of this namespace contains cached OpenAI
  and ElevenLabs synthesis examples that play the result through Vinyl."
  (:require
   [cheshire.core :as cheshire]
   [clojure.core.async :as async]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [donut.system :as ds]
   [fairy.box.audio.browse :as browse]
   [fairy.box.db :as db]
   [hato.client :as hc]
   [hiccup2.core :as h2]
   [jp.nijohando.event :as ev])
  (:import
   [java.nio.file CopyOption Files Paths StandardCopyOption]
   [java.util Base64]))

(def ->json cheshire/generate-string)
(def <-json #(cheshire/parse-string % true))

(def ^:private openai-api-key-pattern
  #"^\s*(?:export\s+)?OPENAI_API_KEY\s*=\s*['\"]?([^'\"\s#]+)['\"]?\s*(?:#.*)?$")
(def ^:private openai-tts-url "https://api.openai.com/v1/audio/speech")
(def ^:private elevenlabs-api-key-pattern
  #"^\s*(?:export\s+)?ELEVENLABS_API_KEY\s*=\s*['\"]?([^'\"\s#]+)['\"]?\s*(?:#.*)?$")
(def ^:private elevenlabs-tts-url "https://api.elevenlabs.io/v1/text-to-speech")
(def ^:private elevenlabs-cache-timeout-ms 60000)
(def ^:private elevenlabs-default-options
  {:model         "eleven_multilingual_v2"
   :output-format "mp3_44100_128"
   :voice-id      "JBFqnCBsd6RMkjVDRZzb"})
(def ^:private elevenlabs-http-client
  (hc/build-http-client {:connect-timeout 10000}))
(def ^:private elevenlabs-request-timeout-ms 30000)

(defn- openai-api-key-from-file []
  (let [key-file (io/file (System/getProperty "user.home") ".llm-keys")]
    (when (.isFile key-file)
      (with-open [reader (io/reader key-file)]
        (some (fn [line]
                (second (re-matches openai-api-key-pattern line)))
              (line-seq reader))))))

(defn- openai-api-key []
  (or (some-> (System/getenv "OPENAI_API_KEY") str/trim not-empty)
      (openai-api-key-from-file)))

(defn- elevenlabs-api-key-from-file []
  (let [key-file (io/file (System/getProperty "user.home") ".llm-keys")]
    (when (.isFile key-file)
      (with-open [reader (io/reader key-file)]
        (some (fn [line]
                (second (re-matches elevenlabs-api-key-pattern line)))
              (line-seq reader))))))

(defn- elevenlabs-api-key []
  (or (some-> (System/getenv "ELEVENLABS_API_KEY") str/trim not-empty)
      (elevenlabs-api-key-from-file)))

(defn- openai-input [text]
  (-> (str text)
      (str/replace #"(?i)<break\b[^>]*>" "\n")
      (str/replace #"(?s)</?[A-Za-z][^>]*>" " ")
      (str/replace "&quot;" "\"")
      (str/replace "&apos;" "'")
      (str/replace "&gt;" ">")
      (str/replace "&lt;" "<")
      (str/replace "&amp;" "&")
      (str/replace #"[ \t]+" " ")
      (str/replace #" *\n *" "\n")
      str/trim))

(defn- elevenlabs-input [text]
  (-> (str text)
      (str/replace #"(?i)</?(?:speak|s)\b[^>]*>" " ")
      (str/replace #"[ \t]+" " ")
      (str/replace #" *\n *" "\n")
      str/trim))

(defn- elevenlabs-options [sys]
  (into elevenlabs-default-options
        (remove (comp nil? val))
        (select-keys sys [:model :output-format :voice-id :voice-settings])))

(defn tts-cache-dir [settings]
  (str (browse/media-dir settings) "/tts-cache"))

(defn hash-text [text]
  (str
   (.encodeToString (Base64/getUrlEncoder) (.getBytes ^String (str (hash text))))
   ".tts-cache"))

(defn b64->input-stream [^String b64]
  (io/input-stream (.decode (Base64/getDecoder) (.getBytes b64))))

(defn cache-get
  "Returns the absolute path for the tts'ed audio of `text` from `cache-dir`, if it exists, otherwise nil"
  [cache-dir text]
  (assert cache-dir "cache-dir must be set")
  (let [maybe-file (.toFile (Paths/get cache-dir (into-array [(hash-text text)])))]
    (when (and (.exists maybe-file) (.canRead maybe-file))
      (.getAbsolutePath maybe-file))))

(defn home-assistant-tts [{:keys [db]} text]
  (let [api-url      (str (db/ha-url db) "/api/tts_get_url")
        bearer-token (db/ha-bearer-token db)]
    (assert api-url "home assistant api url must be set in settings")
    (assert bearer-token "home assistant bearer token must be set in settings")
    (try
      (->
       (hc/post api-url
                {:body         (->json {"message" text "engine_id" "tts.piper"})
                 :content-type :json
                 :headers      {"authorization" (str "Bearer " bearer-token)}})
       :body
       <-json
       :url)
      (catch Exception e
        (log/error "tts failed" e)
        (ex-data e)
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

(defn- write-cache-input-stream! [cache-dir text in publish!]
  (assert cache-dir)
  (let [dest-file (.toFile (Paths/get cache-dir (into-array [(hash-text text)])))
        temp-file (java.io.File/createTempFile ".fairybox-tts-" ".tmp" (io/file cache-dir))]
    (try
      (with-open [^java.io.InputStream input   in
                  ^java.io.OutputStream output (io/output-stream temp-file)]
        (io/copy input output))
      (publish! temp-file dest-file)
      (finally
        (Files/deleteIfExists (.toPath temp-file))))))

(defn cache-input-stream [cache-dir text in]
  (write-cache-input-stream! cache-dir text in move-cache-file!))

(defn- cache-input-stream-with-timeout [cache-dir text in timeout-ms]
  (let [state       (Object.)
        cancelled?_ (atom false)
        result_     (promise)
        worker_     (future
                      (try
                        (write-cache-input-stream!
                         cache-dir
                         text
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

(defn caching-home-assistant-tts [{:keys [tts-cache-dir] :as sys} text]
  (if-let [local-url (cache-get tts-cache-dir text)]
    local-url
    (let [remote-url (home-assistant-tts sys text)]
      (future (cache-file tts-cache-dir text remote-url))
      remote-url)))

(defn mimic3-tts [_sys text]
  (->
   (hc/get "http://10.9.4.3:59125/api/tts"
           {:query-params {"text"        text
                           "voice"       "en_US/cmu-arctic_low#clb"
                           "noiseScale"  "0.677"
                           "noiseW"      "0.8"
                           ;; "lengthScale" "1.2"
                           "ssml"        "true"
                           "audioTarget" "client"}
            :as           :stream})
   :body))

;; http://localhost:59125/api/tts?text=. <break time="500ms" /> In which Tigger comes to the forest and has breakfast&voice=en_US/cmu-arctic_low#clb&noiseScale=0.667&
(defn caching-mimic3-tts [{:keys [tts-cache-dir] :as sys} text]
  (if-let [local-url (cache-get tts-cache-dir text)]
    local-url
    (let [in (mimic3-tts sys text)]
      (cache-input-stream tts-cache-dir text in))))

(defn google-cloud-tts [{:keys [db]} text]
  (let [api-key (db/google-cloud-api-key db)]
    (assert api-key "google cloud api key must be set in settings")
    (->
     (hc/post "https://texttospeech.googleapis.com/v1/text:synthesize"
              {:body (->json {"input"       {"ssml"
                                             (if-not (str/starts-with? text "<")
                                               (str "<speak>" text "</speak>")
                                               text)}
                              "voice"       {"languageCode" "en-US"
                                             "name"         "en-US-Polyglot-1"}
                              "audioConfig" {"audioEncoding" "MP3"}})

               :content-type :json
               :headers      {"X-Goog-Api-Key" api-key}})
     :body
     <-json
     :audioContent
     (b64->input-stream))))

(defn caching-google-cloud-tts [{:keys [tts-cache-dir] :as sys} text]
  #_(let [in (google-cloud-tts sys text)]
      (cache-input-stream tts-cache-dir text in))
  (if-let [local-url (cache-get tts-cache-dir text)]
    local-url
    (let [in (google-cloud-tts sys text)]
      (cache-input-stream tts-cache-dir text in))))

(defn openai-tts
  [{:keys [model voice instructions]
    :or   {model        "gpt-4o-mini-tts"
           voice        "marin"
           instructions "Speak naturally."}}
   text]
  (let [api-key (openai-api-key)]
    (assert api-key "OPENAI_API_KEY must be set in the environment or ~/.llm-keys")
    (-> (hc/post openai-tts-url
                 {:as           :stream
                  :body         (->json {"model"           model
                                         "input"           (openai-input text)
                                         "voice"           voice
                                         "instructions"    instructions
                                         "response_format" "mp3"})
                  :content-type :json
                  :headers      {"Authorization" (str "Bearer " api-key)}})
        :body)))

(defn caching-openai-tts [{:keys [tts-cache-dir] :as sys} text]
  (let [cache-key [::openai
                   (select-keys sys [:model :voice :instructions])
                   text]]
    (if-let [local-url (cache-get tts-cache-dir cache-key)]
      local-url
      (let [in (openai-tts sys text)]
        (cache-input-stream tts-cache-dir cache-key in)))))

(defn elevenlabs-tts [sys text]
  (let [{:keys [model output-format voice-id voice-settings]} (elevenlabs-options sys)
        api-key (elevenlabs-api-key)
        body (cond-> {"text"     (elevenlabs-input text)
                      "model_id" model}
               voice-settings
               (assoc "voice_settings" voice-settings))]
    (assert api-key "ELEVENLABS_API_KEY must be set in the environment or ~/.llm-keys")
    (-> (hc/post (str elevenlabs-tts-url "/" voice-id)
                 {:as           :stream
                  :body         (->json body)
                  :content-type :json
                  :headers      {"xi-api-key" api-key}
                  :http-client  elevenlabs-http-client
                  :query-params {"output_format" output-format}
                  :timeout      elevenlabs-request-timeout-ms})
        :body)))

(defn caching-elevenlabs-tts [{:keys [tts-cache-dir] :as sys} text]
  (let [cache-key [::elevenlabs (elevenlabs-options sys) text]]
    (if-let [local-url (cache-get tts-cache-dir cache-key)]
      local-url
      (let [in (elevenlabs-tts sys text)]
        (cache-input-stream-with-timeout
         tts-cache-dir
         cache-key
         in
         elevenlabs-cache-timeout-ms)))))

(defn with-db [sys]
  (assoc sys :db @(:db-conn sys)))

(defn tts
  "Returns the local path to the file of the tts'ed audio of `text`"
  [sys text]
  (let [sys (with-db sys)]
    (condp = (db/tts-engine (:db sys))
      :mimic3 (caching-mimic3-tts sys text)
      :ha (caching-home-assistant-tts sys text)
      :google-cloud (caching-google-cloud-tts sys text)
      :openai (caching-openai-tts sys text)
      :elevenlabs (caching-elevenlabs-tts sys text))))

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

    (catch Exception e
      (log/error "tts-speak failed" e)
      (speak-problem! sys))))

(defn choose-album [mm]
  (let [albums (->> mm
                    (map :album)
                    (map (fn [a] (when a (str/trim a))))
                    (set))]
    (when (= 1 (count albums))
      (first albums))))

(defn metadata->ssml [metadata]
  (let [album  (choose-album metadata)
        titles (map :title metadata)
        ssml   [:speak
                [:s (if album
                      (str "This one is " album "")
                      "This one has ")]
                [:break {:time "1s"}]
                (if (> (count titles) 1)
                  (concat
                   (map-indexed (fn [i title]
                                  [:s (inc i) ", "
                                   [:break {:time "500ms"}]
                                   title
                                   [:break {:time "500ms"}]]) (butlast titles))
                   [[:s " and " (count titles) ", " [:break {:time "500ms"}] (last titles)]])
                  [:s (first titles)])]]

    (h2/html {:mode :xml} ssml)))

(defn tts-track-text [{:keys [title] :as metadata} {:keys [with-artist? with-album? index] :or {with-artist? false with-album? false}}]
  (let [ssml [:speak
              [:s "Number " (inc index) " "
               title " "]
              (when with-artist?
                [:s " by " (:artist metadata)])
              (when with-album?
                [:s " from the album " (:album metadata)])]]

    (h2/html {:mode :xml} ssml)))

(defn tts-track [sys metadata opts]
  (try
    (let [text          (tts-track-text metadata opts)
          tts-file-path (tts sys text)]
      tts-file-path)
    (catch Exception e
      (log/error e)
      nil)))

(defn events-handler! [sys {:keys [value] :as _ev}]
  (condp = (:action value)
    :tts/speak (tts-speak sys
                          (select-keys value [:text :audio/play-one-shot]))))

(defn start-tts-loop! [sys listener]
  (async/go-loop []
    (when-some [event (async/<! listener)]
      (try
        (events-handler! sys event)
        (catch Exception e
          (log/error e "Encountered exception when handling tts events")))
      (recur))))

(defn init-tts! [{:keys [bus db-conn settings]}]
  (let [listener (async/chan)
        emitter  (async/chan)
        sys      {:listener listener :emitter emitter :db-conn db-conn :settings settings :tts-cache-dir (tts-cache-dir settings)}]

    (.mkdir (io/file (tts-cache-dir settings)))

    (ev/emitize bus emitter)
    (ev/listen bus "/tts/commands" listener)
    (start-tts-loop! sys listener)
    sys))

(defn stop-tts! [{:keys [emitter listener]}]
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
  (def url1 (tts sys "hello"))
  (db/tts-engine (:db (with-db sys)))

  (tts (with-db sys) "This is a test of the tts system")
  ;; rcf

  (tts-speak (with-db sys) {:text "<speak>I can speak in cardinals. Your number is <say-as interpret-as=\"cardinal\">10</say-as>.</speak>"}) ;; rcf
  (tts-speak (with-db sys) {:text "
<speak>
  <s>
This one is Piglet has a Bath
  </s>
  <break time=\"1s\" />
<s>1,<break time=\"500ms\" /> In which Kanga and Baby Roo come to the forest and Piglet has a bath <break time=\"500ms\" /></s>
<s>2,<break time=\"500ms\" /> In which Christopher Robin leads an expotition to the north pole <break time=\"500ms\" /></s>
<s>and 3,<break time=\"500ms\" /> In which Tigger comes to the forest and has breakfast</s>
</speak> "})

  (async/put! (:emitter sys) {:path "/tts/commands" :value {:action :tts/speak :text "Hello"}})

  (caching-mimic3-tts sys "hello there!23")
  ;;
  )

(comment
  (require '[ol.vinyl :as vinyl])

  (defn play-tts-test [tts-fn args prompt]
    (let [audio-path      (tts-fn
                           (assoc args
                                  :tts-cache-dir
                                  (System/getProperty "java.io.tmpdir"))
                           prompt)
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
   caching-openai-tts
   {:model        "gpt-4o-mini-tts"
    :voice        "marin"
    :instructions "Speak cheerfully."}
   "Hello world")

  (play-tts-test
   caching-elevenlabs-tts
   {:model          "eleven_multilingual_v2"
    :voice-id       "JBFqnCBsd6RMkjVDRZzb"
    :output-format  "mp3_44100_128"
    :voice-settings {:stability        0.5
                     :similarity_boost 0.75
                     :speed            1.0}}
   "Hello from ElevenLabs")

  :rcf)
