# Release signing checks (REA-26). Public data only: certificate fingerprints, never passwords.
SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
# 37.0.0 here; CI runners have other versions, any recent apksigner reads certificates the same.
BT="$SDK/build-tools/37.0.0"
[ -x "$BT/apksigner" ] || BT=$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)
cert_sha256() {
  "$BT/apksigner" verify --print-certs "$1" 2>/dev/null \
    | sed -n 's/^.*[Ss]igner.* certificate SHA-256 digest: //p' | head -1
}
require_release_cert() {
  local want got
  want=$(tr -d ' \n' < "${RELEASE_CERT_FILE:-release/signing-cert.sha256}" 2>/dev/null)
  got=$(cert_sha256 "$1")
  if [ -z "$want" ] || [ "$got" != "$want" ]; then
    echo "refused: $1 is signed by ${got:-nothing}, not the release key ${want:-(no release/signing-cert.sha256)}" >&2
    return 1
  fi
}
# publish_checked <built apk> <dir> <version> <tested apk>: the APK reaches <dir>/read-me-<v>.apk,
# with SHA256SUMS, only after the signer and the content checks pass; a refusal leaves neither
# that name nor an older SHA256SUMS behind, so nothing refused can be uploaded by mistake.
publish_checked() {
  local dir=$2 name="read-me-$3.apk" tmp
  rm -f "$dir/$name" "$dir/SHA256SUMS"
  mkdir -p "$dir"
  tmp=$(mktemp "$dir/.unchecked-XXXXXX")
  cp "$1" "$tmp"
  if ! require_release_cert "$tmp"; then rm -f "$tmp"; return 1; fi
  if ! PATH="$BT:$PATH" .venv-fdroid/bin/apksigcopier compare "$tmp" "$4"; then
    rm -f "$tmp"
    echo "refused: the signed APK's content differs from the tested build" >&2
    return 1
  fi
  mv "$tmp" "$dir/$name"
  ( cd "$dir" && sha256sum "$name" > SHA256SUMS )
}
# REA-38: v1.0.0 passed every check on this machine and still differed from F-Droid's build (the
# build host's IP, the Gradle cache path, stale bundle output). npm run fdroid:build records a
# tested APK that F-Droid's own build job matched; release:apk signs nothing else.
RELEASE_VERIFIED_FILE_DEFAULT=release/fdroid-verified
record_fdroid_verified() { # record_fdroid_verified <apk> [file]
  echo "$(sha256sum < "$1" | cut -d' ' -f1)  $(basename "$1")" >> "${2:-$RELEASE_VERIFIED_FILE_DEFAULT}"
}
require_fdroid_verified() {
  local sum
  sum=$(sha256sum < "$1" | cut -d' ' -f1)
  if ! grep -q "^$sum " "${RELEASE_VERIFIED_FILE:-$RELEASE_VERIFIED_FILE_DEFAULT}" 2>/dev/null; then
    echo "refused: F-Droid's build has not matched $1; run npm run fdroid:build -- $1" >&2
    return 1
  fi
}
# clear_bundle_output <android app dir>: React Native's bundle task writes the JS bundle and the
# images it references here but never removes old files, so a resource from an earlier build
# (v1.0.0 shipped the template's logos from 2026-10-01) is packaged forever (REA-38).
clear_bundle_output() {
  rm -rf "$1/build/generated/res/react" "$1/build/generated/assets/react"
}
