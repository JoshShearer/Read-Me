#!/usr/bin/env bash
# npm run device:bridge - R-M12 on the phone, as the plugin would use it. Clears Read Me's data,
# turns the bridge on in Settings, reads the pairing token off the screen (never through adb
# shell argv or a file on shared storage), then over adb forward: /health, /synthesize at 1.0
# and 2.0 (the 2.0 WAV must be shorter: the engine applied the rate once), 401 without the
# token, the hostile-input set, a silent client, 503 while Read Me plays and the bridge after
# playback ends. With Obsidian installed, calls the bridge from inside its WebView with Read Me
# in the background. REA-60: revokes the notification permission, reopens Read Me and checks the
# bridge stays off (nothing listens, the refusal is logged, Settings says why), then grants it back
# and checks the saved setting brings the bridge back. Turns the bridge off and checks it is gone.
# Checks logcat for the token and the test text.
#
# REA-44: the while-playing WebView check needs playback to outlast Obsidian's start. The test
# text is 40 sentences (about 4x the old margin; a fast enough engine or a slow enough Obsidian
# start can still beat it), and the CDP call reports /health's busy flag: busy=false there is a
# failed precondition (timing), reported apart from a bridge fault. Known gap: /health and the
# POST are two fetches, so playback ending between them still reads as a bridge fault.
# Runs on the phone's default engine; READ_ME_ENGINE=<package> pins that engine instead (it must
# be installed for the current user) and puts the default back on every exit.
set -euo pipefail
cd "$(dirname "$0")/.."
. scripts/lib/device.sh
PORT=8787
HDR=""                 # made after the source-only guard, so a sourced test run leaves no file
prev_engine=""
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
notify_revoked=0      # REA-60's section revoked POST_NOTIFICATIONS and has not granted it back
cleanup() {
  restore_engine
  # Plan review F6: a run stopped inside the revoked-permission section gives the permission back.
  [ "$notify_revoked" = 0 ] || adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
  [ -z "$HDR" ] || rm -f "$HDR"
  adb forward --remove tcp:$PORT >/dev/null 2>&1 || true
  adb shell rm -f /sdcard/readme-ui.xml >/dev/null 2>&1 || true
}
DEVICE_ON_EXIT=cleanup
fail=0      # a bridge, privacy or harness failure
PRECOND=0   # the while-playing WebView check could not be judged: playback had already ended
check() { if eval "$2"; then echo "ok: $1"; else echo "FAIL: $1"; fail=1; fi; }

# The Obsidian WebView half of the contention check. $TOK is the pairing token (stdin to node,
# never argv). /health's busy flag over CDP right before the POST says whether Read Me was still
# playing: busy=false means the run was too slow, which is not a bridge fault and not a pass.
# If the CDP call itself failed (Obsidian not running, no debuggable page, a CDP timeout), the
# bridge was never asked, so that is reported as an Obsidian/CDP failure, not as a bridge fault.
obsidian_playing_check() {
  local P rc=0
  P=$(printf '%s' "$TOK" | node scripts/obsidian-cdp-bridge.mjs playing) || rc=$?
  echo "$P"
  if [ "$rc" != 0 ] || ! grep -q '"health":' <<<"$P"; then
    echo "FAIL (Obsidian/CDP): the WebView call did not complete (node exit $rc), not a bridge fault; the 64 KiB POST was not judged"
    fail=1
  elif grep -q '"health":"200 busy=false"' <<<"$P"; then
    echo "FAIL (precondition, timing): playback ended before the WebView check, not a bridge fault; the 64 KiB POST was not judged"
    PRECOND=1
  else
    check "WebView: 64 KiB POST while playing reads 503 busy" "grep -q '\"bigBusy\":\"503 ' <<<\"\$P\""
  fi
}
# The closing line. A failed precondition is never a pass; it is called a timing rerun only when
# nothing else failed, so a real bridge or privacy failure is never summarised as a flake.
verdict() {
  if [ "$fail" = 0 ] && [ "$PRECOND" = 0 ]; then echo "device:bridge PASS (Obsidian: ${OBSIDIAN:-unknown})"; return 0
  elif [ "$fail" = 0 ]; then echo "device:bridge FAIL (precondition: playback ended before the WebView check; rerun)"
  elif [ "$PRECOND" = 1 ]; then echo "device:bridge FAIL (checks above failed; also the precondition: playback ended before the WebView check)"
  else echo "device:bridge FAIL"; fi
  return 1
}
[ -n "${DEVICE_BRIDGE_SOURCE_ONLY:-}" ] && return 0
HDR=$(mktemp)          # the Authorization header lives in a 0600 file, not in curl's argv
chmod 600 "$HDR"

