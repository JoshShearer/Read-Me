#!/usr/bin/env bash
# REA-75: npm run release:launch -- release/tested-<sha> [abi ...]. Launches each per-ABI APK the
# reference phone cannot run on a headless emulator and records it in release/launch-verified,
# which release:apk requires. A launch counts only when the List is on screen (its Settings button
# in a uiautomator dump, so the bundle ran on that ABI's Hermes), the process runs that ABI's
# libhermesvm.so, and the crash log has no Read Me line.
#
# Never touches the phone: its own adb server (port 5038) and emulator ports above the 5585 that
# the default server scans, its own throwaway AVDs. An emulator is not reference evidence (AGENTS.md
# 15); this shows that the ABI loads, nothing more.
#
# armeabi-v7a runs under the API 30 x86 image's ARM translation: the emulator has no ARM32 mode.
# There SoLoader looks for base.apk!/lib/x86 (the device's first ABI), so as root the APK's own
# libraries are unpacked into its native library directory. The APK itself is unmodified.
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/lib/release.sh
PKG=io.loopstring.readme
TESTED=${1:?usage: npm run release:launch -- release/tested-<sha> [abi ...]}
TESTED=${TESTED%/}; shift
ABIS=${*:-x86_64 x86 armeabi-v7a}
export ANDROID_ADB_SERVER_PORT=${READ_ME_EMU_ADB_PORT:-5038}
EMU_PORT=${READ_ME_EMU_PORT:-5600}
A="adb -s emulator-$EMU_PORT"
AVDM=$(ls "$SDK"/cmdline-tools/*/bin/avdmanager 2>/dev/null | head -1 || true)
[ -x "$SDK/emulator/emulator" ] && [ -n "$AVDM" ] || { echo "refused: no emulator or avdmanager under $SDK"; exit 1; }
[ -w /dev/kvm ] || { echo "refused: no writable /dev/kvm"; exit 1; }
image_for() {
  case $1 in
    x86_64) echo "system-images;android-36;default;x86_64" ;;
    x86|armeabi-v7a) echo "system-images;android-30;google_apis;x86" ;;
    *) return 1 ;;
  esac
}
for ABI in $ABIS; do
  [ -f "$TESTED/app-$ABI-release.apk" ] || { echo "refused: no $TESTED/app-$ABI-release.apk"; exit 1; }
  I=$(image_for "$ABI") || { echo "refused: no emulator for $ABI (arm64-v8a runs on the phone: npm run device:smoke)"; exit 1; }
  [ -d "$SDK/${I//;//}" ] || { echo "refused: missing system image; run sdkmanager \"$I\""; exit 1; }
done
T=$(mktemp -d)
EMU_PID=
stop_emulator() {
  if [ -n "$EMU_PID" ]; then
    timeout 10 $A emu kill >/dev/null 2>&1 || true
    for _ in $(seq 10); do kill -0 "$EMU_PID" 2>/dev/null || break; sleep 1; done
    kill "$EMU_PID" 2>/dev/null || true
    EMU_PID=
  fi
}
trap 'stop_emulator; adb kill-server >/dev/null 2>&1 || true; rm -rf "$T"' EXIT
export ANDROID_AVD_HOME="$T/avd"
mkdir -p "$ANDROID_AVD_HOME"
boot() { # boot <system image>
  stop_emulator
  echo no | "$AVDM" create avd -n launch -k "$1" -p "$T/avd/launch.avd" --force >/dev/null 2>&1
  "$SDK/emulator/emulator" -avd launch -ports "$EMU_PORT,$((EMU_PORT + 1))" -no-window -no-audio \
    -no-snapshot -no-boot-anim -gpu swiftshader_indirect -memory 3072 > "$T/emulator.log" 2>&1 &
  EMU_PID=$!
  for _ in $(seq 60); do
    [ "$(timeout 5 $A shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && break
    kill -0 "$EMU_PID" 2>/dev/null || break
    sleep 4
  done
  [ "$(timeout 5 $A shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] \
    || { echo "FAIL: the emulator did not boot ($1)"; tail -5 "$T/emulator.log"; exit 1; }
  timeout 20 $A root >/dev/null 2>&1 || true
  sleep 3
  [ "$(timeout 10 $A shell id -u | tr -d '\r')" = 0 ] || { echo "FAIL: no root on the emulator ($1)"; exit 1; }
}
launch() { # launch <abi>
  local abi=$1 apk=$TESTED/app-$1-release.apk dir pid ui crash maps fail=0 got
  # --no-incremental: an incremental install mounts the app's directory on incfs, which refuses
  # the libraries unpacked below.
  timeout 10 $A uninstall "$PKG" >/dev/null 2>&1 || true
  timeout 120 $A install --no-incremental --abi "$abi" "$apk" >/dev/null || { echo "FAIL: $abi did not install"; return 1; }
  if [ "$abi" = armeabi-v7a ]; then
    rm -rf "$T/libs"; mkdir "$T/libs"
    unzip -q "$apk" "lib/$abi/*" -d "$T/libs"
    dir=$($A shell pm path "$PKG" | tr -d '\r' | sed 's/^package://; s,/base.apk$,,' | head -1)
    $A shell rm -rf /data/local/tmp/readme-libs
    $A push "$T/libs/lib/$abi" /data/local/tmp/readme-libs >/dev/null
    $A shell "mkdir -p $dir/lib/arm && cp /data/local/tmp/readme-libs/*.so $dir/lib/arm/ && chmod 755 $dir/lib $dir/lib/arm && chmod 644 $dir/lib/arm/*.so && restorecon -R $dir/lib" >/dev/null 2>&1
  fi
  $A logcat -b all -c >/dev/null 2>&1 || true
  timeout 90 $A shell am start -W -n "$PKG/.MainActivity" | tr -d '\r' > "$T/start" || true
  grep -qx 'Status: ok' "$T/start" || { echo "FAIL: $abi am start did not report Status: ok"; fail=1; }
  ui=
  for _ in $(seq 12); do
    timeout 30 $A shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1 || true
    ui=$(timeout 10 $A shell cat /sdcard/u.xml 2>/dev/null || true)
    grep -q "package=\"$PKG\"" <<<"$ui" && grep -q 'content-desc="settings"' <<<"$ui" && break
    sleep 3
  done
  { grep -q "package=\"$PKG\"" <<<"$ui" && grep -q 'content-desc="settings"' <<<"$ui"; } \
    || { echo "FAIL: $abi the List is not on screen"; fail=1; }
  got=$(timeout 20 $A shell dumpsys package "$PKG" | tr -d '\r' | sed -n 's/.*primaryCpuAbi=//p' | head -1)
  [ "$got" = "$abi" ] || { echo "FAIL: installed as ${got:-no ABI}, not $abi"; fail=1; }
  pid=$(timeout 10 $A shell pidof "$PKG" | tr -d '\r' || true); pid=${pid%% *}
  [ -n "$pid" ] || { echo "FAIL: $abi process not running"; fail=1; }
  # An APK's libraries are mapped from the APK itself (extractNativeLibs is off) except above.
  if [ -n "$pid" ]; then
    maps=$(timeout 20 $A shell cat "/proc/$pid/maps")
    grep -qE "$PKG[^ ]*/(base\.apk|lib/arm/libhermesvm\.so)" <<<"$maps" || { echo "FAIL: $abi app code not mapped"; fail=1; }
  fi
  crash=$(timeout 20 $A logcat -d -b crash || true)
  ! grep -q "$PKG" <<<"$crash" || { echo "FAIL: $abi crash logged"; grep -m3 "$PKG" <<<"$crash"; fail=1; }
  [ "$fail" = 0 ] || return 1
  hermes_record "$apk" "$abi"
  record_launched "$apk" "emulator $(image_for "$abi") $(date -I)"
  echo "release:launch PASS $abi ($(image_for "$abi"))"
}
rc=0; BOOTED=
for ABI in $ABIS; do
  I=$(image_for "$ABI")
  if [ "$I" != "$BOOTED" ]; then boot "$I"; BOOTED=$I; fi
  launch "$ABI" || { echo "release:launch FAIL $ABI"; rc=1; }
done
exit $rc
