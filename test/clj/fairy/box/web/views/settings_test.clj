(ns fairy.box.web.views.settings-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [fairy.box.media-test-utils :as media]
   [fairy.box.web.views.settings :as settings-view]
   [hyperlith.core :as h]))

(defn- action-fn []
  (ns-resolve 'fairy.box.web.views.settings 'link-rfid-folder-fn))

(defn- component [req component-key]
  (get-in req [:donut.system/instances
               :fairy.box/components
               component-key]))

(defn- action-request [tree selected-folder rfid]
  (let [req (media/request tree "audiobooks/Author One")]
    (reset! (:state (component req :fairy.box.web/rfid-presence)) rfid)
    (assoc req
           :body {:selected_folder selected-folder
                  :rfid_uid "stale-browser-tag"})))

(deftest rfid-form-uses-datastar-action-and-ordinary-back-link
  (fs/with-temp-dir [temp-dir {:prefix "fairybox-rfid-form-"}]
    (let [tree (media/populate-media-tree! temp-dir)
          req (media/request tree "audiobooks/Author Two")
          action-path (ns-resolve 'fairy.box.web.views.settings
                                  'link-rfid-folder)
          html (-> (settings-view/rfid-link-form
                    req
                    "tag-1"
                    "audiobooks/Author Two")
                   h/html->str)]
      (is (some? action-path))
      (when action-path
        (is (= {:form true
                :selection-signal true
                :submit-action true
                :radio-binding true
                :ordinary-back-link true
                :client-rfid-removed true
                :htmx-removed true}
               {:form (str/starts-with? html "<form")
                :selection-signal
                (str/includes?
                 html
                 "data-signals:selected_folder__ifmissing=")
                :submit-action
                (and (str/includes? html "data-on:submit=")
                     (str/includes? html (var-get action-path)))
                :radio-binding
                (str/includes? html "data-bind=\"selected_folder\"")
                :ordinary-back-link
                (str/includes? html "href=\"/settings\"")
                :client-rfid-removed
                (not (str/includes? html "name=\"rfid-uid\""))
                :htmx-removed (not (str/includes? html "hx-"))}))))))

(deftest links-selected-folder-to-current-rfid
  (fs/with-temp-dir [temp-dir {:prefix "fairybox-rfid-action-"}]
    (let [tree (media/populate-media-tree! temp-dir)
          req (action-request tree
                              "audiobooks/Author One/../Author Two"
                              {:action :placed :uid "current-tag"})
          db-conn (component req :fairy.box.db/db)
          link! (action-fn)]
      (is (some? link!))
      (when link!
        (link! req)
        (is (= {:linked-tags
                {"current-tag"
                 {:folder "audiobooks/Author Two"}}}
               @db-conn))))))

(deftest rejects-link-without-current-rfid
  (fs/with-temp-dir [temp-dir {:prefix "fairybox-rfid-no-tag-"}]
    (let [tree (media/populate-media-tree! temp-dir)
          req (action-request tree
                              "audiobooks/Author Two"
                              {:action :removed :uid "old-tag"})
          db-conn (component req :fairy.box.db/db)
          link! (action-fn)]
      (when link!
        (link! req)
        (is (= {:linked-tags {}} @db-conn))))))

(deftest rejects-missing-and-escaped-link-paths
  (fs/with-temp-dir [temp-dir {:prefix "fairybox-rfid-invalid-path-"}]
    (let [tree (media/populate-media-tree! temp-dir)
          link! (action-fn)]
      (when link!
        (let [db-states
              (mapv (fn [selected-folder]
                      (let [req (action-request tree
                                                selected-folder
                                                {:action :placed :uid "tag-1"})
                            db-conn (component req :fairy.box.db/db)]
                        (link! req)
                        @db-conn))
                    ["missing" "../../etc"])]
          (is (= [{:linked-tags {}} {:linked-tags {}}]
                 db-states)))))))
