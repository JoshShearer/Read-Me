#!/usr/bin/env bash
# Run one JS spike probe in the release build, `runs` times, and print one JSON payload per run.
# Usage: scripts/spike-js.sh <ping|segmenter|extract> [timeout_s=120] [runs=1]
# Exit: 0 results printed, 1 timeout, 2 app died (crash lines printed), 3 no slot, 5 phone locked,
# 6 uncommitted changes, or the APK was not built from HEAD.
set -euo pipefail
cd "$(dirname "$0")/.."
NAME=${1:?usage: spike-js.sh <name> [timeout_s] [runs]}
TIMEOUT=${2:-120}; RUNS=${3:-1}
. scripts/lib/device.sh
cleanup() { adb shell am force-stop "$PKG" >/dev/null 2>&1 || true; }
DEVICE_ON_EXIT=cleanup
device_take interactive
device_require_measured_build
device_require_unlocked
device_install_release
for run in $(seq "$RUNS"); do
  adb shell am force-stop "$PKG"
  adb logcat -c
  adb shell am start -n "$PKG/.MainActivity" --es spike "$NAME" >/dev/null
  sleep 2
  got=""
  for _ in $(seq "$TIMEOUT"); do
    # Read whole, then search (scripts/lib/device.sh, device_has).
    logs=$(adb logcat -d -s ReactNativeJS:I)
    line=$(grep -m1 'SPIKE_RESULT' <<<"$logs" || true)
    if [ -n "$line" ]; then got="${line#*SPIKE_RESULT }"; break; fi
    # pidof exits 1 once the process is gone; under set -e that must not end the script silently.
    if [ -z "$(adb shell pidof "$PKG" 2>/dev/null | tr -d '\r' || true)" ]; then
      echo "run $run: app process died during spike '$NAME'" >&2
      crash=$(adb logcat -d -b crash,main)
      grep -E "AndroidRuntime|FATAL|libc|OutOfMemory|hermes" <<<"$crash" | tail -20 >&2 || true
      exit 2
    fi
    sleep 1
  done
  [ -n "$got" ] || { echo "run $run: no SPIKE_RESULT within ${TIMEOUT}s" >&2; exit 1; }
  echo "$got"
done
