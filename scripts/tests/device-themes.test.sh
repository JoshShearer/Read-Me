#!/usr/bin/env bash
# Tests for device-themes.sh's display save and restore (REA-58) against a stub adb that keeps
# the phone's save file in a temp dir, and for scripts/dock-check.py on synthetic dumps. Never
# touches a real device.
set -uo pipefail
unset READ_ME_PIN_FILE DEVICE_UNLOCK_STATE
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
T=$(mktemp -d); # Removing the temp dir also ends any watchdog still running (its id file goes).
trap 'rm -rf "$T"' EXIT
mkdir -p "$T/bin" "$T/repo/scripts/lib"
# The stub adb is a small phone: `adb shell <cmd>` and a script on adb's stdin run in sh with
# /data/local/tmp and /sdcard mapped into $STUB_DIR/phone and the phone's own commands (wm,
# settings, am, cmd, input, uiautomator) from $STUB_DIR/phonebin, so the phone-side restore and
# watchdog that device-themes.sh sends really run (the watchdog as a detached process). The call
# log has one line per adb call ("shell <<<first line>" for a stdin script), and wm and settings
# log themselves as "shell wm ..." / "shell settings ...", once each however they were reached.
# $STUB_DIR/down makes every call fail (adb gone); catfail makes the next read of the save file
# fail once (an adb blip); writefail fails the save write, writeshort writes 9 bytes of it;
# resetfail fails `wm density reset`; forcestop-fail-at <n> fails the n-th `am force-stop`.
mkdir -p "$T/phone" "$T/phonebin"
cat > "$T/bin/adb" <<'STUB'
#!/bin/sh
P="$STUB_DIR/phone"; STDIN_SCRIPT=; from_stdin=0
if [ "$1" = shell ] && [ $# = 1 ]; then
  STDIN_SCRIPT=$(cat); from_stdin=1
  echo "shell <<$(printf '%s\n' "$STDIN_SCRIPT" | head -1)" >> "$STUB_CALLS"
else
  case "$2" in wm|settings) ;; *) echo "$*" >> "$STUB_CALLS" ;; esac
fi
[ -f "$STUB_DIR/down" ] && exit 1
case "$1" in
  shell) ;;
  logcat) [ -f "$STUB_DIR/logcat" ] && cat "$STUB_DIR/logcat"; exit 0 ;;
  *) exit 0 ;;
esac
shift
case "$*" in
  "if [ -f /data/local/tmp/readme-display.saved ]; then cat"*)
    if [ -f "$STUB_DIR/catfail" ]; then rm -f "$STUB_DIR/catfail"; exit 1; fi ;;
  "cat > /data/local/tmp/readme-display.saved")
    if [ -f "$STUB_DIR/writefail" ]; then cat >/dev/null; exit 1; fi
    if [ -f "$STUB_DIR/writeshort" ]; then head -c 9 > "$P/readme-display.saved"; exit 0; fi ;;
esac
PATH="$STUB_DIR/phonebin:$PATH"; export PATH
map() { sed "s|/data/local/tmp/|$P/|g; s|/sdcard/|$P/|g"; }
if [ "$from_stdin" = 1 ]; then printf '%s\n' "$STDIN_SCRIPT" | map | sh
else sh -c "$(printf '%s' "$*" | map)"; fi
STUB
cat > "$T/phonebin/wm" <<'STUB'
#!/bin/sh
echo "shell wm $*" >> "$STUB_CALLS"; O="$STUB_DIR/override"
case "$*" in
  density) echo "Physical density: 480"; [ -f "$O" ] && echo "Override density: $(cat "$O")"; : ;;
  size) echo "Physical size: 1344x2992" ;;
  "density reset") [ "${STUB_WM_RC:-0}" = 0 ] || exit "$STUB_WM_RC"; [ ! -f "$STUB_DIR/resetfail" ] || exit 1; rm -f "$O" ;;
  "density "*) [ "${STUB_WM_RC:-0}" = 0 ] || exit "$STUB_WM_RC"; echo "$2" > "$O" ;;
esac
STUB
cat > "$T/phonebin/settings" <<'STUB'
#!/bin/sh
echo "shell settings $*" >> "$STUB_CALLS"; S="$STUB_DIR/fs"
case "$*" in
  "get system font_scale") if [ -f "$S" ]; then cat "$S"; else echo null; fi ;;
  "put system font_scale "*) echo "$4" > "$S" ;;
  "delete system font_scale") rm -f "$S" ;;
