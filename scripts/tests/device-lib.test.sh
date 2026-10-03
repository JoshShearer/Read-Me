#!/usr/bin/env bash
# Tests for scripts/lib/device.sh lock rules and scripts/device-smoke.sh crash detection, against
# a stub adb in a throwaway git repo. Never touches a real device.
set -uo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
mkdir -p "$T/bin" "$T/repo/scripts/lib"
cat > "$T/bin/adb" <<'STUB'
#!/bin/sh
case "$1" in
  devices) printf 'List of devices attached\nX\tdevice\n\n' ;;
  logcat) [ -f "$STUB_LOGCAT" ] && cat "$STUB_LOGCAT" ;;
  *) : ;;
esac
STUB
chmod +x "$T/bin/adb"
export PATH="$T/bin:$PATH"
cp "$ROOT/scripts/lib/device.sh" "$T/repo/scripts/lib/"
cd "$T/repo" && git init -q && git -c user.email=t@t -c user.name=t commit -q --allow-empty -m init
git checkout -q -b feature/a.b
L=.claude/device.lock; mkdir -p .claude
fail=0
check() { if [ "$2" = "$3" ]; then echo "ok   $1"; else echo "FAIL $1: expected $3, got $2"; fail=1; fi; }
take() { bash -c '. scripts/lib/device.sh; device_take interactive' >/dev/null 2>&1; echo $?; }
owner() { printf '%s\nbranch=%s commit=x at=x purpose=%s\n' "$(git rev-parse --show-toplevel)" "$1" "$2" > "$L/owner"; }

mkdir "$L"; owner feature/a.b interactive
check "a /ship or /verify lock on this lane is reused" "$(take)" 0
check "and is left for the caller to release" "$([ -d "$L" ] && echo kept)" kept
rm -rf "$L"

mkdir "$L"; owner feature/a.b script:interactive
check "a lock another device script holds is never adopted (F1)" "$(take)" 3
rm -rf "$L"

mkdir "$L"; owner feature/aXb interactive
check "branch names match literally, not as a regex (F9)" "$(take)" 3
rm -rf "$L"

mkdir "$L"
check "an ownerless lock is another lane's" "$(take)" 3
rm -rf "$L"

check "a free slot is taken" "$(take)" 0
check "and released on exit" "$([ -d "$L" ] && echo kept || echo released)" released

# device_require_unlocked: Android 13+ prints isKeyguardShowing in `dumpsys window`; Android 10
# (the Huawei VRD-W09 tablet, 2026-10-03) prints only KeyguardStateMonitor's mIsShowing.
mkdir -p "$T/kg"
cat > "$T/kg/adb" <<'STUB'
#!/bin/sh
case "$*" in
  "shell dumpsys power") echo "  mWakefulness=Awake" ;;
  "shell dumpsys window") printf '%b\n' "$STUB_WINDOW" ;;
esac
STUB
chmod +x "$T/kg/adb"
unlocked() { STUB_WINDOW="$1" PATH="$T/kg:$PATH" bash -c '. scripts/lib/device.sh; device_require_unlocked' >/dev/null 2>&1; echo $?; }
check "unlocked on Android 13+" "$(unlocked '    isKeyguardShowing=false')" 0
check "locked on Android 13+" "$(unlocked '    isKeyguardShowing=true\n        mIsShowing=false')" 5
check "unlocked on Android 10 (mIsShowing only)" "$(unlocked '      KeyguardStateMonitor\n        mIsShowing=false')" 0
check "locked on Android 10" "$(unlocked '      KeyguardStateMonitor\n        mIsShowing=true')" 5
check "neither line is locked" "$(unlocked 'nothing')" 5
# A locked phone must never read as unlocked: only the keyguard's own line counts, and any
# "showing" line that says true wins over one that says false.
check "isKeyguardShowing true beside false is locked" "$(unlocked '    isKeyguardShowing=false\n    isKeyguardShowing=true')" 5
check "an unrelated mIsShowing=false after the keyguard's true is locked" "$(unlocked '      KeyguardStateMonitor\n        mIsShowing=true\n      SomeDialog\n        mIsShowing=false')" 5
check "an unrelated mIsShowing=false before the keyguard's true is locked" "$(unlocked '      SomeDialog\n        mIsShowing=false\n      KeyguardStateMonitor\n        mIsShowing=true')" 5
check "an mIsShowing=false with no KeyguardStateMonitor is locked" "$(unlocked '      SomeDialog\n        mIsShowing=false')" 5

# device_crash_seen <pid>: reads logcat on stdin. Real formats: the native libc line carries
# /proc/self/comm, which ART truncates to the LAST 15 chars of the package ("opstring.readme"),
# so the package name never appears on it; debuggerd prints ">>> <package> <<<"; Java crashes
# print "AndroidRuntime: Process: <package>, PID: <pid>".
seen() { bash -c '. scripts/lib/device.sh; device_crash_seen "$1"' _ "$1" >/dev/null 2>&1; echo $?; }
check "native crash: truncated-comm libc line matched by pid" \
  "$(printf 'F libc    : Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0 in tid 1290 (mqt_js), pid 1234 (opstring.readme)\n' | seen 1234)" 0
check "native crash: debuggerd >>> package <<< line" \
  "$(printf 'F DEBUG   : pid: 1234, tid: 1290, name: mqt_js  >>> io.loopstring.readme <<<\n' | seen 9999)" 0
check "java crash: AndroidRuntime Process line" \
  "$(printf 'E AndroidRuntime: FATAL EXCEPTION: main\nE AndroidRuntime: Process: io.loopstring.readme, PID: 1234\n' | seen 1234)" 0
