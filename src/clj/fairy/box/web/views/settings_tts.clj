(ns fairy.box.web.views.settings-tts
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [fairy.box.db :as db]
   [fairy.box.tts :as tts]
   [fairy.box.tts.speech :as speech]
   [fairy.box.web.refresh :as web-refresh]
   [fairy.box.web.views.common :as uic]
   [fairy.box.web.views.ui :as ui]
   [hyperlith.core :as h :refer [defaction defview]]
   [hyperlith.impl.router :as router]
   [shadow.css :refer [css]]))

(def ^:private tts-engine-by-name
  {"ha"           :ha
   "mimic3"       :mimic3
   "google-cloud" :google-cloud
   "openai"       :openai
   "elevenlabs"   :elevenlabs})

(def ^:private tts-provider-by-name
  (select-keys tts-engine-by-name ["google-cloud" "openai" "elevenlabs"]))

(def ^:private remote-tts-providers #{:google-cloud :elevenlabs})
(def ^:private openai-voice-set
  (set (mapcat val tts/openai-voices-by-model)))

(defonce ^:private preview-sequence_ (atom 0))
(def ^:private default-preview-text
  "This one is Frog and Toad Are Friends\n\n• 1, Spring\n• 2, The Story\n• 3, A Lost Button")

(defn- parsed-string [value maximum-length allow-empty?]
  (when (and (string? value)
             (<= (count value) maximum-length)
             (or allow-empty? (not (str/blank? value))))
    [(if allow-empty? value (str/trim value))]))

(defn- parsed-choice [choices value]
  (when (and (string? value) (contains? choices value))
    [value]))

(defn- parsed-number [minimum maximum value]
  (when (and (number? value)
             (Double/isFinite (double value))
             (<= minimum value maximum))
    [(double value)]))

(defn- parsed-boolean [value]
  (when (boolean? value)
    [value]))

(def ^:private tts-setting-specs
  {[:google-cloud "language-code"]
   {:signal :google_language_code
    :path   [:language-code]
    :parse  #(parsed-string % 35 false)}
   [:google-cloud "family"]
   {:signal :google_family
    :parse  #(parsed-string % 128 false)}
   [:google-cloud "voice"]
   {:signal :google_voice
    :path   [:voice]
    :parse  #(parsed-string % 256 false)}
   [:openai "model"]
   {:signal :openai_model
    :path   [:model]
    :parse  #(parsed-choice (set tts/openai-models) %)}
   [:openai "voice"]
   {:signal :openai_voice
    :path   [:voice]
    :parse  #(parsed-choice openai-voice-set %)}
   [:openai "instructions"]
   {:signal :openai_instructions
    :path   [:instructions]
    :parse  #(parsed-string % 4096 true)}
   [:openai "speed"]
   {:signal :openai_speed
    :path   [:speed]
    :parse  #(parsed-number 0.25 4.0 %)}
   [:elevenlabs "model"]
   {:signal :elevenlabs_model
    :path   [:model]
    :parse  #(parsed-string % 256 false)}
   [:elevenlabs "voice-id"]
   {:signal :elevenlabs_voice_id
    :path   [:voice-id]
    :parse  #(parsed-string % 256 false)}
   [:elevenlabs "output-format"]
   {:signal :elevenlabs_output_format
    :path   [:output-format]
    :parse  #(parsed-choice (set tts/elevenlabs-output-formats) %)}
   [:elevenlabs "stability"]
   {:signal :elevenlabs_stability
    :path   [:voice-settings :stability]
    :parse  #(parsed-number 0.0 1.0 %)}
   [:elevenlabs "similarity-boost"]
   {:signal :elevenlabs_similarity_boost
    :path   [:voice-settings :similarity-boost]
    :parse  #(parsed-number 0.0 1.0 %)}
   [:elevenlabs "style"]
   {:signal :elevenlabs_style
    :path   [:voice-settings :style]
    :parse  #(parsed-number 0.0 1.0 %)}
   [:elevenlabs "speaker-boost"]
   {:signal :elevenlabs_speaker_boost
    :path   [:voice-settings :use-speaker-boost]
    :parse  parsed-boolean}
   [:elevenlabs "speed"]
   {:signal :elevenlabs_speed
    :path   [:voice-settings :speed]
    :parse  #(parsed-number 0.7 1.2 %)}})

(def ^:private credential-signal
  {:google-cloud :google_api_key
   :openai       :openai_api_key
   :elevenlabs   :elevenlabs_api_key})

(defn- query-provider [request]
  (get tts-provider-by-name (get-in request [:query-params "provider"])))

(defn- tts-component [{:fairy.box/keys [component]}]
  (component :fairy.box.tts/tts))
(defaction save-tts-engine
  [{:fairy.box/keys [component] :as request}]
  (when-let [engine (get tts-engine-by-name (get-in request [:body :tts_engine]))]
    (db/set-tts-engine! (component :fairy.box.db/db) engine))
  nil)

(defaction save-track-announcements
  [{:fairy.box/keys [component] :keys [body]}]
  (when-let [[announce?] (parsed-boolean (:tts_announce_tracks body))]
    (db/set-announce-tracks! (component :fairy.box.db/db) announce?))
  nil)

(defn- current-provider-catalog [request provider]
  (let [tts-system (tts-component request)]
    (when (:catalog-store tts-system)
      (get-in (tts/provider-catalog-snapshot tts-system)
              [:providers provider :catalog]))))

(defn- google-voices [catalog language-code family]
  (cond->> (:voices catalog)
    language-code
    (filter #(some #{language-code} (:language-codes %)))
    family
    (filter #(= family (speech/google-voice-family (:id %))))))

(defn- google-families [voices]
  (->> voices
       (keep (comp speech/google-voice-family :id))
       distinct
       sort
       vec))

(defn- selected-voice [voices current-voice]
  (if (some #(= current-voice (:id %)) voices)
    current-voice
    (:id (first voices))))

(defn- provider-values [database catalog provider field spec value]
  (let [settings       (db/tts-provider-settings database provider)
        current-voice  (:voice settings)
        current-family (speech/google-voice-family current-voice)
        values         (when-let [path (:path spec)]
                         (assoc-in {} path value))]
    (cond
      (and (= :openai provider) (= "model" field))
      (let [voices (get tts/openai-voices-by-model value)]
        (assoc values :voice (if (some #{current-voice} voices)
                               current-voice
                               (first voices))))

      (and (= :google-cloud provider) (= "language-code" field))
      (let [language-voices (google-voices catalog value nil)
            family-voices   (google-voices catalog value current-family)
            voices          (if (seq family-voices)
                              family-voices
                              language-voices)
            voice           (selected-voice voices current-voice)]
        (cond-> values voice (assoc :voice voice)))

      (and (= :google-cloud provider) (= "family" field))
      (let [voices (google-voices catalog (:language-code settings) value)
            voice  (selected-voice voices current-voice)]
        (cond-> {} voice (assoc :voice voice)))

      :else
      values)))

(defn- google-selection-signals [database]
  (let [{:keys [voice]} (db/tts-provider-settings database :google-cloud)]
    (cond-> {:google_family (or (speech/google-voice-family voice) "")}
      voice (assoc :google_voice voice))))

(defaction save-tts-provider-setting
  [{:fairy.box/keys [component] :as request}]
  (let [provider (query-provider request)
        field    (get-in request [:query-params "field"])
        spec     (get tts-setting-specs [provider field])
        db-conn  (component :fairy.box.db/db)
        catalog  (when (and (= :google-cloud provider)
                            (contains? #{"language-code" "family"} field))
                   (current-provider-catalog request provider))]
    (when spec
      (when-let [[value] ((:parse spec) (get-in request [:body (:signal spec)]))]
        (let [values (provider-values @db-conn catalog provider field spec value)]
          (when (seq values)
            (db/set-tts-provider-values! db-conn provider values))
          (when (and (= :google-cloud provider)
                     (contains? #{"language-code" "family"} field))
            (h/patch-signals (google-selection-signals @db-conn))))))))

(defn- invalidate-catalog! [request provider]
  (when (remote-tts-providers provider)
    (when-let [tts-system (tts-component request)]
      (tts/invalidate-provider-catalog! tts-system provider))))

(defaction replace-tts-credential
  [{:fairy.box/keys [component] :as request}]
  (let [provider (query-provider request)
        signal   (get credential-signal provider)]
    (try
      (when-let [[secret] (and signal
                               (parsed-string (get-in request [:body signal])
                                              4096
                                              false))]
        (db/replace-tts-provider-secret!
         (component :fairy.box.db/db) provider secret)
        (invalidate-catalog! request provider))
      (catch Throwable _
        nil))
    (h/patch-signals (cond-> {} signal (assoc signal "")))))

(defaction clear-tts-credential
  [{:fairy.box/keys [component] :as request}]
  (when-let [provider (query-provider request)]
    (db/clear-tts-provider-secret!
     (component :fairy.box.db/db) provider)
    (invalidate-catalog! request provider))
  nil)

(defaction refresh-tts-catalog [request]
  (when-let [provider (query-provider request)]
    (when (remote-tts-providers provider)
      (invalidate-catalog! request provider)))
  nil)

(defn- preview-target [value]
  (get {"browser" :browser "fairybox" :fairybox "both" :both} value))

(defaction save-tts-preview-target
  [{:fairy.box/keys [component] :keys [body]}]
  (when-let [target (preview-target (:tts_preview_target body))]
    (db/set-tts-preview-target! (component :fairy.box.db/db) target))
  nil)

(defn- emit-fairybox-preview! [tts-system path]
  (when-not path
    (throw (ex-info "The selected TTS engine cannot synthesize previews" {})))
  (tts/emit-player! tts-system
                    {:action    :audio/play-one-shot
                     :id        :tts
                     :item-path path}))

(defn- fairybox-preview! [tts-system text]
  (emit-fairybox-preview! tts-system (tts/tts tts-system text)))

(defaction preview-tts
  [{:fairy.box/keys [component] :keys [body] :as request}]
  (let [target     (preview-target (:tts_preview_target body))
        [text]     (parsed-string (:tts_preview_text body) 5000 false)
        tts-system (tts-component request)
        database   (some-> (component :fairy.box.db/db) deref)]
    (if-not (and target text tts-system
                 (some #{(db/tts-engine database)} tts/configurable-providers))
      (h/patch-signals
       {:tts_preview_url    ""
        :tts_preview_status "Choose a configured cloud provider and enter preview text."})
      (try
        (let [browser-result (when (#{:browser :both} target)
                               (tts/browser-preview-tts tts-system text))
              _              (case target
                               :fairybox (fairybox-preview! tts-system text)
                               :both (emit-fairybox-preview!
                                      tts-system (:path browser-result))
                               nil)
              preview-url    (if browser-result
                               (str "/settings/tts/preview-audio"
                                    (h/url-query-string
                                     {:file (:basename browser-result)}))
                               "")
              status         (case target
                               :browser "Browser preview ready."
                               :fairybox "Preview sent to Fairybox."
                               :both "Browser preview ready and sent to Fairybox.")
              signals        {:tts_preview_url    preview-url
                              :tts_preview_status status}]
          (h/patch-signals
           (cond-> signals
             browser-result
             (assoc :tts_preview_seq (swap! preview-sequence_ inc)))))
        (catch Throwable _
          (h/patch-signals
           {:tts_preview_url    ""
            :tts_preview_status "Preview failed. Check the provider settings and try again."}))))))

(defn tts-preview-audio [{:fairy.box/keys [component] :as request}]
  (let [tts-system (component :fairy.box.tts/tts)
        result     (when tts-system
                     (tts/preview-cache-file
                      tts-system
                      (get-in request [:query-params "file"])))
        headers    {"Cache-Control"           "private, no-store"
                    "Content-Security-Policy" "default-src 'none'"
                    "X-Content-Type-Options"  "nosniff"}]
    (case (:result result)
      :ok
      (let [file (:file result)]
        {:status  200
         :headers (assoc headers
                         "Content-Type" "audio/ogg"
                         "Content-Length" (str (.length ^java.io.File file))
                         "Content-Disposition" "inline; filename=\"preview.opus\"")
         :body    (io/input-stream file)})

      :missing
      {:status  404
       :headers (assoc headers "Cache-Control" "no-store")
       :body    "Preview audio was not found."}

      {:status  400
       :headers (assoc headers "Cache-Control" "no-store")
       :body    "Invalid preview request."})))

(router/add-route! [:get "/settings/tts/preview-audio"] #'tts-preview-audio)

(defn- action-url [action provider & [field]]
  (str "@post('" action
       (h/url-query-string
        (cond-> {:provider (name provider)} field (assoc :field field)))
       "')"))

(defn- select-options [items selected-value]
  (let [options (mapv (fn [{:keys [value label]}]
                        {:value value :label (or label value)})
                      items)]
    (if (some #(= (str selected-value) (str (:value %))) options)
      options
      (conj options {:value selected-value
                     :label (str selected-value " (saved)")}))))

(defn- catalog-status-text
  [{:keys [refreshing? stale? catalog-source last-error
           credential-eligible?]}]
  (cond
    refreshing? "Refreshing provider catalog…"
    last-error (str (:message last-error)
                    (when (= :built-in catalog-source)
                      " Showing built-in fallback options."))
    (and stale? credential-eligible?) "Catalog is stale; refresh is scheduled."
    (= :remote catalog-source) "Provider catalog is current."
    credential-eligible? "Using the built-in catalog until discovery completes."
    :else "Add a credential to discover the provider catalog."))

(defn- settings-card
  [{:keys [id title description show-when class]} & content]
  (into
   [:section (cond-> {:id              id
                      :aria-labelledby (str id "-heading")
                      :class           [(css :rounded-xl :border :border-smoky-300
                                             :bg-white-rock-50 :p-5 :shadow-sm
                                             [:dark :border-smoky-700 :bg-smoky-900]
                                             [:sm :p-6])
                                        class]}
               show-when (assoc :data-show show-when))
    [:div
     [:h3 {:id    (str id "-heading")
           :class (css :text-lg :font-bold :text-smoky-900
                       [:dark :text-smoky-100])}
      title]
     [:p {:class (css :mt-1 :max-w-3xl :text-sm :leading-6 :text-smoky-700
                      [:dark :text-smoky-300])}
      description]]]
   content))

(defn- small-action-button [label action disabled?]
  [:button {:type          "button"
            :disabled      disabled?
            :data-on:click action
            :class         (css :rounded-md :border :border-smoky-300 :px-3 :py-2
                                :text-sm :font-semibold :text-smoky-800
                                [:hover :bg-smoky-100]
                                [:disabled :cursor-not-allowed :opacity-50]
                                [:dark :border-smoky-700 :text-smoky-200])}
   label])

(defn- credential-description [{:keys [configured? source]}]
  (case source
    :database
    "Stored securely in the database. This field clears after every save; paste a new key to replace it."

    :legacy-database
    "Using the legacy database key. Paste a new key to move it into this provider's settings."

    :environment
    "Using an environment variable. Paste a key here to override it in the database."

    :key-file
    "Using ~/.llm-keys. Paste a key here to override it in the database."

    (if configured?
      "A credential is configured. Paste a key to replace it."
      "Paste an API key. The field clears after it is stored securely.")))

(defn- credential-placeholder [{:keys [configured? source]}]
  (case source
    :database "Stored securely in database — paste to replace"
    :legacy-database "Legacy database key active — paste to replace"
    :environment "Environment key active — paste to store an override"
    :key-file "~/.llm-keys key active — paste to store an override"
    (if configured?
      "Credential active — paste to store an override"
      "Paste API key — clears after save")))

(defn- credential-controls [provider status]
  (let [signal             (name (get credential-signal provider))
        configured?        (:configured? status)
        stored-credential? (contains? #{:database :legacy-database}
                                      (:source status))]
    [:div {:class (css :mt-6 :grid :grid-cols-1 :gap-4 [:sm :grid-cols-6])}
     (ui/password-input
      :name (str (name provider) "-api-key")
      :label "API key"
      :description (credential-description status)
      :placeholder (credential-placeholder status)
      :data-bind signal
      :change-action (action-url replace-tts-credential provider))
     [:div {:class (css [:sm :col-span-2] :flex :items-end :gap-2)}
      (small-action-button
       "Clear stored credential"
       (action-url clear-tts-credential provider)
       (not stored-credential?))
      (when (remote-tts-providers provider)
        (small-action-button
         "Refresh catalog"
         (action-url refresh-tts-catalog provider)
         (or (not configured?) (:refreshing? status))))]]))

(defn- provider-card [provider title description status & controls]
  (settings-card
   {:id          (str (name provider) "-tts-settings")
    :title       title
    :description description
    :show-when   (str "$tts_engine === '" (name provider) "'")}
   (when (remote-tts-providers provider)
     [:p {:class (css :mt-4 :text-xs :text-smoky-600
                      [:dark :text-smoky-400])}
      (catalog-status-text status)])
   (credential-controls provider status)
   (into [:div {:class (css :mt-6 :grid :grid-cols-1 :gap-x-6 :gap-y-6
                            [:sm :grid-cols-8])}]
         controls)))

(defn- google-provider-card [settings status]
  (let [catalog         (:catalog status)
        language-code   (:language-code settings)
        current-voice   (:voice settings)
        language-voices (google-voices catalog language-code nil)
        families        (google-families language-voices)
        saved-family    (speech/google-voice-family current-voice)
        family          (if (some #{saved-family} families)
                          saved-family
                          (first families))
        voices          (google-voices catalog language-code family)
        voice-options   (mapv (fn [{:keys [id]}] {:value id :label id}) voices)
        family-options  (mapv #(hash-map :value % :label %) families)
        languages       (mapv (fn [language] {:value language :label language})
                              (:languages catalog))]
    (provider-card
     :google-cloud
     "Google Cloud"
     "Choose the Google Cloud language, voice family, and voice used for speech."
     status
     (ui/select-input
      :name "google-language-code"
      :label "Language"
      :description "Sets pronunciation conventions and filters the available families."
      :selected-value language-code
      :options (select-options languages language-code)
      :data-bind "google_language_code"
      :change-action (action-url save-tts-provider-setting
                                 :google-cloud "language-code"))
     (ui/select-input
      :name "google-family"
      :label "Family"
      :description "Filters voices available for the selected language."
      :selected-value family
      :options (if family
                 (select-options family-options family)
                 family-options)
      :data-bind "google_family"
      :change-action (action-url save-tts-provider-setting
                                 :google-cloud "family"))
     (ui/select-input
      :name "google-voice"
      :label "Voice"
      :description "The Google Cloud voice used for synthesis."
      :selected-value current-voice
      :options (select-options voice-options current-voice)
      :data-bind "google_voice"
      :change-action (action-url save-tts-provider-setting
                                 :google-cloud "voice")))))

(defn- openai-provider-card [settings status]
  (let [model  (:model settings)
        voices (get tts/openai-voices-by-model model
                    (get tts/openai-voices-by-model
                         (:model tts/openai-default-options)))]
    (provider-card
     :openai
     "OpenAI"
     "Choose a speech model and voice, then tune delivery and speed."
     status
     (ui/select-input
      :name "openai-model"
      :label "Model"
      :description "gpt-4o-mini-tts supports instructions; tts-1 favors speed and tts-1-hd favors quality."
      :selected-value model
      :options (mapv #(hash-map :value % :label %) tts/openai-models)
      :data-bind "openai_model"
      :change-action (action-url save-tts-provider-setting :openai "model"))
     (ui/select-input
      :name "openai-voice"
      :label "Voice"
      :description "Controls the speaker identity used for generated speech."
      :selected-value (:voice settings)
      :options (select-options
                (mapv #(hash-map :value % :label %) voices)
                (:voice settings))
      :data-bind "openai_voice"
      :change-action (action-url save-tts-provider-setting :openai "voice"))
     (ui/textarea-input
      :name "openai-instructions"
      :label "Instructions"
      :value (:instructions settings)
      :rows 3
      :description "Describes tone, pacing, accent, or delivery. Used only by gpt-4o-mini-tts."
      :disabled? (not= "gpt-4o-mini-tts" model)
      :data-bind "openai_instructions"
      :change-action (action-url save-tts-provider-setting
                                 :openai "instructions"))
     (ui/range-input
      :name "openai-speed"
      :label "Speed"
      :description "Speech rate from 0.25× to 4×; 1× is normal."
      :value (:speed settings)
      :min 0.25
      :max 4.0
      :step 0.05
      :data-bind "openai_speed"
      :change-action (action-url save-tts-provider-setting :openai "speed")))))

(defn- elevenlabs-provider-card [settings status]
  (let [catalog           (:catalog status)
        models            (:models catalog)
        voices            (:voices catalog)
        model             (:model settings)
        capabilities      (some #(when (= model (:id %)) %) models)
        voice             (:voice-id settings)
        voice-settings    (:voice-settings settings)
        output-format     (:output-format settings)
        output-supported? (some #{output-format} tts/elevenlabs-output-formats)]
    (provider-card
     :elevenlabs
     "ElevenLabs"
     "Choose a model and voice, then tune its supported voice controls."
     status
     (ui/select-input
      :name "elevenlabs-model"
      :label "Model"
      :description "Controls speech quality, latency, languages, and available voice features."
      :selected-value model
      :options (select-options
                (mapv (fn [{:keys [id name]}] {:value id :label name}) models)
                model)
      :data-bind "elevenlabs_model"
      :change-action (action-url save-tts-provider-setting
                                 :elevenlabs "model"))
     (ui/select-input
      :name "elevenlabs-voice"
      :label "Voice"
      :description "Selects an ElevenLabs stock, cloned, or generated speaker voice."
      :selected-value voice
      :options (select-options
                (mapv (fn [{:keys [id name]}] {:value id :label name}) voices)
                voice)
      :data-bind "elevenlabs_voice_id"
      :change-action (action-url save-tts-provider-setting
                                 :elevenlabs "voice-id"))
     (ui/select-input
      :name "elevenlabs-output-format"
      :label "Fairybox output format"
      :description "Sets the device audio codec and bitrate; Opus 128 kbps is the recommended default."
      :selected-value output-format
      :options (select-options
                (mapv #(hash-map :value % :label %)
                      tts/elevenlabs-output-formats)
                output-format)
      :data-bind "elevenlabs_output_format"
      :change-action (action-url save-tts-provider-setting
                                 :elevenlabs "output-format"))
     (when-not output-supported?
       [:p {:class (css :text-sm :text-amber-700 [:sm :col-span-8]
                        [:dark :text-amber-300])}
        "The saved output format is unsupported. Fairybox will use Opus 128 kbps until you choose a supported format."])
     (ui/range-input
      :name "elevenlabs-stability"
      :label "Stability"
      :description "Lower values add variation; higher values are steadier but may sound monotonous."
      :value (:stability voice-settings)
      :min 0.0
      :max 1.0
      :step 0.01
      :data-bind "elevenlabs_stability"
      :change-action (action-url save-tts-provider-setting
                                 :elevenlabs "stability"))
     (ui/range-input
      :name "elevenlabs-similarity-boost"
      :label "Similarity boost"
      :description "Higher values follow the original voice more closely, but may amplify artifacts."
      :value (:similarity-boost voice-settings)
      :min 0.0
      :max 1.0
      :step 0.01
      :data-bind "elevenlabs_similarity_boost"
      :change-action (action-url save-tts-provider-setting
                                 :elevenlabs "similarity-boost"))
     (ui/range-input
      :name "elevenlabs-style"
      :label "Style"
      :description "Amplifies the source voice's speaking style. Higher values can increase latency; 0 disables it."
      :value (:style voice-settings)
      :min 0.0
      :max 1.0
      :step 0.01
      :disabled? (false? (:can-use-style? capabilities))
      :data-bind "elevenlabs_style"
      :change-action (action-url save-tts-provider-setting
                                 :elevenlabs "style"))
     (ui/checkbox-input
      :name "elevenlabs-speaker-boost"
      :label "Speaker boost"
      :description "Improves resemblance to the original speaker at a small processing cost."
      :checked? (:use-speaker-boost voice-settings)
      :disabled? (false? (:can-use-speaker-boost? capabilities))
      :data-bind "elevenlabs_speaker_boost"
      :change-action (action-url save-tts-provider-setting
                                 :elevenlabs "speaker-boost"))
     (ui/range-input
      :name "elevenlabs-speed"
      :label "Speed"
      :description "Speech rate from 0.7× to 1.2×; 1× is normal."
      :value (:speed voice-settings)
      :min 0.7
      :max 1.2
      :step 0.01
      :data-bind "elevenlabs_speed"
      :change-action (action-url save-tts-provider-setting
                                 :elevenlabs "speed")))))

(defn- preview-audio-effect []
  ;; Browser media requests omit Brotli and Hyperlith rejects them. A regular
  ;; fetch succeeds; a data URL is allowed by the page's media-src policy.
  (str
   "if ($tts_preview_seq && $tts_preview_url && "
   "el.dataset.previewSeq !== String($tts_preview_seq)) {"
   "const previewSeq = String($tts_preview_seq);"
   "el.dataset.previewSeq = previewSeq;"
   "fetch($tts_preview_url, {cache: 'no-store'})"
   ".then(response => {"
   "if (!response.ok) throw new Error('Preview audio request failed');"
   "return response.blob();"
   "})"
   ".then(blob => new Promise((resolve, reject) => {"
   "const reader = new FileReader();"
   "reader.onload = () => resolve(reader.result);"
   "reader.onerror = () => reject(reader.error);"
   "reader.readAsDataURL(blob);"
   "}))"
   ".then(src => {"
   "if (el.dataset.previewSeq === previewSeq) {"
   "el.src = src; el.load(); el.play().catch(() => {});"
   "}"
   "})"
   ".catch(() => {"
   "if (el.dataset.previewSeq === previewSeq) {"
   "el.removeAttribute('src'); el.load();"
   "}"
   "});"
   "}"))

(defn- preview-card [selected-target]
  (settings-card
   {:id          "tts-preview"
    :title       "Preview"
    :description "Preview is the only action on this page that may call a paid synthesis API."
    :show-when   "$tts_engine === 'google-cloud' || $tts_engine === 'openai' || $tts_engine === 'elevenlabs'"
    :class       (css :mt-6)}
   [:div {:class (css :mt-6 :grid :grid-cols-1 :gap-6 [:sm :grid-cols-6])}
    (ui/textarea-input
     :name "tts-preview-text"
     :label "Text"
     :description "Sent to the selected provider only when you choose Preview."
     :rows 4
     :value default-preview-text
     :data-bind "tts_preview_text")
    (ui/select-input
     :name "tts-preview-target"
     :label "Play on"
     :description "Fairybox plays on the device; Browser creates an in-page audio player."
     :selected-value (name selected-target)
     :data-bind "tts_preview_target"
     :change-action (str "@post('" save-tts-preview-target "')")
     :options [{:value "browser" :label "Browser"}
               {:value "fairybox" :label "Fairybox"}
               {:value "both" :label "Both"}])]
   [:div {:class (css :mt-4 :flex :flex-wrap :items-center :gap-4)}
    (small-action-button
     "Preview"
     (str "@post('" preview-tts "')")
     false)
    [:p {:data-text "$tts_preview_status"
         :class     (css :text-sm :text-smoky-700 [:dark :text-smoky-300])}]]
   [:audio {:controls    true
            :preload     "metadata"
            :data-show   "$tts_preview_url"
            :data-effect (preview-audio-effect)
            :class       (css :mt-4 :w-full)}]))

(defn- page-signals [settings]
  (let [google     (get-in settings [:providers :google-cloud])
        openai     (get-in settings [:providers :openai])
        elevenlabs (get-in settings [:providers :elevenlabs])
        voice      (:voice-settings elevenlabs)]
    {:tts_announce_tracks         (:announce-tracks? settings)
     :tts_engine                  (name (:engine settings))
     :google_language_code        (:language-code google)
     :google_family               (or (speech/google-voice-family (:voice google)) "")
     :google_voice                (:voice google)
     :google_api_key              ""
     :openai_model                (:model openai)
     :openai_voice                (:voice openai)
     :openai_instructions         (:instructions openai)
     :openai_speed                (:speed openai)
     :openai_api_key              ""
     :elevenlabs_model            (:model elevenlabs)
     :elevenlabs_voice_id         (:voice-id elevenlabs)
     :elevenlabs_output_format    (:output-format elevenlabs)
     :elevenlabs_stability        (:stability voice)
     :elevenlabs_similarity_boost (:similarity-boost voice)
     :elevenlabs_style            (:style voice)
     :elevenlabs_speaker_boost    (:use-speaker-boost voice)
     :elevenlabs_speed            (:speed voice)
     :elevenlabs_api_key          ""
     :tts_preview_text            default-preview-text
     :tts_preview_target          (name (:preview-target settings))
     :tts_preview_url             ""
     :tts_preview_seq             0
     :tts_preview_status          ""}))

(defn tts-settings [{:fairy.box/keys [component] :as request}]
  (let [db-conn             (component :fairy.box.db/db)
        tts-system          (component :fairy.box.tts/tts)
        refresh-event       (some-> (component :fairy.box.web/refresh)
                                    web-refresh/current-event
                                    :value)
        refreshing-provider (when (= :tts/catalog-refresh-started
                                     (:event refresh-event))
                              (:provider refresh-event))
        _                   (when tts-system
                              (tts/ensure-provider-catalogs! tts-system))
        catalogs            (when tts-system
                              (tts/provider-catalog-snapshot tts-system))
        settings            (db/tts-settings @db-conn)
        providers           (:providers settings)
        statuses            (into {}
                                  (map (fn [provider]
                                         (let [catalog-status
                                               (get-in catalogs [:providers provider])]
                                           [provider
                                            (merge
                                             catalog-status
                                             {:catalog-source (:source catalog-status)
                                              :refreshing?
                                              (= provider refreshing-provider)}
                                             (when tts-system
                                               (tts/credential-status
                                                tts-system provider)))])))
                                  tts/configurable-providers)]
    [:div {:id "active-tab"}
     [:div {:class                   [ui/$page-margin
                                      (css :mx-auto :max-w-5xl)]
            :data-signals__ifmissing (h/edn->json (page-signals settings))}
      [:div
       [:h2 {:class (css :text-2xl :font-bold :text-smoky-900
                         [:dark :text-smoky-100])}
        "Text to Speech"]
       [:p {:class (css :mt-1 :max-w-3xl :text-sm :leading-6 :text-smoky-700
                        [:dark :text-smoky-300])}
        "Settings save immediately. Credentials are never displayed after entry."]]
      [:div {:class (css :mt-6)}
       (settings-card
        {:id          "track-announcement-settings"
         :title       "Track announcements"
         :description "Control spoken track titles during normal card playback."}
        [:div {:class (css :mt-4)}
         (ui/checkbox-input
          :name "tts-announce-tracks"
          :label "Announce tracks before playback"
          :description "Speak each track title before it plays. Card identification mode is unaffected."
          :checked? (:announce-tracks? settings)
          :data-bind "tts_announce_tracks"
          :change-action (str "@post('" save-track-announcements "')"))])]
      [:div {:class (css :mt-6)}
       (ui/select-input
        :name "tts-engine"
        :label "Active provider"
        :description "Selects the TTS service and shows only its settings."
        :selected-value (name (:engine settings))
        :options [{:value "google-cloud" :label "Google Cloud"}
                  {:value "openai" :label "OpenAI"}
                  {:value "elevenlabs" :label "ElevenLabs"}
                  {:value "ha" :label "Home Assistant"}
                  {:value "mimic3" :label "Mimic 3"}]
        :data-bind "tts_engine"
        :change-action (str "@post('" save-tts-engine "')"))]
      [:div {:class (css :mt-6 :space-y-6)}
       (google-provider-card (get providers :google-cloud)
                             (get statuses :google-cloud))
       (openai-provider-card (get providers :openai)
                             (get statuses :openai))
       (elevenlabs-provider-card (get providers :elevenlabs)
                                 (get statuses :elevenlabs))
       (preview-card (:preview-target settings))]
      [:section {:data-show "$tts_engine === 'ha' || $tts_engine === 'mimic3'"
                 :class     (css :rounded-xl :border :border-smoky-300
                                 :bg-white-rock-50 :p-6 :text-sm :text-smoky-700
                                 [:dark :border-smoky-700 :bg-smoky-900
                                  :text-smoky-300])}
       "Home Assistant and Mimic 3 use their existing fixed configuration; there are no additional settings here."]
      [:div {:class (css :mt-6 :flex :justify-end)}
       (ui/button :tag :a
                  :href ((:url-for request) :page/settings)
                  :priority :link
                  :label "Back")]]]))

(defview render-tts {:path "/settings/tts" :shim-headers ui/shim-headers}
  [request]
  (h/html
   (ui/css-reload)
   [:main#morph.main
    [:div {}
     (uic/player-tabs request :page/settings)
     (tts-settings request)]]))

(h/refresh-all!)
