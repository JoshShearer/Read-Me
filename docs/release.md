# Releasing Read Me

The owner's runbook (REA-26). Building, checking and signing are scripted; the draft release
is one `gh` command (Per release, step 3); publishing is yours. Run commands from the primary checkout on `main`, synced (`git pull --ff-only`).

## Once: the release key

1. `npm run release:keystore`. It runs `keytool`, which asks for the keystore password (it
   never appears on a command line; JDK 21 makes a PKCS12 keystore, whose key password is the
   same one), creates `~/.android-keys/read-me-release.jks` and writes the certificate's public
   fingerprint to `release/signing-cert.sha256`.
2. Back up the `.jks` file and its password offline (a password manager plus an offline copy).
   Losing them means no update can ever be installed over the published app.
3. Add four lines to `~/.gradle/gradle.properties`, then `chmod 600 ~/.gradle/gradle.properties`:
   ```
   READ_ME_STORE_FILE=/home/<you>/.android-keys/read-me-release.jks
   READ_ME_KEY_ALIAS=read-me
   READ_ME_STORE_PASSWORD=<store password>
   READ_ME_KEY_PASSWORD=<the same password: PKCS12 has one>
   ```
4. Commit `release/signing-cert.sha256` (public, not a secret) to `main` through a PR.

Builds stay debug-signed unless `-PreadmeSign=release` is passed, so these properties change
nothing for day-to-day builds, device scripts or CI.

## Per release

1. Build and test one build. Keep its APK: it is the build the acceptance runs installed.
   Build it in a fresh worktree or clone if you can (`build:release` clears React Native's
   bundle output, which kept stale images in v1.0.0, but a fresh tree is the safe habit).
   ```
   npm run build:release
   cp android/app/build/outputs/apk/release/app-release.apk release/tested-$(git rev-parse --short HEAD).apk
   npm run device:bridge && npm run device:ui && npm run device:intake
   npm run device:accept-share        # unlock the phone when it says so
   npm run device:playback
   AIRPLANE=1 GAP_MINUTES=10 npm run device:gap
   npm run device:screens
   scripts/fdroid-scan.sh             # == result: CLEAN
   npm run repro -- release/tested-<sha>.apk   # repro: SAME, and the recipe's build equals ours
   npm run fdroid:build -- release/tested-<sha>.apk   # fdroid:build: SAME (about 20 min; docker)
   ```
   Write the results down (date, device, build); they go into `AGENTS.md` Known state by a PR
   after the release. `release:apk` refuses a dirty tree, and a commit now would move HEAD off
   the tested commit.
2. `npm run release:apk -- release/tested-<sha>.apk`. It refuses an APK that
   `fdroid:build` has not matched (`release/fdroid-verified`), builds with your key, refuses any
   other signer, checks the signed APK's content equals the tested one (apksigcopier), and
   writes `release/read-me-<version>.apk` and `release/SHA256SUMS`.
3. The draft release, tagged at the tested commit (not whatever `main` is when you publish, so
   the tag's source is what F-Droid's `commit: v<version>` builds):
   ```
   gh release create v<version> --draft --target <full sha of the tested commit> \
     --title "Read Me <version>" --notes-file <notes> release/read-me-<version>.apk release/SHA256SUMS
   ```
4. Optional, on the phone: a release-signed APK will not install over the debug-signed one.
   `adb uninstall io.loopstring.readme` deletes the app and its data; then
   `adb install release/read-me-<version>.apk` and open it. From then on the device scripts
   need a release-signed build too, or another uninstall.

## Publish (yours)

1. The draft release: `gh release view v<version>` shows the APK, `SHA256SUMS` and the notes.
   Publishing creates the tag at the commit the draft names (`--target`, the tested commit):
   `gh release edit v<version> --draft=false`.
2. Make the repository public (F-Droid builds from public source). First check the history
   holds nothing private: read what `git log -p --all | grep -iE '^\+.*(password|BEGIN .*PRIVATE)'`
   prints. On 2026-10-03 the hits were comments, script messages and the debug keystore's
   public `android` password (React Native's template), nothing secret.
   Then `gh repo edit JoshShearer/Read-Me --visibility public` (gh 2.45 here; newer gh also wants
   `--accept-visibility-change-consequences`).
3. F-Droid: the merge request is https://gitlab.com/fdroid/fdroiddata/-/merge_requests/51088
   (branch `io.loopstring.readme` of `gitlab.com/Joshshearer/fdroiddata`). For each release,
   in that fork's `metadata/io.loopstring.readme.yml`, replace the build entry with the one in
   `fdroid/io.loopstring.readme.yml`, with `commit:` set to the tag's full hash
   (`git rev-parse v<version>^{commit}`; fdroiddata refuses a tag name there), and set
   `CurrentVersion`/`CurrentVersionCode`. Then `fdroid rewritemeta io.loopstring.readme` (it
   strips the comments, which fdroiddata's CI requires) and `fdroid lint io.loopstring.readme`,
   commit and push; the merge request's pipeline reruns. Before the first merge only the latest
   version may be listed. After it is merged, `AutoUpdateMode: Version` and `UpdateCheckMode:
   Tags` make F-Droid pick up new tags itself.

## Not established

- F-Droid's production build server has not run this recipe. `npm run fdroid:build` runs
  fdroiddata's own CI build job in F-Droid's buildserver image on this machine; the merge
  request's pipeline runs the same job on GitLab.
- Whether F-Droid accepts React Native's `react-android` and `hermes-android` AARs (prebuilt
  native libraries from Maven Central) or asks for them built from source (SPIKE-04).
