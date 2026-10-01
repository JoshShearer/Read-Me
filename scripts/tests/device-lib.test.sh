#!/usr/bin/env bash
# Tests for scripts/lib/device.sh lock rules and scripts/device-smoke.sh crash detection, against
# a stub adb in a throwaway git repo. Never touches a real device.
set -uo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
mkdir -p "$T/bin" "$T/repo/scripts/lib"
cat > "$T/bin/adb" <<'STUB'
#!/bin/sh
case "$1" in
  devices) printf 'List of devices attached\nX\tdevice\n\n' ;;
  logcat) [ -f "$STUB_LOGCAT" ] && cat "$STUB_LOGCAT" ;;
  *) : ;;
esac
STUB
chmod +x "$T/bin/adb"
export PATH="$T/bin:$PATH"
cp "$ROOT/scripts/lib/device.sh" "$T/repo/scripts/lib/"
cd "$T/repo" && git init -q && git -c user.email=t@t -c user.name=t commit -q --allow-empty -m init
git checkout -q -b feature/a.b
L=.claude/device.lock; mkdir -p .claude
fail=0
check() { if [ "$2" = "$3" ]; then echo "ok   $1"; else echo "FAIL $1: expected $3, got $2"; fail=1; fi; }
take() { bash -c '. scripts/lib/device.sh; device_take interactive' >/dev/null 2>&1; echo $?; }
owner() { printf '%s\nbranch=%s commit=x at=x purpose=%s\n' "$(git rev-parse --show-toplevel)" "$1" "$2" > "$L/owner"; }

mkdir "$L"; owner feature/a.b interactive
check "a /ship or /verify lock on this lane is reused" "$(take)" 0
check "and is left for the caller to release" "$([ -d "$L" ] && echo kept)" kept
rm -rf "$L"

mkdir "$L"; owner feature/a.b script:interactive
check "a lock another device script holds is never adopted (F1)" "$(take)" 3
rm -rf "$L"

mkdir "$L"; owner feature/aXb interactive
check "branch names match literally, not as a regex (F9)" "$(take)" 3
rm -rf "$L"

mkdir "$L"
check "an ownerless lock is another lane's" "$(take)" 3
rm -rf "$L"

check "a free slot is taken" "$(take)" 0
check "and released on exit" "$([ -d "$L" ] && echo kept || echo released)" released

# device-smoke.sh crash detection (F4): a native tombstone line must count as a crash.
grep -q 'grep -iE "fatal|AndroidRuntime"' "$ROOT/scripts/device-smoke.sh"
check "device-smoke matches crash lines case-insensitively (F4)" "$?" 0
echo 'F libc    : Fatal signal 11 (SIGSEGV), code 1, pid 123 (io.loopstring.readme)' \
  | grep -iE "fatal|AndroidRuntime" | grep -q io.loopstring.readme
check "a native Fatal signal line is detected" "$?" 0

exit $fail
