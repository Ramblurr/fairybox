(ns fairy.box.audio.current-test
  (:require
   [clojure.test :refer [deftest is]]
   [fairy.box.audio.current :as current]))

(def tts-track
  {:id   "announcement"
   :mrl  "file:///media/tts-cache/announcement.tts-cache"
   :meta #:meta{:title "announcement.tts-cache"}})

(def introduction
  {:id    "introduction"
   :mrl   "file:///media/Introduction.mp3"
   :index 1
   :meta  #:meta{:title  "Introduction"
                 :artist "The Artist"
                 :album  "The Album"}})

(deftest detects-tts-cache-tracks-test
  (is (= [true false false]
         (mapv current/tts-cache-track?
               [tts-track introduction {:mrl nil}]))))

(deftest projects-physical-queue-for-display-test
  (let [physical-queue [{:id "past" :mrl "file:///media/Past.mp3" :index -1}
                        (assoc tts-track :index 0)
                        introduction
                        (assoc tts-track :id "second-announcement" :index 2)
                        {:id    "tomorrow"
                         :mrl   "file:///media/Tomorrow.mp3"
                         :index 3}]
        display-track  (current/display-track tts-track physical-queue)
        display-queue  (current/display-queue physical-queue)]
    (is (= {:logical-track "introduction"
            :logical-index 1
            :queue-ids     ["past" "introduction" "tomorrow"]
            :queue-indices [-1 1 3]}
           {:logical-track (:id display-track)
            :logical-index (:index display-track)
            :queue-ids     (mapv :id display-queue)
            :queue-indices (mapv :index display-queue)}))))

(deftest leaves-real-current-track-unchanged-test
  (is (= introduction
         (current/display-track introduction
                                [(assoc introduction :index 0)]))))

(deftest gives-orphaned-tts-a-simple-fallback-test
  (let [display-track (current/display-track
                       tts-track
                       [(assoc tts-track :index 0)])]
    (is (= {:mrl  (:mrl tts-track)
            :meta #:meta{:title "TTS"}}
           (select-keys display-track [:mrl :meta])))))