device_take interactive
device_require_unlocked
prev_engine=$(adb shell settings get secure tts_default_synth | tr -d '\r')
if [ -n "${READ_ME_ENGINE:-}" ]; then
  engine=$(device_pick_engine) || { echo "FAIL: $READ_ME_ENGINE is not installed for this user"; exit 1; }
  engine_pinned=1
  adb shell settings put secure tts_default_synth "$engine"
fi
echo "engine: $(adb shell settings get secure tts_default_synth | tr -d '\r')"
device_install_release

device_clear_app
adb logcat -c

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
# Settings scrolls: once the voices load, the bridge row can sit below the fold.
scroll_to_desc() {
  for _ in $(seq 6); do
    grep -qE "content-desc=\"$1\"" <<<"$(ui)" && return 0
    adb shell input swipe 670 2400 670 900 300
    sleep 1
  done
}
tap_text() {
  local b=""
  for _ in $(seq 10); do
    b=$(ui | grep -oE "text=\"$1\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" | head -1 \
      | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' | grep -oE '[0-9]+' | tr '\n' ' ' || true)
    [ -n "$b" ] && break
    sleep 1
  done
  [ -n "$b" ] || { echo "FAIL: nothing on screen has text $1"; exit 1; }
  set -- $b
  adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
}
on_screen() { for _ in $(seq 15); do grep -qE "$1" <<<"$(ui)" && return 0; sleep 1; done; return 1; }
# REA-28: the bridge is a switch labelled "Use the bridge"; its state is the node's checked=.
BRIDGE='Use the bridge'
bridge_is() { on_screen "content-desc=\"$BRIDGE\"[^>]*checked=\"$1\""; }
logs() { adb logcat -d -s ReadMe:I; }
wait_log() { for _ in $(seq "$2"); do grep -qE "$1" <<<"$(logs)" && return 0; sleep 1; done; return 1; }

# A 40-sentence text to play during the contention check: ten sentences at 2.0x lasted 8.6 s on
# Marmalade, shorter than Obsidian's start (REA-44). Synthetic, no owner content.
TEXT="Bridge test one is spoken now. "
for i in $(seq 2 40); do TEXT+="Bridge test $i is spoken now. "; done
printf '%s\n' "am start -W -n $PKG/.ShareActivity -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT $(printf '%q' "$TEXT")" \
  | timeout 30 adb shell >/dev/null || { echo "FAIL: the share did not return within 30 s"; exit 1; }
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null

echo "== Settings: turn the bridge on"
tap_desc settings
# R-M12: no notification permission, no bridge. Refuse once, then allow (Android 13+ dialog).
scroll_to_desc "$BRIDGE"
check "the bridge switch starts off" "bridge_is false"
tap_desc "$BRIDGE"
tap_text 'Don.t allow'
check "refused: Settings explains and the bridge stays off" \
  "on_screen 'Read Me needs to show a notification while the bridge is on' && bridge_is false"
adb forward tcp:$PORT tcp:$PORT >/dev/null
check "refused: nothing listens on the port" \
  "[ \"\$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 http://127.0.0.1:$PORT/health)\" = 000 ]"
tap_desc "$BRIDGE"
tap_text 'Allow'
check "Settings says the bridge is on" "on_screen 'On at 127\.0\.0\.1:$PORT' && bridge_is true"
TOK=$(ui | grep -oE 'text="[0-9a-f]{32}"' | head -1 | grep -oE '[0-9a-f]{32}' || true)
[ -n "$TOK" ] || { echo "FAIL: no pairing token on screen"; exit 1; }
printf 'Authorization: Bearer %s\n' "$TOK" > "$HDR"
check "the notification says the bridge is on" \
  "grep -q 'Obsidian bridge on' <<<\"\$(adb shell dumpsys notification --noredact)\""

adb forward tcp:$PORT tcp:$PORT >/dev/null
B="http://127.0.0.1:$PORT"
code() { curl -s -o /dev/null -w '%{http_code}' --max-time 30 "$@"; }

echo "== contract"
check "/health 200 ok, version 1, not busy" \
  "grep -q '\"ok\":true,\"version\":1,.*\"busy\":false' <<<\"\$(curl -s --max-time 10 $B/health)\""
W1=$(mktemp); W2=$(mktemp)
H1=$(printf 'Hello from the bridge test at rate one.' | curl -s -D - -o "$W1" --max-time 60 -X POST -H @"$HDR" \
  -H 'Content-Type: text/plain' -H 'Origin: http://localhost' --data-binary @- "$B/synthesize")
check "/synthesize 200 audio/wav, X-Rate 1.0, CORS" \
  "grep -q '^HTTP/1.1 200' <<<\"\$H1\" && grep -qi '^content-type: audio/wav' <<<\"\$H1\" && grep -qi '^x-rate: 1.0' <<<\"\$H1\" && grep -qi '^access-control-allow-origin: http://localhost' <<<\"\$H1\""
check "the body is a RIFF WAV" "grep -q RIFF <<<\"\$(head -c 4 '$W1')\""
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
check "other origin: no CORS" "! grep -qi access-control <<<\"\$(curl -s -D - -o /dev/null -H 'Origin: http://evil.example' $B/health)\""
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
check "/health says busy" "grep -q '\"busy\":true' <<<\"\$(curl -s $B/health)\""

if adb shell pm path md.obsidian >/dev/null 2>&1; then
  echo "== Obsidian, Read Me in the background, while it plays"
  adb shell am start -n md.obsidian/.MainActivity >/dev/null; sleep 8
  obsidian_playing_check
fi

echo "== playback ends; the bridge stays"
wait_log 'playback finished item=1' 180 || { echo "FAIL: playback did not finish"; fail=1; }
sleep 2
check "/health 200 and not busy after playback" "grep -q '\"busy\":false' <<<\"\$(curl -s --max-time 5 $B/health)\""
check "the notification still says the bridge is on" \
  "grep -q 'Obsidian bridge on' <<<\"\$(adb shell dumpsys notification --noredact)\""

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

echo "== R-M12: notifications revoked, the app reopened (REA-60)"
# The bridge is on in Settings. A revoke killed Read Me on the reference phone (2026-10-21); the
# next open must refuse the restart while the notification cannot show, and keep the setting.
notify_revoked=1
adb shell pm revoke "$PKG" android.permission.POST_NOTIFICATIONS
sleep 2
adb shell am force-stop "$PKG"
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
sleep 3
adb forward tcp:$PORT tcp:$PORT >/dev/null
check "revoked: nothing listens on the port after the reopen" "[ \"\$(code --max-time 3 $B/health)\" = 000 ]"
check "revoked: the refusal is logged" "wait_log 'bridge blocked: notifications off' 10"
check "revoked: no bridge notification" \
  "! grep -q 'Obsidian bridge on' <<<\"\$(adb shell dumpsys notification --noredact)\""
# A fresh process opens on the List.
tap_desc settings
scroll_to_desc "$BRIDGE"
check "revoked: Settings shows the bridge off and why" \
  "bridge_is false && on_screen 'Off: notifications are off for Read Me' && on_screen 'the bridge comes back on'"
check "the section says the bridge can be turned off from its notification or here" \
  "on_screen 'turn the bridge off from that notification or here'"
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS
notify_revoked=0
adb shell am force-stop "$PKG"
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
sleep 3
tap_desc settings
scroll_to_desc "$BRIDGE"
check "granted again: the saved setting brings the bridge back" "on_screen 'On at 127\.0\.0\.1:$PORT' && bridge_is true"
adb forward tcp:$PORT tcp:$PORT >/dev/null
check "granted again: /health 200" "[ \"\$(code --max-time 5 $B/health)\" = 200 ]"
check "granted again: the notification says the bridge is on" \
  "grep -q 'Obsidian bridge on' <<<\"\$(adb shell dumpsys notification --noredact)\""

echo "== Settings: turn the bridge off"
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
# Settings is open (REA-60's section ends there): back to the List, then Settings.
adb shell input keyevent KEYCODE_BACK
tap_desc settings
scroll_to_desc "$BRIDGE"
check "the bridge switch is still on" "bridge_is true"
tap_desc "$BRIDGE"
sleep 2
check "the bridge switch is off" "bridge_is false"
check "the bridge is gone" "[ \"\$(code --max-time 3 $B/health)\" = 000 ]"

echo "== privacy"
check "the token is not in logcat" "[ \"\$(adb logcat -d | grep -c \"$TOK\" || true)\" = 0 ]"
check "the test text is not in logcat" "! grep -q 'Bridge test\\|Hello from the bridge' <<<\"\$(adb logcat -d)\""
check "no crash" "! adb logcat -d | device_crash_seen"
logs | grep -E 'bridge ' | sed -E 's/.*(bridge .*)/\1/' | sort | uniq -c | sort -rn | head -20

verdict || exit 1
