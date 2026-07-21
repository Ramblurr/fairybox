;; Copyright © 2025 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2
(ns fairy.box.core-test
  (:require
   [clojure.core.async :as async]
   [clojure.test :refer [deftest is]]
   [donut.system :as ds]
   [fairy.box.core]
   [fairy.box.db :as db]
   [fairy.box.hardware.rfid :as rfid]
   [fairy.box.mqtt :as mqtt]
   [fairy.box.settings :as settings]
   [fairy.box.system :as system]
   [jp.nijohando.event :as ev]))

(def component-id-prefix [:fairy.box/components])

(defn- component-id [component-key]
  (conj component-id-prefix component-key))

(defn- components [profile]
  (-> (system/read-config profile)
      :fairy.box/components))

(deftest preserves-hardware-configuration
  (let [test-components (components :test)
        prod-components (components :prod)]
    (is (= {:test-enablement {:rfid true :buttons false :leds false}
            :prod-enablement {:rfid true :buttons true :leds true}
            :test-rfid-type  :simulated
            :prod-rfid-type  :mfrc522
            :buttons         [{:gpio 22 :action :audio/prev}
                              {:gpio 23 :action :audio/next}
                              {:gpio 27 :action :audio/play-pause}
                              {:gpio 5 :action :audio/volume-up}
                              {:gpio 6 :action :audio/volume-down}
                              {:gpio 17 :action :system/shutdown}]
            :leds            [{:gpio 14 :led-type :pwm :name :audio/prev}
                              {:gpio 7 :led-type :pwm :name :audio/next}
                              {:gpio 12 :led-type :pwm :name :audio/play-pause}
                              {:gpio 13 :led-type :pwm :name :audio/volume-up}
                              {:gpio 15 :led-type :pwm :name :audio/volume-down}]
            :groups          {}}
           {:test-enablement (:fairy.box.hardware/enabled test-components)
            :prod-enablement (:fairy.box.hardware/enabled prod-components)
            :test-rfid-type  (get-in test-components
                                     [:fairy.box.hardware/rfid :rfid-type])
            :prod-rfid-type  (get-in prod-components
                                     [:fairy.box.hardware/rfid :rfid-type])
            :buttons         (get-in test-components
                                     [:fairy.box.hardware/buttons :buttons])
            :leds            (get-in test-components
                                     [:fairy.box.hardware/leds :leds])
            :groups          (get-in test-components
                                     [:fairy.box.hardware/leds :groups])}))))

(deftest defines-complete-donut-graph
  (let [component-defs (get-in (system/system {:profile :test})
                               [::ds/defs :fairy.box/components])]
    (is (= {:component-keys        #{:fairy.box/settings
                                     :fairy.box/startup
                                     :fairy.box.audio.system2/player
                                     :fairy.box.bus/bus
                                     :fairy.box.db/db
                                     :fairy.box.hardware/buttons
                                     :fairy.box.hardware/leds
                                     :fairy.box.hardware/rfid
                                     :fairy.box.mqtt/client
                                     :fairy.box.playback-limits/policy
                                     :fairy.box.switchboard/switchboard
                                     :fairy.box.tts/tts
                                     :fairy.box.web/player-event-refresh
                                     :fairy.box.web/player-progress
                                     :fairy.box.web/rfid-presence
                                     :fairy.box.web/server}
            :missing-start         #{}
            :missing-required-stop #{}}
           {:component-keys        (set (keys component-defs))
            :missing-start         (->> component-defs
                                        (keep (fn [[key component]]
                                                (when-not (fn? (::ds/start component))
                                                  key)))
                                        set)
            :missing-required-stop (->> (dissoc component-defs
                                                :fairy.box/settings
                                                :fairy.box.db/db)
                                        (keep (fn [[key component]]
                                                (when-not (fn? (::ds/stop component))
                                                  key)))
                                        set)}))))

(deftest starts-and-stops-safe-hardware-subsystem
  (let [selected      #{(component-id :fairy.box.hardware/buttons)
                        (component-id :fairy.box.hardware/leds)
                        (component-id :fairy.box.hardware/rfid)
                        (component-id :fairy.box.mqtt/client)}
        database      (atom {:settings {:audio db/default-audio-settings}})
        test-system   (assoc-in (system/system {:profile :test})
                                [::ds/defs
                                 :fairy.box/components
                                 :fairy.box.db/db]
                                database)
        running       (ds/start test-system {} selected)
        instance      #(ds/instance running (component-id %))
        rfid-instance (instance :fairy.box.hardware/rfid)]
    (try
      (is (= {:buttons {:enabled? false :buttons []}
              :leds    {:enabled? false :groups {} :leds {}}
              :rfid    {:enabled? true :type :simulated}
              :mqtt    {:enabled? false}}
             {:buttons (instance :fairy.box.hardware/buttons)
              :leds    (instance :fairy.box.hardware/leds)
              :rfid    (select-keys rfid-instance [:enabled? :type])
              :mqtt    (instance :fairy.box.mqtt/client)}))
      (finally
        (ds/stop running)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Simulated RFID is stopped"
                          (rfid/place! rfid-instance "after-stop")))))

(deftest emits-initialized-event-during-startup
  (let [bus      (ev/bus)
        listener (async/chan 1)]
    (try
      (ev/listen bus "/system" listener)
      (let [instance     (settings/startup! {:bus bus :delay-ms 0})
            timeout      (async/timeout 1000)
            [event port] (async/alts!! [listener timeout])]
        (settings/stop! instance)
        (is (= {:event     {:path  "/system"
                            :value {:event :system/initialized}}
                :received? true}
               {:event     (select-keys event [:path :value])
                :received? (= port listener)})))
      (finally
        (async/close! listener)
        (ev/close! bus)))))

(deftest keeps-mqtt-disabled-without-uri
  (let [instance (mqtt/init-client! {:settings {:fairybox-id "test-box"}
                                     :uri      nil})]
    (mqtt/halt-client! instance)
    (is (= {:enabled? false} instance))))

(deftest loads-production-entry-point-without-starting
  (is (= {:main? true :running-system nil}
         {:main?          (boolean (ns-resolve 'fairy.box.core '-main))
          :running-system @system/app_})))