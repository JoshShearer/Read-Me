#!/usr/bin/env bash
# npm run device:continuous - R-S05 on the phone, WITHOUT clearing Read Me's data (it runs on the
# owner's own library). Installs over the current build (adb install -r, data kept), notes
# Settings' Continuous play switch and turns it on, shares four synthetic texts (a long buffer
# first, then T1 long, T2 medium, T3 newest and short), checks they are the top four unread rows
# (T3, T2, T1, buffer) and all ready, opens T3 (Trim,
# then the Reader), plays it and turns the screen off. From the service's log lines (ids and
# counts only) it checks that T3 hands over to T2 and T2 to T1 with the screen off, each archived
# at its end and the next two started by the service (no JS start), then that the user's first
# open of T2 from Archive still shows Trim (R-M05, ADR 0011).
#
# The Reader follows the handover (REA-55, ADR 0011). With SCREEN_OFF=0 the Reader stays visible
# after T3's Play: when T3 hands over to T2 it must switch to T2 by itself (live switch: T2's
# title, a highlighted paragraph, Pause), and Pause then Play in it must pause and resume T2 (by
# logcat id; a second JS "playback start" means a tap replaced the item being read, and stops
# Read Me at once). Then HOME; after T2 hands over to T1, Read Me is brought back and the Reader
# must show T1 (returned to later), with the same transport check on T1, and Back must go to the
# List. With SCREEN_OFF=1 the T1 checks run after the owner's unlock (screen off, then on). T2
# lasts long enough for its checks; a check whose item ended first is NOT REACHED, never PASS.
#
# Safety on the owner's library. The owner's unread items sort below the test items, so the chain
# must never go past them (REA-47):
#  - each test item is identified by its distinct sentence count in the service's own log line,
#    and only after the four were seen as the top four unread rows, each ready (List order is the
#    chain's order; a non-ready item is skipped); an item that does not match stops Read Me at once;
#  - the buffer (the oldest test item, directly below T1) is never meant to play: it is what the
#    chain reaches if T1 ends before the switch is off, instead of the owner's newest item. A
#    background watcher force-stops Read Me within about a second of T1's continue line, and the
#    script checks the watcher is alive and stops Read Me itself when it is not;
#  - while T1 (long) plays, the script wakes the screen and turns the switch OFF through Settings
#    (the service reads it at each handover, so the chain ends after T1). That needs the owner to
#    unlock within SWITCH_DEADLINE seconds (default 45; T1 lasts well over a minute even at 4.0x).
#    With no unlock in time, Read Me is force-stopped while T1 still plays;
#  - Read Me is stopped with `am force-stop` of its own package, never a media key (another
#    app's session can hold those, REA-31). The EXIT trap does this on every exit once playback
#    has started and has not ended by itself, restores the switch and deletes the test items;
#  - every id in a playback log line is checked against the test ids, while T1 plays and again
#    at exit; a non-test id stops Read Me and fails the run loudly. A buffer that played is a test
#    item (recognised from the log line itself), but the run fails as NOT COMPLETED;
#  - a full run refuses to start while an earlier run's state file (the owner's original switch
#    setting) is still there: CLEANUP_ONLY=1 restores it first.
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
# word finds them on screen. TITLES[0] is T1, read last; TITLES[3] is the buffer, shared first.
MARK=Kestrel
TITLES=("$MARK test item one." "$MARK test item two." "$MARK test item three." "$MARK test item buffer.")
# Body sentences per item. Distinct counts identify each item in the service's log line; T1 is
# long (about 7500 characters) so the switch can be turned off while it plays. The buffer is at
# least as long: it only has to outlast the watcher's reaction (an estimated few seconds). T2
# (about 2000 characters) outlasts REA-55's live-switch and transport checks on it: a few
# uiautomator dumps and two taps, each dump seconds on the phone. A Pause landing after T2's end
# would fall in the handover window, which ends the chain (ADR 0011).
BODY=(150 40 3 160)
STATE_FILE="$PRIMARY/.claude/scratch/device-continuous.before"
# The last capture a failed reader_check saw (test titles and controls only, no item text).
READER_FAIL_DUMP=${READER_FAIL_DUMP:-$PRIMARY/.claude/scratch/device-continuous-reader-fail.xml}

