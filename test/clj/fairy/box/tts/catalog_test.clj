(ns fairy.box.tts.catalog-test
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [exoscale.cloak :as cloak]
   [fairy.box.tts.catalog :as catalog]))

(defn- google-credentials [secret]
  {:google-cloud {:credential (cloak/mask secret)
                  :source     :database}})

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
        (is (= {:workers   {}
                :requests  0
                :eligible? false
                :source    :built-in}
               (let [workers  (catalog/ensure-eligible-fresh! store {})
                     snapshot (catalog/snapshot store {})]
                 {:workers   workers
                  :requests  @requests_
                  :eligible? (get-in snapshot
                                     [:providers :google-cloud
                                      :credential-eligible?])
                  :source    (get-in snapshot
                                     [:providers :google-cloud :source])})))
        (finally
          (catalog/stop! store))))))

(deftest refreshes-persists-and-expires-google-catalog
  (fs/with-temp-dir [cache-dir {:prefix "fairybox-catalog-success-"}]
    (let [now_        (atom 1000)
          requests_   (atom [])
          cache-file  (fs/file cache-dir "catalog.edn")
          secret      "catalog-probe-key"
          credentials (google-credentials secret)
          store       (catalog/create-store
                       {:cache-file cache-file
                        :now        #(deref now_)
                        :request!   (fn [_ opts]
                                      (swap! requests_ conj
                                             (= secret
                                                (get-in opts
                                                        [:headers
                                                         "X-Goog-Api-Key"])))
                                      google-response)})]
      (try
        (doseq [worker (vals (catalog/ensure-eligible-fresh!
                              store credentials))]
          @worker)
        (let [fresh (catalog/snapshot store credentials)
              disk  (slurp cache-file)]
          (swap! now_ + (inc catalog/catalog-ttl-ms))
          (let [stale         (catalog/snapshot store credentials)
                second-worker (get (catalog/ensure-eligible-fresh!
                                    store credentials)
                                   :google-cloud)]
            @second-worker
            (is (= {:requests         [true true]
                    :fresh-source     :remote
                    :fresh-stale?     false
                    :stale-after-ttl? true
                    :disk-version     catalog/cache-version
                    :secret-absent?   true}
                   {:requests         @requests_
                    :fresh-source     (get-in fresh
                                              [:providers :google-cloud :source])
                    :fresh-stale?     (get-in fresh
                                              [:providers :google-cloud :stale?])
                    :stale-after-ttl? (get-in stale
                                              [:providers :google-cloud :stale?])
                    :disk-version     (:version (edn/read-string disk))
                    :secret-absent?   (not (str/includes? disk secret))}))))
        (finally
          (catalog/stop! store))))))

(deftest backs-off-with-redacted-errors-and-recovers-malformed-cache
  (fs/with-temp-dir [cache-dir {:prefix "fairybox-catalog-failure-"}]
    (let [now_        (atom 5000)
          requests_   (atom 0)
          cache-file  (fs/file cache-dir "catalog.edn")
          _           (spit cache-file "not edn[")
          credentials (google-credentials "failure-probe-key")
          fail?_      (atom true)
          store       (catalog/create-store
                       {:cache-file cache-file
                        :now        #(deref now_)
                        :request!   (fn [_ _]
                                      (swap! requests_ inc)
                                      (if @fail?_
                                        (throw (ex-info "raw provider detail"
                                                        {:status 429
                                                         :secret "must-not-escape"}))
                                        google-response))})]
      (try
        (let [first-worker (get (catalog/ensure-eligible-fresh!
                                 store credentials)
                                :google-cloud)]
          @first-worker)
        (let [failed    (catalog/snapshot store credentials)
              retry-at  (get-in failed
                                [:providers :google-cloud :retry-at])
              blocked   (catalog/ensure-eligible-fresh! store credentials)
              malformed (slurp cache-file)]
          (reset! fail?_ false)
          (reset! now_ retry-at)
          @(get (catalog/ensure-eligible-fresh! store credentials)
                :google-cloud)
          (let [recovered (catalog/snapshot store credentials)
                disk      (slurp cache-file)]
            (is (= {:error-kind           :rate-limited
                    :safe-message         "Provider temporarily rate-limited catalog discovery."
                    :blocked-workers      {}
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
                    :blocked-workers      blocked
                    :requests             @requests_
                    :malformed-preserved? (= "not edn[" malformed)
                    :recovered-source     (get-in recovered
                                                  [:providers :google-cloud :source])
                    :safe-state?          (not (str/includes? (pr-str @(:state_ store))
                                                              "must-not-escape"))
                    :safe-disk?           (not (str/includes? disk
                                                              "must-not-escape"))}))))
        (finally
          (catalog/stop! store))))))
