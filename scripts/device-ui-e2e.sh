#!/usr/bin/env bash
# npm run device:ui - Phase 4 screens on the phone, as a user would (R-M01, R-M05, R-M07,
# R-M10, R-M13). Clears Read Me's data, shares a three-paragraph text and a dead link, then:
# the list shows both with their R-M10 states; opening the text goes to Trim (first open);
# cutting paragraph 2 and Done goes to the Reader, which plays only the kept sentences and
# highlights the current one; back to the list shows progress; the dead link is deleted;
# Settings lists a voice and Licenses lists packages. Then REA-35's cold start: a rate tap and
# a cut made while the engine starts, and a cut while playing. Checks logs for text and crashes.
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
# so the highlight is checked in pixels: the current sentence's background is primaryContainer
# from src/ui/theme.ts, #ecdcff in light mode and #5a27a3 in dark (REA-24). The phone may be in
# either mode, and neither colour is used anywhere else on screen.
highlight_px() {
  adb exec-out screencap | node -e '
    const b = require("fs").readFileSync(0);
    const w = b.readUInt32LE(0), h = b.readUInt32LE(4), off = b.length - w * h * 4;
    const near = (i, c) => Math.abs(b[i] - c[0]) < 4 && Math.abs(b[i + 1] - c[1]) < 4 && Math.abs(b[i + 2] - c[2]) < 4;
    let n = 0;
    for (let i = off; i < b.length; i += 4)
      if (near(i, [0xec, 0xdc, 0xff]) || near(i, [0x5a, 0x27, 0xa3])) n++;
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

# Settings: the voice Select (REA-28), then Licenses (R-M01, R-M13). The field is a combobox
# labelled "voice" whose value is the current voice, dumped as "voice, <name>"; its sheet lists
# each offline voice as a radio labelled "voice <name>" with checked=.
FIELD='voice, [^"]+'
tap_node content-desc 'settings'
on_screen "content-desc=\"$FIELD\"" || fail=1
on_screen '[0-9]+ items, [0-9]+ archived' || fail=1
# Nothing above touches the rate, so it is still the 2.0x default. Twice on 2026-10-03 it read
# 1.7x after a scripted run (three presses of "slower" no script sends); this catches a repeat.
on_screen 'text="2\.0x"' || { echo "FAIL: the default rate moved without a press"; fail=1; }
tap_node content-desc "$FIELD"
sheet=""
for _ in $(seq 10); do sheet=$(ui); device_has "$sheet" 'content-desc="close voice"' && break; sleep 1; done
radios=$(grep -oE 'content-desc="voice [^",]+"[^>]*bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' <<<"$sheet" || true)
voices=$(sed -E 's/content-desc="voice ([^"]+)".*/\1/' <<<"$radios")
was=$(grep -E 'checked="true"' <<<"$radios" | head -1 | sed -E 's/content-desc="voice ([^"]+)".*/\1/' || true)
n=$(grep -c . <<<"$voices" || true)
echo "voice sheet: $n voices on screen, one checked: ${was:+yes}"
[ "$n" -ge 1 ] && [ -n "$was" ] || { echo "FAIL: the voice sheet has no checked voice"; fail=1; }
# Another voice whose row is fully on screen: the list opens scrolled to the checked one, so
# the row above can be cut to a sliver under the sheet's heading (2026-10-03).
other=$(grep -v 'checked="true"' <<<"$radios" | while read -r r; do
  read -r y0 y1 < <(sed -E 's/.*\[[0-9]+,([0-9]+)\]\[[0-9]+,([0-9]+)\]".*/\1 \2/' <<<"$r")
  [ $((y1 - y0)) -ge 150 ] && { sed -E 's/content-desc="voice ([^"]+)".*/\1/' <<<"$r"; break; }
done || true)
if [ -n "$other" ]; then
  # Pick another voice: the sheet closes and the field shows it.
  tap_node content-desc "voice $other"
  for _ in $(seq 10); do device_has "$(ui)" 'content-desc="close voice"' || break; sleep 1; done
  device_has "$(ui)" 'content-desc="close voice"' && { echo "FAIL: the voice sheet stayed open after a pick"; fail=1; }
  on_screen "text=\"$other\"" || { echo "FAIL: the field does not show the picked voice"; fail=1; }
  tap_node content-desc "$FIELD"
  tap_node content-desc "voice $was"
  on_screen "text=\"$was\"" || { echo "FAIL: the first voice was not restored"; fail=1; }
else
  echo "only one offline voice: the pick is not exercised"
  adb shell input keyevent KEYCODE_BACK
fi
# Back closes an open sheet without leaving Settings.
tap_node content-desc "$FIELD"
on_screen 'content-desc="close voice"' || fail=1
adb shell input keyevent KEYCODE_BACK
for _ in $(seq 10); do device_has "$(ui)" 'content-desc="close voice"' || break; sleep 1; done
device_has "$(ui)" 'content-desc="close voice"' && { echo "FAIL: Back did not close the voice sheet"; fail=1; }
on_screen 'content-desc="licenses"' || { echo "FAIL: Back left Settings instead of closing the sheet"; fail=1; }
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

# Cold start (REA-35): while the engine starts, the service holds Play with no item loaded. A
# rate tap then dropped the Play, and a cut made then was not applied. The phone's default
# engine is force-stopped, never changed (TtsOpen binds the system's engine from Android 14).
# The service must be new, so Read Me is force-stopped too: only a new service's speaker is
# still pending. Each tap sequence goes to the phone as one adb shell script so it lands inside
# the window, which is measured from a marker logged just before the Play tap.
engine=$(adb shell settings get secure tts_default_synth | tr -d '\r')
count() { logs | grep -cE -- "$1" || true; }
last_start() { logs | grep 'playback start item=' | tail -1 || true; }
# at <tag> <ERE>: epoch seconds of the last matching line in that tag, or nothing.
at() { adb logcat -d -v epoch -s "$1:I" | grep -E -- "$2" | tail -1 | awk '{print $1}' || true; }
ms() { awk -v a="$1" -v b="$2" 'BEGIN { if (a == "" || b == "") print "?"; else printf "%d", (b - a) * 1000 }'; }
open_cold() {
  adb shell am force-stop "$PKG"
  adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
  tap_node text 'Echo one is here[^"]*'
  on_screen 'content-desc="play"' || fail=1
  # The Reader probes the engine on open, which binds it; let that finish before stopping it.
  sleep 5
}
# The window, and whether the step reached the cold start ("reached"): the service logged
# `playback waiting` after the Play tap (it held the Play for the engine), and the last tap
# landed before the first start after the Play tap. A cut that lands once reading has begun
# comes after that first start, so it cannot pass as a cut made during the cold start.
window() {
  local play tapped held start
  play=$(at ReadMeE2E rea35-play); tapped=$(at ReadMeE2E rea35-tapped)
  held=$(at ReadMe 'playback waiting item=')
  start=$(adb logcat -d -v epoch -s ReadMe:I | grep 'playback start item=' | awk -v p="${play:-0}" '$1 >= p { print $1; exit }' || true)
  echo "cold start ($1): $(ms "$play" "$start") ms from the Play tap to playback start; the last tap at $(ms "$play" "$tapped") ms; held at $(ms "$play" "$held") ms"
  [ "$(ms "$play" "$held")" != "?" ] && [ "$(ms "$play" "$held")" -ge 0 ] \
    && [ "$(ms "$tapped" "$start")" != "?" ] && [ "$(ms "$tapped" "$start")" -ge 0 ]
}
sentences() { sed -nE 's/.* sentences=([0-9]+) .*/\1/p' <<<"$1"; }
if [ -z "$engine" ] || [ "$engine" = null ]; then
  echo "cold start (REA-35): NOT RUN (no default engine in settings)"
else
  # Three paragraphs of ten sentences: Echo, Foxtrot, Golf.
  three=""
  for name in Echo Foxtrot Golf; do
    para=""; for w in "${words[@]}"; do para+="$name $w is here. "; done
    three+="${para% }"$'\n\n'
  done
  share "${three%$'\n\n'}"
  adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
  tap_node text 'Echo one is here[^"]*'
  on_screen 'content-desc="trim done"' || fail=1
  tap_node content-desc 'trim done'
  on_screen 'content-desc="play"' || fail=1
  sleep 3 # the Reader settles (it cannot be dumped while it updates)
  play_at=$(centre_of play); faster_at=$(centre_of faster); trim_at=$(centre_of trim)
  tap_node content-desc trim
  on_screen 'content-desc="trim done"' || fail=1
  p1_at=$(centre_of 'paragraph 1'); done_at=$(centre_of 'trim done')
  tap_node content-desc 'trim done'
  if [ -z "$play_at" ] || [ -z "$faster_at" ] || [ -z "$trim_at" ] || [ -z "$p1_at" ] || [ -z "$done_at" ]; then
    echo "FAIL: cold start (REA-35): a control was not found on screen"; fail=1
  else
    # (a) Play, then faster, during the cold start: Play must go on, at the new rate.
    n=$(count 'playback start item=')
    open_cold
    printf '%s\n' "am force-stop $engine" "log -t ReadMeE2E rea35-play" "input tap $play_at" \
      "input tap $faster_at" "log -t ReadMeE2E rea35-tapped" | adb shell >/dev/null
    for _ in $(seq 20); do [ "$(count 'playback start item=')" -gt "$n" ] && break; sleep 1; done
    if [ "$(count 'playback start item=')" -le "$n" ]; then
      echo "FAIL: cold start (a): a rate tap while the engine started dropped Play (no playback start in 20 s)"; fail=1
    elif window a; then
      device_has "$(last_start)" ' rate=2\.1$' || { echo "FAIL: cold start (a): Play did not start at the new rate"; fail=1; }
    else
      echo "cold start (a): NOT REACHED (the engine was up before the rate tap landed)"
    fi
    # Pause near the start, so the position stays in paragraph 1.
    n=$(count 'playback paused item=')
    adb shell input tap $play_at
    for _ in $(seq 10); do [ "$(count 'playback paused item=')" -gt "$n" ] && break; sleep 1; done

    # (b) Play, then cut paragraph 1, during the cold start: the start reads only what is kept.
    n=$(count 'playback start item=')
    open_cold
    printf '%s\n' "am force-stop $engine" "log -t ReadMeE2E rea35-play" "input tap $play_at" \
      "input tap $trim_at" "sleep 0.7" "input tap $p1_at" "input tap $done_at" \
      "log -t ReadMeE2E rea35-tapped" | adb shell >/dev/null
    for _ in $(seq 20); do [ "$(count 'playback start item=')" -gt "$n" ] && break; sleep 1; done
    sleep 3 # a replacing start, if the engine came up between the tap and the new play
    new=$(( $(count 'playback start item=') - n ))
    if [ "$new" -le 0 ]; then
      echo "FAIL: cold start (b): no playback start in 20 s"; fail=1
    elif window b; then
      got=$(sentences "$(last_start)")
      echo "cold start (b): $new start line(s), the last with sentences=$got"
      [ "$got" = 20 ] || { echo "FAIL: cold start (b): the cut made while the engine started was not applied"; fail=1; }
    else
      echo "cold start (b): NOT REACHED (the engine was up before the cut landed)"
    fi

    # (c) A cut of the current paragraph while playing: a new start with fewer sentences.
    sleep 2
    before=$(sentences "$(last_start)")
    n=$(count 'playback start item=')
    tap_node content-desc trim
    on_screen 'content-desc="trim done"' || fail=1
    tap_node content-desc 'paragraph 2'
    on_screen 'content-desc="paragraph 2 cut"' || fail=1
    tap_node content-desc 'trim done'
    for _ in $(seq 15); do [ "$(count 'playback start item=')" -gt "$n" ] && break; sleep 1; done
    after=$(sentences "$(last_start)")
    echo "cut while playing: sentences=$before before, sentences=$after after"
    if [ "$(count 'playback start item=')" -le "$n" ] || [ -z "$before" ] || [ -z "$after" ] || [ "$after" -ge "$before" ]; then
      echo "FAIL: cutting the current paragraph while playing did not re-plan with fewer sentences"; fail=1
    fi
    # Pause what still plays (the Reader cannot be dumped while it updates, so ask the log).
    state=$(logs | grep -E 'playback (start|resumed|paused|finished) ' | tail -1 || true)
    if device_has "$state" 'playback (start|resumed) '; then adb shell input tap $play_at; fi
  fi
fi

all=$(adb logcat -d)
if device_has "$all" 'Alpha one|Beta two|Gamma one|Delta sentence|Echo one|Foxtrot one|Golf one|nothing-here'; then
  echo "FAIL: a log line carries shared text or a URL path"; fail=1
fi
pid=$(adb shell pidof "$PKG" 2>/dev/null | tr -d '\r' || true)
if device_crash_seen "${pid%% *}" <<<"$(adb logcat -d -b crash,main)"; then
  # Kept for diagnosis in the gitignored scratch dir; never pasted into a ticket (AGENTS.md 1).
  mkdir -p .claude/scratch && adb logcat -d -b crash,main > .claude/scratch/device-ui-crash.log
  echo "FAIL: crash logged (.claude/scratch/device-ui-crash.log)"; fail=1
fi
adb shell rm -f /sdcard/readme-ui.xml
echo "device:ui $([ $fail -eq 0 ] && echo PASS || echo FAIL)"
exit $fail
