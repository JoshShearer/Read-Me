#!/usr/bin/env bash
# npm run device:accept-share - R-M14 on-device run 1, as a user would: share a real article
# link (the ACTION_SEND a browser's Share sends), trim a paragraph, play at 2.0x (the default
# rate), lock the screen for 60 s, unlock, and confirm the position: reading advanced while
# locked, and after the app is killed and reopened it resumes there, not at the start.
# Needs the network (Wikipedia: a long real article, so it is still reading after 60 s at 2.0x;
# the weather.gov page device:intake uses ends inside a minute) and the owner to unlock once,
# when it says so. Clears Read Me's data. Checks no log line carries the URL path.
set -euo pipefail
cd "$(dirname "$0")/.."
. scripts/lib/device.sh
device_take interactive
device_require_unlocked
device_install_release
LINK='https://en.wikipedia.org/wiki/Lightning'

ui() {
  adb shell rm -f /sdcard/readme-ui.xml
  adb shell uiautomator dump /sdcard/readme-ui.xml >/dev/null 2>&1 || true
  adb shell cat /sdcard/readme-ui.xml 2>/dev/null || true
}
# centre_of <attr> <ERE>: "x y" of the first node whose attr matches, retrying for 30 s.
centre_of() {
  local b=""
  for _ in $(seq 30); do
    b=$(ui | grep -oE "$1=\"$2\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" | head -1 \
      | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' | grep -oE '[0-9]+' | tr '\n' ' ' || true)
    [ -n "$b" ] && break
    sleep 1
  done
  [ -n "$b" ] || { echo "FAIL: nothing on screen has $1 $2" >&2; exit 1; }
  set -- $b
  echo "$(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))"
}
tap() { adb shell input tap $(centre_of "$1" "$2"); }
logs() { adb logcat -d -s ReadMe:I; }
wait_count() {
  for _ in $(seq "$3"); do
    [ "$(logs | grep -cE "$1" || true)" -ge "$2" ] && return 0
    sleep 1
  done
  echo "FAIL: waited $3 s for $2 x '$1'"; return 1
}
pos() { sed -E 's/.*paragraph=([0-9]+) offset=([0-9]+).*/\1 \2/' <<<"$1"; }
later() { # later "p1 o1" "p2 o2": true when the second position is after the first
  local p1 o1 p2 o2; read -r p1 o1 <<<"$1"; read -r p2 o2 <<<"$2"
  [ "${p2:-0}" -gt "${p1:-0}" ] || { [ "${p2:-0}" -eq "${p1:-0}" ] && [ "${o2:-0}" -gt "${o1:-0}" ]; }
}

device_clear_app
adb logcat -c
fail=0
# The link goes on stdin, not the adb argv, which adbd logs (AGENTS.md, SPIKE-01).
printf '%s\n' "am start -W -n $PKG/.ShareActivity -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT $(printf '%q' "$LINK")" \
  | adb shell >/dev/null
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null

echo "== share, trim, play at 2.0x"
tap text 'ready'
tap content-desc 'paragraph 2'
device_has "$(ui)" 'content-desc="paragraph 2 cut"' || { echo "FAIL: paragraph 2 not cut"; fail=1; }
tap content-desc 'trim done'
play_at=$(centre_of content-desc play)
adb shell input tap $play_at
wait_count 'playback start item=1 ' 1 30 || fail=1
device_has "$(logs)" 'playback start item=1 .* from=0 rate=2.0' || { echo "FAIL: not a start at 2.0x"; logs | grep 'playback start' | sed 's/^/  /'; fail=1; }
sleep 8
adb shell input tap $play_at
wait_count 'playback paused item=1 ' 1 10 || fail=1
before=$(pos "$(logs | grep -oE 'playback paused item=1 paragraph=[0-9]+ offset=[0-9]+' | tail -1)")
adb shell input tap $play_at
wait_count 'playback resumed item=1' 1 10 || fail=1

echo "== screen locked for 60 s"
adb shell input keyevent KEYCODE_SLEEP
sleep 60
adb shell cmd media_session dispatch pause
wait_count 'playback paused item=1 ' 2 10 || fail=1
locked=$(pos "$(logs | grep -oE 'playback paused item=1 paragraph=[0-9]+ offset=[0-9]+' | tail -1)")
if later "$before" "$locked"; then echo "ok: reading advanced while locked ($before -> $locked)"
else echo "FAIL: reading did not advance while locked ($before -> $locked)"; fail=1; fi

echo "== unlock the phone now (waiting up to 3 min)"
adb shell input keyevent KEYCODE_WAKEUP
for _ in $(seq 180); do ( device_require_unlocked ) 2>/dev/null && break; sleep 1; done
( device_require_unlocked ) || exit 5

echo "== killed and reopened, it resumes where it was"
# Keep the share, fetch, trim and play logs (and the first process's crash lines): the
# privacy and crash checks at the end read them together with the reopened process's.
first_pid=$(adb shell pidof "$PKG" 2>/dev/null | tr -d '\r' || true)
first_log=$(adb logcat -d)
first_crash=$(adb logcat -d -b crash,main)
adb shell am force-stop "$PKG"
adb logcat -c
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
# The list shows the saved progress; a second open goes to the Reader, not Trim (R-M05).
pct=$(ui | grep -oE 'text="[^"]*[0-9]+% read"' | head -1 || true)
[ -n "$pct" ] && echo "ok: the list shows ${pct#text=}" || { echo "FAIL: no progress on the list row"; fail=1; }
tap text '[^"]*[0-9]+% read'
adb shell input tap $(centre_of content-desc play)
wait_count 'playback start item=1 ' 1 30 || fail=1
from=$(logs | grep -oE 'playback start item=1 .* from=[0-9]+' | grep -oE 'from=[0-9]+' | cut -d= -f2 | tail -1)
if [ "${from:-0}" -gt 0 ]; then echo "ok: resumed at sentence $from, not the start"
else echo "FAIL: resumed at the start"; fail=1; fi
adb shell cmd media_session dispatch pause
wait_count 'playback paused item=1 ' 1 10 || fail=1
resumed=$(pos "$(logs | grep -oE 'playback paused item=1 paragraph=[0-9]+ offset=[0-9]+' | tail -1)")
# The resume starts at the sentence holding the saved offset, so it is not before the paused
# paragraph (it can be the same offset or a little after, having read on).
read -r lp _ <<<"$locked"; read -r rp _ <<<"$resumed"
[ "${rp:-0}" -ge "${lp:-0}" ] && echo "ok: position kept ($locked -> $resumed)" || { echo "FAIL: position went back ($locked -> $resumed)"; fail=1; }

all="$first_log
$(adb logcat -d)"
device_has "$all" 'wiki/Lightning' && { echo "FAIL: a log line carries the URL path"; fail=1; }
pid=$(adb shell pidof "$PKG" 2>/dev/null | tr -d '\r' || true)
if device_crash_seen "${first_pid%% *}" <<<"$first_crash" || device_crash_seen "${pid%% *}" <<<"$(adb logcat -d -b crash,main)"; then
  echo "FAIL: crash logged"; fail=1
fi
adb shell rm -f /sdcard/readme-ui.xml
echo "device:accept-share $([ $fail -eq 0 ] && echo PASS || echo FAIL)"
exit $fail
