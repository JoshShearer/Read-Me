#!/usr/bin/env bash
# Tests for device-continuous.sh (REA-41) against a stub adb in a throwaway git repo. It runs on
# the owner's own library, so its safety must hold off the phone too: it never clears data or
# sends a media key, it stops Read Me through its own package, it fails loudly when a non-test
# item was played, and its EXIT trap stops playback and releases the slot on every exit.
# Never touches a real device.
set -uo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
mkdir -p "$T/bin" "$T/repo/scripts/lib"
cat > "$T/bin/adb" <<'STUB'
#!/bin/sh
echo "$*" >> "$STUB_CALLS"
case "$*" in
  devices) printf 'List of devices attached\nX\tdevice\n\n' ;;
  "shell dumpsys power") if [ "${STUB_LOCKED:-0}" = 1 ]; then echo "  mWakefulness=Asleep"; else echo "  mWakefulness=Awake"; fi ;;
  "shell dumpsys window") if [ "${STUB_LOCKED:-0}" = 1 ]; then echo "    isKeyguardShowing=true"; else echo "    isKeyguardShowing=false"; fi ;;
  "logcat -d"*) cat "$STUB_LOG" 2>/dev/null ;;
  *) : ;;
esac
STUB
chmod +x "$T/bin/adb"
export PATH="$T/bin:$PATH" STUB_CALLS="$T/calls" STUB_LOG="$T/log"
S="$ROOT/scripts/device-continuous.sh"
cp "$ROOT/scripts/lib/device.sh" "$T/repo/scripts/lib/"
cp "$S" "$T/repo/scripts/"
cd "$T/repo" && git init -q && git -c user.email=t@t -c user.name=t commit -q --allow-empty -m init
fail=0
check() { if [ "$2" = "$3" ]; then echo "ok   $1"; else echo "FAIL $1: expected $3, got $2"; fail=1; fi; }
# src <code>: runs code with the script's functions loaded, in a fresh bash.
src() { local c=$1; shift; bash -c "DEVICE_CONTINUOUS_SOURCE_ONLY=1; . scripts/device-continuous.sh; $c" "$@"; }

# --- structure: what the script must never do on the owner's phone ---
code=$(grep -vE '^\s*#' "$S")
check "never clears the app's data" "$(grep -cE 'pm clear|device_clear_app' <<<"$code")" 0
check "never sends a media key or a media-session command" \
  "$(grep -cE 'media_session|KEYCODE_MEDIA|dispatch (stop|pause|play)' <<<"$code")" 0
check "never uninstalls or installs without -r" "$(grep -cE 'adb (uninstall|install [^-])' <<<"$code")" 0
check "stops Read Me only by its own package" "$(grep -c 'am force-stop "\$PKG"' <<<"$code")" 1
check "replaces the lib's trap with one that still releases the slot" \
  "$(grep -cE '^trap finish EXIT$' <<<"$code"),$(grep -c '_device_exit' <<<"$code")" "1,1"
check "the trap is set right after the slot is taken" \
  "$(grep -A1 -E '^device_take ' <<<"$code" | tail -1)" "trap finish EXIT"

# --- helpers ---
LOG='I ReadMe  : playback start item=12 sentences=4 from=0 rate=2.0
I ReadMe  : playback finished item=12
I ReadMe  : playback continue from=12 item=11 sentences=6 start=0 ms=3
I ReadMe  : playback handover from=12 item=11 ms=180
I ReadMe  : playback continue from=11 item=10 sentences=151 start=0 ms=9
I ReadMe  : playback finished item=10
I ReadMe  : playback continue from=10 item=4 sentences=2 start=0 ms=2'
check "playback ids are read from every playback line" "$(src "playback_ids \"\$1\"" _ "$LOG" | tr '\n' ' ')" "4 10 11 12 "
check "a non-test id is found" "$(src "foreign_ids \"\$1\" '12 11 10'" _ "$LOG" | tr '\n' ' ')" "4 "
check "no foreign id when all are test ids" "$(src "foreign_ids \"\$1\" '12 11 10 4'" _ "$LOG")" ""
check "sentence windows: T1 151, T2 6, T3 4" \
  "$(src 'count_ok 0 151 && count_ok 1 6 && count_ok 2 4 && echo yes')" yes
check "the windows do not admit each other's counts" \
  "$(src 'count_ok 0 6 || count_ok 1 4 || count_ok 2 6 || count_ok 2 151 || echo none')" none
