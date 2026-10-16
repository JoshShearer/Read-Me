#!/usr/bin/env bash
# Tests for device-smoke.sh (REA-13): `am start -W` must report "Status: ok" or the run prints a
# FAIL line and exits non-zero (it used to die silently under set -e when no Status line came
# back). Runs against a stub adb and a stub device.sh in a throwaway dir; never touches a device.
set -uo pipefail
# REA-48: never the owner's real unattended unlock against a stub phone.
unset READ_ME_PIN_FILE DEVICE_UNLOCK_STATE
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
mkdir -p "$T/bin" "$T/repo/scripts/lib"
cat > "$T/bin/adb" <<'STUB'
#!/bin/sh
case "$*" in
  "shell am start -W -n "*) cat "$STUB_AMSTART" ;;
  "shell pidof "*) printf '1234\r\n' ;;
  "shell dumpsys activity activities") echo "topResumedActivity io.loopstring.readme/.MainActivity" ;;
  *) : ;;
esac
STUB
chmod +x "$T/bin/adb"
cat > "$T/repo/scripts/lib/device.sh" <<'STUB'
PKG=io.loopstring.readme
device_take() { :; }
device_require_unlocked() { :; }
device_install_release() { :; }
device_has() { printf '%s' "$1" | grep -Eq "$2"; }
device_crash_seen() { return 1; }
STUB
cp "$ROOT/scripts/device-smoke.sh" "$T/repo/scripts/"
export PATH="$T/bin:$PATH" STUB_AMSTART="$T/amstart"
cd "$T/repo"
fail=0
check() { if [ "$2" = "$3" ]; then echo "ok   $1"; else echo "FAIL $1: expected $3, got $2"; fail=1; fi; }
run() { out=$(bash scripts/device-smoke.sh 2>&1); rc=$?; }
has() { printf '%s' "$out" | grep -q "$1" && echo yes || echo no; }

printf 'Starting: Intent { cmp=io.loopstring.readme/.MainActivity }\r\nStatus: ok\r\nLaunchState: COLD\r\nTotalTime: 800\r\nWaitTime: 810\r\nComplete\r\n' > "$T/amstart"
run
check "(a) Status: ok passes" "$rc" 0
check "(a) no am start FAIL" "$(has 'FAIL: am start')" no
check "(a) Status line still printed" "$(has 'Status: ok')" yes
check "(a) LaunchState printed" "$(has 'LaunchState: COLD')" yes
check "(a) summary PASS" "$(has 'device:smoke PASS')" yes

printf 'Error: Activity not started, unknown error code 1\r\n' > "$T/amstart"
run
check "(b) no Status line exits non-zero" "$([ "$rc" -ne 0 ] && echo nonzero || echo zero)" nonzero
check "(b) prints a FAIL line" "$(has 'FAIL: am start')" yes
check "(b) summary FAIL" "$(has 'device:smoke FAIL')" yes

for s in timeout error; do
  printf 'Status: %s\r\nLaunchState: UNKNOWN (0)\r\n' "$s" > "$T/amstart"
  run
  check "(c) Status: $s exits non-zero" "$([ "$rc" -ne 0 ] && echo nonzero || echo zero)" nonzero
  check "(c) Status: $s prints a FAIL line" "$(has 'FAIL: am start')" yes
done
exit $fail
