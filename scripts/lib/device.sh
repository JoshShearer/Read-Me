# Sourced by every script that installs on or drives the phone (AGENTS.md "Device work",
# .claude/commands/worktrees.md). The lock lives in the PRIMARY checkout: a lock inside a
# fresh worktree is free by construction and would guard nothing.
PKG=io.loopstring.readme
PRIMARY=$(dirname "$(git rev-parse --path-format=absolute --git-common-dir)")
DEVICE_LOCK="$PRIMARY/.claude/device.lock"
DEVICE_ON_EXIT=${DEVICE_ON_EXIT:-}
DEVICE_LOCK_OURS=0   # 1 only when this process created the lock and must release it
APK=android/app/build/outputs/apk/release/app-release.apk

_device_owner_line() {
  printf 'branch=%s commit=%s at=%s purpose=%s' "$(git branch --show-current)" \
    "$(git rev-parse --short HEAD)" "$(date -Iseconds)" "$1"
}

device_take() {
  local purpose=${1:?usage: device_take <purpose>}
  local ready other
  ready=$(adb devices | awk 'NR > 1 && $2 == "device"' | wc -l)
  other=$(adb devices | awk 'NR > 1 && NF && $2 != "device"' | wc -l)
  if [ "$ready" -ne 1 ] || [ "$other" -ne 0 ]; then
    echo "need exactly one authorized device; adb shows $ready ready, $other other" >&2
    exit 3
  fi
  mkdir -p "$PRIMARY/.claude"
  trap _device_exit EXIT
  if mkdir "$DEVICE_LOCK" 2>/dev/null; then
    DEVICE_LOCK_OURS=1
    # purpose=script:... marks a lock a device script took for itself; it is never adopted, so
    # two scripts in one lane cannot install over each other.
    printf '%s\n%s\n' "$(git rev-parse --show-toplevel)" "$(_device_owner_line "script:$purpose")" > "$DEVICE_LOCK/owner"
  elif _device_lock_held_by_this_lane; then
    # /ship, /verify, /worktrees and /run-tickets take the lock themselves and then call
    # npm run device:install. Reuse it, and leave it for the caller to release.
    echo "device slot already held by this lane; reusing it" >&2
  else
    trap - EXIT
    echo "device slot held by another lane:" >&2
    cat "$DEVICE_LOCK/owner" >&2 2>/dev/null || true
    echo "if that holder is gone (a killed script or session), release it by hand: rm -rf $DEVICE_LOCK" >&2
    exit 3
  fi
}

# Every lock taker (worktrees.md, run-tickets.md, ship.md, verify.md) writes the worktree path
# on line 1 and "branch=<name> ... purpose=<p>" on line 2. Ours = same worktree, same named
# branch (compared literally, not as a regex), and not a lock another device script took
# (purpose=script:...). A detached HEAD has no branch name, so it never adopts a lock. An
# ownerless lock (someone mid-mkdir, or an old command) is never ours.
_device_lock_held_by_this_lane() {
  local owner="$DEVICE_LOCK/owner" top branch line2
  [ -f "$owner" ] || return 1
  top=$(git rev-parse --show-toplevel); branch=$(git branch --show-current)
  [ -n "$branch" ] || return 1
  [ "$(sed -n 1p "$owner")" = "$top" ] || return 1
  line2=$(sed -n 2p "$owner")
  case "$line2" in
    *" purpose=script:"*) return 1 ;;
    "branch=$branch "*) return 0 ;;
    *) return 1 ;;
  esac
}

_device_exit() {
  local rc=$?
  if [ -n "$DEVICE_ON_EXIT" ]; then "$DEVICE_ON_EXIT" || true; fi
  if [ "$DEVICE_LOCK_OURS" = 1 ]; then rm -rf "$DEVICE_LOCK"; fi
  exit $rc
}

# Every adb answer is read whole into a variable before it is searched. Piping adb into
# `grep -q` under pipefail is wrong: grep exits at the first match, adb dies of SIGPIPE on a
# large answer (dumpsys and logcat -d run to MBs), the pipeline returns 141, and a match reads
# as a miss. device_has <text> <ERE> searches text that is already in hand.
device_has() { grep -qE -- "$2" <<<"$1"; }

