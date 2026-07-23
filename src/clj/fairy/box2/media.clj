(ns fairy.box2.media
  "Box2 local-media preparation and its single blocking worker."
  (:require
   [babashka.fs :as fs]
   [clojure.core.async :as async]
   [ol.vinyl :as vinyl]
   [taoensso.trove :as trove]))

(def ^:private job-buffer-size 16)

(defn canonical-path
  "Returns `item-path` below `media-dir`, rejecting paths outside that directory."
  [media-dir item-path]
  (let [base (fs/canonicalize (fs/path media-dir))
        path (fs/canonicalize (fs/path base item-path))]
    (when (fs/starts-with? path base)
      (str path))))

(defn prepare!
  "Expands one linked local folder, file, or playlist through Vinyl metadata parsing."
  [player media-dir item-path]
  (let [path   (or (canonical-path media-dir item-path)
                   (throw (ex-info "Media path is outside the configured media directory"
                                   {:item-path item-path})))
        tracks @(vinyl/parse-meta player [path])]
    (when (instance? Throwable tracks)
      (throw (ex-info "Failed to parse media tracks"
                      {:item-path item-path}
                      tracks)))
    (let [playable (->> tracks
                        (filter #(= :media-parsed-status/done
                                    (:parse-status %)))
                        (filter (comp seq :audio-tracks))
                        (mapv :mrl))]
      (when-not (seq playable)
        (throw (ex-info "Media expansion produced no playable tracks"
                        {:item-path item-path})))
      playable)))

(defn- job-key [{:effect/keys [data]}]
  (select-keys data [:presence-epoch :request-id]))

(defn- stopped-ticket [adapter]
  (if-let [failure @(:fatal_ adapter)]
    {:accepted? false
     :failure   failure
     :reason    :adapter-failed}
    {:accepted? false :reason :stopped}))

(defn- fail-fast! [{:keys [accepting?_ fatal_ jobs] :as adapter} failure]
  (when (compare-and-set! fatal_ nil failure)
    (reset! accepting?_ false)
    (async/close! jobs))
  (stopped-ticket adapter))

(defn- submit-completion! [{:keys [accepting?_ submit!]} event]
  (when @accepting?_
    (submit! event)))

(defn- process-job!
  [{:keys [cancelled_ media-dir player] :as adapter}
   {:effect/keys [data] :as job}]
  (let [key (job-key job)]
    (try
      (when-not (contains? @cancelled_ key)
        (let [paths (prepare! player media-dir (:item-path data))]
          (when-not (contains? @cancelled_ key)
            (submit-completion!
             adapter
             {:name :media.ev/prepared
              :data {:paths          paths
                     :presence-epoch (:presence-epoch data)
                     :request-id     (:request-id data)}}))))
      (catch Exception error
        (trove/log! {:level :error
                     :id    ::preparation-failed
                     :msg   "Box2 media preparation failed"
                     :data  {:error          error
                             :presence-epoch (:presence-epoch data)
                             :request-id     (:request-id data)}})
        (when-not (contains? @cancelled_ key)
          (submit-completion!
           adapter
           {:name :media.ev/preparation-failed
            :data {:error          {:category :media/preparation
                                    :message  (ex-message error)}
                   :presence-epoch (:presence-epoch data)
                   :request-id     (:request-id data)}})))
      (finally
        (swap! cancelled_ disj key)))))

(defn- worker! [{:keys [accepting?_ fatal_ jobs] :as adapter}]
  (async/thread
    (try
      (loop []
        (when-let [job (async/<!! jobs)]
          (process-job! adapter job)
          (recur)))
      (catch Throwable error
        (let [failure {:error  error
                       :reason :worker-infrastructure-failed}]
          (compare-and-set! fatal_ nil failure)
          (reset! accepting?_ false)
          (async/close! jobs)
          (trove/log! {:level :error
                       :id    ::worker-failed
                       :msg   "Box2 media worker failed"
                       :data  failure}))))
    :stopped))

(defn start!
  "Starts the single blocking media-preparation worker.

  Options:

  | key          | description
  | ------------ | -----------
  | `:media-dir` | Canonical root for linked local media
  | `:player`    | Active Vinyl player used for metadata parsing
  | `:submit!`   | Required non-blocking Box2 event submission function"
  [{:keys [media-dir player submit!]}]
  (let [adapter {:accepting?_ (atom true)
                 :cancelled_  (atom #{})
                 :fatal_      (atom nil)
                 :jobs        (async/chan job-buffer-size)
                 :media-dir   media-dir
                 :player      player
                 :submit!     submit!}]
    (assoc adapter :worker (worker! adapter))))

(defn offer!
  "Offers one media effect without blocking."
  [{:keys [accepting?_ cancelled_ jobs] :as adapter}
   {:effect/keys [type] :as effect}]
  (if-not @accepting?_
    (stopped-ticket adapter)
    (case type
      :media.fx/cancel-preparation
      (do
        (swap! cancelled_ conj (job-key effect))
        {:accepted? true})

      :media.fx/prepare
      (if (async/offer! jobs effect)
        {:accepted? true}
        (if @accepting?_
          (fail-fast! adapter
                      {:effect effect
                       :reason :preparation-lane-full})
          (stopped-ticket adapter)))

      {:accepted? false :reason :unsupported-effect})))

(defn fatal
  "Returns the adapter's fatal infrastructure failure, if any."
  [adapter]
  @(:fatal_ adapter))

(defn stop!
  "Stops intake and waits for the current media library call to return."
  [adapter]
  (reset! (:accepting?_ adapter) false)
  (async/close! (:jobs adapter))
  (async/<!! (:worker adapter))
  :stopped)
