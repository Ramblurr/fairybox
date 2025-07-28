(ns fairy.box.tts
  (:require
   [cheshire.core :as cheshire]
   [clojure.core.async :as async]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [fairy.box.audio :as audio]
   [fairy.box.audio.browse :as browse]
   [fairy.box.db :as db]
   [hato.client :as hc]
   [hiccup2.core :as h2]
   [integrant.core :as ig]
   [jp.nijohando.event :as ev])
  (:import
   (java.nio.file Paths)
   (java.util Base64)))

(def ->json cheshire/generate-string)
(def <-json #(cheshire/parse-string % true))

(defn tts-cache-dir [settings]
  (str (browse/media-dir settings) "/tts-cache"))

(defn hash-text [text]
  (str
   (.encodeToString (Base64/getUrlEncoder) (.getBytes (str (hash text))))
   ".tts-cache"))

(defn b64->input-stream [b64]
  (io/input-stream (.decode (Base64/getDecoder) (.getBytes b64))))

(defn cache-get
  "Returns the absolute path for the tts'ed audio of `text` from `cache-dir`, if it exists, otherwise nil"
  [cache-dir text]
  (assert cache-dir "cache-dir must be set")
  (let [maybe-file (.toFile (Paths/get cache-dir (into-array [(hash-text text)])))]
    (when (and (.exists maybe-file) (.canRead maybe-file))
      (.getAbsolutePath maybe-file))))

(defn home-assistant-tts [{:keys [db]} text]
  (let [api-url (str (db/ha-url db) "/api/tts_get_url")
        bearer-token (db/ha-bearer-token db)]
    (assert api-url "home assistant api url must be set in settings")
    (assert bearer-token "home assistant bearer token must be set in settings")
    (try
      (->
       (hc/post api-url
                {:body (->json {"message" text "engine_id" "tts.piper"})
                 :content-type :json
                 :headers {"authorization" (str "Bearer " bearer-token)}})
       :body
       <-json
       :url)
      (catch Exception e
        (log/error "tts failed" e)
        (ex-data e)
        nil))))

(defn cache-file [cache-dir text remote-url]
  (assert cache-dir)

  (with-open [in (io/input-stream remote-url)
              out (io/output-stream (.toFile (Paths/get cache-dir (into-array [(hash-text text)]))))]
    (io/copy in out)))

(defn cache-input-stream [cache-dir text in]
  (assert cache-dir)
  (let [dest-file (.toFile (Paths/get cache-dir (into-array [(hash-text text)])))]
    (with-open [out (io/output-stream dest-file)]
      (io/copy in out))
    (.getAbsolutePath dest-file)))

(defn caching-home-assistant-tts  [{:keys [db tts-cache-dir] :as sys} text]
  (if-let [local-url (cache-get tts-cache-dir text)]
    local-url
    (let [remote-url (home-assistant-tts sys text)]
      (future (cache-file tts-cache-dir text remote-url))
      remote-url)))

(defn mimic3-tts [sys text]
  (->
   (hc/get "http://10.9.4.3:59125/api/tts"
           {:query-params {"text" text
                           "voice" "en_US/cmu-arctic_low#clb"
                           "noiseScale" "0.677"
                           "noiseW" "0.8"
                           ;; "lengthScale" "1.2"
                           "ssml" "true"
                           "audioTarget" "client"}
            :as :stream})
   :body))

;; http://localhost:59125/api/tts?text=. <break time="500ms" /> In which Tigger comes to the forest and has breakfast&voice=en_US/cmu-arctic_low#clb&noiseScale=0.667&
(defn caching-mimic3-tts [{:keys [tts-cache-dir] :as sys} text]
  (if-let [local-url (cache-get tts-cache-dir text)]
    local-url
    (let [in (mimic3-tts sys text)]
      (cache-input-stream tts-cache-dir text in))))

(defn google-cloud-tts [{:keys [db]} text]
  (let [api-key (db/google-cloud-api-key db)]
    (assert api-key)
    (->
     (hc/post "https://texttospeech.googleapis.com/v1/text:synthesize"
              {:body (->json {"input" {"ssml"
                                       (str "<speak>" text "</speak>")}
                              "voice" {"languageCode" "en-US"
                                       "name" "en-US-Polyglot-1"}
                              "audioConfig" {"audioEncoding" "MP3"}})

               :content-type :json
               :headers {"X-Goog-Api-Key" api-key}})
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

(defn with-db [sys]
  (assoc sys :db @(:db-conn sys)))

(defn tts
  "Returns the local path to the file of the tts'ed audio of `text`"
  [sys text]
  (let [sys (with-db sys)]
    (condp = (db/tts-engine (:db sys))
      :mimic3 (caching-mimic3-tts sys text)
      :ha (caching-home-assistant-tts sys text)
      :google-cloud (caching-google-cloud-tts sys text))))

(defn emit-player! [{:keys [emitter]} event]
  (async/put! emitter {:path "/player/commands" :value event}))

(defn emit-tts! [emitter event]
  (async/put! emitter {:path "/tts/commands" :value event}))

(defn speak-problem! [{:keys [settings] :as sys}]
  (if-let [problem-path (browse/sfx-path settings :tts-problem)]
    (emit-player! sys
                  {:action :audio/play-one-shot :id :error
                   :item-path problem-path}
                  #_{:action :audio/play-one-shot :id :tts :item-path url})
    (log/error "no tts problem sound found!")))

(defn tts-speak [sys text]
  (try
    (assert text)
    (when-let [url (tts sys text)]
      (emit-player! sys
                    #_{:action :audio/play-path
                       :item-path url
                       :uid nil}
                    {:action :audio/play-one-shot :id :tts :item-path url}))

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
  (let [album (choose-album metadata)
        titles (map :title metadata)
        text (if album
               (str "This one is " album "")
               (str "This one has "))
        ssml [:speak
              [:s (if album
                    (str "This one is " album "")
                    (str "This one has "))]
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

    (str (h2/html {:mode :xml} ssml))))

(defn speak-card-contents [{:keys [emitter] :as sys} item-path]
  (let [metadata (audio/metadata-for sys item-path)
        text (metadata->ssml metadata)]
    (tap> [:card-contents metadata text])
    (emit-tts! emitter {:action :tts/speak :text text})))

(defn tts-track-text [{:keys [title] :as metadata} {:keys [with-artist? with-album? index] :or {with-artist? false with-album? false}}]
  (let [ssml [:speak
              [:s "Number " (inc index) " "
               title " "]
              (when with-artist?
                [:s " by " (:artist metadata)])
              (when with-album?
                [:s " from the album " (:album metadata)])]]

    (str (h2/html {:mode :xml} ssml))))

(defn tts-track [sys metadata opts]
  (try
    (let [text (tts-track-text metadata opts)
          tts-file-path (tts sys text)]
      tts-file-path)
    (catch Exception e
      (log/error e)
      nil)))

(defn events-handler! [sys {:keys [value] :as ev}]
  (condp = (:action value)
    :tts/speak (tts-speak sys (:text value))))

(defn start-tts-loop! [sys listener]
  (async/go-loop []
    (when-some [event (async/<! listener)]
      (try
        (events-handler! sys event)
        (catch Exception e
          (log/error e "Encountered exception when handling tts events")))
      (recur))))

(defn init-tts! [{:keys [bus db-conn settings] :as opts}]
  (let [listener (async/chan)
        emitter (async/chan)
        sys {:listener listener :emitter emitter :db-conn db-conn :settings settings :tts-cache-dir (tts-cache-dir settings)}]

    (.mkdir (io/file (tts-cache-dir settings)))

    (ev/emitize bus emitter)
    (ev/listen bus "/tts/commands" listener)
    (start-tts-loop! sys listener)
    sys))

(defmethod ig/init-key ::tts [_ opts]
  (log/info "\n-=[starting tts]=-")
  (init-tts! opts))

(defmethod ig/halt-key! ::tts [_ {:keys [emitter listener]}]
  (log/info "\n-=[goodbye tts]=-")
  (when emitter
    (async/close! emitter))
  (when listener
    (async/close! listener)))

(comment

  (do
    (require '[fairy.box.core :as main])
    (require '[integrant.repl.state :as state])
    #_(def db-conn (:fairy.box.db/db state/system))
    (def sys (::tts state/system
                    ;; @main/system
                    )))
  (def url1 (text->audio-url nil "hello"))
  (db/tts-engine (:db (with-db sys)))

  (tts (with-db sys) "This is a test of the tts system")
  ;; rcf

  (tts-speak (with-db sys) "This is a test of the tts system wow. WOW!") ;; rcf
  (tts-speak (with-db sys) "
<speak>
  <s>
This one is Piglet has a Bath
  </s>
  <break time=\"1s\" />
<s>1,<break time=\"500ms\" /> In which Kanga and Baby Roo come to the forest and Piglet has a bath <break time=\"500ms\" /></s>
<s>2,<break time=\"500ms\" /> In which Christopher Robin leads an expotition to the north pole <break time=\"500ms\" /></s>
<s>and 3,<break time=\"500ms\" /> In which Tigger comes to the forest and has breakfast</s>
</speak> ")

  (async/put! (:emitter sys) {:path "/tts/commands" :value {:action :tts/speak :text "Hello"}})

  (caching-mimic3-tts sys "hello there!23")
  ;;
  )
