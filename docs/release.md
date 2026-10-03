# Releasing Read Me

The owner's runbook (REA-26). Everything up to a draft GitHub Release is scripted; publishing
is yours. Run commands from the primary checkout on `main`, synced (`git pull --ff-only`).

## Once: the release key

1. `npm run release:keystore`. It runs `keytool`, which asks for a store password and a key
   password (they never appear on a command line), creates `~/.android-keys/read-me-release.jks`
   and writes the certificate's public fingerprint to `release/signing-cert.sha256`.
2. Back up the `.jks` file and both passwords offline (a password manager plus an offline copy).
   Losing them means no update can ever be installed over the published app.
3. Add four lines to `~/.gradle/gradle.properties`, then `chmod 600 ~/.gradle/gradle.properties`:
   ```
   READ_ME_STORE_FILE=/home/<you>/.android-keys/read-me-release.jks
   READ_ME_KEY_ALIAS=read-me
   READ_ME_STORE_PASSWORD=<store password>
   READ_ME_KEY_PASSWORD=<key password>
   ```
4. Commit `release/signing-cert.sha256` (public, not a secret) to `main` through a PR.

Builds stay debug-signed unless `-PreadmeSign=release` is passed, so these properties change
nothing for day-to-day builds, device scripts or CI.

## Per release

1. Build and test one build. Keep its APK: it is the build the acceptance runs installed.
   ```
   npm run build:release
   cp android/app/build/outputs/apk/release/app-release.apk release/tested-$(git rev-parse --short HEAD).apk
   npm run device:bridge && npm run device:ui && npm run device:intake
   npm run device:accept-share        # unlock the phone when it says so
   npm run device:playback
   AIRPLANE=1 GAP_MINUTES=10 npm run device:gap
   scripts/fdroid-scan.sh             # == result: CLEAN
   npm run repro                      # repro: SAME
   ```
   Record the results in `AGENTS.md` Known state (date, device, build).
2. `npm run release:apk -- release/tested-<sha>.apk`. It builds with your key, refuses any
   other signer, checks the signed APK's content equals the tested one (apksigcopier), and
   writes `release/read-me-<version>.apk` and `release/SHA256SUMS`.
3. Optional, on the phone: a release-signed APK will not install over the debug-signed one.
   `adb uninstall io.loopstring.readme` deletes the app and its data; then
   `adb install release/read-me-<version>.apk` and open it. From then on the device scripts
   need a release-signed build too, or another uninstall.

## Publish (yours)

1. The draft release: `gh release view v<version>` shows the APK, `SHA256SUMS` and the notes.
   Publishing creates the tag on `main`: `gh release edit v<version> --draft=false`.
2. Make the repository public (F-Droid builds from public source). First check the history
   holds nothing private: read what `git log -p --all | grep -iE '^\+.*(password|BEGIN .*PRIVATE)'`
   prints. On 2026-10-03 the hits were comments, script messages and the debug keystore's
   public `android` password (React Native's template), nothing secret.
   Then `gh repo edit JoshShearer/Read-Me --visibility public` (gh 2.45 here; newer gh also wants
   `--accept-visibility-change-consequences`).
3. F-Droid merge request:
   1. Fork `https://gitlab.com/fdroid/fdroiddata`.
   2. Copy `fdroid/io.loopstring.readme.yml` to `metadata/` and `fdroid/srclibs/hermes.yml`
      to `srclibs/`.
   3. To have F-Droid publish your signed APK instead of signing its own: add
      `Binaries: https://github.com/JoshShearer/Read-Me/releases/download/v%v/read-me-%v.apk`
      and `AllowedAPKSigningKeys: <the fingerprint in release/signing-cert.sha256>` to the
      recipe. F-Droid then builds from source and publishes yours only if the two match
      (`npm run repro` checks this on one machine).
   4. `fdroid lint io.loopstring.readme` in the fork, then open the merge request.

## Not established

- F-Droid's build server has not run this recipe. `npm run repro` compares two builds on this
  machine with its caches and JDK 21; the buildserver has another JDK and paths.
- Whether F-Droid accepts React Native's `react-android` and `hermes-android` AARs (prebuilt
  native libraries from Maven Central) or asks for them built from source (SPIKE-04).
