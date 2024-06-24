{
  config,
  pkgs,
  lib,
  ...
}:
{
  security.rtkit.enable = false;
  hardware.pulseaudio.enable = pkgs.lib.mkForce false;
  sound.enable = true;
  services.pipewire = {
    enable = false;
    alsa.enable = true;
    alsa.support32Bit = false;
    pulse.enable = false;
    jack.enable = false;
    wireplumber.enable = true;
    audio.enable = true;
    #systemWide = true;
  };

  environment.systemPackages = with pkgs; [ alsa-utils ];

  #systemd.user.services = {
  #  pipewire.wantedBy = ["default.target"];
  #  wireplumber.wantedBy = ["default.target"];
  #};

  environment.etc."pipewire/pipewire.conf.d/100-user.conf" = {
    text = builtins.toJSON {
      "context.modules" = [
        {
          name = "libpipewire-module-rt";
          args = {
            "nice.level" = 20;
            "rt.prio" = 88;
            "rtportal.enabled" = false;
            "rtkit.enabled" = false;
            "rlimits.enabled" = true;
          };
          flags = [
            "ifexists"
            "nofail"
          ];
        }
      ];
    };
  };
}
