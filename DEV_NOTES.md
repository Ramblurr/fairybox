# Dev Notes

# RPI Setup

### Hardware overlays

```
# dtparam=audio=on # comment out
dtoverlay=vc4-kms-v3d,noaudio #change existing line

# new lines
dtoverlay=hifiberry-dac
# only enable 1 chip select pin, gpio 8, leaving the other pin free for gpio
dtoverlay=spi0-1cs,cs0_pin=8
```

### Hardware interop

```
sudo apt install -y pigpio pigpio-tools pigpiod
sudo systemctl enable --now pigpiod.service 
```

```
sudo systemctl edit pigpiod.service
```
add
```
[Service]
ExecStart=
ExecStart=/usr/bin/pigpiod -l -n 127.0.0.1 -t0
```

* `-n 127.0.0.1` expose local tcp socket on port 8888 for gpio control
* `-t0` set clock periphial to type 0 (PWM) instead of the default PCM (which interferes with audio playback)

### Java JDK
```
sudo apt install -y gnupg ca-certificates curl
curl -s https://repos.azul.com/azul-repo.key | sudo gpg --dearmor -o /usr/share/keyrings/azul.gpg
echo "deb [signed-by=/usr/share/keyrings/azul.gpg] https://repos.azul.com/zulu/deb stable main" | sudo tee /etc/apt/sources.list.d/zulu.list
sudo apt update
sudo apt install -y zulu17-ca-jdk-headless
java -version
```


### Docker


```
sudo apt-get update
sudo apt-get install ca-certificates curl gnupg
sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/debian/gpg | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
sudo chmod a+r /etc/apt/keyrings/docker.gpg

# Add the repository to Apt sources:
echo \
  "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/debian \
  $(. /etc/os-release && echo "$VERSION_CODENAME") stable" | \
  sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
sudo apt-get update
sudo apt-get -y install docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
```

## Pipewire

```
# add deb http://deb.debian.org/debian bookworm-backports main
# to /etc/apt/sources.list
sudo apt update
sudo apt install -t bookworm-backports pipewire wireplumber pipewire-alsa
sudo loginctl enable-linger admin
sudo modprobe snd-aloop id=RoonLoopback
systemctl --user enable --now pipewire wireplumber
systemctl --user status pipewire
```

For reboot persistent loopback:

`/etc/modules` should contain a line `snd-aloop`
`/etc/modprobe.d/snd-aloop.conf` should contain `options snd-aloop id=RoonLoopback`

## VLC

```
sudo apt install vlc-bin vlc-plugin-base
```

# Library Notes

https://javadoc.io/doc/uk.co.caprica/vlcj/latest/index.html
https://www.javadoc.io/doc/com.diozero/diozero-core/1.3.5/index.html

# Design

Audio files will be stored in a directory "audio"

Every directory under "audio" can be assigned to an RFID card.

Tracks in the folder we be played as a playlist in filename lexical order.

We need a store a map of rfid uids -> audio folders

Button commands:
* Play/Pause
* Next
* Prev
* Vol up
* Vol down

LED Control:
* starting animation
* after start LEDs go full bright

Roon Endpoint:
* We will run roon bridge to provide a Roon endpoint for streaming audio not on the pi
* Roon bridge requires exlusive access to an ALSA device. This device will be a loopback device linked to pipewire
* NICE TO HAVE: Detect when local or roon audio starts and stop the other if its playing

Local Media Playback:
* vlc via vlcj will be used to playback local media over pipewire

Home Assistant Integration:
* Use the mqtt mediaplayer component https://github.com/TroyFernandes/hass-mqtt-mediaplayer
* MQTT client lib: https://github.com/clojurewerkz/machine_head


Local Web UI:
* View assigned rfid tags
* Assign new rfid tags
* View local playback state
  * Title / Album / Artist
* Local playback controls
  * All from buttons above plus: repeat-one, repeat-all, absolute vol control 
  
* File browsing and uploading will be provided by filebrowser: https://github.com/filebrowser/filebrowser



### Code Design

Ok so we have some services:

* Audio playback
* RFID Scanning
* Button input
* Web UI input
* MQTT Home Assistant input
* Coordinator

#### Audio playback

Audio playback uses the vlcj library to play media files over the local speaker.

It also is responsible for control of that playback: play/pause, next, prev, vol up/down, etc.

vlcj does have some particular requirements about which threads you can call it from (https://github.com/caprica/vlcj#threading-model)

All callbacks from vlcj come from the "native event callback" thread. But control of the native vlc happens on the "libvlc" thread. See the link for more info.

Runtime State:

* Current track
* Play Queue
* Playback mode (loop-all, loop-one)

Persistent State: Nothing? We don't plan on supporting resuming playback or anything across app restarts. Volume can be reset to the default. We do want to support a maximum volume level (kiddos like to crank it), but maybe that doesn't get stored here.


#### RFID Scanning

This needs to run in its own thread in a forever loop.

It is a little daemon like system that just waits for cards to be scanned.

When a card scan is detected it should send it somewhere. Where will it send it?

Configuration:
* GPIO pin numbers for the rfid scanner

Runtime State: Just the native diozero hardware handles

Persistent State: none

#### Button Input

Similar to the RFID scanning thread it should run in its own loop. Maybe the same loop as the RFID scanner?

It should send button input events somewhere..

Configuration:
* GPIO pin numbers for the buttons
* Mapping of button to action

Runtime State: Native diozero hardware handles


#### Web UI Input / MQTT Home Assistant Input

Configuration: routes, ports, etc

Runtime State:

* Needs to be able to view current media state (track, queue)
* Needs to be able to generate control events

Persistent State: nothing

#### Coordinator

Ok, this is what I am calling the thing that ties all these services together.

It should be able to receive control events and know how to dispatch them.

It should be able to receive RFID scans and know what to do with them.

It should be able to provide consumers info (what is currently playing? what is playing next?)

Since the hardware system runs a forever loop it needs to be on its own thread. So it could communicate via channels. Specifically, it can send input events to the coordinator via a channel.

Audio playback also needs to have careful thread management.