check "a two-sentence owner item matches no window" "$(src 'count_ok 0 2 || count_ok 1 2 || count_ok 2 2 || echo none')" none
DUMP='<node content-desc="delete Kestrel test item three." bounds="[0,0][1,1]"/><node content-desc="delete Kestrel test item two." bounds="[0,0][1,1]"/><node content-desc="delete Kestrel test item one." bounds="[0,0][1,1]"/><node content-desc="delete Owner thing" bounds="[0,0][1,1]"/>'
check "row titles come top to bottom" "$(src "row_titles \"\$1\" | head -3 | tr '\n' '|'" _ "$DUMP")" \
  "Kestrel test item three.|Kestrel test item two.|Kestrel test item one.|"
check "the body text has no digits and the marker" \
  "$(src 'body_text 3' | grep -cE '[0-9]'),$(src 'body_text 3' | grep -c Kestrel)" "0,1"
check "field reads one number" "$(src "field 'playback continue from=12 item=11 sentences=6 start=0' item")" 11

# --- guard: a non-test id stops Read Me and fails loudly ---
printf '%s\n' "$LOG" > "$STUB_LOG"; : > "$STUB_CALLS"
out=$(src "CLEARED=1; TEST_IDS='12 11 10'; if guard; then r=0; else r=1; fi; echo rc=\$r FAIL=\$FAIL")
check "guard fails on a non-test id" "$(grep -oE 'rc=[0-9]+ FAIL=[0-9]+' <<<"$out")" "rc=1 FAIL=1"
check "and says so loudly" "$(grep -c 'NON-TEST ITEM PLAYED: id(s) 4' <<<"$out")" 1
check "and force-stops Read Me" "$(grep -c 'shell am force-stop io.loopstring.readme' "$STUB_CALLS")" 1

# --- the EXIT trap ---
: > "$STUB_CALLS"; mkdir -p .claude
mkdir .claude/device.lock
STUB_LOCKED=1 UNLOCK_WAIT=1 src "DEVICE_LOCK_OURS=1; trap finish EXIT; PLAYED=1; CLEARED=1; SHARED=1; TEST_IDS='12 11 10'; exit 0" >"$T/out" 2>&1
rc=$?
check "the trap fails a run that exited 0 after a non-test item played" "$rc" 1
check "it stopped playback through Read Me's package" "$(grep -c 'shell am force-stop io.loopstring.readme' "$STUB_CALLS")" 2
check "it says cleanup was not done on a locked phone" "$(grep -c 'CLEANUP NOT DONE' "$T/out")" 1
check "it released the device slot" "$([ -d .claude/device.lock ] && echo held || echo free)" free
check "it never sent a media key" "$(grep -cE 'media_session|KEYCODE_MEDIA' "$STUB_CALLS")" 0

: > "$STUB_LOG"; : > "$STUB_CALLS"; mkdir .claude/device.lock
src "DEVICE_LOCK_OURS=1; trap finish EXIT; PLAYED=1; CLEARED=1; TEST_IDS=''; false" >"$T/out" 2>&1
check "a failure mid-run (set -e) still stops playback" "$?,$(grep -c 'am force-stop' "$STUB_CALLS")" "1,1"
check "and releases the slot" "$([ -d .claude/device.lock ] && echo held || echo free)" free

: > "$STUB_CALLS"; mkdir .claude/device.lock
src "DEVICE_LOCK_OURS=1; trap finish EXIT; PLAYED=1; ENDED=1; CLEARED=1; TEST_IDS='12'; exit 0" >"$T/out" 2>&1
check "a chain that ended by itself is not force-stopped" "$?,$(grep -c 'am force-stop' "$STUB_CALLS")" "0,0"

# --- a whole run that stops at the install step (no APK) ---
rm -rf .claude/device.lock; : > "$STUB_CALLS"; : > "$STUB_LOG"
bash scripts/device-continuous.sh >"$T/out" 2>&1; rc=$?
check "without an APK the run stops before touching the app" "$rc" 1
check "and releases the device lock" "$([ -d .claude/device.lock ] && echo held || echo free)" free
check "and never played, shared or stopped anything" \
  "$(grep -cE 'ShareActivity|force-stop|input tap|logcat -c' "$STUB_CALLS")" 0
check "and never cleared the app" "$(grep -c 'pm clear' "$STUB_CALLS")" 0
exit $fail
