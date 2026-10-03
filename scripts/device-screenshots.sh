#!/usr/bin/env bash
# npm run device:screenshots - the F-Droid phone screenshots, taken from the real app on the
# phone. Clears Read Me's data, shares three demo texts written for this (no third-party
# content), then captures the list, Trim with the newsletter paragraph cut, the Reader while it
# speaks, and Settings. The phone is put in light mode and SystemUI demo mode (fixed clock, full
# battery) for the run, and both are restored on exit. Writes
# fastlane/metadata/android/en-US/images/phoneScreenshots/{1..4}.png.
set -euo pipefail
cd "$(dirname "$0")/.."
. scripts/lib/device.sh
device_take screenshots
device_require_unlocked
device_install_release

OUT=fastlane/metadata/android/en-US/images/phoneScreenshots
RAW=.claude/scratch/screenshots-raw  # gitignored; kept for inspection
rm -rf "$RAW"; mkdir -p "$RAW"
mkdir -p "$OUT"

night=$(adb shell cmd uimode night | tr -d '\r' | awk '{print $NF}')
demo() { printf '%s\n' "am broadcast -a com.android.systemui.demo $*" | adb shell >/dev/null; }
restore() {
  demo -e command exit
  adb shell cmd uimode night "$night" >/dev/null || true
  adb shell rm -f /sdcard/readme-ui.xml
}
DEVICE_ON_EXIT=restore
adb shell cmd uimode night no >/dev/null
adb shell settings put global sysui_demo_allowed 1
demo -e command enter
demo -e command clock -e hhmm 0930
demo -e command battery -e level 100 -e plugged false
demo -e command network -e wifi show -e level 4 -e mobile hide
demo -e command notifications -e visible false

ARTICLE='The quiet hour before work is the best time to listen.

Sign up for our newsletter to get stories like this one every week. No spam, unsubscribe at any time.

A long article that sat unread for a week takes twenty minutes on the walk to the station. Your eyes stay on the road and the words still arrive.

Read Me Offline speaks with the voice already on your phone. Nothing you share is sent anywhere to be turned into speech.

As each sentence is spoken it lights up on the screen, so you can glance down and pick up the thread.'
NOTES='Notes from the garden in early October. The tomatoes are finally done, and the beans have climbed past the top of the fence. Next year the squash goes on the north side, where it can sprawl without shading anything else.'
HISTORY='A short history of the lighthouse keeper'"'"'s log. Every four hours the keeper wrote the wind, the weather and the ships that passed. Most entries are a single line. A few, written on long winter nights, run for pages.'

device_clear_app
adb logcat -c
share() {
  # The text goes on stdin, not the adb argv, which adbd logs (AGENTS.md, SPIKE-01).
  printf '%s\n' "am start -W -n $PKG/.ShareActivity -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT $(printf '%q' "$1")" \
    | adb shell >/dev/null
  sleep 1
}
share "$HISTORY"
share "$NOTES"
share "$ARTICLE"
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null

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
# wait_for <ERE> [seconds]
wait_for() {
  for _ in $(seq "${2:-15}"); do device_has "$(ui)" "$1" && return 0; sleep 1; done
  echo "FAIL: '$1' not on screen (screen at failure: $RAW/fail.png)"
  adb exec-out screencap -p > "$RAW/fail.png"
  exit 1
}
shot() { sleep 1; adb exec-out screencap -p > "$RAW/$1.png"; echo "captured $1"; }

wait_for 'The quiet hour before work'
shot 1
tap_node text 'The quiet hour before work[^"]*'
wait_for 'content-desc="trim done"'
tap_node content-desc 'paragraph 2'
wait_for 'content-desc="paragraph 2 cut"'
shot 2
tap_node content-desc 'trim done'
wait_for 'content-desc="play"'
tap_node content-desc 'play'
# A cold engine has taken 8 s to first audio on the reference device (AGENTS.md). The log, not
# a UI dump, says when speech starts: a dump can take longer than the whole text at 2x.
for _ in $(seq 40); do device_has "$(adb logcat -d -s ReadMe:I)" 'playback start item=' && break; sleep 1; done
sleep 3
shot 3
tap_node content-desc 'pause'
adb shell input keyevent KEYCODE_BACK
tap_node content-desc 'settings'
wait_for 'content-desc="voice"'
shot 4

python3 scripts/tidy-screenshots.py "$RAW" "$OUT"
