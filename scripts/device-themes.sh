#!/usr/bin/env bash
# npm run device:themes - every screen in system light mode and in dark mode, with contrast
# measured (REA-22). Clears Read Me's data, shares two texts written for this, then in each
# mode captures List, Trim with a paragraph cut, the Reader before play (sentence controls
# disabled) and while it speaks (the highlight), Settings and Licenses. Each capture is a
# screenshot and a UI dump; scripts/theme-contrast.py measures every text node and the status
# bar from them. SystemUI demo mode fixes the status bar's contents for the run; the phone's
# night mode and demo mode are restored on exit. Captures stay in .claude/scratch/themes/.
#
# REA-58: a narrow pass after light and dark (THEMES_ONLY=light|dark|narrow runs one pass). The
# display is set to 360 dp wide (wm density 597 on the 1344 px reference phone; NARROW_DENSITY)
# with font scale 1.0, then 2.0, the largest Settings offers; the app is restarted after each
# change (MainActivity is recreated on a density or font change). Each time the idle Reader's
# dock is checked by scripts/dock-check.py; at 2.0 Play is tapped, the playing Reader captured,
# and Pause is proven to sit where Play was by tapping Play's recorded centre and reading
# `playback paused` in logcat (a dump of the playing Reader can come back empty, REA-56).
# Before the first change the phone's own override density and font_scale are written to
# /data/local/tmp/readme-display.saved on the phone; every exit restores from that file and
# deletes it only when both writes succeeded, and with no file nothing on the display is
# touched. A file left by a dead run is restored at the next start. By hand:
#   adb shell cat /data/local/tmp/readme-display.saved
#   adb shell wm density reset        (or wm density <saved>)
#   adb shell settings put system font_scale <saved>   (or settings delete system font_scale)
#   adb shell rm /data/local/tmp/readme-display.saved

DISPLAY_SAVE=/data/local/tmp/readme-display.saved
# The saved values, "density=<n|none> font_scale=<x|null>", or nothing when no file is there.
display_saved() {
  local saved
  saved=$(adb shell cat "$DISPLAY_SAVE" 2>/dev/null | tr -d '\r') || return 0
  grep -qE '^density=([0-9]+|none) font_scale=([0-9.]+|null)$' <<<"$saved" && printf '%s\n' "$saved"
  return 0
}
# Puts the saved display back. Runs on every exit with errexit off (lib/device.sh calls the hook
# as `hook || true`), so every step is checked explicitly; the file goes only after both writes.
display_restore() {
  local saved d f ok=1
  saved=$(display_saved)
  [ -n "$saved" ] || return 0
  d=$(sed -E 's/^density=([^ ]+) .*/\1/' <<<"$saved")
  f=$(sed -E 's/.* font_scale=(.+)$/\1/' <<<"$saved")
  if [ "$d" = none ]; then adb shell wm density reset >/dev/null || ok=0
  else adb shell wm density "$d" >/dev/null || ok=0; fi
  if [ "$f" = null ]; then adb shell settings delete system font_scale >/dev/null || ok=0
  else adb shell settings put system font_scale "$f" >/dev/null || ok=0; fi
  if [ "$ok" = 1 ] && adb shell rm -f "$DISPLAY_SAVE"; then
    echo "display restored (density $d, font_scale $f)"
  else
    echo "WARNING: display NOT restored; by hand: adb shell wm density $([ "$d" = none ] && echo reset || echo "$d");" \
      "adb shell settings $([ "$f" = null ] && echo 'delete system font_scale' || echo "put system font_scale $f");" \
      "adb shell rm $DISPLAY_SAVE" >&2
    return 1
  fi
}
# Saves the phone's own values once (only Override density counts: Physical is not a setting).
display_save() {
  local d f
  [ -n "$(display_saved)" ] && return 0
  d=$(adb shell wm density | tr -d '\r' | sed -nE 's/^Override density: ([0-9]+)$/\1/p')
  f=$(adb shell settings get system font_scale | tr -d '\r')
  grep -qE '^[0-9.]+$' <<<"$f" || f=null
  printf 'density=%s font_scale=%s\n' "${d:-none}" "$f" | adb shell "cat > $DISPLAY_SAVE"
  [ -n "$(display_saved)" ] || { echo "FAIL: could not save the display settings"; return 1; }
}
# display_set <density> <font scale>: saves first, then changes the display.
display_set() {
  display_save || return 1
  adb shell wm density "$1" >/dev/null && adb shell settings put system font_scale "$2" >/dev/null
}
# The stub-adb test sources the script for the display functions alone.
if [ -n "${DEVICE_THEMES_SOURCE_ONLY:-}" ]; then return 0; fi

