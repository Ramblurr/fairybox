(ns sync
  (:require [babashka.tasks :refer [shell]]))

(defn ^:export do-sync! [_]
  (shell "rsync -e  \"ssh -o 'ControlPath=/dev/shm/control:%h:%p:%r'\" -avr --exclude .git --exclude target --exclude src --exclude .lsp --exclude .clj-kondo --exclude .cpcache ../fairybox fairybox:")
  (shell "rsync -e  \"ssh -o 'ControlPath=/dev/shm/control:%h:%p:%r'\" --delete -avr --exclude .git --exclude .lsp --exclude .clj-kondo --exclude .cpcache src/ fairybox:fairybox/src"))

(defn ^:export on-watch-event! [path]
  (do-sync! nil))

(defn on-start! []
  (do-sync! nil))
