(ns fairy.box.web.views.hello
    (:require
[shadow.css :refer (css)]
      [simpleui.core :as simpleui :refer [defcomponent]]
      [fairy.box.web.htmx :refer [page-htmx]]))

(defcomponent ^:endpoint hello [req my-name]
  [:div#hello {:class (css :px-2 )} "Hello " my-name])


(defn ui-routes [base-path]
  (simpleui/make-routes
   base-path
   (fn [req]
     (page-htmx
       [:div {:class (css :px-10 )}
        [:label {:style "margin-right: 10px"}
         "What is your name?"]
        [:input {:type "text"
                 :name "my-name"
                 :hx-patch "hello"

                 :hx-target "#hello"
                 :hx-swap "outerHTML"}]]

      (hello req "")))))