FAIL=0       # any check failed
PLAYED=0     # playback of a test item was requested; Read Me may be reading
ENDED=0      # the chain ended by itself with the switch off; nothing is playing
CLEARED=0    # logcat was cleared before the shares: every playback line is this run's
SHARED=0     # test items may exist on the phone
TEST_IDS=""  # verified test item ids
ID1=""       # T1's id, once known: the buffer is what follows it
BUFFER_PLAYED=0 BUFFER_ID=""
# REA-55: how long a Reader check waits for the Reader to show the item being read, and a
# transport tap for the service's line (the off-phone tests shorten both).
READER_WAIT=${READER_WAIT:-15}
TAP_WAIT=${TAP_WAIT:-10}
# REA-55 fix round 2: how long a tap waits for SystemUI's clipboard overlay to go (see overlays).
OVERLAY_WAIT=${OVERLAY_WAIT:-20}
WATCH_PID="" WATCH_MARK=""  # the chain watcher (REA-47) and its informational marker file

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
# slack either way for a segmentation difference; the four windows never overlap.
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
# rows_ok <ui dump>: the top four unread rows are T3, T2, T1 and the buffer, in that order.
rows_ok() {
  [ "$(row_titles "$1" | head -4 | tr '\n' '|')" = "${TITLES[2]}|${TITLES[1]}|${TITLES[0]}|${TITLES[3]}|" ]
}
# rows_ready <ui dump>: each of the top four rows shows the badge "ready". Continuous play skips
# an item that is not ready, so a fetching buffer would let the chain reach the owner's item. A
# badge is a Text with nothing naming its row, so the dump is split at each row's delete button
# (the last node of a row): segment i holds row i's badge.
rows_ready() {
  local segs i
  segs=$(tr -d '\n' <<<"$1" | sed 's/content-desc="delete /\n/g')
  for i in 1 2 3 4; do
    device_has "$(sed -n "${i}p" <<<"$segs")" 'text="ready"' || return 1
  done
}
# buffer_seen <log>: when T1's continue line names an item with the buffer's sentence count, read
# from its start and older than T1, that item is the buffer: its id joins TEST_IDS and
# BUFFER_PLAYED is set. Read from the log itself, so it never depends on the watcher's timing.
buffer_seen() {
  local line id
  [ -n "$ID1" ] || return 0
  line=$(grep -oE "playback continue from=$ID1 item=[0-9]+ sentences=[0-9]+ start=[0-9]+" <<<"$1" | head -1 || true)
  [ -n "$line" ] || return 0
  id=$(field "$line" item)
  if count_ok 3 "$(field "$line" sentences)" && [ "$(field "$line" start)" = 0 ] && [ "$id" -lt "$ID1" ]; then
    case " $TEST_IDS " in *" $id "*) ;; *) TEST_IDS+=" $id" ;; esac
    BUFFER_PLAYED=1
    BUFFER_ID=$id
  fi
  return 0
}

# --- REA-55: the Reader follows the handover (pure helpers, tested off the phone) ---

