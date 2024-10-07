(ns fairy.box.tts
  (:require
   [integrant.core :as ig]
   [jp.nijohando.event :as ev]
   [cheshire.core :as cheshire]
   [clojure.tools.logging :as log]
   [fairy.box.db :as db]
   [hato.client :as hc]))

(def ->json cheshire/generate-string)
(def <-json #(cheshire/parse-string % true))

(defn text->audio-url [{:keys [db]} text]
  (tap> [:db db (db/ha-url db) (db/ha-bearer-token db)])
  (let [api-url (str (db/ha-url db) "/api/tts_get_url")
        bearer-token (db/ha-bearer-token db)]
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

(defn emit-player! [{:keys [emitter]} event]
  (tap> [:emitter emitter])
  (async/put! emitter {:path "/player/commands" :value event}))

(defn tts-speak [sys text]
  (when-let [url (text->audio-url sys text)]
    (emit-player! sys {:action :audio/play-one-shot :id :tts :item-path url})))

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
          (log/error e "Encountered exception when stopping rfid poller")))
      (recur))))

(defn init-tts! [{:keys [bus db-conn] :as opts}]
  (let [listener (async/chan)
        emitter (async/chan)
        sys {:listener listener :emitter emitter :db-conn db-conn}]

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

  (tts-speak (with-db sys) "Hello")

  (when-let [url (text->audio-url nil "This one is \"\"Martin and Sylvia More Adventures\"\", and it has the episodes: 1, Burger night and the no no bug, 2, Butterflies, and 3, Daddy's Toe.")]
    (emit-player! sys {:action :audio/play-one-shot :id :tts :item-path url}))

  (async/put! (:emitter sys) {:path "/tts/commands" :value {:action :tts/speak :text "Hello"}})
  ;;
  )
