#!/usr/bin/env bash
# npm run device:intake - R-M02/R-M03/R-M04 on the phone, as a user would. Clears Read Me's
# data, then shares a link, a text and a dead link to ShareActivity with the app closed. It
# then opens the app and checks each item's state on screen, and that no log line carries the
# shared URL path or text (AGENTS.md 1). Uses the network: the link is weather.gov's
# lightning safety page (public domain, a Phase 1 fixture source).
set -euo pipefail
cd "$(dirname "$0")/.."
. scripts/lib/device.sh
device_take interactive
device_require_unlocked
device_install_release

LINK='https://www.weather.gov/safety/lightning'
LINK_PATH_MARK='safety/lightning'
TEXT_MARK='Paragraph one of the shared note'
DEAD='http://127.0.0.1:9/nothing-here'

adb shell pm clear "$PKG" >/dev/null
adb logcat -c
share() {
  # The text goes on stdin, not the adb argv, which adbd logs (AGENTS.md, SPIKE-01).
  printf '%s\n' "am start -W -n $PKG/.ShareActivity -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT $(printf '%q' "$1")" \
    | adb shell >/dev/null
}
share "Lightning safety $LINK"
share "$TEXT_MARK, with a second sentence.

Paragraph two."
share "dead $DEAD"
echo "shared three items with the app closed; waiting for the requests"
sleep 25

adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
fail=0
screen=""
for _ in $(seq 30); do
  adb shell uiautomator dump /sdcard/readme-ui.xml >/dev/null 2>&1 || true
  screen=$(adb shell cat /sdcard/readme-ui.xml 2>/dev/null || true)
  if device_has "$screen" 'text="ready"' && device_has "$screen" 'fetch-failed: offline'; then
    break
  fi
  sleep 2
done
adb shell rm -f /sdcard/readme-ui.xml
readies=$(grep -o 'text="ready"' <<<"$screen" | wc -l)
[ "$readies" -ge 2 ] || { echo "FAIL: expected 2 ready items (link, text), saw $readies"; fail=1; }
device_has "$screen" 'fetch-failed: offline' || { echo "FAIL: dead link not fetch-failed: offline"; fail=1; }
device_has "$screen" 'text="fetching"' && { echo "FAIL: an item still fetching"; fail=1; }

logs=$(adb logcat -d)
if device_has "$logs" "$LINK_PATH_MARK|$TEXT_MARK|nothing-here"; then
  echo "FAIL: a log line carries a shared URL path or text"
  grep -nE "$LINK_PATH_MARK|$TEXT_MARK|nothing-here" <<<"$logs" | cut -c1-80 | sed 's/^/  /' | head -5
  fail=1
fi
pid=$(adb shell pidof "$PKG" 2>/dev/null | tr -d '\r' || true)
if device_crash_seen "${pid%% *}" <<<"$(adb logcat -d -b crash,main)"; then
  echo "FAIL: crash logged"; fail=1
fi
echo "device:intake $([ $fail -eq 0 ] && echo PASS || echo FAIL)"
exit $fail
