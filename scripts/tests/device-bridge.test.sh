#!/usr/bin/env bash
# Tests for device-bridge-e2e.sh (REA-44) against a stub adb and a stub CDP script in a throwaway
# git repo: the while-playing WebView check reports playback that already ended as a failed
# precondition (timing), not as a bridge fault and not as a pass; a real 503 passes; a wrong
# answer while busy is a bridge failure; READ_ME_ENGINE's restore puts the previous default back.
# Never touches a real device.
set -uo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
mkdir -p "$T/bin" "$T/repo/scripts/lib"
cat > "$T/bin/adb" <<'STUB'
#!/bin/sh
echo "$*" >> "$STUB_CALLS"
case "$*" in
  "shell settings get secure tts_default_synth") echo "${STUB_ENGINE:-null}" ;;
  *) : ;;
esac
STUB
chmod +x "$T/bin/adb"
# The CDP script's answer comes from $STUB_CDP; it must be given the token on stdin only.
cat > "$T/repo/scripts/obsidian-cdp-bridge.mjs" <<'STUB'
import {readFileSync} from 'node:fs';
if (process.argv.length !== 3 || process.argv[2] !== 'playing') process.exit(9);
if (!/^[0-9a-f]{32}$/.test(readFileSync(0, 'utf8'))) process.exit(8);
console.log('OBSIDIAN ' + process.env.STUB_CDP);
STUB
export PATH="$T/bin:$PATH" STUB_CALLS="$T/calls"
cp "$ROOT/scripts/lib/device.sh" "$T/repo/scripts/lib/"
cp "$ROOT/scripts/device-bridge-e2e.sh" "$T/repo/scripts/"
cd "$T/repo" && git init -q && git -c user.email=t@t -c user.name=t commit -q --allow-empty -m init
fail=0
check() { if [ "$2" = "$3" ]; then echo "ok   $1"; else echo "FAIL $1: expected $3, got $2"; fail=1; fi; }
# run <cdp json> <body>: the script's functions loaded, TOK set, body run; prints the output.
run() {
  STUB_CDP=$1 bash -c 'DEVICE_BRIDGE_SOURCE_ONLY=1; . scripts/device-bridge-e2e.sh; TOK=0123456789abcdef0123456789abcdef; '"$2" scripts/device-bridge-e2e.sh 2>&1
}

out=$(run '{"health":"200 busy=false","bigBusy":"413 {}"}' 'obsidian_playing_check; echo "fail=$fail precond=$PRECOND"')
check "playback already ended: a timing precondition failure" "$(grep -c 'precondition, timing' <<<"$out")" 1
check "and it is not reported as the bridge check" "$(grep -c 'FAIL: WebView' <<<"$out")" 0
check "and it is not a pass" "$(grep -c 'ok: WebView' <<<"$out")" 0
check "and it fails the run, flagged as a precondition" "$(grep -o 'fail=[0-9] precond=[0-9]' <<<"$out")" "fail=1 precond=1"

out=$(run '{"health":"200 busy=true","bigBusy":"503 {\"error\":\"busy\"}"}' 'obsidian_playing_check; echo "fail=$fail precond=$PRECOND"')
check "busy and 503: the check passes" "$(grep -c 'ok: WebView: 64 KiB POST while playing reads 503 busy' <<<"$out")" 1
check "and nothing fails" "$(grep -o 'fail=[0-9] precond=[0-9]' <<<"$out")" "fail=0 precond=0"

out=$(run '{"health":"200 busy=true","bigBusy":"413 x"}' 'obsidian_playing_check; echo "fail=$fail precond=$PRECOND"')
check "busy but not 503: a bridge failure, not a precondition" "$(grep -o 'FAIL: WebView.*\|fail=[0-9] precond=[0-9]' <<<"$out" | tr '\n' '|')" \
  "FAIL: WebView: 64 KiB POST while playing reads 503 busy|fail=1 precond=0|"

# The text outlasts Obsidian's start: 40 sentences, none carrying owner content.
n=$(grep -oE 'seq 2 [0-9]+' "$ROOT/scripts/device-bridge-e2e.sh" | head -1 | awk '{print $3}')
check "the test text is 40 sentences" "$n" 40
check "the playback-finished wait covers 40 sentences (>= 180 s)" \
  "$(grep -oE "playback finished item=1' [0-9]+" "$ROOT/scripts/device-bridge-e2e.sh" | awk '{ print ($4 >= 180) ? "yes" : "no" }')" yes

# Engine pin: the previous default comes back on exit; an unpinned run touches nothing.
: > "$STUB_CALLS"
run '' 'engine_pinned=1; prev_engine=app.example.tts; restore_engine' >/dev/null
check "restore puts the previous default engine back" "$(grep -c 'shell settings put secure tts_default_synth app.example.tts' "$STUB_CALLS")" 1
: > "$STUB_CALLS"
run '' 'engine_pinned=1; prev_engine=null; restore_engine' >/dev/null
check "a previously unset default is deleted again" "$(grep -c 'shell settings delete secure tts_default_synth' "$STUB_CALLS")" 1
: > "$STUB_CALLS"
run '' 'restore_engine' >/dev/null
check "an unpinned run changes no setting" "$(grep -c 'settings' "$STUB_CALLS")" 0
check "the script pins only through device_pick_engine" "$(grep -c 'device_pick_engine' "$ROOT/scripts/device-bridge-e2e.sh")" 1
check "the token is never in an adb call" "$(grep -c 0123456789abcdef "$STUB_CALLS")" 0
exit $fail
