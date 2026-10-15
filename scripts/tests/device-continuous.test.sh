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
  # REA-55 fix round 2: STUB_OVERLAY names a file holding how many more window dumps show
  # SystemUI's clipboard overlay over Read Me; while it shows, a tap lands on it (no answer).
  "shell dumpsys window windows") if [ -n "${STUB_OVERLAY:-}" ] && [ "$(cat "$STUB_OVERLAY" 2>/dev/null || echo 0)" -gt 0 ]; then
      printf '  Window #0 Window{1 u0 ClipboardOverlay}:\n    isVisible=true\n'; echo $(($(cat "$STUB_OVERLAY") - 1)) > "$STUB_OVERLAY"; fi
    printf '  Window #1 Window{2 u0 StatusBar}:\n    isVisible=true\n  Window #2 Window{3 u0 io.loopstring.readme/io.loopstring.readme.MainActivity}:\n    isVisible=true\n' ;;
  "shell dumpsys window") if [ "${STUB_LOCKED:-0}" = 1 ]; then echo "    isKeyguardShowing=true"; else echo "    isKeyguardShowing=false"; fi ;;
  # REA-47: STUB_LOGCAT_FAIL names a file; while it exists, one logcat read fails and removes it.
  "logcat -d"*) if [ -n "${STUB_LOGCAT_FAIL:-}" ] && [ -f "$STUB_LOGCAT_FAIL" ]; then rm -f "$STUB_LOGCAT_FAIL"; exit 1; fi
    cat "$STUB_LOG" 2>/dev/null ;;
  # REA-47: a bare `adb shell` gets its command on stdin (the shares); record it so checks see it.
  shell) cat >> "$STUB_CALLS" ;;
  # REA-47: STUB_UI names a file served as the uiautomator dump.
  "shell cat /sdcard/readme-ui.xml") [ -n "${STUB_UI:-}" ] && cat "$STUB_UI" 2>/dev/null ;;
  # REA-55: each tap appends the next line of STUB_TAPQ (the service's answer) to the log.
  "shell input tap"*) if [ -n "${STUB_OVERLAY:-}" ] && [ "$(cat "$STUB_OVERLAY" 2>/dev/null || echo 0)" -gt 0 ]; then :
    elif [ -n "${STUB_TAPQ:-}" ] && [ -s "$STUB_TAPQ" ]; then
      head -1 "$STUB_TAPQ" >> "$STUB_LOG"; sed -i 1d "$STUB_TAPQ"; fi ;;
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
I ReadMe  : playback continue from=12 item=11 sentences=41 start=0 ms=3
I ReadMe  : playback handover from=12 item=11 ms=180
I ReadMe  : playback continue from=11 item=10 sentences=151 start=0 ms=9
I ReadMe  : playback finished item=10
I ReadMe  : playback continue from=10 item=4 sentences=2 start=0 ms=2'
check "playback ids are read from every playback line" "$(src "playback_ids \"\$1\"" _ "$LOG" | tr '\n' ' ')" "4 10 11 12 "
check "a non-test id is found" "$(src "foreign_ids \"\$1\" '12 11 10'" _ "$LOG" | tr '\n' ' ')" "4 "
check "no foreign id when all are test ids" "$(src "foreign_ids \"\$1\" '12 11 10 4'" _ "$LOG")" ""
check "sentence windows: T1 151, T2 41, T3 4" \
  "$(src 'count_ok 0 151 && count_ok 1 41 && count_ok 2 4 && echo yes')" yes
check "the windows do not admit each other's counts" \
  "$(src 'count_ok 0 41 || count_ok 1 4 || count_ok 2 41 || count_ok 2 151 || count_ok 1 151 || echo none')" none
check "T2's window is 40-42 (REA-55: long enough for the Reader checks)" \
  "$(src 'count_ok 1 40 && count_ok 1 42 && echo yes'),$(src 'count_ok 1 39 || count_ok 1 43 || echo no')" "yes,no"
