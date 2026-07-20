;; Copyright © 2025 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2
(ns fairy.box.css
  (:require
   [clojure.string :as str]
   [fairy.box.colors :refer [prefixed-colors css-color-vars]]
   [clojure.java.io :as io]
   [shadow.css.build :as cb]))

(def index-path "src/clj")
(def css-out-dir "resources/public/css")

(def aliases {:dark             "@media (prefers-color-scheme: dark)"
              :light            "@media (prefers-color-scheme: light)"
              :hover-mouse      "@media (hover: hover) and (pointer: fine)"
              :none-hover-mouse "@media
 (hover: none) and (pointer: fine)"

              :hover-touch                     "@media (hover: hover) and (any-pointer: coarse)"
              :pointer-fine                    "@media (pointer: fine)"
              :pointer-coarse                  "@media (any-pointer: coarse)"
              :-my-2                           {:margin-top "-0.5rem" :margin-bottom "-0.5rem"}
              :-mx-2                           {:margin-left "-0.5rem" :margin-right "-0.5rem"}
              :-mt-2                           {:margin-top "-0.5rem"}
              :-ml-2                           {:margin-left "-0.5rem"}
              :w-half                          {:width "50%"}
              :max-w-none                      {:max-width "none"}
              :max-w-xs                        {:max-width "20rem"} ;; /* 320px */
              :max-w-sm                        {:max-width "24rem"} ;; /* 384px */
              :max-w-md                        {:max-width "28rem"} ;; /* 448px */
              :max-w-lg                        {:max-width "32rem"} ;; /* 512px */
              :max-w-xl                        {:max-width "36rem"} ;; /* 576px */
              :max-w-2xl                       {:max-width "42rem"} ;; /* 672px */
              :max-w-3xl                       {:max-width "48rem"} ;; /* 768px */
              :max-w-4xl                       {:max-width "56rem"} ;; /* 896px */
              :max-w-5xl                       {:max-width "64rem"} ;; /* 1024px */
              :max-w-6xl                       {:max-width "72rem"} ;; /* 1152px */
              :max-w-7xl                       {:max-width "80rem"} ;; /* 1280px */
              :left-half                       {:left "50%"}
              :top-half                        {:top "50%"}
              :float-right                     {:float "right"}
              :float-none                      {:float "none"}
              :float-left                      {:float "left"}
              :outline-0                       {:outline-width "0px"}
              :outline-1                       {:outline-width "1px"}
              :outline-2                       {:outline-width "2px"}
              :outline-4                       {:outline-width "4px"}
              :outline-8                       {:outline-width "8px"}
              :outline-offset-0                {:outline-offset "0px"}
              :outline-offset-1                {:outline-offset "1px"}
              :outline-offset-2                {:outline-offset "2px"}
              :outline-offset-4                {:outline-offset "4px"}
              :outline-offset-8                {:outline-offset "8px"}
              :bg-form-invalid-400-transparent {:background-color "color-mix(in srgb, var(--form-invalid-400) 20%, transparent)"}
              :bg-form-valid-400-transparent   {:background-color "color-mix(in srgb, var(--form-valid-400) 20%, transparent)"}
              :scale-0                         {:transform "scale(0)"}
              :scale-x-0                       {:transform " scaleX(0)"}
              :scale-y-0                       {:transform "scaleY(0)"}
              :scale-50                        {:transform "scale(.5)"}
              :scale-x-50                      {:transform "scaleX(.5)"}
              :scale-y-50                      {:transform "scaleY(.5)"}
              :scale-75                        {:transform "scale(.75)"}
              :scale-x-75                      {:transform "scaleX(.75)"}
              :scale-y-75                      {:transform "scaleY(.75)"}
              :scale-90                        {:transform "scale(.9)"}
              :scale-x-90                      {:transform "scaleX(.9)"}
              :scale-y-90                      {:transform "scaleY(.9)"}
              :scale-95                        {:transform "scale(.95)"}
              :scale-x-95                      {:transform "scaleX(.95)"}
              :scale-y-95                      {:transform "scaleY(.95)"}
              :scale-100                       {:transform "scale(1)"}
              :scale-x-100                     {:transform "scaleX(1)"}
              :scale-y-100                     {:transform "scaleY(1)"}
              :scale-105                       {:transform "scale(1.05)"}
              :scale-x-105                     {:transform "scaleX(1.05)"}
              :scale-y-105                     {:transform "scaleY(1.05)"}
              :scale-110                       {:transform "scale(1.1)"}
              :scale-x-110                     {:transform "scaleX(1.1)"}
              :scale-y-110                     {:transform "scaleY(1.1)"}
              :scale-125                       {:transform "scale(1.25)"}
              :scale-x-125                     {:transform "scaleX(1.25)"}
              :scale-y-125                     {:transform "scaleY(1.25)"}
              :scale-150                       {:transform "scale(1.5)"}
              :scale-x-150                     {:transform "scaleX(1.5)"}
              :scale-y-150                     {:transform "scaleY(1.5)"}
              :transition-transform            {:transition-property        "transform"
                                                :transition-timing-function "cubic-bezier(0.4, 0, 0.2, 1)"
                                                :transition-duration        "150ms"}
              :transition-all                  {:transition-property        "all"
                                                :transition-timing-function "cubic-bezier(0.4, 0, 0.2, 1)"
                                                :transition-duration        "150ms"}
              :ease-linear                     {:transition-timing-function "linear"}
              :ease-in                         {:transition-timing-function "cubic-bezier(0.4, 0, 1, 1)"}
              :ease-out                        {:transition-timing-function "cubic-bezier(0, 0, 0.2, 1)"}
              :ease-in-out                     {:transition-timing-function "cubic-bezier(0.4, 0, 0.2, 1)"}
              :duration-0                      {:transition-duration "0s"}
              :duration-75                     {:transition-duration "75ms"}
              :duration-100                    {:transition-duration "100ms"}
              :duration-150                    {:transition-duration "150ms"}
              :duration-200                    {:transition-duration "200ms"}
              :duration-300                    {:transition-duration "300ms"}
              :duration-500                    {:transition-duration "500ms"}
              :duration-700                    {:transition-duration "700ms"}
              :duration-1000                   {:transition-duration "1000ms"}
              :delay-0                         {:transition-delay "0s"}
              :delay-75                        {:transition-delay "75ms"}
              :delay-100                       {:transition-delay "100ms"}
              :delay-150                       {:transition-delay "150ms"}
              :delay-200                       {:transition-delay "200ms"}
              :delay-300                       {:transition-delay "300ms"}
              :delay-500                       {:transition-delay "500ms"}
              :delay-700                       {:transition-delay "700ms"}
              :delay-1000                      {:transition-delay "1000ms"}
              :appearance-none                 {:appearance "none"}
              :trim-cap                        {:text-box "trim-both cap alphabetic"}
              :trim-ex                         {:text-box "trim-both ex alphabetic"}
              :trim-none                       {:text-box-trim "none"}
              :trim-start                      {:text-box-trim "trim-start"}
              :trim-end                        {:text-box-trim "trim-end"}
              :object-contain                  {:object-fit "contain"}
              :object-cover                    {:object-fit "cover"}
              :object-fill                     {:object-fit "fill"}
              :object-none                     {:object-fit "none"}
              :object-scale-down               {:object-fit "scale-down"}
              :opacity-0                       {:opacity "0"}
              :opacity-5                       {:opacity "0.05"}
              :opacity-10                      {:opacity "0.1"}
              :opacity-20                      {:opacity "0.2"}
              :opacity-25                      {:opacity "0.25"}
              :opacity-30                      {:opacity "0.3"}
              :opacity-40                      {:opacity "0.4"}
              :opacity-50                      {:opacity "0.5"}
              :opacity-60                      {:opacity "0.6"}
              :opacity-70                      {:opacity "0.7"}
              :opacity-75                      {:opacity "0.75"}
              :opacity-80                      {:opacity "0.8"}
              :opacity-90                      {:opacity "0.9"}
              :opacity-95                      {:opacity "0.95"}
              :opacity-100                     {:opacity "1"}
              :fill-none                       {:fill "none"}
              :fill-inherit                    {:fill "inherit"}
              :fill-current                    {:fill "currentColor"}
              :fill-transparent                {:fill "transparent"}
              :ring-0                          {:box-shadow "var(--tw-ring-inset) 0 0 0 calc(0px + var(--tw-ring-offset-width)) var(--tw-ring-color)"}
              :ring-1                          {:box-shadow "var(--tw-ring-inset) 0 0 0 calc(1px + var(--tw-ring-offset-width)) var(--tw-ring-color)"}
              :ring-1-test                       {"--tw-ring-offset-shadow" "var(--tw-ring-inset) 0 0 0 var(--tw-ring-offset-width) var(--tw-ring-offset-color)"
                                                  "--tw-ring-shadow" "var(--tw-ring-inset) 0 0 0 calc(${value} + var(--tw-ring-offset-width)) var(--tw-ring-color)"
                                                  :box-shadow "var(--tw-ring-offset-shadow) var(--tw-ring-shadow) var(--tw-shadow, 0 0 #0000)"}
              :ring-2                          {:box-shadow "var(--tw-ring-inset) 0 0 0 calc(2px + var(--tw-ring-offset-width)) var(--tw-ring-color)"}
              :ring                            {:box-shadow "var(--tw-ring-inset) 0 0 0 calc(3px + var(--tw-ring-offset-width)) var(--tw-ring-color)"}
              :ring-4                          {:box-shadow "var(--tw-ring-inset) 0 0 0 calc(4px + var(--tw-ring-offset-width)) var(--tw-ring-color)"}
              :ring-8                          {:box-shadow "var(--tw-ring-inset) 0 0 0 calc(8px + var(--tw-ring-offset-width)) var(--tw-ring-color)"}
              :ring-inset                      {:--tw-ring-inset "inset"}
              :ring-offset-0                   {:--tw-ring-offset-width "0px"}
              :ring-offset-1                   {:--tw-ring-offset-width "1px"}
              :ring-offset-2                   {:--tw-ring-offset-width "2px"}
              :ring-offset-4                   {:--tw-ring-offset-width "4px"}
              :ring-offset-8                   {:--tw-ring-offset-width "8px"}
              :col-auto                        {:grid-column "auto"}
              :col-span-1                      {:grid-column "span 1 / span 1"}
              :col-span-2                      {:grid-column "span 2 / span 2"}
              :col-span-3                      {:grid-column "span 3 / span 3"}
              :col-span-4                      {:grid-column "span 4 / span 4"}
              :col-span-5                      {:grid-column "span 5 / span 5"}
              :col-span-6                      {:grid-column "span 6 / span 6"}
              :col-span-7                      {:grid-column "span 7 / span 7"}
              :col-span-8                      {:grid-column "span 8 / span 8"}
              :col-span-9                      {:grid-column "span 9 / span 9"}
              :col-span-10                     {:grid-column "span 10 / span 10"}
              :col-span-11                     {:grid-column "span 11 / span 11"}
              :col-span-12                     {:grid-column "span 12 / span 12"}
              :col-span-full                   {:grid-column "1 / -1"}
              :col-start-1                     {:grid-column-start "1"}
              :col-start-2                     {:grid-column-start "2"}
              :col-start-3                     {:grid-column-start "3"}
              :col-start-4                     {:grid-column-start "4"}
              :col-start-5                     {:grid-column-start "5"}
              :col-start-6                     {:grid-column-start "6"}
              :col-start-7                     {:grid-column-start "7"}
              :col-start-8                     {:grid-column-start "8"}
              :col-start-9                     {:grid-column-start "9"}
              :col-start-10                    {:grid-column-start "10"}
              :col-start-11                    {:grid-column-start "11"}
              :col-start-12                    {:grid-column-start "12"}
              :col-start-13                    {:grid-column-start "13"}
              :col-start-auto                  {:grid-column-start "auto"}
              :col-end-1                       {:grid-column-end "1"}
              :col-end-2                       {:grid-column-end "2"}
              :col-end-3                       {:grid-column-end "3"}
              :col-end-4                       {:grid-column-end "4"}
              :col-end-5                       {:grid-column-end "5"}
              :col-end-6                       {:grid-column-end "6"}
              :col-end-7                       {:grid-column-end "7"}
              :col-end-8                       {:grid-column-end "8"}
              :col-end-9                       {:grid-column-end "9"}
              :col-end-10                      {:grid-column-end "10"}
              :col-end-11                      {:grid-column-end "11"}
              :col-end-12                      {:grid-column-end "12"}
              :col-end-13                      {:grid-column-end "13"}
              :col-end-auto                    {:grid-column-end "auto"}
              :border-solid   {:border-style "solid"}
              :border-dashed  {:border-style "dashed"}
              :border-dotted  {:border-style "dotted"}
              :border-double  {:border-style "double"}
              :border-hidden  {:border-style "hidden"}
              :border-none  {:border-style "none"}})

