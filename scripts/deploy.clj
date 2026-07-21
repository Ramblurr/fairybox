;; Copyright © 2026 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2
(ns deploy
  (:require
   [babashka.fs :as fs]
   [babashka.process :as process]
   [clojure.string :as str]))

(def host (or (System/getenv "FAIRYBOX_HOST") "fairybox-wifi"))
(def artifact "target/box-standalone.jar")
(def remote-root "/var/lib/fairybox")
(def remote-deploy-command "/usr/local/bin/fairybox-deploy")
(def required-jar-entries
  #{"fairy/box/core.class"
    "com/aayushatharva/brotli4j/linux/aarch64/NativeLoader.class"
    "lib/linux-aarch64/libbrotli.so"})

(defn- output [& command]
  (-> (apply process/shell {:out :string} command)
      :out
      str/trim))

(defn- jar-sha256 [path]
  (first (str/split (output "sha256sum" path) #"\s+")))

(defn- validate-artifact! [path]
  (when-not (and (fs/regular-file? path)
                 (pos? (fs/size path)))
    (throw (ex-info "Production jar is missing or empty"
                    {:artifact path})))
  (let [entries (->> (str/split-lines (output "jar" "tf" path))
                     set)
        missing (remove entries required-jar-entries)]
    (when (seq missing)
      (throw (ex-info "Production jar lacks required entries"
                      {:artifact path
                       :missing  (vec missing)})))))

(defn- remote-incoming [sha]
  (str remote-root "/incoming/" sha))

(defn- stage! [sha]
  (let [incoming (remote-incoming sha)
        remote-artifact (str incoming "/box-standalone.jar.part")]
    (process/shell "ssh" "-o" "BatchMode=yes"
                   host "mkdir" "-p" "--" incoming)
    (process/shell "rsync" "--archive" "--partial"
                   artifact (str host ":" remote-artifact))
    (let [remote-sha (first
                      (str/split
                       (output "ssh" "-o" "BatchMode=yes"
                               host "sha256sum" remote-artifact)
                       #"\s+"))]
      (when-not (= sha remote-sha)
        (throw (ex-info "Remote artifact checksum mismatch"
                        {:expected sha
                         :actual   remote-sha
                         :path     remote-artifact}))))))

(defn ^:export deploy! [_]
  (validate-artifact! artifact)
  (let [sha (jar-sha256 artifact)]
    (println (str "Staging Fairybox release " sha " on " host))
    (stage! sha)
    (process/shell "ssh" "-o" "BatchMode=yes"
                   host remote-deploy-command "install" sha)))

(defn ^:export status! [_]
  (process/shell "ssh" "-o" "BatchMode=yes"
                 host remote-deploy-command "status"))
