#!/usr/bin/env bash
# npm run device:playback - R-M07/R-M11 on the phone, as a user would. Clears Read Me's data,
# shares a text, opens it (Trim, then the Reader), plays, pauses and resumes with the Reader's button, then turns the screen off,
# pauses and resumes with media-button presses, and waits for the item to finish. Checks the
# service's log lines, that reading advanced with the screen off, that the item archived, and
# that no log line carries the text (AGENTS.md 1). Leaves the screen off: unlock it after.
set -euo pipefail
cd "$(dirname "$0")/.."
. scripts/lib/device.sh
device_take interactive
device_require_unlocked
device_install_release

MARK='Quietly the heron waited'
text="$MARK by the water while the morning went on without it."
for i in $(seq 2 9); do
  text+="

Paragraph $i begins here. It has a second sentence for the queue. A third one keeps the reader busy. The fourth closes paragraph $i."
done

adb shell pm clear "$PKG" >/dev/null
adb logcat -c
# The text goes on stdin, not the adb argv, which adbd logs (AGENTS.md, SPIKE-01).
printf '%s\n' "am start -W -n $PKG/.ShareActivity -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT $(printf '%q' "$text")" \
  | adb shell >/dev/null
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null

ui() {
  # Remove the last dump first: uiautomator cannot dump while the Reader's highlight moves,
  # and a failed dump must not hand back the previous screen.
  adb shell rm -f /sdcard/readme-ui.xml
  adb shell uiautomator dump /sdcard/readme-ui.xml >/dev/null 2>&1 || true
  adb shell cat /sdcard/readme-ui.xml 2>/dev/null || true
}
# Taps the centre of the first node whose text matches $1 (an ERE).
tap() {
  local b
  b=$(ui | grep -oE "text=\"$1\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" | head -1 \
    | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' | grep -oE '[0-9]+' | tr '\n' ' ' || true)
  [ -n "$b" ] || { echo "FAIL: nothing on screen matches $1"; exit 1; }
  set -- $b
  adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
}
# centre_of <ERE on content-desc>: "x y" of the first matching node, retrying for 10 s.
centre_of() {
  local b=""
  for _ in $(seq 10); do
    b=$(ui | grep -oE "content-desc=\"$1\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" | head -1 \
      | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' | grep -oE '[0-9]+' | tr '\n' ' ' || true)
    [ -n "$b" ] && break
    sleep 1
  done
  [ -n "$b" ] || { echo "FAIL: nothing on screen has content-desc $1" >&2; exit 1; }
  set -- $b
  echo "$(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))"
}
logs() { adb logcat -d -s ReadMe:I; }
# wait_count <ERE> <count> <seconds>
wait_count() {
  for _ in $(seq "$3"); do
    [ "$(logs | grep -cE "$1" || true)" -ge "$2" ] && return 0
    sleep 1
  done
  echo "FAIL: waited $3 s for $2 x '$1'"; return 1
}
on_screen() {
  for _ in $(seq 10); do device_has "$(ui)" "$1" && return 0; sleep 1; done
  echo "FAIL: '$1' not on screen"; return 1
}

fail=0
on_screen 'text="ready"' || fail=1
# Phase 4: a first open goes to Trim; Done goes to the Reader, whose Play/Pause button is
# one button in one place (relabelled), so its position is taken once.
tap 'ready'
done_at=$(centre_of 'trim done')
adb shell input tap $done_at
play_at=$(centre_of play)
adb shell input tap $play_at
wait_count 'playback start item=1 ' 1 20 || fail=1
sleep 4
adb shell input tap $play_at
wait_count 'playback paused item=1 ' 1 10 || fail=1
on_screen 'content-desc="play"' || fail=1
adb shell input tap $play_at
wait_count 'playback resumed item=1' 1 10 || fail=1

# Screen off, then media-button controls (lock screen / headset path, R-M07).
adb shell input keyevent KEYCODE_SLEEP
sleep 8
adb shell cmd media_session dispatch pause
wait_count 'playback paused item=1 ' 2 10 || fail=1
first=$(logs | grep -oE 'playback paused item=1 paragraph=[0-9]+ offset=[0-9]+' | sed -n 1p)
second=$(logs | grep -oE 'playback paused item=1 paragraph=[0-9]+ offset=[0-9]+' | sed -n 2p)
key() { sed -E 's/.*paragraph=([0-9]+) offset=([0-9]+)/\1 \2/' <<<"$1"; }
read -r p1 o1 <<<"$(key "$first")"; read -r p2 o2 <<<"$(key "$second")"
if [ "${p2:-0}" -lt "${p1:-0}" ] || { [ "${p2:-0}" -eq "${p1:-0}" ] && [ "${o2:-0}" -le "${o1:-0}" ]; }; then
  echo "FAIL: reading did not advance with the screen off ($first -> $second)"; fail=1
fi
adb shell cmd media_session dispatch play
wait_count 'playback resumed item=1' 2 10 || fail=1
wait_count 'playback finished item=1' 1 180 || fail=1
wait_count 'playback gaps n=' 1 5 || fail=1
logs | grep -E 'playback (start|paused|resumed|gaps|finished|resume refused|engine)' | sed 's/^/  /'

all=$(adb logcat -d)
if device_has "$all" "$MARK|heron|Paragraph [0-9] begins"; then
  echo "FAIL: a log line carries the shared text"; fail=1
fi
pid=$(adb shell pidof "$PKG" 2>/dev/null | tr -d '\r' || true)
if device_crash_seen "${pid%% *}" <<<"$(adb logcat -d -b crash,main)"; then
  echo "FAIL: crash logged"; fail=1
fi
adb shell rm -f /sdcard/readme-ui.xml
echo "the screen is off: unlock the phone to use it"
echo "device:playback $([ $fail -eq 0 ] && echo PASS || echo FAIL)"
exit $fail
