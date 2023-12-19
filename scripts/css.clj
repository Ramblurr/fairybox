(ns css
  (:require
   [colors :refer [prefixed-colors css-color-vars]]
   [clojure.java.io :as io]
   [shadow.css.build :as cb]))

(def watch-paths ["src/clj"])
(def index-path "src/clj")
(def watch-extensions ["cljs" "cljc" "clj"])
(def css-out-dir "resources/public/css")
(def generated-css-out (format "%s/generated.css" css-out-dir))

(def aliases {:dark                            "@media (prefers-color-scheme: dark)"
              :light                           "@media (prefers-color-scheme: light)"
              :hover-mouse                     "@media(hover: hover) and (pointer: fine)"
              :none-hover-mouse                "@media(hover: none) and (pointer: fine)"
              :hover-touch                     "@media(hover: hover) and (any-pointer: coarse)"
              :pointer-fine                    "@media(pointer: fine)"
              :pointer-coarse                  "@media(any-pointer: coarse)"
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
              :fill-transparent                {:fill "transparent"}})

(defn update-color-alias-groups [color-groups]
  (assoc color-groups "accent-" :accent-color))

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

(defn write-to! [f css]
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
            (cb/write-outputs-to (io/file css-out-dir)))]
    (css/write-to! (io/file generated-css-out) (css/generated-css (:colors @css-ref)))
    (prn :CSS-GENERATED)
    (doseq [mod (:outputs result)
            {:keys [warning-type] :as warning} (:warnings mod)]
      (prn [:CSS (name warning-type) (dissoc warning :warning-type)]))
    (println)))

(defn on-start! []
  (let [build-state (->  (cb/start)
                         (update-config))]
    ;; (tap> build-state)
    ;; first initialize my css
    (reset! css-ref
            (-> build-state
                (cb/index-path (io/file index-path) {}))))

  ;; then build it once
  (generate-css))

(defn on-watch-event! [path]
  (try
    (swap! css-ref cb/index-file path)
    (generate-css)
    (catch Exception e
      (prn :css-build-failure)
      (prn e))))

(defn ^:export css-dev [& args]
  (on-start!))

(defn ^:export css-release
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

    (css/write-to! (io/file generated-css-out) (css/generated-css (:colors build-state)))

    (doseq [mod (:outputs build-state)
            {:keys [warning-type] :as warning} (:warnings mod)]

      (prn [:CSS (name warning-type) (dissoc warning :warning-type)]))))
