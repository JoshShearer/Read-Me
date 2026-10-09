#!/usr/bin/env bash
# npm run device:continuous - R-S05 on the phone, WITHOUT clearing Read Me's data (it runs on the
# owner's own library). Installs over the current build (adb install -r, data kept), notes
# Settings' Continuous play switch and turns it on, shares three synthetic texts (T3 newest and
# short, T2 short, T1 oldest and long), checks they are the top three unread rows, opens T3 (Trim,
# then the Reader), plays it and turns the screen off. From the service's log lines (ids and
# counts only) it checks that T3 hands over to T2 and T2 to T1 with the screen off, each archived
# at its end and the next two started by the service (no JS start), then that the user's first
# open of T2 from Archive still shows Trim (R-M05, ADR 0011).
#
# Safety on the owner's library. The owner's unread items sort below T1, so the chain must never
# go past it:
#  - each test item is identified by its distinct sentence count in the service's own log line,
#    and only after the three were seen as the top three unread rows (List order is the chain's
#    order); an item that does not match stops Read Me at once;
#  - while T1 (long) plays, the script wakes the screen and turns the switch OFF through Settings
#    (the service reads it at each handover, so the chain ends after T1). That needs the owner to
#    unlock within SWITCH_DEADLINE seconds (default 45; T1 lasts well over a minute even at 4.0x).
#    With no unlock in time, Read Me is force-stopped while T1 still plays;
#  - Read Me is stopped with `am force-stop` of its own package, never a media key (another
#    app's session can hold those, REA-31). The EXIT trap does this on every exit once playback
#    has started and has not ended by itself, restores the switch and deletes the test items;
#  - every id in a playback log line is checked against the three test ids, while T1 plays and
#    again at exit; a non-test id stops Read Me and fails the run loudly.
# Cleanup needs an unlocked screen: the trap waits UNLOCK_WAIT seconds (default 180) for it, or
# says what is left; CLEANUP_ONLY=1 npm run device:continuous does only the cleanup. SCREEN_OFF=0
# keeps the screen on (Read Me in the background only), for a run nobody has to unlock.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
. scripts/lib/device.sh

SCREEN_OFF=${SCREEN_OFF:-1}
UNLOCK_WAIT=${UNLOCK_WAIT:-180}
SWITCH_DEADLINE=${SWITCH_DEADLINE:-45}
# Titles are the first paragraph (Intake.textTitle), read as a sentence of their own; the marker
# word finds them on screen. Oldest first: TITLES[0] is T1, read last.
MARK=Kestrel
TITLES=("$MARK test item one." "$MARK test item two." "$MARK test item three.")
# Body sentences per item. Distinct counts identify each item in the service's log line; T1 is
# long (about 7500 characters) so the switch can be turned off while it plays.
BODY=(150 5 3)
STATE_FILE="$PRIMARY/.claude/scratch/device-continuous.before"

FAIL=0       # any check failed
PLAYED=0     # playback of a test item was requested; Read Me may be reading
ENDED=0      # the chain ended by itself with the switch off; nothing is playing
CLEARED=0    # logcat was cleared before the shares: every playback line is this run's
SHARED=0     # test items may exist on the phone
TEST_IDS=""  # verified test item ids

# --- pure helpers (tested off the phone by scripts/tests/device-continuous.test.sh) ---

