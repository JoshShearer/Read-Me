#!/usr/bin/env bash
# npm run device:bridge - R-M12 on the phone, as the plugin would use it. Clears Read Me's data,
# turns the bridge on in Settings, reads the pairing token off the screen (never through adb
# shell argv or a file on shared storage), then over adb forward: /health, /synthesize at 1.0
# and 2.0 (the 2.0 WAV must be shorter: the engine applied the rate once), 401 without the
# token, the hostile-input set, a silent client, 503 while Read Me plays and the bridge after
# playback ends. With Obsidian installed, calls the bridge from inside its WebView with Read Me
# in the background. Turns the bridge off and checks it is gone. Checks logcat for the token
# and the test text.
set -euo pipefail
cd "$(dirname "$0")/.."
. scripts/lib/device.sh
PORT=8787
HDR=$(mktemp)          # the Authorization header lives in a 0600 file, not in curl's argv
chmod 600 "$HDR"
cleanup() {
  rm -f "$HDR"
  adb forward --remove tcp:$PORT >/dev/null 2>&1 || true
  adb shell rm -f /sdcard/readme-ui.xml >/dev/null 2>&1 || true
}
DEVICE_ON_EXIT=cleanup
device_take interactive
device_require_unlocked
device_install_release

adb shell pm clear "$PKG" >/dev/null
# Settings asks for this when the bridge is turned on (Android 13+); granted here so no dialog.
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS
adb logcat -c
fail=0
check() { if eval "$2"; then echo "ok: $1"; else echo "FAIL: $1"; fail=1; fi; }

