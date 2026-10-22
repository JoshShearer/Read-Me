#!/usr/bin/env bash
# npm run device:themes - every screen in system light mode and in dark mode, with contrast
# measured (REA-22). Clears Read Me's data, shares two texts written for this, then in each
# mode captures List, Trim with a paragraph cut, the Reader before play (sentence controls
# disabled) and while it speaks (the highlight), Settings and Licenses. Each capture is a
# screenshot and a UI dump; scripts/theme-contrast.py measures every text node and the status
# bar from them. SystemUI demo mode fixes the status bar's contents for the run; the phone's
# night mode and demo mode are restored on exit. Captures stay in .claude/scratch/themes/.
#
# REA-58: a narrow pass after light and dark (THEMES_ONLY=light|dark|narrow runs one pass). It
# first checks the idle Reader's dock at the phone's own density and font scale (the `wide`
# capture: one row asserted on the reference phone at 448 dp and font scale 1.0). Then the
# display is set to 360 dp wide (wm density 597 on the 1344 px reference phone; NARROW_DENSITY)
# with font scale 1.0, then 2.0, the largest Settings offers; the app is restarted after each
# change (MainActivity is recreated on a density or font change). Each time the idle Reader's
# dock is checked by scripts/dock-check.py (two rows asserted); at 2.0 Play is tapped, the
# playing Reader captured, and Pause is proven to sit where Play was by tapping Play's recorded
# centre and reading `playback paused` in logcat (a dump of the playing Reader can come back
# empty, REA-56).
# Before the first change the phone's own override density and font_scale are written to
# /data/local/tmp/readme-display.saved on the phone; every exit restores from that file and
# deletes it only when both writes succeeded, and with no file nothing on the display is
# touched. A run saves at most once, and an adb failure reading the file is never taken for "no
# file". When a restore this run needs cannot read or write, it prints the commands below on
# stderr and the run fails. A file left by a dead run is restored at the next start of
# device:themes.
# A run killed without its EXIT trap (SIGKILL, the host going down, USB lost) cannot restore
# from the host, and the density and font_scale outlive a reboot. So before the first change a
# watchdog starts ON THE PHONE (display_phone_sh, detached with nohup and setsid so it outlives
# the adb session): it restores from the same save file once DISPLAY_WATCHDOG_SECS (default 360)
# have passed by the phone's clock, unless the run cancels it after a confirmed restore. The
# host restore and the watchdog run the same phone-side restore (display_phone_sh). By hand:
#   adb shell cat /data/local/tmp/readme-display.saved
#   adb shell wm density reset        (or wm density <saved>)
#   adb shell settings put system font_scale <saved>   (or settings delete system font_scale)
#   adb shell rm /data/local/tmp/readme-display.saved

