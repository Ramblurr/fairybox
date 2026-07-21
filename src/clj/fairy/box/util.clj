;; Copyright © 2025 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2
(ns fairy.box.util
  (:require
   [clojure.core.async :as async])
  (:import
   [java.time LocalTime]))

(def ^:private wall-clock-time-pattern
  #"(?:[01][0-9]|2[0-3]):[0-5][0-9]")

(defn parse-wall-clock-time
  ([value]
   (parse-wall-clock-time nil value))
  ([setting-key value]
   (if (and (string? value)
            (re-matches wall-clock-time-pattern value))
     (LocalTime/of (parse-long (subs value 0 2))
                   (parse-long (subs value 3 5)))
     (throw (ex-info "Wall-clock time must use HH:mm"
                     (cond-> {:expected-format "HH:mm"
                              :value           value}
                       setting-key (assoc :setting-key setting-key)))))))

(defn valid-wall-clock-time? [value]
  (try
    (parse-wall-clock-time value)
    true
    (catch clojure.lang.ExceptionInfo _
      false)))

(defn debounce [in ms]
  (let [out (async/chan)]
    (async/go-loop [last-val nil]
      (let [val          (if (nil? last-val) (async/<! in) last-val)
            timer        (async/timeout ms)
            [new-val ch] (async/alts! [timer in] :priority true)]
        (condp = ch
          timer (do
                  ;; (tap> {:sending val})
                  (when-not
                   (async/>! out val)
                    (async/close! in))
                  (recur nil))
          in (when new-val (recur new-val)))))
    out))

(defn throttle
  "Pipes values from in to out (the ret val), but at most once every ms milliseconds.
  in should probably be a sliding buffer chan."
  [in ms]
  (let [out (async/chan)]
    (async/go-loop []
      (let [val   (async/<! in)
            timer (async/timeout ms)]
        (when val
          (if-not (async/>! out val)
            (do
              (async/close! in)
              nil)
            (do
              (async/<! timer)
              (recur))))))
    out))

(defn exception?
  "returns true if x is an exception"
  [x]
  (instance? Throwable x))

(defn remove-nils
  "Returns the list/vec/map less any keys that have nil values"
  [m]
  (cond (map? m)
        (into {} (filter #(not (nil? (val %))) m))
        (list? m)
        (remove #(nil? %) m)
        (vector? m)
        (filterv #(some? %) m)
        (sequential? m)
        (remove #(nil? %) m)
        :else
        (throw (ex-info "remove-nils: Not implemented" {:value m}))))

(defmacro thread
  "Starts a virtual thread. Conveys bindings."
  [& body]
  `(Thread/startVirtualThread
    (bound-fn* ;; binding conveyance
     (fn [] ~@body))))
