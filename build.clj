;; Copyright © 2025 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2
(ns build
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.tools.build.api :as b]
            [fairy.box.css :as app-css]))

(def lib 'fairy/box)
(def main-cls (string/join "." (filter some? [(namespace lib) (name lib) "core"])))
(def version (format "0.0.1-SNAPSHOT"))
(def target-dir "target")
(def generated-resource-dir (str target-dir "/resources"))
(def production-source-dirs
  ["src/clj" "resources" "env/prod/resources"])
(def unreferenced-image-paths
  ["public/img/cover.png"
   "public/img/fairy.png"
   "public/img/fairy2.png"
   "public/img/fairy3.png"
   "public/img/jukebox2.png"])
(def build-variants
  {:production {:aliases     []
                :class-dir   (str target-dir "/classes")
                :source-dirs production-source-dirs
                :uber-file   (format "%s/%s-standalone.jar" target-dir (name lib))}
   :diagnostic {:aliases     [:diagnostic]
                :class-dir   (str target-dir "/diagnostic-classes")
                :source-dirs (conj production-source-dirs "env/prod/clj")
                :uber-file   (format "%s/%s-diagnostic-standalone.jar" target-dir (name lib))}})

(defn clean
  "Delete the build target directory"
  [_]
  (println (str "Cleaning " target-dir))
  (b/delete {:path target-dir}))

(defn- write-resource! [resource-path body]
  (let [output (io/file generated-resource-dir resource-path)]
    (io/make-parents output)
    (spit output body)))

(defn compile-css [_]
  (println "Compiling CSS...")
  (app-css/start)
  (write-resource! app-css/shadow-css-resource
                   (app-css/generate-css))
  (write-resource! app-css/fairybox-css-resource
                   (app-css/compile-css! app-css/fairybox-css-source))
  (write-resource! app-css/compiled-css-marker "compiled\n"))

(defn- variant-config [{:keys [variant]
                        :or   {variant :production}}]
  (if-let [config (get build-variants variant)]
    (assoc config
           :basis (b/create-basis
                   (cond-> {:project "deps.edn"}
                     (seq (:aliases config)) (assoc :aliases (:aliases config))))
           :variant variant)
    (throw (ex-info "Unknown build variant"
                    {:variant            variant
                     :available-variants (keys build-variants)}))))

(defn- remove-unreferenced-images! [class-dir]
  (doseq [resource-path unreferenced-image-paths
          :let          [path (io/file class-dir resource-path)]
          :when         (.exists path)]
    (b/delete {:path (str path)})))

(defn- prep-variant! [{:keys [basis class-dir source-dirs]}]
  (println "Writing Pom...")
  (b/write-pom {:class-dir class-dir
                :lib       lib
                :version   version
                :basis     basis
                :src-dirs  ["src/clj"]})
  (b/copy-dir {:src-dirs   (conj source-dirs generated-resource-dir)
               :target-dir class-dir})
  (remove-unreferenced-images! class-dir))

(defn prep [opts]
  (compile-css nil)
  (prep-variant! (variant-config opts)))

(defn- uber-variant! [{:keys [basis class-dir source-dirs uber-file variant]}]
  (println (str "Compiling Clojure for " (name variant) " build..."))
  (b/compile-clj {:basis     basis
                  :src-dirs  source-dirs
                  :class-dir class-dir
                  :java-opts ["-Dclojure.tools.logging.factory=clojure.tools.logging.impl/jul-factory"
                              "--enable-native-access=ALL-UNNAMED"]})
  (println (str "Making " uber-file "..."))
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :main      main-cls
           :basis     basis
           :manifest  {"Enable-Native-Access" "ALL-UNNAMED"}}))

(defn uber [opts]
  (uber-variant! (variant-config opts)))

(defn all [opts]
  (clean nil)
  (compile-css nil)
  (let [config (variant-config opts)]
    (prep-variant! config)
    (uber-variant! config)))

(defn all-variants [_]
  (clean nil)
  (compile-css nil)
  (doseq [variant (keys build-variants)
          :let    [config (variant-config {:variant variant})]]
    (prep-variant! config)
    (uber-variant! config)))