(defn update-color-alias-groups [color-groups]
  (-> color-groups
      (assoc "accent-" :accent-color)
      (assoc "ring-" :--tw-ring-color)))

(defn update-config [config]
  (-> config
      (update-in [:alias-groups :color] update-color-alias-groups)
      (update :colors merge prefixed-colors)
      (update :aliases merge aliases)))

(defn style->str [[css-name value]]
  (str "  " (name css-name) ": " value ";\n"))

(defn generated-css [base-colors]
  (str
   (css-color-vars (merge prefixed-colors base-colors))
   "\n"
   (reduce (fn [css [class styles]]
             (if (string? styles)
               css
               (str css
                    (str "." (name class) " {\n")
                    (reduce str
                            ""
                            (map style->str styles))
                    "}\n"))) "" aliases)))

#_(defn write-to! [f css]
    (spit f css))

(defonce css-ref (atom nil))
(defonce css-watch-ref (atom nil))

#_(keys
   (:aliases
    (-> @css-ref
        (update-config)
        (cb/generate-color-aliases)
        (cb/generate-spacing-aliases)

        (cb/generate '{:tailwind {:include [fairy.*]}}))))

(defn generate-css []
  (let [result
        (-> @css-ref
            (update-config)
            (cb/generate-color-aliases)
            (cb/generate-spacing-aliases)
            (cb/generate '{:tailwind {:include [fairy.*]}})
            #_(cb/write-outputs-to (io/file css-out-dir)))
        css-str (str (generated-css (:colors @css-ref))
                     (get-in result [:chunks :tailwind :css]))]
    (doseq [mod (:outputs result)
            {:keys [warning-type] :as warning} (:warnings mod)]
      (println (name warning-type))
      (println (dissoc warning :warning-type))
      (tap> [:CSS (name warning-type) (dissoc warning :warning-type)]))
    css-str))

(-> @css-ref
    (update-config)
    (cb/generate-color-aliases)
    (cb/generate-spacing-aliases)
    (cb/generate '{:tailwind {:include [fairy.*]}})
    :chunks :tailwind :css (str/includes? "fairy_box_web_views_settings__L67_C2"))

#_(generate-css)

(defn start []
  (reset! css-ref
          (-> (cb/start)
              (update-config)
              (cb/index-path (io/file index-path) {}))))

#_(defn css-release
    "Build CSS for production releases"
    [& args]
    (let [build-state
          (-> (cb/start)
              (update-config)
              (cb/generate-color-aliases)
              (cb/generate-spacing-aliases)
              (cb/index-path (io/file index-path) {})
              (cb/generate
               '{:tailwind {:include [fairy.*]}})
              (cb/write-outputs-to (io/file css-out-dir)))]

      #_(write-to! (io/file generated-css-out) (css/generated-css (:colors build-state)))

      (doseq [mod                                (:outputs build-state)
              {:keys [warning-type] :as warning} (:warnings mod)]

        (prn [:CSS (name warning-type) (dissoc warning :warning-type)]))))
