#!/usr/bin/env bash
# Tests for device-settings.sh (REA-27) against a stub adb in a throwaway git repo: saved dumps
# have the pairing token redacted, and a run that stops at the install step (no APK) releases the
# device lock and has not cleared the app or put anything on an adb command line beyond the
# checks. Never touches a real device.
set -uo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
mkdir -p "$T/bin" "$T/repo/scripts/lib"
cat > "$T/bin/adb" <<'STUB'
#!/bin/sh
echo "$*" >> "$STUB_CALLS"
case "$*" in
  devices) printf 'List of devices attached\nX\tdevice\n\n' ;;
  "shell dumpsys power") echo "  mWakefulness=Awake" ;;
  "shell dumpsys window") echo "    isKeyguardShowing=false" ;;
  *) : ;;
esac
STUB
chmod +x "$T/bin/adb"
export PATH="$T/bin:$PATH" STUB_CALLS="$T/calls"
cp "$ROOT/scripts/lib/device.sh" "$T/repo/scripts/lib/"
cp "$ROOT/scripts/device-settings.sh" "$T/repo/scripts/"
cd "$T/repo" && git init -q && git -c user.email=t@t -c user.name=t commit -q --allow-empty -m init
fail=0
check() { if [ "$2" = "$3" ]; then echo "ok   $1"; else echo "FAIL $1: expected $3, got $2"; fail=1; fi; }

TOKEN=0123456789abcdef0123456789abcdef
check "a token in a dump is redacted" \
  "$(printf '<node text="%s" content-desc="pairing token"/>' "$TOKEN" \
     | bash -c 'DEVICE_SETTINGS_SOURCE_ONLY=1 . scripts/device-settings.sh; redact_tokens')" \
  '<node text="<token>" content-desc="pairing token"/>'

: > "$STUB_CALLS"
bash scripts/device-settings.sh >/dev/null 2>&1; rc=$?
check "without an APK the run stops before touching the app" "$rc" 1
check "and releases the device lock" "$([ -d .claude/device.lock ] && echo held || echo free)" free
check "and never cleared the app" "$(grep -c 'pm clear' "$STUB_CALLS")" 0
exit $fail
