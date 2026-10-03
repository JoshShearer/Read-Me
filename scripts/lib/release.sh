# Release signing checks (REA-26). Public data only: certificate fingerprints, never passwords.
BT="${ANDROID_HOME:-$HOME/Android/Sdk}/build-tools/37.0.0"
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
