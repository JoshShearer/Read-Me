#!/usr/bin/env bash
# REA-26, once: create the release key. keytool prompts for both passwords; they never reach argv.
set -euo pipefail
cd "$(dirname "$0")/.."
KS=${1:-$HOME/.android-keys/read-me-release.jks}
[ -e "$KS" ] && { echo "refused: $KS exists; never overwrite a release key"; exit 1; }
mkdir -p "$(dirname "$KS")" release; chmod 700 "$(dirname "$KS")"
keytool -genkeypair -v -keystore "$KS" -alias read-me -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=Read Me, O=Loopstring"
chmod 600 "$KS"
echo "Enter the store password once more to read the public certificate fingerprint:"
keytool -list -v -keystore "$KS" -alias read-me | sed -n 's/.*SHA256: //p' | head -1 | tr -d ':' | tr 'A-F' 'a-f' > release/signing-cert.sha256
[ -s release/signing-cert.sha256 ] || { echo "could not read the fingerprint; rerun the keytool -list step"; exit 1; }
echo "fingerprint written to release/signing-cert.sha256 (public; commit it)"
echo "Add to ~/.gradle/gradle.properties (then chmod 600 it), with your passwords:"
echo "  READ_ME_STORE_FILE=$KS"
echo "  READ_ME_KEY_ALIAS=read-me"
echo "  READ_ME_STORE_PASSWORD=..."
echo "  READ_ME_KEY_PASSWORD=..."
echo "Back up $KS and both passwords offline: losing them ends updates for every installed copy."
