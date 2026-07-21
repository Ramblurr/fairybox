(ns fairy.box.hardware.buttons-test
  (:require
   [clojure.core.async :as async]
   [clojure.test :refer [deftest is]]
   [fairy.box.hardware.buttons :as buttons]
   [jp.nijohando.event :as ev]))

(def button-definitions
  [{:gpio 1 :action :audio/play-pause}
   {:gpio 2 :action :audio/volume-up}])

(defn- await-value [channel timeout-ms]
  (let [[value port] (async/alts!! [channel (async/timeout timeout-ms)])]
    (when (= port channel)
      value)))

(defn- virtual-buttons [bus]
  (buttons/start-component!
   {:hardware-enablement {:buttons false}
    :bus                 bus
    :buttons             button-definitions}))

(defn- interval-double [hold-callback_]
  (fn [callback delay-ms]
    (if (= delay-ms buttons/hold-threshold)
      (deliver hold-callback_ callback)
      (future
        (Thread/sleep (long delay-ms))
        (callback)))))

(deftest virtual-short-press-uses-physical-button-pipeline
  (let [bus            (ev/bus)
        listener       (async/chan 2)
        hold-callback_ (promise)]
    (try
      (ev/listen bus "/hardware/input/buttons" listener)
      (with-redefs [buttons/init-button!
                    (fn [& _]
                      (throw (ex-info "Virtual buttons opened GPIO" {})))
                    buttons/set-interval (interval-double hold-callback_)]
        (let [instance (virtual-buttons bus)]
          (try
            (let [pressed?  (buttons/press! instance :audio/volume-up)
                  released? (buttons/release! instance :audio/volume-up)
                  event     (await-value listener 1000)
                  invalid?  (buttons/press! instance :audio/unknown)]
              (is (= {:enabled?       false
                      :gpio-buttons   []
                      :pressed?       true
                      :released?      true
                      :invalid?       nil
                      :external-event {:path  "/hardware/input/buttons"
                                       :value {:button-id :audio/volume-up
                                               :action    :button/single-press}}}
                     {:enabled?       (:enabled? instance)
                      :gpio-buttons   (:buttons instance)
                      :pressed?       pressed?
                      :released?      released?
                      :invalid?       invalid?
                      :external-event (select-keys event [:path :value])})))
            (finally
              (buttons/stop-component! instance)))))
      (finally
        (async/close! listener)
        (ev/close! bus)))))

(deftest virtual-hold-emits-hold-and-hold-release
  (let [bus            (ev/bus)
        listener       (async/chan 2)
        hold-callback_ (promise)]
    (try
      (ev/listen bus "/hardware/input/buttons" listener)
      (with-redefs [buttons/set-interval (interval-double hold-callback_)]
        (let [instance (virtual-buttons bus)]
          (try
            (buttons/press! instance :audio/play-pause)
            (let [hold-callback (deref hold-callback_ 1000 nil)]
              (when hold-callback
                (hold-callback))
              (let [hold-event (await-value listener 1000)]
                (buttons/release! instance :audio/play-pause)
                (is (= [{:path  "/hardware/input/buttons"
                         :value {:button-id :audio/play-pause
                                 :action    :button/hold}}
                        {:path  "/hardware/input/buttons"
                         :value {:button-id :audio/play-pause
                                 :action    :button/hold-release}}]
                       [(select-keys hold-event [:path :value])
                        (select-keys (await-value listener 1000)
                                     [:path :value])]))))
            (finally
              (buttons/stop-component! instance)))))
      (finally
        (async/close! listener)
        (ev/close! bus)))))

(deftest virtual-components-have-independent-logical-state
  (let [first-bus      (ev/bus)
        second-bus     (ev/bus)
        listener       (async/chan 1)
        hold-callback_ (promise)]
    (try
      (ev/listen first-bus "/hardware/input/buttons" listener)
      (with-redefs [buttons/set-interval (interval-double hold-callback_)]
        (let [first-instance  (virtual-buttons first-bus)
              second-instance (virtual-buttons second-bus)]
          (try
            (buttons/stop-component! second-instance)
            (buttons/press! first-instance :audio/play-pause)
            (buttons/release! first-instance :audio/play-pause)
            (is (= {:path  "/hardware/input/buttons"
                    :value {:button-id :audio/play-pause
                            :action    :button/single-press}}
                   (select-keys (await-value listener 1000)
                                [:path :value])))
            (finally
              (buttons/stop-component! first-instance)))))
      (finally
        (async/close! listener)
        (ev/close! first-bus)
        (ev/close! second-bus)))))
