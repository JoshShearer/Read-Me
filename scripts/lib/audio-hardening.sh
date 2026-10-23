# Android 17's AudioHardening mutes an app's background playback and logs it in `dumpsys audio`
# (REA-57: a force-stop of Read Me caused it). REA-66: device:playback and device:gap fail when a
# new such line naming Read Me appears during the run. Source after device.sh (needs adb, and PKG
# if it is not io.loopstring.readme).
#
# A new entry is a whole line (timestamp included) in the after-snapshot that the baseline did not
# have, not a larger count: dumpsys keeps a bounded log, so an evicted old entry plus a new one
# leaves the count unchanged. Take the baseline after device_clear_app and the check after the
# final pause or finish, with no force-stop of Read Me in between. Output carries counts only,
# never a dumpsys line (AGENTS.md 1).

# The AudioHardening lines that name Read Me, from a snapshot on stdin.
audio_hardening_lines() {
  tr -d '\r' | grep -F 'AudioHardening' | grep -F "${PKG:-io.loopstring.readme}" || true
}

# The snapshot on stdout; fails when adb fails or says nothing, so an unreadable snapshot is never
# taken for "no new mute".
audio_hardening_snapshot() {
  local s
  s=$(adb shell dumpsys audio) || { echo "FAIL: dumpsys audio failed" >&2; return 1; }
  [ -n "$(tr -d '[:space:]' <<<"$s")" ] || { echo "FAIL: dumpsys audio returned nothing" >&2; return 1; }
  # dumpsys's per-service timeout marker: the dump was cut and may lack the hardening entries.
  # (No section header was confirmed on the phone, so non-empty without the marker is readable.)
  ! grep -qF "DUMP TIMEOUT" <<<"$s" || { echo "FAIL: dumpsys audio timed out (truncated dump)" >&2; return 1; }
  printf '%s\n' "$s"
}

AUDIO_HARDENING_BASELINE=
AUDIO_HARDENING_HAVE_BASELINE=

audio_hardening_baseline() {
  local s
  AUDIO_HARDENING_HAVE_BASELINE=
  s=$(audio_hardening_snapshot) || return 1
  AUDIO_HARDENING_BASELINE=$(audio_hardening_lines <<<"$s")
  AUDIO_HARDENING_HAVE_BASELINE=1
  echo "audio hardening baseline: $(_ah_count "$AUDIO_HARDENING_BASELINE") line(s) for Read Me"
}

_ah_count() { [ -n "$1" ] && printf '%s\n' "$1" | wc -l | tr -d ' ' || echo 0; }

audio_hardening_check() {
  local s after new
  [ -n "$AUDIO_HARDENING_HAVE_BASELINE" ] || { echo "FAIL: no audio hardening baseline was taken"; return 1; }
  s=$(audio_hardening_snapshot) || return 1
  after=$(audio_hardening_lines <<<"$s")
  local rc=0
  new=$(grep -vxFf <(printf '%s\n' "$AUDIO_HARDENING_BASELINE") <<<"$after") || rc=$?
  # grep: 0 = some new line, 1 = none; anything above 1 is an error, never "no new line".
  [ "$rc" -le 1 ] || { echo "FAIL: audio hardening comparison errored (grep exit $rc)"; return 1; }
  [ -z "$after" ] && new=
  local n; n=$(_ah_count "$new")
  echo "audio hardening: before=$(_ah_count "$AUDIO_HARDENING_BASELINE") after=$(_ah_count "$after") new=$n"
  [ "$n" -eq 0 ] || { echo "FAIL: $n new AudioHardening line(s) for Read Me during the run (background playback muted)"; return 1; }
}
