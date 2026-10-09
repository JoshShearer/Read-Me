#!/usr/bin/env bash
# npm run device:continuous - R-S05 on the phone, WITHOUT clearing Read Me's data (it runs on the
# owner's own library). Installs over the current build (data kept), notes Settings' Continuous
# play switch and turns it on, shares three short synthetic texts, opens the newest (Trim, then
# the Reader), plays it, turns the screen off, and checks from the service's log lines (ids and
# counts only) that the three are read newest to oldest (List order), each archived at its end,
# the next two started by the service (no JS start, so no Trim), with no item text in any log.
#
# The owner's own unread items sort below the three test items, so the chain would go on into
# them: as soon as the service continues to an id that is not a test item, the script sends Stop
# (a media-session stop). That stop saves a position at that item's current sentence, as a user's
# Stop would. "Stops after the last" is checked only when no other unread item is left; otherwise
# it is NOT REACHED here (unit tests cover it).
#
# Cleanup needs the screen: after the chain the script wakes the phone and waits for the owner to
# unlock it (UNLOCK_WAIT seconds, default 180), then deletes the three test items and puts the
# switch back as it was. If nobody unlocks, it says so and leaves them: CLEANUP_ONLY=1 npm run
# device:continuous does only the cleanup. SCREEN_OFF=0 keeps the screen on (Read Me in the
# background only), for an unattended run that must clean up after itself.
set -euo pipefail
cd "$(dirname "$0")/.."
. scripts/lib/device.sh
device_take continuous
device_require_unlocked

SCREEN_OFF=${SCREEN_OFF:-1}
UNLOCK_WAIT=${UNLOCK_WAIT:-180}
# Titles are the first paragraph (Intake.textTitle); the marker word finds them on screen.
MARK=Kestrel
TITLES=("$MARK test item one." "$MARK test item two." "$MARK test item three.")

ui() {
  adb shell rm -f /sdcard/readme-ui.xml
  adb shell uiautomator dump /sdcard/readme-ui.xml >/dev/null 2>&1 || true
  adb shell cat /sdcard/readme-ui.xml 2>/dev/null || true
}
# tap_node <attr> <ERE>: taps the centre of the first node whose attr matches, retrying 10 s.
tap_node() {
  local b=""
  for _ in $(seq 10); do
    b=$(ui | grep -oE "$1=\"$2\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" | head -1 \
      | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' | grep -oE '[0-9]+' | tr '\n' ' ' || true)
    [ -n "$b" ] && break
    sleep 1
  done
  [ -n "$b" ] || { echo "FAIL: nothing on screen has $1 matching $2"; return 1; }
  set -- $b
  adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
}
on_screen() {
  for _ in $(seq 10); do device_has "$(ui)" "$1" && return 0; sleep 1; done
  echo "FAIL: '$1' not on screen"; return 1
}
logs() { adb logcat -d -s ReadMe:I; }
# wait_log <ERE> <seconds>: prints the first matching line.
wait_log() {
  local hit
  for _ in $(seq "$2"); do
    hit=$(logs | grep -oE "$1" | head -1 || true)
    [ -n "$hit" ] && { echo "$hit"; return 0; }
    sleep 1
  done
  return 1
}
home() { adb shell am start -W -n "$PKG/.MainActivity" >/dev/null; }

SWITCH='content-desc="Continuous play"'
# switch_state: "true" or "false" from Settings (the List must be on screen).
switch_state() {
  local d=""
  tap_node content-desc settings >/dev/null
  for _ in $(seq 10); do d=$(ui); device_has "$d" "$SWITCH" && break; sleep 1; done
  if device_has "$d" "$SWITCH[^>]*checked=\"true\""; then echo true; else echo false; fi
}
set_switch() { # set_switch true|false; leaves Settings
  local now
  now=$(switch_state)
  if [ "$now" != "$1" ]; then
    tap_node content-desc 'Continuous play' >/dev/null
    for _ in $(seq 10); do device_has "$(ui)" "$SWITCH[^>]*checked=\"$1\"" && break; sleep 1; done
  fi
  device_has "$(ui)" "$SWITCH[^>]*checked=\"$1\"" || { echo "FAIL: Continuous play did not turn $1"; return 1; }
  adb shell input keyevent KEYCODE_BACK
}

STATE_FILE="$PRIMARY/.claude/scratch/device-continuous.before"
cleanup() {
  local fail=0 t esc
  home
  for view in unread archive; do
    for t in "${TITLES[@]}"; do
      esc=$(sed 's/[.]/[.]/g' <<<"$t")
      if device_has "$(ui)" "content-desc=\"delete $esc\""; then
        tap_node content-desc "delete $esc" && tap_node text '(Delete|DELETE)' || fail=1
        sleep 1
      fi
    done
    [ "$view" = unread ] && { tap_node content-desc archive >/dev/null || fail=1; sleep 1; }
  done
  tap_node content-desc unread >/dev/null || true
  device_has "$(ui)" "$MARK test item" && { echo "FAIL: a test item is still on screen"; fail=1; }
  if [ -f "$STATE_FILE" ]; then
    set_switch "$(cat "$STATE_FILE")" || fail=1
    rm -f "$STATE_FILE"
  fi
  echo "cleanup: $([ $fail -eq 0 ] && echo done || echo INCOMPLETE)"
  return $fail
}

if [ "${CLEANUP_ONLY:-0}" = 1 ]; then
  cleanup
  exit $?
fi

device_install_release # adb install -r: the owner's data stays
home
mkdir -p "$PRIMARY/.claude/scratch"
before=$(switch_state)
adb shell input keyevent KEYCODE_BACK
echo "$before" > "$STATE_FILE"
echo "Continuous play was: $before"
set_switch true

adb logcat -c
# Oldest first, so List order (newest first) is three, two, one. Text on stdin, never in the
# adb argv, which adbd logs (AGENTS.md, SPIKE-01).
for t in "${TITLES[@]}"; do
  text="$t

The $MARK hovers over the field for a short while. It drops and rises again."
  printf '%s\n' "am start -W -n $PKG/.ShareActivity -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT $(printf '%q' "$text")" \
    | adb shell >/dev/null
  sleep 1
done
home

fail=0
three=$(sed 's/[.]/[.]/g' <<<"${TITLES[2]}")
on_screen "text=\"$three\"" || fail=1
tap_node text "$three"
tap_node content-desc 'trim done'
tap_node content-desc play
start=$(wait_log 'playback start item=[0-9]+ ' 20) || { echo "FAIL: playback did not start"; fail=1; }
id3=$(grep -oE '[0-9]+' <<<"${start:-x 0}" | head -1)
id2=$((id3 - 1)); id1=$((id3 - 2)) # ids are AUTOINCREMENT and the shares were back to back
echo "test items (newest first): $id3 $id2 $id1"
if [ "$SCREEN_OFF" = 1 ]; then adb shell input keyevent KEYCODE_SLEEP; else adb shell input keyevent KEYCODE_HOME; fi

wait_log "playback finished item=$id3\b" 120 >/dev/null || { echo "FAIL: item $id3 did not finish"; fail=1; }
wait_log "playback continue from=$id3 item=$id2 " 10 >/dev/null || { echo "FAIL: no handover $id3 -> $id2"; fail=1; }
wait_log "playback finished item=$id2\b" 120 >/dev/null || { echo "FAIL: item $id2 did not finish"; fail=1; }
wait_log "playback continue from=$id2 item=$id1 " 10 >/dev/null || { echo "FAIL: no handover $id2 -> $id1"; fail=1; }
wait_log "playback finished item=$id1\b" 120 >/dev/null || { echo "FAIL: item $id1 did not finish"; fail=1; }
after=$(wait_log "playback continue (none after=$id1|from=$id1 item=[0-9]+)" 10 || true)
case "$after" in
  *"none after"*) echo "stops after the last: PASS (no other unread item)" ;;
  *item=*)
    adb shell cmd media_session dispatch stop
    echo "stops after the last: NOT REACHED (the owner's unread item ${after##*item=} is next; sent Stop)" ;;
  *) echo "FAIL: no continue line after item $id1"; fail=1 ;;
