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

(defn rpi?
  "Are we running on a Raspberry Pi?"
  []
  (nil? (System/getenv "NOT_A_RPI")))

(if (rpi?)
  (System/setProperty "hifi.profile" "dev")
  (System/setProperty "hifi.profile" "dev-no-rpi"))

(comment
  (dev)
  ;;
  )
