#!/usr/bin/env bash
# Tests for device-ui-e2e.sh's engine pin (REA-37): it sets Google TTS as the default engine and
# puts the previous default back on every exit, against a stub adb in a throwaway git repo. The
# script stops at the install step (no APK), which is an early exit the restore must survive.
# Never touches a real device.
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
  "shell pm list packages") printf '%b\n' "$STUB_PKGS" ;;
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
export PATH="$T/bin:$PATH" STUB_DIR="$T/state"
cp "$ROOT/scripts/lib/device.sh" "$T/repo/scripts/lib/"
cp "$ROOT/scripts/device-ui-e2e.sh" "$T/repo/scripts/"
cd "$T/repo" && git init -q && git -c user.email=t@t -c user.name=t commit -q --allow-empty -m init
fail=0
check() { if [ "$2" = "$3" ]; then echo "ok   $1"; else echo "FAIL $1: expected $3, got $2"; fail=1; fi; }
# run <previous default or empty> <packages>: prints the settings calls in order, then the
# default left behind.
run() {
  rm -rf "$STUB_DIR"; mkdir -p "$STUB_DIR"
  [ -n "$1" ] && echo "$1" > "$STUB_DIR/current"
  STUB_PKGS="$2" bash scripts/device-ui-e2e.sh >/dev/null 2>&1
  { tr "\n" " " < "$STUB_DIR/calls"; } 2>/dev/null; echo "-> $(cat "$STUB_DIR/current" 2>/dev/null || echo unset)"
}
G=package:com.google.android.tts
check "the previous default is put back after an early exit" \
  "$(run com.brahmadeo.supertonic.tts "$G\npackage:com.brahmadeo.supertonic.tts")" \
  "put com.google.android.tts put com.brahmadeo.supertonic.tts -> com.brahmadeo.supertonic.tts"
check "an unset default is deleted again, not set to null" \
  "$(run '' "$G")" "put com.google.android.tts delete -> unset"
check "without Google TTS the default is never touched" \
  "$(run com.brahmadeo.supertonic.tts 'package:com.google.android.ttsx')" "-> com.brahmadeo.supertonic.tts"
exit $fail
