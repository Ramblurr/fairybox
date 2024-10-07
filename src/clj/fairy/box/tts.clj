(ns fairy.box.tts
  (:import [java.util Base64]
           [java.nio.file Paths])
  (:require
   [clojure.core.async :as async]
   [clojure.string :as str]
   [clojure.java.io :as io]
   [fairy.box.audio.browse :as browse]
   [integrant.core :as ig]
   [jp.nijohando.event :as ev]
   [cheshire.core :as cheshire]
   [clojure.tools.logging :as log]
   [fairy.box.db :as db]
   [hato.client :as hc]))

(def ->json cheshire/generate-string)
(def <-json #(cheshire/parse-string % true))

(defn tts-cache-dir [settings]
  (str (browse/media-dir settings) "/tts-cache"))

(defn hash-text [text]
  (str
   (.encodeToString (Base64/getUrlEncoder) (.getBytes (str (hash text))))
   ".tts-cache"))

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

(defn caching-text->audio-url [{:keys [tts-cache-dir] :as sys} text]
  (let [remote-url (home-assistant-tts sys text)]
    (future (cache-file tts-cache-dir text remote-url))
    remote-url))

(defn text->audio-url [{:keys [db tts-cache-dir] :as sys} text]
  (if-let [local-url (cache-get tts-cache-dir text)]
    local-url
    (caching-text->audio-url sys text)))

(defn emit-player! [{:keys [emitter]} event]
  (async/put! emitter {:path "/player/commands" :value event}))

(defn tts-speak [sys text]
  (when-let [url (text->audio-url sys text)]
    (tap> [:tts-speak url])
    (emit-player! sys
                  {:action :audio/play-path
                   :item-path url
                   :uid nil}
                  #_{:action :audio/play-one-shot :id :tts :item-path url})))

(defn with-db [sys]
  (assoc sys :db @(:db-conn sys)))

(defn events-handler! [sys {:keys [value] :as ev}]
  (condp = (:action value)
    :tts/speak (do (assert (:text value))
                   (tts-speak (with-db sys) (:text value)))))

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

  (tts-speak (with-db sys) "Hello6")

  (async/put! (:emitter sys) {:path "/tts/commands" :value {:action :tts/speak :text "Hello"}})
  ;;
  )