esac
STUB
cat > "$T/phonebin/am" <<'STUB'
#!/bin/sh
[ "$1" = force-stop ] || exit 0
echo x >> "$STUB_DIR/forcestops"
[ -f "$STUB_DIR/forcestop-fail-at" ] && [ "$(wc -l < "$STUB_DIR/forcestops")" -ge "$(cat "$STUB_DIR/forcestop-fail-at")" ] && exit 1
exit 0
STUB
printf '#!/bin/sh\necho "Night mode: no"\n' > "$T/phonebin/cmd"
printf '#!/bin/sh\n:\n' > "$T/phonebin/input"; cp "$T/phonebin/input" "$T/phonebin/uiautomator"
chmod +x "$T/bin/adb" "$T/phonebin/"*
export PATH="$T/bin:$PATH" STUB_CALLS="$T/calls" STUB_DIR="$T"
SAVED="$T/phone/readme-display.saved"; WATCH="$T/phone/readme-display.watchdog"
# THEMES_SH and DOCK_CHECK point at another copy, to show a new case failing on an older script.
THEMES_SH=${THEMES_SH:-$ROOT/scripts/device-themes.sh}
DOCK_CHECK=${DOCK_CHECK:-$ROOT/scripts/dock-check.py}

cp "$ROOT/scripts/lib/device.sh" "$T/repo/scripts/lib/"
cp "$THEMES_SH" "$T/repo/scripts/device-themes.sh"
cd "$T/repo" && git init -q && git -c user.email=t@t -c user.name=t commit -q --allow-empty -m init
fail=0
check() { if [ "$2" = "$3" ]; then echo "ok   $1"; else echo "FAIL $1: expected $3, got $2"; fail=1; fi; }
# run <commands>: in one bash process (so the script's in-process flags hold across them);
# its stdout and stderr go to $T/out and $T/err, its exit status to $T/rc.
run() {
  bash -c "DEVICE_THEMES_SOURCE_ONLY=1; . scripts/device-themes.sh; $1" >"$T/out" 2>"$T/err"
  echo $? > "$T/rc"
}
# fresh [override] [font_scale]: no save file, the phone at those values, an empty call log.
fresh() {
  rm -f "$SAVED" "$WATCH" "$T/override" "$T/fs" "$T/down" "$T/catfail" "$T/writefail" "$T/writeshort" \
    "$T/resetfail" "$T/forcestops" "$T/forcestop-fail-at" "$T/logcat"
  : > "$STUB_CALLS"; unset STUB_WM_RC DISPLAY_WATCHDOG_SECS
  [ -z "${1:-}" ] || echo "$1" > "$T/override"
  [ -z "${2:-}" ] || echo "$2" > "$T/fs"
}
state() { echo "$(cat "$T/override" 2>/dev/null || echo none) $(cat "$T/fs" 2>/dev/null || echo null)"; }
writes() { grep -E 'cat > |wm density [^ ]+$|settings (put|delete)|rm -f /data|<<# readme-display: watchdog' "$STUB_CALLS"; }
file() { [ -f "$SAVED" ] && echo kept || echo gone; }

fresh
run display_restore
check "no save file: no wm density at exit" "$(grep -c 'wm density' "$STUB_CALLS")" 0
check "no save file: no settings write at exit" "$(grep -cE 'settings (put|delete)' "$STUB_CALLS")" 0

fresh '' 1.0
run 'display_set 597 1.0'
check "the save is written before the density changes" \
  "$(grep -nE 'cat > |wm density 597' "$STUB_CALLS" | cut -d: -f2 | tr '\n' '|')" \
  'shell cat > /data/local/tmp/readme-display.saved|shell wm density 597|'
check "display_set: the phone's watchdog starts, then the save, then the density changes" \
  "$(grep -nE '^shell <<# readme-display: watchdog start$|cat > |wm density 597' "$STUB_CALLS" | cut -d: -f2- | tr '\n' '|')" \
  'shell <<# readme-display: watchdog start|shell cat > /data/local/tmp/readme-display.saved|shell wm density 597|'
