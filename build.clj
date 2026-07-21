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
(def class-dir (str target-dir "/" "classes"))
(def uber-file (format "%s/%s-standalone.jar" target-dir (name lib)))
(def basis (b/create-basis {:project "deps.edn"}))
(def generated-resource-dir (str target-dir "/resources"))
(def build-source-dirs
  ["src/clj" "resources" "env/prod/resources" "env/prod/clj"])

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

(defn prep [_]
  (compile-css nil)
  (println "Writing Pom...")
  (b/write-pom {:class-dir class-dir
                :lib lib
                :version version
                :basis basis
                :src-dirs ["src/clj"]})
  (b/copy-dir {:src-dirs (conj build-source-dirs generated-resource-dir)
               :target-dir class-dir}))

(defn uber [_]
  (println "Compiling Clojure...")
  (b/compile-clj {:basis basis
                  :src-dirs build-source-dirs
                  :class-dir class-dir})
  (println "Making uberjar...")
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :main main-cls
           :basis basis}))

(defn all [_]
  (clean nil)
  (prep nil)
  (uber nil))
