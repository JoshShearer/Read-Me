#!/usr/bin/env bash
# npm run device:settings - REA-27 guard: Settings with the bridge on, opened again and again.
# Clears Read Me's data, grants the notification permission, shares one short text, turns the
# bridge on in Settings, then repeats LOOPS times (default 20), alternating two paths:
#   A: List -> Settings.
#   B (the path REA-27 was seen on): open the item, leave Read Me in the background 5 s, call
#      /health over adb forward, bring Read Me back, Back to the List, Settings.
# In Settings each loop scrolls to the bottom and requires every bridge control: the switch on,
# "On at 127.0.0.1:<port>", the port field, the pairing token, Copy and New token. A loop fails
# on a missing node, on "Checking..." still shown after 10 s, on Fabric's "Unable to find
# viewState", or on a crash. A failing loop's logcat (main, system, crash) and its screen dump,
# with the token redacted, go to <primary>/.claude/scratch/REA-27/. Never a screenshot: the
# token is drawn on screen.
# The token is read off the screen into a 0600 header file for curl -H @file; it never goes on an
# adb command line, into a log line or into a saved file (non-negotiable 4), and every loop's
# logcat is checked for it and for the shared text (non-negotiable 1).

# Replaces every 32-hex token in stdin. Saved dumps pass through it.
redact_tokens() { sed -E 's/[0-9a-f]{32}/<token>/g'; }
# The stub-adb test sources the script for redact_tokens alone.
if [ -n "${DEVICE_SETTINGS_SOURCE_ONLY:-}" ]; then return 0; fi

set -euo pipefail
cd "$(dirname "$0")/.."
. scripts/lib/device.sh

PORT=8787
LOOPS=${LOOPS:-20}
OUT="$PRIMARY/.claude/scratch/REA-27"
HDR=$(mktemp)
chmod 600 "$HDR"
cleanup() {
  rm -f "$HDR"
  adb forward --remove tcp:$PORT >/dev/null 2>&1 || true
}
DEVICE_ON_EXIT=cleanup
device_take interactive
device_require_unlocked
device_install_release

