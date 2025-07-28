(ns sync
  (:require [babashka.tasks :refer [shell]]))

(def host "fairybox-wifi")
#_(def host "fairybox")

(def excludes ["archive" ".git" "target" ".lsp" ".clj-kondo" ".cpcache"])
(def exclude-args (mapcat (fn [x] ["--exclude" x]) excludes))

(defn ^:export do-sync! [_]
  (shell (-> ["rsync" "-e" "ssh -o 'ControlPath=/dev/shm/control:%h:%p:%r'" "-avr" "--exclude" "src"]
             (into exclude-args)
             (conj "../fairybox/"
                   (str host ":/var/lib/fairybox/fairybox"))))

  (shell (-> ["rsync" "-e" "ssh -o 'ControlPath=/dev/shm/control:%h:%p:%r'" "--delete" "-avr"]
             (into exclude-args)
             (conj "src/"
                   (str host ":/var/lib/fairybox/fairybox/src")))))

(defn ^:export on-watch-event! [path]
  (do-sync! nil))

(defn on-start! []
  (do-sync! nil))