DISPLAY_SAVE=/data/local/tmp/readme-display.saved
DISPLAY_WATCH=/data/local/tmp/readme-display.watchdog     # the live watchdog's id: removing it cancels
DISPLAY_WATCH_SH=/data/local/tmp/readme-display-watchdog.sh
DISPLAY_WATCHDOG_SECS=${DISPLAY_WATCHDOG_SECS:-360}  # a narrow pass takes about 2 min
DISPLAY_SAVED_BY_US=0  # 1 once this run saved (or adopted a stale save): it never saves again
DISPLAY_CHANGED=0      # 1 from this run's first display write until a restore succeeds
DISPLAY_WATCHDOG=      # this run's watchdog id, once it runs on the phone
# The saved values, "density=<n|none> font_scale=<x|null>"; nothing when no file is there.
# Returns 1 when adb failed or the answer is not one of those two: "unknown" is never "absent",
# or a blip could make display_save overwrite the owner's values with this run's own (REA-58).
display_saved() {
  local out
  out=$(adb shell "if [ -f $DISPLAY_SAVE ]; then cat $DISPLAY_SAVE; else echo absent; fi" 2>/dev/null) || return 1
  out=$(tr -d '\r' <<<"$out")
  [ "$out" = absent ] && return 0
  grep -qE '^density=([0-9]+|none) font_scale=([0-9.]+|null)$' <<<"$out" || return 1
  printf '%s\n' "$out"
}
# display_cmds [density font_scale]: the manual restore, on stderr.
display_cmds() {
  if [ $# = 2 ]; then
    echo "  adb shell wm density $([ "$1" = none ] && echo reset || echo "$1")" >&2
    echo "  adb shell settings $([ "$2" = null ] && echo 'delete system font_scale' || echo "put system font_scale $2")" >&2
  else
    echo "  adb shell cat $DISPLAY_SAVE   (density=<n|none> font_scale=<x|null>)" >&2
    echo "  adb shell wm density reset   (or wm density <n>)" >&2
    echo "  adb shell settings put system font_scale <x>   (or settings delete system font_scale)" >&2
  fi
  echo "  adb shell rm $DISPLAY_SAVE $DISPLAY_WATCH" >&2
}
display_warn() {
  echo "WARNING: display NOT restored; this run changed it (wm density, font_scale). By hand:" >&2
  display_cmds "$@"
}
# The phone side, in POSIX sh (the phone's mksh and the test's sh): the one restore, used by the
# host's display_restore and by the watchdog. restore_display deletes the save file only after
# both writes succeed and prints "display-restore ok" only then; with no file it does nothing.
display_phone_sh() {
  printf 'S=%s W=%s\n' "$DISPLAY_SAVE" "$DISPLAY_WATCH"
  cat <<'PHONE'
restore_display() {
  [ -f "$S" ] || { echo display-restore none; return 0; }
  l=$(cat "$S") || return 1
  d=${l#density=}; d=${d%% *}; f=${l##* font_scale=}
  case "$l" in "density=$d font_scale=$f") ;; *) return 1 ;; esac
  case "$d" in none) ;; ''|*[!0-9]*) return 1 ;; esac
  case "$f" in null) ;; ''|*[!0-9.]*) return 1 ;; esac
  ok=1
  if [ "$d" = none ]; then wm density reset >/dev/null 2>&1 || ok=0
  else wm density "$d" >/dev/null 2>&1 || ok=0; fi
  if [ "$f" = null ]; then settings delete system font_scale >/dev/null 2>&1 || ok=0
  else settings put system font_scale "$f" >/dev/null 2>&1 || ok=0; fi
  [ "$ok" = 1 ] && rm -f "$S" && echo display-restore ok
}
# watchdog <id> <seconds>: by the phone's wall clock, because sleep's clock stops while the phone
# is suspended. It polls each second; $W no longer holding <id> is a cancel (a restore removed
# it, or a later run wrote its own id).
watchdog() {
  end=$(( $(date +%s) + $2 ))
  while [ "$(cat "$W" 2>/dev/null)" = "$1" ]; do
    if [ "$(date +%s)" -ge "$end" ]; then restore_display; rm -f "$W"; return; fi
    sleep 1
  done
}
PHONE
}
# display_phone <command>: the phone-side functions and the command, on adb's stdin. The first
# line names the step (the stub adb's call log shows it).
display_phone() {
  { echo "# readme-display: $1"; display_phone_sh; echo "$1"; } | adb shell
}
# Starts the phone-side watchdog once per run, before the first display change. nohup and setsid
# detach it from the adb session, so it outlives this script: on the reference phone (2026-10-21,
# build 73ac95e) a narrow run killed with SIGKILL as a whole process group, adb sessions included,
# left the watchdog running with PPID 1, and it restored density and font_scale and removed the
# save file at its 20 s deadline (DISPLAY_WATCHDOG_SECS=20). Its id goes into $DISPLAY_WATCH
# first, which also cancels a dead run's watchdog.
display_watchdog_start() {
  local id out
  [ -z "$DISPLAY_WATCHDOG" ] || return 0
  id="$$-$(date +%s)"
  out=$({ echo "# readme-display: watchdog start"
    echo "cat > $DISPLAY_WATCH_SH <<'READ_ME_PHONE'"
    display_phone_sh
    echo 'watchdog "$1" "$2"'
    echo READ_ME_PHONE
    echo "echo $id > $DISPLAY_WATCH"
    echo "nohup setsid sh $DISPLAY_WATCH_SH $id $DISPLAY_WATCHDOG_SECS </dev/null >/dev/null 2>&1 &"
    echo 'p=$!; sleep 1; kill -0 $p 2>/dev/null && echo "display-watchdog started $p"'
  } | adb shell 2>/dev/null) || true
  if ! grep -qE '^display-watchdog started [0-9]+$' <<<"$(tr -d '\r' <<<"$out")"; then
    echo "FAIL: the phone-side display watchdog did not start; display not changed" >&2
    adb shell rm -f "$DISPLAY_WATCH" >/dev/null 2>&1 || true
    return 1
  fi
  DISPLAY_WATCHDOG=$id
  echo "display watchdog on the phone: restores in $DISPLAY_WATCHDOG_SECS s unless this run restores first"
}
# After a confirmed restore. The watchdog exits within a second of its id file going.
display_watchdog_cancel() {
  if adb shell rm -f "$DISPLAY_WATCH" "$DISPLAY_WATCH_SH"; then
    [ -z "$DISPLAY_WATCHDOG" ] || echo "display watchdog cancelled"
    DISPLAY_WATCHDOG=
  else
    echo "WARNING: the phone's display watchdog was not cancelled; with no save file it changes nothing" >&2
  fi
}
# Puts the saved display back. Runs on every exit with errexit off (lib/device.sh calls the hook
# as `hook || true`), so every step is checked explicitly. Returns 1, with the manual commands
# when this run changed the display, if it cannot.
display_restore() {
  local saved d f out
  if ! saved=$(display_saved); then
    [ "$DISPLAY_CHANGED" = 0 ] || display_warn
    return 1
  fi
  if [ -z "$saved" ]; then
    [ "$DISPLAY_CHANGED" = 0 ] && return 0
    display_warn; return 1
  fi
  d=$(sed -E 's/^density=([^ ]+) .*/\1/' <<<"$saved")
  f=$(sed -E 's/.* font_scale=(.+)$/\1/' <<<"$saved")
  out=$(display_phone restore_display 2>/dev/null) || true
  if grep -q '^display-restore ok$' <<<"$(tr -d '\r' <<<"$out")"; then
    DISPLAY_CHANGED=0 DISPLAY_SAVED_BY_US=0
    echo "display restored (density $d, font_scale $f)"
    display_watchdog_cancel
  else
    display_warn "$d" "$f"
    return 1
  fi
}
# The start of a run: a save file left by a dead run is restored before anything else. An adb
# failure reading it, or a garbled file, is an error, not "no file".
display_recover() {
  local saved
  if ! saved=$(display_saved); then
    echo "FAIL: could not read $DISPLAY_SAVE (adb failed, or the file is garbled); display not changed." >&2
    echo "  If the display is not the phone's own, restore it by hand, then remove the file:" >&2
    display_cmds
    return 1
  fi
  [ -n "$saved" ] || return 0
  echo "an earlier run left the display changed; restoring it first"
  display_restore
}
# Saves the phone's own values once per run (only Override density counts: Physical is not a
# setting). A save file already there holds an earlier run's originals and is kept.
display_save() {
  local saved out d f line
  [ "$DISPLAY_SAVED_BY_US" = 1 ] && return 0
  saved=$(display_saved) || { echo "FAIL: could not read $DISPLAY_SAVE over adb; display not changed" >&2; return 1; }
  if [ -n "$saved" ]; then DISPLAY_SAVED_BY_US=1; return 0; fi
  out=$(adb shell wm density) || { echo "FAIL: wm density did not answer; display not changed" >&2; return 1; }
  grep -q 'Physical density:' <<<"$out" || { echo "FAIL: wm density answered oddly; display not changed" >&2; return 1; }
  d=$(tr -d '\r' <<<"$out" | sed -nE 's/^Override density: ([0-9]+)$/\1/p')
  f=$(adb shell settings get system font_scale) || { echo "FAIL: font_scale did not answer; display not changed" >&2; return 1; }
  f=$(tr -d '\r' <<<"$f")
  grep -qE '^[0-9.]+$' <<<"$f" || f=null
  line="density=${d:-none} font_scale=$f"
  printf '%s\n' "$line" | adb shell "cat > $DISPLAY_SAVE" || true
  # The read-back, not the write's status, decides: a short or failed write must stop here.
  [ "$(display_saved 2>/dev/null)" = "$line" ] || { echo "FAIL: could not save the display settings; display not changed" >&2; return 1; }
  DISPLAY_SAVED_BY_US=1
}
# display_set <density> <font scale>: the watchdog, then the save, then the change. The watchdog
# goes first so that no window has a save file and a dead run's watchdog still holding $W.
display_set() {
  display_watchdog_start || return 1
  display_save || { display_watchdog_cancel; return 1; }
  DISPLAY_CHANGED=1
  adb shell wm density "$1" >/dev/null && adb shell settings put system font_scale "$2" >/dev/null
}
demo() { printf '%s\n' "am broadcast -a com.android.systemui.demo $*" | adb shell >/dev/null; }
# The EXIT hook (DEVICE_ON_EXIT). The display goes first: it is the one change that outlives a
# reboot.
restore() {
  display_restore || true
  demo -e command exit
  [ -z "${night:-}" ] || adb shell cmd uimode night "$night" >/dev/null || true
  adb shell rm -f /sdcard/readme-ui.xml
}
# The stub-adb test sources the script for the functions above alone.
if [ -n "${DEVICE_THEMES_SOURCE_ONLY:-}" ]; then return 0; fi