# body_text <sentences>: synthetic text, no digits, two alternating sentences.
body_text() {
  local i out=""
  for ((i = 0; i < $1; i++)); do
    if ((i % 2)); then out+=" It drops and rises again over the long grass."; else out+=" The $MARK hovers over the field for a short while."; fi
  done
  echo "${out# }"
}
# count_ok <index into BODY> <sentences logged>: the title sentence plus the body, with one of
# slack either way for a segmentation difference; the three windows never overlap.
count_ok() {
  local b=${BODY[$1]}
  [ "$2" -ge "$b" ] && [ "$2" -le $((b + 2)) ]
}
# playback_ids <log>: every item id named on a "playback ..." line: item=, a handover's from=
# and after= (a start line's from= is a sentence index, not an id).
playback_ids() {
  {
    grep -oE 'playback .*' <<<"$1" | grep -oE ' item=[0-9]+' || true
    grep -oE 'playback (continue|handover) from=[0-9]+' <<<"$1" || true
    grep -oE 'playback continue none after=[0-9]+' <<<"$1" || true
  } | grep -oE '[0-9]+$' | sort -un || true
}
# foreign_ids <log> <ids>: ids in playback lines that are not in the space-separated <ids>.
foreign_ids() {
  local id
  for id in $(playback_ids "$1"); do
    case " $2 " in *" $id "*) ;; *) echo "$id" ;; esac
  done
}
# row_titles <ui dump>: the unread List's row titles, top to bottom (from each row's delete button).
row_titles() {
  grep -oE 'content-desc="delete [^"]*"' <<<"$1" | sed -E 's/^content-desc="delete //; s/"$//' || true
}
# field <line> <name>: the number after name= in a log line.
field() { grep -oE "(^| )$2=[0-9]+" <<<"$1" | head -1 | cut -d= -f2; }

# --- phone ---

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
logs() { adb logcat -d -s ReadMe:I || true; }
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
# to_list: Read Me's List on screen (its Settings button), from whatever screen the task shows.
to_list() {
  for _ in $(seq 4); do
    home
    device_has "$(ui)" 'content-desc="settings"' && return 0
    adb shell input keyevent KEYCODE_BACK
    sleep 1
  done
  echo "FAIL: Read Me's List is not on screen"; return 1
}
unlocked() { (device_require_unlocked) >/dev/null 2>&1; }
escape() { sed 's/[.]/[.]/g' <<<"$1"; }

SWITCH='content-desc="Continuous play"'
# switch_state: "true" or "false" from Settings (the List must be on screen); leaves Settings open.
switch_state() {
  local d=""
  tap_node content-desc settings >/dev/null
  for _ in $(seq 10); do d=$(ui); device_has "$d" "$SWITCH" && break; sleep 1; done
  device_has "$d" "$SWITCH" || { echo "FAIL: no Continuous play switch in Settings" >&2; return 1; }
  if device_has "$d" "$SWITCH[^>]*checked=\"true\""; then echo true; else echo false; fi
}
set_switch() { # set_switch true|false, from the List; back on the List after
  local now
  now=$(switch_state)
  if [ "$now" != "$1" ]; then
    tap_node content-desc 'Continuous play' >/dev/null
    for _ in $(seq 10); do device_has "$(ui)" "$SWITCH[^>]*checked=\"$1\"" && break; sleep 1; done
  fi
  device_has "$(ui)" "$SWITCH[^>]*checked=\"$1\"" || { echo "FAIL: Continuous play did not turn $1"; return 1; }
  adb shell input keyevent KEYCODE_BACK
}

# stop_readme: ends playback through Read Me's own process. Not a media key: those go to whichever
# session holds them, which on the reference phone can be the TTS engine's own (REA-31).
stop_readme() {
  adb shell am force-stop "$PKG" || true
  echo "stopped Read Me (am force-stop $PKG)"
  PLAYED=0
}
# guard: Read Me must never have read a non-test item. Stops it and fails loudly if it did.
guard() {
  local bad
  [ "$CLEARED" = 1 ] || return 0
  bad=$(foreign_ids "$(logs)" "$TEST_IDS")
  [ -z "$bad" ] && return 0
  stop_readme
  echo "FAIL: NON-TEST ITEM PLAYED: id(s) $(tr '\n' ' ' <<<"$bad")- check them in the owner's List and Archive"
  FAIL=1
  return 1
}
die() { echo "FAIL: $*"; FAIL=1; exit 1; }

