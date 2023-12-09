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
(def watch-paths ["src/clj" "env/dev/clj"])

(defn interesting? [filename]
  (some #(str/ends-with? filename %) watch-extensions))

(defn watch-handler [{:keys [type path] :as event}]
  (prn event)
  (when (and (= :write type) (interesting? path))
    (css/on-watch-event! path)
    (sync/on-watch-event! path)))

(defn ^:export dev-watch [_]
  (async/thread
    (css/on-start!))
  (async/thread
    (sync/on-start!))
  (doseq [path watch-paths]
    (new-watcher watch-handler path))
  (deref (promise)))