ui() { adb exec-out uiautomator dump /dev/tty 2>/dev/null | sed 's/UI hierchary dumped to.*//' || true; }
# centre_of <attr> <ERE> [seconds]: "x y" of the first matching node, or nothing.
centre_of() {
  local b=""
  for _ in $(seq "${3:-15}"); do
    b=$(ui | grep -oE "$1=\"$2\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" | head -1 \
      | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' | grep -oE '[0-9]+' | tr '\n' ' ' || true)
    [ -n "$b" ] && break
    sleep 1
  done
  [ -n "$b" ] || return 1
  set -- $b
  echo "$(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))"
}
tap() { local p; p=$(centre_of "$@") || return 1; adb shell input tap $p; }
on_screen() { for _ in $(seq "${2:-15}"); do ui | grep -qE "$1" && return 0; sleep 1; done; return 1; }
health() { curl -s -o /dev/null -w '%{http_code}' --max-time 5 -H @"$HDR" "http://127.0.0.1:$PORT/health" || true; }

BRIDGE='Use the bridge'
TEXT="Settings loop text one is spoken. Settings loop text two is spoken."
TEXT_RE='Settings loop text'

echo "== setup"
device_clear_app
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS
printf '%s\n' "am start -W -n $PKG/.ShareActivity -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT $(printf '%q' "$TEXT")" \
  | timeout 30 adb shell >/dev/null || { echo "FAIL: the share did not return within 30 s"; exit 1; }
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
on_screen 'content-desc="Settings loop text one[^"]*"' 20 || { echo "FAIL: the shared text is not on the List"; exit 1; }
# The item opens in Trim the first time; take it through once so loop B lands in the Reader.
tap content-desc 'Settings loop text one[^"]*' || { echo "FAIL: cannot open the item"; exit 1; }
tap content-desc 'trim done' || { echo "FAIL: Trim did not show"; exit 1; }
on_screen 'content-desc="play"' || { echo "FAIL: the Reader did not show"; exit 1; }
adb shell input keyevent KEYCODE_BACK
tap content-desc settings || { echo "FAIL: no Settings button"; exit 1; }
for _ in $(seq 6); do
  ui | grep -qE "content-desc=\"$BRIDGE\"" && break
  adb shell input swipe 670 2000 670 900 300; sleep 1
done
tap content-desc "$BRIDGE" || { echo "FAIL: no bridge switch"; exit 1; }
on_screen "On at 127\.0\.0\.1:$PORT" 20 || { echo "FAIL: the bridge did not turn on"; exit 1; }
TOK=$(ui | grep -oE 'text="[0-9a-f]{32}"' | head -1 | grep -oE '[0-9a-f]{32}' || true)
[ -n "$TOK" ] || { echo "FAIL: no pairing token on screen"; exit 1; }
printf 'Authorization: Bearer %s\n' "$TOK" > "$HDR"
adb forward tcp:$PORT tcp:$PORT >/dev/null
[ "$(health)" = 200 ] || { echo "FAIL: /health did not answer 200 with the bridge on"; exit 1; }
adb shell input keyevent KEYCODE_BACK
on_screen 'content-desc="settings"' 10 || { echo "FAIL: Back did not reach the List"; exit 1; }
echo "bridge on, token read (not shown)"

# Settings is on screen: scroll to the bottom, collecting every dump, then check the union.
check_settings() {
  local seen="" d i
  for i in $(seq 8); do
    d=$(ui); seen+="$d"
    device_has "$d" 'content-desc="licenses"' && break
    adb shell input swipe 670 2000 670 900 300; sleep 1
  done
  DUMP=$seen
  local r=""
  device_has "$seen" "content-desc=\"$BRIDGE\"[^>]*checked=\"true\"" || r+="switch missing or off; "
  device_has "$seen" "text=\"On at 127\\.0\\.0\\.1:$PORT\"" || r+="status missing; "
  device_has "$seen" 'content-desc="bridge port"' || r+="port missing; "
  device_has "$seen" 'content-desc="pairing token"' || r+="token missing; "
  device_has "$seen" 'content-desc="copy token"' || r+="copy missing; "
  device_has "$seen" 'content-desc="new token"' || r+="new token missing; "
  device_has "$seen" 'content-desc="licenses"' || r+="never reached Licenses; "
  # "Checking..." may show for a moment while data arrives; only a stuck one counts.
  if device_has "$seen" 'Checking'; then
    sleep 10
    d=$(ui)
    for i in $(seq 3); do
      device_has "$d" 'Checking' || break
      adb shell input swipe 670 900 670 2000 300; sleep 1; d+=$(ui)
    done
    device_has "$d" 'Checking' && r+="Checking... persists; "
  fi
  SETTINGS_RESULT=${r%; }
}

mkdir -p "$OUT"
DUMP=""; SETTINGS_RESULT=""
fail=0; nfail=0; privacy=0
for i in $(seq "$LOOPS"); do
  if [ $((i % 2)) = 1 ]; then v=A; else v=B; fi
  adb logcat -c
  r=""
  if [ $v = B ]; then
    if tap content-desc 'Settings loop text one[^"]*'; then
      on_screen 'content-desc="play"' 10 || r="Reader did not show; "
      adb shell input keyevent KEYCODE_HOME; sleep 5
      [ "$(health)" = 200 ] || r+="/health not 200 in the background; "
      adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
      adb shell input keyevent KEYCODE_BACK
    else
      r="item not on the List; "
    fi
  fi
  if tap content-desc settings 10; then
    check_settings; [ -n "$SETTINGS_RESULT" ] && r+="$SETTINGS_RESULT; "
  else
    r+="no Settings button; "
  fi
  log=$(adb logcat -d -b main,system,crash)
  lost=$(grep -c 'Unable to find viewState' <<<"$log" || true)
  [ "$lost" = 0 ] || r+="Fabric lost a view ($lost); "
  pid=$(adb shell pidof "$PKG" 2>/dev/null | tr -d '\r' || true)
  device_crash_seen "${pid%% *}" <<<"$log" && r+="crash; "
  if grep -qF "$TOK" <<<"$log"; then r+="TOKEN IN LOGCAT; "; privacy=1; fi
  if grep -qE "$TEXT_RE" <<<"$log"; then r+="ITEM TEXT IN LOGCAT; "; privacy=1; fi
  r=${r%; }
  if [ -z "$r" ]; then
    echo "$i ($v): ok"
  else
    echo "$i ($v): $r"
    fail=1; nfail=$((nfail + 1))
    dir="$OUT/$(date +%Y%m%d-%H%M%S)-loop$i"; mkdir -p "$dir"
    # Never save a log that carries the token or the text (non-negotiables 1 and 4).
    if grep -qF "$TOK" <<<"$log" || grep -qE "$TEXT_RE" <<<"$log"; then
      echo "logcat not saved: it carries the token or the item text" > "$dir/logcat.txt"
    else
      printf '%s\n' "$log" > "$dir/logcat.txt"
    fi
    printf '%s\n' "$DUMP" | redact_tokens | sed -E "s/$TEXT_RE[^\"]*/<item>/g" > "$dir/ui.xml"
    echo "   saved $dir"
  fi
  adb shell input keyevent KEYCODE_BACK
  on_screen 'content-desc="settings"' 10 || { adb shell am start -W -n "$PKG/.MainActivity" >/dev/null; }
done

echo "== bridge off"
tap content-desc settings 10 || true
for _ in $(seq 6); do
  ui | grep -qE "content-desc=\"$BRIDGE\"" && break
  adb shell input swipe 670 2000 670 900 300; sleep 1
done
tap content-desc "$BRIDGE" || true
sleep 2
if [ "$(health)" = 000 ]; then echo "ok: the bridge is off"; else echo "FAIL: the bridge still answers"; fail=1; fi
adb shell input keyevent KEYCODE_BACK

echo "device:settings: $nfail of $LOOPS loops failed$([ $privacy = 1 ] && echo ', PRIVACY FAILURE')"
if [ "$fail" = 0 ]; then echo "device:settings PASS"; else echo "device:settings FAIL"; exit 1; fi