set -euo pipefail
cd "$(dirname "$0")/.."
. scripts/lib/device.sh
device_take themes
device_require_unlocked
device_install_release

RAW=.claude/scratch/themes  # gitignored
rm -rf "$RAW"; mkdir -p "$RAW"

if [ -n "$(display_saved)" ]; then
  echo "an earlier run left the display changed; restoring it first"
  display_restore || exit 1
fi
NARROW_DENSITY=${NARROW_DENSITY:-597}
THEMES_ONLY=${THEMES_ONLY:-}
case "$THEMES_ONLY" in ''|light|dark|narrow) ;; *) echo "THEMES_ONLY must be light, dark or narrow"; exit 2 ;; esac
night=$(adb shell cmd uimode night | tr -d '\r' | awk '{print $NF}')
demo() { printf '%s\n' "am broadcast -a com.android.systemui.demo $*" | adb shell >/dev/null; }
restore() {
  display_restore || true
  demo -e command exit
  adb shell cmd uimode night "$night" >/dev/null || true
  adb shell rm -f /sdcard/readme-ui.xml
}
DEVICE_ON_EXIT=restore
adb shell settings put global sysui_demo_allowed 1
demo -e command enter
demo -e command clock -e hhmm 0930
demo -e command battery -e level 100 -e plugged false
demo -e command network -e wifi show -e level 4 -e mobile hide
demo -e command notifications -e visible false

# Long enough that playback cannot reach the end before Pause: finishing archives the item
# (R-M11), which took Pause off the screen and the item off the list for the dark pass (REA-28,
# twice on 2026-10-03 once the Reader dumped more slowly). On 2026-10-15 the five sentences
# (about 9 s at 2x) ended 39 ms after Pause: a dump can wait about 12 s for the playing Reader to
# go idle (REA-56), so the last paragraph now runs on for about a minute.
FIRST='Light and dark both have to read well.

This second paragraph gets cut in Trim, so its faint style is measured too.

The last paragraph is plain text again, long enough to wrap onto a second line on the phone. It goes on for a while, because reading it to the end archives the item, and the dark pass opens it again. A few more sentences keep it playing while the screen is captured and Pause is pressed. The playback stops well before here. Filler sentence one is here only to keep the reading going. Filler sentence two is here only to keep the reading going. Filler sentence three is here only to keep the reading going. Filler sentence four is here only to keep the reading going. Filler sentence five is here only to keep the reading going. Filler sentence six is here only to keep the reading going. Filler sentence seven is here only to keep the reading going. Filler sentence eight is here only to keep the reading going. Filler sentence nine is here only to keep the reading going. Filler sentence ten is here only to keep the reading going. Filler sentence eleven is here only to keep the reading going. Filler sentence twelve is here only to keep the reading going. Filler sentence thirteen is here only to keep the reading going. Filler sentence fourteen is here only to keep the reading going. Filler sentence fifteen is here only to keep the reading going. Filler sentence sixteen is here only to keep the reading going. Filler sentence seventeen is here only to keep the reading going. Filler sentence eighteen is here only to keep the reading going. Filler sentence nineteen is here only to keep the reading going. Filler sentence twenty is here only to keep the reading going.'
SECOND='A second item keeps the list from looking empty. It has one paragraph.'

device_clear_app
adb logcat -c
share() {
  # The text goes on stdin, not the adb argv, which adbd logs (AGENTS.md, SPIKE-01).
  printf '%s\n' "am start -W -n $PKG/.ShareActivity -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT $(printf '%q' "$1")" \
    | adb shell >/dev/null
  sleep 1
}
share "$SECOND"
share "$FIRST"

