;; Copyright © 2025 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2
(ns watch
  (:require
   [clojure.core.async :as async]
   [css :as css]
   [sync :as sync]
   [clojure.string :as str]
   [pod.babashka.fswatcher :as fw]))

(defn new-watcher [handler path]
  (fw/watch path handler {:recursive true}))

(def watch-extensions [".clj" ".cljs" ".cljc" ".js" ".css" ".edn"])
(def sync-watch-paths ["src/clj" "env/dev/clj" "src/css" "resources/"])
(def css-watch-paths ["src/clj" "env/dev/clj"])

(defn interesting? [filename]
  (some #(str/ends-with? filename %) watch-extensions))

(defn watch-handler [handler]
  (fn [{:keys [type path] :as event}]
    (prn event)
    (when (and (= :write type) (interesting? path))
      (handler path))))

(defn ^:export dev-watch [_]
  (async/thread
    (css/on-start!))
  (async/thread
    (sync/on-start!))
  (doseq [path css-watch-paths]
    (new-watcher (watch-handler css/on-watch-event!) path))
  (doseq [path sync-watch-paths]
    (new-watcher (watch-handler sync/on-watch-event!) path))
  (deref (promise)))