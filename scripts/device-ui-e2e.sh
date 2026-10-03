#!/usr/bin/env bash
# npm run device:ui - Phase 4 screens on the phone, as a user would (R-M01, R-M05, R-M07,
# R-M10, R-M13). Clears Read Me's data, shares a three-paragraph text and a dead link, then:
# the list shows both with their R-M10 states; opening the text goes to Trim (first open);
# cutting paragraph 2 and Done goes to the Reader, which plays only the kept sentences and
# highlights the current one; back to the list shows progress; the dead link is deleted;
# Settings lists a voice and Licenses lists packages. Checks logs for text and crashes.
set -euo pipefail
cd "$(dirname "$0")/.."
. scripts/lib/device.sh
device_take interactive
device_require_unlocked
device_install_release
# The screen dump lives on shared storage; remove it however the script ends.
cleanup_dump() { adb shell rm -f /sdcard/readme-ui.xml >/dev/null 2>&1 || true; }
DEVICE_ON_EXIT=cleanup_dump

# Ten four-word sentences in each kept paragraph: playback must outlast the screen checks
# (two sentences each finished in 8 s, before the first uiautomator dump; 2026-10-02).
words=(one two three four five six seven eight nine ten)
alpha=""; gamma=""
for w in "${words[@]}"; do alpha+="Alpha $w is here. "; gamma+="Gamma $w is here. "; done
TEXT="${alpha% }

Beta one is cut. Beta two is cut. Beta three is cut.

${gamma% }"

