#!/usr/bin/env bash
# npm run device:gap - R-M07's gap target on the reference device: 2.0x on (simulated)
# battery in forced Doze with the screen off. Reads the service's own "playback gaps" line
# (onDone(n) to onStart(n+1), measured in PlaybackService). Shares a long public-domain text
# (Pride and Prejudice, the gutenberg-1342 fixture). Resets battery and Doze state on exit.
# Plays 2 minutes by default (about 4 in total), a quick check. R-M07 defines the target over
# 10 minutes, so only `GAP_MINUTES=10 npm run device:gap` (about 12) is the spec measurement.
# AIRPLANE=1 adds airplane mode during the measurement (R-M14 run 2), restored on exit.
set -euo pipefail
cd "$(dirname "$0")/.."
. scripts/lib/device.sh
device_take gap
device_require_unlocked
device_install_release
MINUTES=${GAP_MINUTES:-2}

TXT=$(mktemp)
node -e '
  const html = require("fs").readFileSync("__tests__/fixtures/pages/gutenberg-1342.html", "utf8");
  const ents = { amp: "&", lt: "<", gt: ">", quot: "\"", "#39": "\x27", nbsp: " " };
  const paras = [...html.matchAll(/<p[^>]*>([\s\S]*?)<\/p>/g)]
    .map(m => m[1].replace(/<[^>]+>/g, "").replace(/&(\w+|#\d+);/g, (x, e) => ents[e] ?? " ")
      .replace(/\s+/g, " ").trim())
    // No paragraph with a link or a domain: a share containing a URL goes down the intake link path,
    // which downloads the page instead of reading this text (seen 2026-10-02, da81794).
    .filter(p => p.length > 40 && !/https?:|www\.|\.org|\.com/i.test(p));
  let out = "Gap run\n\n", i = 0;
  while (out.length < 60000 && i < paras.length) out += paras[i++] + "\n\n";
  process.stdout.write(out);
' > "$TXT"
adb push "$TXT" /data/local/tmp/readme-gap.txt >/dev/null
want=$(wc -c < "$TXT"); got=$(adb shell wc -c /data/local/tmp/readme-gap.txt | awk '{print $1}')
[ "$got" = "$want" ] || { echo "FAIL: pushed $got of $want bytes"; exit 1; }
rm -f "$TXT"

restore() {
  [ "${AIRPLANE:-0}" = 1 ] && { adb shell cmd connectivity airplane-mode disable >/dev/null 2>&1 || true; }
  adb shell dumpsys deviceidle unforce >/dev/null 2>&1 || true
  adb shell dumpsys battery reset >/dev/null 2>&1 || true
  adb shell rm -f /data/local/tmp/readme-gap.txt /sdcard/readme-ui.xml >/dev/null 2>&1 || true
}
DEVICE_ON_EXIT=restore

device_clear_app
adb logcat -c
# The device's shell expands the file into the extra; nothing long rides the adb argv.
# Two runs started 2 s after an unlock ended with an empty list (2026-10-02); the same share
# by hand worked. Say what am answered rather than guess next time.
started=$(echo "am start -W -n $PKG/.ShareActivity -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT \"\$(cat /data/local/tmp/readme-gap.txt)\"" \
  | adb shell 2>&1 || true)
device_has "$started" 'Status: ok' || { echo "FAIL: the share did not start:"; grep -vE '^$' <<<"$started" | head -5; exit 1; }
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
b=""
# A 60 KB share takes a few seconds to land in the list; poll rather than look once.
for _ in $(seq 30); do
  adb shell uiautomator dump /sdcard/readme-ui.xml >/dev/null 2>&1 || true
  b=$(adb shell cat /sdcard/readme-ui.xml | grep -oE 'text="ready"[^>]*bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' \
    | head -1 | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' | grep -oE '[0-9]+' | tr '\n' ' ' || true)
  [ -n "$b" ] && break
  sleep 1
done
if [ -z "$b" ]; then
  echo "FAIL: the shared text is not on screen as ready; states seen:"
  adb shell cat /sdcard/readme-ui.xml | grep -oE 'text="(ready|fetching|fetched|fetch-failed[^"]*|extract-poor|Share a link or text to Read Me.)"' | sort | uniq -c
  exit 1
fi
set -- $b
adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
# Phase 4: a first open goes to Trim; Done goes to the Reader, which has Play.
tap_desc() {
  local c=""
  for _ in $(seq 10); do
    adb shell rm -f /sdcard/readme-ui.xml
    adb shell uiautomator dump /sdcard/readme-ui.xml >/dev/null 2>&1 || true
    c=$(adb shell cat /sdcard/readme-ui.xml 2>/dev/null | grep -oE "content-desc=\"$1\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" \
      | head -1 | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' | grep -oE '[0-9]+' | tr '\n' ' ' || true)
    [ -n "$c" ] && break
    sleep 1
  done
  [ -n "$c" ] || { echo "FAIL: nothing on screen has content-desc $1"; exit 1; }
  set -- $c
  adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
}
tap_desc 'trim done'
tap_desc play
rlog() { adb logcat -d -s ReadMe:I; }
for _ in $(seq 20); do device_has "$(rlog)" 'playback start item=1 ' && break; sleep 1; done
device_has "$(rlog)" 'playback start item=1 ' || { echo "FAIL: playback did not start"; exit 1; }

# R-M14 run 2: AIRPLANE=1 cuts every radio for the measurement. The text is already stored.
if [ "${AIRPLANE:-0}" = 1 ]; then
  adb shell cmd connectivity airplane-mode enable
  [ "$(adb shell cmd connectivity airplane-mode | tr -d '\r')" = enabled ] || { echo "FAIL: airplane mode did not turn on"; exit 1; }
  echo "airplane mode on"
fi
adb shell dumpsys battery unplug
adb shell input keyevent KEYCODE_SLEEP
sleep 2
adb shell dumpsys deviceidle force-idle | sed 's/^/  deviceidle: /'
echo "playing for $MINUTES min on battery, screen off, forced Doze ($(date +%T))"
sleep $(( MINUTES * 60 ))
restore
adb shell cmd media_session dispatch pause
for _ in $(seq 10); do device_has "$(rlog)" 'playback gaps n=' && break; sleep 1; done
line=$(grep -oE 'playback gaps n=.*' <<<"$(rlog)" | tail -1 || true)
echo "$line"
[ -n "$line" ] || { echo "device:gap FAIL: no gaps line"; exit 1; }
num() { grep -oE "$1=[0-9]+" <<<"$line" | cut -d= -f2; }
fail=0
log=$(rlog)
device_has "$log" 'request id=' && { echo "FAIL: the share was downloaded as a link, not read as text"; fail=1; }
device_has "$log" 'playback finished' && { echo "FAIL: the text ran out before $MINUTES min; not a $MINUTES-minute measurement"; fail=1; }
[ "$(num n)" -ge $(( MINUTES * 5 )) ] || { echo "FAIL: only $(num n) gaps in $MINUTES min"; fail=1; }
[ "$(num p95)" -le 300 ] || { echo "FAIL: p95 $(num p95) ms > 300"; fail=1; }
[ "$(num max)" -le 1000 ] || { echo "FAIL: max $(num max) ms > 1000"; fail=1; }
[ "$(num stalls)" -eq 0 ] || { echo "FAIL: $(num stalls) stalls"; fail=1; }
pid=$(adb shell pidof "$PKG" 2>/dev/null | tr -d '\r' || true)
# REA-25: a cold engine (8 s to first audio) must not be taken for a dead one.
if rlog | grep -q 'engine lost'; then echo "FAIL: a stall was declared during normal playback"; fail=1; fi
if device_crash_seen "${pid%% *}" <<<"$(adb logcat -d -b crash,main)"; then
  echo "FAIL: crash logged"; fail=1
fi
echo "the screen is off: unlock the phone to use it"
echo "device:gap $([ $fail -eq 0 ] && echo PASS || echo FAIL)"
exit $fail
