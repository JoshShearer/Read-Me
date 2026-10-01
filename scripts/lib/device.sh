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
    printf '%s\n%s\n' "$(git rev-parse --show-toplevel)" "$(_device_owner_line "$purpose")" > "$DEVICE_LOCK/owner"
  elif _device_lock_held_by_this_lane; then
    # /ship, /verify, /worktrees and /run-tickets take the lock themselves and then call
    # npm run device:install. Reuse it, and leave it for the caller to release.
    echo "device slot already held by this lane; reusing it" >&2
  else
    trap - EXIT
    echo "device slot held by another lane:" >&2
    cat "$DEVICE_LOCK/owner" >&2 2>/dev/null || true
    exit 3
  fi
}

# Every lock taker (worktrees.md, run-tickets.md, ship.md, verify.md) writes the worktree path
# on line 1 and "branch=<name> ..." on line 2. Ours = same worktree AND same named branch.
# A detached HEAD has no branch name, so it never adopts a lock. An ownerless lock (someone
# mid-mkdir, or an old command) is never ours.
_device_lock_held_by_this_lane() {
  local owner="$DEVICE_LOCK/owner" top branch
  [ -f "$owner" ] || return 1
  top=$(git rev-parse --show-toplevel); branch=$(git branch --show-current)
  [ -n "$branch" ] || return 1
  [ "$(sed -n 1p "$owner")" = "$top" ] && grep -q "^branch=$branch " "$owner"
}

_device_exit() {
  local rc=$?
  if [ -n "$DEVICE_ON_EXIT" ]; then "$DEVICE_ON_EXIT" || true; fi
  if [ "$DEVICE_LOCK_OURS" = 1 ]; then rm -rf "$DEVICE_LOCK"; fi
  exit $rc
}

# The phone has a secure lock screen that no script can dismiss. Stop early and say so.
device_require_unlocked() {
  if ! adb shell dumpsys power | grep -q 'mWakefulness=Awake' \
     || ! adb shell dumpsys window | grep -q 'isKeyguardShowing=false'; then
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

device_install_release() {
  [ -f "$APK" ] || { echo "missing $APK; run npm run build:release" >&2; exit 1; }
  adb install -r "$APK"
  mkdir -p "$PRIMARY/.claude/scratch"
  printf '%s\n%s\n' "$(git rev-parse --show-toplevel)" "$(_device_owner_line installed)" \
    > "$PRIMARY/.claude/scratch/device-installed-from"
  echo "installed $(git rev-parse --short HEAD) on the phone (replaces whatever build was there)"
}
