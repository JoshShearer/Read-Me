#!/usr/bin/env bash
# Tests for device-themes.sh's display save and restore (REA-58) against a stub adb that keeps
# the phone's save file in a temp dir, and for scripts/dock-check.py on synthetic dumps. Never
# touches a real device.
set -uo pipefail
unset READ_ME_PIN_FILE DEVICE_UNLOCK_STATE
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
mkdir -p "$T/bin" "$T/repo/scripts/lib"
cat > "$T/bin/adb" <<'STUB'
#!/bin/sh
echo "$*" >> "$STUB_CALLS"
F="$STUB_DIR/saved"
case "$*" in
  "shell cat /data/local/tmp/readme-display.saved") [ -f "$F" ] && cat "$F" || exit 1 ;;
  "shell cat > /data/local/tmp/readme-display.saved") cat > "$F" ;;
  "shell rm -f /data/local/tmp/readme-display.saved") rm -f "$F" ;;
  "shell wm density")
    echo "Physical density: 480"
    [ -n "${STUB_OVERRIDE:-}" ] && echo "Override density: $STUB_OVERRIDE"; : ;;
  "shell wm density "*) exit "${STUB_WM_RC:-0}" ;;
  "shell settings get system font_scale") echo "${STUB_FS:-null}" ;;
  *) : ;;
esac
STUB
chmod +x "$T/bin/adb"
export PATH="$T/bin:$PATH" STUB_CALLS="$T/calls" STUB_DIR="$T"
cp "$ROOT/scripts/lib/device.sh" "$T/repo/scripts/lib/"
cp "$ROOT/scripts/device-themes.sh" "$T/repo/scripts/"
cd "$T/repo" && git init -q && git -c user.email=t@t -c user.name=t commit -q --allow-empty -m init
fail=0
check() { if [ "$2" = "$3" ]; then echo "ok   $1"; else echo "FAIL $1: expected $3, got $2"; fail=1; fi; }
run() { bash -c "DEVICE_THEMES_SOURCE_ONLY=1; . scripts/device-themes.sh; $1" >"$T/out" 2>&1; }
fresh() { rm -f "$T/saved"; : > "$STUB_CALLS"; unset STUB_OVERRIDE STUB_FS STUB_WM_RC; }

fresh
run display_restore
check "no save file: no wm density at exit" "$(grep -c 'wm density' "$STUB_CALLS")" 0
check "no save file: no settings write at exit" "$(grep -cE 'settings (put|delete)' "$STUB_CALLS")" 0

fresh; export STUB_FS=1.0
run 'display_set 597 1.0'
check "the save is written before the density changes" \
  "$(grep -nE 'cat > |wm density 597' "$STUB_CALLS" | cut -d: -f2 | tr '\n' '|')" \
  'shell cat > /data/local/tmp/readme-display.saved|shell wm density 597|'
check "only Override density is saved, none when absent" "$(cat "$T/saved")" 'density=none font_scale=1.0'
: > "$STUB_CALLS"
run 'display_set 597 2.0'
check "a second change keeps the first save" "$(grep -c 'cat > ' "$STUB_CALLS")" 0
check "and the saved values are the phone's own" "$(cat "$T/saved")" 'density=none font_scale=1.0'
: > "$STUB_CALLS"
run display_restore
check "restore resets an unset density" "$(grep -c 'shell wm density reset' "$STUB_CALLS")" 1
check "restore puts the saved font scale back" "$(grep -c 'settings put system font_scale 1.0' "$STUB_CALLS")" 1
check "and removes the save file" "$([ -f "$T/saved" ] && echo kept || echo gone)" gone

fresh; export STUB_OVERRIDE=420 STUB_FS=null
run 'display_set 597 2.0'
check "an override and an unset font scale are saved" "$(cat "$T/saved")" 'density=420 font_scale=null'
: > "$STUB_CALLS"
run display_restore
check "restore puts the override back" "$(grep -c 'shell wm density 420' "$STUB_CALLS")" 1
check "restore deletes an unset font scale" "$(grep -c 'settings delete system font_scale' "$STUB_CALLS")" 1

fresh; printf 'density=none font_scale=1.3\n' > "$T/saved"; export STUB_WM_RC=1
run display_restore
check "a failed restore keeps the save file" "$([ -f "$T/saved" ] && echo kept || echo gone)" kept
check "and says how to restore by hand" "$(grep -c 'display NOT restored' "$T/out")" 1

fresh; printf 'density=none font_scale=1.3\n' > "$T/saved"
run 'display_set 597 2.0'
check "a stale save file is never overwritten" "$(cat "$T/saved")" 'density=none font_scale=1.3'
check "the start of a run restores a stale save file first" \
  "$(grep -c 'display_saved)" \]; then' "$ROOT/scripts/device-themes.sh")" 1

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
dc() { dump "$@" > "$T/d.xml"; python3 "$ROOT/scripts/dock-check.py" "$T/d.xml" 597 2992 play; }
check "dock-check passes a dock that fits" "$(dc ok | tail -1)" 'dock: PASS'
check "a missing faster fails" "$(dc nofaster | grep -c 'faster missing')" 1
check "a faster with no area fails" "$(dc ok 160 -5 | grep -c 'faster has no area')" 1
check "a narrowed faster fails as clipped" "$(dc ok 160 120 | grep -c 'faster 120x179 px')" 1
check "a glyph as tall as its box fails" "$(dc ok 179 | grep -c 'slower glyph clipped')" 1
check "it prints Play's centre" "$(dc ok | grep -c '^dock: play centre 620,2589$')" 1
printf '' > "$T/e.xml"; python3 "$ROOT/scripts/dock-check.py" "$T/e.xml" 597 2992 play >"$T/out"; rc=$?
check "an empty dump is no capture, exit 2" "$rc $(cat "$T/out" | cut -c1-16)" '2 dock: no capture'
exit $fail
