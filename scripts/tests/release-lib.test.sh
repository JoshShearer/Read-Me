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
if grep -nE -- '-storepass|-keypass|--ks-pass|--key-pass|storePassword +["'"'"']' scripts/release-*.sh scripts/lib/release.sh android/app/build.gradle | grep -v "'android'"; then
  echo "FAIL: a password on a command line or in the build file"; fail=1
else echo "ok: no password on a command line"; fi
exit $fail
