(ns fairy.box.debug)

(defn xxx [msg v]
  (tap> {:msg msg :v v})
  v)
