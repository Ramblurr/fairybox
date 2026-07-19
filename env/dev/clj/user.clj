;; Copyright © 2025 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2
(ns user)

((requiring-resolve 'hashp.install/install!))

;; --------------------------------------------------------------------------------------------
;; Toggle Dev-time flags

#_(set! *warn-on-reflection* true)
#_(set! *print-namespace-maps* false)

(defn dev
  "Load and switch to the 'dev' namespace."
  []
  (require 'dev)
  (in-ns 'dev)
  :loaded)

(defn rpi?
  "Are we running on a Raspberry Pi?"
  []
  (nil? (System/getenv "NOT_A_RPI")))

(comment
  (dev)
  (clojure.repl.deps/sync-deps)
  ;;
  )

(comment
  (do
    (require
     '[portal.colors]
     '[portal.api :as p])
    (p/open {:theme :portal.colors/gruvbox})
    (add-tap p/submit)
    (require '[clj-reload.core :as clj-reload])
    (clj-reload/init {:dirs ["src" "dev" "test"]}))

  (clj-reload/reload)

  (clojure.repl.deps/sync-deps)
  ;;
  )
