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
