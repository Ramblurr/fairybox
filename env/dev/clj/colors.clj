(ns colors
  (:require [clojure.string :as string]))
;;  custom pallette from https://lospec.com/palette-list/ty-shades-of-nokia-12
(def raw-colors
  {:juniper
   {"50" "#f4f7f7"
    "100" "#e4e9e9"
    "200" "#cbd5d6"
    "300" "#a7b7b9"
    "400" "#778d90"
    "500" "#617679"
    "600" "#536367"
    "700" "#475357"
    "800" "#3f484b"
    "900" "#383f41"
    "950" "#22282a"}

   :bismark
   {"50" "#f3f8f8"
    "100" "#dfebee"
    "200" "#c3d9de"
    "300" "#9abec6"
    "400" "#699aa7"
    "500" "#4a7885"
    "600" "#436977"
    "700" "#3b5763"
    "800" "#374b53"
    "900" "#314048"
    "950" "#1d292f"}

   :salt-box
   {"50" "#faf9fa"
    "100" "#f3f2f5"
    "200" "#e7e4ea"
    "300" "#d4cfd8"
    "400" "#bab1c1"
    "500" "#9c90a5"
    "600" "#7f7287"
    "700" "#695e70"
    "800" "#564d5b"
    "900" "#49424d"
    "950" "#2b252d"}

   :fjord
   {"50" "#f4f7fa"
    "100" "#e6ecf3"
    "200" "#d3dcea"
    "300" "#b5c6db"
    "400" "#91a8c9"
    "500" "#778eba"
    "600" "#6478ac"
    "700" "#59699c"
    "800" "#4c5781"
    "900" "#3c4460"
    "950" "#2b2f40"}

   :sycamore
   {"50" "#f7f7ee"
    "100" "#edeed9"
    "200" "#dcdeb8"
    "300" "#c4c88e"
    "400" "#acb269"
    "500" "#8f964c"
    "600" "#7d8540"
    "700" "#565c2f"
    "800" "#464a2a"
    "900" "#3c4027"
    "950" "#1f2211"}

   :cabbage-point
   {"50" "#f6f8f5"
    "100" "#ebefe9"
    "200" "#d7dfd3"
    "300" "#b6c5b0"
    "400" "#8ea385"
    "500" "#6d8463"
    "600" "#566b4e"
    "700" "#43523d"
    "800" "#394635"
    "900" "#313a2d"
    "950" "#171e15"}

   :mine-shaft
   {"50" "#f6f6f6"
    "100" "#e7e7e7"
    "200" "#d1d1d1"
    "300" "#b0b0b0"
    "400" "#888888"
    "500" "#6d6d6d"
    "600" "#5d5d5d"
    "700" "#4f4f4f"
    "800" "#454545"
    "900" "#3f3f3f"
    "950" "#262626"}

   :dune
   {"50" "#f5f5f1"
    "100" "#e4e5dc"
    "200" "#ceccba"
    "300" "#b1ae93"
    "400" "#9b9674"
    "500" "#8c8666"
    "600" "#786f56"
    "700" "#615847"
    "800" "#534b40"
    "900" "#49423a"
    "950" "#312b25"}

   :shingle-fawn
   {"50" "#faf6f2"
    "100" "#f3eae1"
    "200" "#e7d4c1"
    "300" "#d7b79a"
    "400" "#c69471"
    "500" "#ba7b55"
    "600" "#ac694a"
    "700" "#8f533f"
    "800" "#744538"
    "900" "#5e3a30"
    "950" "#321d18"}

   :rust
   {"50" "#f9f4ed"
    "100" "#f0e3d1"
    "200" "#e2c8a6"
    "300" "#d1a573"
    "400" "#c2874d"
    "500" "#aa6d3c"
    "600" "#9a5934"
    "700" "#7b432d"
    "800" "#68382b"
    "900" "#5a3129"
    "950" "#331915"}

   :hillary
   {"50" "#f8f7f4"
    "100" "#eeede6"
    "200" "#ddd9cb"
    "300" "#c7c0aa"
    "400" "#aa9d80"
    "500" "#9f8e70"
    "600" "#927f64"
    "700" "#7a6854"
    "800" "#645648"
    "900" "#52473c"
    "950" "#2b241f"}

   :cruise
   {"50" "#eefbf4"
    "100" "#c7f0d8"
    "200" "#b2e8cb"
    "300" "#7fd6ad"
    "400" "#4abd8b"
    "500" "#28a170"
    "600" "#198259"
    "700" "#14684a"
    "800" "#13523c"
    "900" "#104432"
    "950" "#08261d"}})

(def colors
  (-> raw-colors
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