set -euo pipefail
cd "$(dirname "$0")/.."
. scripts/lib/device.sh
device_take themes
device_require_unlocked
device_install_release

RAW=.claude/scratch/themes  # gitignored
rm -rf "$RAW"; mkdir -p "$RAW"

display_recover || exit 1
NARROW_DENSITY=${NARROW_DENSITY:-597}
THEMES_ONLY=${THEMES_ONLY:-}
case "$THEMES_ONLY" in ''|light|dark|narrow) ;; *) echo "THEMES_ONLY must be light, dark or narrow"; exit 2 ;; esac
night=$(adb shell cmd uimode night | tr -d '\r' | awk '{print $NF}')
DEVICE_ON_EXIT=restore
adb shell settings put global sysui_demo_allowed 1
demo -e command enter
demo -e command clock -e hhmm 0930
demo -e command battery -e level 100 -e plugged false
demo -e command network -e wifi show -e level 4 -e mobile hide
demo -e command notifications -e visible false

# Long enough that playback cannot reach the end before Pause: finishing archives the item
# (R-M11), which took Pause off the screen and the item off the list for the dark pass (REA-28,
# twice on 2026-10-03 once the Reader dumped more slowly). On 2026-10-15 the five sentences
# (about 9 s at 2x) ended 39 ms after Pause: a dump can wait about 12 s for the playing Reader to
# go idle (REA-56), so the last paragraph now runs on for about a minute.
FIRST='Light and dark both have to read well.

