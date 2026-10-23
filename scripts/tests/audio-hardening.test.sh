#!/usr/bin/env bash
# Tests for scripts/lib/audio-hardening.sh (REA-66): a new AudioHardening line naming Read Me in
# `dumpsys audio` between the baseline and the end of a run fails device:playback / device:gap.
# Line identity, not counts (dumpsys keeps a bounded log). Runs against a stub adb; never touches
# a device.
set -uo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
mkdir -p "$T/bin"
# The stub answers `dumpsys audio` from $STUB_DUMP (a file) and fails when STUB_FAIL is set.
cat > "$T/bin/adb" <<'STUB'
#!/bin/sh
case "$*" in
  "shell dumpsys audio") [ -n "$STUB_FAIL" ] && exit 1; [ -f "$STUB_DUMP" ] && cat "$STUB_DUMP" ;;
  *) : ;;
esac
STUB
chmod +x "$T/bin/adb"
export PATH="$T/bin:$PATH" STUB_DUMP="$T/dump" STUB_FAIL=
PKG=io.loopstring.readme
. "$ROOT/scripts/lib/audio-hardening.sh"
fail=0
check() { if [ "$2" = "$3" ]; then echo "ok   $1"; else echo "FAIL $1: expected $3, got $2"; fail=1; fi; }

OLD='10-22 10:42:23:485 AudioHardening background playback muted for io.loopstring.readme (10357), level: partial, reason: 6, usage: USAGE_MEDIA'
NEWM='10-22 11:01:02:100 AudioHardening background playback muted for io.loopstring.readme (10357), level: partial, reason: 6, usage: USAGE_MEDIA'
NEWW='10-22 11:02:03:200 AudioHardening background playback would be muted for io.loopstring.readme (10357), level: partial, reason: 6, usage: USAGE_MEDIA'
OTHER='10-22 11:03:04:300 AudioHardening background playback muted for md.obsidian (10200), level: partial, reason: 6, usage: USAGE_MEDIA'
NOISE='Audio event log: something unrelated'
run() { # baseline-file after-file -> sets rc, out
  # The baseline sets shell variables, so it runs here, not in a command substitution.
  printf '%b' "$1" > "$STUB_DUMP"; audio_hardening_baseline >"$T/b" 2>&1 || { rc=$?; out=$(cat "$T/b"); return; }
  printf '%b' "$2" > "$STUB_DUMP"; audio_hardening_check >"$T/c" 2>&1; rc=$?; out=$(cat "$T/b" "$T/c")
}
has() { printf '%s' "$out" | grep -q "$1" && echo yes || echo no; }

run "$NOISE\n$OLD\n" "$NOISE\n$OLD\n"
check "(a) unchanged list passes" "$rc" 0
check "(a) counts printed" "$(has 'before=1 after=1 new=0')" yes

run "$NOISE\n$OLD\n" "$NOISE\n$OLD\n$NOISE\n"
check "(b) old entry still present passes" "$rc" 0

run "$NOISE\n$OLD\n" "$NOISE\n$OLD\n$NEWM\n"
check "(c) new muted fails" "$([ "$rc" -ne 0 ] && echo fail || echo pass)" fail
check "(c) says new=1" "$(has 'new=1')" yes

run "$NOISE\n$OLD\n" "$NOISE\n$OLD\n$NEWW\n"
check "(d) new would-be-muted fails" "$([ "$rc" -ne 0 ] && echo fail || echo pass)" fail

# Bounded log: the old entry is evicted and a new one arrives, so the count is unchanged.
run "$NOISE\n$OLD\n" "$NOISE\n$NEWM\n"
check "(e) evicted old plus new (same count) fails" "$([ "$rc" -ne 0 ] && echo fail || echo pass)" fail

run "$NOISE\n" "$NOISE\n$OTHER\n"
check "(f) another app's mute passes" "$rc" 0

run "$NOISE\n" "$NOISE\n"
check "(g) no AudioHardening lines is a valid baseline of 0" "$rc" 0
check "(g) before=0" "$(has 'before=0 after=0 new=0')" yes

run "$NOISE\n" "$NOISE\n$NEWM\n"
check "(h) baseline 0 then a new mute fails" "$([ "$rc" -ne 0 ] && echo fail || echo pass)" fail

# Empty or failed dumpsys fails, at the baseline and at the check.
printf '' > "$STUB_DUMP"; out=$(audio_hardening_baseline 2>&1); rc=$?
check "(i) empty baseline fails" "$([ "$rc" -ne 0 ] && echo fail || echo pass)" fail
STUB_FAIL=1; out=$(audio_hardening_baseline 2>&1); rc=$?
check "(j) failed baseline fails" "$([ "$rc" -ne 0 ] && echo fail || echo pass)" fail
STUB_FAIL=; printf '%s\n' "$NOISE" > "$STUB_DUMP"; audio_hardening_baseline >/dev/null
STUB_FAIL=1; out=$(audio_hardening_check 2>&1); rc=$?
check "(k) failed after-snapshot fails" "$([ "$rc" -ne 0 ] && echo fail || echo pass)" fail
STUB_FAIL=; printf '' > "$STUB_DUMP"; out=$(audio_hardening_check 2>&1); rc=$?
check "(l) empty after-snapshot fails" "$([ "$rc" -ne 0 ] && echo fail || echo pass)" fail
check "(m) the output never carries a dumpsys line" "$(has 'AudioHardening')" no
exit $fail