check "a two-sentence owner item matches no window" "$(src 'count_ok 0 2 || count_ok 1 2 || count_ok 2 2 || echo none')" none
DUMP='<node content-desc="delete Kestrel test item three." bounds="[0,0][1,1]"/><node content-desc="delete Kestrel test item two." bounds="[0,0][1,1]"/><node content-desc="delete Kestrel test item one." bounds="[0,0][1,1]"/><node content-desc="delete Owner thing" bounds="[0,0][1,1]"/>'
check "row titles come top to bottom" "$(src "row_titles \"\$1\" | head -3 | tr '\n' '|'" _ "$DUMP")" \
  "Kestrel test item three.|Kestrel test item two.|Kestrel test item one.|"
check "the body text has no digits and the marker" \
  "$(src 'body_text 3' | grep -cE '[0-9]'),$(src 'body_text 3' | grep -c Kestrel)" "0,1"
check "field reads one number" "$(src "field 'playback continue from=12 item=11 sentences=41 start=0' item")" 11

# --- REA-55: the Reader follows the handover ---
RD() { # RD <title> <button> [highlight]: a Reader dump
  printf '<node text="%s" bounds="[0,0][1,1]"/><node content-desc="trim" bounds="[0,0][1,1]"/>%s<node content-desc="%s" bounds="[0,0][1,1]"/>' \
    "$1" "${3:+<node content-desc=\"current paragraph\" bounds=\"[0,0][1,1]\"/>}" "$2"
}
R_T2=$(RD "Kestrel test item two." pause yes)
R_STALE=$(RD "Kestrel test item three." play)
R_BOTH="$(RD "Kestrel test item two." pause yes)"'<node text="Kestrel test item one." bounds="[0,0][1,1]"/>'
check "a followed Reader on T2 (title, highlight, Pause) is accepted" "$(src 'reader_shows "$1" 1 && echo yes' _ "$R_T2")" yes
check "a stale Reader (T3's title, Play, nothing highlighted) is not T2's" "$(src 'reader_shows "$1" 1 || echo no' _ "$R_STALE")" no
check "T2's title and Play but no highlight is not accepted" \
  "$(src 'reader_shows "$1" 1 || echo no' _ "$(RD "Kestrel test item two." play)")" no
check "the List is not a Reader" "$(src 'reader_shows "$1" 1 || echo no' _ "$DUMP")" no
check "a dump with two test titles is not accepted" "$(src 'reader_shows "$1" 1 || echo no' _ "$R_BOTH")" no
check "the FAIL line names what the stale Reader shows (no item text)" "$(src 'reader_state "$1"' _ "$R_STALE")" \
  "title T3, highlighted paragraph no, button Play"
L2='I ReadMe  : playback start item=12 sentences=4 from=0 rate=2.0
I ReadMe  : playback start cancelled item=9
I ReadMe  : playback continue from=12 item=11 sentences=41 start=0 ms=3
I ReadMe  : playback paused item=11 paragraph=3 offset=0'
check "JS starts are counted, a cancelled start is not" "$(src 'js_starts "$1"' _ "$L2")" 1
check "the item being read is the latest continue" "$(src 'reading_now "$1"' _ "$L2")" 11
check "a second JS start is counted and becomes the item being read" \
  "$(src 'js_starts "$1"; reading_now "$1"' _ "$L2
I ReadMe  : playback start item=12 sentences=4 from=0 rate=2.0" | tr '\n' ' ')" "2 12 "