check "display_set's first adb write is the watchdog's start" "$(writes | head -1)" 'shell <<# readme-display: watchdog start'
check "the watchdog runs on the (stub) phone, detached" "$(pgrep -fc "$T/phone/readme-display-watchdog.sh" || true)" 1
check "only Override density is saved, none when absent" "$(cat "$SAVED")" 'density=none font_scale=1.0'
: > "$STUB_CALLS"
run 'display_set 597 2.0'
check "a second change (a new process) keeps the first save" "$(grep -c 'cat > ' "$STUB_CALLS")" 0
check "and the saved values are the phone's own" "$(cat "$SAVED")" 'density=none font_scale=1.0'
: > "$STUB_CALLS"
run display_restore
check "restore resets an unset density" "$(grep -c 'shell wm density reset' "$STUB_CALLS")" 1
check "restore puts the saved font scale back" "$(grep -c 'settings put system font_scale 1.0' "$STUB_CALLS")" 1
check "and removes the save file" "$(file)" gone
check "and the phone is back to its own values" "$(state)" 'none 1.0'

fresh 420 ''
run 'display_set 597 2.0'
check "an override and an unset font scale are saved" "$(cat "$SAVED")" 'density=420 font_scale=null'
: > "$STUB_CALLS"
run display_restore
check "restore puts the override back" "$(grep -c 'shell wm density 420' "$STUB_CALLS")" 1
check "restore deletes an unset font scale" "$(grep -c 'settings delete system font_scale' "$STUB_CALLS")" 1

fresh; printf 'density=none font_scale=1.3\n' > "$SAVED"; export STUB_WM_RC=1
run display_restore
check "a failed restore keeps the save file" "$(file)" kept
check "and says how to restore by hand" "$(grep -c 'display NOT restored' "$T/err")" 1
unset STUB_WM_RC

fresh; printf 'density=none font_scale=1.3\n' > "$SAVED"
run 'display_set 597 2.0'
check "a stale save file is never overwritten" "$(cat "$SAVED")" 'density=none font_scale=1.3'

# The start of a run (display_recover), behaviourally.
fresh 597 2.0; printf 'density=none font_scale=1.3\n' > "$SAVED"
run display_recover
check "recover: a stale save is restored (density reset, font_scale 1.3)" \
  "$(writes | tr '\n' '|')" 'shell wm density reset|shell settings put system font_scale 1.3|shell rm -f /data/local/tmp/readme-display.watchdog /data/local/tmp/readme-display-watchdog.sh|'
check "recover: the file is removed" "$(file)" gone
check "recover: and returns 0" "$(cat "$T/rc")" 0
fresh 597 2.0; printf 'density=none font_scale=1.3\n' > "$SAVED"; export STUB_WM_RC=1
run display_recover
check "recover: a failed restore returns non-zero" "$([ "$(cat "$T/rc")" != 0 ] && echo nonzero || echo zero)" nonzero
check "recover: and keeps the file" "$(file)" kept
unset STUB_WM_RC
fresh 420 1.0
run display_recover
check "recover: no file, no display writes" "$(writes | wc -l | tr -d ' ')" 0
check "recover: no file returns 0" "$(cat "$T/rc")" 0
fresh; printf 'density=none font_scale=1.3\n' > "$SAVED"; touch "$T/down"
run display_recover
check "recover: adb failing is an error, not 'no file'" "$([ "$(cat "$T/rc")" != 0 ] && echo nonzero || echo zero)" nonzero

# One run, two changes: a read of the save file that fails once must not re-save this run's values.
fresh '' 1.3
run 'display_set 597 1.0; touch "$STUB_DIR/catfail"; display_set 597 2.0'
check "a failed read on the second display_set keeps the owner's save" "$(cat "$SAVED")" 'density=none font_scale=1.3'
check "and the run saved once" "$(grep -c 'cat > ' "$STUB_CALLS")" 1
check "and, holding a verified save, the second change goes ahead without re-reading it" \
  "$(cat "$T/rc") $(state)" '0 597 2.0'

# adb gone at exit after this run changed the display: a warning with the commands, non-zero.
fresh '' 1.3
run 'display_set 597 2.0; touch "$STUB_DIR/down"; display_restore'
check "a failed read at exit after a change warns" "$(grep -c 'display NOT restored' "$T/err")" 1
check "with the manual commands" "$(grep -c 'adb shell wm density reset' "$T/err")" 1
check "and returns non-zero" "$([ "$(cat "$T/rc")" != 0 ] && echo nonzero || echo zero)" nonzero
rm -f "$T/down"

