#!/usr/bin/env bash
# npm run device:screens - screen switches under Fabric (REA-26). Shares a real article, opens it
# (List to Trim), cuts a paragraph and taps Done (Trim to Reader), N times (default 10), and
# fails if any switch leaves a blank screen or Fabric logs "Unable to find viewState". The
# Reader went blank in 4-5 of 10 such runs while App keyed a wrapper view (2026-10-03).
# Needs the network (weather.gov, as device:intake). Clears Read Me's data.
set -euo pipefail
cd "$(dirname "$0")/.."
. scripts/lib/device.sh
device_take interactive
device_require_unlocked
device_install_release
LINK='https://www.weather.gov/safety/lightning'
N=${SCREENS_RUNS:-10}

ui() {
  adb shell rm -f /sdcard/readme-ui.xml
  adb shell uiautomator dump /sdcard/readme-ui.xml >/dev/null 2>&1 || true
  adb shell cat /sdcard/readme-ui.xml 2>/dev/null || true
}
# centre_of <attr> <ERE> [seconds]: "x y" of the first matching node, or nothing.
centre_of() {
  local b=""
  for _ in $(seq "${3:-15}"); do
    b=$(ui | grep -oE "$1=\"$2\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" | head -1 \
      | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' | grep -oE '[0-9]+' | tr '\n' ' ' || true)
    [ -n "$b" ] && break
    sleep 1
  done
  [ -n "$b" ] || return 1
  set -- $b
  echo "$(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))"
}

fail=0
for i in $(seq "$N"); do
  device_clear_app
  adb logcat -c
  # The link goes on stdin, not the adb argv, which adbd logs (AGENTS.md, SPIKE-01).
  printf '%s\n' "am start -W -n $PKG/.ShareActivity -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT $(printf '%q' "$LINK")" \
    | adb shell >/dev/null
  adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
  r=ok
  if p=$(centre_of text ready 30); then adb shell input tap $p; else r="no ready item"; fi
  [ "$r" = ok ] && { p=$(centre_of content-desc 'paragraph 2') && adb shell input tap $p || r="Trim blank"; }
  [ "$r" = ok ] && { p=$(centre_of content-desc 'trim done') && adb shell input tap $p || r="Trim blank"; }
  [ "$r" = ok ] && { centre_of content-desc play 10 >/dev/null || r="Reader blank"; }
  lost=$(adb logcat -d | grep -c 'Unable to find viewState' || true)
  [ "$lost" = 0 ] || r="$r, Fabric lost a view ($lost)"
  echo "$i: $r"
  [ "$r" = ok ] || fail=1
done
pid=$(adb shell pidof "$PKG" 2>/dev/null | tr -d '\r' || true)
if device_crash_seen "${pid%% *}" <<<"$(adb logcat -d -b crash,main)"; then echo "FAIL: crash logged"; fail=1; fi
adb shell rm -f /sdcard/readme-ui.xml
echo "device:screens $([ $fail -eq 0 ] && echo PASS || echo FAIL)"
exit $fail
