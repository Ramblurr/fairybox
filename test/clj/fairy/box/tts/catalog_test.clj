(ns fairy.box.tts.catalog-test
  (:require
   [babashka.fs :as fs]
   [clojure.core.async :as async]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [exoscale.cloak :as cloak]
   [fairy.box.tts.catalog :as catalog]))

(defn- google-credentials [secret]
  {:google-cloud {:credential (cloak/mask secret)
                  :source     :database}})

(defn- await-value [channel timeout-ms]
  (let [[value port] (async/alts!! [channel (async/timeout timeout-ms)])]
    (when (= port channel)
      value)))

(defn- await-event [events event]
  (let [timeout (async/timeout 2000)]
    (loop []
      (let [[message port] (async/alts!! [events timeout])]
        (when (= port events)
          (if (= event (get-in message [:value :event]))
            message
            (recur)))))))

(def google-response
  {:voices [{:name                   "de-DE-Neural2-C"
             :languageCodes          ["de-DE"]
             :ssmlGender             "FEMALE"
             :naturalSampleRateHertz 24000}
            {:name                   "en-US-Standard-A"
             :languageCodes          ["en-US" "en-CA"]
             :ssmlGender             "MALE"
             :naturalSampleRateHertz 22050}]})

(deftest normalizes-provider-catalogs
  (is (= {:google
          {:languages ["de-DE" "en-CA" "en-US"]
           :voices    [{:id             "de-DE-Neural2-C"
                        :language-codes ["de-DE"]
                        :gender         :female
                        :sample-rate-hz 24000}
                       {:id             "en-US-Standard-A"
                        :language-codes ["en-CA" "en-US"]
                        :gender         :male
                        :sample-rate-hz 22050}]}
          :models [{:id "eleven_multilingual_v2"
                    :name                   "Multilingual"
                    :languages              ["de" "en"]
                    :can-use-style?         false
                    :can-use-speaker-boost? true}]
          :voices [{:id "voice-1" :name "Alice"}
                   {:id "voice-2" :name "zebra"}]}
         {:google (catalog/normalize-google-voices google-response)
          :models (catalog/normalize-elevenlabs-models
                   [{:model_id "ignored" :can_do_text_to_speech false}
                    {:model_id              "eleven_multilingual_v2"
                     :name                  "Multilingual"
                     :can_do_text_to_speech true
                     :can_use_style         false
                     :can_use_speaker_boost true
                     :languages             [{:language_id "en"}
                                             {:language_id "de"}]}])
          :voices (catalog/normalize-elevenlabs-voices
                   [{:voice_id "voice-2" :name "zebra"}
                    {:voice_id "voice-1" :name "Alice"}])})))

(deftest gates-refreshes-on-credentials
  (fs/with-temp-dir [cache-dir {:prefix "fairybox-catalog-gate-"}]
    (let [requests_ (atom 0)
          store     (catalog/create-store
                     {:cache-file (fs/file cache-dir "catalog.edn")
                      :request!   (fn [_ _]
                                    (swap! requests_ inc)
                                    google-response)})]
      (try
        (catalog/ensure-eligible-fresh! store {})
        (let [snapshot (catalog/snapshot store {})]
          (is (= {:requests  0
                  :eligible? false
                  :source    :built-in}
                 {:requests  @requests_
                  :eligible? (get-in snapshot
                                     [:providers :google-cloud
                                      :credential-eligible?])
                  :source    (get-in snapshot
                                     [:providers :google-cloud :source])})))
        (finally
          (catalog/stop! store))))))

(deftest refreshes-persists-expires-and-emits-lifecycle-events
  (fs/with-temp-dir [cache-dir {:prefix "fairybox-catalog-success-"}]
    (let [now_        (atom 1000)
          requests_   (atom [])
          events      (async/chan 16)
          cache-file  (fs/file cache-dir "catalog.edn")
          secret      "catalog-probe-key"
          credentials (google-credentials secret)
          store       (catalog/create-store
                       {:cache-file cache-file
                        :emitter    events
                        :now        #(deref now_)
                        :request!   (fn [_ opts]
                                      (swap! requests_ conj
                                             (= secret
                                                (get-in opts
                                                        [:headers
                                                         "X-Goog-Api-Key"])))
                                      google-response)})]
      (try
        (catalog/ensure-eligible-fresh! store credentials)
        (let [lifecycle (mapv (fn [_]
                                (some-> (await-value events 1000)
                                        :value
                                        (select-keys [:event
                                                      :operation
                                                      :provider])))
                              (range 3))
              fresh     (catalog/snapshot store credentials)
              disk      (slurp cache-file)]
          (swap! now_ + (inc catalog/catalog-ttl-ms))
          (let [stale (catalog/snapshot store credentials)]
            (catalog/ensure-eligible-fresh! store credentials)
            (await-event events :tts/catalog-updated)
            (is (= {:lifecycle
                    [{:event     :tts/catalog-refresh-queued
                      :operation :refresh
                      :provider  :google-cloud}
                     {:event     :tts/catalog-refresh-started
                      :operation :refresh
                      :provider  :google-cloud}
                     {:event     :tts/catalog-updated
                      :operation :refresh
                      :provider  :google-cloud}]
                    :requests         [true true]
                    :fresh-source     :remote
                    :fresh-stale?     false
                    :stale-after-ttl? true
                    :disk-version     catalog/cache-version
                    :secret-absent?   true}
                   {:lifecycle        lifecycle
                    :requests         @requests_
                    :fresh-source     (get-in fresh
                                              [:providers :google-cloud :source])
                    :fresh-stale?     (get-in fresh
                                              [:providers :google-cloud :stale?])
                    :stale-after-ttl? (get-in stale
                                              [:providers :google-cloud :stale?])
                    :disk-version     (:version (edn/read-string disk))
                    :secret-absent?   (not (str/includes? disk secret))}))))
        (finally
          (catalog/stop! store)
          (async/close! events))))))