# --- REA-55 fix round 1: the Reader checks themselves, run against the stub ---
# Each case runs the real reader_check / transport_tap / transport_check / live_switch_checks /
# returned_checks with a stub log, a stub dump and the service's answer to each tap.
BTN='<node content-desc="pause" bounds="[10,10][20,20]"/><node content-desc="play" bounds="[30,30][40,40]"/>'
T2_READING='I ReadMe  : playback start item=12 sentences=4 from=0 rate=2.0
I ReadMe  : playback continue from=12 item=11 sentences=41 start=0 ms=3'
# rc <code>: runs code with the script loaded and short waits; prints its output, then rc=<n>.
rc() { READER_WAIT=1 TAP_WAIT=2 src "$1"; echo "rc=$?"; }
setup() { # setup <log> <dump> <tap answers, one per line>
  printf '%s\n' "$1" > "$STUB_LOG"; printf '%s' "$2" > "$T/ui"; printf '%s' "$3" > "$T/tapq"; : > "$STUB_CALLS"
}
export STUB_UI="$T/ui" STUB_TAPQ="$T/tapq"

setup "$T2_READING" "$R_T2$BTN" 'I ReadMe  : playback paused item=11 paragraph=3 offset=0
I ReadMe  : playback resumed item=11
'
out=$(rc 'transport_check 11 "T2 transport"')
check "transport_check passes when Pause and Play act on the item being read" \
  "$(grep -c '^PASS T2 transport' <<<"$out"),$(grep -o 'rc=[0-9]*' <<<"$out"),$(grep -c 'input tap' "$STUB_CALLS")" "1,rc=0,2"

setup "$T2_READING
I ReadMe  : playback continue from=11 item=10 sentences=151 start=0 ms=3" "$R_T2$BTN" ''
out=$(rc 'transport_check 11 "T2 transport"')
check "transport_check on an item that already ended: NOT REACHED, a failure, never PASS, no tap" \
  "$(grep -c '^NOT REACHED T2 transport' <<<"$out"),$(grep -c PASS <<<"$out"),$(grep -o 'rc=[0-9]*' <<<"$out"),$(grep -c 'input tap' "$STUB_CALLS")" "1,0,rc=1,0"

setup "$T2_READING" "$R_T2$BTN" 'I ReadMe  : playback continue from=11 item=10 sentences=151 start=0 ms=3
'
out=$(rc 'transport_tap 11 "(pause|play)" "playback paused item=11 " "T2 transport"')
check "transport_tap whose item ends before its line: NOT REACHED and a failure" \
  "$(grep -c '^NOT REACHED T2 transport' <<<"$out"),$(grep -o 'rc=[0-9]*' <<<"$out")" "1,rc=1"
setup "$T2_READING" "$R_T2$BTN" 'I ReadMe  : playback continue from=11 item=10 sentences=151 start=0 ms=3
'
out=$(rc 'transport_check 11 "T2 transport"')
check "and transport_check then fails without PASS" "$(grep -c PASS <<<"$out"),$(grep -o 'rc=[0-9]*' <<<"$out")" "0,rc=1"

setup "$T2_READING" "$R_T2$BTN" 'I ReadMe  : playback start item=12 sentences=4 from=0 rate=2.0
'
out=$(rc 'transport_tap 11 "(pause|play)" "playback paused item=11 " "T2 transport"; echo went-on')
check "a tap that makes JS start another item stops Read Me and dies at once" \
  "$(grep -c 'started item 12 while item 11' <<<"$out"),$(grep -c 'shell am force-stop io.loopstring.readme' "$STUB_CALLS"),$(grep -c went-on <<<"$out"),$(grep -o 'rc=[0-9]*' <<<"$out")" "1,1,0,rc=1"
setup 'I ReadMe  : playback continue from=12 item=11 sentences=41 start=0 ms=3' "$R_T2$BTN" 'I ReadMe  : playback start item=12 sentences=4 from=0 rate=2.0
'
out=$(rc 'transport_tap 11 "(pause|play)" "playback paused item=11 " "T2 transport"; echo went-on')
check "and so does one whose earlier start line the log no longer holds (counted from before the tap)" \
  "$(grep -c 'shell am force-stop io.loopstring.readme' "$STUB_CALLS"),$(grep -c went-on <<<"$out")" "1,0"

