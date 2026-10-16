#!/usr/bin/env bash
# REA-18: device scripts run under `set -o pipefail`. `producer | grep -q` lets grep exit at the
# first match; a producer with more to write (adb logcat -d, dumpsys) then dies of SIGPIPE, the
# pipeline returns 141, and a match reads as a miss: a negated privacy check passes on a leak and
# a wait loop never sees what it waits for. Every device script must read the answer whole first
# (a here-string or device_has). `||` is not a pipe.
set -uo pipefail
# REA-48: never the owner's real unattended unlock against a stub phone.
unset READ_ME_PIN_FILE DEVICE_UNLOCK_STATE
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
fail=0

# The trap itself, so this test fails loudly if it ever stops being one.
seq 1 3000000 | grep -q 1
rc=$?
if [ "$rc" = 141 ]; then echo "ok: a pipe into grep -q returns 141 on a match under pipefail"
else echo "FAIL: expected 141 from the pipefail trap, got $rc"; fail=1; fi
if grep -q 1 <<<"$(seq 1 3000000)"; then echo "ok: a here-string reads the whole answer and matches"
else echo "FAIL: the here-string form missed a match"; fail=1; fi

hits=$(grep -nE '(^|[^|])\|[[:space:]]*grep[[:space:]]+-[a-zA-Z]*q' "$ROOT"/scripts/device-*.sh || true)
if [ -z "$hits" ]; then echo "ok: no device script pipes into grep -q"
else echo "FAIL: a device script pipes into grep -q (read the answer whole first):"; echo "$hits"; fail=1; fi

for f in "$ROOT"/scripts/device-*.sh; do
  bash -n "$f" || { echo "FAIL: syntax: $f"; fail=1; }
done

[ "$fail" = 0 ] && echo "device-pipefail: all ok"
exit "$fail"
