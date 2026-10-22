#!/usr/bin/env bash
# Tests for device-themes.sh's display save and restore (REA-58) against a stub adb that keeps
# the phone's save file in a temp dir, and for scripts/dock-check.py on synthetic dumps. Never
# touches a real device.
set -uo pipefail
unset READ_ME_PIN_FILE DEVICE_UNLOCK_STATE
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
mkdir -p "$T/bin" "$T/repo/scripts/lib"
# The stub keeps the phone's state in files: the save file, the override density and font_scale.
# $STUB_DIR/down makes every call fail (adb gone); $STUB_DIR/catfail makes the next read of the
# save file fail once (an adb blip).
cat > "$T/bin/adb" <<'STUB'
#!/bin/sh
echo "$*" >> "$STUB_CALLS"
F="$STUB_DIR/saved"; O="$STUB_DIR/override"; S="$STUB_DIR/fs"
[ -f "$STUB_DIR/down" ] && exit 1
case "$*" in
  "shell if [ -f /data/local/tmp/readme-display.saved ]; then cat"*|"shell cat /data/local/tmp/readme-display.saved")
    if [ -f "$STUB_DIR/catfail" ]; then rm -f "$STUB_DIR/catfail"; exit 1; fi ;;
esac
case "$*" in
  "shell if [ -f /data/local/tmp/readme-display.saved ]; then cat"*) if [ -f "$F" ]; then cat "$F"; else echo absent; fi ;;
  "shell cat /data/local/tmp/readme-display.saved") [ -f "$F" ] && cat "$F" || exit 1 ;;
  "shell cat > /data/local/tmp/readme-display.saved") cat > "$F" ;;
  "shell rm -f /data/local/tmp/readme-display.saved") rm -f "$F" ;;
  "shell wm density")
    echo "Physical density: 480"
    [ -f "$O" ] && echo "Override density: $(cat "$O")"; : ;;
  "shell wm density reset") [ "${STUB_WM_RC:-0}" = 0 ] || exit "$STUB_WM_RC"; rm -f "$O" ;;
  "shell wm density "*) [ "${STUB_WM_RC:-0}" = 0 ] || exit "$STUB_WM_RC"; echo "$4" > "$O" ;;
  "shell settings get system font_scale") if [ -f "$S" ]; then cat "$S"; else echo null; fi ;;
  "shell settings put system font_scale "*) echo "$6" > "$S" ;;
  "shell settings delete system font_scale") rm -f "$S" ;;
  *) : ;;
esac
STUB
chmod +x "$T/bin/adb"
export PATH="$T/bin:$PATH" STUB_CALLS="$T/calls" STUB_DIR="$T"
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
  rm -f "$T/saved" "$T/override" "$T/fs" "$T/down" "$T/catfail"; : > "$STUB_CALLS"; unset STUB_WM_RC
  [ -z "${1:-}" ] || echo "$1" > "$T/override"
  [ -z "${2:-}" ] || echo "$2" > "$T/fs"
}
state() { echo "$(cat "$T/override" 2>/dev/null || echo none) $(cat "$T/fs" 2>/dev/null || echo null)"; }
writes() { grep -E 'cat > |wm density [^ ]+$|settings (put|delete)|rm -f /data' "$STUB_CALLS"; }
file() { [ -f "$T/saved" ] && echo kept || echo gone; }

fresh
run display_restore
check "no save file: no wm density at exit" "$(grep -c 'wm density' "$STUB_CALLS")" 0
check "no save file: no settings write at exit" "$(grep -cE 'settings (put|delete)' "$STUB_CALLS")" 0

fresh '' 1.0
run 'display_set 597 1.0'
check "the save is written before the density changes" \
  "$(grep -nE 'cat > |wm density 597' "$STUB_CALLS" | cut -d: -f2 | tr '\n' '|')" \
  'shell cat > /data/local/tmp/readme-display.saved|shell wm density 597|'
