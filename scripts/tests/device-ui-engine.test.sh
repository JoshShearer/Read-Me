#!/usr/bin/env bash
# Tests for device-ui-e2e.sh's engine pin (REA-37, REA-31): it sets the first known engine
# installed for the phone's current user as the default engine (GrapheneOS Speech Services, then
# Google TTS; READ_ME_ENGINE overrides) and puts the previous default back on every exit, against
# a stub adb in a throwaway git repo. The script stops at the install step (no APK), which is an
# early exit the restore must survive. Never touches a real device.
set -uo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
mkdir -p "$T/bin" "$T/repo/scripts/lib"
cat > "$T/bin/adb" <<'STUB'
#!/bin/sh
case "$*" in
  devices) printf 'List of devices attached\nX\tdevice\n\n' ;;
  "shell dumpsys power") echo "  mWakefulness=Awake" ;;
  "shell dumpsys window") echo "    isKeyguardShowing=false" ;;
  "shell am get-current-user") echo 0 ;;
  # Plain `pm list packages` also lists other users' apps; only --user 0 is this user's.
  "shell pm list packages") printf '%b\n' "$STUB_PKGS\npackage:$STUB_OTHER_USER" ;;
  "shell pm list packages --user 0") printf '%b\n' "$STUB_PKGS" ;;
  "shell settings get secure tts_default_synth")
    if [ -f "$STUB_DIR/current" ]; then cat "$STUB_DIR/current"; else echo null; fi ;;
  "shell settings put secure tts_default_synth "*)
    echo "put $6" >> "$STUB_DIR/calls"; echo "$6" > "$STUB_DIR/current" ;;
  "shell settings delete secure tts_default_synth")
    echo delete >> "$STUB_DIR/calls"; rm -f "$STUB_DIR/current" ;;
  *) : ;;
esac
STUB
chmod +x "$T/bin/adb"
export PATH="$T/bin:$PATH" STUB_DIR="$T/state" STUB_OTHER_USER=com.google.android.tts
cp "$ROOT/scripts/lib/device.sh" "$T/repo/scripts/lib/"
cp "$ROOT/scripts/device-ui-e2e.sh" "$T/repo/scripts/"
cd "$T/repo" && git init -q && git -c user.email=t@t -c user.name=t commit -q --allow-empty -m init
fail=0
check() { if [ "$2" = "$3" ]; then echo "ok   $1"; else echo "FAIL $1: expected $3, got $2"; fail=1; fi; }
# run <previous default or empty> <packages for user 0>: prints the settings calls in order, then
# the default left behind.
run() {
  rm -rf "$STUB_DIR"; mkdir -p "$STUB_DIR"
  [ -n "$1" ] && echo "$1" > "$STUB_DIR/current"
  STUB_PKGS="$2" bash scripts/device-ui-e2e.sh >/dev/null 2>&1
  { tr "\n" " " < "$STUB_DIR/calls"; } 2>/dev/null; echo "-> $(cat "$STUB_DIR/current" 2>/dev/null || echo unset)"
}
GOS=package:app.grapheneos.speechservices
G=package:com.google.android.tts
S=package:com.brahmadeo.supertonic.tts
check "GrapheneOS Speech Services is pinned and the previous default put back after an early exit" \
  "$(run com.brahmadeo.supertonic.tts "$GOS\n$S")" \
  "put app.grapheneos.speechservices put com.brahmadeo.supertonic.tts -> com.brahmadeo.supertonic.tts"
check "Google TTS is pinned when it is this user's and GrapheneOS's is not" \
  "$(run com.brahmadeo.supertonic.tts "$G\n$S")" \
  "put com.google.android.tts put com.brahmadeo.supertonic.tts -> com.brahmadeo.supertonic.tts"
check "an unset default is deleted again, not set to null" \
  "$(run '' "$GOS")" "put app.grapheneos.speechservices delete -> unset"
check "an engine installed only for another user is never pinned" \
  "$(run com.brahmadeo.supertonic.tts "$S")" "-> com.brahmadeo.supertonic.tts"
check "READ_ME_ENGINE overrides the choice" \
  "$(READ_ME_ENGINE=com.brahmadeo.supertonic.tts run app.grapheneos.speechservices "$GOS\n$S")" \
  "put com.brahmadeo.supertonic.tts put app.grapheneos.speechservices -> app.grapheneos.speechservices"
check "an override that is not installed pins nothing" \
  "$(READ_ME_ENGINE=com.example.none run app.grapheneos.speechservices "$GOS")" "-> app.grapheneos.speechservices"
exit $fail
