{
  description = "Caretto development shell: JDK 17, the Android SDK tools on PATH, and the emulator tooling";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";

  outputs =
    { nixpkgs, ... }:
    let
      # nixpkgs dropped x86_64-darwin in 26.11.
      systems = [
        "aarch64-darwin"
        "x86_64-linux"
        "aarch64-linux"
      ];
      forAllSystems = f: nixpkgs.lib.genAttrs systems (system: f nixpkgs.legacyPackages.${system});
    in
    {
      devShells = forAllSystems (pkgs: {
        default = pkgs.mkShell {
          # macOS ships lsof; a minimal Linux runner may not.
          packages = [
            pkgs.jdk17
            pkgs.bun
            pkgs.grpcurl
          ]
          ++ pkgs.lib.optional pkgs.stdenv.hostPlatform.isLinux pkgs.lsof;

          JAVA_HOME = pkgs.jdk17.home;
          CARETTO_DEVSHELL = "1";

          # The SDK stays outside Nix: Android Studio and sdkmanager already own it on the
          # machines this runs on, and a second copy would mean a second set of system images.
          shellHook = ''
            if [ -z "''${ANDROID_HOME:-}" ]; then
              if [ -n "''${ANDROID_SDK_ROOT:-}" ]; then
                ANDROID_HOME="$ANDROID_SDK_ROOT"
              elif [ "$(uname -s)" = Darwin ]; then
                ANDROID_HOME="$HOME/Library/Android/sdk"
              else
                ANDROID_HOME="$HOME/Android/Sdk"
              fi
            fi
            export ANDROID_HOME

            cmdline_tools="$ANDROID_HOME/cmdline-tools/latest/bin"
            if [ ! -d "$cmdline_tools" ]; then
              newest="$(ls -d "$ANDROID_HOME"/cmdline-tools/*/bin 2>/dev/null | sort -V | tail -n 1)"
              [ -n "$newest" ] && cmdline_tools="$newest"
            fi
            export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$cmdline_tools:$PATH"

            if [ ! -d "$ANDROID_HOME/platform-tools" ]; then
              echo "caretto: no Android SDK at $ANDROID_HOME; set ANDROID_HOME" >&2
            fi
          '';
        };
      });
    };
}