cleanup() {
  local fail=0 t view
  to_list || return 1
  for view in unread archive; do
    for t in "${TITLES[@]}"; do
      while device_has "$(ui)" "content-desc=\"delete $(escape "$t")\""; do
        { tap_node content-desc "delete $(escape "$t")" && tap_node text '(Delete|DELETE)'; } || { fail=1; break; }
        sleep 1
      done
    done
    [ "$view" = unread ] && { tap_node content-desc archive >/dev/null || fail=1; sleep 1; }
  done
  tap_node content-desc unread >/dev/null || true
  device_has "$(ui)" "$MARK test item" && { echo "FAIL: a test item is still on screen"; fail=1; }
  if [ -f "$STATE_FILE" ]; then
    if set_switch "$(cat "$STATE_FILE")"; then rm -f "$STATE_FILE"; else fail=1; fi
  fi
  echo "cleanup: $([ $fail -eq 0 ] && echo done || echo INCOMPLETE)"
  return $fail
}

# on_exit: runs on every exit after the slot is taken. Nonzero when anything is left wrong.
on_exit() {
  local bad=0 i
  if [ "$PLAYED" = 1 ] && [ "$ENDED" = 0 ]; then stop_readme; fi
  guard || bad=1
  if [ "$SHARED" = 1 ] || [ -f "$STATE_FILE" ]; then
    adb shell input keyevent KEYCODE_WAKEUP || true
    for ((i = 0; i < UNLOCK_WAIT; i++)); do
      unlocked && break
      [ "$i" = 0 ] && echo "unlock the phone to let the script delete its test items and restore the switch"
      sleep 1
    done
    if unlocked; then
      cleanup || bad=1
    else
      echo "CLEANUP NOT DONE: the phone stayed locked. '$MARK test item' entries may be in the List or"
      echo "Archive, and Continuous play may be on. Unlock, then: CLEANUP_ONLY=1 npm run device:continuous"
      bad=1
    fi
  fi
  adb shell rm -f /sdcard/readme-ui.xml || true
  return $bad
}
# The EXIT trap: on_exit's result decides the exit code too, then the lib releases the slot.
finish() {
  local rc=$?
  # errexit off: a failing step here must not skip the slot's release below.
  set +e
  on_exit || rc=1
  [ "$FAIL" = 0 ] || [ "$rc" != 0 ] || rc=1
  echo "device:continuous $([ "$rc" = 0 ] && echo PASS || echo FAIL)"
  (exit "$rc")
  _device_exit
}

[ "${DEVICE_CONTINUOUS_SOURCE_ONLY:-0}" = 1 ] && return 0

device_take continuous
trap finish EXIT
device_require_unlocked

if [ "${CLEANUP_ONLY:-0}" = 1 ]; then
  SHARED=1
  exit 0 # the trap cleans up
fi

device_install_release # adb install -r: the owner's data stays
to_list
mkdir -p "$PRIMARY/.claude/scratch"
before=$(switch_state)
adb shell input keyevent KEYCODE_BACK
echo "$before" > "$STATE_FILE"
echo "Continuous play was: $before"
set_switch true

adb logcat -c
CLEARED=1
SHARED=1
# Oldest first, so List order (newest first) is T3, T2, T1. Text on stdin, never in the adb argv,
# which adbd logs (AGENTS.md, SPIKE-01).
for k in 0 1 2; do
  text="${TITLES[$k]}

$(body_text "${BODY[$k]}")"
  printf '%s\n' "am start -W -n $PKG/.ShareActivity -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT $(printf '%q' "$text")" \
    | adb shell >/dev/null
  sleep 1
done
to_list

# The chain's order is List order: with the three as the top three rows, T3's next is T2 and
# T2's is T1. Anything else and nothing is played.
rows=$(row_titles "$(ui)" | head -3 | tr '\n' '|')
[ "$rows" = "${TITLES[2]}|${TITLES[1]}|${TITLES[0]}|" ] \
  || die "the three test items are not the top three unread rows; nothing played"

tap_node text "$(escape "${TITLES[2]}")"
tap_node content-desc 'trim done'
PLAYED=1
tap_node content-desc play
line=$(wait_log 'playback start item=[0-9]+ sentences=[0-9]+' 20) || die "playback did not start"
id3=$(field "$line" item)
count_ok 2 "$(field "$line" sentences)" || die "the started item ($id3) does not have T3's sentence count"
TEST_IDS="$id3"
if [ "$SCREEN_OFF" = 1 ]; then adb shell input keyevent KEYCODE_SLEEP; else adb shell input keyevent KEYCODE_HOME; fi