# The real exit path: lib/device.sh's EXIT trap running the script's hook after a failure.
fresh '' 1.3
cat > "$T/repo/exit-path.sh" <<'SH'
DEVICE_THEMES_SOURCE_ONLY=1
. scripts/device-themes.sh
unset DEVICE_THEMES_SOURCE_ONLY
. scripts/lib/device.sh
trap _device_exit EXIT
DEVICE_ON_EXIT=restore
display_set 597 2.0
exit 1
SH
(cd "$T/repo" && bash exit-path.sh) >"$T/out" 2>"$T/err"; rc=$?
check "exit path: the run's own status is kept" "$rc" 1
check "exit path: the hook restored density and font_scale" \
  "$(grep -cE '^shell (wm density reset|settings put system font_scale 1.3)$' "$STUB_CALLS")" 2
check "exit path: the save file is removed" "$(file)" gone
check "exit path: the phone is back to its own values" "$(state)" 'none 1.3'

# The save write failing, or reading back different: no display change, non-zero, no watchdog left.
for mode in writefail writeshort; do
  fresh '' 1.3; touch "$T/$mode"
  run 'display_set 597 2.0'
  check "$mode: display_set returns non-zero" "$([ "$(cat "$T/rc")" != 0 ] && echo nonzero || echo zero)" nonzero
  check "$mode: no density or font_scale write" "$(grep -cE 'wm density 597|settings put system font_scale 2.0' "$STUB_CALLS")" 0
  check "$mode: the phone keeps its own values" "$(state)" 'none 1.3'
  check "$mode: the watchdog is cancelled" "$([ -f "$WATCH" ] && echo kept || echo gone)" gone
done
# The save file gone after this run changed the display: a warning, non-zero.
fresh '' 1.3
run 'display_set 597 2.0; rm -f "$STUB_DIR/phone/readme-display.saved"; display_restore'
check "save file gone after a change: warns" "$(grep -c 'display NOT restored' "$T/err")" 1
check "save file gone after a change: non-zero" "$([ "$(cat "$T/rc")" != 0 ] && echo nonzero || echo zero)" nonzero
# A garbled save file: recover and display_set both refuse, with no display write; recover prints
# the whole manual block.
fresh 597 2.0; printf 'density=59' > "$SAVED"
run display_recover
check "garbled save: recover fails" "$([ "$(cat "$T/rc")" != 0 ] && echo nonzero || echo zero)" nonzero
check "garbled save: recover prints the manual commands" \
  "$(grep -cE 'adb shell (wm density reset|settings put system font_scale|rm /data/local/tmp/readme-display.saved)' "$T/err")" 3
check "garbled save: recover writes nothing" "$(grep -cE 'wm density [^ ]+$|settings (put|delete)' "$STUB_CALLS")" 0
run 'display_set 597 1.0'
check "garbled save: display_set changes nothing" "$(grep -cE 'wm density 597|settings put' "$STUB_CALLS")" 0

# The phone-side watchdog. Not started when the display is never changed.
fresh 420 1.0
run 'display_restore; display_recover'
check "no display change: no watchdog" "$(grep -c 'watchdog start' "$STUB_CALLS")" 0
# A run killed (SIGKILL, no EXIT trap) after it changed the display: the watchdog restores it.
fresh '' 1.3
(export DISPLAY_WATCHDOG_SECS=3
 exec bash -c 'DEVICE_THEMES_SOURCE_ONLY=1; . scripts/device-themes.sh; display_set 597 2.0; touch "$STUB_DIR/changed"; sleep 60') \
  >"$T/out" 2>&1 &
killer=$!
for _ in $(seq 50); do [ -f "$T/changed" ] && break; sleep 0.1; done
check "killed run: the display was changed" "$(state)" '597 2.0'
kill -9 "$killer"; wait "$killer" 2>/dev/null; rm -f "$T/changed"
for _ in $(seq 60); do [ -f "$SAVED" ] || break; sleep 0.1; done
check "killed run: the watchdog put the phone's own values back" "$(state)" 'none 1.3'
check "killed run: and removed the save file" "$(file)" gone
check "killed run: and its id file" "$([ -f "$WATCH" ] && echo kept || echo gone)" gone
sleep 1.5
check "killed run: no watchdog left running" "$(pgrep -fc "$T/phone/readme-display-watchdog.sh" || true)" 0
# A confirmed restore cancels it: a later save file is left alone.
fresh '' 1.3; export DISPLAY_WATCHDOG_SECS=2
run 'display_set 597 2.0; display_restore'
unset DISPLAY_WATCHDOG_SECS
check "restore: the watchdog is cancelled" "$(grep -c 'display watchdog cancelled' "$T/out")" 1
check "restore: its id file is gone" "$([ -f "$WATCH" ] && echo kept || echo gone)" gone
printf 'density=111 font_scale=9.9\n' > "$SAVED"; sleep 3
check "restore: the cancelled watchdog changed nothing later" "$(state) $(file)" 'none 1.3 kept'
check "restore: no watchdog left running" "$(pgrep -fc "$T/phone/readme-display-watchdog.sh" || true)" 0