check "display_set's first adb write is the save" "$(writes | head -1)" 'shell cat > /data/local/tmp/readme-display.saved'
check "only Override density is saved, none when absent" "$(cat "$T/saved")" 'density=none font_scale=1.0'
: > "$STUB_CALLS"
run 'display_set 597 2.0'
check "a second change (a new process) keeps the first save" "$(grep -c 'cat > ' "$STUB_CALLS")" 0
check "and the saved values are the phone's own" "$(cat "$T/saved")" 'density=none font_scale=1.0'
: > "$STUB_CALLS"
run display_restore
check "restore resets an unset density" "$(grep -c 'shell wm density reset' "$STUB_CALLS")" 1
check "restore puts the saved font scale back" "$(grep -c 'settings put system font_scale 1.0' "$STUB_CALLS")" 1
check "and removes the save file" "$(file)" gone
check "and the phone is back to its own values" "$(state)" 'none 1.0'

fresh 420 ''
run 'display_set 597 2.0'
check "an override and an unset font scale are saved" "$(cat "$T/saved")" 'density=420 font_scale=null'
: > "$STUB_CALLS"
run display_restore
check "restore puts the override back" "$(grep -c 'shell wm density 420' "$STUB_CALLS")" 1
check "restore deletes an unset font scale" "$(grep -c 'settings delete system font_scale' "$STUB_CALLS")" 1

fresh; printf 'density=none font_scale=1.3\n' > "$T/saved"; export STUB_WM_RC=1
run display_restore
check "a failed restore keeps the save file" "$(file)" kept
check "and says how to restore by hand" "$(grep -c 'display NOT restored' "$T/err")" 1
unset STUB_WM_RC

fresh; printf 'density=none font_scale=1.3\n' > "$T/saved"
run 'display_set 597 2.0'
check "a stale save file is never overwritten" "$(cat "$T/saved")" 'density=none font_scale=1.3'

# The start of a run (display_recover), behaviourally.
fresh 597 2.0; printf 'density=none font_scale=1.3\n' > "$T/saved"
run display_recover
check "recover: a stale save is restored (density reset, font_scale 1.3)" \
  "$(writes | tr '\n' '|')" 'shell wm density reset|shell settings put system font_scale 1.3|shell rm -f /data/local/tmp/readme-display.saved|'
check "recover: the file is removed" "$(file)" gone
check "recover: and returns 0" "$(cat "$T/rc")" 0
fresh 597 2.0; printf 'density=none font_scale=1.3\n' > "$T/saved"; export STUB_WM_RC=1
run display_recover
check "recover: a failed restore returns non-zero" "$([ "$(cat "$T/rc")" != 0 ] && echo nonzero || echo zero)" nonzero
check "recover: and keeps the file" "$(file)" kept
unset STUB_WM_RC
fresh 420 1.0
run display_recover
check "recover: no file, no display writes" "$(writes | wc -l | tr -d ' ')" 0
check "recover: no file returns 0" "$(cat "$T/rc")" 0
fresh; printf 'density=none font_scale=1.3\n' > "$T/saved"; touch "$T/down"
run display_recover
check "recover: adb failing is an error, not 'no file'" "$([ "$(cat "$T/rc")" != 0 ] && echo nonzero || echo zero)" nonzero

# One run, two changes: a read of the save file that fails once must not re-save this run's values.
fresh '' 1.3
run 'display_set 597 1.0; touch "$STUB_DIR/catfail"; display_set 597 2.0'
check "a failed read on the second display_set keeps the owner's save" "$(cat "$T/saved")" 'density=none font_scale=1.3'
check "and the run saved once" "$(grep -c 'cat > ' "$STUB_CALLS")" 1

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
printf '' > "$T/e.xml"; python3 "$DOCK_CHECK" "$T/e.xml" 597 2992 play >"$T/out"; rc=$?
check "an empty dump is no capture, exit 2" "$rc $(cat "$T/out" | cut -c1-16)" '2 dock: no capture'
exit $fail
