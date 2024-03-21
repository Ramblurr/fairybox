
<div align="center">

<img src="https://raw.githubusercontent.com/Ramblurr/fairybox/main/resources/public/img/jukebox-300.png?token=GHSAT0AAAAAACGBSOKLUWXZADODJHH2W37GZPZQARQ" align="center" width="300px" height="300px"/>

# Fairybox

_Igniting children's imagination with tactile, screen-less storytelling and music._

</div>

<div align="center">

![License AGPL v3+](https://img.shields.io/github/license/ramblurr/fairybox)

</div>

---
_Fairybox_ is an open-source audio player designed specifically for the Raspberry Pi
platform, that aims to place the audio and imagination at center stage.


Unlike modern devices, _Fairybox_ operates without a screen and is powered by RFID
cards or tokens, making it an ideal, interactive audio experience for kids.

## Background

In today's digital age, screen time is a significant concern for parents
worldwide. Excessive use of phones and tablets has been linked to various
negative impacts on children's development and well-being.

Fairybox offers a delightful alternative, promoting auditory learning and
imaginative play through a tangible, interactive medium.

By eliminating screens and simplifying controls, Fairybox encourages more
natural, explorative interaction with technology, nurturing creativity and
listening skills in young minds.

In my own childhood, the simple joy of playing and listening to stories and
music on cassette tapes was a formative experience. The tangible interaction of
inserting a tape, pressing play, and flipping sides to continue the adventure
engaged both the mind and the senses.

With Fairybox, I've tried to recapture this tactile and auditory experience for
my children. Just as I once eagerly anticipated the physical flip of a cassette
to unveil the story's next chapter, my children can experience a similar thrill
by swapping cards to discover new tales and tunes.

## Technical Info

- **Platform**: Raspberry Pi 4
- **Features**:
  - Screen-free interaction
  - Battery-powered for portability
  - Simple GPIO button controls for power, playback, and volume
  - Uses MFRC 522 RFID reader for contactless card or token detection
  - Operable totally offline, with an optional web interface for parental control
- **Technology Stack**:
  - Programmed in Clojure for a robust, all-in-one backend
  - Deployable via Nix, ensuring a consistent, self-contained environment
- **Web Interface**: Available for managing playback and settings, enhancing parental control over the content and device usage.

## Photo Gallery

_Photos coming soon._

## Build Instructions

Pre-reqs:

* Java JDK 17+
* Babashka
* Clojure

```bash
bb uberjar
```


# License

Copyright (C) 2024 Casey Link

Fairybox is licensed under the [GNU Affero General Public License v3.0 or later
(AGPLv3+)](LICENSE.md).

