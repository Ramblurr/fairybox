;; Copyright © 2025 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2
(ns fairy.box.tts.catalog
  "Credential-gated provider catalog discovery and durable safe caching.

  One I/O worker serially consumes catalog jobs. Credentials stay in memory;
  only normalized catalogs and redacted refresh metadata are written to disk."
  (:require
   [babashka.fs :as fs]
   [clojure.core.async :as async]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [exoscale.cloak :as cloak]
   [hato.client :as hc])
  (:import
   [java.nio.file CopyOption Files StandardCopyOption]
   [java.util UUID]))

(def cache-version 1)
(def catalog-ttl-ms (* 24 60 60 1000))
(def remote-providers [:google-cloud :elevenlabs])
(def built-in-catalogs
  {:google-cloud
   {:languages ["en-US"]
    :voices    [{:id             "en-US-Polyglot-1"
                 :language-codes ["en-US"]
                 :gender         :unspecified
                 :sample-rate-hz 24000}]}

   :elevenlabs
   {:models [{:id "eleven_multilingual_v2"
              :name                   "Eleven Multilingual v2"
              :languages              []
              :can-use-style?         true
              :can-use-speaker-boost? true}]
    :voices [{:id   "JBFqnCBsd6RMkjVDRZzb"
              :name "George"}]}})

(def google-voices-url "https://texttospeech.googleapis.com/v1/voices")
(def elevenlabs-models-url "https://api.elevenlabs.io/v1/models")
(def elevenlabs-voices-url "https://api.elevenlabs.io/v2/voices")
(def request-timeout-ms 30000)
(def initial-retry-delay-ms (* 5 60 1000))
(def maximum-retry-delay-ms (* 6 60 60 1000))
(def maximum-elevenlabs-pages 100)
(def safe-provider-keys
  [:fetched-at
   :credential-source
   :last-attempt-at
   :consecutive-failures
   :retry-at
   :last-error
   :catalog])

(defn- nonblank-string [value]
  (let [value (cloak/unmask value)]
    (when (string? value)
      (some-> value str/trim not-empty))))

(defn- normalized-gender [gender]
  (case gender
    "MALE" :male
    "FEMALE" :female
    "NEUTRAL" :neutral
    :unspecified))

(defn normalize-google-voices
  "Normalizes a Google ListVoices response into selector-ready catalog data."
  [response]
  (let [voices (->> (:voices response)
                    (keep (fn [voice]
                            (let [id             (nonblank-string (:name voice))
                                  language-codes (->> (:languageCodes voice)
                                                      (keep nonblank-string)
                                                      distinct
                                                      sort
                                                      vec)]
                              (when (and id (seq language-codes))
                                {:id             id
                                 :language-codes language-codes
                                 :gender         (normalized-gender (:ssmlGender voice))
                                 :sample-rate-hz (:naturalSampleRateHertz voice)}))))
                    (sort-by :id)
                    vec)]
    {:languages (->> voices
                     (mapcat :language-codes)
                     distinct
                     sort
                     vec)
     :voices    voices}))

(defn normalize-elevenlabs-models
  "Keeps ElevenLabs text-to-speech models and their form capabilities."
  [models]
  (->> models
       (filter :can_do_text_to_speech)
       (keep (fn [model]
               (when-let [id (nonblank-string (:model_id model))]
                 {:id id
                  :name                   (or (nonblank-string (:name model)) id)
                  :languages              (->> (:languages model)
                                               (keep (comp nonblank-string :language_id))
                                               distinct
                                               sort
                                               vec)
                  :can-use-style?         (not (false? (:can_use_style model)))
                  :can-use-speaker-boost? (not (false? (:can_use_speaker_boost model)))})))
       (sort-by (juxt (comp str/lower-case :name) :id))
       vec))

(defn- normalized-verified-language [language]
  (when-let [language-id (nonblank-string (:language language))]
    (cond-> {:language language-id
             :model-id (:model_id language)}
      (nonblank-string (:accent language))
      (assoc :accent (:accent language))

      (nonblank-string (:locale language))
      (assoc :locale (:locale language))

      (nonblank-string (:preview_url language))
      (assoc :preview-url (:preview_url language)))))