# The real script, top level, THEMES_ONLY=narrow, with lib/device.sh's own trap and the phone
# work it needs stubbed in the test copy of lib (the lane's lib is not touched).
cat >> "$T/repo/scripts/lib/device.sh" <<'LIB'
device_take() { trap _device_exit EXIT; }
device_require_unlocked() { :; }
device_install_release() { :; }
device_clear_app() { :; }
LIB
cp "$DOCK_CHECK" "$T/repo/scripts/dock-check.py"
cp "${SHEET_CHECK:-$ROOT/scripts/sheet-check.py}" "$T/repo/scripts/sheet-check.py"
printf 'print("contrast: not measured (stub)")\n' > "$T/repo/scripts/theme-contrast.py"
# A two-row dock at 597 dpi (also what the stub phone shows at 480 dpi: wide must be one row).
dock_dump() {
  printf '<hierarchy><node bounds="[0,0][1344,2992]">'
  icon 'back paragraph' 40 2500; icon 'previous sentence' 240 2500
  printf '<node content-desc="play" bounds="[440,2515][800,2664]"><node text="Play" bounds="[530,2540][710,2640]"/></node>'
  icon 'next sentence' 820 2500
  icon slower 400 2700; printf '<node text="1.0x" bounds="[590,2740][750,2840]"/>'; icon faster 760 2700
  trim_nodes; printf '</node></hierarchy>'
}
# What the narrow pass's REA-62 step finds on the stub's one screen: Trim's button and Done, a
# paragraph, and the open sheet's rows at 597 dpi (48 dp = 179 px), each row ${SHEET_ROW:-250} px
# tall with a label ${SHEET_LABEL:-120} px tall.
sheet_row() {  # sheet_row <id> <y0> [row height] [label height]
  local h=${3:-250} l=${4:-120}
  printf '<node resource-id="%s" bounds="[36,%s][1308,%s]"><node text="x" bounds="[84,%s][900,%s]"/></node>' \
    "$1" "$2" "$(($2 + h))" "$(($2 + (h - l) / 2))" "$(($2 + (h + l) / 2))"
}
trim_nodes() {
  printf '<node content-desc="trim" bounds="[1100,100][1300,200]"/><node content-desc="trim done" bounds="[1100,220][1300,320]"/>'
  printf '<node resource-id="paragraph 1" bounds="[0,400][1344,800]"/>'
  local h=${SHEET_ROW:-250} l=${SHEET_LABEL:-120}
  sheet_row 'trim menu start here' 2000 "$h" "$l"; sheet_row 'trim menu cut after' $((2000 + h)) "$h" "$l"
  sheet_row 'trim menu cancel' $((2000 + 2 * h)) "$h" "$l"
}
icon() { printf '<node content-desc="%s" bounds="[%s,%s][%s,%s]"><node text="g" bounds="[%s,%s][%s,%s]"/></node>' \
  "$1" "$2" "$3" "$(($2 + ${5:-179}))" "$(($3 + 179))" "$(($2 + 60))" "$(($3 + (179 - ${4:-160}) / 2))" \
  "$(($2 + 120))" "$(($3 + (179 + ${4:-160}) / 2))"; }
themes() {  # themes: the real script; stdout+stderr to $T/out, status to $T/rc
  dock_dump > "$T/phone/readme-ui.xml"
  (cd "$T/repo" && THEMES_ONLY=narrow bash scripts/device-themes.sh) >"$T/out" 2>&1; echo $? > "$T/rc"
}
before() {  # before <ERE a> <ERE b>: the first call matching a comes before the first matching b
  local a b; a=$(grep -nE "$1" "$STUB_CALLS" | head -1 | cut -d: -f1); b=$(grep -nE "$2" "$STUB_CALLS" | head -1 | cut -d: -f1)
  [ -n "$a" ] && [ -n "$b" ] && [ "$a" -lt "$b" ] && echo yes || echo no
}
# A failure right after the first display change (the second am force-stop fails, set -e).
fresh '' 1.3; echo 2 > "$T/forcestop-fail-at"
themes
check "script: a failure in the narrow pass fails the run" "$([ "$(cat "$T/rc")" != 0 ] && echo nonzero || echo zero)" nonzero
check "script: it had changed the display" "$(grep -c '^shell wm density 597$' "$STUB_CALLS")" 1
check "script: the watchdog started before the first wm density 597" "$(before 'watchdog start' '^shell wm density 597$')" yes
check "script: the EXIT hook (DEVICE_ON_EXIT=restore) put the display back" "$(state) $(file)" 'none 1.3 gone'
check "script: the hook restores the display before demo mode's exit" \
  "$(before '^shell <<# readme-display: restore_display' 'command exit')" yes
