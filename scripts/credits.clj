(ns credits
  (:require [clojure.string :as string]))

(def licenses {"https://creativecommons.org/licenses/by/3.0/" "CC BY 3.0 DEED"
               "https://creativecommons.org/licenses/by-nc/3.0/" "CC BY-NC 3.0 DEED"})
(def credits [{:author "Yoyon Pujiyono"
               :link "https://thenounproject.com/icon/repeat-play-2447134/"
               :license "https://creativecommons.org/licenses/by/3.0/"}
              {:author "Yoyon Pujiyono"
               :link "https://thenounproject.com/icon/repeat-one-2447137/"
               :license "https://creativecommons.org/licenses/by/3.0/"}
              {:author "Astonish"
               :link "https://thenounproject.com/icon/album-cover-1433586/"
               :license "https://creativecommons.org/licenses/by/3.0/"}
              {:author "Yoyon Pujiyono"
               :link "https://thenounproject.com/icon/back-step-2506788/"
               :license "https://creativecommons.org/licenses/by/3.0/"}
              {:author "Yoyon Pujiyono"
               :link "https://thenounproject.com/icon/next-step-2506791/"
               :license "https://creativecommons.org/licenses/by/3.0/"}
              {:author "Yoyon Pujiyono"
               :link "https://thenounproject.com/icon/volume-mute-2506797/"
               :license "https://creativecommons.org/licenses/by/3.0/"}
              {:author "Yoyon Pujiyono"
               :link "https://thenounproject.com/icon/pause-2506789/"
               :license "https://creativecommons.org/licenses/by/3.0/"}
              {:author "Yoyon Pujiyono"
               :link "https://thenounproject.com/icon/play-2506787/"
               :license "https://creativecommons.org/licenses/by/3.0/"}
              {:author "Yoyon Pujiyono"
               :link "https://thenounproject.com/icon/fast-forward-2506785/"
               :license "https://creativecommons.org/licenses/by/3.0/"}
              {:license "https://creativecommons.org/licenses/by/3.0/"
               :author "Yoyon Pujiyono"
               :link "https://thenounproject.com/icon/rewind-2506784/"}
              {:license "https://creativecommons.org/licenses/by/3.0/"
               :author "Yoyon Pujiyono"
               :link "https://thenounproject.com/icon/middle-volume-2506798/"}
              {:license "https://creativecommons.org/licenses/by/3.0/"
               :author "Yoyon Pujiyono"
               :link "https://thenounproject.com/icon/volume-down-2506806/"}
              {:license "https://creativecommons.org/licenses/by/3.0/"
               :author "Yoyon Pujiyono"
               :link "https://thenounproject.com/icon/volume-up-2506805/"}
              {:license "https://creativecommons.org/licenses/by/3.0/"
               :author "Yoyon Pujiyono"
               :link "https://thenounproject.com/icon/download-2506781/"}
              {:license "https://creativecommons.org/licenses/by/3.0/"
               :author "Yoyon Pujiyono"
               :link "https://thenounproject.com/icon/play-list-2506807/"}

              {:author "Yoyon Pujiyono"
               :license "https://creativecommons.org/licenses/by/3.0/"
               :link "https://thenounproject.com/icon/new-3190873/"}
              {:author "Yoyon Pujiyono"
               :link "https://thenounproject.com/icon/disable-3190864/"
               :license "https://creativecommons.org/licenses/by/3.0/"}

              {:license "https://creativecommons.org/licenses/by/3.0/"
               :link "https://thenounproject.com/icon/radio-frequency-identification-4500829/"
               :author "Iconbunny"}

              {:link "https://freesound.org/people/SergeQuadrado/sounds/476714/"
               :author "SergeQuadrado"
               :license "https://creativecommons.org/licenses/by-nc/3.0/"
               :type "sound"
               :name "Magic Harp Logo"}
              {:link "https://freesound.org/people/SergeQuadrado/sounds/476709/"
               :author "SergeQuadrado"
               :name "Celtic Positive Intro"
               :license "https://creativecommons.org/licenses/by-nc/3.0/"
               :type "sound"}])

(defn credit->markdown [{:keys [link author license type name]}]
  (if name
    (format "* [%s](%s) by %s. License: [%s](%s)" name link author (get licenses license) license)
    (format "* %s by %s. License: [%s](%s)"  link author (get licenses license) license)))

(defn ^:export build-attribution [_]
  (let [content (string/join "\n" (map credit->markdown credits))
        start-marker "<!--START CREDITS-->"
        end-marker "<!--END CREDITS-->"
        readme (slurp "README.md")]
    (spit "README.md" (str (subs readme 0 (.indexOf readme start-marker))
                           start-marker "\n" content "\n"
                           (subs readme (.indexOf readme end-marker))))))
