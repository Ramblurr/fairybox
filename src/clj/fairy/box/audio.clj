;; Copyright © 2025 Casey Link <casey@outskirtslabs.com>
;; SPDX-License-Identifier: EUPL-1.2
(ns fairy.box.audio
  "Public interface for audio subsystem"
  (:require
   [fairy.box.audio.system2 :as audio]))

(defn current-mixer! []
  (-> audio/audio-state
      deref
      :mixer))

(defn current-track! []
  (-> audio/audio-state
      deref
      :playback
      :current-track))

(defn current-playback! []
  (-> audio/audio-state
      deref
      :playback))

(defn current-play-queue! []
  (-> audio/audio-state
      deref
      :queue))

(defn metadata-for [audio-system item-path]
  (audio/metadata-for audio-system item-path))