(deftest backs-off-with-redacted-errors-and-recovers-malformed-cache
  (fs/with-temp-dir [cache-dir {:prefix "fairybox-catalog-failure-"}]
    (let [now_        (atom 5000)
          requests_   (atom 0)
          events      (async/chan 16)
          cache-file  (fs/file cache-dir "catalog.edn")
          _           (spit cache-file "not edn[")
          credentials (google-credentials "failure-probe-key")
          fail?_      (atom true)
          store       (catalog/create-store
                       {:cache-file cache-file
                        :emitter    events
                        :now        #(deref now_)
                        :request!   (fn [_ _]
                                      (swap! requests_ inc)
                                      (if @fail?_
                                        (throw (ex-info "raw provider detail"
                                                        {:status 429
                                                         :secret "must-not-escape"}))
                                        google-response))})]
      (try
        (catalog/ensure-eligible-fresh! store credentials)
        (await-event events :tts/catalog-refresh-failed)
        (let [failed    (catalog/snapshot store credentials)
              retry-at  (get-in failed
                                [:providers :google-cloud :retry-at])
              malformed (slurp cache-file)]
          (catalog/ensure-eligible-fresh! store credentials)
          (let [blocked-event (await-value events 50)]
            (reset! fail?_ false)
            (reset! now_ retry-at)
            (catalog/ensure-eligible-fresh! store credentials)
            (await-event events :tts/catalog-updated)
            (let [recovered (catalog/snapshot store credentials)
                  disk      (slurp cache-file)]
              (is (= {:error-kind           :rate-limited
                      :safe-message         "Provider temporarily rate-limited catalog discovery."
                      :blocked-event        nil
                      :requests             2
                      :malformed-preserved? true
                      :recovered-source     :remote
                      :safe-state?          true
                      :safe-disk?           true}
                     {:error-kind           (get-in failed
                                                    [:providers :google-cloud
                                                     :last-error :kind])
                      :safe-message         (get-in failed
                                                    [:providers :google-cloud
                                                     :last-error :message])
                      :blocked-event        blocked-event
                      :requests             @requests_
                      :malformed-preserved? (= "not edn[" malformed)
                      :recovered-source     (get-in recovered
                                                    [:providers :google-cloud :source])
                      :safe-state?          (not (str/includes? (pr-str @(:state_ store))
                                                                "must-not-escape"))
                      :safe-disk?           (not (str/includes? disk
                                                                "must-not-escape"))})))))
        (finally
          (catalog/stop! store)
          (async/close! events))))))

(deftest serializes-provider-fetch-jobs
  (fs/with-temp-dir [cache-dir {:prefix "fairybox-catalog-serial-"}]
    (let [events         (async/chan 16)
          release        (promise)
          google-started (promise)
          requests_      (atom [])
          active_        (atom 0)
          peak_          (atom 0)
          request!       (fn [url _]
                           (let [active (swap! active_ inc)]
                             (swap! peak_ max active)
                             (swap! requests_ conj url)
                             (try
                               (cond
                                 (= url catalog/google-voices-url)
                                 (do
                                   (deliver google-started true)
                                   @release
                                   google-response)

                                 (= url catalog/elevenlabs-models-url)
                                 []

                                 (= url catalog/elevenlabs-voices-url)
                                 {:voices [] :has_more false})
                               (finally
                                 (swap! active_ dec)))))
          store          (catalog/create-store
                          {:cache-file (fs/file cache-dir "catalog.edn")
                           :emitter    events
                           :request!   request!})
          credentials    {:google-cloud {:credential (cloak/mask "google")
                                         :source     :database}
                          :elevenlabs   {:credential (cloak/mask "eleven")
                                         :source     :database}}]
      (try
        (catalog/ensure-eligible-fresh! store credentials)
        (deref google-started 1000 false)
        (let [before-release @requests_]
          (deliver release true)
          (await-event events :tts/catalog-updated)
          (await-event events :tts/catalog-updated)
          (is (= {:before-release before-release
                  :requests       [catalog/google-voices-url
                                   catalog/elevenlabs-models-url
                                   catalog/elevenlabs-voices-url]
                  :peak           1
                  :sources        {:google-cloud :remote
                                   :elevenlabs   :remote}}
                 {:before-release before-release
                  :requests       @requests_
                  :peak           @peak_
                  :sources        (-> (catalog/snapshot store credentials)
                                      :providers
                                      (update-vals :source))})))
        (finally
          (deliver release true)
          (catalog/stop! store)
          (async/close! events))))))
