;; Copyright © 2025 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2
(ns colors
  (:require [clojure.string :as string]))
;;  custom pallette from https://lospec.com/palette-list/ty-shades-of-nokia-12
(def raw-colors
  {:white-rock
   {"950" "#322116",
    "300" "#ccbc78",
    "500" "#ad9145",
    "100" "#eeebd3",
    "900" "#573f2c",
    "50" "#f8f7ee",
    "800" "#654a2e",
    "700" "#775831",
    "200" "#dfd7a9",
    "400" "#bca453",
    "600" "#94753a"}
   :summer-green
   {"950" "#12211e",
    "300" "#8ebead",
    "500" "#498371",
    "100" "#dbece5",
    "900" "#263b35",
    "50" "#f4f9f7",
    "800" "#2a453e",
    "700" "#30554b",
    "200" "#b7d8ca",
    "400" "#639e8a",
    "600" "#39685b"},
   :moss-green
   {"950" "#16240f",
    "300" "#b0d29b",
    "500" "#699c4b",
    "100" "#eaf4e4",
    "900" "#2f4324",
    "50" "#f6faf3",
    "800" "#38512a",
    "700" "#436530",
    "200" "#d5e8ca",
    "400" "#8bb96f",
    "600" "#547f3a"},
   :smoky
   {"950" "#2c2230",
    "300" "#d5ccdb",
    "500" "#9f8aab",
    "100" "#f4f1f6",
    "900" "#4b3f50",
    "50" "#f9f8fb",
    "800" "#584860",
    "700" "#64526d",
    "200" "#e8e3eb",
    "400" "#bcadc5",
    "600" "#826c8d"},
   :wax-flower
   {"950" "#471808",
    "300" "#ffbba4",
    "500" "#f56c3e",
    "100" "#ffe9e1",
    "900" "#82341a",
    "50" "#fef5f2",
    "800" "#9d3917",
    "700" "#be4117",
    "200" "#ffd6c8",
    "400" "#fd916c",
    "600" "#e25120"},
   :cloud-burst
   {"950" "#2e305b",
    "300" "#a9bbe7",
    "500" "#6378ce",
    "100" "#e2e7f7",
    "900" "#353a73",
    "50" "#f2f4fc",
    "800" "#3d4190",
    "700" "#454db0",
    "200" "#ccd5f1",
    "400" "#8198d9",
    "600" "#4f5ec1"}})

(def colors
  raw-colors
  #_(-> raw-colors
        (assoc :form-valid (:sycamore raw-colors))
        (assoc :form-invalid (:rust raw-colors))))

(def prefixed-colors
  (update-keys
   (update-vals colors (fn [c-map] (update-keys c-map #(str "-" %))))
   name))

(defn css-color-vars [colors]
  (str
   (->> colors
        (map (fn [[c-name values]]
               (->> values
                    (map (fn [[weight hex]]
                           (str "--" (name c-name) weight ": " hex ";")))
                    sort
                    (string/join "\n  "))))

        (string/join "\n  ")
        (str ":root {\n  ")) "}"))