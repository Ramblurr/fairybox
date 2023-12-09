(ns button2
  (:require
   [clojure.string :as str]
   [clojure.core.async :as async])
  (:import
   [uk.co.caprica.vlcj.player.base MediaPlayer]
   [uk.co.caprica.vlcj.player.component AudioPlayerComponent]
   [uk.co.caprica.vlcj.factory MediaPlayerFactory]
   [uk.co.caprica.vlcj.media ParseFlag MetaData MediaParsedStatus MediaEventListener Media MediaEventAdapter]
   [com.diozero.api GpioPullUpDown]
   [com.diozero.util Diozero]
   [com.diozero.devices LED Button MFRC522]
   [com.diozero.util SleepUtil Hex]))

(comment
  ;; My macro to wrap a list of args into an array for java interop
  (defmacro to-varargs [class-name & args]
    `(into-array ~class-name ~args))

  ;; Let's see what it expands to..
  (macroexpand '(to-varargs String "foo" "bar"))
  ;; => (clojure.core/into-array String ("foo" "bar"))

  ;; Works..
  (into-array String '("foo") )
  ;; => #<[Ljava.lang.String;@ac8eb0f>

  ;; Does not work..
  (to-varargs String "foo" )
;; => Execution error (ClassCastException) at button2/eval49362 (REPL:23).
;;    class java.lang.String cannot be cast to class clojure.lang.IFn (java.lang.String is in module java.base of loader 'bootstrap'; clojure.lang.IFn is in unnamed module of loader 'app')
  )

(defn munge-enum-name [^Enum e]
  (-> e (.name) (str/replace #"\W" "-") (str/replace #"_" "-") (str/lower-case) (keyword)))

(defn extract-metadata-async [ filename]
  (let [factory (MediaPlayerFactory.)
        media (-> factory (.media) (.newMedia filename nil))
        result-promise (promise)
        listener (proxy [MediaEventAdapter] []
                   (mediaParsedChanged [^Media media ^MediaParsedStatus newStatus]
                     (let [metadata (-> media (.meta) (.asMetaData))]
                       (-> media (.events) (.removeMediaEventListener this))
                       (deliver result-promise
                                (reduce (fn [acc [k v]]
                                          (assoc acc (munge-enum-name k) v)) ( :filename filename ) (.values metadata))))))]

    (-> media (.events) (.addMediaEventListener listener))
    (-> media (.parsing) (.parse (into-array ParseFlag [ParseFlag/FETCH_LOCAL])))
    result-promise))

(defn extract-metadata [filename]
  @(extract-metadata-async  filename))

(extract-metadata "/home/admin/test.mp3")

(def audio-player
  (proxy [AudioPlayerComponent] []
    (mediaStateChanged [media newState]
      (prn "State changed to" newState))
    (timeChanged [mediaPlayer newTime]
      (prn "Time changed to" newTime))
    (finished [mediaPlayer]
      (prn "Finished playing media"))
    (error [mediaPlayer]
      (prn "Failed to play media"))))

(defn get-card [^com.diozero.devices.MFRC522 rfid]
  (when (.isNewCardPresent rfid)
    (when-let [uid (.readCardSerial rfid)]
      (.haltA rfid)
      (.stopCrypto1 rfid)
      (Hex/encodeHexString (.getUidBytes uid)))))

(comment
  (def state (atom {:led false :button false :running true}))

  @state
  (swap! state assoc :running true)
  (swap! state assoc :running false)

  (do
    (reset! state {:led false :button false :running true})
    (async/thread
      (prn "pre")
      (with-open [led (LED. 12)
                  button (Button. 27 GpioPullUpDown/PULL_UP)
                  rfid (MFRC522. 0 0 25)]
        (prn "inside")
        (.off led)
        (prn "STARTED")
        (loop []
          (when-let [uid (get-card rfid)]
            (prn "CARD " uid))
          (when (.isPressed button)
            (-> audio-player (.mediaPlayer) (.media) (.play "/home/admin/beep.wav" nil))
            (if (:led @state)
              (do
                (.off led)
                (swap! state assoc :led false))
              (do
                (.on led)
                (swap! state assoc :led true)))
            (prn "BUTTON PRESSED"))
          (if-not (:running @state)
            (do
              (swap! state assoc :done true)
              (prn "DONE"))
            (do
              ;; (prn "LED " (:led @state))
              (SleepUtil/sleepMillis 500)
              (recur)))))))

  (Diozero/shutdown)

  ;;
  )