# next_test <from id> <BODY index>: the service's continue line from <from> must name the
# expected test item, or Read Me is stopped before it reads further. Sets NEXT_ID (no subshell, so
# die's exit and FAIL reach the main shell).
next_test() {
  local line id
  line=$(wait_log "playback continue from=$1 item=[0-9]+ sentences=[0-9]+ start=[0-9]+" 300) \
    || die "no handover from item $1"
  id=$(field "$line" item)
  if ! count_ok "$2" "$(field "$line" sentences)" || [ "$(field "$line" start)" != 0 ] || [ "$id" -ge "$1" ]; then
    stop_readme
    die "the item after $1 ($id) is not the expected test item; stopped Read Me"
  fi
  NEXT_ID=$id
}
next_test "$id3" 1
id2=$NEXT_ID
TEST_IDS+=" $id2"
next_test "$id2" 0
id1=$NEXT_ID
TEST_IDS+=" $id1"
t1_at=$(date +%s)
echo "test items (List order): $id3 $id2 $id1; T1 is playing"

# T1 plays: turn the switch off before it ends, so the chain stops after it.
adb shell input keyevent KEYCODE_WAKEUP
echo "unlock the phone within ${SWITCH_DEADLINE}s: the script turns Continuous play off before the last test item ends"
until unlocked; do
  guard || exit 1
  if [ $(($(date +%s) - t1_at)) -ge "$SWITCH_DEADLINE" ]; then
    stop_readme
    die "NOT COMPLETED: no unlock within ${SWITCH_DEADLINE}s; Read Me stopped while the last test item played"
  fi
  sleep 1
done
to_list
set_switch false || { stop_readme; die "could not turn Continuous play off; stopped Read Me"; }
guard || exit 1
echo "Continuous play is off; T1 reads to its end"

wait_log "playback finished item=$id1\b" 900 >/dev/null || die "item $id1 did not finish"
sleep 3
log=$(logs)
device_has "$log" "playback continue from=$id1 " && { stop_readme; die "the chain went on after item $id1 with the switch off"; }
ENDED=1
guard || exit 1

for id in $id3 $id2 $id1; do
  device_has "$log" "playback finished item=$id\b" || { echo "FAIL: item $id did not finish"; FAIL=1; }
done
# T2 and T1 were started by the service, not by JS (a JS start logs "playback start").
for id in $id2 $id1; do
  device_has "$log" "playback start item=$id " && { echo "FAIL: item $id was started from JS"; FAIL=1; }
done
grep -E 'playback (start|finished|continue|handover|paused|resume refused|engine)' <<<"$log" | sed 's/^/  /' || true
grep -oE 'playback handover from=[0-9]+ item=[0-9]+ ms=[0-9]+' <<<"$log" | sed 's/^/  information (not an R-M07 gap): /' || true
grep -oE 'playback continue from=[0-9]+ item=[0-9]+ .*ms=[0-9]+' <<<"$log" | sed 's/^/  information (next item built): /' || true
all=$(adb logcat -d)
device_has "$all" "$MARK|hovers over the field|long grass" && { echo "FAIL: a log line carries item text"; FAIL=1; }
pid=$(adb shell pidof "$PKG" 2>/dev/null | tr -d '\r' || true)
if device_crash_seen "${pid%% *}" <<<"$(adb logcat -d -b crash,main)"; then echo "FAIL: crash logged"; FAIL=1; fi

# R-M05, ADR 0011: the user's own first open of an item read by continuous play shows Trim.
to_list
tap_node content-desc archive >/dev/null
tap_node text "$(escape "${TITLES[1]}")"
if on_screen 'content-desc="trim done"'; then echo "Trim on the first open of T2 from Archive: PASS"; else FAIL=1; fi
adb shell input keyevent KEYCODE_BACK
exit 0 # the trap checks ids once more, cleans up and sets the exit code
