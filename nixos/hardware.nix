{
  config,
  lib,
  pkgs,
  ...
}:

{
  # "${nixpkgs}/nixos/modules/installer/sd-card/sd-image-aarch64.nix" creates a
  # disk with this label on first boot. Therefore, we need to keep it. It is the
  # only information from the installer image that we need to keep persistent
  fileSystems."/" = {
    device = "/dev/disk/by-label/NIXOS_SD";
    fsType = "ext4";
  };
  boot = {
    tmp.useTmpfs = true;
    loader = {
      generic-extlinux-compatible.enable = lib.mkDefault true;
      grub.enable = lib.mkDefault false;
    };

    initrd.availableKernelModules = [
      "usbhid"
      "usb_storage"
      "vc4"
      "pcie_brcmstb" # required for the pcie bus to work
      "reset-raspberrypi" # required for vl805 firmware to load
    ];

    kernelParams =
      #["console=tty0"]
      [ ] ++ (
        # rpi 3
        # [ "cma=32M" ]
        # rpi4
        [ "cma=128M" ]);
  };
  nix.settings = {
    experimental-features = lib.mkDefault "nix-command flakes";
    trusted-users = [
      "root"
      "@wheel"
    ];
  };
  hardware.enableRedistributableFirmware = true;
  services.timesyncd.enable = true;
  networking = {
    useDHCP = true;
    interfaces.eth0.useDHCP = true;
  };

  # Source: https://github.com/NixOS/nixpkgs/blob/master/nixos/modules/installer/sd-card/sd-image-aarch64-new-kernel-no-zfs-installer.nix
  # Makes `availableOn` fail for zfs, see <nixos/modules/profiles/base.nix>.
  # This is a workaround since we cannot remove the `"zfs"` string from `supportedFilesystems`.
  # The proper fix would be to make `supportedFilesystems` an attrset with true/false which we
  # could then `lib.mkForce false`
  nixpkgs.overlays = [
    (final: super: {
      zfs = super.zfs.overrideAttrs (_: {
        meta.platforms = [ ];
      });
    })

    # Workaround: https://github.com/NixOS/nixpkgs/issues/154163
    # modprobe: FATAL: Module sun4i-drm not found in directory
    (final: super: {
      makeModulesClosure = x: super.makeModulesClosure (x // { allowMissing = true; });
    })

    (final: prev: {
      diffutils = prev.diffutils.overrideAttrs (_: {
        doCheck = false;
      });

      #  gnugrep = prev.diffutils.overrideAttrs (_: {
      #    doCheck = false;
      #  });
    })

    (final: super: {
      llvmPackages = super.llvmPackages_14;
      cmake = super.cmake.overrideAttrs (old: {
        env.NIX_CFLAGS_COMPILE = "-latomic";
      });
      jbig2dec = super.jbig2dec.overrideAttrs (old: {
        configureScript = "./autogen.sh";
        preConfigure = "";
      });
      cups-filters = super.cups-filters.overrideAttrs (old: {
        configureFlags = old.configureFlags ++ [ "--with-cups-config=${pkgs.cups.dev}/bin/cups-config" ];
        nativeBuildInputs = old.nativeBuildInputs ++ [ pkgs.glib ];
      });
    })
  ];

  # Trim some fat
  # This causes an overlay which causes a lot of rebuilding
  #environment.noXlibs = lib.mkForce false;
  ## Limit the journal size to X MB or last Y days of logs
  #services.journald.extraConfig = ''
  #  SystemMaxUse=1536M
  #  MaxFileSec=60day
  #'';
  #services.fwupd.enable = false;
  #services.udisks2.enable = false;
  #programs.command-not-found.enable = false;
  #boot.enableContainers = false;
}