# The screen as XML on stdout, without a file on /sdcard (the token is on screen).
ui() { adb exec-out uiautomator dump /dev/tty 2>/dev/null | sed 's/UI hierchary dumped to.*//' || true; }
tap_desc() {
  local b=""
  for _ in $(seq 10); do
    b=$(ui | grep -oE "content-desc=\"$1\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" | head -1 \
      | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' | grep -oE '[0-9]+' | tr '\n' ' ' || true)
    [ -n "$b" ] && break
    sleep 1
  done
  [ -n "$b" ] || { echo "FAIL: nothing on screen has content-desc $1"; exit 1; }
  set -- $b
  adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
}
on_screen() { for _ in $(seq 15); do ui | grep -qE "$1" && return 0; sleep 1; done; return 1; }
logs() { adb logcat -d -s ReadMe:I; }
wait_log() { for _ in $(seq "$2"); do logs | grep -qE "$1" && return 0; sleep 1; done; return 1; }

# A ten-sentence text to play during the contention check.
TEXT=""
for w in one two three four five six seven eight nine ten; do TEXT+="Bridge test $w is spoken now. "; done
printf '%s\n' "am start -W -n $PKG/.ShareActivity -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT $(printf '%q' "$TEXT")" \
  | adb shell >/dev/null
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null

echo "== Settings: turn the bridge on"
tap_desc settings
tap_desc 'bridge on'
check "Settings says the bridge is on" "on_screen 'On at 127\.0\.0\.1:$PORT'"
TOK=$(ui | grep -oE 'text="[0-9a-f]{32}"' | head -1 | grep -oE '[0-9a-f]{32}' || true)
[ -n "$TOK" ] || { echo "FAIL: no pairing token on screen"; exit 1; }
printf 'Authorization: Bearer %s\n' "$TOK" > "$HDR"
check "the notification says the bridge is on" \
  "adb shell dumpsys notification --noredact | grep -q 'Obsidian bridge on'"

adb forward tcp:$PORT tcp:$PORT >/dev/null
B="http://127.0.0.1:$PORT"
code() { curl -s -o /dev/null -w '%{http_code}' --max-time 30 "$@"; }

echo "== contract"
check "/health 200 ok, version 1, not busy" \
  "curl -s --max-time 10 $B/health | grep -q '\"ok\":true,\"version\":1,.*\"busy\":false'"
W1=$(mktemp); W2=$(mktemp)
H1=$(printf 'Hello from the bridge test at rate one.' | curl -s -D - -o "$W1" --max-time 60 -X POST -H @"$HDR" \
  -H 'Content-Type: text/plain' -H 'Origin: http://localhost' --data-binary @- "$B/synthesize")
check "/synthesize 200 audio/wav, X-Rate 1.0, CORS" \
  "grep -q '^HTTP/1.1 200' <<<\"\$H1\" && grep -qi '^content-type: audio/wav' <<<\"\$H1\" && grep -qi '^x-rate: 1.0' <<<\"\$H1\" && grep -qi '^access-control-allow-origin: http://localhost' <<<\"\$H1\""
check "the body is a RIFF WAV" "head -c 4 '$W1' | grep -q RIFF"
H2=$(printf 'Hello from the bridge test at rate one.' | curl -s -D - -o "$W2" --max-time 60 -X POST -H @"$HDR" \
  -H 'Content-Type: text/plain' --data-binary @- "$B/synthesize?rate=2.0")
check "rate=2.0 reports X-Rate 2.0" "grep -qi '^x-rate: 2.0' <<<\"\$H2\""
dur() { python3 -c 'import sys,wave; w=wave.open(sys.argv[1]); print(w.getnframes()/w.getframerate())' "$1"; }
D1=$(dur "$W1"); D2=$(dur "$W2")
echo "durations: rate 1.0 ${D1}s, rate 2.0 ${D2}s"
check "the engine applied 2.0 once (duration ratio 0.35 to 0.75)" \
  "python3 -c 'import sys; r=float(sys.argv[2])/float(sys.argv[1]); sys.exit(0 if 0.35<=r<=0.75 else 1)' $D1 $D2"
rm -f "$W1" "$W2"

echo "== hostile input"
check "no token: 401" "[ \"\$(printf x | code -X POST --data-binary @- $B/synthesize)\" = 401 ]"
check "no token with a 64 KiB body: 401, readable" \
  "[ \"\$(head -c 65536 /dev/zero | tr '\\0' a | code -X POST --data-binary @- $B/synthesize)\" = 401 ]"
check "Content-Length abc: 400" "[ \"\$(code -X POST -H @$HDR -H 'Content-Length: abc' $B/synthesize)\" = 400 ]"
check "body over 64 KiB: 413" \
  "[ \"\$(head -c 65537 /dev/zero | tr '\\0' a | code -X POST -H @$HDR --data-binary @- $B/synthesize)\" = 413 ]"
check "a 20 KB header: 431" "[ \"\$(code -H \"X-Pad: \$(head -c 20000 /dev/zero | tr '\\0' a)\" $B/health)\" = 431 ]"
check "other origin: no CORS" "! curl -s -D - -o /dev/null -H 'Origin: http://evil.example' $B/health | grep -qi access-control"
exec 3<>/dev/tcp/127.0.0.1/$PORT   # a silent client
check "a silent client does not block /health" "[ \"\$(code --max-time 3 $B/health)\" = 200 ]"
exec 3<&- 3>&-

echo "== contention (ADR 0004)"
adb shell input keyevent KEYCODE_BACK
tap_desc 'Bridge test one[^"]*'
tap_desc 'trim done'
tap_desc play
wait_log 'playback start item=1 ' 20 || { echo "FAIL: playback did not start"; fail=1; }
sleep 2
R=$(printf 'Busy test.' | curl -s -w ' %{http_code}' --max-time 10 -X POST -H @"$HDR" --data-binary @- "$B/synthesize")
check "/synthesize while playing: 503 busy playback" "[ \"\$R\" = '{\"error\":\"busy\",\"reason\":\"playback\"} 503' ]"
check "/health says busy" "curl -s $B/health | grep -q '\"busy\":true'"

if adb shell pm path md.obsidian >/dev/null 2>&1; then
  echo "== Obsidian, Read Me in the background, while it plays"
  adb shell am start -n md.obsidian/.MainActivity >/dev/null; sleep 8
  P=$(printf '%s' "$TOK" | node scripts/obsidian-cdp-bridge.mjs playing); echo "$P"
  check "WebView: 64 KiB POST while playing reads 503 busy" "grep -q '\"bigBusy\":\"503 ' <<<\"\$P\""
fi

echo "== playback ends; the bridge stays"
wait_log 'playback finished item=1' 90 || { echo "FAIL: playback did not finish"; fail=1; }
sleep 2
check "/health 200 and not busy after playback" "curl -s --max-time 5 $B/health | grep -q '\"busy\":false'"
check "the notification still says the bridge is on" \
  "adb shell dumpsys notification --noredact | grep -q 'Obsidian bridge on'"

if adb shell pm path md.obsidian >/dev/null 2>&1; then
  echo "== Obsidian, Read Me in the background, idle"
  adb shell am start -n md.obsidian/.MainActivity >/dev/null; sleep 5
  O=$(printf '%s' "$TOK" | node scripts/obsidian-cdp-bridge.mjs idle); echo "$O"
  check "WebView fetch /health 200" "grep -q '\"health\":\"200 busy=false\"' <<<\"\$O\""
  check "WebView CapacitorHttp /synthesize 200 at 1.0" "grep -q '\"capSynth\":\"200 rate=1.0\"' <<<\"\$O\""
  check "WebView fetch /synthesize 200 at 1.0" "grep -q '\"fetchSynth\":\"200 rate=1.0' <<<\"\$O\""
  check "WebView 64 KiB POST without token reads 401" "grep -q '\"bigNoToken\":401' <<<\"\$O\""
  OBSIDIAN=ran
else
  echo "SKIP: Obsidian (md.obsidian) is not installed; the WebView half did not run"
  OBSIDIAN=skipped
fi

echo "== Settings: turn the bridge off"
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
# The Reader is still open: back to the List, then Settings.
adb shell input keyevent KEYCODE_BACK
tap_desc settings
tap_desc 'bridge off'
sleep 2
check "the bridge is gone" "[ \"\$(code --max-time 3 $B/health)\" = 000 ]"

echo "== privacy"
check "the token is not in logcat" "[ \"\$(adb logcat -d | grep -c \"$TOK\" || true)\" = 0 ]"
check "the test text is not in logcat" "! adb logcat -d | grep -q 'Bridge test\\|Hello from the bridge'"
check "no crash" "! adb logcat -d | device_crash_seen"
logs | grep -E 'bridge ' | sed -E 's/.*(bridge .*)/\1/' | sort | uniq -c | sort -rn | head -20

if [ "$fail" = 0 ]; then echo "device:bridge PASS (Obsidian: $OBSIDIAN)"; else echo "device:bridge FAIL"; exit 1; fi
