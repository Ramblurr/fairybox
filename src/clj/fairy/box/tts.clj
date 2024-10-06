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

(defn emit-player! [emitter event]
  (async/put! emitter {:path "/player/commands" :value event}))

(defmethod ig/init-key ::tts [_ {:keys [bus] :as opts}]
  (log/info "\n-=[starting tts]=-")
  (let [emitter (async/chan)]
    (ev/emitize bus emitter)
    emitter))

(defmethod ig/halt-key! ::tts [_ emitter]
  (log/info "\n-=[goodbye tts]=-")
  (when emitter
    (async/close! emitter)))

(comment

  (do
    (require '[fairy.box.core :as main])
    (require '[integrant.repl.state :as state])
    (def sys (::tts state/system
              ;; @main/system
                    )))
  (def url1 (text->audio-url nil "hello"))

  (when-let [url (text->audio-url nil "This one is \"\"Martin and Sylvia More Adventures\"\", and it has the episodes: 1, Burger night and the no no bug, 2, Butterflies, and 3, Daddy's Toe.")]
    (emit-player! sys {:action :audio/play-one-shot :id :tts :item-path url}))

  ;;
  )
