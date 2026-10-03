#!/usr/bin/env bash
# REA-26 / R-M13: the publishable APK. Builds the release signed with the owner's key (Gradle
# properties, never argv), checks the signer against release/signing-cert.sha256, proves the
# content equals the debug-signed build the acceptance runs installed, and writes SHA256SUMS.
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/lib/release.sh
TESTED=${1:?usage: npm run release:apk -- <the debug-signed APK the acceptance runs installed>}
[ -f "$TESTED" ] || { echo "refused: no such APK: $TESTED"; exit 1; }
[ -z "$(git status --porcelain -- . ':(exclude).claude')" ] || { echo "refused: the tree is dirty"; exit 1; }
[ -s release/signing-cert.sha256 ] || { echo "refused: no release/signing-cert.sha256; run npm run release:keystore first"; exit 1; }
[ -x .venv-fdroid/bin/apksigcopier ] || { echo "refused: no .venv-fdroid/bin/apksigcopier; run scripts/fdroid-scan.sh once"; exit 1; }
VER=$(node -p "require('./package.json').version")
node scripts/make-notices.mjs --check
( cd android && ./gradlew --quiet assembleRelease -PreadmeSign=release )
OUT=release/read-me-$VER.apk
cp android/app/build/outputs/apk/release/app-release.apk "$OUT"
require_release_cert "$OUT"
# apksigcopier copies the release signature onto the tested APK; it verifies only if every
# byte outside the signature is the same.
if ! PATH="$BT:$PATH" .venv-fdroid/bin/apksigcopier compare "$OUT" "$TESTED"; then
  rm -f "$OUT"
  echo "refused: the signed APK's content differs from the tested build"; exit 1
fi
( cd release && sha256sum "read-me-$VER.apk" > SHA256SUMS )
echo "ready: $OUT sha256 $(cut -c1-16 release/SHA256SUMS)..., signer $(cert_sha256 "$OUT" | cut -c1-16)..."