check "another app's native crash is not ours" \
  "$(printf 'F libc    : Fatal signal 11 (SIGSEGV), code 1, fault addr 0x0 in tid 77 (x), pid 12345 (other.app)\nF DEBUG   : >>> com.other.app <<<\n' | seen 1234)" 1
check "a clean log is not a crash" "$(printf 'I ReactNativeJS: Running "ReadMe"\n' | seen 1234)" 1

# A crash line followed by a large log (logcat -d is routinely MBs). Under pipefail, a reader
# that stops at the first match SIGPIPEs the producer, the pipeline returns 141, and the crash is
# reported as absent (final review F1).
check "a crash followed by 2 MB of log is still seen" \
  "$(bash -c 'set -o pipefail; . scripts/lib/device.sh
     { echo "F libc    : Fatal signal 11 (SIGSEGV), code 1, fault addr 0x0 in tid 9 (mqt_js), pid 1234 (opstring.readme)"
       awk "BEGIN { for (i = 0; i < 90000; i++) print \"I filler: nothing here\" }"; } | device_crash_seen 1234' >/dev/null 2>&1; echo $?)" 0

# device-smoke.sh end to end against a stub adb: it must report, never die silently, when the
# app is slow to start or never starts (pidof exits 1 under set -euo pipefail).
# Env for the stub: STUB_BIG=1 appends 2 MB after every dumpsys/logcat answer; STUB_CRASH=1 puts
# a native crash of pid 4242 in logcat; STUB_PIDS overrides pidof's answer; STUB_STAMP=stale
# writes an APK stamp for another commit.
smoke_run() {  # $1 = how many pidof calls fail before the process "appears" (99 = never)
  mkdir -p "$T/smoke/bin" "$T/smoke/repo/scripts/lib" "$T/smoke/repo/android/app/build/outputs/apk/release"
  cat > "$T/smoke/bin/adb" <<STUB
#!/bin/bash
big() { if [ -n "\${STUB_BIG:-}" ]; then yes 'I filler: nothing here' | head -c 2000000; fi; }
n=\$(cat "$T/smoke/pidof-calls" 2>/dev/null || echo 0)
case "\$*" in
  devices) printf 'List of devices attached\nX\tdevice\n\n' ;;
  "shell dumpsys power") echo "  mWakefulness=Awake"; big ;;
  "shell dumpsys window") echo "    isKeyguardShowing=false"; big ;;
  "shell dumpsys activity activities") echo "  topResumedActivity=ActivityRecord{1 u0 io.loopstring.readme/.MainActivity t1}"; big ;;
  "shell pidof io.loopstring.readme")
    echo \$((n+1)) > "$T/smoke/pidof-calls"
    if [ "\$n" -ge "$1" ]; then echo "\${STUB_PIDS:-4242}"; else exit 1; fi ;;
  "shell am start -W"*) printf 'Status: ok\nLaunchState: UNKNOWN (0)\n' ;;
  "logcat -d"*)
    if [ -n "\${STUB_CRASH:-}" ]; then echo "F libc    : Fatal signal 11 (SIGSEGV), code 1, fault addr 0x0 in tid 9 (mqt_js), pid 4242 (opstring.readme)"; fi
    big ;;
  install*) echo Success; echo installed > "$T/smoke/installed" ;;
  *) : ;;
esac
STUB
  chmod +x "$T/smoke/bin/adb"; rm -f "$T/smoke/pidof-calls" "$T/smoke/installed"
  cp "$ROOT/scripts/lib/device.sh" "$T/smoke/repo/scripts/lib/"; cp "$ROOT/scripts/device-smoke.sh" "$T/smoke/repo/scripts/"
  ( cd "$T/smoke/repo" && { [ -d .git ] || { git init -q && git -c user.email=t@t -c user.name=t commit -q --allow-empty -m i && git checkout -q -b t; }; }
    a=android/app/build/outputs/apk/release/app-release.apk; touch "$a"
    if [ "${STUB_STAMP:-}" = stale ]; then printf '0000000000000000000000000000000000000000\nclean\n' > "$a.stamp"
    else printf '%s\nclean\n' "$(git rev-parse HEAD)" > "$a.stamp"; fi
    PATH="$T/smoke/bin:$PATH" bash scripts/device-smoke.sh 2>&1 ) > "$T/smoke/out"; echo $?
}
check "device-smoke passes when the app starts a moment late" "$(smoke_run 2)" 0
check "device-smoke fails loudly when the app never starts" "$(smoke_run 99)" 1
check "and says why" "$(grep -c 'FAIL: process not running' "$T/smoke/out")" 1
check "device-smoke passes on a clean run with MBs of dumpsys and logcat (no false lock or resume FAIL)" \
  "$(STUB_BIG=1 smoke_run 0)" 0
check "device-smoke catches a crash buried before MBs of logcat (F1)" "$(STUB_BIG=1 STUB_CRASH=1 smoke_run 0)" 1
check "and says why" "$(grep -c 'FAIL: crash logged' "$T/smoke/out")" 1
check "device-smoke matches the crash when pidof returns two pids (F3)" "$(STUB_PIDS='4242 4343' STUB_CRASH=1 smoke_run 0)" 1
check "device-smoke refuses an APK stamped for another commit (F2)" "$(STUB_STAMP=stale smoke_run 0)" 6
check "and installs nothing" "$([ -f "$T/smoke/installed" ] && echo installed || echo none)" none

exit $fail
