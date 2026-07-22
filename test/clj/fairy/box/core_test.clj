;; Copyright © 2025 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2
(ns fairy.box.core-test
  (:require
   [clojure.core.async :as async]
   [clojure.test :refer [deftest is]]
   [donut.system :as ds]
   [fairy.box.core :as core]
   [fairy.box.db :as db]
   [fairy.box.hardware.buttons :as buttons]
   [fairy.box.hardware.led :as led]
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
                              {:gpio 6 :action :audio/volume-down}]
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

(deftest enables-host-poweroff-only-in-production-by-default
  (is (= {:test false :dev-no-rpi false :prod true}
         (update-vals {:test       (components :test)
                       :dev-no-rpi (components :dev-no-rpi)
                       :prod       (components :prod)}
                      #(get-in % [:fairy.box/settings
                                  :shutdown
                                  :poweroff-enabled?])))))

(deftest configures-production-nrepl-server
  (is (= {:bind "127.0.0.1" :port 7000}
         (get (components :prod) :fairy.box.nrepl/server))))

(deftest defines-complete-donut-graph
  (let [component-defs
        (get-in (system/system {:profile :test})
                [::ds/defs :fairy.box/components])
        refresh-config
        (get-in component-defs
                [:fairy.box.web/refresh ::ds/config])
        web-components
        (get-in component-defs
                [:fairy.box.web/server ::ds/config :components])
        removed-component-keys
        [:fairy.box.web/front-panel-refresh
         :fairy.box.web/player-event-refresh
         :fairy.box.web/player-progress
         :fairy.box.web/rfid-presence]]
    (is (= {:component-keys        #{:fairy.box/settings
                                     :fairy.box/startup
                                     :fairy.box.auto-shutdown/timer
                                     :fairy.box.audio.system2/player
                                     :fairy.box.bus/bus
                                     :fairy.box.db/db
                                     :fairy.box.hardware/buttons
                                     :fairy.box.hardware/leds
                                     :fairy.box.hardware/rfid
                                     :fairy.box.mqtt/client
                                     :fairy.box.nrepl/server
                                     :fairy.box.playback-limits/policy
                                     :fairy.box.sleep/timer
                                     :fairy.box.switchboard/switchboard
                                     :fairy.box.tts/tts
                                     :fairy.box.web/refresh
                                     :fairy.box.web/server}
            :refresh-bus-ref       (ds/ref (component-id :fairy.box.bus/bus))
            :refresh-db-ref        (ds/ref (component-id :fairy.box.db/db))
            :refresh-led-ref?      false
            :server-refresh-ref    (ds/ref
                                    (component-id :fairy.box.web/refresh))
            :removed-components    {}
            :removed-server-refs   {}
            :missing-start         #{}
            :missing-required-stop #{}}
           {:component-keys        (set (keys component-defs))
            :refresh-bus-ref       (get refresh-config :bus)
            :refresh-db-ref        (get refresh-config :db-conn)
            :refresh-led-ref?      (contains? refresh-config :leds)
            :server-refresh-ref    (get web-components
                                        :fairy.box.web/refresh)
            :removed-components    (select-keys component-defs
                                                removed-component-keys)
            :removed-server-refs   (select-keys web-components
                                                removed-component-keys)
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
  (let [selected         #{(component-id :fairy.box.hardware/buttons)
                           (component-id :fairy.box.hardware/leds)
                           (component-id :fairy.box.hardware/rfid)
                           (component-id :fairy.box.mqtt/client)}
        database         (atom {:settings {:audio db/default-audio-settings}})
        test-system      (assoc-in (system/system {:profile :test})
                                   [::ds/defs
                                    :fairy.box/components
                                    :fairy.box.db/db]
                                   database)
        running          (ds/start test-system {} selected)
        instance         #(ds/instance running (component-id %))
        buttons-instance (instance :fairy.box.hardware/buttons)
        leds-instance    (instance :fairy.box.hardware/leds)
        rfid-instance    (instance :fairy.box.hardware/rfid)]
    (try
      (let [led-values (led/current-values (:controller leds-instance))]
        (is (= {:buttons {:enabled?       false
                          :gpio-handles   []
                          :configured-button-ids
                          #{:audio/prev
                            :audio/next
                            :audio/play-pause
                            :audio/volume-up
                            :audio/volume-down}
                          :logical-input? true}
                :leds    {:enabled?        false
                          :gpio-handles    {}
                          :configured-leds #{:audio/prev
                                             :audio/next
                                             :audio/play-pause
                                             :audio/volume-up
                                             :audio/volume-down}
                          :current-values  {:audio/prev        0.0
                                            :audio/next        0.0
                                            :audio/play-pause  0.0
                                            :audio/volume-up   0.0
                                            :audio/volume-down 0.0}}
                :rfid    {:enabled? true :type :simulated}
                :mqtt    {:enabled? false}}
               {:buttons {:enabled?              (:enabled? buttons-instance)
                          :gpio-handles          (:buttons buttons-instance)
                          :configured-button-ids (:button-ids buttons-instance)
                          :logical-input?
                          (and (buttons/press! buttons-instance
                                               :audio/volume-up)
                               (buttons/release! buttons-instance
                                                 :audio/volume-up))}
                :leds    {:enabled?        (:enabled? leds-instance)
                          :gpio-handles    (:leds leds-instance)
                          :configured-leds (set (keys led-values))
                          :current-values  led-values}
                :rfid    (select-keys rfid-instance [:enabled? :type])
                :mqtt    (instance :fairy.box.mqtt/client)})))
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

(deftest jvm-component-shutdown-does-not-request-host-poweroff
  (let [selected  #{(component-id :fairy.box/settings)
                    (component-id :fairy.box.bus/bus)
                    (component-id :fairy.box.switchboard/switchboard)}
        running   (ds/start (system/system {:profile :test}) {} selected)
        event-bus (ds/instance running
                               (component-id :fairy.box.bus/bus))
        listener  (async/chan 4)
        test-app_ (atom running)]
    (try
      (ev/listen event-bus "/system" listener)
      (with-redefs [clojure.core/shutdown-agents (constantly nil)
                    system/app_                  test-app_]
        (core/stop-jvm!))
      (is (= {:running-system nil
              :system-event   nil}
             {:running-system @test-app_
              :system-event   (some-> (async/poll! listener)
                                      (select-keys [:path :value]))}))
      (finally
        (when-let [running-system @test-app_]
          (ds/stop running-system))
        (async/close! listener)))))

(deftest loads-production-entry-point-without-starting
  (is (= {:main? true :running-system nil}
         {:main?          (boolean (ns-resolve 'fairy.box.core '-main))
          :running-system @system/app_})))