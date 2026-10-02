#!/usr/bin/env bash
# Phase 1 / roadmap F19: run the real text pipeline (extract + segment) on Hermes in a release
# build, `runs` times in fresh processes, and compare every fingerprint with Node's. Also checks
# SPIKE-02's F17 line with the production extract code.
# Usage: scripts/devcheck.sh [runs=3]
# Exit: 0 parity and F17 hold, 1 mismatch/timeout/F17 miss, 2 app died, 3 no slot, 5 phone
# locked, 6 uncommitted changes. Installs a DEVCHECK build over whatever is on the phone and
# deletes the product APK, so product device commands refuse until npm run build:release.
set -euo pipefail
cd "$(dirname "$0")/.."
RUNS=${1:-3}
. scripts/lib/device.sh
if [ -n "$(git status --porcelain -- . ':(exclude).claude')" ]; then
  echo "uncommitted changes: commit first, so the measured build is named" >&2
  git status --short -- . ':(exclude).claude' >&2
  exit 6
fi
cleanup() { adb shell am force-stop "$PKG" >/dev/null 2>&1 || true; }
DEVICE_ON_EXIT=cleanup
device_take interactive
device_require_unlocked
HEAD7=$(git rev-parse --short HEAD)
SCR="$PRIMARY/.claude/scratch/devcheck"
mkdir -p "$SCR"
EXPECTED="$SCR/expected-$HEAD7.json"
DEVICE_OUT="$SCR/device-$HEAD7.txt"
: > "$DEVICE_OUT"

npm run -s devcheck:fixtures >/dev/null
DEVCHECK_EXPECTED_OUT="$EXPECTED" npx jest __tests__/devcheck.expected.test.ts >/dev/null

(cd android && ./gradlew -q assembleRelease -PreadmeEntryFile=../../index.devcheck.js)
APK=android/app/build/outputs/apk/release/app-release.apk
mkdir -p android/app/build/devcheck
mv "$APK" android/app/build/devcheck/app-devcheck.apk
rm -f "$APK.stamp"   # product device commands now refuse until npm run build:release
adb install -r android/app/build/devcheck/app-devcheck.apk >/dev/null
printf '%s\nbranch=%s commit=%s tree=clean at=%s purpose=devcheck\n' \
  "$(git rev-parse --show-toplevel)" "$(git branch --show-current)" "$HEAD7" "$(date -Iseconds)" \
  > "$PRIMARY/.claude/scratch/device-installed-from"
echo "installed DEVCHECK build $HEAD7 on the phone (replaces whatever build was there)"

for run in $(seq "$RUNS"); do
  adb shell am force-stop "$PKG"
  adb logcat -c
  # -W waits for the launch; without it the first pidof can run before the process exists and
  # a healthy run is reported as a death.
  adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
  done_=""
  logs=""
  seen=""
  for _ in $(seq 300); do
    # Read whole, then search (scripts/lib/device.sh, device_has).
    logs=$(adb logcat -d -s ReactNativeJS:I)
    if device_has "$logs" 'DEVCHECK_DONE'; then done_=1; break; fi
    pid=$(adb shell pidof "$PKG" 2>/dev/null | tr -d '\r' || true)
    if [ -n "$pid" ]; then seen=1; fi
    if [ -n "$seen" ] && [ -z "$pid" ]; then
      echo "run $run: app process died during the devcheck" >&2
      crash=$(adb logcat -d -b crash,main)
      grep -E "AndroidRuntime|FATAL|libc|OutOfMemory|hermes" <<<"$crash" | tail -20 >&2 || true
      exit 2
    fi
    sleep 1
  done
  [ -n "$done_" ] || { echo "run $run: no DEVCHECK_DONE within 300 s (process seen: ${seen:-no})" >&2; exit 1; }
  grep -oE 'DEVCHECK(_ENV)? \{.*\}' <<<"$logs" | sed "s/^/run=$run /" >> "$DEVICE_OUT"
done
node scripts/devcheck-report.mjs "$EXPECTED" "$DEVICE_OUT"
