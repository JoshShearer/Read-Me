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
PID=$(adb shell pidof "$PKG" | tr -d '\r')   # taken before any crash, for the native match
sleep 3
fail=0
[ -n "$(adb shell pidof "$PKG" | tr -d '\r')" ] || { echo "FAIL: process not running"; fail=1; }
adb shell dumpsys activity activities | grep -q "topResumedActivity.*$PKG/.MainActivity" \
  || { echo "FAIL: MainActivity not resumed"; fail=1; }
if adb logcat -d -b crash,main | device_crash_seen "$PID"; then
  echo "FAIL: crash logged"; fail=1
fi
echo "device:smoke $([ $fail -eq 0 ] && echo PASS || echo FAIL)"
exit $fail
