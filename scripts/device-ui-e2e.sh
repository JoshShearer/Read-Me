#!/usr/bin/env bash
# npm run device:ui - Phase 4 screens on the phone, as a user would (R-M01, R-M05, R-M07,
# R-M10, R-M13). Clears Read Me's data, shares a three-paragraph text and a dead link, then:
# the list shows both with their R-M10 states; opening the text goes to Trim (first open);
# cutting paragraph 2 and Done goes to the Reader, which plays only the kept sentences and
# highlights the current one; back to the list shows progress; the dead link is deleted;
# Settings lists a voice and Licenses lists react-native. Checks logs for text and crashes.
set -euo pipefail
cd "$(dirname "$0")/.."
. scripts/lib/device.sh
device_take interactive
device_require_unlocked
device_install_release

TEXT='Alpha one is here. Alpha two is here.

Beta one is cut. Beta two is cut. Beta three is cut.

Gamma one is here. Gamma two is here.'

adb shell pm clear "$PKG" >/dev/null
adb logcat -c
share() {
  # The text goes on stdin, not the adb argv, which adbd logs (AGENTS.md, SPIKE-01).
  printf '%s\n' "am start -W -n $PKG/.ShareActivity -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT $(printf '%q' "$1")" \
    | adb shell >/dev/null
}
share "$TEXT"
share "dead http://127.0.0.1:9/nothing-here"
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null

ui() {
  adb shell uiautomator dump /sdcard/readme-ui.xml >/dev/null 2>&1 || true
  adb shell cat /sdcard/readme-ui.xml 2>/dev/null || true
}
# tap_node <attr> <ERE>: taps the centre of the first node whose attr (text or content-desc)
# matches. Retries for 10 s while the screen settles.
tap_node() {
  local b=""
  for _ in $(seq 10); do
    b=$(ui | grep -oE "$1=\"$2\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" | head -1 \
      | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' | grep -oE '[0-9]+' | tr '\n' ' ' || true)
    [ -n "$b" ] && break
    sleep 1
  done
  [ -n "$b" ] || { echo "FAIL: nothing on screen has $1 matching $2"; exit 1; }
  set -- $b
  adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
}
on_screen() {
  for _ in $(seq 15); do device_has "$(ui)" "$1" && return 0; sleep 1; done
  echo "FAIL: '$1' not on screen"; return 1
}
logs() { adb logcat -d -s ReadMe:I; }

fail=0
# List (R-M01, R-M10)
on_screen 'text="Shared text · 28 words"' || fail=1
on_screen 'text="fetch-failed: offline"' || fail=1
on_screen 'content-desc="retry 127.0.0.1"' || fail=1
on_screen 'share the text to Read Me' || fail=1

# First open goes to Trim (R-M05); cut paragraph 2 by tapping it, then Done
tap_node text 'Shared text · 28 words'
on_screen 'content-desc="trim done"' || fail=1
tap_node content-desc 'paragraph 2'
on_screen 'content-desc="paragraph 2 cut"' || fail=1
device_has "$(ui)" 'content-desc="paragraph (1|3) cut"' && { echo "FAIL: the wrong paragraph was cut"; fail=1; }
on_screen '2 of 3 paragraphs kept' || fail=1
tap_node content-desc 'trim done'

# Reader plays only the kept sentences and highlights the current one (R-M07)
on_screen 'content-desc="play"' || fail=1
tap_node content-desc 'play'
for _ in $(seq 20); do device_has "$(logs)" 'playback start item=1 ' && break; sleep 1; done
device_has "$(logs)" 'playback start item=1 sentences=4 ' || { echo "FAIL: expected 4 kept sentences"; logs | grep 'playback start' | sed 's/^/  /'; fail=1; }
on_screen 'content-desc="current paragraph"' || fail=1
sleep 3
tap_node content-desc 'pause'
for _ in $(seq 10); do device_has "$(logs)" 'playback paused item=1 ' && break; sleep 1; done

# Back to the list: progress shows (R-M01)
adb shell input keyevent KEYCODE_BACK
on_screen '% read' || fail=1

# Delete the dead link (R-M10)
tap_node content-desc 'delete 127.0.0.1'
tap_node text '(Delete|DELETE)'
for _ in $(seq 10); do device_has "$(ui)" 'fetch-failed: offline' || break; sleep 1; done
device_has "$(ui)" 'fetch-failed: offline' && { echo "FAIL: the dead link was not deleted"; fail=1; }

# Settings: a voice, then Licenses (R-M01, R-M13)
tap_node content-desc 'settings'
on_screen 'content-desc="voice [^"]+"' || fail=1
on_screen '[0-9]+ items, [0-9]+ archived' || fail=1
tap_node content-desc 'licenses'
on_screen 'text="react-native [0-9.]+"' || fail=1
adb shell input keyevent KEYCODE_BACK
adb shell input keyevent KEYCODE_BACK
on_screen 'text="Read Me"' || fail=1

all=$(adb logcat -d)
if device_has "$all" 'Alpha one|Beta two|Gamma one|nothing-here'; then
  echo "FAIL: a log line carries shared text or a URL path"; fail=1
fi
pid=$(adb shell pidof "$PKG" 2>/dev/null | tr -d '\r' || true)
if device_crash_seen "${pid%% *}" <<<"$(adb logcat -d -b crash,main)"; then
  echo "FAIL: crash logged"; fail=1
fi
adb shell rm -f /sdcard/readme-ui.xml
echo "device:ui $([ $fail -eq 0 ] && echo PASS || echo FAIL)"
exit $fail
