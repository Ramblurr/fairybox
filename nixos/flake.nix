{
  description = "Build Raspberry PI 4 image";
  inputs = {
    nixpkgs.url = "github:nixos/nixpkgs/nixos-unstable";
    nixos-hardware.url = "github:nixos/nixos-hardware";
    nixos-raspberrypi.url = "github:ramblurr/nixos-raspberrypi/dev";
    nixos-raspberrypi.inputs.nixpkgs.follows = "nixpkgs";
    nixos-raspberrypi.inputs.nixos-hardware.follows = "nixos-hardware";
  };
  outputs =
    { self, nixpkgs, ... }:

    let
    in
    {

      nixosConfigurations = {
        fairybox = nixpkgs.lib.nixosSystem {
          system = "aarch64-linux";
          modules = [ ./configuration.nix ];
        };
      };

      images = {
        fairybox =
          (self.nixosConfigurations.fairybox.extendModules {
            modules = [
              "${nixpkgs}/nixos/modules/installer/sd-card/sd-image-aarch64.nix"
              ./sdimage.nix
            ];
          }).config.system.build.sdImage;
      };
    };
}
