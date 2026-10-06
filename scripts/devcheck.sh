#!/usr/bin/env bash
# Phase 1 / roadmap F19: run the real text pipeline (extract + segment) on Hermes in a release
# build, `runs` times in fresh processes, and compare every fingerprint with Node's. Also checks
# SPIKE-02's F17 line with the production extract code.
# Usage: scripts/devcheck.sh [runs=3]
# Exit: 0 parity and F17 hold, 1 mismatch/timeout/F17 miss, 2 app died, 3 no slot, 5 phone
# locked, 6 uncommitted changes. Installs a DEVCHECK build over whatever is on the phone and
# leaves build:release's APKs alone: they live under outputs/readme/, which Gradle never writes.
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

(cd android && ./gradlew -q assembleRelease -PreadmeEntryFile=../../index.devcheck.js -PreactNativeArchitectures=arm64-v8a)
mkdir -p android/app/build/devcheck
# Never under build:release's name (outputs/readme/), so no product device command installs it.
mv android/app/build/outputs/apk/release/app-arm64-v8a-release.apk android/app/build/devcheck/app-devcheck.apk
adb install -r android/app/build/devcheck/app-devcheck.apk >/dev/null
printf '%s\nbranch=%s commit=%s tree=clean at=%s purpose=devcheck\n' \
  "$(git rev-parse --show-toplevel)" "$(git branch --show-current)" "$HEAD7" "$(date -Iseconds)" \
  > "$PRIMARY/.claude/scratch/device-installed-from"
echo "installed DEVCHECK build $HEAD7 on the phone (replaces whatever build was there)"

# One launch per fixture per run: each page is measured in a fresh process, as a share is.
# Measuring every page in one process put Wikipedia at 2196 ms behind the 5 MB page, against
# 1344 ms alone (a544319, reference device).
NAMES=$(node -e "for (const e of require(process.argv[1])) console.log(e.name)" "$EXPECTED")
[ -n "$NAMES" ] || { echo "no fixtures in $EXPECTED" >&2; exit 1; }
# Heat throttles the CPU before the aggregate thermal status moves: f4bcce4 ran 1.7x slower at
# status 0 with the skin at 38.8 C and the wired-charging skin sensor at status 2. A launch
# waits (up to 15 min) until the aggregate status is 0, every sensor's own status is 0, and
# VIRTUAL-SKIN is under 36 C, and logs what it started at; the report rejects the timings of
# any launch that started warm.
SKIN_MAX=36
thermal_read() {
  local t agg skin hot cool
  t=$(adb shell dumpsys thermalservice 2>/dev/null | tr -d '\r')
  agg=$(sed -n 's/^Thermal Status: *\([0-9]*\).*/\1/p' <<<"$t" | head -1)
  # Only the "Current temperatures from HAL" block: one line per sensor, as of now.
  t=$(sed -n '/Current temperatures from HAL/,/Current cooling devices/p' <<<"$t")
  skin=$(sed -n 's/.*mValue=\([0-9.]*\), mType=3, mName=VIRTUAL-SKIN,.*/\1/p' <<<"$t" | head -1)
  hot=$(grep -c 'mStatus=[1-9]' <<<"$t" || true)
  cool=false
  if [ "${agg:-x}" = 0 ] && [ "${hot:-1}" = 0 ] && [ -n "$skin" ] \
     && awk -v s="$skin" -v m="$SKIN_MAX" 'BEGIN { exit !(s < m) }'; then cool=true; fi
  printf '%s %s %s %s\n' "${agg:-99}" "${skin:-0}" "${hot:-0}" "$cool"
}
for run in $(seq "$RUNS"); do
  for name in $NAMES; do
    adb shell am force-stop "$PKG"
    read -r status skin hot cool <<<"$(thermal_read)"
    for _ in $(seq 60); do
      [ "$cool" = true ] && break
      echo "run $run $name: status $status, skin $skin C, $hot sensor(s) flagged; waiting to cool"
      sleep 15
      read -r status skin hot cool <<<"$(thermal_read)"
    done
    printf 'run=%s DEVCHECK_THERMAL {"name":"%s","status":%s,"skin":%s,"sensorsFlagged":%s,"cool":%s}\n' \
      "$run" "$name" "$status" "$skin" "$hot" "$cool" >> "$DEVICE_OUT"
    adb logcat -c
    # -W waits for the launch; without it the first pidof can run before the process exists
    # and a healthy run is reported as a death.
    # VIEW: React Native's Linking reads the intent data only for ACTION_VIEW
    # (IntentModule.kt); without it every launch silently ran every fixture.
    adb shell am start -W -a android.intent.action.VIEW -n "$PKG/.MainActivity" \
      -d "devcheck://fixture/$name" >/dev/null
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
        echo "run $run $name: app process died during the devcheck" >&2
        crash=$(adb logcat -d -b crash,main)
        grep -E "AndroidRuntime|FATAL|libc|OutOfMemory|hermes" <<<"$crash" | tail -20 >&2 || true
        exit 2
      fi
      sleep 1
    done
    [ -n "$done_" ] || { echo "run $run $name: no DEVCHECK_DONE within 300 s (process seen: ${seen:-no})" >&2; exit 1; }
    results=$(grep -oE 'DEVCHECK \{.*\}' <<<"$logs" || true)
    if [ "$(grep -c . <<<"$results")" != 1 ] || ! grep -q "\"name\":\"$name\"" <<<"$results"; then
      echo "run $run $name: the launch did not run exactly its own fixture" >&2
      exit 1
    fi
    grep -oE 'DEVCHECK(_ENV)? \{.*\}' <<<"$logs" | sed "s/^/run=$run /" >> "$DEVICE_OUT"
  done
done
node scripts/devcheck-report.mjs "$EXPECTED" "$DEVICE_OUT" "$RUNS"
