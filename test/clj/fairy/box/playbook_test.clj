(ns fairy.box.playbook-test
  (:require
   [babashka.fs :as fs]
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]))

(defn- trigger-script []
  (let [playbook (slurp "playbook.yml")
        block    (second
                  (re-find
                   #"(?ms)^    - name: Install onoff-shim-trigger\n.*?^        content: \|\n(.*?)^        dest: /usr/local/bin/onoff-shim-trigger$"
                   playbook))]
    (or (some-> block
                (str/replace #"(?m)^ {10}" ""))
        (throw (ex-info "OnOff SHIM trigger script not found" {})))))

(defn- executable! [path content]
  (spit (str path) content)
  (fs/set-posix-file-permissions path "rwx------")
  path)

(defn- fake-pinctrl [calls count-file]
  (str "#!/usr/bin/env bash\n"
       "if [ \"$1\" = get ]; then\n"
       "  count=$(cat \"" count-file "\" 2>/dev/null || echo 0)\n"
       "  count=$((count + 1))\n"
       "  echo \"$count\" > \"" count-file "\"\n"
       "  if [ \"$count\" -eq 1 ]; then\n"
       "    echo '17: ip pu | hi // GPIO17'\n"
       "  else\n"
       "    echo '17: ip pu | lo // GPIO17'\n"
       "  fi\n"
       "else\n"
       "  printf 'pinctrl\\t%s\\n' \"$*\" >> \"" calls "\"\n"
       "fi\n"))

(defn- fake-curl [calls exit]
  (str "#!/usr/bin/env bash\n"
       "printf 'curl' >> \"" calls "\"\n"
       "printf '\\t%s' \"$@\" >> \"" calls "\"\n"
       "printf '\\n' >> \"" calls "\"\n"
       "exit " exit "\n"))

(defn- fake-systemctl [calls stopping?]
  (str "#!/usr/bin/env bash\n"
       "if [ \"$1\" = is-system-running ]; then\n"
       "  printf 'systemctl\\tis-system-running\\n' >> \"" calls "\"\n"
       "  count=$(grep -c '^systemctl.*is-system-running$' \"" calls "\")\n"
       (if stopping?
         (str "  if [ \"$count\" -ge 2 ]; then\n"
              "    echo stopping\n"
              "    exit 1\n"
              "  fi\n")
         "")
       "  echo running\n"
       "else\n"
       "  printf 'systemctl\\t%s\\n' \"$*\" >> \"" calls "\"\n"
       "fi\n"))

(defn- trigger-result [curl-exit stopping?]
  (fs/with-temp-dir [temp-dir {:prefix "fairybox-onoff-trigger-"}]
    (let [calls      (fs/path temp-dir "calls")
          count-file (fs/path temp-dir "pinctrl-count")
          pinctrl    (executable! (fs/path temp-dir "pinctrl")
                                  (fake-pinctrl calls count-file))
          curl       (executable! (fs/path temp-dir "curl")
                                  (fake-curl calls curl-exit))
          sleep      (executable! (fs/path temp-dir "sleep")
                                  "#!/usr/bin/env bash\nexit 0\n")
          systemctl  (executable! (fs/path temp-dir "systemctl")
                                  (fake-systemctl calls stopping?))
          script     (-> (trigger-script)
                         (str/replace "/usr/bin/pinctrl" (str pinctrl))
                         (str/replace "/usr/bin/curl" (str curl))
                         (str/replace "/usr/bin/sleep" (str sleep))
                         (str/replace "/usr/bin/systemctl" (str systemctl)))
          trigger    (fs/path temp-dir "onoff-shim-trigger")
          _          (spit (str calls) "")
          _          (spit (str trigger) script)
          result     (shell/sh "bash" (str trigger))
          call-lines (fs/read-all-lines calls)]
      {:exit           (:exit result)
       :curl-calls     (filterv #(str/starts-with? % "curl\t") call-lines)
       :poweroff-calls (filterv #(= "systemctl\tpoweroff" %) call-lines)
       :state-checks   (count
                        (filter #(= "systemctl\tis-system-running" %)
                                call-lines))
       :stderr         (:err result)})))

(def canonical-curl-call
  (str "curl\t--fail\t--silent\t--show-error"
       "\t--connect-timeout\t2\t--max-time\t5"
       "\t--header\tAccept-Encoding: br"
       "\t--header\tSec-Fetch-Site: same-origin"
       "\t--cookie\t__Host-sid=onoff-shim-trigger"
       "\t--request\tPOST\thttp://127.0.0.1/api/shutdown"))

(deftest onoff-trigger-uses-graceful-api-with-bounded-fallback
  (is (= {:healthy
          {:exit           0
           :curl-calls     [canonical-curl-call]
           :poweroff-calls []
           :state-checks   2
           :stderr         ""}
          :api-unavailable
          {:exit           0
           :curl-calls     [canonical-curl-call]
           :poweroff-calls ["systemctl\tpoweroff"]
           :state-checks   0
           :stderr
           "Graceful poweroff request failed; forcing host poweroff\n"}
          :graceful-stall
          {:exit           0
           :curl-calls     [canonical-curl-call]
           :poweroff-calls ["systemctl\tpoweroff"]
           :state-checks   30
           :stderr
           "Graceful poweroff deadline expired; forcing host poweroff\n"}}
         {:healthy         (trigger-result 0 true)
          :api-unavailable (trigger-result 22 true)
          :graceful-stall  (trigger-result 0 false)})))

(deftest playbook-provisions-aot-release-deployment
  (let [playbook (slurp "playbook.yml")]
    (is (= {:release-directories true
            :migration-required  true
            :launcher-source     true
            :launcher-dest       true
            :deployer-source     true
            :deployer-dest       true
            :service-launcher    true
            :fixed-aot-in-unit   false}
           {:release-directories
            (and (str/includes? playbook "- name: Create Fairybox release directories")
                 (str/includes? playbook "        - releases\n        - incoming"))
            :migration-required
            (and (str/includes? playbook "- name: Require an explicitly migrated Fairybox release")
                 (str/includes? playbook "/var/lib/fairybox/current must be an explicitly provisioned release"))
            :launcher-source
            (str/includes? playbook "        src: scripts/fairybox-launch")
            :launcher-dest
            (str/includes? playbook "        dest: /usr/local/bin/fairybox-launch")
            :deployer-source
            (str/includes? playbook "        src: scripts/fairybox-deploy")
            :deployer-dest
            (str/includes? playbook "        dest: /usr/local/bin/fairybox-deploy")
            :service-launcher
            (str/includes? playbook "          ExecStart=/usr/local/bin/fairybox-launch")
            :fixed-aot-in-unit
            (str/includes? playbook "          ExecStart=/usr/local/bin/fairybox-launch -XX:AOTCache=")}))))