# The phone has a secure lock screen that no script can dismiss. Stop early and say so.
device_require_unlocked() {
  local power window
  power=$(adb shell dumpsys power); window=$(adb shell dumpsys window)
  # Android 10 (the VRD-W09 tablet) has no isKeyguardShowing line; its KeyguardStateMonitor
  # says mIsShowing instead. Where isKeyguardShowing exists, only it decides. A locked phone must
  # never read as unlocked: any isKeyguardShowing=true wins, and on Android 10 only the first
  # mIsShowing under KeyguardStateMonitor counts, not one from another part of the dump.
  local unlocked=1 kg
  if device_has "$window" 'isKeyguardShowing='; then
    device_has "$window" 'isKeyguardShowing=true' && unlocked=0
    device_has "$window" 'isKeyguardShowing=false' || unlocked=0
  else
    kg=$(awk '/KeyguardStateMonitor/ { f = 1; next } f && /mIsShowing=/ { print; exit }' <<<"$window")
    device_has "$kg" 'mIsShowing=false' || unlocked=0
  fi
  if ! device_has "$power" 'mWakefulness=Awake' || [ "$unlocked" = 0 ]; then
    echo "the phone is asleep or locked: ask the owner to unlock it, leave the screen on, rerun" >&2
    exit 5
  fi
}

# Spike runs must name the build that was measured (AGENTS.md 17): a clean tree, AND an APK
# stamped by `npm run build:release` from exactly this commit while the tree was clean. A clean
# tree alone is not enough: commit, build, commit again and the APK is the older commit's.
device_require_measured_build() {
  local stamp="$APK.stamp"
  if [ -n "$(git status --porcelain -- . ':(exclude).claude')" ]; then
    echo "uncommitted changes: commit, then npm run build:release" >&2
    git status --short -- . ':(exclude).claude' >&2
    exit 6
  fi
  if [ ! -f "$stamp" ] || [ "$(sed -n 1p "$stamp")" != "$(git rev-parse HEAD)" ] \
     || [ "$(sed -n 2p "$stamp")" != clean ]; then
    echo "the APK was not built from this clean commit (stamp: $(tr '\n' ' ' < "$stamp" 2>/dev/null || echo none)); run npm run build:release" >&2
    exit 6
  fi
}

# device_crash_seen <pid>: reads ALL of logcat on stdin (see device_has); exits 0 if it shows a
# crash of $PKG.
# The native libc "Fatal signal ... pid N (comm)" line carries /proc/self/comm, which ART
# truncates to the last 15 chars of the package, so it is matched by pid, not by name.
# debuggerd's ">>> <package> <<<" line and Java's "Process: <package>, PID" line carry the name.
device_crash_seen() {
  local pid=${1:-none} log
  log=$(cat)
  device_has "$log" "Fatal signal .*[^0-9]pid $pid [(]|>>> $PKG <<<|AndroidRuntime: Process: $PKG,"
}

# device_clear_app: clears Read Me's data and waits until its old tasks are gone. pm clear
# removes them asynchronously, and a share started meanwhile was killed with them ("Killing
# ... remove task", reference device, 2026-10-02, build 5695d8b, after device:bridge left
# tasks open). Waits at most 10 s.
device_clear_app() {
  # From the home screen: clearing an app that is on screen relaunched it into a new task
  # (2026-10-03, after device:bridge left Settings open), so the wait below never ended.
  adb shell input keyevent KEYCODE_HOME
  adb shell pm clear "$PKG" >/dev/null
  for _ in $(seq 20); do
    adb shell dumpsys activity activities | grep -qE "A=[0-9]+:$PKG\b" || return 0
    sleep 0.5
  done
  echo "device_clear_app: $PKG still has tasks after 10 s" >&2
  return 1
}

# Installs only an APK that `npm run build:release` stamped from this HEAD, and records the
# stamp (commit and clean/dirty), not HEAD: commit, build, commit again and HEAD names a build
# the phone is not running. A dirty stamp is allowed here and recorded as dirty; spike runs
# also call device_require_measured_build, which requires clean.
device_install_release() {
  local stamp="$APK.stamp" built
  [ -f "$APK" ] || { echo "missing $APK; run npm run build:release" >&2; exit 1; }
  built=$(sed -n 1p "$stamp" 2>/dev/null || true)
  if [ "$built" != "$(git rev-parse HEAD)" ]; then
    echo "the APK was not built from HEAD (stamp: ${built:-none}); run npm run build:release" >&2
    exit 6
  fi
  adb install -r "$APK"
  mkdir -p "$PRIMARY/.claude/scratch"
  printf '%s\nbranch=%s commit=%s tree=%s at=%s purpose=installed\n' "$(git rev-parse --show-toplevel)" \
    "$(git branch --show-current)" "${built:0:7}" "$(sed -n 2p "$stamp")" "$(date -Iseconds)" \
    > "$PRIMARY/.claude/scratch/device-installed-from"
  echo "installed ${built:0:7} ($(sed -n 2p "$stamp")) on the phone (replaces whatever build was there)"
}
