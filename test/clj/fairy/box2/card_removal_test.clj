(ns fairy.box2.card-removal-test
  (:require
   [clojure.test :refer [deftest is]]
   [fairy.box2.runtime :as runtime]))

(def ^:private base-settings
  {:audio         {:card-removal-behavior :pause
                   :card-return-behavior  :resume}
   :auto-shutdown {:enabled? false}
   :tts           {:announce-tracks? false}})

(defn- effect-types [receipt]
  (mapv :effect/type (:effects receipt)))

(defn- active? [snapshot state-id]
  (contains? (set (:configuration snapshot)) state-id))

(defn- start-run! [settings]
  (let [run (runtime/start! (constantly nil))]
    (runtime/submit-and-await! run {:name :system.ev/initialized
                                    :data {:settings          settings
                                           :settings-revision 0}})
    run))

(defn- observe-present! [run observation-seq presence-epoch uid]
  (runtime/submit-and-await! run {:name :rfid.ev/presence-observed
                                  :data {:item-path       "story"
                                         :observation-seq observation-seq
                                         :presence-epoch  presence-epoch
                                         :request-id      (random-uuid)
                                         :status          :present
                                         :uid             uid}}))

(defn- observe-absent! [run observation-seq presence-epoch]
  (runtime/submit-and-await! run {:name :rfid.ev/presence-observed
                                  :data {:observation-seq observation-seq
                                         :presence-epoch  presence-epoch
                                         :status          :absent}}))

(defn- finish-preparation! [run placement]
  (let [prepare-data (get-in placement [:effects 0 :effect/data])]
    (runtime/submit-and-await!
     run
     {:name :media.ev/prepared
      :data (assoc (select-keys prepare-data
                                [:generation :request-id :settings-revision])
                   :paths ["/story.mp3"])})))

(defn- start-active! [settings uid]
  (let [run (start-run! settings)]
    (try
      (let [placement (observe-present! run 1 1 uid)
            prepared  (finish-preparation! run placement)
            context   (get-in prepared
                              [:effects 0 :effect/data :playback-context])]
        (runtime/submit-and-await! run {:name :player.ev/queue-installed
                                        :data {:playback-context context}})
        (runtime/submit-and-await! run {:name :player.ev/state-changed
                                        :data {:playback-context context
                                               :state            :opening}})
        {:context    context
         :request-id (get-in (runtime/snapshot run)
                             [:data :audio :active-request :request-id])
         :run        run})
      (catch Throwable error
        (runtime/stop! run)
        (throw error)))))

(deftest paused-card-resumes-from-new-presence-epoch-test
  (let [{:keys [request-id run]} (start-active! base-settings "CARD-A")]
    (try
      (let [removed  (observe-absent! run 2 1)
            returned (observe-present! run 3 2 "CARD-A")]
        (is (= {:removed  {:effects       [:player.fx/pause]
                           :removed-data? false
                           :state         :suspended}
                :returned {:active-epoch 2
                           :effects      [:player.fx/resume]
                           :request-id   request-id
                           :state        :active}}
               {:removed  {:effects       (effect-types removed)
                           :removed-data? (contains? (get-in removed [:snapshot :data :audio])
                                                     :removed-card)
                           :state         (when (active? (:snapshot removed)
                                                         :card-request.st/suspended)
                                            :suspended)}
                :returned {:active-epoch (get-in returned
                                                 [:snapshot :data :audio
                                                  :active-request :presence-epoch])
                           :effects      (effect-types returned)
                           :request-id   (get-in returned
                                                 [:snapshot :data :audio
                                                  :active-request :request-id])
                           :state        (when (active? (:snapshot returned)
                                                        :card-request.st/active)
                                           :active)}})))
      (finally
        (runtime/stop! run)))))

(deftest keep-playing-return-retains-request-test
  (let [settings                 (assoc-in base-settings
                                           [:audio :card-removal-behavior]
                                           :keep-playing)
        {:keys [request-id run]} (start-active! settings "CARD-A")]
    (try
      (let [removed  (observe-absent! run 2 1)
            returned (observe-present! run 3 2 "CARD-A")]
        (is (= {:removed  {:active? true :effects []}
                :returned {:active-epoch 2
                           :active?      true
                           :effects      []
                           :request-id   request-id}}
               {:removed  {:active? (active? (:snapshot removed)
                                             :card-request.st/active)
                           :effects (effect-types removed)}
                :returned {:active-epoch (get-in returned
                                                 [:snapshot :data :audio
                                                  :active-request :presence-epoch])
                           :active?      (active? (:snapshot returned)
                                                  :card-request.st/active)
                           :effects      (effect-types returned)
                           :request-id   (get-in returned
                                                 [:snapshot :data :audio
                                                  :active-request :request-id])}})))
      (finally
        (runtime/stop! run)))))

(deftest restart-return-creates-pending-request-test
  (let [settings                 (assoc-in base-settings
                                           [:audio :card-return-behavior]
                                           :restart)
        {:keys [request-id run]} (start-active! settings "CARD-A")]
    (try
      (observe-absent! run 2 1)
      (let [returned (observe-present! run 3 2 "CARD-A")]
        (is (= {:active-request-id request-id
                :effects           [:media.fx/prepare]
                :new-request?      true
                :preparing?        true}
               {:active-request-id (get-in returned
                                           [:snapshot :data :audio
                                            :active-request :request-id])
                :effects           (effect-types returned)
                :new-request?      (not= request-id
                                         (get-in returned
                                                 [:snapshot :data :audio
                                                  :pending-request :request-id]))
                :preparing?        (active? (:snapshot returned)
                                            :card-request.st/preparing)})))
      (finally
        (runtime/stop! run)))))

