(ns fairy.box2.db
  "Read-only access to the existing Fairybox database format."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [fairy.box.db :as box1-db]))

(defn read-db
  "Reads the existing EDN database without retaining it in chart-facing values."
  [path]
  (edn/read-string (slurp (io/file path))))

(defn settings
  "Returns the non-secret operational settings projection used by Box2."
  [db]
  (let [settings (box1-db/settings db)]
    {:audio               (:audio settings)
     :auto-shutdown       (:auto-shutdown settings)
     :led-language?       (:led-language? settings)
     :sleep               (:sleep settings)
     :tts                 (select-keys (:tts settings) [:announce-tracks?])
     :tts-error-messages? (:tts-error-messages? settings)}))

(defn linked-folder
  "Returns the linked folder for `tag-uid`, as in the existing DB schema."
  [db tag-uid]
  (box1-db/linked-folder db tag-uid)
)