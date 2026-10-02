#!/usr/bin/env bash
# SPIKE-05 / SPIKE-06 on the reference device: offline, screen off, app backgrounded.
# Usage: scripts/spike-gap.sh <single|idle2|concurrent|synthonly> [minutes=10] [rate=2.0] [wakelock=false]
# Prints the SPIKE_RESULT payload. On every exit: app force-stopped, airplane mode off,
# Wi-Fi restored, screen woken, device slot released. The phone is LOCKED afterwards
# (KEYCODE_SLEEP locks a secure screen), so the next device run needs the owner to unlock it.
set -euo pipefail
cd "$(dirname "$0")/.."
MODE=${1:?usage: spike-gap.sh <single|idle2|concurrent|synthonly> [minutes] [rate] [wakelock]}
MIN=${2:-10}; RATE=${3:-2.0}; WL=${4:-false}
. scripts/lib/device.sh
. scripts/lib/spike.sh
WIFI_WAS=""; AIRPLANE_WAS=""
restore() {
  adb shell am force-stop "$PKG" >/dev/null 2>&1 || true
  [ "$AIRPLANE_WAS" = "0" ] && adb shell cmd connectivity airplane-mode disable >/dev/null 2>&1 || true
  [ "$WIFI_WAS" = "1" ] && adb shell svc wifi enable >/dev/null 2>&1 || true
  adb shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
}
DEVICE_ON_EXIT=restore
device_take interactive
trap 'exit 130' INT TERM
device_require_measured_build
device_require_unlocked
device_install_release
WIFI_WAS=$(adb shell settings get global wifi_on | tr -d '\r')
AIRPLANE_WAS=$(adb shell settings get global airplane_mode_on | tr -d '\r')
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null || true
adb shell am force-stop "$PKG"
adb shell am force-stop io.loopstring.ttsbridge 2>/dev/null || true
adb shell cmd connectivity airplane-mode enable
adb shell svc wifi disable
sleep 3
if adb shell ping -c1 -W2 1.1.1.1 >/dev/null 2>&1; then echo "device still online; aborting" >&2; exit 1; fi
echo "offline confirmed"
spike_native --es cmd gap --es mode "$MODE" --ei minutes "$MIN" --ef rate "$RATE" --ez wakelock "$WL"
sleep 2
adb shell input keyevent KEYCODE_HOME
adb shell input keyevent KEYCODE_SLEEP
echo "running $MODE for ${MIN} min at ${RATE}x, wakelock=$WL, screen off"
spike_wait SPIKE_RESULT $(( MIN * 60 + 120 )) || {
  echo "no SPIKE_RESULT before deadline" >&2
  adb logcat -d -s ReadMeSpike:I | tail -5 | sed 's/.*SPIKE_/SPIKE_/' >&2
  exit 1
}