(defn- normalize-elevenlabs-voice [voice]
  (when-let [id (nonblank-string (:voice_id voice))]
    (cond-> {:id   id
             :name (or (nonblank-string (:name voice)) id)}
      (nonblank-string (:category voice))
      (assoc :category (keyword (:category voice)))

      (nonblank-string (:description voice))
      (assoc :description (:description voice))

      (nonblank-string (:preview_url voice))
      (assoc :preview-url (:preview_url voice))

      (seq (:high_quality_base_model_ids voice))
      (assoc :high-quality-base-model-ids
             (->> (:high_quality_base_model_ids voice)
                  (keep nonblank-string)
                  distinct
                  sort
                  vec))

      (seq (:verified_languages voice))
      (assoc :verified-languages
             (->> (:verified_languages voice)
                  (keep normalized-verified-language)
                  vec)))))

(defn normalize-elevenlabs-voices
  "Normalizes and case-insensitively sorts ElevenLabs voices."
  [voices]
  (->> voices
       (keep normalize-elevenlabs-voice)
       (sort-by (juxt (comp str/lower-case :name) :id))
       vec))

(defn retry-delay-ms
  "Returns exponential provider retry delay for positive `failure-count`."
  [failure-count]
  (let [exponent (min 7 (max 0 (dec failure-count)))
        delay    (* initial-retry-delay-ms
                    (bit-shift-left 1 exponent))]
    (min maximum-retry-delay-ms delay)))

(defn fresh?
  "Returns true when `provider-state` has current data for `credential-source`."
  [provider-state now credential-source]
  (let [fetched-at (:fetched-at provider-state)]
    (and (:catalog provider-state)
         (number? fetched-at)
         (= credential-source (:credential-source provider-state))
         (< (- now fetched-at) catalog-ttl-ms))))

(defn- error-message [kind]
  (case kind
    :unauthorized "Provider rejected the catalog request."
    :rate-limited "Provider temporarily rate-limited catalog discovery."
    :timeout "Provider catalog request timed out."
    :invalid-response "Provider returned an invalid catalog response."
    :cache-read "The provider catalog cache could not be read."
    :cache-write "The provider catalog cache could not be saved."
    "Provider catalog discovery failed."))

(defn- safe-error [kind]
  {:kind    kind
   :message (error-message kind)})

