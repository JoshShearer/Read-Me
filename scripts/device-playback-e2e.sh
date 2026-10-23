#!/usr/bin/env bash
# npm run device:playback - R-M07/R-M11 on the phone, as a user would. Clears Read Me's data,
# shares a text, opens it (Trim, then the Reader), plays, pauses and resumes with the Reader's button, then turns the screen off,
# pauses and resumes with media-button presses, and waits for the item to finish. Checks the
# service's log lines, that reading advanced with the screen off, that the item archived, and
# that no log line carries the text (AGENTS.md 1). Leaves the screen off: unlock it after.
#
# REA-31: after the start, after the Reader's resume and after the media-key resume, Android must
# name Read Me's session as the media-button session (Supertonic's own session took it while the
# engine played the audio). Runs on the phone's default engine; READ_ME_ENGINE=<package> pins
# that engine instead (it must be installed for the current user) and puts the default back on
# every exit.
set -euo pipefail
cd "$(dirname "$0")/.."
. scripts/lib/device.sh
. scripts/lib/audio-hardening.sh
device_take interactive
device_require_unlocked
prev_engine=$(adb shell settings get secure tts_default_synth | tr -d '\r')
engine_pinned=0
restore_engine() {
  [ "$engine_pinned" = 1 ] || return 0
  if [ -z "$prev_engine" ] || [ "$prev_engine" = null ]; then
    adb shell settings delete secure tts_default_synth >/dev/null 2>&1 || true
  else
    adb shell settings put secure tts_default_synth "$prev_engine" >/dev/null 2>&1 || true
  fi
  echo "engine: restored tts_default_synth to $(adb shell settings get secure tts_default_synth | tr -d '\r')"
}
DEVICE_ON_EXIT=restore_engine
if [ -n "${READ_ME_ENGINE:-}" ]; then
  engine=$(device_pick_engine) || { echo "FAIL: $READ_ME_ENGINE is not installed for this user"; exit 1; }
  engine_pinned=1
  adb shell settings put secure tts_default_synth "$engine"
fi
echo "engine: $(adb shell settings get secure tts_default_synth | tr -d '\r')"
device_install_release

MARK='Quietly the heron waited'
text="$MARK by the water while the morning went on without it."
for i in $(seq 2 9); do
  text+="

Paragraph $i begins here. It has a second sentence for the queue. A third one keeps the reader busy. The fourth closes paragraph $i."
done

device_clear_app
# REA-66: no new AudioHardening line for Read Me between here and the finish (no force-stop in between).
audio_hardening_baseline || ah_failed=1
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

fail=${ah_failed:-0}
# The session Android sends media keys to: "Media button session is <package>/<tag>/<id> ...".
button_session() {
  local d
  d=$(adb shell dumpsys media_session)
  grep -m1 -oE 'Media button session is [^ ]+' <<<"$d" | awk '{print $5}' || true
}
expect_button_session() {
  local s
  s=$(button_session)
  case "$s" in
    "$PKG/"*) echo "ok: the media-button session is Read Me's ($1)" ;;
    *) echo "FAIL: the media-button session is ${s:-none}, not Read Me's ($1)"; fail=1 ;;
  esac
}
on_screen 'text="ready"' || fail=1
# Phase 4: a first open goes to Trim; Done goes to the Reader, whose Play/Pause button is
# one button in one place (relabelled), so its position is taken once.
tap 'ready'
done_at=$(centre_of 'trim done')
adb shell input tap $done_at
play_at=$(centre_of play)
adb shell input tap $play_at
wait_count 'playback start item=1 ' 1 20 || fail=1
# REA-31: the first sentence is heard once the engine has written all of it (ADR 0012).
wait_count 'playback first-audio ' 1 20 || fail=1
sleep 3
# Supertonic's session took the buttons back about 0.6 s after Read Me's claim (REA-31).
expect_button_session "after start"
adb shell input tap $play_at
wait_count 'playback paused item=1 ' 1 10 || fail=1
on_screen 'content-desc="play"' || fail=1
adb shell input tap $play_at
wait_count 'playback resumed item=1' 1 10 || fail=1
sleep 3
expect_button_session "after the Reader's resume"

# Screen off, then media-button controls (lock screen / headset path, R-M07).
adb shell input keyevent KEYCODE_SLEEP
sleep 8
adb shell cmd media_session dispatch pause
wait_count 'playback paused item=1 ' 2 10 || fail=1
first=$(logs | grep -oE 'playback paused item=1 paragraph=[0-9]+ offset=[0-9]+' | sed -n 1p || true)
second=$(logs | grep -oE 'playback paused item=1 paragraph=[0-9]+ offset=[0-9]+' | sed -n 2p || true)
key() { sed -E 's/.*paragraph=([0-9]+) offset=([0-9]+)/\1 \2/' <<<"$1"; }
read -r p1 o1 <<<"$(key "$first")"; read -r p2 o2 <<<"$(key "$second")"
if [ "${p2:-0}" -lt "${p1:-0}" ] || { [ "${p2:-0}" -eq "${p1:-0}" ] && [ "${o2:-0}" -le "${o1:-0}" ]; }; then
  echo "FAIL: reading did not advance with the screen off ($first -> $second)"; fail=1
fi
adb shell cmd media_session dispatch play
wait_count 'playback resumed item=1' 2 10 || fail=1
sleep 3
expect_button_session "after the media-key resume"
wait_count 'playback finished item=1' 1 180 || fail=1
wait_count 'playback gaps n=' 1 5 || fail=1
[ -n "${ah_failed:-}" ] || audio_hardening_check || fail=1
logs | grep -E 'playback (start|paused|resumed|gaps|finished|resume refused|engine|first-audio)' | sed 's/^/  /' || true

all=$(adb logcat -d)
if device_has "$all" "$MARK|heron|Paragraph [0-9] begins"; then
  echo "FAIL: a log line carries the shared text"; fail=1
fi
pid=$(adb shell pidof "$PKG" 2>/dev/null | tr -d '\r' || true)
# REA-25: a cold engine (8 s to first audio) must not be taken for a dead one.
if grep -q 'engine lost' <<<"$(logs)"; then echo "FAIL: a stall was declared during normal playback"; fail=1; fi
if device_crash_seen "${pid%% *}" <<<"$(adb logcat -d -b crash,main)"; then
  echo "FAIL: crash logged"; fail=1
fi
adb shell rm -f /sdcard/readme-ui.xml
echo "the screen is off: unlock the phone to use it"
echo "device:playback $([ $fail -eq 0 ] && echo PASS || echo FAIL)"
exit $fail
