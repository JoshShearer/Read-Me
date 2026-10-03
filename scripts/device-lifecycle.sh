#!/usr/bin/env bash
# npm run device:lifecycle - REA-25 on the phone. (1) Pause, Home, wait 75 s (Android removed
# a paused background service at 60 s on the reference device), then a headset Play must
# resume (ADR 0009). (2) Force-stop the TTS engine while reading: playback must rebind it,
# resume, and keep reading. Clears app data. About 3 minutes.
set -euo pipefail
cd "$(dirname "$0")/.."
. scripts/lib/device.sh
device_take interactive
device_require_unlocked
device_install_release

ENGINE=$(adb shell settings get secure tts_default_synth | tr -d '\r')
case "$ENGINE" in
  ''|null) echo "no default TTS engine set (Settings > Text-to-speech); cannot kill it"; exit 1 ;;
esac
text="Quietly the heron waited by the water while the morning went on without it."
for i in $(seq 2 30); do
  text+="

Paragraph $i begins here. It has a second sentence for the queue. A third one keeps the reader busy. The fourth closes paragraph $i."
done

ui() {
  adb shell rm -f /sdcard/readme-ui.xml
  adb shell uiautomator dump /sdcard/readme-ui.xml >/dev/null 2>&1 || true
  adb shell cat /sdcard/readme-ui.xml 2>/dev/null || true
}
centre_of() {
  local b=""
  for _ in $(seq 10); do
    b=$(ui | grep -oE "$1=\"$2\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" | head -1 \
      | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' | grep -oE '[0-9]+' | tr '\n' ' ' || true)
    [ -n "$b" ] && break
    sleep 1
  done
  [ -n "$b" ] || { echo "FAIL: nothing on screen has $1 $2" >&2; exit 1; }
  set -- $b
  echo "$(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))"
}
logs() { adb logcat -d -s ReadMe:I; }
wait_log() {
  for _ in $(seq "$2"); do logs | grep -qE "$1" && return 0; sleep 1; done
  echo "FAIL: no '$1' within $2 s"; return 1
}
session_state() {
  adb shell dumpsys media_session | grep -A14 "package=$PKG" | grep -oE '\{state=[A-Z]+' | head -1 | tr -d '{'
}

fail=0
device_clear_app
adb logcat -c
printf '%s\n' "am start -W -n $PKG/.ShareActivity -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT $(printf '%q' "$text")" \
  | adb shell >/dev/null
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
adb shell input tap $(centre_of text ready)
adb shell input tap $(centre_of content-desc 'trim done')
adb shell input tap $(centre_of content-desc play)
wait_log 'playback start item=1 ' 30 || fail=1
sleep 4

echo "== a paused session survives 75 s in the background (ADR 0009)"
adb shell input tap $(centre_of content-desc pause)
wait_log 'playback paused item=1 ' 10 || fail=1
adb shell input keyevent KEYCODE_HOME
sleep 75
n=$(adb shell dumpsys activity services "$PKG" | grep -cE 'ServiceRecord.*PlaybackService' || true)
if [ "$n" -ge 1 ]; then echo "ok: the service is still there"; else echo "FAIL: the service is gone"; fail=1; fi
# Still there is not enough: a paused service that left the foreground is the 60 s case.
if adb shell dumpsys activity services "$PKG" | grep -q 'isForeground=true'; then
  echo "ok: still in the foreground"
else
  echo "FAIL: no longer in the foreground"; fail=1
fi
adb logcat -c
adb shell input keyevent KEYCODE_MEDIA_PLAY
wait_log 'playback resumed item=1' 10 && echo "ok: headset Play resumed" || fail=1
[ "$(session_state)" = "state=PLAYING" ] && echo "ok: session PLAYING" || { echo "FAIL: session $(session_state)"; fail=1; }

echo "== the TTS engine dies mid-read (REA-18)"
sleep 4
adb logcat -c
adb shell am force-stop "$ENGINE"
wait_log 'playback engine lost; rebinding' 25 && echo "ok: loss noticed" || fail=1
wait_log 'playback engine rebound' 20 && echo "ok: engine rebound" || fail=1
sleep 8
adb logcat -c
adb shell input keyevent KEYCODE_MEDIA_PAUSE
wait_log 'playback gaps n=[1-9]' 10 && echo "ok: sentences spoken after the rebind" || fail=1

echo "== privacy and crashes"
if logs | grep -q 'Quietly the heron\|Paragraph [0-9]* begins'; then echo "FAIL: text in the log"; fail=1; fi
pid=$(adb shell pidof "$PKG" 2>/dev/null | tr -d '\r' || true)
if device_crash_seen "${pid%% *}" <<<"$(adb logcat -d -b crash,main)"; then echo "FAIL: crash logged"; fail=1; fi

if [ "$fail" = 0 ]; then echo "device:lifecycle PASS"; else echo "device:lifecycle FAIL"; exit 1; fi