check "script: and cancels the watchdog" "$([ -f "$WATCH" ] && echo kept || echo gone)" gone
check "script: the change was announced with the manual commands" "$(grep -c '^NOTE: the display changes now' "$T/out")" 1
# A stale save at the start is restored before anything else (display_recover's call site).
fresh 597 2.0; printf 'density=none font_scale=1.3\n' > "$SAVED"; echo 1 > "$T/forcestop-fail-at"
themes
check "script: a stale save is restored before the first share" \
  "$(before '^shell settings put system font_scale 1.3$' 'ShareActivity')" yes
# A save file that cannot be read stops the run before it shares or changes anything.
fresh 597 2.0; printf 'garbled\n' > "$SAVED"
themes
check "script: an unreadable save file stops the run" "$(cat "$T/rc") $(grep -cE 'ShareActivity|wm density 597' "$STUB_CALLS")" '1 0'
# A whole narrow pass on the stub; the end-of-pass restore cannot reset the density.
fresh '' 1.0; touch "$T/resetfail"
printf 'I ReadMe: playback start item=1\nI ReadMe: playback paused item=1\n' > "$T/logcat"
themes
check "script: wide is checked for one row" "$(grep -c '^wide-1-reader FAIL dock: layout two-rows, expected one-row$' "$T/out")" 1
check "script: narrow is checked for two rows" \
  "$(grep -c '^narrow-1-reader dock: layout two rows$' "$T/out") $(grep -c '^narrow-1-reader FAIL dock: layout' "$T/out")" '1 0'
check "script: a failed end-of-pass restore fails the run" "$(grep -c '^FAIL: the display was not restored' "$T/out")" 1
check "script: and warns with the commands" "$(grep -c 'display NOT restored' "$T/out")" 2
rm -f "$T/resetfail"
# The same pass on a dock drawn in one row: the narrow captures must fail on the layout.
fresh '' 1.0
printf 'I ReadMe: playback start item=1\nI ReadMe: playback paused item=1\n' > "$T/logcat"
dock_dump() {
  printf '<hierarchy><node bounds="[0,0][1344,2992]">'
  icon 'back paragraph' 20 2500; icon 'previous sentence' 205 2500
  printf '<node content-desc="play" bounds="[390,2515][760,2664]"><node text="Play" bounds="[480,2540][670,2640]"/></node>'
  icon 'next sentence' 770 2500; icon slower 960 2500; icon faster 1150 2500
  printf '<node text="1.0x" bounds="[1000,2400][1100,2480]"/>'; trim_nodes; printf '</node></hierarchy>'
}
themes
check "script: a one-row dock fails narrow's two-row check" \
  "$(grep -c '^narrow-[13]-reader FAIL dock: layout one-row, expected two-rows$' "$T/out")" 2
check "script: and passes wide's one-row check" "$(grep -c '^wide-1-reader FAIL dock: layout' "$T/out")" 0
check "script: the display is back after the pass" "$(state) $(file)" 'none 1.0 gone'
check "script: the narrow pass checks Trim's sheet and it passes" \
  "$(grep -c '^narrow-5-trim-sheet sheet: PASS$' "$T/out") $(grep -c "^FAIL: Trim's long-press sheet" "$T/out")" '1 0'
# REA-62: a fixed 48 dp row (179 px) whose wrapped label is clipped to it fails the run.
fresh '' 1.0
printf 'I ReadMe: playback start item=1\nI ReadMe: playback paused item=1\n' > "$T/logcat"
SHEET_ROW=179 SHEET_LABEL=179 themes
check "script: a clipped sheet row fails the run" \
  "$(grep -c "^FAIL: Trim's long-press sheet" "$T/out") $(grep -c '^device:themes FAIL$' "$T/out") $(cat "$T/rc")" '1 1 1'
check "script: and the display is still restored" "$(state) $(file)" 'none 1.0 gone'

