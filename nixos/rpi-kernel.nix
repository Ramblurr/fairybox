{
  config,
  pkgs,
  lib,
  inputs,
  unstable,
  ...
}:
{
  #boot.kernelPackages = pkgs.linuxPackagesFor (pkgs.linux_rpi4);
  boot.kernelPackages = unstable.linuxPackages_rpi4;
  boot.kernelParams = [
    "snd_bcm2835.enable_hdmi=0"
    "iomem=relaxed"
    "strict-devmem=0" # rpi kernels only
  ];
  boot.blacklistedKernelModules = [ "snd_bcm2835" ];
  boot.kernelModules = [
    #"pwm_bcm2835"
    #"w1-gpio"
  ];
}
