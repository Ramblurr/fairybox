(ns fairy.box.deployment-test
  (:require
   [clojure.java.shell :as shell]
   [clojure.test :refer [deftest is]]))

(deftest production-deployment-scripts-preserve-release-invariants
  (let [result (shell/sh "bash" "test/scripts/fairybox-deployment-test.sh")]
    (is (= {:exit 0
            :out  "deployment integration scenarios passed\n"
            :err  ""}
           result))))
