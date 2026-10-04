#!/usr/bin/env bash
# release.sh: a debug-signed APK is refused; no script passes a password on a command line.
set -uo pipefail
cd "$(dirname "$0")/../.."
source scripts/lib/release.sh
fail=0
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
JAR=$(ls -d "$SDK"/platforms/*/android.jar 2>/dev/null | sort -V | tail -1)
if [ ! -x "${BT:-}/apksigner" ] || [ -z "$JAR" ]; then echo "SKIP: no Android build-tools or platform here"; exit 0; fi
# A minimal APK: apksigner needs a binary manifest, which aapt2 writes.
printf '<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="x.fixture"><uses-sdk android:minSdkVersion="24"/></manifest>' > "$T/M.xml"
"$BT/aapt2" link -o "$T/u.zip" -I "$JAR" --manifest "$T/M.xml" || { echo "FAIL: aapt2 could not build the fixture"; exit 1; }
# The debug keystore's passwords are public ("android"), so argv is acceptable here only.
# The grep below skips the debug config's public 'android' password the same way.
"$BT/apksigner" sign --ks android/app/debug.keystore --ks-pass pass:android --out "$T/d.apk" "$T/u.zip" || { echo "FAIL: could not sign the fixture"; exit 1; }
want=$(keytool -list -v -keystore android/app/debug.keystore -storepass android 2>/dev/null | sed -n 's/.*SHA256: //p' | head -1 | tr -d ':' | tr 'A-F' 'a-f')
[ "$(cert_sha256 "$T/d.apk")" = "$want" ] && echo "ok: cert_sha256 reads the signer" || { echo "FAIL: cert_sha256"; fail=1; }
echo deadbeef > "$T/fp"
if RELEASE_CERT_FILE="$T/fp" require_release_cert "$T/d.apk" >/dev/null 2>&1; then
  echo "FAIL: a debug-signed APK was accepted"; fail=1
else echo "ok: a debug-signed APK is refused"; fi
if RELEASE_CERT_FILE="$T/missing" require_release_cert "$T/d.apk" >/dev/null 2>&1; then
  echo "FAIL: accepted with no fingerprint file"; fail=1
else echo "ok: no fingerprint file refuses"; fi
echo "$want" > "$T/fp"
RELEASE_CERT_FILE="$T/fp" require_release_cert "$T/d.apk" >/dev/null && echo "ok: the matching cert is accepted" || { echo "FAIL: matching cert refused"; fail=1; }
# publish_checked must leave nothing under the published names when a check refuses.
mkdir -p "$T/rel"; echo stale > "$T/rel/read-me-9.9.9.apk"; echo stale > "$T/rel/SHA256SUMS"
echo deadbeef > "$T/fp"
if RELEASE_CERT_FILE="$T/fp" publish_checked "$T/d.apk" "$T/rel" 9.9.9 "$T/d.apk" >/dev/null 2>&1; then
  echo "FAIL: publish_checked accepted a wrong signer"; fail=1
fi
if [ -e "$T/rel/read-me-9.9.9.apk" ] || [ -e "$T/rel/SHA256SUMS" ]; then
  echo "FAIL: a refused or stale APK is left under the published name"; fail=1
else echo "ok: nothing left under the published names after a refusal"; fi
if [ -x .venv-fdroid/bin/apksigcopier ]; then
  echo "$want" > "$T/fp"
  if RELEASE_CERT_FILE="$T/fp" publish_checked "$T/d.apk" "$T/rel" 9.9.9 "$T/d.apk" >/dev/null 2>&1 \
     && [ -s "$T/rel/read-me-9.9.9.apk" ] && grep -q 'read-me-9.9.9.apk' "$T/rel/SHA256SUMS"; then
    echo "ok: a checked APK is published with its SHA256SUMS"
  else echo "FAIL: a checked APK was not published"; fail=1; fi
else echo "SKIP: publish success path (no .venv-fdroid apksigcopier)"; fi
if grep -nE -- '-storepass|-keypass|--ks-pass|--key-pass|storePassword +["'"'"']' scripts/release-*.sh scripts/lib/release.sh android/app/build.gradle | grep -v "'android'"; then
  echo "FAIL: a password on a command line or in the build file"; fail=1
else echo "ok: no password on a command line"; fi
# REA-38: release:apk signs only a tested APK that F-Droid's own build matched (npm run fdroid:build).
echo apk > "$T/tested.apk"
if RELEASE_VERIFIED_FILE="$T/none" require_fdroid_verified "$T/tested.apk" >/dev/null 2>&1; then
  echo "FAIL: an APK F-Droid never matched was accepted"; fail=1
else echo "ok: an APK without an F-Droid match is refused"; fi
echo "$(sha256sum < "$T/d.apk" | cut -d' ' -f1)  other.apk" > "$T/verified"
if RELEASE_VERIFIED_FILE="$T/verified" require_fdroid_verified "$T/tested.apk" >/dev/null 2>&1; then
  echo "FAIL: another APK's match was accepted"; fail=1
else echo "ok: a match for another APK is refused"; fi
record_fdroid_verified "$T/tested.apk" "$T/verified"
RELEASE_VERIFIED_FILE="$T/verified" require_fdroid_verified "$T/tested.apk" >/dev/null \
  && echo "ok: a recorded match is accepted" || { echo "FAIL: a recorded match was refused"; fail=1; }
# REA-38: React Native's bundle task never empties its output directories, so a resource from an
# old build (the template's new-app-screen logos, in v1.0.0) is packaged into every later APK.
mkdir -p "$T/app/build/generated/res/react/release/drawable-mdpi" "$T/app/build/generated/assets/react/release" "$T/app/build/generated/res/resValues"
touch "$T/app/build/generated/res/react/release/drawable-mdpi/stale.png" "$T/app/build/generated/assets/react/release/index.android.bundle" "$T/app/build/generated/res/resValues/keep"
clear_bundle_output "$T/app"
if [ -e "$T/app/build/generated/res/react" ] || [ -e "$T/app/build/generated/assets/react" ]; then
  echo "FAIL: stale bundle output survives"; fail=1
elif [ ! -e "$T/app/build/generated/res/resValues/keep" ]; then echo "FAIL: cleared more than the bundle output"; fail=1
else echo "ok: the bundle task's old output is cleared, nothing else"; fi
grep -q 'clear_bundle_output' scripts/build-release.sh && grep -q 'clear_bundle_output' scripts/release-apk.sh \
  && echo "ok: both release builds clear it" || { echo "FAIL: a release build does not clear the bundle output"; fail=1; }
grep -q 'require_fdroid_verified "$TESTED"' scripts/release-apk.sh \
  && echo "ok: release:apk requires F-Droid's match" || { echo "FAIL: release:apk does not require F-Droid's match"; fail=1; }
# REA-38: F-Droid's build is unsigned; without apksigcopier's --unsigned every F-Droid build reads
# as different (seen on 51f837a, whose entries were all identical). apksigner re-lays out a zip
# it signs, so no fixture pair can stand in for AGP's; npm run fdroid:build exercises it.
grep -q 'apk_same_unsigned "$OURS" "$THEIRS"' scripts/fdroid-build.sh && grep -q 'compare "$@"' scripts/lib/recipe.sh \
  && grep -q 'apk_same --unsigned' scripts/lib/recipe.sh \
  && echo "ok: fdroid:build compares an unsigned F-Droid build" || { echo "FAIL: fdroid:build does not pass --unsigned"; fail=1; }
# REA-38: F-Droid's check apk job refused v1.0.1 for AGP's "Dependency metadata" signing block
# (0x504b4453, encrypted for Google Play); the comparisons copy the signing block, so only a
# look inside it catches one. The fixture gets the block inserted, sizes and EOCD offset fixed.
python3 - "$T/d.apk" "$T/dep.apk" <<'EOF'
import struct, sys
d = open(sys.argv[1], "rb").read()
e = d.rfind(b"PK\x05\x06"); cd = struct.unpack("<I", d[e + 16:e + 20])[0]
size = struct.unpack("<Q", d[cd - 24:cd - 16])[0]; start = cd - size - 8
pair = struct.pack("<QI", 4 + 8, 0x504b4453) + b"\0" * 8
new_size = struct.pack("<Q", size + len(pair))
block = new_size + d[start + 8:cd - 24] + pair + new_size + d[cd - 16:cd]
out = d[:start] + block + d[cd:e + 16] + struct.pack("<I", cd + len(pair)) + d[e + 20:]
open(sys.argv[2], "wb").write(out)
EOF
require_no_extra_signing_blocks "$T/d.apk" >/dev/null 2>&1 \
  && echo "ok: an APK without extra signing blocks is accepted" || { echo "FAIL: a plain signed APK was refused"; fail=1; }
if require_no_extra_signing_blocks "$T/dep.apk" >/dev/null 2>&1; then
  echo "FAIL: an APK with Dependency metadata was accepted"; fail=1
else echo "ok: an APK with Dependency metadata is refused"; fi
"$BT/apksigner" verify "$T/dep.apk" >/dev/null 2>&1 \
  && echo "ok: the fixture with the block still verifies (so it is a real APK)" || { echo "FAIL: the injected fixture is not a valid APK"; fail=1; }
grep -q 'require_no_extra_signing_blocks "$tmp"' scripts/lib/release.sh && grep -q 'require_no_extra_signing_blocks "$APK"' scripts/fdroid-scan.sh \
  && echo "ok: release:apk and fdroid-scan refuse it" || { echo "FAIL: release:apk or fdroid-scan does not check signing blocks"; fail=1; }
# REA-38 review: a match holds for the commit F-Droid built. A later commit that changes anything
# but docs (the recipe, build-hermesc.sh: F-Droid's build only) needs a new fdroid:build.
LIB=$PWD/scripts/lib/release.sh
R=$T/repo && mkdir -p "$R/fdroid" "$R/docs" && cd "$R" && git init -q && echo a > fdroid/r.yml && echo a > docs/x.md
git -c user.email=t@t -c user.name=t add -A && git -c user.email=t@t -c user.name=t commit -qm a
echo apk > "$T/t2.apk"
( source "$LIB"; record_fdroid_verified "$T/t2.apk" "$T/v2" )
echo b > docs/x.md && echo note >> README.md && git -c user.email=t@t -c user.name=t add -A && git -c user.email=t@t -c user.name=t commit -qm docs
( source "$LIB"; RELEASE_VERIFIED_FILE="$T/v2" require_fdroid_verified "$T/t2.apk" >/dev/null 2>&1 ) \
  && echo "ok: a docs-only commit keeps the match" || { echo "FAIL: a docs-only commit lost the match"; fail=1; }
echo b > fdroid/r.yml && git -c user.email=t@t -c user.name=t commit -qam recipe
if ( source "$LIB"; RELEASE_VERIFIED_FILE="$T/v2" require_fdroid_verified "$T/t2.apk" >/dev/null 2>&1 ); then
  echo "FAIL: a recipe change after the match was accepted"; fail=1
else echo "ok: a recipe change after the match is refused"; fi
cd - >/dev/null
exit $fail
