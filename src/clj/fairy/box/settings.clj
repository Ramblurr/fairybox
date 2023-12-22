(ns fairy.box.settings
  (:require [integrant.core :as ig]))

(defmethod ig/init-key :fairy.box/settings [_ opts]
  opts)