device_clear_app
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
  # Remove the last dump first: a failed dump must not hand back the previous screen.
  adb shell rm -f /sdcard/readme-ui.xml
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
# centre_of <ERE on content-desc>: prints "x y" of the first matching node, or nothing.
centre_of() {
  local b
  b=$(ui | grep -oE "content-desc=\"$1\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" | head -1 \
    | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' | grep -oE '[0-9]+' | tr '\n' ' ' || true)
  [ -n "$b" ] || return 0
  set -- $b
  echo "$(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))"
}
# uiautomator cannot dump while the Reader updates ("could not get idle state", 2026-10-02),
# so the highlight is checked in pixels: the current sentence's background is #ffe680.
highlight_px() {
  adb exec-out screencap | node -e '
    const b = require("fs").readFileSync(0);
    const w = b.readUInt32LE(0), h = b.readUInt32LE(4), off = b.length - w * h * 4;
    let n = 0;
    for (let i = off; i < b.length; i += 4)
      if (Math.abs(b[i] - 255) < 4 && Math.abs(b[i + 1] - 230) < 4 && Math.abs(b[i + 2] - 128) < 4) n++;
    console.log(n);'
}
on_screen() {
  for _ in $(seq 15); do device_has "$(ui)" "$1" && return 0; sleep 1; done
  echo "FAIL: '$1' not on screen"; return 1
}
logs() { adb logcat -d -s ReadMe:I; }

fail=0
# List (R-M01, R-M10)
on_screen 'text="Shared text · 92 words"' || fail=1
on_screen 'text="fetch-failed: offline"' || fail=1
on_screen 'content-desc="retry 127.0.0.1"' || fail=1
on_screen 'share the text to Read Me' || fail=1

# First open goes to Trim (R-M05); cut paragraph 2 by tapping it, then Done
tap_node text 'Shared text · 92 words'
on_screen 'content-desc="trim done"' || fail=1
tap_node content-desc 'paragraph 2'
on_screen 'content-desc="paragraph 2 cut"' || fail=1
device_has "$(ui)" 'content-desc="paragraph (1|3) cut"' && { echo "FAIL: the wrong paragraph was cut"; fail=1; }
on_screen '2 of 3 paragraphs kept' || fail=1
tap_node content-desc 'trim done'

# Reader plays only the kept sentences and highlights the current one (R-M07)
on_screen 'content-desc="play"' || fail=1
play_at=$(centre_of play)
[ -n "$play_at" ] || { echo "FAIL: no play button"; exit 1; }
adb shell input tap $play_at
for _ in $(seq 20); do device_has "$(logs)" 'playback start item=1 ' && break; sleep 1; done
device_has "$(logs)" 'playback start item=1 sentences=20 ' || { echo "FAIL: expected 20 kept sentences"; logs | grep 'playback start' | sed 's/^/  /'; fail=1; }
sleep 2
px=$(highlight_px || echo 0)
[ "${px:-0}" -gt 1000 ] || { echo "FAIL: no highlighted sentence on screen ($px px)"; fail=1; }
# Let it read on (a cold engine can take seconds to start), then pause where Play was (the
# same button, relabelled).
sleep 10
adb shell input tap $play_at
for _ in $(seq 10); do device_has "$(logs)" 'playback paused item=1 ' && break; sleep 1; done
device_has "$(logs)" 'playback paused item=1 ' || { echo "FAIL: the Reader's pause did not pause"; fail=1; }
on_screen 'content-desc="play"' || fail=1
device_has "$(logs)" 'playback paused item=1 paragraph=0 offset=0$' && { echo "FAIL: paused before reading anything"; fail=1; }
px=$(highlight_px || echo 0)
[ "${px:-0}" -gt 1000 ] || { echo "FAIL: the paused sentence is not highlighted ($px px)"; fail=1; }

# Back to the list: progress and the paused marker show (R-M01)
adb shell input keyevent KEYCODE_BACK
on_screen '% read' || fail=1
on_screen 'text="paused"' || fail=1

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
# Alphabetical, so check what is on screen: packages with a license line.
on_screen 'text="(MIT|Apache-2.0|ISC|BSD-3-Clause)"' || fail=1
adb shell input keyevent KEYCODE_BACK
adb shell input keyevent KEYCODE_BACK
on_screen 'text="Read Me"' || fail=1

# R-M07 "kept in view" inside one long paragraph: a text with no blank lines is one paragraph.
# Jump 50 sentences in with Next (deep enough to be below the first screen), then the
# highlight must still be on screen.
long=""
for i in $(seq 80); do long+="Delta sentence $i keeps going with a few more words so that the line fills up. "; done
share "${long% }"
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
tap_node text 'Delta sentence 1 keeps going[^"]*'
tap_node content-desc 'trim done'
on_screen 'content-desc="play"' || fail=1
long_play=$(centre_of play)
adb shell input tap $long_play
# Counted, not matched by id: the deleted dead link's id may be reused.
for _ in $(seq 20); do [ "$(logs | grep -c 'playback start item=' || true)" -ge 2 ] && break; sleep 1; done
sleep 2
adb shell input tap $long_play
for _ in $(seq 10); do [ "$(logs | grep -c 'playback paused item=' || true)" -ge 2 ] && break; sleep 1; done
next_at=$(centre_of 'next sentence')
[ -n "$next_at" ] || { echo "FAIL: no next sentence button for the loaded item"; fail=1; }
if [ -n "$next_at" ]; then
  for _ in $(seq 50); do adb shell input tap $next_at; sleep 0.2; done
  sleep 2
  px=$(highlight_px || echo 0)
  [ "${px:-0}" -gt 1000 ] || { echo "FAIL: deep in a long paragraph the highlight is off screen ($px px)"; fail=1; }
fi

all=$(adb logcat -d)
if device_has "$all" 'Alpha one|Beta two|Gamma one|Delta sentence|nothing-here'; then
  echo "FAIL: a log line carries shared text or a URL path"; fail=1
fi
pid=$(adb shell pidof "$PKG" 2>/dev/null | tr -d '\r' || true)
if device_crash_seen "${pid%% *}" <<<"$(adb logcat -d -b crash,main)"; then
  echo "FAIL: crash logged"; fail=1
fi
adb shell rm -f /sdcard/readme-ui.xml
echo "device:ui $([ $fail -eq 0 ] && echo PASS || echo FAIL)"
exit $fail