setup "$T2_READING" "$R_STALE" ''
s0=$(date +%s); out=$(rc 'reader_check 1 "live" 2'); s1=$(date +%s)
check "reader_check on a Reader that did not follow fails within its wait, naming what it shows" \
  "$(grep -c '^FAIL live: the service is reading item 11; the Reader shows title T3' <<<"$out"),$(grep -o 'rc=[0-9]*' <<<"$out"),$([ $((s1 - s0)) -le 10 ] && echo bounded)" "1,rc=1,bounded"

for fn in live_switch_checks:11 returned_checks:11; do
  setup "$T2_READING" "$R_STALE$BTN" 'I ReadMe  : playback paused item=11 paragraph=3 offset=0
I ReadMe  : playback resumed item=11
'
  out=$(rc "${fn%%:*} ${fn#*:}; echo FAIL=\$FAIL")
  check "${fn%%:*}: a Reader that did not follow fails the run although its transport passed" \
    "$(grep -o 'FAIL=[0-9]' <<<"$out" | tail -1),$(grep -o 'rc=[0-9]*' <<<"$out")" "FAIL=1,rc=0"
done
setup "$T2_READING" "$R_T2$BTN" 'I ReadMe  : playback paused item=11 paragraph=3 offset=0
I ReadMe  : playback resumed item=11
'
out=$(rc 'live_switch_checks 11; echo FAIL=$FAIL')
check "live_switch_checks: a followed Reader and a working transport pass" \
  "$(grep -o 'FAIL=[0-9]' <<<"$out" | tail -1),$(grep -c '^PASS' <<<"$out")" "FAIL=0,2"
for fn in live_switch_checks:T2 returned_checks:T1; do
  setup "$T2_READING
