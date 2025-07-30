;; Copyright © 2025 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2
(ns user)

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

(comment
  (dev)
  ;;
  )