(ns css
  (:require
   [colors :refer [prefixed-colors css-color-vars colors]]
   [clojure.java.io :as io]
   [shadow.css.build :as cb]))

(def watch-paths ["src/clj"])
(def index-path "src/clj")
(def watch-extensions ["cljs" "cljc" "clj"])
(def css-out-dir "resources/public/css")
(def generated-css-out (format "%s/generated.css" css-out-dir))

(def aliases {:dark "@media (prefers-color-scheme: dark)"
              :light "@media (prefers-color-scheme: light)"
              :float-right {:float "right"}
              :float-none {:float "none"}
              :float-left {:float "left"}
              :outline-0 {:outline-width "0px"}
              :outline-1 {:outline-width "1px"}
              :outline-2 {:outline-width "2px"}
              :outline-4 {:outline-width "4px"}
              :outline-8 {:outline-width "8px"}
              :outline-offset-0     {:outline-offset "0px"}
              :outline-offset-1     {:outline-offset "1px"}
              :outline-offset-2     {:outline-offset "2px"}
              :outline-offset-4     {:outline-offset "4px"}
              :outline-offset-8     {:outline-offset "8px"}
              :bg-form-invalid-400-transparent {:background-color "color-mix(in srgb, var(--form-invalid-400) 20%, transparent)"}
              :bg-form-valid-400-transparent {:background-color "color-mix(in srgb, var(--form-valid-400) 20%, transparent)"}})

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
