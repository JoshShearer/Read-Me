#!/usr/bin/env bash
# npm run device:smoke - scripted on-device checks (R-M14). Today: install, cold launch,
# the activity is resumed, and no FATAL for our package. Later phases add their checks here.
set -euo pipefail
cd "$(dirname "$0")/.."
. scripts/lib/device.sh
device_take interactive
device_require_unlocked
device_install_release
adb shell am force-stop "$PKG"
adb logcat -c
adb shell am start -W -n "$PKG/.MainActivity" | grep -E "Status|LaunchState|TotalTime"
# The pid is taken before any crash, for the native match. pidof exits 1 while the process is
# not up yet, which would end the script silently under set -e, so poll and tolerate misses.
PID=""
for _ in 1 2 3 4 5 6 7 8 9 10; do
  PID=$(adb shell pidof "$PKG" 2>/dev/null | tr -d '\r' || true)
  PID=${PID%% *}   # pidof can name several processes; the app's main one is enough for the match
  [ -n "$PID" ] && break
  sleep 1
done
sleep 3
fail=0
[ -n "$(adb shell pidof "$PKG" 2>/dev/null | tr -d '\r' || true)" ] || { echo "FAIL: process not running"; fail=1; }
# Each answer is read whole before it is searched (device_has in scripts/lib/device.sh).
activities=$(adb shell dumpsys activity activities)
device_has "$activities" "topResumedActivity.*$PKG/.MainActivity" \
  || { echo "FAIL: MainActivity not resumed"; fail=1; }
logs=$(adb logcat -d -b crash,main)
if device_crash_seen "$PID" <<<"$logs"; then
  echo "FAIL: crash logged"; fail=1
fi
echo "device:smoke $([ $fail -eq 0 ] && echo PASS || echo FAIL)"
exit $fail
