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
  (let [run (runtime/start! (constantly {:accepted? true}))]
    (runtime/submit-and-await! run
                               {:name :system.ev/initialized
                                :data {:settings          settings
                                       :settings-revision 0}})
    run))

(defn- observe-present! [run presence-epoch uid]
  (runtime/submit-and-await! run
                             {:name :rfid.ev/presence-observed
                              :data {:item-path      "story"
                                     :presence-epoch presence-epoch
                                     :request-id     (random-uuid)
                                     :status         :present
                                     :uid            uid}}))

(defn- observe-absent! [run presence-epoch]
  (runtime/submit-and-await! run
                             {:name :rfid.ev/presence-observed
                              :data {:presence-epoch presence-epoch
                                     :status         :absent}}))

(defn- finish-preparation! [run placement]
  (let [prepare-data (get-in placement [:effects 0 :effect/data])]
    (runtime/submit-and-await!
     run
     {:name :media.ev/prepared
      :data {:paths          ["/story.mp3"]
             :presence-epoch (:presence-epoch prepare-data)
             :request-id     (:request-id prepare-data)}})))

(defn- start-active! [settings uid]
  (let [run (start-run! settings)]
    (try
      (let [placement (observe-present! run 1 uid)
            prepared  (finish-preparation! run placement)
            context   (get-in prepared
                              [:effects 0 :effect/data :playback-context])]
        (runtime/submit-and-await! run
                                   {:name :player.ev/state-changed
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
      (let [removed  (observe-absent! run 1)
            returned (observe-present! run 2 "CARD-A")]
        (is (= {:removed  {:effects [:player.fx/pause]
                           :state   :suspended}
                :returned {:active-epoch 2
                           :effects      [:player.fx/resume]
                           :request-id   request-id
                           :state        :active}}
               {:removed  {:effects (effect-types removed)
                           :state   (when (active? (:snapshot removed)
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
      (let [removed  (observe-absent! run 1)
            returned (observe-present! run 2 "CARD-A")]
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
      (observe-absent! run 1)
      (let [returned (observe-present! run 2 "CARD-A")]
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

(deftest duplicate-presence-does-not-restart-active-request-test
  (let [{:keys [request-id run]} (start-active! base-settings "CARD-A")]
    (try
      (let [duplicate (observe-present! run 1 "CARD-A")]
        (is (= {:active-epoch 1
                :effects      []
                :request-id   request-id}
               {:active-epoch (get-in duplicate
                                      [:snapshot :data :audio
                                       :active-request :presence-epoch])
                :effects      (effect-types duplicate)
                :request-id   (get-in duplicate
                                      [:snapshot :data :audio
                                       :active-request :request-id])})))
      (finally
        (runtime/stop! run)))))

(deftest removal-during-preparation-cancels-authority-test
  (let [run (start-run! base-settings)]
    (try
      (let [placement (observe-present! run 1 "CARD-A")
            removed   (observe-absent! run 1)
            late      (finish-preparation! run placement)]
        (is (= {:late-effects    []
                :late-idle?      true
                :pending-request nil
                :removed-effects [:media.fx/cancel-preparation]
                :removed-idle?   true}
               {:late-effects    (effect-types late)
                :late-idle?      (active? (:snapshot late)
                                          :card-request.st/idle)
                :pending-request (get-in removed
                                         [:snapshot :data :audio :pending-request])
                :removed-effects (effect-types removed)
                :removed-idle?   (active? (:snapshot removed)
                                          :card-request.st/idle)})))
      (finally
        (runtime/stop! run)))))

(deftest removal-before-opening-rejects-late-opening-test
  (let [run (start-run! base-settings)]
    (try
      (let [placement (observe-present! run 1 "CARD-A")
            prepared  (finish-preparation! run placement)
            context   (get-in prepared
                              [:effects 0 :effect/data :playback-context])
            removed   (observe-absent! run 1)
            late      (runtime/submit-and-await!
                       run
                       {:name :player.ev/state-changed
                        :data {:playback-context context
                               :state            :opening}})]
        (is (= {:late-change     {:effects [] :entered #{} :exited #{}}
                :late-idle?      true
                :removed-effects [:player.fx/stop]}
               {:late-change     (select-keys late [:effects :entered :exited])
                :late-idle?      (active? (:snapshot late)
                                          :card-request.st/idle)
                :removed-effects (effect-types removed)})))
      (finally
        (runtime/stop! run)))))

(deftest fault-recovery-absence-cancels-pending-authority-test
  (let [run (start-run! base-settings)]
    (try
      (observe-present! run 1 "CARD-A")
      (runtime/submit-and-await! run
                                 {:name :rfid.ev/faulted
                                  :data {:error {:category :rfid/test}}})
      (runtime/submit-and-await! run {:name :rfid.ev/recovered})
      (let [removed (observe-absent! run 1)]
        (is (= {:effects         [:media.fx/cancel-preparation]
                :idle?           true
                :pending-request nil}
               {:effects         (effect-types removed)
                :idle?           (active? (:snapshot removed)
                                          :card-request.st/idle)
                :pending-request (get-in removed
                                         [:snapshot :data :audio
                                          :pending-request])})))
      (finally
        (runtime/stop! run)))))

(deftest fault-recovery-absence-pauses-active-authority-test
  (let [{:keys [run]} (start-active! base-settings "CARD-A")]
    (try
      (runtime/submit-and-await! run
                                 {:name :rfid.ev/faulted
                                  :data {:error {:category :rfid/test}}})
      (runtime/submit-and-await! run {:name :rfid.ev/recovered})
      (let [removed (observe-absent! run 1)]
        (is (= {:effects    [:player.fx/pause]
                :suspended? true}
               {:effects    (effect-types removed)
                :suspended? (active? (:snapshot removed)
                                     :card-request.st/suspended)})))
      (finally
        (runtime/stop! run)))))