This second paragraph gets cut in Trim, so its faint style is measured too.

The last paragraph is plain text again, long enough to wrap onto a second line on the phone. It goes on for a while, because reading it to the end archives the item, and the dark pass opens it again. A few more sentences keep it playing while the screen is captured and Pause is pressed. The playback stops well before here. Filler sentence one is here only to keep the reading going. Filler sentence two is here only to keep the reading going. Filler sentence three is here only to keep the reading going. Filler sentence four is here only to keep the reading going. Filler sentence five is here only to keep the reading going. Filler sentence six is here only to keep the reading going. Filler sentence seven is here only to keep the reading going. Filler sentence eight is here only to keep the reading going. Filler sentence nine is here only to keep the reading going. Filler sentence ten is here only to keep the reading going. Filler sentence eleven is here only to keep the reading going. Filler sentence twelve is here only to keep the reading going. Filler sentence thirteen is here only to keep the reading going. Filler sentence fourteen is here only to keep the reading going. Filler sentence fifteen is here only to keep the reading going. Filler sentence sixteen is here only to keep the reading going. Filler sentence seventeen is here only to keep the reading going. Filler sentence eighteen is here only to keep the reading going. Filler sentence nineteen is here only to keep the reading going. Filler sentence twenty is here only to keep the reading going.'
SECOND='A second item keeps the list from looking empty. It has one paragraph.'

device_clear_app
adb logcat -c
share() {
  # The text goes on stdin, not the adb argv, which adbd logs (AGENTS.md, SPIKE-01).
  printf '%s\n' "am start -W -n $PKG/.ShareActivity -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT $(printf '%q' "$1")" \
    | adb shell >/dev/null
  sleep 1
}
share "$SECOND"
share "$FIRST"