ui() {
  adb shell uiautomator dump /sdcard/readme-ui.xml >/dev/null 2>&1 || true
  adb shell cat /sdcard/readme-ui.xml 2>/dev/null || true
}
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
# Swipe up the middle of the screen, from 80% to 30% of its height, so the scroll works on the
# reference phone and on a tablet alike (the last line of `wm size` is the size in use).
read -r SCREEN_W SCREEN_H < <(adb shell wm size | tail -1 | grep -oE '[0-9]+x[0-9]+' | tr x ' ')
# A node scrolled out of view is still in the dump, with bounds whose top is below its bottom
# (Settings' Licenses, [0,2983][1344,2920], 2026-10-15): it counts only when it lies on screen,
# above the bottom tenth (the gesture bar).
desc_on_screen() {
  local b
  b=$(grep -oE "content-desc=\"$1\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" <<<"$2" | head -1 \
    | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' | grep -oE '[0-9]+' | tr '\n' ' ')
  set -- $b
  [ $# = 4 ] && [ "$2" -lt "$4" ] && [ "$4" -le $((SCREEN_H * 9 / 10)) ]
}
scroll_to_desc() {
  for _ in $(seq 6); do
    desc_on_screen "$1" "$(ui)" && return 0
    adb shell input swipe $((SCREEN_W / 2)) $((SCREEN_H * 8 / 10)) $((SCREEN_W / 2)) $((SCREEN_H * 3 / 10)) 300
    sleep 1
  done
}
wait_for() {
  for _ in $(seq "${2:-15}"); do device_has "$(ui)" "$1" && return 0; sleep 1; done
  echo "FAIL: '$1' not on screen (screen at failure: $RAW/fail.png)"
  adb exec-out screencap -p > "$RAW/fail.png"
  exit 1
}
# shot <name>: the screenshot and the dump it is measured against, taken back to back.
shot() {
  sleep 1
  adb exec-out screencap -p > "$RAW/$1.png"
  ui > "$RAW/$1.xml"
  echo "captured $1"
}

for mode in light dark; do
  [ -z "$THEMES_ONLY" ] || [ "$THEMES_ONLY" = "$mode" ] || continue
  [ "$mode" = light ] && adb shell cmd uimode night no >/dev/null || adb shell cmd uimode night yes >/dev/null
  adb shell am force-stop "$PKG"
  adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
  wait_for 'Light and dark both'
  shot "$mode-1-list"
  tap_node text 'Light and dark both[^"]*'
  if [ "$mode" = light ]; then
    wait_for 'content-desc="trim done"'   # Trim opens by itself on the first open
  else
    wait_for 'content-desc="trim"'
    tap_node content-desc 'trim'
    wait_for 'content-desc="trim done"'
  fi
  device_has "$(ui)" 'resource-id="paragraph 2 cut"' || tap_node resource-id 'paragraph 2'
  wait_for 'resource-id="paragraph 2 cut"'
  shot "$mode-2-trim"
  tap_node content-desc 'trim done'
  wait_for 'content-desc="play"'
  shot "$mode-3-reader"
  adb logcat -c
  tap_node content-desc 'play'
  for _ in $(seq 40); do device_has "$(adb logcat -d -s ReadMe:I)" 'playback start item=' && break; sleep 1; done
  sleep 1
  shot "$mode-4-reading"
  tap_node content-desc 'pause'
  adb shell input keyevent KEYCODE_BACK
  tap_node content-desc 'settings'
  wait_for 'content-desc="voice, [^"]+"'
  shot "$mode-5-settings"
  # REA-28: the voice Select's bottom sheet, over its scrim.
  tap_node content-desc 'voice, [^"]+'
  wait_for 'content-desc="close voice"'
  shot "$mode-5b-voice-sheet"
  adb shell input keyevent KEYCODE_BACK
  for _ in $(seq 10); do device_has "$(ui)" 'content-desc="close voice"' || break; sleep 1; done
  scroll_to_desc 'licenses'
  tap_node content-desc 'licenses'
  wait_for 'text="(MIT|Apache-2.0|ISC|BSD-3-Clause)"'
  shot "$mode-6-licenses"
  adb shell input keyevent KEYCODE_BACK
  adb shell input keyevent KEYCODE_BACK
done

# REA-58: the narrow pass, light mode. Opens the Reader from wherever the restarted app is:
# the List, Trim (the first open of an item, THEMES_ONLY=narrow) or the Reader itself.
open_reader() {
  adb shell am force-stop "$PKG"
  adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
  local screen=""
  for _ in $(seq 20); do
    screen=$(ui)
    device_has "$screen" 'content-desc="(play|trim done)"|Light and dark both' && break
    sleep 1
  done
  if ! device_has "$screen" 'content-desc="(play|trim done)"'; then
    tap_node text 'Light and dark both[^"]*'
    for _ in $(seq 15); do
      screen=$(ui); device_has "$screen" 'content-desc="(play|trim done)"' && break; sleep 1
    done
  fi
  if device_has "$screen" 'content-desc="trim done"'; then tap_node content-desc 'trim done'; fi
  wait_for 'content-desc="play"'
}
dock_fail=0
dock() {  # dock <capture> <play|pause>: the checker's lines, labels and bounds only
  local rc=0
  python3 scripts/dock-check.py "$RAW/$1.xml" "$NARROW_DENSITY" "$SCREEN_H" "$2" | sed "s/^/$1 /" || true
  python3 scripts/dock-check.py "$RAW/$1.xml" "$NARROW_DENSITY" "$SCREEN_H" "$2" >/dev/null || rc=$?
  [ "$rc" = 0 ] || dock_fail=1
  return 0
}
if [ -z "$THEMES_ONLY" ] || [ "$THEMES_ONLY" = narrow ]; then
  adb shell cmd uimode night no >/dev/null
  echo "narrow: $((SCREEN_W * 160 / NARROW_DENSITY)) dp wide (wm density $NARROW_DENSITY)"
  display_set "$NARROW_DENSITY" 1.0
  open_reader
  shot narrow-1-reader
  dock narrow-1-reader play
  display_set "$NARROW_DENSITY" 2.0
  open_reader
  shot narrow-3-reader
  dock narrow-3-reader play
  centre=$(python3 scripts/dock-check.py "$RAW/narrow-3-reader.xml" "$NARROW_DENSITY" "$SCREEN_H" play \
    | sed -nE 's/^dock: play centre ([0-9]+),([0-9]+)$/\1 \2/p' || true)
  if [ -z "$centre" ]; then
    echo "narrow: FAIL no Play centre to tap; Pause's place not checked"
    dock_fail=1
  else
    adb logcat -c
    adb shell input tap $centre
    for _ in $(seq 40); do device_has "$(adb logcat -d -s ReadMe:I)" 'playback start item=' && break; sleep 1; done
    sleep 1
    shot narrow-4-reading
    if grep -q '<node' "$RAW/narrow-4-reading.xml"; then
      dock narrow-4-reading pause
      pc=$(python3 scripts/dock-check.py "$RAW/narrow-4-reading.xml" "$NARROW_DENSITY" "$SCREEN_H" pause \
        | sed -nE 's/^dock: pause centre ([0-9]+),([0-9]+)$/\1 \2/p' || true)
      set -- $centre $pc
      if [ $# = 4 ] && [ $(( $1 > $3 ? $1 - $3 : $3 - $1 )) -le 2 ] && [ $(( $2 > $4 ? $2 - $4 : $4 - $2 )) -le 2 ]; then
        echo "narrow: Pause's centre ($pc) is Play's ($centre)"
      else
        echo "narrow: FAIL Pause's centre (${pc:-none}) is not Play's ($centre)"; dock_fail=1
      fi
    else echo "narrow-4-reading dock: no capture (the playing Reader's dump came back empty)"; fi
    # Pause must sit where Play was: tap Play's recorded centre and read the pause in logcat.
    adb logcat -c
    adb shell input tap $centre
    paused=0
    for _ in $(seq 10); do device_has "$(adb logcat -d -s ReadMe:I)" 'playback paused item=' && { paused=1; break; }; sleep 1; done
    if [ "$paused" = 1 ]; then echo "narrow: Pause at Play's centre ($centre) paused playback"
    else echo "narrow: FAIL a tap at Play's centre ($centre) while playing did not pause"; dock_fail=1; fi
  fi
  adb shell input keyevent KEYCODE_BACK
  display_restore || dock_fail=1
fi

if adb logcat -d | device_crash_seen; then echo "FAIL: a crash in the log"; exit 1; fi
contrast=0
python3 scripts/theme-contrast.py "$RAW" || contrast=1
[ "$dock_fail" = 0 ] || echo "FAIL: the Reader's dock at $NARROW_DENSITY dpi (lines above)"
if [ "$contrast" = 0 ] && [ "$dock_fail" = 0 ]; then echo "device:themes PASS"; else echo "device:themes FAIL"; exit 1; fi
