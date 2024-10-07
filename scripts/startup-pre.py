#!/usr/bin/env python3
import signal
import os
import sys
import urllib.request
import urllib.error
from time import sleep
from gpiozero import PWMLED


LED_VOLDOWN = PWMLED(15)
LED_PREV = PWMLED(14)
LED_PLAY = PWMLED(12)
LED_NEXT = PWMLED(7)
LED_VOLUP = PWMLED(13)


def cleanup():
    LED_VOLDOWN.off()
    LED_VOLUP.off()
    LED_VOLDOWN.close()
    LED_VOLUP.close()
    sleep(0.1)
    LED_PREV.off()
    LED_NEXT.off()
    LED_PREV.close()
    LED_NEXT.close()
    sleep(0.1)
    LED_PLAY.off()
    LED_PLAY.close()


def sigterm_handler(*_):
    cleanup()
    sys.exit(0)


FAIRYBOX_API = os.getenv("FAIRYBOX_API", "http://localhost:3000/api")


def is_fairybox_ready():
    try:
        url = f"{FAIRYBOX_API}/ready"
        print(f"checking ready: {url}")
        response = urllib.request.urlopen(url)
        print(f"checking ready got: {response.getcode()}")
        return response.getcode() == 200
    except urllib.error.HTTPError as e:
        return False
    except urllib.error.URLError:
        return False


def leds_on():
    try:
        response = urllib.request.urlopen(f"{FAIRYBOX_API}/leds-on")
        print(f"leds on = {response.getcode()}")
        return response.getcode() == 200
    except urllib.error.HTTPError as e:
        return False
    except urllib.error.URLError:
        return False


def initiate_animation():
    pos = 1
    direction = 0
    fairybox_ready = False
    while not fairybox_ready:
        if pos == 1:
            LED_PLAY.pulse(n=1, fade_in_time=0.2, fade_out_time=0.8)
            sleep(0.2)
        elif pos == 2:
            LED_PREV.pulse(n=1, fade_in_time=0.2, fade_out_time=0.8)
            LED_NEXT.pulse(n=1, fade_in_time=0.2, fade_out_time=0.8)
            sleep(0.2)
        elif pos == 3:
            LED_VOLUP.pulse(n=1, fade_in_time=0.2, fade_out_time=0.8)
            LED_VOLDOWN.pulse(n=1, fade_in_time=0.2, fade_out_time=0.8)
            fairybox_ready = is_fairybox_ready()
            sleep(0.8)
            pos = 0
        pos += 1
        sleep(0.04)


def main():
    dummy = ""
    while dummy == "":
        sleep(5)


if __name__ == "__main__":
    signal.signal(signal.SIGTERM, sigterm_handler)
    print("starting animation")
    initiate_animation()
    print("cleaning up")
    cleanup()
    print("calling api to turn lights on")
    leds_on()
    print("exit")