(deftest duplicate-stale-and-missed-cycle-observations-test
  (let [{:keys [request-id run]} (start-active! base-settings "CARD-A")]
    (try
      (let [duplicate    (observe-present! run 2 1 "CARD-A")
            stale        (observe-absent! run 1 1)
            missed-cycle (observe-present! run 3 2 "CARD-A")]
        (is (= {:duplicate-effects []
                :missed-cycle      {:active-epoch 2
                                    :effects      []
                                    :request-id   request-id}
                :rfid              {:observation-seq 3
                                    :presence-epoch  2
                                    :present-uid     "CARD-A"}
                :stale-change      {:effects [] :entered #{} :exited #{}}}
               {:duplicate-effects (effect-types duplicate)
                :missed-cycle      {:active-epoch (get-in missed-cycle
                                                          [:snapshot :data :audio
                                                           :active-request
                                                           :presence-epoch])
                                    :effects      (effect-types missed-cycle)
                                    :request-id   (get-in missed-cycle
                                                          [:snapshot :data :audio
                                                           :active-request :request-id])}
                :rfid              (get-in missed-cycle [:snapshot :data :rfid])
                :stale-change      (select-keys stale [:effects :entered :exited])})))
      (finally
        (runtime/stop! run)))))

(deftest removal-during-preparation-cancels-authority-test
  (let [run (start-run! base-settings)]
    (try
      (let [placement (observe-present! run 1 1 "CARD-A")
            removed   (observe-absent! run 2 1)
            late      (finish-preparation! run placement)]
        (is (= {:late-effects    []
                :late-idle?      true
                :pending-request nil
                :removed-effects [:media.fx/cancel-preparation]
                :removed-idle?   true}
               {:late-effects    (effect-types late)
                :late-idle?      (active? (:snapshot late) :card-request.st/idle)
                :pending-request (get-in removed
                                         [:snapshot :data :audio :pending-request])
                :removed-effects (effect-types removed)
                :removed-idle?   (active? (:snapshot removed)
                                          :card-request.st/idle)})))
      (finally
        (runtime/stop! run)))))

(deftest removal-during-installation-stops-and-rejects-ack-test
  (let [run (start-run! base-settings)]
    (try
      (let [placement (observe-present! run 1 1 "CARD-A")
            prepared  (finish-preparation! run placement)
            context   (get-in prepared
                              [:effects 0 :effect/data :playback-context])
            removed   (observe-absent! run 2 1)
            late      (runtime/submit-and-await!
                       run
                       {:name :player.ev/queue-installed
                        :data {:playback-context context}})]
        (is (= {:authorized?     false
                :context         context
                :late-effects    []
                :late-idle?      true
                :removed-effects [:player.fx/stop]}
               {:authorized?     (get-in removed
                                         [:snapshot :data :audio
                                          :playback-authorized?])
                :context         (get-in removed
                                         [:snapshot :data :audio :playback-context])
                :late-effects    (effect-types late)
                :late-idle?      (active? (:snapshot late) :card-request.st/idle)
                :removed-effects (effect-types removed)})))
      (finally
        (runtime/stop! run)))))

(deftest removal-before-opening-rejects-late-opening-test
  (let [run (start-run! base-settings)]
    (try
      (let [placement (observe-present! run 1 1 "CARD-A")
            prepared  (finish-preparation! run placement)
            context   (get-in prepared
                              [:effects 0 :effect/data :playback-context])
            _         (runtime/submit-and-await! run {:name :player.ev/queue-installed
                                                      :data {:playback-context context}})
            removed   (observe-absent! run 2 1)
            late      (runtime/submit-and-await! run {:name :player.ev/state-changed
                                                      :data {:playback-context context
                                                             :state            :opening}})]
        (is (= {:authorized?     false
                :late-change     {:effects [] :entered #{} :exited #{}}
                :late-idle?      true
                :removed-effects [:player.fx/stop]}
               {:authorized?     (get-in removed
                                         [:snapshot :data :audio
                                          :playback-authorized?])
                :late-change     (select-keys late [:effects :entered :exited])
                :late-idle?      (active? (:snapshot late) :card-request.st/idle)
                :removed-effects (effect-types removed)})))
      (finally
        (runtime/stop! run)))))

(deftest direct-card-supersession-ignores-older-absence-test
  (let [run (start-run! base-settings)]
    (try
      (observe-present! run 1 1 "CARD-A")
      (let [card-b (observe-present! run 2 2 "CARD-B")
            stale  (observe-absent! run 1 1)]
        (is (= {:card-b-effects [:media.fx/prepare]
                :pending-uid    "CARD-B"
                :rfid           {:observation-seq 2
                                 :presence-epoch  2
                                 :present-uid     "CARD-B"}
                :stale-change   {:effects [] :entered #{} :exited #{}}}
               {:card-b-effects (effect-types card-b)
                :pending-uid    (get-in stale
                                        [:snapshot :data :audio
                                         :pending-request :uid])
                :rfid           (get-in stale [:snapshot :data :rfid])
                :stale-change   (select-keys stale [:effects :entered :exited])})))
      (finally
        (runtime/stop! run)))))
