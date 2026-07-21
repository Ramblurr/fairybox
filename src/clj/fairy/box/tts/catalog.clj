;; Copyright © 2025 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2
(ns fairy.box.tts.catalog
  "Credential-gated provider catalog discovery and durable safe caching.

  Google Cloud and ElevenLabs refresh independently. Runtime generations,
  workers, and credentials stay in memory; only normalized catalogs and
  redacted refresh metadata are written to disk."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
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
  {:version   cache-version
   :providers {}})

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
  (cond-> (merge {:generation           0
                  :in-flight            nil
                  :consecutive-failures 0}
                 provider-state)
    (:catalog provider-state)
    (assoc :catalog-origin :disk)))

(defn- initial-runtime-state [{:keys [disk-state cache-writable? parse-error]}]
  {:version         cache-version
   :providers       (merge (zipmap remote-providers
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
        ^java.io.File temp-file (io/file parent
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

(defn- persist! [{:keys [state_ cache-file write-lock]}]
  (locking write-lock
    (atomic-write! cache-file (disk-state @state_))))

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

(defn create-store
  "Creates a provider catalog store and loads its safe disk state.

  Options:

  | key           | description
  |---------------|------------
  | `:cache-file` | Catalog EDN path (required)
  | `:request!`   | Injected JSON GET boundary returning a response body
  | `:now`        | Epoch-millisecond clock function
  | `:refresh!`   | Callback after refresh publication"
  [{:keys [cache-file request! now refresh!]
    :or   {now      #(System/currentTimeMillis)
           refresh! (fn [])}}]
  (let [cache-file  (io/file cache-file)
        http-client (hc/build-http-client {:connect-timeout 10000})]
    {:state_     (atom (initial-runtime-state (read-cache-file cache-file)))
     :workers_   (atom #{})
     :cache-file cache-file
     :write-lock (Object.)
     :request!   (or request! (default-request-fn http-client))
     :now        now
     :refresh!   refresh!}))

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
    (merge
     {:catalog              (or remote-catalog fallback)
      :source               (if remote-catalog
                              (:catalog-origin provider-state :disk)
                              :built-in)
      :credential-eligible? eligible?
      :credential-source    credential-source
      :refreshing?          (boolean (:in-flight provider-state))
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
      :last-error           (:last-error provider-state)})))

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

(defn- current-refresh? [state provider generation token]
  (and (not (:stopped? state))
       (= generation (get-in state [:providers provider :generation]))
       (= token (get-in state [:providers provider :in-flight :token]))))

(defn- publish-cache-write-error! [{:keys [state_]}]
  (swap! state_ assoc :parse-error (safe-error :cache-write)))

(defn- notify-refresh! [{:keys [refresh!]}]
  (try
    (refresh!)
    (catch Throwable _
      nil)))

(defn- publish-success!
  [{:keys [state_ now] :as store}
   provider generation token credential-source catalog]
  (let [published?_ (atom false)
        timestamp   (now)]
    (swap! state_
           (fn [state]
             (if (current-refresh? state provider generation token)
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
                                 :credential-source    credential-source
                                 :last-attempt-at      timestamp
                                 :consecutive-failures 0
                                 :retry-at             nil
                                 :last-error           nil
                                 :in-flight            nil})))
               state)))
    (when @published?_
      (try
        (persist! store)
        (catch Throwable _
          (publish-cache-write-error! store)))
      (notify-refresh! store))))

(defn- publish-failure!
  [{:keys [state_ now] :as store}
   provider generation token error]
  (let [published?_  (atom false)
        timestamp    (now)
        safe-failure (categorized-error error)]
    (swap! state_
           (fn [state]
             (if (current-refresh? state provider generation token)
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
                             :last-error           safe-failure
                             :in-flight            nil}))
               state)))
    (when @published?_
      (when (:cache-writable? @state_)
        (try
          (persist! store)
          (catch Throwable _
            (publish-cache-write-error! store))))
      (notify-refresh! store))))

(defn- refresh-worker!
  [{:keys [request!] :as store}
   provider generation token {:keys [credential source]}]
  (try
    (publish-success! store
                      provider
                      generation
                      token
                      source
                      (fetch-provider-catalog request! provider credential))
    (catch Throwable error
      (publish-failure! store provider generation token error))))

(defn- reserve-refresh!
  [{:keys [state_ now] :as store} provider credential force?]
  (let [timestamp (now)
        token     (Object.)]
    (loop []
      (let [state          @state_
            provider-state (get-in state [:providers provider])
            retry-at       (:retry-at provider-state)
            eligible?      (and credential
                                (not (:stopped? state))
                                (nil? (:in-flight provider-state))
                                (or force?
                                    (and (not (fresh? provider-state
                                                      timestamp
                                                      (:source credential)))
                                         (or (nil? retry-at)
                                             (<= retry-at timestamp)))))
            reserved-state (when eligible?
                             (-> state
                                 (assoc-in [:providers provider :last-attempt-at]
                                           timestamp)
                                 (assoc-in [:providers provider :in-flight]
                                           {:token  token
                                            :future nil})))]
        (cond
          (not eligible?) nil

          (compare-and-set! state_ state reserved-state)
          (let [generation (:generation provider-state)
                worker     (future
                             (refresh-worker! store
                                              provider
                                              generation
                                              token
                                              credential))]
            (swap! (:workers_ store)
                   (fn [workers]
                     (conj (set (remove future-done? workers)) worker)))
            (swap! state_
                   (fn [current-state]
                     (if (current-refresh? current-state
                                           provider
                                           generation
                                           token)
                       (assoc-in current-state
                                 [:providers provider :in-flight :future]
                                 worker)
                       current-state)))
            worker)

          :else
          (recur))))))

(defn ensure-eligible-fresh!
  "Schedules one refresh for each eligible stale provider and returns handles."
  [store credentials]
  (into {}
        (keep (fn [provider]
                (when-let [worker (reserve-refresh!
                                   store
                                   provider
                                   (credential-entry credentials provider)
                                   false)]
                  [provider worker])))
        remote-providers))

(defn invalidate-provider!
  "Purges `provider`, advances its generation, and force-refreshes if eligible."
  [{:keys [state_] :as store} provider credentials]
  (when-not (some #{provider} remote-providers)
    (throw (ex-info "Unsupported remote TTS catalog provider"
                    {:provider provider})))
  (swap! state_
         (fn [state]
           (let [generation (inc (get-in state
                                         [:providers provider :generation]
                                         0))]
             (assoc-in state
                       [:providers provider]
                       {:generation           generation
                        :in-flight            nil
                        :consecutive-failures 0}))))
  (when (:cache-writable? @state_)
    (try
      (persist! store)
      (catch Throwable _
        (publish-cache-write-error! store))))
  (reserve-refresh! store
                    provider
                    (credential-entry credentials provider)
                    true))

(defn stop!
  "Stops the store, cancels tracked workers, and rejects late publication."
  [{:keys [state_ workers_]}]
  (swap! state_
         (fn [state]
           (-> state
               (assoc :stopped? true)
               (update :providers
                       update-vals
                       (fn [provider-state]
                         (-> provider-state
                             (update :generation inc)
                             (assoc :in-flight nil)))))))
  (doseq [worker @workers_]
    (future-cancel worker))
  (reset! workers_ #{}))
