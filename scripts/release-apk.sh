#!/usr/bin/env bash
# REA-26 / R-M13: the publishable APKs, one per ABI (REA-40). Builds each signed with the owner's
# key (Gradle properties, never argv), checks the signer against release/signing-cert.sha256,
# proves each one's content equals the debug-signed build of that ABI the acceptance runs
# tested and F-Droid's build matched, and writes SHA256SUMS.
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/lib/release.sh
TESTED=${1:?usage: npm run release:apk -- release/tested-<sha> (a copy of $READ_ME_OUT after build:release)}
TESTED=${TESTED%/}
commits=""
for ABI in $READ_ME_ALL_ABIS; do
  [ -f "$TESTED/app-$ABI-release.apk" ] || { echo "refused: no $TESTED/app-$ABI-release.apk"; exit 1; }
  require_fdroid_verified "$TESTED/app-$ABI-release.apk"
  commits="$commits $FDROID_VERIFIED_COMMIT"
done
# One release is one commit: F-Droid builds every ABI from the same commit: field.
[ "$(tr ' ' '\n' <<<"$commits" | sort -u | grep -c .)" = 1 ] \
  || { echo "refused: F-Droid matched these APKs at different commits:$commits"; exit 1; }
[ -z "$(git status --porcelain -- . ':(exclude).claude')" ] || { echo "refused: the tree is dirty"; exit 1; }
[ -s release/signing-cert.sha256 ] || { echo "refused: no release/signing-cert.sha256; run npm run release:keystore first"; exit 1; }
[ -x .venv-fdroid/bin/apksigcopier ] || { echo "refused: no .venv-fdroid/bin/apksigcopier; run scripts/fdroid-scan.sh once"; exit 1; }
VER=$(node -p "require('./package.json').version")
node scripts/make-notices.mjs --check
rm -f release/read-me-"$VER"-*.apk release/SHA256SUMS
# From here the build output is release-signed: whatever happens, never leave it where a device
# script (or a hand upload) could pick it up, and leave no partial set under the published names.
done=0
trap 'rm -f android/app/build/outputs/apk/release/*.apk; [ "$done" = 1 ] || rm -f release/read-me-"$VER"-*.apk release/SHA256SUMS' EXIT
for ABI in $READ_ME_ALL_ABIS; do
  assemble_abi "$ABI" -PreadmeSign=release
  publish_checked "$(gradle_apk "$ABI")" release "read-me-$VER-$ABI.apk" "$TESTED/app-$ABI-release.apk"
done
( cd release && sha256sum read-me-"$VER"-*.apk > SHA256SUMS )
done=1
cat release/SHA256SUMS
echo "signer $(cert_sha256 "release/read-me-$VER-arm64-v8a.apk" | cut -c1-16)..."
echo "tag the commit F-Droid built: gh release create v$VER --draft --target ${commits# } ..."