I ReadMe  : playback continue from=11 item=10 sentences=151 start=0 ms=3" "$R_T2$BTN" ''
  out=$(rc "${fn%%:*} 11; echo went-on")
  check "${fn%%:*}: a transport check NOT REACHED ends the run as a failure" \
    "$(grep -c went-on <<<"$out"),$(grep -c "FAIL: the transport check on ${fn#*:} did not pass" <<<"$out"),$(grep -o 'rc=[0-9]*' <<<"$out")" "0,1,rc=1"
done
check "the main path runs these functions, not its own copies" \
  "$(grep -cE '^\s*live_switch_checks "\$id2"$' <<<"$code"),$(grep -cE '^returned_checks "\$id1"$' <<<"$code"),$(grep -cE '(reader|transport)_check "?\$id[12]' <<<"$code")" "1,1,0"

# --- REA-55 fix round 2: SystemUI's clipboard overlay took the returned-to Reader's Pause tap ---
WW=$(printf '  Window #0 Window{a u0 ScreenDecorHwcOverlay}:\n    isVisible=true\n  Window #1 Window{b u0 ClipboardOverlay}:\n    isVisible=true\n  Window #2 Window{c u0 NotificationShade}:\n    isVisible=false\n  Window #3 Window{d u0 io.loopstring.readme/io.loopstring.readme.MainActivity}:\n    isVisible=true\n  Window #4 Window{e u0 ClipboardOverlay}:\n    isVisible=true\n')
check "windows above Read Me: visible ones listed before its window, by name" \
  "$(src 'windows_above_readme "$1" | tr "\n" "|"' _ "$WW")" "ScreenDecorHwcOverlay|ClipboardOverlay|"
check "the clipboard overlay above Read Me is an overlay; one below it is not" \
  "$(src 'overlays "$1"' _ "$WW"),$(src 'overlays "$1"' _ "$(sed -n '1,2p;7,10p' <<<"$WW")")" "ClipboardOverlay,"
export STUB_OVERLAY="$T/overlay"
setup "$T2_READING" "$R_T2$BTN" 'I ReadMe  : playback paused item=11 paragraph=3 offset=0
I ReadMe  : playback resumed item=11
'
echo 2 > "$STUB_OVERLAY"
out=$(OVERLAY_WAIT=5 rc 'transport_check 11 "T1 transport"')
check "a tap waits for the clipboard overlay to go, then reaches Read Me (it was lost under it)" \
  "$(grep -c '^PASS T1 transport' <<<"$out"),$(grep -c 'ClipboardOverlay is over Read Me' <<<"$out"),$(grep -o 'rc=[0-9]*' <<<"$out")" "1,1,rc=0"
setup "$T2_READING" "$R_T2$BTN" 'I ReadMe  : playback paused item=11 paragraph=3 offset=0
'
echo 99 > "$STUB_OVERLAY"
out=$(OVERLAY_WAIT=2 rc 'transport_check 11 "T1 transport"')
check "an overlay that stays: FAIL naming it, and no tap under it" \
  "$(grep -c 'ClipboardOverlay still covers Read Me after 2 s; not tapped' <<<"$out"),$(grep -c 'input tap' "$STUB_CALLS"),$(grep -c PASS <<<"$out"),$(grep -o 'rc=[0-9]*' <<<"$out")" "1,0,0,rc=1"
echo 0 > "$STUB_OVERLAY"
setup "$T2_READING" "$R_T2$BTN" ''
out=$(rc 'transport_check 11 "T1 transport"')
check "a tap with no answer says what the Reader and the windows above Read Me show" \
  "$(grep -cF "tap; now the Reader shows title T2, highlighted paragraph yes, button " <<<"$out"),$(grep -cF "and the visible windows above Read Me are: StatusBar " <<<"$out"),$(grep -o 'rc=[0-9]*' <<<"$out")" "1,1,rc=1"
unset STUB_OVERLAY
unset STUB_UI STUB_TAPQ; : > "$STUB_LOG"

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

# ===================== REA-47: the buffer item, the chain watcher, a stale state file =====================
# T1's continue line naming a buffer-like item (161 sentences: the buffer's window), and one
# naming an owner-like item.
BUF='I ReadMe  : playback continue from=10 item=9 sentences=161 start=0 ms=3'
OWN='I ReadMe  : playback continue from=10 item=4 sentences=2 start=0 ms=2'

# --- a fourth test item: the buffer, shared first, so it sits directly below T1 ---
check "four test items" "$(src 'echo ${#TITLES[@]} ${#BODY[@]}')" "4 4"
check "the buffer's window takes 160-162 sentences" \
  "$(src 'count_ok 3 160 && count_ok 3 162 && echo yes'),$(src 'count_ok 3 159 || count_ok 3 163 || echo no')" "yes,no"
check "the buffer's window admits no other item's count, nor they its" \
  "$(src 'count_ok 3 151 || count_ok 3 41 || count_ok 3 4 || count_ok 0 161 || echo none')" none
: > "$STUB_CALLS"
src share_all >"$T/out" 2>&1
check "the shares go buffer, T1, T2, T3 (List order: T3, T2, T1, buffer)" \
  "$(grep -oE 'test item (buffer|one|two|three)' "$STUB_CALLS" | tr '\n' '|')" \
  "test item buffer|test item one|test item two|test item three|"
check "every share's text goes on stdin, never in the adb argv" \
  "$(grep -c '^shell .*Kestrel' "$STUB_CALLS")" 0

# --- the List check: exactly T3, T2, T1, buffer on top, each one ready ---
row() { printf '<node text="%s" bounds="[0,0][1,1]"/><node text="Shared text · 20 words" bounds="[0,0][1,1]"/><node text="%s" bounds="[0,0][1,1]"/><node content-desc="open %s" bounds="[0,0][1,1]"/><node content-desc="delete %s" bounds="[0,0][1,1]"/>' "$1" "$2" "$1" "$1"; }
HEAD='<node content-desc="settings" bounds="[0,0][1,1]"/>'
D_OK="$HEAD$(row 'Kestrel test item three.' ready)$(row 'Kestrel test item two.' ready)$(row 'Kestrel test item one.' ready)$(row 'Kestrel test item buffer.' ready)$(row 'Owner thing' ready)"
D_OWNER4="$HEAD$(row 'Kestrel test item three.' ready)$(row 'Kestrel test item two.' ready)$(row 'Kestrel test item one.' ready)$(row 'Owner thing' ready)$(row 'Kestrel test item buffer.' ready)"
D_NOTREADY="$HEAD$(row 'Kestrel test item three.' ready)$(row 'Kestrel test item two.' ready)$(row 'Kestrel test item one.' ready)$(row 'Kestrel test item buffer.' fetching)$(row 'Owner thing' ready)"
rows_verdict() { src 'if rows_ok "$1" && rows_ready "$1"; then echo accepted; else echo refused; fi' _ "$1"; }
check "T3, T2, T1, buffer, all ready, is accepted" "$(rows_verdict "$D_OK")" accepted
check "an owner item as the fourth row is refused (the chain would reach it after T1)" "$(rows_verdict "$D_OWNER4")" refused
check "the old three-row dump (owner item fourth, no buffer) is refused" "$(rows_verdict "$DUMP")" refused
check "a buffer not ready is refused, even with a ready owner row below it" "$(rows_verdict "$D_NOTREADY")" refused

# --- the watcher: stops Read Me as soon as the chain goes past T1 ---
printf '%s\n%s\n' "$LOG" "$BUF" | grep -v 'from=10 item=4' > "$STUB_LOG"; : > "$STUB_CALLS"; rm -f "$T/mark"
src "watch_chain 10 '$T/mark' 3" >"$T/out" 2>&1
check "the watcher force-stops Read Me on T1's continue line" "$(grep -c 'shell am force-stop io.loopstring.readme' "$STUB_CALLS")" 1
check "and notes the item, its count and start" "$(cut -d' ' -f1-3 "$T/mark" 2>/dev/null)" "9 161 0"
check "its output carries ids and counts only" "$(grep -cE 'Kestrel|hovers|grass' "$T/out" "$T/mark" | awk -F: '{s+=$2} END {print s}')" 0
printf '%s\n' "$LOG" | grep -v 'from=10 item=4' > "$STUB_LOG"; : > "$STUB_CALLS"; rm -f "$T/mark"
src "watch_chain 10 '$T/mark' 2" >"$T/out" 2>&1
check "with no continue line from T1 it stops nothing" "$(grep -c 'force-stop' "$STUB_CALLS"),$([ -e "$T/mark" ] && echo marked || echo none)" "0,none"
printf '%s\n' "$BUF" > "$STUB_LOG"; : > "$STUB_CALLS"; rm -f "$T/mark"; : > "$T/logcat-fail"
STUB_LOGCAT_FAIL="$T/logcat-fail" src "watch_chain 10 '$T/mark' 3" >"$T/out" 2>&1
check "a failed logcat read leaves the watcher polling (errexit off in it)" \
  "$([ -e "$T/logcat-fail" ] && echo unread || echo read),$(grep -c 'force-stop' "$STUB_CALLS")" "read,1"

# --- the buffer is recognised from the log itself, not from the watcher's marker ---
printf '%s\n%s\n' "$LOG" "$BUF" | grep -v 'from=10 item=4' > "$STUB_LOG"; : > "$STUB_CALLS"; rm -f "$T/mark"
out=$(src "CLEARED=1; ID1=10; TEST_IDS='12 11 10'; if guard; then r=0; else r=1; fi; echo rc=\$r ids=\$TEST_IDS played=\$BUFFER_PLAYED")
check "a played buffer is a test item for guard, with no marker file" "$(grep -oE 'rc=[0-9]+ ids=.* played=[0-9]+' <<<"$out")" "rc=0 ids=12 11 10 9 played=1"
check "and no NON-TEST alarm, no stop by guard" "$(grep -c 'NON-TEST' <<<"$out"),$(grep -c force-stop "$STUB_CALLS")" "0,0"
printf '%s\n' "$LOG" > "$STUB_LOG"; : > "$STUB_CALLS"
out=$(src "CLEARED=1; ID1=10; TEST_IDS='12 11 10'; if guard; then r=0; else r=1; fi; echo rc=\$r")
check "an owner-like item after T1 stays foreign: guard fails loudly" "$(grep -oE 'rc=[0-9]+' <<<"$out"),$(grep -c 'NON-TEST ITEM PLAYED: id(s) 4' <<<"$out")" "rc=1,1"
printf '%s\n' 'I ReadMe  : playback continue from=10 item=11 sentences=161 start=0 ms=3' > "$STUB_LOG"
out=$(src "CLEARED=1; ID1=10; TEST_IDS='12 10'; if guard; then r=0; else r=1; fi; echo rc=\$r")
check "a buffer-sized item newer than T1 is not the buffer" "$(grep -oE 'rc=[0-9]+' <<<"$out")" "rc=1"
printf '%s\n' 'I ReadMe  : playback continue from=10 item=9 sentences=161 start=40 ms=3' > "$STUB_LOG"
out=$(src "CLEARED=1; ID1=10; TEST_IDS='12 11 10'; if guard; then r=0; else r=1; fi; echo rc=\$r")
check "a buffer-sized item read from a saved position is not the buffer" "$(grep -oE 'rc=[0-9]+' <<<"$out")" "rc=1"
printf '%s\n%s\n' "$LOG" "$BUF" | grep -v 'from=10 item=4' > "$STUB_LOG"; : > "$STUB_CALLS"; mkdir .claude/device.lock
STUB_LOCKED=1 UNLOCK_WAIT=1 src "DEVICE_LOCK_OURS=1; trap finish EXIT; PLAYED=1; CLEARED=1; ID1=10; TEST_IDS='12 11 10'; exit 0" >"$T/out" 2>&1
check "a run where the buffer played fails as NOT COMPLETED, not as a non-test play" \
  "$?,$(grep -c 'NOT COMPLETED' "$T/out"),$(grep -c 'NON-TEST' "$T/out")" "1,1,0"

# --- the parent checks the watcher is alive, and stops Read Me when it is not ---
printf '%s\n' "$LOG" | grep -v 'from=10 item=4' > "$STUB_LOG"; : > "$STUB_CALLS"
out=$(src "CLEARED=1; ID1=10; TEST_IDS='12 11 10'; (exit 0) & d=\$!; wait \$d; WATCH_PID=\$d; check_watch; echo survived" 2>&1)
check "a dead watcher stops Read Me and fails the run" \
  "$(grep -c survived <<<"$out"),$(grep -c 'shell am force-stop' "$STUB_CALLS"),$(grep -c 'watcher is not running' <<<"$out")" "0,1,1"
: > "$STUB_CALLS"
out=$(src "CLEARED=1; ID1=10; TEST_IDS='12 11 10'; sleep 5 & WATCH_PID=\$!; check_watch; echo survived; kill \$WATCH_PID" 2>&1)
check "a live watcher and a clean log go on" "$(grep -c survived <<<"$out"),$(grep -c force-stop "$STUB_CALLS")" "1,0"
printf '%s\n' "$BUF" > "$STUB_LOG"; : > "$STUB_CALLS"
out=$(src "CLEARED=1; ID1=10; TEST_IDS='12 11 10'; sleep 5 & WATCH_PID=\$!; check_watch; echo survived" 2>&1)
check "the buffer started before the switch-off: stopped, NOT COMPLETED" \
  "$(grep -c survived <<<"$out"),$(grep -c 'shell am force-stop' "$STUB_CALLS"),$(grep -c 'NOT COMPLETED' <<<"$out")" "0,1,1"

# --- finish ends the watcher; a signal still stops Read Me, fails the run and frees the slot ---
: > "$STUB_LOG"; : > "$STUB_CALLS"; mkdir .claude/device.lock
src "DEVICE_LOCK_OURS=1; trap finish EXIT; sleep 30 & WATCH_PID=\$!; echo \$WATCH_PID > '$T/wpid'; exit 0" >"$T/out" 2>&1
check "the trap ends the watcher" "$(kill -0 "$(cat "$T/wpid")" 2>/dev/null && echo alive || echo gone)" gone
check "the signal trap is set right after the EXIT trap" \
  "$(grep -A1 -E '^trap finish EXIT$' <<<"$code" | tail -1)" "trap on_signal INT TERM HUP"
: > "$STUB_CALLS"; mkdir .claude/device.lock
bash -c "DEVICE_CONTINUOUS_SOURCE_ONLY=1; . scripts/device-continuous.sh; DEVICE_LOCK_OURS=1; trap finish EXIT; trap on_signal INT TERM HUP; PLAYED=1; CLEARED=1; TEST_IDS=''; while :; do sleep 0.2; done" >"$T/out" 2>&1 &
p=$!; sleep 1; kill -TERM "$p"; wait "$p"; rc=$?
check "TERM mid-play: Read Me force-stopped, the run fails, the slot is free" \
  "$([ "$rc" != 0 ] && echo nonzero),$(grep -c 'shell am force-stop' "$STUB_CALLS"),$(grep -c 'device:continuous FAIL' "$T/out"),$([ -d .claude/device.lock ] && echo held || echo free)" \
  "nonzero,1,1,free"

# --- a stale state file (an earlier run's cleanup did not finish) refuses a full run ---
mkdir -p android/app/build/outputs/readme .claude/scratch
APKF=android/app/build/outputs/readme/app-arm64-v8a-release.apk
: > "$APKF"; printf '%s\nclean\n' "$(git rev-parse HEAD)" > "$APKF.stamp"
printf '%s' "$HEAD"'<node content-desc="Continuous play" checked="true" bounds="[0,0][1,1]"/><node content-desc="archive" bounds="[0,0][1,1]"/><node content-desc="unread" bounds="[0,0][1,1]"/>' > "$T/ui"
echo false > .claude/scratch/device-continuous.before
rm -rf .claude/device.lock; : > "$STUB_CALLS"; : > "$STUB_LOG"
STUB_UI="$T/ui" bash scripts/device-continuous.sh >"$T/out" 2>&1; rc=$?
check "a full run with a previous run's state file pending is refused" "$rc,$(grep -c 'REFUSED' "$T/out")" "1,1"
check "and says to run the cleanup first" "$(grep -c 'CLEANUP_ONLY=1 npm run device:continuous' "$T/out")" 1
check "the owner's original setting is kept" "$(cat .claude/scratch/device-continuous.before 2>/dev/null || echo missing)" false
check "nothing reached the phone: no adb call at all" "$(wc -l < "$STUB_CALLS" | tr -d ' ')" 0
check "the slot was never taken" "$([ -d .claude/device.lock ] && echo held || echo free)" free
echo true > .claude/scratch/device-continuous.before; : > "$STUB_CALLS"
CLEANUP_ONLY=1 STUB_UI="$T/ui" bash scripts/device-continuous.sh >"$T/out" 2>&1; rc=$?
check "CLEANUP_ONLY=1 is not refused, and restores and clears the state" \
  "$rc,$(grep -c REFUSED "$T/out"),$(grep -c 'cleanup: done' "$T/out"),$([ -e .claude/scratch/device-continuous.before ] && echo kept || echo gone)" "0,0,1,gone"
rm -f "$APKF" "$APKF.stamp"
exit $fail
