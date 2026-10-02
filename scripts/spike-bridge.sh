#!/usr/bin/env bash
# SPIKE-01: start the spike bridge under one FGS type, put Obsidian in front, and call the
# bridge from inside Obsidian (CDP) and over adb forward for ~5 minutes of background time.
# Usage: scripts/spike-bridge.sh <media|special>
set -euo pipefail
cd "$(dirname "$0")/.."
FGS=${1:?usage: spike-bridge.sh <media|special>}
PORT=8787
. scripts/lib/device.sh
. scripts/lib/spike.sh
cleanup() {
  adb forward --remove tcp:$PORT >/dev/null 2>&1 || true
  adb shell am force-stop "$PKG" >/dev/null 2>&1 || true
}
DEVICE_ON_EXIT=cleanup
device_take interactive
device_require_measured_build
device_require_unlocked
device_install_release
TOK=$(openssl rand -hex 16)   # generated here; the app never logs it (AGENTS.md 4)
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null || true
adb shell am force-stop io.loopstring.ttsbridge 2>/dev/null || true
adb shell am force-stop "$PKG"
spike_native --es cmd bridge --es fgs "$FGS" --es token "$TOK"
started=$(spike_wait SPIKE_RESULT 10 || true)
device_has "$started" '"listening"' || { echo "bridge did not start: ${started:-no SPIKE_RESULT}" >&2; exit 1; }
adb shell dumpsys activity services "$PKG/.spike.SpikeService" | grep -iE "isForeground|foregroundServiceType" || true

echo "== Obsidian to the foreground; Read Me now has only its FGS"
adb shell am start -n md.obsidian/.MainActivity >/dev/null; sleep 8
printf '%s' "$TOK" | node scripts/obsidian-cdp-probe.mjs

echo "== 20 synth calls over ~5 min with Read Me in the background"
adb forward tcp:$PORT tcp:$PORT >/dev/null
for i in $(seq 1 20); do
  code=$(printf 'Background bridge spike sentence number %d.' "$i" \
    | curl -s -o /dev/null -w '%{http_code}' --max-time 70 -X POST \
      -H "Authorization: Bearer $TOK" -H 'Content-Type: text/plain' --data-binary @- \
      "http://127.0.0.1:$PORT/synthesize?rate=1.0")
  echo "synth $i: $code"; sleep 15
done

echo "== Obsidian again, after 5 min of background"
printf '%s' "$TOK" | node scripts/obsidian-cdp-probe.mjs

echo "== bridge log (route, status, importance: 100 foreground, 125 foreground service)"
adb logcat -d -s ReadMeSpike:I | grep 'SPIKE_BRIDGE' | sed 's/.*SPIKE_BRIDGE //' \
  | python3 -c 'import sys,json,collections; c=collections.Counter((d["route"],d["status"],d["importance"]) for d in map(json.loads,sys.stdin)); [print(n,k) for k,n in c.most_common()]'
echo "== token occurrences in logcat (must be 0)"
adb logcat -d | grep -c "$TOK" || true