(defn- categorized-error [error]
  (let [data   (ex-data error)
        status (:status data)
        kind   (or (:catalog/error data)
                   (cond
                     (#{401 403} status) :unauthorized
                     (= 429 status) :rate-limited
                     (or (instance? java.net.http.HttpTimeoutException error)
                         (instance? java.util.concurrent.TimeoutException error))
                     :timeout
                     :else :provider-unavailable))]
    (safe-error kind)))

(defn- safe-loaded-error [error]
  (when (map? error)
    (safe-error (if (keyword? (:kind error))
                  (:kind error)
                  :provider-unavailable))))

(defn- safe-provider-state [provider-state]
  (cond-> (select-keys provider-state safe-provider-keys)
    (:last-error provider-state)
    (assoc :last-error (safe-loaded-error (:last-error provider-state)))))

(defn- valid-cache? [value]
  (and (map? value)
       (= cache-version (:version value))
       (map? (:providers value))
       (every? (fn [[provider provider-state]]
                 (and (some #{provider} remote-providers)
                      (map? provider-state)))
               (:providers value))))

(defn- empty-disk-state []
  {:providers {}})

(defn- read-cache-file [^java.io.File cache-file]
  (if-not (.isFile cache-file)
    {:disk-state      (empty-disk-state)
     :cache-writable? true}
    (try
      (let [value (edn/read-string (slurp cache-file))]
        (if (valid-cache? value)
          {:disk-state
           {:version   cache-version
            :providers (update-vals (:providers value) safe-provider-state)}
           :cache-writable? true}
          {:disk-state      (empty-disk-state)
           :cache-writable? false
           :parse-error     (safe-error :cache-read)}))
      (catch Throwable _
        {:disk-state      (empty-disk-state)
         :cache-writable? false
         :parse-error     (safe-error :cache-read)}))))

(defn- runtime-provider-state [provider-state]
  (cond-> (merge {:consecutive-failures 0} provider-state)
    (:catalog provider-state)
    (assoc :catalog-origin :disk)))

(defn- initial-runtime-state [{:keys [disk-state cache-writable? parse-error]}]
  {:providers       (merge (zipmap remote-providers
                                   (repeat (runtime-provider-state {})))
                           (update-vals (:providers disk-state)
                                        runtime-provider-state))
   :cache-writable? cache-writable?
   :parse-error     parse-error
   :stopped?        false})

(defn- disk-state [runtime-state]
  {:version cache-version
   :providers
   (into {}
         (keep (fn [[provider provider-state]]
                 (let [safe-state (safe-provider-state provider-state)]
                   (when (seq safe-state)
                     [provider safe-state]))))
         (:providers runtime-state))})

(defn- atomic-write! [^java.io.File cache-file value]
  (let [^java.io.File parent    (.getParentFile cache-file)
        ^java.io.File temp-file (fs/file parent
                                         (str "." (.getName cache-file) "."
                                              (UUID/randomUUID) ".tmp"))]
    (try
      (spit temp-file (pr-str value))
      (Files/move (.toPath temp-file)
                  (.toPath cache-file)
                  (into-array CopyOption
                              [StandardCopyOption/ATOMIC_MOVE
                               StandardCopyOption/REPLACE_EXISTING]))
      (finally
        (Files/deleteIfExists (.toPath temp-file))))))

(defn- persist! [{:keys [state_ cache-file]}]
  (atomic-write! cache-file (disk-state @state_)))

(defn- default-request-fn [http-client]
  (fn [url opts]
    (:body (hc/get url
                   (merge {:as          :json
                           :http-client http-client
                           :timeout     request-timeout-ms}
                          opts)))))

(defn- fetch-google-catalog [request! credential]
  (-> (request! google-voices-url
                {:headers {"X-Goog-Api-Key" (cloak/unmask credential)}})
      normalize-google-voices))

(defn- fetch-elevenlabs-voices [request! credential]
  (loop [page-token  nil
         seen-tokens #{}
         page-count  0
         voices      []]
    (when (>= page-count maximum-elevenlabs-pages)
      (throw (ex-info "ElevenLabs voice pagination exceeded its limit"
                      {:catalog/error :invalid-response})))
    (let [query-params (cond-> {"page_size"           100
                                "include_total_count" false}
                         page-token
                         (assoc "next_page_token" page-token))
          response     (request! elevenlabs-voices-url
                                 {:headers      {"xi-api-key"
                                                 (cloak/unmask credential)}
                                  :query-params query-params})
          all-voices   (into voices (:voices response))]
      (if-not (:has_more response)
        (normalize-elevenlabs-voices all-voices)
        (let [next-page-token (nonblank-string (:next_page_token response))]
          (when (or (nil? next-page-token)
                    (seen-tokens next-page-token))
            (throw (ex-info "ElevenLabs returned invalid voice pagination"
                            {:catalog/error :invalid-response})))
          (recur next-page-token
                 (conj seen-tokens next-page-token)
                 (inc page-count)
                 all-voices))))))

(defn- fetch-elevenlabs-catalog [request! credential]
  {:models (-> (request! elevenlabs-models-url
                         {:headers {"xi-api-key" (cloak/unmask credential)}})
               normalize-elevenlabs-models)
   :voices (fetch-elevenlabs-voices request! credential)})

(defn fetch-provider-catalog
  "Fetches and normalizes one remote provider catalog through `request!`."
  [request! provider credential]
  (case provider
    :google-cloud (fetch-google-catalog request! credential)
    :elevenlabs (fetch-elevenlabs-catalog request! credential)
    (throw (ex-info "Unsupported remote TTS catalog provider"
                    {:provider provider}))))

(declare ^:private run-jobs!)

(defn create-store
  "Creates a provider catalog store and starts its job worker.

  Options:

  | key           | description
  |---------------|------------
  | `:cache-file` | Catalog EDN path (required)
  | `:emitter`    | TTS event-bus emitter channel
  | `:request!`   | Injected JSON GET boundary returning a response body
  | `:now`        | Epoch-millisecond clock function"
  [{:keys [cache-file emitter request! now]
    :or   {now #(System/currentTimeMillis)}}]
  (let [cache-file  (fs/file cache-file)
        http-client (hc/build-http-client {:connect-timeout 10000})
        jobs        (async/chan 16)
        store       {:state_     (atom (initial-runtime-state
                                        (read-cache-file cache-file)))
                     :cache-file cache-file
                     :emitter    emitter
                     :jobs       jobs
                     :request!   (or request! (default-request-fn http-client))
                     :now        now}
        worker      (async/io-thread (run-jobs! store))]
    (assoc store :worker worker)))

(defn- credential-entry [credentials provider]
  (let [{:keys [credential source]} (get credentials provider)]
    (when-let [credential (and source (nonblank-string credential))]
      {:credential (cloak/mask credential)
       :source     source})))

(defn- provider-snapshot [provider-state fallback now credential]
  (let [eligible?         (boolean credential)
        credential-source (:source credential)
        remote-catalog    (when eligible? (:catalog provider-state))
        fetched-at        (:fetched-at provider-state)]
    {:catalog              (or remote-catalog fallback)
     :source               (if remote-catalog
                             (:catalog-origin provider-state :disk)
                             :built-in)
     :credential-eligible? eligible?
     :credential-source    credential-source
     :stale?               (and remote-catalog
                                (not (fresh? provider-state
                                             now
                                             credential-source)))
     :age-ms               (when (number? fetched-at)
                             (max 0 (- now fetched-at)))
     :fetched-at           fetched-at
     :last-attempt-at      (:last-attempt-at provider-state)
     :consecutive-failures (:consecutive-failures provider-state 0)
     :retry-at             (:retry-at provider-state)
     :last-error           (:last-error provider-state)}))

(defn snapshot
  "Returns a redacted, selector-ready snapshot for `credentials`."
  [store credentials]
  (let [state @(:state_ store)
        now   ((:now store))]
    {:version     cache-version
     :cache-error (:parse-error state)
     :providers
     (into {}
           (map (fn [provider]
                  [provider
                   (provider-snapshot
                    (get-in state [:providers provider])
                    (get built-in-catalogs provider)
                    now
                    (credential-entry credentials provider))]))
           remote-providers)}))

(defn- emit-catalog-event!
  [{:keys [emitter]} {:keys [operation provider]} event]
  (when emitter
    (async/put! emitter
                {:path  "/tts/events"
                 :value {:event     event
                         :operation operation
                         :provider  provider}})))

(defn- publish-cache-write-error! [{:keys [state_]}]
  (swap! state_ assoc :parse-error (safe-error :cache-write)))

(defn- persist-catalog! [store]
  (try
    (persist! store)
    (catch Throwable _
      (publish-cache-write-error! store))))

(defn- refresh-eligible?
  [{:keys [state_ now]} provider credential force?]
  (let [state          @state_
        provider-state (get-in state [:providers provider])
        retry-at       (:retry-at provider-state)
        timestamp      (now)]
    (and credential
         (not (:stopped? state))
         (or force?
             (and (not (fresh? provider-state
                               timestamp
                               (:source credential)))
                  (or (nil? retry-at)
                      (<= retry-at timestamp)))))))

(defn- publish-success!
  [{:keys [state_ now] :as store}
   {:keys [credential provider] :as job}
   catalog]
  (let [published?_ (atom false)
        timestamp   (now)]
    (swap! state_
           (fn [state]
             (if (:stopped? state)
               state
               (do
                 (reset! published?_ true)
                 (-> state
                     (assoc :cache-writable? true
                            :parse-error nil)
                     (update-in [:providers provider]
                                merge
                                {:catalog              catalog
                                 :catalog-origin       :remote
                                 :fetched-at           timestamp
                                 :credential-source    (:source credential)
                                 :last-attempt-at      timestamp
                                 :consecutive-failures 0
                                 :retry-at             nil
                                 :last-error           nil}))))))
    (when @published?_
      (persist-catalog! store)
      (emit-catalog-event! store job :tts/catalog-updated))))

(defn- publish-failure!
  [{:keys [state_ now] :as store}
   {:keys [provider] :as job}
   error]
  (let [published?_  (atom false)
        timestamp    (now)
        safe-failure (categorized-error error)]
    (swap! state_
           (fn [state]
             (if (:stopped? state)
               state
               (let [failure-count (inc (get-in state
                                                [:providers provider
                                                 :consecutive-failures]
                                                0))]
                 (reset! published?_ true)
                 (update-in state
                            [:providers provider]
                            merge
                            {:last-attempt-at      timestamp
                             :consecutive-failures failure-count
                             :retry-at             (+ timestamp
                                                      (retry-delay-ms failure-count))
                             :last-error           safe-failure})))))
    (when @published?_
      (when (:cache-writable? @state_)
        (persist-catalog! store))
      (emit-catalog-event! store job :tts/catalog-refresh-failed))))

(defn- refresh-provider!
  [{:keys [request!] :as store}
   {:keys [credential force? provider] :as job}]
  (if-not (refresh-eligible? store provider credential force?)
    (emit-catalog-event! store job :tts/catalog-refresh-skipped)
    (do
      (emit-catalog-event! store job :tts/catalog-refresh-started)
      (try
        (publish-success! store
                          job
                          (fetch-provider-catalog request!
                                                  provider
                                                  (:credential credential)))
        (catch Throwable error
          (publish-failure! store job error))))))

(defn- purge-provider!
  [{:keys [state_] :as store} provider]
  (let [purged?_ (atom false)]
    (swap! state_
           (fn [state]
             (if (:stopped? state)
               state
               (do
                 (reset! purged?_ true)
                 (assoc-in state
                           [:providers provider]
                           (runtime-provider-state {}))))))
    (when (and @purged?_ (:cache-writable? @state_))
      (persist-catalog! store))
    @purged?_))

(defn- process-job!
  [store {:keys [credential operation] :as job}]
  (case operation
    :refresh
    (refresh-provider! store job)

    :invalidate
    (when (purge-provider! store (:provider job))
      (if credential
        (refresh-provider! store (assoc job :force? true))
        (emit-catalog-event! store job :tts/catalog-updated)))))

(defn- run-jobs! [{:keys [jobs state_] :as store}]
  (loop []
    (when-some [job (async/<!! jobs)]
      (when-not (:stopped? @state_)
        (process-job! store job))
      (recur))))

(defn- enqueue!
  [{:keys [jobs] :as store} job]
  (async/put! jobs
              job
              (fn [queued?]
                (when queued?
                  (emit-catalog-event! store
                                       job
                                       :tts/catalog-refresh-queued))))
  nil)

(defn ensure-eligible-fresh!
  "Queues refresh jobs for eligible stale providers."
  [store credentials]
  (doseq [provider remote-providers
          :let     [credential (credential-entry credentials provider)]
          :when    (refresh-eligible? store provider credential false)]
    (enqueue! store
              {:operation  :refresh
               :provider   provider
               :credential credential}))
  nil)

(defn invalidate-provider!
  "Queues invalidation and refresh of `provider` under the effective credential."
  [store provider credentials]
  (when-not (some #{provider} remote-providers)
    (throw (ex-info "Unsupported remote TTS catalog provider"
                    {:provider provider})))
  (enqueue! store
            {:operation  :invalidate
             :provider   provider
             :credential (credential-entry credentials provider)})

  nil)

(defn stop!
  "Stops accepting catalog jobs and allows the I/O worker to terminate."
  [{:keys [jobs state_ worker]}]
  (swap! state_ assoc :stopped? true)
  (async/close! jobs)
  (when worker
    (async/alts!! [worker (async/timeout 1000)]))
  nil)