ui() {
  adb shell uiautomator dump /sdcard/readme-ui.xml >/dev/null 2>&1 || true
  adb shell cat /sdcard/readme-ui.xml 2>/dev/null || true
}
tap_node() {
  local b=""
  for _ in $(seq 10); do
    b=$(ui | grep -oE "$1=\"$2\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" | head -1 \
      | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' | grep -oE '[0-9]+' | tr '\n' ' ' || true)
    [ -n "$b" ] && break
    sleep 1
  done
  [ -n "$b" ] || { echo "FAIL: nothing on screen has $1 matching $2"; exit 1; }
  set -- $b
  adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
}
# Swipe up the middle of the screen, from 80% to 30% of its height, so the scroll works on the
# reference phone and on a tablet alike (the last line of `wm size` is the size in use).
read -r SCREEN_W SCREEN_H < <(adb shell wm size | tail -1 | grep -oE '[0-9]+x[0-9]+' | tr x ' ')
# A node scrolled out of view is still in the dump, with bounds whose top is below its bottom
# (Settings' Licenses, [0,2983][1344,2920], 2026-10-15): it counts only when it lies on screen,
# above the bottom tenth (the gesture bar).
desc_on_screen() {
  local b
  b=$(grep -oE "content-desc=\"$1\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" <<<"$2" | head -1 \
    | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' | grep -oE '[0-9]+' | tr '\n' ' ')
  set -- $b
  [ $# = 4 ] && [ "$2" -lt "$4" ] && [ "$4" -le $((SCREEN_H * 9 / 10)) ]
}
scroll_to_desc() {
  for _ in $(seq 6); do
    desc_on_screen "$1" "$(ui)" && return 0
    adb shell input swipe $((SCREEN_W / 2)) $((SCREEN_H * 8 / 10)) $((SCREEN_W / 2)) $((SCREEN_H * 3 / 10)) 300
    sleep 1
  done
}
wait_for() {
  for _ in $(seq "${2:-15}"); do device_has "$(ui)" "$1" && return 0; sleep 1; done
  echo "FAIL: '$1' not on screen (screen at failure: $RAW/fail.png)"
  adb exec-out screencap -p > "$RAW/fail.png"
  exit 1
}
# shot <name>: the screenshot and the dump it is measured against, taken back to back.
# Every later `adb logcat -c` clears the crash buffer too, so the log is checked first (a source
# check in scripts/tests/device-themes.test.sh holds this): the crash gate at the end then still
# covers the run from the first clear after device_clear_app.
crashed=0
crash_check() {
  if adb logcat -d | device_crash_seen; then echo "FAIL: a crash in the log ($1)"; crashed=1; fi
}
shot() {
  sleep 1
  adb exec-out screencap -p > "$RAW/$1.png"
  ui > "$RAW/$1.xml"
  echo "captured $1"
}

for mode in light dark; do
  [ -z "$THEMES_ONLY" ] || [ "$THEMES_ONLY" = "$mode" ] || continue
  [ "$mode" = light ] && adb shell cmd uimode night no >/dev/null || adb shell cmd uimode night yes >/dev/null
  adb shell am force-stop "$PKG"
  adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
  wait_for 'Light and dark both'
  shot "$mode-1-list"
  tap_node text 'Light and dark both[^"]*'
  if [ "$mode" = light ]; then
    wait_for 'content-desc="trim done"'   # Trim opens by itself on the first open
  else
    wait_for 'content-desc="trim"'
    tap_node content-desc 'trim'
    wait_for 'content-desc="trim done"'
  fi
  device_has "$(ui)" 'resource-id="paragraph 2 cut"' || tap_node resource-id 'paragraph 2'
  wait_for 'resource-id="paragraph 2 cut"'
  shot "$mode-2-trim"
  tap_node content-desc 'trim done'
  wait_for 'content-desc="play"'
  shot "$mode-3-reader"
  crash_check "before the $mode pass's Play"
  adb logcat -c
  tap_node content-desc 'play'
  for _ in $(seq 40); do device_has "$(adb logcat -d -s ReadMe:I)" 'playback start item=' && break; sleep 1; done
  sleep 1
  shot "$mode-4-reading"
  tap_node content-desc 'pause'
  adb shell input keyevent KEYCODE_BACK
  tap_node content-desc 'settings'
  wait_for 'content-desc="voice, [^"]+"'
  shot "$mode-5-settings"
  # REA-28: the voice Select's bottom sheet, over its scrim.
  tap_node content-desc 'voice, [^"]+'
  wait_for 'content-desc="close voice"'
  shot "$mode-5b-voice-sheet"
  adb shell input keyevent KEYCODE_BACK
  for _ in $(seq 10); do device_has "$(ui)" 'content-desc="close voice"' || break; sleep 1; done
  scroll_to_desc 'licenses'
  tap_node content-desc 'licenses'
  wait_for 'text="(MIT|Apache-2.0|ISC|BSD-3-Clause)"'
  shot "$mode-6-licenses"
  adb shell input keyevent KEYCODE_BACK
  adb shell input keyevent KEYCODE_BACK
done

# REA-58: the narrow pass, light mode. Opens the Reader from wherever the restarted app is:
# the List, Trim (the first open of an item, THEMES_ONLY=narrow) or the Reader itself.
open_reader() {
  adb shell am force-stop "$PKG"
  adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
  local screen=""
  for _ in $(seq 20); do
    screen=$(ui)
    device_has "$screen" 'content-desc="(play|trim done)"|Light and dark both' && break
    sleep 1
  done
  if ! device_has "$screen" 'content-desc="(play|trim done)"'; then
    tap_node text 'Light and dark both[^"]*'
    for _ in $(seq 15); do
      screen=$(ui); device_has "$screen" 'content-desc="(play|trim done)"' && break; sleep 1
    done
  fi
  if device_has "$screen" 'content-desc="trim done"'; then tap_node content-desc 'trim done'; fi
  wait_for 'content-desc="play"'
}
dock_fail=0
display_fail=0
sheet_fail=0
# long_press <attr> <value>: a 1 s press at the centre of the first node matching, as tap_node.
long_press() {
  local b=""
  for _ in $(seq 10); do
    b=$(ui | grep -oE "$1=\"$2\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" | head -1 \
      | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' | grep -oE '[0-9]+' | tr '\n' ' ' || true)
    [ -n "$b" ] && break
    sleep 1
  done
  [ -n "$b" ] || { echo "FAIL: nothing on screen has $1 matching $2"; exit 1; }
  set -- $b
  adb shell input swipe $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 )) $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 )) 1000
}
# dock <capture> <play|pause> <one-row|two-rows|any> [density]: the checker's lines, labels and
# bounds only.
dock() {
  local rc=0 dpi=${4:-$NARROW_DENSITY} expect=()
  [ "$3" = any ] || expect=(--expect "$3")
  python3 scripts/dock-check.py "$RAW/$1.xml" "$dpi" "$SCREEN_H" "$2" "${expect[@]}" | sed "s/^/$1 /" || true
  python3 scripts/dock-check.py "$RAW/$1.xml" "$dpi" "$SCREEN_H" "$2" "${expect[@]}" >/dev/null || rc=$?
  [ "$rc" = 0 ] || dock_fail=1
  return 0
}
if [ -z "$THEMES_ONLY" ] || [ "$THEMES_ONLY" = narrow ]; then
  adb shell cmd uimode night no >/dev/null
  # The phone's own layout first (no display change). At font scale 1.0 (or unset) and 429 dp
  # or more the dock must be one row (model.dockOneRow: 329 + 100 x font scale); the reference
  # phone is 448 dp. Another phone or font scale is captured and checked, its layout not asserted.
  own=$(adb shell wm density | tr -d '\r')
  own_dpi=$(sed -nE 's/^Override density: ([0-9]+)$/\1/p' <<<"$own")
  [ -n "$own_dpi" ] || own_dpi=$(sed -nE 's/^Physical density: ([0-9]+)$/\1/p' <<<"$own")
  own_fs=$(adb shell settings get system font_scale | tr -d '\r')
  wide_dp=$((SCREEN_W * 160 / own_dpi))
  wide_expect=any
  case "$own_fs" in 1.0|1|null) [ "$wide_dp" -lt 429 ] || wide_expect=one-row ;; esac
  echo "wide: $wide_dp dp wide (density $own_dpi, font_scale $own_fs; layout $wide_expect)"
  open_reader
  shot wide-1-reader
  dock wide-1-reader play "$wide_expect" "$own_dpi"
  echo "narrow: $((SCREEN_W * 160 / NARROW_DENSITY)) dp wide (wm density $NARROW_DENSITY)"
  echo "NOTE: the display changes now (wm density $NARROW_DENSITY, font_scale 1.0 then 2.0). If this run ends" \
    "without 'display restored', a watchdog on the phone restores it within $DISPLAY_WATCHDOG_SECS s; if not, by hand:" >&2
  display_cmds
  display_set "$NARROW_DENSITY" 1.0
  open_reader
  shot narrow-1-reader
  dock narrow-1-reader play two-rows
  display_set "$NARROW_DENSITY" 2.0
  open_reader
  shot narrow-3-reader
  dock narrow-3-reader play two-rows
  # REA-62: Trim's long-press sheet at 360 dp and font scale 2.0. Each row must hold its label
  # (a wrapped "Cut everything after this" must grow the row, not spill out of it). Cancel
  # closes it without a change, and Trim's Done goes back to the Reader for the Play check.
  tap_node content-desc 'trim'
  wait_for 'content-desc="trim done"'
  long_press resource-id 'paragraph 1'
  wait_for 'resource-id="trim menu cancel"'
  shot narrow-5-trim-sheet
  python3 scripts/sheet-check.py "$RAW/narrow-5-trim-sheet.xml" "$NARROW_DENSITY" "$SCREEN_W" "$SCREEN_H" \
    'trim menu start here' 'trim menu cut after' 'trim menu cancel' | sed 's/^/narrow-5-trim-sheet /' \
    || sheet_fail=1
  tap_node resource-id 'trim menu cancel'
  sleep 1
  tap_node content-desc 'trim done'
  wait_for 'content-desc="play"'
  centre=$(python3 scripts/dock-check.py "$RAW/narrow-3-reader.xml" "$NARROW_DENSITY" "$SCREEN_H" play \
    | sed -nE 's/^dock: play centre ([0-9]+),([0-9]+)$/\1 \2/p' || true)
  if [ -z "$centre" ]; then
    echo "narrow: FAIL no Play centre to tap; Pause's place not checked"
    dock_fail=1
  else
    crash_check "the narrow pass, before Play"
    adb logcat -c
    adb shell input tap $centre
    for _ in $(seq 40); do device_has "$(adb logcat -d -s ReadMe:I)" 'playback start item=' && break; sleep 1; done
    sleep 1
    shot narrow-4-reading
    if grep -q '<node' "$RAW/narrow-4-reading.xml"; then
      dock narrow-4-reading pause two-rows
      pc=$(python3 scripts/dock-check.py "$RAW/narrow-4-reading.xml" "$NARROW_DENSITY" "$SCREEN_H" pause \
        | sed -nE 's/^dock: pause centre ([0-9]+),([0-9]+)$/\1 \2/p' || true)
      set -- $centre $pc
      if [ $# = 4 ] && [ $(( $1 > $3 ? $1 - $3 : $3 - $1 )) -le 2 ] && [ $(( $2 > $4 ? $2 - $4 : $4 - $2 )) -le 2 ]; then
        echo "narrow: Pause's centre ($pc) is Play's ($centre)"
      else
        echo "narrow: FAIL Pause's centre (${pc:-none}) is not Play's ($centre)"; dock_fail=1
      fi
    else echo "narrow-4-reading dock: no capture (the playing Reader's dump came back empty)"; fi
    # Pause must sit where Play was: tap Play's recorded centre and read the pause in logcat.
    crash_check "the narrow pass, before Pause"
    adb logcat -c
    adb shell input tap $centre
    paused=0
    for _ in $(seq 10); do device_has "$(adb logcat -d -s ReadMe:I)" 'playback paused item=' && { paused=1; break; }; sleep 1; done
    if [ "$paused" = 1 ]; then echo "narrow: Pause at Play's centre ($centre) paused playback"
    else
      echo "narrow: FAIL Pause did not pause: no 'playback paused' in logcat within 10 s of a tap at Play's centre ($centre); stopping Read Me"
      adb shell am force-stop "$PKG" || true
      dock_fail=1
    fi
  fi
  adb shell input keyevent KEYCODE_BACK
  display_restore || display_fail=1
fi

crash_check "the end of the run"
if [ "$crashed" = 1 ]; then echo "FAIL: a crash in the log"; exit 1; fi
contrast=0
python3 scripts/theme-contrast.py "$RAW" || contrast=1
[ "$dock_fail" = 0 ] || echo "FAIL: the Reader's dock (wide or narrow, lines above)"
[ "$sheet_fail" = 0 ] || echo "FAIL: Trim's long-press sheet at 360 dp and font scale 2.0 (lines above)"
[ "$display_fail" = 0 ] || echo "FAIL: the display was not restored (WARNING above has the commands)"
if [ "$contrast" = 0 ] && [ "$dock_fail" = 0 ] && [ "$sheet_fail" = 0 ] && [ "$display_fail" = 0 ]; then echo "device:themes PASS"; else echo "device:themes FAIL"; exit 1; fi