# reader_shows <ui dump> <TITLES index>: Read Me's Reader is the view of that test item being
# read: its title, a highlighted (current) paragraph and Pause, and no other test item's title.
reader_shows() {
  local k
  device_has "$1" 'content-desc="trim"' || return 1
  device_has "$1" 'content-desc="current paragraph"' || return 1
  device_has "$1" 'content-desc="pause"' || return 1
  device_has "$1" "text=\"$(escape "${TITLES[$2]}")\"" || return 1
  for k in 0 1 2 3; do
    [ "$k" = "$2" ] && continue
    device_has "$1" "text=\"$(escape "${TITLES[$k]}")" && return 1
  done
  return 0
}
# reader_state <ui dump>: what a Reader dump shows, for a FAIL line: the test item named by its
# title (T1, T2, T3, buffer or none), whether a paragraph is highlighted, and the button.
reader_state() {
  local k shown=none names=(T1 T2 T3 buffer) hl=no button=none
  for k in 0 1 2 3; do device_has "$1" "text=\"$(escape "${TITLES[$k]}")" && shown=${names[$k]}; done
  device_has "$1" 'content-desc="current paragraph"' && hl=yes
  device_has "$1" 'content-desc="pause"' && button=Pause
  device_has "$1" 'content-desc="play"' && button=Play
  echo "title $shown, highlighted paragraph $hl, button $button"
}
# windows_above_readme <dumpsys window windows>: the visible windows listed above Read Me's (top
# first), by name; all visible windows when Read Me has none.
windows_above_readme() {
  awk '/^  Window #/ { n = $0; sub(/^  Window #[0-9]+ Window\{[^ ]+ u[0-9]+ /, "", n); sub(/\}:$/, "", n)
                       if (n ~ /io\.loopstring\.readme/) exit }
       /^    isVisible=true/ { print n }' <<<"$1"
}
# overlays <dumpsys window windows>: the windows above Read Me that take a tap meant for it.
# SystemUI's clipboard overlay appears at the bottom of the screen whenever anything sets the
# clipboard (here: nothing in the run; something outside it) and stays about 6 s, longer while
# touched. On 2026-10-15 (d9c196c, verify run 1) it came up 5 s before the returned-to Reader's
# Pause tap, the tap landed on it (it then went 6.4 s after the tap, not 6 s after it appeared)
# and Read Me never saw the tap.
overlays() { windows_above_readme "$1" | grep -E '^ClipboardOverlay' | sort -u || true; }
# js_starts <log>: how many items JS started ("playback start item=" is logged for a JS start only).
js_starts() { grep -cE 'playback start item=[0-9]+' <<<"$1" || true; }
# reading_now <log>: the item the service took up last, from its latest start or continue line.
reading_now() {
  grep -oE 'playback (start|continue from=[0-9]+) item=[0-9]+' <<<"$1" | tail -1 | grep -oE '[0-9]+$' || true
}

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
  clear_of_overlays || return 1
  set -- $b
  adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
}
# clear_of_overlays: returns once no overlay (see overlays) covers Read Me, saying what it waited
# for; after OVERLAY_WAIT seconds it fails and nothing is tapped. Never dismisses one: a tap or key
# to do that could reach Read Me.
clear_of_overlays() {
  local o waited=0
  while o=$(overlays "$(adb shell dumpsys window windows 2>/dev/null || true)"); [ -n "$o" ]; do
    if [ "$waited" -ge "$OVERLAY_WAIT" ]; then
      echo "FAIL: $(tr '\n' ' ' <<<"$o")still covers Read Me after ${waited} s; not tapped"; return 1
    fi
    [ "$waited" = 0 ] && echo "  information: $(tr '\n' ' ' <<<"$o")is over Read Me (something set the clipboard); waiting for it to go before the tap"
    sleep 1
    waited=$((waited + 1))
  done
  [ "$waited" = 0 ] || echo "  information: it went after about ${waited} s"
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
# session holds them, which before REA-31 (ADR 0012) could be the TTS engine's own; a force-stop
# ends Read Me whoever holds them.
stop_readme() {
  adb shell am force-stop "$PKG" || true
  echo "stopped Read Me (am force-stop $PKG)"
  PLAYED=0
}
# guard: Read Me must never have read a non-test item. Stops it and fails loudly if it did.
guard() {
  local bad log
  [ "$CLEARED" = 1 ] || return 0
  log=$(logs)
  buffer_seen "$log"
  bad=$(foreign_ids "$log" "$TEST_IDS")
  [ -z "$bad" ] && return 0
  stop_readme
  echo "FAIL: NON-TEST ITEM PLAYED: id(s) $(tr '\n' ' ' <<<"$bad")- check them in the owner's List and Archive"
  FAIL=1
  return 1
}
die() { echo "FAIL: $*"; FAIL=1; exit 1; }

# reader_check <TITLES index> <what> <seconds>: RUN/PASS or RUN/FAIL; the Reader must show that
# test item being read within the time (each dump takes seconds on the phone).
# An empty capture (uiautomator returned nothing, seen right after an unlock) is counted, so a FAIL
# says whether the Reader was seen wrong or not seen at all.
reader_check() {
  local d="" seen="" n=0 empty=0 until=$(($(date +%s) + $3))
  echo "RUN  $2"
  while :; do
    d=$(ui)
    n=$((n + 1))
    if [ -n "$d" ]; then seen=$d; else empty=$((empty + 1)); fi
    reader_shows "$d" "$1" && { echo "PASS $2"; return 0; }
    [ "$(date +%s)" -ge "$until" ] && break
    sleep 1
  done
  if [ -n "$seen" ]; then { printf '%s' "$seen" > "$READER_FAIL_DUMP"; } 2>/dev/null || true; fi
  echo "FAIL $2: the service is reading item $(reading_now "$(logs)"); the Reader shows $(reader_state "$seen") ($empty of $n captures empty)"
  return 1
}
# transport_tap <id> <label> <log ERE> <what>: taps the Reader's Play/Pause button labelled
# <label> and waits for the service's line. A JS start of any item after the tap (counted from
# the log just before it, so lines the ring buffer dropped earlier cannot hide one) is a tap that
# replaced the item being read: Read Me is stopped and the run dies. Returns 1 (NOT REACHED or
# FAIL) when the line never came; NOT REACHED is never a pass.
transport_tap() {
  local log base
  base=$(js_starts "$(logs)")
  tap_node content-desc "$2" || { echo "FAIL $4: the $2 button in the Reader was not tapped"; return 1; }
  for _ in $(seq "$TAP_WAIT"); do
    log=$(logs)
    if [ "$(js_starts "$log")" -gt "$base" ]; then
      echo "FAIL $4: the $(tr -d '()' <<<"$2" | tr '|' '/') tap in the Reader started item $(grep -oE 'playback start item=[0-9]+' <<<"$log" | tail -1 | grep -oE '[0-9]+$') while item $1 was being read"
      stop_readme
      die "the Reader's transport acted on another item"
    fi
    device_has "$log" "$3" && return 0
    sleep 1
  done
  if device_has "$log" "playback continue from=$1 "; then echo "NOT REACHED $4: item $1 ended before its $2 line"; return 1; fi
  echo "FAIL $4: no '$3' within $TAP_WAIT s of the $2 tap; now the Reader shows $(reader_state "$(ui)"), and the visible windows above Read Me are: $(windows_above_readme "$(adb shell dumpsys window windows 2>/dev/null || true)" | tr '\n' ' ')"
  return 1
}
# transport_check <id> <what>: Pause then Play in the Reader act on item <id>, the one being read.
transport_check() {
  echo "RUN  $2"
  if device_has "$(logs)" "playback continue from=$1 "; then echo "NOT REACHED $2: item $1 ended before the check"; return 1; fi
  transport_tap "$1" '(pause|play)' "playback paused item=$1 " "$2" || return 1
  transport_tap "$1" play "playback resumed item=$1\b" "$2" || return 1
  echo "PASS $2"
}
# live_switch_checks <T2 id> (SCREEN_OFF=0): the visible Reader follows T3's handover to T2, and
# its transport acts on T2. A Reader that did not follow fails the run (FAIL=1, the run goes on
# to its cleanup checks); a transport check that did not pass, NOT REACHED included, dies.
live_switch_checks() {
  reader_check 1 "live switch: the visible Reader follows the handover to T2 ($1)" "$READER_WAIT" || FAIL=1
  transport_check "$1" "Pause and Play in the Reader act on T2 ($1)" || die "the transport check on T2 did not pass"
}
# returned_checks <T1 id>: back in Read Me after T2 handed over to T1 while away, the Reader
# followed to T1 and its transport acts on T1; the same FAIL and die rules.
returned_checks() {
  reader_check 0 "returned to: the Reader followed to T1 ($1)" "$READER_WAIT" || FAIL=1
  transport_check "$1" "Pause and Play in the Reader act on T1 ($1)" || die "the transport check on T1 did not pass"
}

# watch_chain <T1 id> <marker file> [max polls]: runs in the background from T1's start. Each
# second it reads the log; on T1's continue line (the chain went past T1, into the buffer) it
# force-stops Read Me at once, then writes "<item> <sentences> <start> <ms>" to the marker (ids and
# counts only; ms: from the last read that did not show the line to the stop, an upper bound on
# its reaction). Errexit is off in it: a failed read must not end the only stopper while the
# switch is being turned off. 1800 polls of at least a second each outlast the run (T1's
# finish is waited 900 s at most).
watch_chain() {
  set +e
  local i line prev now
  prev=$(date +%s%3N)
  for ((i = 0; i < ${3:-1800}; i++)); do
    now=$(date +%s%3N)
    line=$(logs | grep -oE "playback continue from=$1 item=[0-9]+ sentences=[0-9]+ start=[0-9]+" | head -1)
    if [ -n "$line" ]; then
      stop_readme
      echo "$(field "$line" item) $(field "$line" sentences) $(field "$line" start) $(($(date +%s%3N) - prev))" > "$2"
      echo "watcher: the chain went past item $1 (to item $(field "$line" item)); Read Me stopped"
      return 0
    fi
    prev=$now
    sleep 1
  done
  return 0
}
watcher_alive() { [ -n "$WATCH_PID" ] && kill -0 "$WATCH_PID" 2>/dev/null; }
# check_watch: while T1 plays and the switch is still on. Stops Read Me and fails the run if the
# chain already went past T1 (the buffer started) or the watcher is gone.
check_watch() {
  buffer_seen "$(logs)"
  if [ "$BUFFER_PLAYED" = 1 ]; then
    stop_readme
    die "NOT COMPLETED: item $ID1 ended before Continuous play was off; the buffer ($BUFFER_ID) started and Read Me was stopped"
  fi
  watcher_alive || { stop_readme; die "the chain watcher is not running; stopped Read Me"; }
}
# share_all: the four texts, oldest first, so List order (newest first) is T3, T2, T1, buffer.
# Text on stdin, never in the adb argv, which adbd logs (AGENTS.md, SPIKE-01).
share_all() {
  local k text
  for k in 3 0 1 2; do
    text="${TITLES[$k]}

$(body_text "${BODY[$k]}")"
    printf '%s\n' "am start -W -n $PKG/.ShareActivity -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT $(printf '%q' "$text")" \
      | adb shell >/dev/null
    sleep 1
  done
}

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
  if [ "$BUFFER_PLAYED" = 1 ] && [ "$FAIL" = 0 ]; then
    echo "FAIL: NOT COMPLETED: the chain went past the last test item into the buffer ($BUFFER_ID); Read Me was stopped"
    bad=1
  fi
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
  # After on_exit, which stops Read Me if it may still be reading: the watcher guards until then.
  if [ -n "$WATCH_PID" ]; then kill "$WATCH_PID" 2>/dev/null; fi
  if [ -s "$WATCH_MARK" ]; then
    echo "  information: the watcher stopped Read Me $(cut -d' ' -f4 "$WATCH_MARK") ms after its last read that did not show the chain going on"
  fi
  [ -n "$WATCH_MARK" ] && rm -f "$WATCH_MARK"
  [ "$FAIL" = 0 ] || [ "$rc" != 0 ] || rc=1
  echo "device:continuous $([ "$rc" = 0 ] && echo PASS || echo FAIL)"
  (exit "$rc")
  _device_exit
}
# on_signal: bash runs the EXIT trap on INT, TERM and HUP anyway, but with $? 0, which would
# print PASS for an interrupted run.
on_signal() { echo "FAIL: INTERRUPTED"; FAIL=1; exit 1; }

