# Sourced after scripts/lib/device.sh. Native spike commands go through the exported
# MainActivity (adb cannot start the unexported SpikeService). Never pass item text here.
# adb answers are read whole before they are searched (device_has in scripts/lib/device.sh).

# spike_native <am extras...>: start MainActivity with nativeSpike=true and the extras, then
# require a SPIKE_FGS line within 10 s. Exits 4 if am errors or the service never started.
spike_native() {
  adb logcat -c
  local out crash
  # The command goes to adb shell on stdin, not argv: adbd logs every argv command line to logcat
  # ("adbd service requested ... raw:<command>"), and the extras can carry the bridge token
  # (AGENTS.md 4). Reproduced on the reference device 2026-10-01; stdin is logged as "raw:".
  out=$(printf '%s\n' "am start -n $PKG/.MainActivity --ez nativeSpike true $(printf '%q ' "$@")" | adb shell 2>&1)
  if device_has "$out" '[Ee]rror|[Ee]xception'; then
    echo "am start failed: $(grep -iE 'error|exception' <<<"$out" | head -2)" >&2
    exit 4
  fi
  if ! spike_wait SPIKE_FGS 10 >/dev/null; then
    echo "SpikeService did not start (no SPIKE_FGS in 10 s). Crash buffer:" >&2
    crash=$(adb logcat -d -b crash)
    tail -15 <<<"$crash" >&2   # e.g. ForegroundServiceStartNotAllowedException: an answer
    exit 4
  fi
}

# spike_wait <PATTERN> <timeout_s>: print the payload of the first ReadMeSpike line matching.
spike_wait() {
  local pattern=$1 timeout=$2 logs line
  for _ in $(seq "$timeout"); do
    logs=$(adb logcat -d -s ReadMeSpike:I)
    line=$(grep -m1 -- "$pattern" <<<"$logs" || true)
    if [ -n "$line" ]; then echo "${line#*"$pattern" }"; return 0; fi
    sleep 1
  done
  return 1
}
