(ns fairy.box.web.views.ui
  (:require
   [fairy.box.util :as util]
   [shadow.css :refer (css)]))

(def $page-margin (css :px-8 :px-4 :py-4 [:sm :px-6] [:lg :px-8]))

(defn setting-heading [& {:keys [label]}]
  [:h2 [:p {:class (css :text-lg :font-bold :text-smoky-900 [:dark :text-smoky-300])} label]])

(def button-priority-classes {:link (css :text-sm :font-semibold :leading-6 :text-gray-900

                                         [:hover-mouse [:hover :bg-smoky-300]]
                                         [:pointer-fine [:active :bg-smoky-300]]
                                         [:pointer-coarse [:active  :bg-smoky-300]]

                                         [:dark :text-smoky-400
                                          [:pointer-fine [:active :bg-smoky-800]]
                                          [:pointer-coarse [:active  :bg-smoky-800]]
                                          [:hover-mouse [:hover :bg-smoky-800]]]
                                         #_(comment
                                             [:hover :text-gray-300]
                                             [:pointer-fine [:active :text-gray-300]]
                                             [:pointer-coarse [:active :text-gray-300]]
                                             [:dark :text-smoky-300]))
                              :primary
                              (css :rounded-md :px-3 :py-2 :text-sm :font-semibold :text-white :shadow-sm
                                   [:focus-visible :outline :outline-2 :outline-offset-2 :outline-cloud-burst-600]
                                   [:hover :bg-cloud-burst-500]
                                   [:pointer-fine [:active :bg-cloud-burst-500]]
                                   [:pointer-coarse [:active :bg-cloud-burst-500]]
                                   [:disabled :bg-gray-400 :text-gray-300]
                                   :bg-smoky-600
                                   [:dark :bg-cloud-burst-600])})

(def spinner-priority-classes {:primary (css  :text-white)})

(def button-sizes-classes {:2xsmall (css :px-1.5 :py-0.5 :text-xs)
                           :xsmall (css :px-2.5 :py-1.5 :text-xs)
                           :small (css  :px-3 :py-2 :text-sm :leading-4)
                           :normal (css :px-4 :py-2 :text-sm)
                           :large (css  :px-4 :py-2 :text-base)
                           :xlarge (css  :px-6 :py-3 :text-base)})

(def button-icon-sizes-classes {:xsmall (css  :h-3 :w-3)
                                :small (css :h-4 :w-4)
                                :normal (css :h-5 :w-5)
                                :large (css :h-5 :w-5)
                                :xlarge (css :h-5 :w5)})
(defn button [&  {:keys [type tag label disabled? class attr icon icon-class priority centered? size hx-target hx-get hx-put hx-post hx-delete hx-vals hx-confirm hx-boost hx-push-url hx-swap form tabindex href spinner? id title name value]
                  :or   {class ""
                         icon-class ""
                         type :button
                         priority :primary
                         size  :large
                         disabled? false
                         tag :button}}]
  [tag
   (merge
    (util/remove-nils {:hx-target hx-target :hx-swap hx-swap :hx-get hx-get :hx-post hx-post :hx-put hx-put :hx-delete hx-delete :hx-vals hx-vals :hx-confirm hx-confirm :hx-push-url (when hx-push-url "true") :form form :tabindex tabindex
                       :hx-boost  (when hx-boost "true")
                       :id        id
                       :name      name
                       :value     value
                       :title     title
                       :href      (when-not disabled? href)})
    {:type  type
     :disabled disabled?
     :class
     (let [;; $not-link-rounding (css :inline-flex :items-center :rounded-md :border :font-medium)
           $centered (css :items-center :justify-center)
           $disabled (css [:disabled :opacity-50 :cursor-not-allowed])
           $disabled-a (css :opacity-50 :cursor-not-allowed)]
       [(priority button-priority-classes)
        (size button-sizes-classes)
        ;; (when-not (= :link priority)  $not-link-rounding)
        (when centered? $centered)
        (when (and disabled? (= :tag :a)) $disabled-a)
        (when (and disabled? (not= :tag :a)) $disabled)
        (when class class)])})

   (when icon
     (let [$label-offset (css :-ml-1 :mr-2)]
       (icon  {:class [#_(size button-icon-sizes-classes)
                       #_(when label $label-offset)
                       icon-class]})))
   [:span {:class "button-label"} label]])

(defn integer-input [& {:keys [name value label required? hint error min max step id autocomplete]
                        :or {required? true step 1 min 0 max 100}}]
  [:div {:class (css [:sm :col-span-4])}
   [:label {:for name :class (css :block :text-sm :font-medium :leading-6)} label]
   [:div {:class (css :mt-2)}
    [:div {:class (css :flex [:sm :max-w-md])}
     [:input {:type "number" :min min :max max :step step :name name :id (or id name) :autocomplete autocomplete
              :value value
              :class (css [:focus-within :ring-2 :ring-inset :ring-smoky-600] :block :rounded-md :shadow-sm :flex-1 :border :border-gray-300
                          :bg-transparent :py-1.5 :pl-1  [:focus :ring-0] [:sm :text-sm :leading-6])}]]]])