[ "${DEVICE_CONTINUOUS_SOURCE_ONLY:-0}" = 1 ] && return 0

# REA-47: the state file holds the owner's original switch setting until a cleanup restores it.
# A full run would overwrite it with whatever the switch is now (on, after a failed cleanup), so
# it refuses before taking the slot or touching the phone.
if [ "${CLEANUP_ONLY:-0}" != 1 ] && [ -f "$STATE_FILE" ]; then
  echo "REFUSED: $STATE_FILE is left from an earlier run whose cleanup did not finish (it holds"
  echo "the owner's original Continuous play setting). Unlock the phone and run first:"
  echo "  CLEANUP_ONLY=1 npm run device:continuous"
  exit 1
fi

device_take continuous
trap finish EXIT
trap on_signal INT TERM HUP
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
share_all
to_list

# The chain's order is List order: with the four as the top four rows, all ready, T3's next is
# T2, T2's is T1 and T1's the buffer. Anything else and nothing is played.
rows=""
for _ in $(seq 10); do
  d=$(ui)
  if rows_ok "$d" && rows_ready "$d"; then rows=ok; break; fi
  sleep 1
done
[ "$rows" = ok ] || die "the four test items are not the top four unread rows, each ready; nothing played"

tap_node text "$(escape "${TITLES[2]}")"
tap_node content-desc 'trim done'
PLAYED=1
tap_node content-desc play
line=$(wait_log 'playback start item=[0-9]+ sentences=[0-9]+' 20) || die "playback did not start"
id3=$(field "$line" item)
count_ok 2 "$(field "$line" sentences)" || die "the started item ($id3) does not have T3's sentence count"
TEST_IDS="$id3"
if [ "$SCREEN_OFF" = 1 ]; then
  adb shell input keyevent KEYCODE_SLEEP
