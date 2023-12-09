(ns css
  (:require
   [clojure.java.io :as io]
   [shadow.css.build :as cb]
   [shadow.cljs.devtools.server.fs-watch :as fs-watch]
   [colors :refer [prefixed-colors css-color-vars colors]]))

(def watch-paths ["src/clj"])
(def index-path "src/clj")
(def watch-extensions ["cljs" "cljc" "clj"])
(def css-out-dir "resources/public/css")
(def generated-css-out (format "%s/generated.css" css-out-dir))

(def aliases {:text-xs {:font-size "0.3rem" :line-height "0.7rem"}
              :text-sm {:font-size "0.5rem" :line-height "0.75rem"}
              :text-base {:font-size "0.75rem" :line-height "0.75rem"}
              :text-info-panel {:font-size "0.75rem" :line-height "17px"}
              :text-lg {:font-size "0.8rem" :line-height "0.8rem"}
              :text-xl {:font-size "1.25rem" :line-height "1.75rem"}
              :text-2xl {:font-size "1.5rem" :line-height "2rem"}
              :text-3xl {:font-size "1.875rem" :line-height "2.25rem"}
              :text-4xl {:font-size "2.25rem" :line-height "2.5rem"}
              :text-5xl {:font-size "3rem" :line-height "1"}
              :text-6xl {:font-size "3.75rem" :line-height "1"}
              :text-7xl {:font-size "4.5rem" :line-height "1"}
              :text-8xl {:font-size "6rem" :line-height "1"}
              :text-9xl {:font-size "8rem" :line-height "1"}
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
              :bg-form-valid-400-transparent {:background-color "color-mix(in srgb, var(--form-valid-400) 20%, transparent)"}
              })

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
             (str css
                  (str "." (name class) " {\n")
                  (reduce str
                          ""
                          (map style->str styles))
                  "}\n")) "" aliases)))

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
            (cb/generate '{:tailwind {:include [fh-util.*]}})
            (cb/write-outputs-to (io/file css-out-dir)))]
    (css/write-to! (io/file generated-css-out) (css/generated-css (:colors @css-ref)))
    (prn :CSS-GENERATED " " generated-css-out )
    (doseq [mod (:outputs result)
            {:keys [warning-type] :as warning} (:warnings mod)]
      (prn [:CSS (name warning-type) (dissoc warning :warning-type)]))
    (println)))

(defn watch-css
  "Based on the repl script from https://github.com/thheller/shadow-css"
  []
  (let [build-state (->  (cb/start)
                         (update-config))]
    ;; (tap> build-state)
    ;; first initialize my css
    (reset! css-ref
            (-> build-state
                (cb/index-path (io/file index-path) {}))))

  ;; then build it once
  (generate-css)

  ;; then setup the watcher that rebuilds everything on change
  (reset! css-watch-ref
          (fs-watch/start
           {}
           (mapv io/file watch-paths)
           watch-extensions
           (fn [updates]
             (try
               (doseq [{:keys [file event]} updates
                       :when (not= event :del)]
                 ;; re-index all added or modified files
                 (swap! css-ref cb/index-file file))

               (generate-css)
               (catch Exception e
                 (prn :css-build-failure)
                 (prn e)))))))

(defn watch-stop []
  (when-some [css-watch @css-watch-ref]
    (fs-watch/stop css-watch)
    (reset! css-ref nil)))

(defn css-release
  "Build CSS for production releases"
  [& args]
  (let [build-state
        (-> (cb/start)
            (update-config)
            (cb/generate-color-aliases)
            (cb/generate-spacing-aliases)
            (cb/index-path (io/file index-path) {})
            (cb/generate
              '{:tailwind {:include [fh-util.*]}})
            (cb/write-outputs-to (io/file css-out-dir)))]

    (css/write-to! (io/file generated-css-out) (css/generated-css (:colors build-state)))

    (doseq [mod (:outputs build-state)
            {:keys [warning-type] :as warning} (:warnings mod)]

      (prn [:CSS (name warning-type) (dissoc warning :warning-type)]))))
