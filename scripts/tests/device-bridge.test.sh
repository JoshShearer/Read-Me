#!/usr/bin/env bash
# Tests for device-bridge-e2e.sh (REA-44) against a stub adb and a stub CDP script in a throwaway
# git repo. The while-playing WebView check reports playback that already ended as a failed
# precondition (timing), a CDP failure as an Obsidian/CDP failure, and neither as a bridge fault or
# a pass; a real 503 passes; a wrong answer while busy is a bridge failure; the closing line says
# "precondition" only when nothing else failed. The script itself is run end to end up to its
# install step (no APK, an early exit): READ_ME_ENGINE pins through device_pick_engine and the
# EXIT trap puts the previous default back; unset, no setting is written. Every adb call of every
# run is logged and must never carry the token. Never touches a real device.
set -uo pipefail
# REA-48: never the owner's real unattended unlock against a stub phone.
unset READ_ME_PIN_FILE DEVICE_UNLOCK_STATE
unset READ_ME_ENGINE DEVICE_ON_EXIT
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
mkdir -p "$T/bin" "$T/repo/scripts/lib" "$T/tmp"
# Logs every call to $STUB_CALLS (never truncated: the token check reads all of it) and keeps
# tts_default_synth in $STUB_DIR/current, its writes in $STUB_DIR/writes.
cat > "$T/bin/adb" <<'STUB'
#!/bin/sh
echo "adb $*" >> "$STUB_CALLS"
case "$*" in
  devices) printf 'List of devices attached\nX\tdevice\n\n' ;;
  "shell dumpsys power") echo "  mWakefulness=Awake" ;;
  "shell dumpsys window") echo "    isKeyguardShowing=false" ;;
  "shell am get-current-user") echo 0 ;;
  "shell pm list packages --user 0") printf 'package:app.example.pinned\npackage:app.example.prev\n' ;;
  "shell settings get secure tts_default_synth")
    if [ -f "$STUB_DIR/current" ]; then cat "$STUB_DIR/current"; else echo null; fi ;;
  "shell settings put secure tts_default_synth "*)
    echo "put $6" >> "$STUB_DIR/writes"; echo "$6" > "$STUB_DIR/current" ;;
  "shell settings delete secure tts_default_synth")
    echo delete >> "$STUB_DIR/writes"; rm -f "$STUB_DIR/current" ;;
  *) : ;;
esac
STUB
chmod +x "$T/bin/adb"
# The CDP script's answer comes from $STUB_CDP (exit $STUB_CDP_EXIT); it must be given the token
# on stdin only. It logs its argv, so the token check can show it covered these runs.
cat > "$T/repo/scripts/obsidian-cdp-bridge.mjs" <<'STUB'
import {readFileSync, appendFileSync} from 'node:fs';
appendFileSync(process.env.STUB_CALLS, 'cdp ' + process.argv.slice(2).join(' ') + '\n');
if (process.argv.length !== 3 || process.argv[2] !== 'playing') process.exit(9);
if (!/^[0-9a-f]{32}$/.test(readFileSync(0, 'utf8'))) process.exit(8);
const code = Number(process.env.STUB_CDP_EXIT || 0);
if (code) process.exit(code);
console.log('OBSIDIAN ' + process.env.STUB_CDP);
STUB
TOKEN=0123456789abcdef0123456789abcdef
export PATH="$T/bin:$PATH" STUB_CALLS="$T/calls" STUB_DIR="$T/state" TMPDIR="$T/tmp"
: > "$STUB_CALLS"
cp "$ROOT/scripts/lib/device.sh" "$T/repo/scripts/lib/"
cp "$ROOT/scripts/device-bridge-e2e.sh" "$T/repo/scripts/"
cd "$T/repo" && git init -q && git -c user.email=t@t -c user.name=t commit -q --allow-empty -m init
fail=0
check() { if [ "$2" = "$3" ]; then echo "ok   $1"; else echo "FAIL $1: expected $3, got $2"; fail=1; fi; }
# run <cdp json> <body>: the script's functions loaded, TOK set, body run; prints the output.
run() {
  STUB_CDP=$1 bash -c 'DEVICE_BRIDGE_SOURCE_ONLY=1; . scripts/device-bridge-e2e.sh; TOK='"$TOKEN"'; '"$2" scripts/device-bridge-e2e.sh 2>&1
}
STATE='echo "fail=$fail precond=$PRECOND"'

out=$(run '{"health":"200 busy=false","bigBusy":"413 {}"}' "obsidian_playing_check; $STATE")
check "playback already ended: a timing precondition failure" "$(grep -c 'precondition, timing' <<<"$out")" 1
check "and it is not reported as the bridge check" "$(grep -c 'FAIL: WebView' <<<"$out")" 0
check "and it is not a pass" "$(grep -c 'ok: WebView' <<<"$out")" 0
check "and it is flagged as a precondition, not a bridge failure" "$(grep -o 'fail=[0-9] precond=[0-9]' <<<"$out")" "fail=0 precond=1"

out=$(run '{"health":"200 busy=true","bigBusy":"503 {\"error\":\"busy\"}"}' "obsidian_playing_check; $STATE")
check "busy and 503: the check passes" "$(grep -c 'ok: WebView: 64 KiB POST while playing reads 503 busy' <<<"$out")" 1
check "and nothing fails" "$(grep -o 'fail=[0-9] precond=[0-9]' <<<"$out")" "fail=0 precond=0"

