(ns fairy.box.hardware.rfid-test
  (:require
   [clojure.core.async :as async]
   [clojure.test :refer [deftest is]]
   [fairy.box.hardware.rfid :as rfid]
   [jp.nijohando.event :as ev]))

(defn- start-simulator [bus]
  (rfid/start-component! {:bus bus
                          :hardware-enablement {:rfid true}
                          :rfid-type           :simulated
                          :mfrc522             {:reset-gpio 25}}))

(defn- observed-event [event]
  (when (map? event)
    {:path  (:path event)
     :value (dissoc (:value event) :at)}))

(defn- take-event
  ([listener]
   (take-event listener 1000))
  ([listener timeout-ms]
   (let [timeout         (async/timeout timeout-ms)
         [event channel] (async/alts!! [listener timeout])]
     (if (= channel timeout)
       ::timeout
       (observed-event event)))))

(defn- test-rig []
  (let [bus      (ev/bus)
        listener (async/chan 8)]
    (ev/listen bus "/hardware/input/rfid" listener)
    {:bus      bus
     :listener listener
     :rfid     (start-simulator bus)}))

(defn- stop-test-rig! [{:keys [bus listener rfid]}]
  (rfid/release-rfid! rfid)
  (async/close! listener)
  (ev/close! bus))

(deftest selects-configured-component-type
  (is (= {:simulated :simulated
          :mfrc522   :mfrc522}
         {:simulated (rfid/component-type :simulated)
          :mfrc522   (rfid/component-type :mfrc522)})))

(deftest places-and-removes-simulated-rfid
  (let [{:keys [listener rfid] :as rig} (test-rig)]
    (try
      (let [placed             (rfid/place! rfid "card-a")
            placed-event       (take-event listener)
            state-after-place  (select-keys @(:state rfid) [:status :uid])
            removed            (rfid/remove! rfid)
            removed-event      (take-event listener)
            state-after-remove (select-keys @(:state rfid) [:status :uid])]
        (is (= {:placed-return
                {:path  "/hardware/input/rfid"
                 :value {:uid "card-a" :action :placed}}
                :placed-event
                {:path  "/hardware/input/rfid"
                 :value {:uid "card-a" :action :placed}}
                :state-after-place  {:status :present :uid "card-a"}
                :removed-return
                {:path  "/hardware/input/rfid"
                 :value {:uid "card-a" :action :removed}}
                :removed-event
                {:path  "/hardware/input/rfid"
                 :value {:uid "card-a" :action :removed}}
                :state-after-remove {:status :absent :uid nil}}
               {:placed-return      (observed-event placed)
                :placed-event       placed-event
                :state-after-place  state-after-place
                :removed-return     (observed-event removed)
                :removed-event      removed-event
                :state-after-remove state-after-remove})))
      (finally
        (stop-test-rig! rig)))))

(deftest switching-cards-removes-before-placing
  (let [{:keys [listener rfid] :as rig} (test-rig)]
    (try
      (rfid/place! rfid "card-a")
      (take-event listener)
      (rfid/place! rfid "card-b")
      (is (= [{:path  "/hardware/input/rfid"
               :value {:uid "card-a" :action :removed}}
              {:path  "/hardware/input/rfid"
               :value {:uid "card-b" :action :placed}}]
             [(take-event listener) (take-event listener)]))
      (finally
        (stop-test-rig! rig)))))

(deftest duplicate-operations-emit-nothing
  (let [{:keys [listener rfid] :as rig} (test-rig)]
    (try
      (rfid/place! rfid "card-a")
      (take-event listener)
      (let [duplicate-place              (rfid/place! rfid "card-a")
            event-after-duplicate-place  (take-event listener 100)
            _ (rfid/remove! rfid)
            _ (take-event listener)
            duplicate-remove             (rfid/remove! rfid)
            event-after-duplicate-remove (take-event listener 100)]
        (is (= {:duplicate-place              nil
                :event-after-duplicate-place  ::timeout
                :duplicate-remove             nil
                :event-after-duplicate-remove ::timeout}
               {:duplicate-place              duplicate-place
                :event-after-duplicate-place  event-after-duplicate-place
                :duplicate-remove             duplicate-remove
                :event-after-duplicate-remove event-after-duplicate-remove})))
      (finally
        (stop-test-rig! rig)))))

(deftest stopped-simulator-rejects-placement
  (let [{:keys [rfid] :as rig} (test-rig)]
    (try
      (rfid/release-rfid! rfid)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Simulated RFID is stopped"
                            (rfid/place! rfid "card-a")))
      (finally
        (stop-test-rig! rig)))))
