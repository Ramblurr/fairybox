{
  config,
  pkgs,
  lib,
  inputs,
  ...
}:
let
  # these scripts are for the rpi onoff shim from Pimoroni
  # the gpio-shutoff script is called by systemd when a poweroff command happens
  # the onoff-shim-trigger script is ran as a daemon and monitors the trigger pin and initiates a shutdown
  poweroffPin = "4";
  triggerPin = "17";
  gpio-shutoff = pkgs.writeScriptBin "gpio-shutoff" ''
    #!${pkgs.bash}/bin/bash
    poweroff_pin="${poweroffPin}"
    led_pin="${triggerPin}}"
    if [ "$1" = "poweroff" ]; then
        ${pkgs.coreutils}/bin/echo $led_pin > /sys/class/gpio/export
        ${pkgs.coreutils}/bin/echo out > /sys/class/gpio/gpio$led_pin/direction
            for iteration in 1 2 3; do
                ${pkgs.coreutils}/bin/echo 0 > /sys/class/gpio/gpio$led_pin/value
                sleep 0.2
                ${pkgs.coreutils}/bin/echo 1 > /sys/class/gpio/gpio$led_pin/value
                sleep 0.2
           done
        ${pkgs.coreutils}/bin/echo $poweroff_pin > /sys/class/gpio/export
        ${pkgs.coreutils}/bin/echo out > /sys/class/gpio/gpio$poweroff_pin/direction
        ${pkgs.coreutils}/bin/echo 0 > /sys/class/gpio/gpio$poweroff_pin/value
    fi
  '';
  onoff-shim-trigger = pkgs.writeScriptBin "onoff-shim-trigger" ''
    #!${pkgs.bash}/bin/bash
    trigger_pin="${triggerPin}"
    echo $trigger_pin > /sys/class/gpio/export
    echo in > /sys/class/gpio/gpio$trigger_pin/direction
    power=$(cat /sys/class/gpio/gpio$trigger_pin/value)
    [ $power = 0 ] && switchtype="1" #Not a momentary button
    [ $power = 1 ] && switchtype="0" #Momentary button
    until [ $power = $switchtype ]; do
        power=$(cat /sys/class/gpio/gpio$trigger_pin/value)
        # echo power="$power" switchtype="$switchtype"
        sleep 1
    done
    echo "Powering Off!"
    ${pkgs.systemd}/bin/systemctl poweroff
  '';
in
{
  #raspberry-pi.hardware.hifiberry-dac.enable = false;
  #raspberry-pi.hardware.spi0-1cs.enable = false;
  raspberry-pi.hardware.platform.type = "rpi4";
  raspberry-pi.hardware.apply-overlays-dtmerge.enable = lib.mkForce true;
  hardware = {
    firmware = [ pkgs.wireless-regdb ];
    i2c.enable = true;

    deviceTree = {
      enable = true;
      filter = lib.mkForce "bcm2711-rpi-4-b.dtb";
      overlays = [
        {
          # known working
          #name = "spi0-0cs";
          #dtsText = builtins.readFile ./overlays/spi0-0cs-mainline.dts;

          #name = "spi0-0cs.dts";
          #dtsText = builtins.readFile ./overlays/spi0-0cs-overlay.dts;

          # only enable 1 chip select pin, gpio 8, leaving the other pin free for gpio
          name = "spi0-1cs-overlay";
          dtsText = builtins.readFile ./overlays/spi0-1cs-overlay.dts;
        }
        {
          name = "hifiberry-dac.dts";
          dtsText = builtins.readFile ./overlays/hifiberry-dac.dts;
        }
        {
          # Allows software made for the rpi to detect that it is in fact running on an rpi
          name = "rpi4-cpu-revision";
          dtsText = ''
            /dts-v1/;
            /plugin/;

            / {
              compatible = "raspberrypi,4-model-b";

              fragment@0 {
                target-path = "/";
                __overlay__ {
                  system {
                    linux,revision = <0x00d03114>;
                  };
                };
              };
            };
          '';
        }
      ];
    };
  };

  systemd.services.onoff-trigger = {
    enable = true;
    wantedBy = [ "multi-user.target" ];
    description = "OnOff SHIM daemon";
    path = [ pkgs.util-linux ];
    serviceConfig = {
      Type = "simple";
      ExecStart = "${onoff-shim-trigger}/bin/onoff-shim-trigger";
      Restart = "on-failure";
    };
  };
  systemd.shutdown.gpio-shutoff = "${gpio-shutoff}/bin/gpio-shutoff";
}
