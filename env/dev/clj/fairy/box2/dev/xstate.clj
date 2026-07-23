(ns fairy.box2.dev.xstate
  "Exports the Box2 topology as an XState v5 machine for visualization.

  This is a development aid, not an alternate Box2 runtime. Guards and effects
  become named XState labels so the generated machine preserves diagram intent
  without attempting to execute Clojure behavior.

  Adapted from Fulcro Statecharts' alpha `src/dev/xstate.clj` exporter."
  (:require
   [clojure.data.json :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [com.fulcrologic.statecharts.chart :as chart]
   [fairy.box2.model :as model]))

(def ^:private default-output-path "target/box2-xstate.js")

(defn- identifier [x]
  (cond
    (keyword? x) (subs (str x) 1)
    (symbol? x) (str x)
    :else (str x)))

(defn- state-key [id]
  (str/replace (identifier id) #"[^A-Za-z0-9_-]" "_"))

(defn- node-id [id]
  (str "box2_" (state-key id)))

(defn- child-state-ids [statechart node]
  (into []
        (comp
         (filter #(chart/state? statechart %))
         (remove #(true? (:initial? (chart/element statechart %)))))
        (:children (chart/element statechart node))))

(defn- effect-names [element key]
  (mapv identifier (get element key)))

(defn- transition-config [transition]
  (let [{:keys [diagram/condition target type]} transition
        targets (mapv #(str "#" (node-id %)) target)]
    (cond-> {}
      (seq targets) (assoc "target" (if (= 1 (count targets))
                                      (first targets)
                                      targets))
      condition (assoc "guard" condition)
      (seq (:fairy.box2.model/effects transition))
      (assoc "actions" (effect-names transition :fairy.box2.model/effects))
      (and (= :external type) (seq targets)) (assoc "reenter" true))))

(defn- transition-groups [statechart node]
  (reduce
   (fn [result transition-id]
     (let [{:keys [event] :as transition} (chart/element statechart transition-id)
           config (transition-config transition)
           events (cond
                    (nil? event) [nil]
                    (sequential? event) event
                    :else [event])]
       (reduce (fn [result event]
                 (if event
                   (update-in result ["on" (identifier event)] (fnil conj []) config)
                   (update result "always" (fnil conj []) config)))
               result
               events)))
   {}
   (chart/transitions statechart node)))

(declare state-config)

(defn- states-config [statechart node]
  (into (array-map)
        (map (fn [state-id]
               [(state-key state-id) (state-config statechart state-id)]))
        (child-state-ids statechart node)))

(defn- state-config [statechart state-id]
  (let [{:keys [id initial node-type] :as state} (chart/element statechart state-id)
        children (child-state-ids statechart state-id)
        description (get-in model/states [id :description])
        entry-actions (effect-names state :fairy.box2.model/entry-effects)
        exit-actions (effect-names state :fairy.box2.model/exit-effects)]
    (merge
     (cond-> {"id" (node-id id)}
       description (assoc "description" description)
       (= :final node-type) (assoc "type" "final")
       (= :parallel node-type) (assoc "type" "parallel")
       (and (seq children) (not= :parallel node-type))
       (assoc "initial" (state-key initial))
       (seq children) (assoc "states" (states-config statechart state-id))
       (seq entry-actions) (assoc "entry" entry-actions)
       (seq exit-actions) (assoc "exit" exit-actions))
     (transition-groups statechart state-id))))

(defn machine-config
  "Returns an XState v5 machine configuration for [[model/application-chart]]."
  []
  (let [statechart model/application-chart]
    (merge
     {"id"      (identifier (:name statechart))
      "initial" (state-key (:initial statechart))
      "states"  (states-config statechart statechart)}
     (transition-groups statechart statechart))))

(defn javascript
  "Returns the Box2 visualization machine as a JavaScript module."
  []
  (str "// Generated from fairy.box2.model/application-chart.\n"
       "// Guards and actions are labels; this is not the Box2 runtime.\n"
       "import { createMachine } from \"xstate\";\n\n"
       "export const box2Machine = createMachine(\n"
       (json/write-str (machine-config)
                       :escape-slash false
                       :escape-unicode false
                       :indent true)
       "\n);\n"))

(defn generate-xstate
  "Writes the Box2 XState module to `path` and returns its canonical path."
  ([]
   (generate-xstate default-output-path))
  ([path]
   (let [file (io/file path)]
     (io/make-parents file)
     (spit file (javascript))
     (.getCanonicalPath file))))

(comment
  (generate-xstate)

  :rcf)