# Source check (not behaviour): every `adb logcat -c` after the first follows a crash_check, or
# the clear would drop a crash before the end-of-run gate reads the log.
check "every later logcat -c is preceded by crash_check" "$(awk '
  /^[[:space:]]*adb logcat -c[[:space:]]*$/ { n++; if (n > 1 && prev !~ /^[[:space:]]*crash_check /) bad++ }
  NF { prev = $0 } END { print (n > 1 ? bad + 0 : "none") }' "$THEMES_SH")" 0

# dock-check.py: a two-row dock at 597 dpi (48 dp = 179 px, 40 dp = 149 px), 2992 px tall.
# icon <label> <x0> <y0> [glyph height] [width]
icon() { printf '<node content-desc="%s" bounds="[%s,%s][%s,%s]"><node text="g" bounds="[%s,%s][%s,%s]"/></node>' \
  "$1" "$2" "$3" "$(($2 + ${5:-179}))" "$(($3 + 179))" "$(($2 + 60))" "$(($3 + (179 - ${4:-160}) / 2))" \
  "$(($2 + 120))" "$(($3 + (179 + ${4:-160}) / 2))"; }
dump() {
  printf '<hierarchy><node bounds="[0,0][1344,2992]">'
  icon 'back paragraph' 40 2500; icon 'previous sentence' 240 2500
  printf '<node content-desc="play" bounds="[440,2515][800,2664]"><node text="Play" bounds="[530,2540][710,2640]"/></node>'
  icon 'next sentence' 820 2500
  icon slower 400 2700 "${2:-160}"; printf '<node text="1.0x" bounds="[590,2740][750,2840]"/>'
  [ "$1" = nofaster ] || icon faster 760 2700 160 "${3:-179}"
  printf '</node></hierarchy>'
}
dc() { dump "$@" > "$T/d.xml"; python3 "$DOCK_CHECK" "$T/d.xml" 597 2992 play; }
check "dock-check passes a dock that fits" "$(dc ok | tail -1)" 'dock: PASS'
check "a missing faster fails" "$(dc nofaster | grep -c 'faster missing')" 1
check "a faster with no area fails" "$(dc ok 160 -5 | grep -c 'faster has no area')" 1
check "a narrowed faster fails as clipped" "$(dc ok 160 120 | grep -c 'faster 120x179 px')" 1
check "a glyph as tall as its box fails" "$(dc ok 179 | grep -c 'slower glyph clipped')" 1
check "it prints Play's centre" "$(dc ok | grep -c '^dock: play centre 620,2589$')" 1
check "it prints the layout" "$(dc ok | grep -c '^dock: layout two rows$')" 1
dump ok | sed 's|<node content-desc="play" bounds="\[440,2515\]\[800,2664\]"><node text="Play" bounds="\[530,2540\]\[710,2640\]"/>|<node content-desc="play" bounds="[1000,2515][1344,2664]"><node text="Play" bounds="[1100,2540][1344,2640]"/>|' > "$T/d.xml"
check "a Play pill clipped by the screen edge fails" \
  "$(python3 "$DOCK_CHECK" "$T/d.xml" 597 2992 play | grep -c 'play label clipped')" 1
check "--expect two-rows passes a two-row dock" "$(dc ok | tail -1)" 'dock: PASS'
dump ok > "$T/d.xml"
check "--expect two-rows passes it" "$(python3 "$DOCK_CHECK" "$T/d.xml" 597 2992 play --expect two-rows | tail -1)" 'dock: PASS'
check "--expect one-row fails it" \
  "$(python3 "$DOCK_CHECK" "$T/d.xml" 597 2992 play --expect one-row | grep -c 'FAIL dock: layout two-rows, expected one-row')" 1
# One row: every control on one line.
{ printf '<hierarchy><node bounds="[0,0][1344,2992]">'
  icon 'back paragraph' 20 2500; icon 'previous sentence' 205 2500
  printf '<node content-desc="play" bounds="[390,2515][760,2664]"><node text="Play" bounds="[480,2540][670,2640]"/></node>'
  icon 'next sentence' 770 2500; icon slower 960 2500; icon faster 1150 2500
  printf '<node text="1.0x" bounds="[1000,2400][1100,2480]"/></node></hierarchy>'; } > "$T/d.xml"
check "--expect one-row passes a one-row dock" "$(python3 "$DOCK_CHECK" "$T/d.xml" 597 2992 play --expect one-row | tail -1)" 'dock: PASS'
check "--expect two-rows fails it" \
  "$(python3 "$DOCK_CHECK" "$T/d.xml" 597 2992 play --expect two-rows | grep -c 'FAIL dock: layout one-row, expected two-rows')" 1
