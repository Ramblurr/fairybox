;; Copyright © 2025 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2
(ns fairy.box.util.natural-sorting
  (:refer-clojure :exclude [sort sort-by])
  (:require [clojure.string :as string]))

(defn parse-int [s]
  #?(:clj (Long/parseLong s)
     :cljs (js/parseInt s)))

(defn vector-compare [[value1 & rest1] [value2 & rest2]]
  (let [result (compare value1 value2)]
    (cond
      (not (zero? result)) result
      (nil? value1) 0
      :else (recur rest1 rest2))))

(defn prepare-string [s]
  (let [s       (or s "")
        parts   (vec (string/split s #"\d+"))
        numbers (->> (re-seq #"\d+" s)
                     (map parse-int)
                     (vec))]
    (vec (interleave (conj parts "") (conj numbers -1)))))

(defn natural-compare [a b]
  (vector-compare (prepare-string a)
                  (prepare-string b)))

(defn sort [coll] (clojure.core/sort natural-compare coll))

(defn sort-by [keyfn coll]
  (clojure.core/sort-by keyfn natural-compare coll))