esac
sleep 2

log=$(logs)
# The next two were started by the service, not by JS (a JS start logs "playback start"), so no
# Trim could have opened for them.
for id in $id2 $id1; do
  device_has "$log" "playback start item=$id " && { echo "FAIL: item $id was started from JS"; fail=1; }
done
grep -E 'playback (start|finished|continue|handover|paused|resume refused|engine)' <<<"$log" | sed 's/^/  /'
grep -oE 'playback handover from=[0-9]+ item=[0-9]+ ms=[0-9]+' <<<"$log" | sed 's/^/  information (not an R-M07 gap): /'
all=$(adb logcat -d)
device_has "$all" "$MARK|hovers over the field" && { echo "FAIL: a log line carries item text"; fail=1; }
pid=$(adb shell pidof "$PKG" 2>/dev/null | tr -d '\r' || true)
if device_crash_seen "${pid%% *}" <<<"$(adb logcat -d -b crash,main)"; then echo "FAIL: crash logged"; fail=1; fi

# Cleanup needs an unlocked screen.
adb shell input keyevent KEYCODE_WAKEUP
unlocked=0
for i in $(seq "$UNLOCK_WAIT"); do
  if (device_require_unlocked) >/dev/null 2>&1; then unlocked=1; break; fi
  [ "$i" = 1 ] && echo "unlock the phone to let the script delete its three test items and restore the switch"
  sleep 1
done
if [ "$unlocked" = 1 ]; then
  home
  device_has "$(ui)" 'content-desc="trim done"' && { echo "FAIL: Trim is on screen after continuous play"; fail=1; }
  cleanup || fail=1
else
  echo "CLEANUP NOT DONE: the phone stayed locked. The three '$MARK test item' entries are in Archive and"
  echo "Continuous play is on. Unlock, then: CLEANUP_ONLY=1 npm run device:continuous"
  fail=1
fi
adb shell rm -f /sdcard/readme-ui.xml
echo "device:continuous $([ $fail -eq 0 ] && echo PASS || echo FAIL)"
exit $fail
