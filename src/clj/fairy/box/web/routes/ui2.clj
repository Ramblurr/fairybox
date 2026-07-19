(ns fairy.box.web.routes.ui2
  (:require
   [hifi.util.codec :as codec]
   [medley.core :as medley]
   [clojure.tools.logging :as log]
   [clojure.core.async :as async]
   [jp.nijohando.event :as ev]
   [fairy.box.web.routes.commands :as commands]
   [fairy.box.web.views.player :as player]
   [fairy.box.web.views.settings :as settings]
   [fairy.box.web.views.queue :as queue]
   [hifi.core :as h]
   [hifi.datastar :as datastar]
   [hifi.datastar.http-kit :as d*http-kit]
   [hifi.html :as html]
   [hifi.web.middleware :as hifi.mw]
   [hifi.util.assets :as assets]))

(defn pages []
  {:page/home {:path  "/" :render #'player/render}
   :page/queue {:path  "/queue" :render #'queue/render}
   :page/settings {:path  "/settings" :render #'settings/render}
   :page.settings/rfid-link {:path "/settings/rfid" :render #'settings/render-rfid}
   :page.settings/browse {:path "/settings/browse" :render #'settings/render-browse}
   :page.settings/playback {:path "/settings/playback" :render #'settings/render-playback}})

(defn shim-head []
  (list
   [:title {} "Fairy Box"]
   [:meta {:name "color-scheme" :content "dark"}]
   [:meta {:name "darkreader-lock"}]
   [::html/stylesheet-link {:href "css/tailwind.css"}]
   [::html/stylesheet-link {:href "css/fairybox.css"}]
   [::html/javascript-include {:src "js/datastar@v1.0.2.js" :defer true :type "module"}]
   (when (h/dev?)
     [::html/javascript-include {:src "js/datastar-inspector.js"}])))

(def body-post [:div
                [:datastar-inspector]
                [:svg {:style "display: none"}
                 [:symbol {:id "svg-sprite-spinner" :fill "none", :viewbox "0 0 24 24"}
                  [:circle {:class "opacity-25", :cx "12", :cy "12", :r "10", :stroke "currentColor", :stroke-width "4"}]
                  [:path {:class "opacity-75", :fill "currentColor", :d "M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"}]]]])

(defn build-shim-page-resp [req {:keys [compress-fn encoding head-hiccup body-post]
                                 :or   {compress-fn identity}}]
  (let [body
        (->> (html/shim-document
              {:csrf-cookie-js (when (h/dev?) html/csrf-cookie-js-dev)
               :head           head-hiccup
               :body-post      (html/compile body-post)})
             (html/render req)
             :html)]
    (-> {:status 200

         :headers (-> {"Content-Type"  "text/html"
                       "Cache-Control" "no-cache, must-revalidate"}
                      (medley/assoc-some "Content-Encoding" encoding))
         :body    (compress-fn body)}
        ;; Etags ensure the shim is only sent again if it's contents have changed
        (assoc-in [:headers "ETag"] (codec/digest body)))))

(defn unwrap [v]
  (cond
    (var? v) (var-get v)
    (fn? v) (v)
    :else v))

(defn shim-handler
  [_path head-hiccup body-post]
  (fn handler [req]
    (let [resp (build-shim-page-resp req {:head-hiccup #p (unwrap head-hiccup) :body-post body-post})
          etag (get-in resp [:headers "ETag"])]
      (if (= ((:headers req) "if-none-match") etag)
        {:status 304}
        resp))))
  ;; todo prod cache resp
#_(let [resp (build-shim-page-resp req {:head-hiccup head-hiccup :body-post body-post})
        etag (get-in resp [:headers "ETag"])]
    (fn handler [req]
      #p (if (= ((:headers req) "if-none-match") etag)
           {:status 304}
           resp)))

(defn render-handler
  [path render-fn & {:keys [on-close on-open br-window-size render-on-connect] :as _opts
                     :or {;; Window size can be tuned to trade memory
                          ;; for reduced bandwidth and compute.
                          ;; The right window size can significantly improve
                          ;; compression of highly variable streams of data.
                          ;; (br/window-size->kb 18) => 262KB
                          br-window-size    18
                          ;; If false does not render on connect  waits for
                          ;; next batch. Note this means you should do
                          ;; something on connect to trigger a batch.
                          ;; Otherwise the user will not see anything
                          ;; until a batch is triggered.
                          render-on-connect true}}]
  (fn [req]
    {:status 200
     :body (tap> ["render-handler" (:uri req)])}))

(defmacro defpage
  {:clj-kondo/lint-as 'clojure.core/defn}
  [sym {:keys [path shim-head body-post] :as opts} args & body]
  (let [sym-fn (symbol (str sym "-fn"))]
    `(do (defn ~sym-fn ~args ~@body)
         (def ~sym [~path {:get  (shim-handler ~path ~shim-head ~body-post)
                           :post (render-handler ~path (var ~sym-fn) ~opts)}]))))

(defpage home
  {:path "/" :shim-head shim-head :body-post body-post}
  [req]
  (player/render req))

#_(defn pages->routes [pages]
    (->> (pages)
         (mapv (fn [[page-route-name {:keys [path render]}]]
                 [path {:name page-route-name
                        :get  shim-handler
                        :post (d*http-kit/render-handler render)}]))

         (into [""])))

(h/defroutes routes
  ["" {:middleware (conj hifi.mw/hypermedia-chain :hifi/assets :fairy.box/middleware)}
   home
   #_(pages->routes pages)
   #_commands/commands])

(defn handle-event [opts event]
  (datastar/rerender-all!))

(defn start-loop! [{:keys [<listener] :as opts}]
  (async/go-loop []
    (when-some [event (async/<! <listener)]
      (try
        (handle-event opts event)
        (catch Exception e
          (log/error e "sse broadcaster: error handling event" event)))
      (recur))))

(defn init-sse-broacast! [{:keys [bus] :as opts}]
  (let [<listener (async/chan)
        <emitter (async/chan)]
    (ev/emitize bus <emitter)
    (ev/listen bus "/hardware/input/rfid" <listener)
    (ev/listen bus "/player/events" <listener)

    (start-loop! (-> opts
                     (assoc
                      :<listener <listener
                      :<emitter <emitter)))
    {:<listener <listener
     :<emitter <emitter}))

(defn stop-sse-broadcast! [{:keys [<emitter <listener]}]
  (when <emitter
    (async/close! <emitter))
  (when <listener
    (async/close! <listener)))

(def SSEBroadcastComponent
  "Component that listens to events from the bus and emits them over SSE to clients"
  {:donut.system/start  (fn [{config :donut.system/config}]
                          (init-sse-broacast! config))
   :donut.system/stop   (fn [{:donut.system/keys [instance]}]
                          (stop-sse-broadcast! instance))
   :donut.system/config {:config        [:donut.system/ref [:config]]
                         :bus        [:donut.system/ref [:fairy.box/components :fairy.box.bus/bus]]
                         :settings   [:donut.system/ref [:fairy.box/components :fairy.box/settings]]
                         :db-conn    [:donut.system/ref [:fairy.box/components :fairy.box.db/db]]}})