out=$(run '{"health":"200 busy=true","bigBusy":"413 x"}' "obsidian_playing_check; $STATE")
check "busy but not 503: a bridge failure, not a precondition" "$(grep -o 'FAIL: WebView.*\|fail=[0-9] precond=[0-9]' <<<"$out" | tr '\n' '|')" \
  "FAIL: WebView: 64 KiB POST while playing reads 503 busy|fail=1 precond=0|"

out=$(STUB_CDP_EXIT=3 run '' "obsidian_playing_check; $STATE")
check "the CDP call failed: an Obsidian/CDP failure, not a bridge fault" "$(grep -o 'FAIL (Obsidian/CDP)\|FAIL: WebView\|fail=[0-9] precond=[0-9]' <<<"$out" | tr '\n' '|')" \
  "FAIL (Obsidian/CDP)|fail=1 precond=0|"
out=$(run '{"exceptionDetails":"x"}' "obsidian_playing_check; $STATE")
check "CDP answered without a /health result: the same" "$(grep -o 'FAIL (Obsidian/CDP)\|FAIL: WebView\|fail=[0-9] precond=[0-9]' <<<"$out" | tr '\n' '|')" \
  "FAIL (Obsidian/CDP)|fail=1 precond=0|"

# The closing line and exit status for each combination.
verdict_of() { run '' "OBSIDIAN=ran; fail=$1; PRECOND=$2; if verdict; then echo rc=0; else echo rc=1; fi" | tr '\n' '|'; }
check "nothing failed: PASS" "$(verdict_of 0 0)" "device:bridge PASS (Obsidian: ran)|rc=0|"
check "only the precondition: FAIL, rerun" "$(verdict_of 0 1)" \
  "device:bridge FAIL (precondition: playback ended before the WebView check; rerun)|rc=1|"
check "the precondition and a real failure: both named, no rerun" "$(verdict_of 1 1)" \
  "device:bridge FAIL (checks above failed; also the precondition: playback ended before the WebView check)|rc=1|"
check "a real failure alone: FAIL" "$(verdict_of 1 0)" "device:bridge FAIL|rc=1|"
check "the script ends on that verdict" "$(tail -n 1 scripts/device-bridge-e2e.sh)" "verdict || exit 1"
check "sourcing the script leaves no temp file" "$(ls -A "$TMPDIR" | wc -l)" 0

# The text outlasts Obsidian's start: 40 sentences, none carrying owner content.
n=$(grep -oE 'seq 2 [0-9]+' "$ROOT/scripts/device-bridge-e2e.sh" | head -1 | awk '{print $3}')
check "the test text is 40 sentences" "$n" 40
check "the playback-finished wait covers 40 sentences (>= 180 s)" \
  "$(grep -oE "playback finished item=1' [0-9]+" "$ROOT/scripts/device-bridge-e2e.sh" | awk '{ print ($4 >= 180) ? "yes" : "no" }')" yes

# The script end to end, up to its install step (no APK here: an early exit through the EXIT
# trap). full <previous default or empty> [READ_ME_ENGINE]: prints the exit status, the settings
# writes in order, the default left behind and whether the install step was reached.
full() {
  local rc
  rm -rf "$STUB_DIR"; mkdir -p "$STUB_DIR"; : > "$STUB_DIR/writes"
  [ -n "$1" ] && echo "$1" > "$STUB_DIR/current"
  out=$(READ_ME_ENGINE=${2:-} bash scripts/device-bridge-e2e.sh 2>&1); rc=$?
  printf 'rc=%s writes=%s left=%s install=%s lock=%s' "$rc" \
    "$(tr '\n' , < "$STUB_DIR/writes")" "$(cat "$STUB_DIR/current" 2>/dev/null || echo unset)" \
    "$(grep -c 'missing android/app/build/outputs' <<<"$out")" "$([ -d .claude/device.lock ] && echo held || echo free)"
}
check "READ_ME_ENGINE pins it and the exit puts the previous default back" "$(full app.example.prev app.example.pinned)" \
  "rc=1 writes=put app.example.pinned,put app.example.prev, left=app.example.prev install=1 lock=free"
check "a previously unset default is deleted again on exit" "$(full '' app.example.pinned)" \
  "rc=1 writes=put app.example.pinned,delete, left=unset install=1 lock=free"
check "unset READ_ME_ENGINE: no setting is written" "$(full app.example.prev)" \
  "rc=1 writes= left=app.example.prev install=1 lock=free"
check "an engine not installed for the user is refused before any write" "$(full app.example.prev app.example.absent)" \
  "rc=1 writes= left=app.example.prev install=0 lock=free"
check "the exit removed the header file" "$(ls -A "$TMPDIR" | wc -l)" 0

# Across every run above, obsidian_playing_check's and the script's own: the log is real and
# never carries the token on an adb (or node) argv.
check "the call log covers the WebView runs" "$(grep -c '^cdp playing$' "$STUB_CALLS")" 5
check "the call log covers the script's own adb calls" "$([ "$(grep -c '^adb ' "$STUB_CALLS")" -gt 20 ] && echo yes || echo no)" yes
check "the token is never in an adb call" "$(grep -c "$TOKEN" "$STUB_CALLS")" 0
exit $fail