else
  # REA-55: the Reader stays visible for the live switch.
  reader_check 2 "the Reader shows T3 being read" 20 || die "the Reader does not show T3 being read"
fi

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
if [ "$SCREEN_OFF" = 0 ]; then
  live_switch_checks "$id2"
  adb shell input keyevent KEYCODE_HOME
else
  echo "live switch and T2 transport: not run (SCREEN_OFF=1: the screen is off)"
fi
next_test "$id2" 0
id1=$NEXT_ID
TEST_IDS+=" $id1"
ID1=$id1
t1_at=$(date +%s)
WATCH_MARK=$(mktemp)
rm -f "$WATCH_MARK" # created by the watcher only when it stops Read Me
watch_chain "$id1" "$WATCH_MARK" &
WATCH_PID=$!
echo "test items (List order): $id3 $id2 $id1; T1 is playing; the chain watcher runs"

# T1 plays: turn the switch off before it ends, so the chain stops after it.
adb shell input keyevent KEYCODE_WAKEUP
echo "unlock the phone within ${SWITCH_DEADLINE}s: the script turns Continuous play off before the last test item ends"
until unlocked; do
  guard || exit 1
  check_watch
  if [ $(($(date +%s) - t1_at)) -ge "$SWITCH_DEADLINE" ]; then
    stop_readme
    die "NOT COMPLETED: no unlock within ${SWITCH_DEADLINE}s; Read Me stopped while the last test item played"
  fi
  sleep 1
done
check_watch
# REA-55: back to Read Me after T2 handed over to T1 while away (or with the screen off): the
# Reader followed to T1, its transport acts on T1, and Back goes to the List.
home
returned_checks "$id1"
check_watch
adb shell input keyevent KEYCODE_BACK
if on_screen 'content-desc="settings"'; then echo "PASS Back from the followed Reader goes to the List"; else FAIL=1; fi
to_list
set_switch false || { stop_readme; die "could not turn Continuous play off; stopped Read Me"; }
guard || exit 1
check_watch
echo "Continuous play is off; T1 reads to its end"

wait_log "playback finished item=$id1\b" 900 >/dev/null || die "item $id1 did not finish"
echo "  information: T1 played about $(($(date +%s) - t1_at)) s (host clock, from its continue line seen to its finished line seen)"
sleep 3
log=$(logs)
if device_has "$log" "playback continue from=$id1 "; then
  stop_readme
  buffer_seen "$log"
  die "the chain went on after item $id1 with the switch off; stopped Read Me"
fi
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