# Two controls overlapping (the one-row transport running into the Stepper).
{ printf '<hierarchy><node bounds="[0,0][1344,2992]">'
  icon 'back paragraph' 20 2500; icon 'previous sentence' 205 2500
  printf '<node content-desc="play" bounds="[390,2515][760,2664]"><node text="Play" bounds="[480,2540][670,2640]"/></node>'
  icon 'next sentence' 900 2500; icon slower 960 2500; icon faster 1150 2500
  printf '<node text="1.0x" bounds="[1000,2400][1100,2480]"/></node></hierarchy>'; } > "$T/d.xml"
check "overlapping controls fail" "$(python3 "$DOCK_CHECK" "$T/d.xml" 597 2992 play | grep -c 'FAIL dock: next sentence overlaps slower')" 1
# sheet-check.py (REA-62): three rows at 597 dpi on a 1344 x 2992 screen.
SHEET_CHECK=${SHEET_CHECK:-$ROOT/scripts/sheet-check.py}
IDS=('trim menu start here' 'trim menu cut after' 'trim menu cancel')
sc() { { printf '<hierarchy><node bounds="[0,0][1344,2992]">'; "$@"; printf '</node></hierarchy>'; } > "$T/s.xml"
  python3 "$SHEET_CHECK" "$T/s.xml" 597 1344 2992 "${IDS[@]}"; }
rows3() { sheet_row "${IDS[0]}" 2000 "$@"; sheet_row "${IDS[1]}" $((2000 + ${1:-250})) "$@"; sheet_row "${IDS[2]}" $((2000 + 2 * ${1:-250})) "$@"; }
check "sheet-check passes rows that hold their labels" "$(sc rows3 | tail -1)" 'sheet: PASS'
check "a row below 48 dp fails" "$(sc rows3 150 100 | grep -c 'below 48 dp')" 3
check "a label as tall as its row fails as clipped" "$(sc rows3 179 179 | grep -c 'label clipped to its row')" 3
check "a missing row fails" "$(sc eval 'sheet_row "${IDS[0]}" 2000; sheet_row "${IDS[1]}" 2250' | grep -c 'trim menu cancel missing')" 1
check "overlapping rows fail" \
  "$(sc eval 'sheet_row "${IDS[0]}" 2000; sheet_row "${IDS[1]}" 2100; sheet_row "${IDS[2]}" 2500' | grep -c 'start here overlaps trim menu cut after')" 1
check "rows sharing a pixel row (layout rounding) pass" \
  "$(sc eval 'sheet_row "${IDS[0]}" 2000; sheet_row "${IDS[1]}" 2249; sheet_row "${IDS[2]}" 2498' | tail -1)" 'sheet: PASS'
check "a row off the bottom fails" "$(sc eval 'sheet_row "${IDS[0]}" 2000; sheet_row "${IDS[1]}" 2250; sheet_row "${IDS[2]}" 2900' | grep -c 'cancel off screen')" 1
check "a label reaching past its row fails" \
  "$(sc eval 'printf "<node resource-id=\"trim menu start here\" bounds=\"[36,2000][1308,2179]\"><node text=\"x\" bounds=\"[84,1950][900,2229]\"/></node>"; sheet_row "${IDS[1]}" 2250; sheet_row "${IDS[2]}" 2500' | grep -c 'start here label .* outside its row')" 1
check "a row with no label node fails" \
  "$(sc eval 'printf "<node resource-id=\"trim menu start here\" bounds=\"[36,2000][1308,2250]\"/>"; sheet_row "${IDS[1]}" 2250; sheet_row "${IDS[2]}" 2500' | grep -c 'start here has no label node')" 1
check "it prints no label text" "$(sc rows3 | grep -c 'text=')" 0
printf '' > "$T/e.xml"; python3 "$SHEET_CHECK" "$T/e.xml" 597 1344 2992 "${IDS[@]}" >"$T/out"; rc=$?
check "sheet-check: an empty dump is no capture, exit 2" "$rc $(cut -c1-17 "$T/out")" '2 sheet: no capture'
printf '' > "$T/e.xml"; python3 "$DOCK_CHECK" "$T/e.xml" 597 2992 play >"$T/out"; rc=$?
check "an empty dump is no capture, exit 2" "$rc $(cat "$T/out" | cut -c1-16)" '2 dock: no capture'
exit $fail
