{ pkgs, inputs, ... }:
let
  pigpio = pkgs.callPackage ../../../../packages/pigpio { };
in
{
  imports = [
    #inputs.nixos-raspberrypi.inputs.nixos-hardware.nixosModules.raspberry-pi-4
    ./hardware.nix
    #./gpio.nix
    #./audio.nix
    #./rpi-kernel.nix
    #./mainline-kernel.nix
  ];
  time.timeZone = "Europe/Berlin";
  i18n.defaultLocale = "en_US.UTF-8";
  users.users.root.initialHashedPassword = "$y$j9T$/29noYRT4W/22Hy4lW7B71$MNtGBgjk01Zo3LtKgFRQtwaXdv6I15oiSgGGCMkt9s2"; # =test use mkpasswd to generate
  system = {
    stateVersion = "23.11";
  };
  networking = {
    wireless.enable = true;
    useDHCP = true;
  };
  environment.systemPackages = with pkgs; [
    git
    #raspberrypi-firmware
    #raspberrypi-wireless-firmware
    raspberrypifw
    python311
    #pigpio-py
    python311Packages.pip
    python311Packages.spidev
    #python311Packages.rpi-gpio2
    #python311Packages.libgpiod
    rpi-gpio2_1
    python311Packages.pylibgpiod_11
    python311Packages.evdev
    gcc
    dtc
    raspberrypifw
    gpio-utils
    pigpio
    zulu17
    libvlc
    unstable.babashka
  ];
  ####
  #services.journald.storage = "volatile";
  #networking.firewall.allowedTCPPorts = [
  #  22 # ssh
  #  80 # http
  #  3000 # dev http
  #  7000 # nrepl
  #  7001 # portal
  #  7002 # dev nrepl
  #];

  #users.groups.gpio = { };
  #users.groups.spi = { };
  #users.groups.i2c = { };
  #services.udev.extraRules = ''
  #  SUBSYSTEM=="bcm2835-gpiomem", KERNEL=="gpiomem", GROUP="gpio",MODE="0660"
  #  SUBSYSTEM=="bcm2711-gpiomem", KERNEL=="gpiomem", GROUP="gpio",MODE="0660"
  #  SUBSYSTEM=="gpio", KERNEL=="gpiochip*", ACTION=="add", RUN+="${pkgs.bash}/bin/bash -c 'chown root:gpio  /sys/class/gpio/export /sys/class/gpio/unexport ; chmod 220 /sys/class/gpio/export /sys/class/gpio/unexport'"
  #  SUBSYSTEM=="gpio", KERNEL=="gpio*", ACTION=="add",RUN+="${pkgs.bash}/bin/bash -c 'chown root:gpio /sys%p/active_low /sys%p/direction /sys%p/edge /sys%p/value ; chmod 660 /sys%p/active_low /sys%p/direction /sys%p/edge /sys%p/value'"
  #  SUBSYSTEM=="i2c-dev", GROUP="i2c",  MODE="0666"
  #  SUBSYSTEM=="spidev", GROUP="spi", MODE="0660"
  #'';
  #systemd.services.pigpiod = {
  #  enable = true;
  #  wantedBy = [ "multi-user.target" ];
  #  description = "pigpio daemon";
  #  serviceConfig = {
  #    Type = "forking";
  #    PIDFile = "pigpio.pid";
  #    ExecStart = "${pigpio}/bin/pigpiod -l -n 127.0.0.1 -t0";
  #  };
  #};
  #systemd.tmpfiles.rules = [
  #  "d /var/lib/fairybox 750 ramblurr ramblurr"
  #  "f /var/lib/systemd/linger/ramblurr"
  #];
  #systemd.services.fairybox = {
  #  enable = true;
  #  wantedBy = [ "multi-user.target" ];
  #  after = [ "pigpiod.service" ];
  #  description = "fairybox";
  #  path = [ pkgs.util-linux ]; # diozero needs this
  #  environment = {
  #    NREPL_HOST = "0.0.0.0";
  #    PORT = "80";
  #    DB_PATH = "/var/lib/fairybox/db.edn";
  #    MEDIA_DIR = "/home/ramblurr/media";
  #    LD_LIBRARY_PATH = "${pkgs.vlc}/lib:${pigpio}/lib";
  #  };
  #  serviceConfig = {
  #    Type = "simple";
  #    User = "ramblurr";
  #    SupplementaryGroups = "gpio spi i2c audio";
  #    #SupplementaryGroups = "gpio spi i2c audio pipewire";
  #    WorkingDirectory = "/var/lib/fairybox";
  #    ExecStart = "${pkgs.zulu17}/bin/java -XX:-OmitStackTraceInFastThrow -DPIGPIOD_HOST=127.0.0.1 -jar /var/lib/fairybox/box-standalone.jar";
  #    AmbientCapabilities = "CAP_NET_BIND_SERVICE";
  #    Restart = "on-failure";
  #    TimeoutStopSec = "30";
  #  };
  #};
}
