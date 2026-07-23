(ns fairy.box2.rfid
  "Shared RFID reader port and chart-ingress adapter.

  Reader implementations report raw presence levels through [[start-reader!]].
  The ingress suppresses unchanged levels, owns presence epochs, enriches present
  readings with the safe linked-media path, and submits immutable events through
  the chart runtime's non-blocking submission function.")

(defprotocol RfidReader
  "Raw RFID presence reader implemented by real and synthetic adapters.

  Each reader calls `report!` serially; concurrent reporting is outside the port
  contract."
  (start-reader! [reader report!]
    "Starts `reader` and reports raw presence levels serially through `report!`.")
  (stop-reader! [reader]
    "Stops `reader` and releases its resources."))

(defn- changed-observation [state status uid]
  (let [present? (= :present status)
        level    [status (when present? uid)]]
    (when-not (= level [(:status state) (:uid state)])
      {:presence-epoch (cond-> (:presence-epoch state)
                         (and present?
                              (or (not= :present (:status state))
                                  (not= uid (:uid state))))
                         inc)
       :status         status
       :uid            (when present? uid)})))

(defn- observation-event [resolve-item-path {:keys [presence-epoch status uid]}]
  (let [present?  (= :present status)
        item-path (when present? (resolve-item-path uid))]
    {:name :rfid.ev/presence-observed
     :data (cond-> {:presence-epoch presence-epoch
                    :status         status}
             present?
             (assoc :request-id (random-uuid)
                    :uid        uid)

             item-path
             (assoc :item-path item-path))}))

(defn- submit-observation! [state_ submit! resolve-item-path observation]
  (let [ticket (submit! (observation-event resolve-item-path observation))]
    (when (:accepted? ticket)
      (reset! state_ observation))
    ticket))

(defn- report! [state_ submit! resolve-item-path {:keys [error status uid]}]
  (let [state @state_]
    (case status
      :faulted
      (if (= :faulted (:status state))
        {:accepted? false :reason :unchanged}
        (let [ticket (submit! {:name :rfid.ev/faulted
                               :data {:error error}})]
          (when (:accepted? ticket)
            (reset! state_ (assoc state :status :faulted :uid nil)))
          ticket))

      (:absent :present)
      (if-let [observation (changed-observation state status uid)]
        (let [recovery (when (= :faulted (:status state))
                         (submit! {:name :rfid.ev/recovered}))]
          (if (and recovery (not (:accepted? recovery)))
            recovery
            (submit-observation! state_
                                 submit!
                                 resolve-item-path
                                 observation)))
        {:accepted? false :reason :unchanged}))))

(defn start!
  "Starts an RFID `reader` connected to chart ingress.

  Options:

  | key                  | description
  | -------------------- | -----------
  | `:reader`            | Value satisfying [[RfidReader]]
  | `:resolve-item-path` | Function from UID to a safe linked-media path or `nil`
  | `:submit!`           | Non-blocking function that offers one immutable chart event

  Returns an adapter handle for [[snapshot]] and [[stop!]]."
  [{:keys [reader resolve-item-path submit!]
    :or   {resolve-item-path (constantly nil)}}]
  (let [state_  (atom {:presence-epoch 0
                       :status         :absent
                       :uid            nil})
        report! #(report! state_ submit! resolve-item-path %)]
    (start-reader! reader report!)
    {:reader reader
     :state_ state_}))

(defn snapshot
  "Returns the most recent raw RFID presence state and issued provenance."
  [adapter]
  @(:state_ adapter))

(defn stop!
  "Stops the reader attached to `adapter`."
  [adapter]
  (stop-reader! (:reader adapter)))