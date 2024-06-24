{
  config,
  lib,
  pkgs,
  ...
}:

{
  system.nixos.variant_id = "fairybox";
  sdImage.compressImage = false;
  sdImage.imageBaseName = "fairybox-rpi4-nix-sd-image";
}
