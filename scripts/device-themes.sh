#!/usr/bin/env bash
# npm run device:themes - every screen in system light mode and in dark mode, with contrast
# measured (REA-22). Clears Read Me's data, shares two texts written for this, then in each
# mode captures List, Trim with a paragraph cut, the Reader before play (sentence controls
# disabled) and while it speaks (the highlight), Settings and Licenses. Each capture is a
# screenshot and a UI dump; scripts/theme-contrast.py measures every text node and the status
# bar from them. SystemUI demo mode fixes the status bar's contents for the run; the phone's
# night mode and demo mode are restored on exit. Captures stay in .claude/scratch/themes/.
set -euo pipefail
cd "$(dirname "$0")/.."
. scripts/lib/device.sh
device_take themes
device_require_unlocked
device_install_release

RAW=.claude/scratch/themes  # gitignored
rm -rf "$RAW"; mkdir -p "$RAW"

night=$(adb shell cmd uimode night | tr -d '\r' | awk '{print $NF}')
demo() { printf '%s\n' "am broadcast -a com.android.systemui.demo $*" | adb shell >/dev/null; }
restore() {
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

FIRST='Light and dark both have to read well.

This second paragraph gets cut in Trim, so its faint style is measured too.

The last paragraph is plain text again, long enough to wrap onto a second line on the phone.'
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
scroll_to_desc() {
  for _ in $(seq 6); do
    ui | grep -qE "content-desc=\"$1\"" && return 0
    adb shell input swipe 670 2400 670 900 300
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
  device_has "$(ui)" 'content-desc="paragraph 2 cut"' || tap_node content-desc 'paragraph 2'
  wait_for 'content-desc="paragraph 2 cut"'
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
  wait_for 'content-desc="voice [^"]+"'
  shot "$mode-5-settings"
  scroll_to_desc 'licenses'
  tap_node content-desc 'licenses'
  wait_for 'text="(MIT|Apache-2.0|ISC|BSD-3-Clause)"'
  shot "$mode-6-licenses"
  adb shell input keyevent KEYCODE_BACK
  adb shell input keyevent KEYCODE_BACK
done

if adb logcat -d | device_crash_seen; then echo "FAIL: a crash in the log"; exit 1; fi
python3 scripts/theme-contrast.py "$RAW" && echo "device:themes PASS" || { echo "device:themes FAIL"; exit 1; }
