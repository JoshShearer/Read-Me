#!/usr/bin/env bash
# npm run device:intake - R-M02/R-M03/R-M04 on the phone, as a user would. Clears Read Me's
# data, then shares a link, a text and a dead link to ShareActivity with the app closed. It
# then opens the app and checks each item's state on screen. REA-63: shares the link and the dead
# link again with Read Me in the background and checks each opens its unread item (Trim; the
# unread List, left on its Archive tab before the share) and adds and fetches nothing. Last,
# that no log line carries the shared URL path or text (AGENTS.md 1), the second shares included. Uses the network: the link is weather.gov's
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
NOHOST='https://nowebsite.invalid/nothing-here'

device_clear_app
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
# REA-59: a host that does not exist, while online, is "not-found", not "offline".
share "nowhere $NOHOST"
# R-M03 allows http: a plain-http page must fetch, not fail as "network" under a cleartext ban.
# example.com, not neverssl.com: neverssl took 1 to 20 s to connect (2026-10-02).
share "http://example.com/"
echo "shared five items with the app closed; waiting for the requests"
sleep 25

adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
fail=0
screen=""
for _ in $(seq 30); do
  adb shell uiautomator dump /sdcard/readme-ui.xml >/dev/null 2>&1 || true
  screen=$(adb shell cat /sdcard/readme-ui.xml 2>/dev/null || true)
  if device_has "$screen" 'text="ready"' && device_has "$screen" 'fetch-failed: offline' && device_has "$screen" 'fetch-failed: not-found'; then
    break
  fi
  sleep 2
done
adb shell rm -f /sdcard/readme-ui.xml
# grep -c, not grep | wc: with no match grep exits 1 and pipefail would end the script with
# no verdict (seen at 964fef0 when the app crashed on launch).
readies=$(grep -cE 'text="(ready|extract-poor)"' <<<"${screen//\/>/$'\n'}" || true)
[ "$readies" -ge 3 ] || { echo "FAIL: expected 3 readable items (link, text, http link), saw $readies"; fail=1; }
device_has "$screen" 'fetch-failed: network' && { echo "FAIL: a link failed as network (cleartext http blocked?)"; fail=1; }
device_has "$screen" 'fetch-failed: offline' || { echo "FAIL: dead link not fetch-failed: offline"; fail=1; }
device_has "$screen" 'fetch-failed: not-found' || { echo "FAIL: .invalid host not fetch-failed: not-found (needs the phone online)"; fail=1; }
device_has "$screen" 'text="fetching"' && { echo "FAIL: an item still fetching"; fail=1; }
# Every row's state badge (text=, not the row's content-desc, which repeats it).
rows() { grep -cE 'text="(ready|extract-poor|fetching|fetched|fetch-failed[^"]*)"' <<<"${screen//\/>/$'\n'}" || true; }
rows_before=$(rows)

# REA-63 (ADR 0016): a second share of an unread link opens that item and fetches nothing.
# These shares go through am start, which puts ShareActivity in Read Me's own task, on top of the
# screen left there before HOME; a share from another app's sheet starts it in that app's task,
# so this does not exercise the cross-task start of MainActivity a user's share makes. Because
# the old screen shows again when ShareActivity finishes, the dead-link share is made with the
# List on its Archive tab: only a shared open brings back the unread tab with that row.
dump_until() { # $1: a regex; up to about 20 s; the screen is left in $screen
  for _ in $(seq 10); do
    adb shell uiautomator dump /sdcard/readme-ui.xml >/dev/null 2>&1 || true
    screen=$(adb shell cat /sdcard/readme-ui.xml 2>/dev/null || true)
    device_has "$screen" "$1" && break
    sleep 2
  done
  adb shell rm -f /sdcard/readme-ui.xml
}
requests() { grep -c 'request id=' <<<"$(adb logcat -d -s ReadMe)" || true; }
before=$(requests)
adb shell input keyevent KEYCODE_HOME
share "Lightning safety $LINK"
dump_until 'content-desc="trim done"'
matched=$(grep -c 'share matched item=' <<<"$(adb logcat -d -s ReadMe)" || true)
[ "$matched" -eq 1 ] || { echo "FAIL: second link share: expected one 'share matched' line, saw $matched"; fail=1; }
device_has "$(adb shell dumpsys activity activities)" "ResumedActivity.*$PKG/\.MainActivity" \
  || { echo "FAIL: second link share: MainActivity is not the resumed activity"; fail=1; }
device_has "$screen" 'content-desc="trim done"' || { echo "FAIL: second link share: Trim not shown"; fail=1; }
adb shell input keyevent KEYCODE_BACK
dump_until 'content-desc="settings"'
readies=$(grep -cE 'text="(ready|extract-poor)"' <<<"${screen//\/>/$'\n'}" || true)
[ "$readies" -ge 3 ] || { echo "FAIL: after the second link share expected 3 readable items, saw $readies"; fail=1; }
n=$(rows)
[ "$n" -eq "$rows_before" ] || { echo "FAIL: second link share: $rows_before rows before, $n after"; fail=1; }
device_has "$screen" 'text="fetching"' && { echo "FAIL: second link share: a row is fetching"; fail=1; }
b=$(grep -oE 'content-desc="archive"[^>]*bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' <<<"$screen" | head -1 \
  | grep -oE '[0-9]+' | tail -4 | tr '\n' ' ' || true)
if [ -n "$b" ]; then
  read -r x0 y0 x1 y1 <<<"$b"
  adb shell input tap $(((x0 + x1) / 2)) $(((y0 + y1) / 2))
  dump_until 'content-desc="unread"'
  device_has "$screen" 'content-desc="unread"' || { echo "FAIL: the List did not switch to its Archive tab"; fail=1; }
else
  echo "FAIL: no Archive button on the List"; fail=1
fi
adb shell input keyevent KEYCODE_HOME
share "dead $DEAD"
dump_until 'fetch-failed: offline'
matched=$(grep -c 'share matched item=' <<<"$(adb logcat -d -s ReadMe)" || true)
[ "$matched" -eq 2 ] || { echo "FAIL: second dead-link share: expected two 'share matched' lines, saw $matched"; fail=1; }
offline=$(grep -c 'text="fetch-failed: offline"' <<<"${screen//\/>/$'\n'}" || true)
[ "$offline" -eq 1 ] || { echo "FAIL: second dead-link share: expected one fetch-failed: offline row, saw $offline"; fail=1; }
device_has "$screen" 'content-desc="retry ' || { echo "FAIL: second dead-link share: no Retry on the List"; fail=1; }
device_has "$screen" 'content-desc="unread"' && { echo "FAIL: second dead-link share: the List is on its Archive tab"; fail=1; }
n=$(rows)
[ "$n" -eq "$rows_before" ] || { echo "FAIL: second dead-link share: $rows_before rows before, $n after"; fail=1; }
device_has "$screen" 'text="fetching"' && { echo "FAIL: second dead-link share: a row is fetching"; fail=1; }
after=$(requests)
[ "$after" -eq "$before" ] || { echo "FAIL: a matched share fetched ($before request lines before, $after after)"; fail=1; }
echo "second shares: matched=$matched rows=$rows_before requests before=$before after=$after"

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
