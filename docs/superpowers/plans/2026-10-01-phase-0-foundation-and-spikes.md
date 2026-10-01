# Phase 0: Foundation and Spikes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship a bare React Native 0.87 scaffold with real quality gates, then answer SPIKE-01 through SPIKE-06 on the reference device and merge only the recorded answers to `main`.

**Architecture:** One `feature/` branch delivers the scaffold, the gates (npm scripts, CI, the device-slot helper, F-Droid and license checks), and two ADRs. Each spike then gets its own Linear issue and `spike/rea-N-*` branch. Spikes that share probe code are stacked: SPIKE-02 on SPIKE-03, and SPIKE-06 and SPIKE-01 on SPIKE-05. JS spikes run in the **release** build, triggered by an `am start --es spike <name>` extra that reaches the root component as an initial prop. Native spikes run in a Kotlin foreground service. adb cannot start that service directly, because only root and system may access an unexported component. So scripts start the exported `MainActivity` with `--ez nativeSpike true`, and it forwards the extras to the service while in the foreground. Probe code is committed on a `spike/rea-N-<slug>` **probe branch** that is pushed but never opened as a PR. The recorded answer (`srs.md`, plus an ADR when a decision changes) is cherry-picked onto a `spike/rea-N-<slug>-answer` branch cut from `main`, and that branch is what `/ship` opens as a PR. So only answers reach `main` (owner decision 2026-10-01; `/ship` pushes and PRs the whole branch, so it can't filter commits that are already there).

**Tech Stack:** React Native 0.87.1 (bare, Hermes, new architecture), `@react-native-community/cli` 20.2.0, TypeScript, Jest (`@react-native/jest-preset`), ESLint 8, Kotlin 2.2.0, Gradle 9.4.1, JUnit 4.13.2, GitHub Actions. Spike-only: `@mozilla/readability` 0.6.0, `linkedom` 0.18.13, `@babel/plugin-transform-export-namespace-from` 7.29.7. Dev tools: fdroidserver 2.4.5, `license-checker-rseidelsohn` 5.0.1.

**Spec:** `srs.md` (SPEC-001). Rules: `AGENTS.md`. Architecture: `CONTEXT.md`. Roadmap and SRS review: `docs/superpowers/plans/2026-10-01-roadmap.md`. Revision 2 resolves the first critique (2026-10-01, BLOCK 35, F1-F15). Revision 3 resolves the second (CONCERNS 58, F1-F12). Revision 4 resolves the third (CONCERNS 60, F1-F13; `/verify` and `/ship` lock owner format normalised in the repo).

## Global Constraints

- Application id and Kotlin namespace: `io.loopstring.readme`. JS component name: `ReadMe`. Display name: `Read Me`.
- React Native `0.87.1`, CLI `20.2.0`, Node `>= 22.11.0` (this machine: v24.21.0), JDK 21.
- Android: compileSdk 37, targetSdk 36, minSdk 24 (template defaults, kept until an ADR changes them), build-tools 37.0.0, NDK 27.1.12297006. The device is API 37, so every spike runs **targetSdk 36 behaviour on an API 37 device**, and every answer says so.
- AGENTS.md non-negotiables 1-14 apply to spike code too. Explicitly:
  - **No item text, URL path or query in any log** (1). Spike logs carry counts, ids, states, durations and fixed route names only.
  - **The bridge token is never logged and never put in a URL** (4). Spike scripts generate the token on the host and pass it as an intent extra.
  - **Rate is applied once, by the engine** (9). Every `TextToSpeech` call sets its rate explicitly. Never rely on the system default: on the reference device `secure tts_default_rate` is `235`, read 2026-10-01.
- R-M09.4: `android:allowBackup="false"` stays set. R-M06: the manifest declares the `TTS_SERVICE` query.
- R-M12: the bridge binds `127.0.0.1` explicitly, never `InetAddress.getLoopbackAddress()`.
- Every script that installs on or drives the phone sources `scripts/lib/device.sh`. That helper takes `$PRIMARY/.claude/device.lock/` with `mkdir`, writes the `owner` file in the `worktrees.md` format, requires exactly one device in state `device` and an unlocked, awake screen, and releases the lock on exit.
- **Device runs are owner-attended.** The phone has a lock screen (`lock_settings get-disabled` is `false`), and `KEYCODE_SLEEP`, which every gap run uses, locks it. No script can unlock it. Every device script stops with exit 5 if the phone is asleep or locked, and the executor then asks the owner to unlock it. Do not try to work around the lock.
- No commit lands on `main` directly. Branch per issue: `feature/rea-{N}-{slug}` or `spike/rea-{N}-{slug}` (`.claude/linear.md`). Stage explicit paths, never `git add -A`. Ship with `/ship`.
- License policy (owner decision 2026-10-01, ADR 0002): production npm dependencies must be OSI-licensed, except CC-BY-4.0 **data-only** packages listed by name in `scripts/check-licenses.mjs`.
- Reference device: Pixel 9 Pro XL, GrapheneOS, Android 17, adb serial `48071FDAS004PV`. Engines installed: `app.grapheneos.speechservices` (default) and `com.google.android.tts`.
- Timing spikes use **release** builds only (`npm run build:release`). Debug builds run JS from Metro.
- Docs and comments use no em-dash characters.

## Review Focus

1. **Release `console.log` not reaching logcat.** If it doesn't, every JS spike would time out and look like a hang. Expected: a trivial `ping` probe prints `SPIKE_RESULT` from the release APK. Pinned in Task 4, Step 7.
2. **The phone left offline, or a spike service left speaking, after an interrupted run.** Expected: on every exit path, airplane mode is off, Wi-Fi is back to its prior value, the app is force-stopped, and the device slot is released. Pinned in Task 6, Step 10.
3. **Port 8787 already bound.** The prototype `io.loopstring.ttsbridge` is installed and binds the same port. Expected: the spike bridge reports `bind-failed` and the service stays alive. Pinned in Task 8, Step 6.
4. **A native spike command that never reached the service.** This was the original plan's silent failure: `am` refused the unexported service and the script waited out its timeout. Expected: a direct adb start is refused on the device, and `spike_native` exits 4 within about 10 s when no `SPIKE_FGS` line appears. Pinned in Task 6, Step 9.
5. **The TTS engine failing to bind.** If that happens synchronously, `onInit` can run inside the `TextToSpeech` constructor before the field is assigned. Expected: a reported `init-failed` or `init-timeout`, never a NullPointerException. Pinned by deferring `onInit` through a `Handler` in `GapProbe`, `SynthLoad` and `SpikeBridge`, plus the 20 s init timeout in `GapProbe` (Task 6).

## Verified before writing this revision (2026-10-01)

These were run on this machine or read from the device. Steps that depend on them cite them here instead of re-asserting them.

- The RN 0.87.1 template is `buildToolsVersion 37.0.0`, `compileSdk 37`, `targetSdk 36`, `minSdk 24`, `ndkVersion 27.1.12297006`, `kotlinVersion 2.2.0`, Gradle `9.4.1`. `sdkmanager --list` offers `platforms;android-37.0`, `build-tools;37.0.0`, `ndk;27.1.12297006` and `cmake;3.22.1`.
- `DefaultReactActivityDelegate` is `public open class` and `ReactActivityDelegate.getLaunchOptions()` is `protected @Nullable Bundle` (RN v0.87.1 source).
- AOSP `ActivityManager.canAccessUnexportedComponents` allows only ROOT and SYSTEM, and `ActiveServices` rejects other callers with "not exported from uid". So adb (uid 2000) cannot start an unexported service. This was read in the source, not yet reproduced on the device; Task 6, Step 9 reproduces it.
- In a scratch copy of the template, with this revision's TS files: `jest` passed 6 tests in 2 suites, `tsc --noEmit` passed, and `eslint .` passed. That run used the `transformIgnorePatterns` below and the export-namespace-from Babel plugin. A `--dev false` Metro bundle was 10,374,701 bytes, and `hermesc -O` compiled it to 15,726,485 bytes. Without the plugin, the bundle fails at `htmlparser2/dist/esm/index.js`. Without the ignore patterns, Jest fails at `linkedom/cjs/shared/matches.js`.
- The only `new XMLHttpRequest()` in that bundle is RN's own `whatwg-fetch` polyfill, and linkedom's sources contain no network calls.
- `scripts/check-licenses.mjs` (Task 2) on the stock template plus spike deps: 471 production packages, 1 recorded data exception (`caniuse-lite`, CC-BY-4.0), exit 0.
- The F-Droid source scan of the stock template flags only `Found dependency file without lock at package.json`.
- Read-only on the device: `cmd connectivity airplane-mode`, `/system/bin/ping` and `/system/bin/svc` exist; `mWakefulness=Awake` and `isKeyguardShowing=false` are readable from `dumpsys`; `md.obsidian/.MainActivity` is Obsidian's launcher activity; `openssl` exists on this machine.

---

## Branches and issues

Create the issues with `/create-issue` (team `REA`). Use each issue's number `N` in its branch name. If the `linear-rea` MCP server is not authenticated yet, `/start-issue`'s degradation rule applies: create the branch anyway and record the intended issue.

| Task | Issue title | Branch | Branches from | What merges |
|---|---|---|---|---|
| 0 | (none: owner housekeeping) | `main` | - | governance files, by the owner |
| 1-2 | `Scaffold: bare RN 0.87 app with quality gates` | `feature/rea-N-scaffold` | `main` | everything (PR) |
| 3 | `SPIKE-04: F-Droid-clean bare React Native` | answer branch only (no probe) | `main` after 1-2 | `srs.md` answer |
| 4 | `SPIKE-03: Intl.Segmenter on Hermes` | probe `spike/rea-N-hermes-segmenter` | `main` after 1-2 | `srs.md` answer, via `-answer` branch |
| 5 | `SPIKE-02: Readability on Hermes` | probe `spike/rea-N-hermes-readability` | Task 4 probe branch | `srs.md` answer (+ ADR if WebView), via `-answer` branch |
| 6 | `SPIKE-05: gapless queueing at 2x` | probe `spike/rea-N-gapless-2x` | `main` after 1-2 | `srs.md` answer, via `-answer` branch |
| 7 | `SPIKE-06: two TextToSpeech instances` | probe `spike/rea-N-two-tts-instances` | Task 6 probe branch | `srs.md` answer (+ ADR if contention), via `-answer` branch |
| 8 | `SPIKE-01: bridge service type in background` | probe `spike/rea-N-bridge-fgs-type` | Task 6 probe branch | `srs.md` answer + ADR (service type), via `-answer` branch |
| 9 | (closing work in the last spike's `/finish`) | `feature/rea-N-*` docs follow-up | `main` | AGENTS.md known state, roadmap |

Branch names are illustrative: `/start-issue` derives the slug from the issue title (`.claude/linear.md`), so use whatever it creates. **Stacked probe branches** (Tasks 5, 7, 8) are created with `/start-issue` too, so the status and `base-branch` are set, and then `git merge --no-edit <parent probe branch>` brings in the parent's probe. **Every spike answer ships with the procedure in Task 9, "Shipping a spike answer"**.

ADR numbers are reserved now so parallel spike branches cannot collide: 0003 extraction in a WebView (SPIKE-02, only if it fails F17), 0004 TTS contention policy (SPIKE-06, only if needed), 0005 foreground-service types (SPIKE-01). An unused number stays unused.

Tasks 3, 4 and 6 are independent of each other. All but Task 3 need the phone, and only one lane may hold it, so run the device tasks in sequence.

## File Structure

```text
SCAFFOLD (feature branch, merges)
package.json, package-lock.json, app.json, index.js, App.tsx        RN template, renamed
babel.config.js, metro.config.js, jest.config.js, tsconfig.json     template
.eslintrc.js, .prettierrc.js, .watchmanconfig, .bundle/              template
__tests__/App.test.tsx                                               template test
android/...                                                          template + TTS query
.gitignore                                                           existing file + template entries
scripts/lib/device.sh          device slot: lock, one-device and unlocked checks, install, marker
scripts/device-install.sh      npm run device:install
scripts/device-smoke.sh        npm run device:smoke (launch + no FATAL; grows with R-M14)
scripts/check-licenses.mjs     OSI check with recorded data exceptions
scripts/fdroid-scan.sh         source scan, APK scan, Gradle non-free deps, licenses
.github/workflows/ci.yml       js job + android job
BUILDING.md                    clean-checkout build
docs/adr/0001-kotlin-owns-database.md
docs/adr/0002-cc-by-data-packages.md
AGENTS.md, CONTEXT.md, srs.md  gates block, source layout, amendments

SPIKE BRANCHES (probe code never merges)
src/spikes/run.ts, segmenterProbe.ts               SPIKE-03
src/spikes/extractProbe.ts, fixtures.generated.ts  SPIKE-02 (generated file gitignored)
__tests__/spikes.test.ts                            SPIKE-03, SPIKE-02
spikes/fixtures/*.html, scripts/fetch-spike-fixtures.sh, scripts/make-spike-fixtures.mjs   SPIKE-02
scripts/spike-js.sh                                JS spike runner
scripts/lib/spike.sh                               native spike helpers
android/.../MainActivity.kt                        spike prop (SPIKE-03), native forwarding (SPIKE-05)
android/.../spike/{SpikeText,GapStats,GapProbe,SynthLoad,SpikeService}.kt   SPIKE-05/06
android/.../spike/SpikeBridge.kt                   SPIKE-01
android/app/src/test/.../spike/*Test.kt            SPIKE-05
android/app/src/main/assets/spike/corpus.txt, scripts/fetch-spike-corpus.sh   SPIKE-05
scripts/spike-gap.sh                               SPIKE-05/06
scripts/spike-bridge.sh, scripts/obsidian-cdp-probe.mjs   SPIKE-01
```

---

### Task 0: Preconditions (owner)

**Files:** none created by the plan.

- [x] **Step 1: Governance files committed on `main`**

Done 2026-10-01: the governance files were committed on `main` in `b31782b`, and the opencode permission hardening in `e1b87c4`, `be26b11` and `45e7786`. This step is now only the check, because `/start-issue` refuses to branch over a dirty tree.

```bash
cd /home/joshshearer/Documents/Dev/Read-Me
git status --short   # must be empty before Task 1
```

Expected: no output.

- [ ] **Step 2: Create the Linear issues in the "Branches and issues" table** with `/create-issue`. Spike titles start `SPIKE-0N:` (`.claude/linear.md`).

---

### Task 1: Scaffold that builds and runs on the phone

Branch: `/start-issue <scaffold issue>` creates `feature/rea-N-scaffold`.

**Files:**
- Create: the RN scaffold at the repo root, from `@react-native-community/cli` 20.2.0
- Modify: `.gitignore` (merge in the template entries; the existing file stays the base)
- Modify: `android/app/src/main/AndroidManifest.xml` (TTS query)
- Create: `scripts/lib/device.sh`, `scripts/device-install.sh`, `scripts/device-smoke.sh`
- Modify: `package.json` (`typecheck`, `build:release`, `device:install`, `device:smoke` scripts)
- Test: `__tests__/App.test.tsx` (template), `npm run device:smoke`

**Interfaces:**
- Produces:
  - `scripts/lib/device.sh`, sourced, provides:
    - `device_take <purpose>`: exits 3 if another lane holds the slot or there isn't exactly one device. If this lane already holds it (a `/ship` or `/verify` run that took the lock before calling `npm run device:install`), it reuses the lock and leaves releasing it to the caller.
    - `device_require_unlocked`: exits 5 if the screen is off or locked.
    - `device_require_committed`: exits 6 if the working tree has uncommitted changes (spike scripts call it so every answer names the build that was measured).
    - `device_install_release`: installs `android/app/build/outputs/apk/release/app-release.apk` and writes `$PRIMARY/.claude/scratch/device-installed-from`.
    - `DEVICE_ON_EXIT`: the name of a function that runs before the lock is released.
    - `PKG=io.loopstring.readme`.
  - npm scripts `typecheck`, `lint`, `test`, `build:release`, `device:install`, `device:smoke`.

- [ ] **Step 1: Install the Android SDK pieces**

```bash
SM=$HOME/Android/Sdk/cmdline-tools/latest/bin/sdkmanager
yes | $SM --licenses >/dev/null
$SM "platform-tools" "platforms;android-37.0" "build-tools;37.0.0" "ndk;27.1.12297006" "cmake;3.22.1"
$SM --list_installed | grep -E "android-37|37.0.0|27.1.12297006|cmake"
```

Expected: all four packages are listed. If Gradle later asks for a different platform name, it installs it itself, because the licenses are accepted.

- [ ] **Step 2: Generate the scaffold and copy it in without clobbering the repo's `.gitignore`**

```bash
SCRATCH=$(mktemp -d)
npx -y @react-native-community/cli@20.2.0 init ReadMe --version 0.87.1 \
  --package-name io.loopstring.readme --title "Read Me" \
  --skip-git-init --skip-install --directory "$SCRATCH/ReadMe"
cd /home/joshshearer/Documents/Dev/Read-Me
rsync -a --exclude .gitignore --exclude ios --exclude Gemfile --exclude README.md \
  "$SCRATCH/ReadMe/" ./
# Merge: keep every existing line, append template lines that are not already present.
{ echo; echo "# From the RN 0.87 template"; grep -vxF -f .gitignore "$SCRATCH/ReadMe/.gitignore" | grep -v '^\s*$'; } >> .gitignore
grep -nE "device.lock|last-critique|pipeline-state|node_modules|local.properties" .gitignore
printf 'sdk.dir=%s\n' "$HOME/Android/Sdk" > android/local.properties
git status --short
```

Expected: the `grep` shows that the `.claude/device.lock/`, `.claude/last-critique.md` and pipeline-state lines survived, alongside `node_modules/` and `android/local.properties`. `git status` lists the template files and `.gitignore` as modified, and does not list `android/local.properties`.

- [ ] **Step 3: Add the TTS engine query (R-M06)**

In `android/app/src/main/AndroidManifest.xml`, add this directly after the `INTERNET` permission line:

```xml
    <!--
      R-M06. An app targeting API 30+ cannot see or bind a TTS engine without this query.
      Proven causal by single-variable A/B on the reference device (srs.md, Background).
    -->
    <queries>
        <intent>
            <action android:name="android.intent.action.TTS_SERVICE" />
        </intent>
    </queries>
```

`android:allowBackup="false"` is already on `<application>` in the template. Confirm it with `grep allowBackup android/app/src/main/AndroidManifest.xml`.

- [ ] **Step 4: Write the device-slot helper**

`scripts/lib/device.sh`:

```bash
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

# Spike runs must name the commit that was measured (AGENTS.md 17), so refuse a dirty tree.
device_require_committed() {
  if [ -n "$(git status --porcelain)" ]; then
    echo "uncommitted changes: commit first so the answer can cite the measured build" >&2
    git status --short >&2
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
```

`scripts/device-install.sh`:

```bash
#!/usr/bin/env bash
# npm run device:install - install the current release build on the one attached phone.
set -euo pipefail
cd "$(dirname "$0")/.."
. scripts/lib/device.sh
device_take interactive
device_install_release
```

`scripts/device-smoke.sh`:

```bash
#!/usr/bin/env bash
# npm run device:smoke - scripted on-device checks (R-M14). Today: install, cold launch,
# the activity is resumed, and no FATAL for our package. Later phases add their checks here.
set -euo pipefail
cd "$(dirname "$0")/.."
. scripts/lib/device.sh
device_take interactive
device_require_unlocked
device_install_release
adb shell am force-stop "$PKG"
adb logcat -c
adb shell am start -W -n "$PKG/.MainActivity" | grep -E "Status|LaunchState|TotalTime"
sleep 3
fail=0
[ -n "$(adb shell pidof "$PKG" | tr -d '\r')" ] || { echo "FAIL: process not running"; fail=1; }
adb shell dumpsys activity activities | grep -q "topResumedActivity.*$PKG/.MainActivity" \
  || { echo "FAIL: MainActivity not resumed"; fail=1; }
if adb logcat -d -b crash,main | grep -E "FATAL|AndroidRuntime" | grep -q "$PKG"; then
  echo "FAIL: crash logged"; fail=1
fi
echo "device:smoke $([ $fail -eq 0 ] && echo PASS || echo FAIL)"
exit $fail
```

```bash
chmod +x scripts/device-install.sh scripts/device-smoke.sh
```

- [ ] **Step 5: Add the npm scripts**

In `package.json` `"scripts"`, keep the template's `lint`, `test` and `start`. Remove `ios`, and add:

```json
    "typecheck": "tsc --noEmit",
    "build:release": "cd android && ./gradlew --quiet assembleRelease",
    "device:install": "scripts/device-install.sh",
    "device:smoke": "scripts/device-smoke.sh",
```

Check every lock shape before relying on it. `ship.md` and `verify.md` write the same two-line owner format as `worktrees.md` (normalised in commit "chore: one device-lock owner format", 2026-10-01):

```bash
P=$(dirname "$(git rev-parse --path-format=absolute --git-common-dir)"); L="$P/.claude/device.lock"
mkdir -p "$P/.claude"
take() { bash -c '. scripts/lib/device.sh; device_take interactive; echo took'; echo "rc=$?"; }
echo "-- held by this lane (the /ship and /verify shape): reuse, keep"
mkdir "$L" && printf '%s\nbranch=%s commit=x at=x purpose=interactive\n' "$(git rev-parse --show-toplevel)" "$(git branch --show-current)" > "$L/owner"
take; ls -d "$L" >/dev/null && echo "caller lock kept"; rm -rf "$L"
echo "-- ownerless lock: someone else, stop"
mkdir "$L"; take; ls -d "$L" >/dev/null && echo "foreign lock untouched"; rm -rf "$L"
echo "-- another worktree: stop"
mkdir "$L" && printf '/elsewhere\nbranch=%s commit=x at=x purpose=interactive\n' "$(git branch --show-current)" > "$L/owner"
take; rm -rf "$L"
echo "-- free: take and release"
take; ls -d "$L" 2>/dev/null || echo "own lock released"
```

Expected, in order: `reusing it`, `took`, `rc=0`, `caller lock kept`; `held by another lane`, `rc=3`, `foreign lock untouched`; `held by another lane`, `rc=3`; `took`, `rc=0`, `own lock released`.

- [ ] **Step 6: Install, then run the JS gates**

```bash
npm install
npm run typecheck && npm run lint && npm test
```

Expected: `package-lock.json` is created, typecheck and lint exit 0, and Jest reports `1 passed`.

- [ ] **Step 7: Build and smoke-test on the phone**

```bash
npm run build:release
npm run device:smoke
ls "$(dirname "$(git rev-parse --path-format=absolute --git-common-dir)")/.claude/device.lock" 2>/dev/null || echo "slot released"
```

Expected:
- The build succeeds. The first build downloads Gradle 9.4.1 and the RN artifacts, so it takes minutes.
- `device:smoke PASS`.
- `slot released`.
- `adb exec-out screencap -p > /tmp/claude-readme-shot.png`, opened with the Read tool, shows the RN welcome screen.

- [ ] **Step 8: Commit (explicit paths)**

```bash
git add package.json package-lock.json app.json index.js App.tsx babel.config.js metro.config.js \
  jest.config.js tsconfig.json .eslintrc.js .prettierrc.js .watchmanconfig .bundle __tests__ android \
  .gitignore scripts/lib/device.sh scripts/device-install.sh scripts/device-smoke.sh
git status --short   # nothing staged from .claude/, no local.properties, no build output
git commit -m "chore: scaffold bare React Native 0.87.1 app (io.loopstring.readme)

TTS_SERVICE query (R-M06), device-slot helper, device:install and device:smoke."
```

Before committing, check `git status --short`: any file the template produced that isn't in the list (`ls -a`) is either added explicitly or deliberately left out, never swept in.

---

### Task 2: Gates, CI, license policy, ADRs, and docs

Same branch as Task 1. One PR via `/ship`.

**Files:**
- Create: `scripts/check-licenses.mjs`, `scripts/fdroid-scan.sh`, `.github/workflows/ci.yml`, `BUILDING.md`
- Create: `docs/adr/0001-kotlin-owns-database.md`, `docs/adr/0002-cc-by-data-packages.md`
- Modify: `AGENTS.md` (Quality gates block, Known state), `CONTEXT.md` (source layout, the DB note), `srs.md` (amendments for both ADRs), `.gitignore` (`.venv-fdroid/`)

**Interfaces:**
- Consumes: the npm scripts from Task 1
- Produces: `node scripts/check-licenses.mjs` (exit 0 = clean; lists every violation) and `scripts/fdroid-scan.sh` (runs all four checks, exit 0 only if all pass). SPIKE-04 (Task 3) runs both.

- [ ] **Step 1: Write `scripts/check-licenses.mjs`**

```js
// R-M13 / AGENTS.md 14: every production npm dependency must be OSI-licensed. Reports every
// violation (not just the first) and exits 1 if there is any. The only exceptions are listed
// in DATA_EXCEPTIONS, each recorded in docs/adr/0002-cc-by-data-packages.md.
import {execFileSync} from 'node:child_process';

const OSI = new Set([
  'MIT', 'ISC', 'Apache-2.0', 'BSD-2-Clause', 'BSD-3-Clause', '0BSD', 'BlueOak-1.0.0',
  'Unlicense', 'Python-2.0', 'Zlib', 'MPL-2.0',
]);
// Data-only packages (no executable code reaches the app), owner decision 2026-10-01.
const DATA_EXCEPTIONS = {'caniuse-lite': 'CC-BY-4.0'};

function allowed(expr) {
  const e = expr.replace(/[()]/g, '').trim();
  if (e.includes(' OR ')) return e.split(' OR ').some(allowed);
  if (e.includes(' AND ')) return e.split(' AND ').every(allowed);
  return OSI.has(e);
}

const raw = execFileSync('npx', ['-y', 'license-checker-rseidelsohn@5.0.1', '--production',
  '--excludePrivatePackages', '--json'], {encoding: 'utf8', maxBuffer: 64 * 1024 * 1024});
const pkgs = JSON.parse(raw);
const bad = [];
let excepted = 0;
for (const [id, info] of Object.entries(pkgs)) {
  const name = id.slice(0, id.lastIndexOf('@'));
  const lic = [].concat(info.licenses ?? 'UNKNOWN').join(' OR ');
  if (allowed(lic)) continue;
  if (DATA_EXCEPTIONS[name] === lic) { excepted++; continue; }
  bad.push(`${id}: ${lic}`);
}
console.log(`${Object.keys(pkgs).length} production packages, ${excepted} recorded data exception(s)`);
for (const b of bad) console.log(`NOT ALLOWED ${b}`);
process.exit(bad.length ? 1 : 0);
```

Run: `node scripts/check-licenses.mjs`
Expected: `N production packages, 1 recorded data exception(s)`, exit 0. The same script on the template plus spike deps gave 471 packages (see "Verified before writing").

- [ ] **Step 2: Write `scripts/fdroid-scan.sh`**

```bash
#!/usr/bin/env bash
# R-M13 / AGENTS.md 14: F-Droid-style checks. Runs all four and exits 0 only if all pass.
#   1. fdroidserver source scan of a clean export of HEAD (what F-Droid's builder sees).
#   2. fdroidserver binary scan of the release APK (known non-free classes).
#   3. No Play Services / Firebase / Crashlytics in the resolved release runtime classpath.
#   4. Production npm licenses (scripts/check-licenses.mjs, ADR 0002).
set -uo pipefail
cd "$(dirname "$0")/.."
export ANDROID_HOME=${ANDROID_HOME:-$HOME/Android/Sdk}
VENV=.venv-fdroid
[ -x "$VENV/bin/fdroid" ] || { python3 -m venv "$VENV" && "$VENV/bin/pip" -q install fdroidserver==2.4.5; } || exit 1
fail=0

echo "== 1. source scan of clean HEAD export"
SRC=$(mktemp -d)
git archive HEAD | tar -x -C "$SRC"
"$VENV/bin/python" - "$SRC" <<'PY' || fail=1
import sys, logging
logging.basicConfig(level=logging.WARNING, format="%(levelname)s %(message)s")
from fdroidserver import common, scanner
common.get_config()
n = scanner.scan_source(sys.argv[1])
print("source problems:", n)
sys.exit(1 if n else 0)
PY

echo "== 2. APK binary scan"
APK=android/app/build/outputs/apk/release/app-release.apk
if [ -f "$APK" ]; then "$VENV/bin/fdroid" scanner --exit-code "$APK" || fail=1
else echo "missing $APK; run npm run build:release"; fail=1; fi

echo "== 3. non-free Gradle dependencies (resolved tree)"
# Capture first: a failed gradlew piped straight into grep would read as "none found".
DEPS=$(mktemp)
if ! ( cd android && ./gradlew --quiet :app:dependencies --configuration releaseRuntimeClasspath ) > "$DEPS"; then
  echo "gradlew dependencies failed"; fail=1
elif grep -niE "com\.google\.android\.gms|firebase|crashlytics|play-services|com\.google\.android\.play" "$DEPS"; then
  echo "non-free dependency found"; fail=1
else echo "none"; fi

echo "== 4. npm production licenses"
node scripts/check-licenses.mjs || fail=1

echo "== result: $([ $fail -eq 0 ] && echo CLEAN || echo PROBLEMS)"
exit $fail
```

```bash
chmod +x scripts/fdroid-scan.sh
printf '\n# fdroidserver venv for scripts/fdroid-scan.sh\n.venv-fdroid/\n' >> .gitignore
```

- [ ] **Step 3: Write the CI workflow**

`.github/workflows/ci.yml`:

```yaml
name: ci
on:
  push:
    branches: [main]
  pull_request:

jobs:
  js:
    runs-on: ubuntu-24.04
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with:
          node-version: 22
          cache: npm
      - run: npm ci
      - run: npm run typecheck
      - run: npm run lint
      - run: npm test
      - run: node scripts/check-licenses.mjs

  android:
    runs-on: ubuntu-24.04
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with:
          node-version: 22
          cache: npm
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 21
      - run: npm ci
      - name: Android SDK packages
        run: |
          SM="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
          yes | "$SM" --licenses > /dev/null
          "$SM" "platforms;android-37.0" "build-tools;37.0.0" "ndk;27.1.12297006" "cmake;3.22.1"
      - name: Kotlin unit tests and release build
        working-directory: android
        run: ./gradlew --no-daemon testDebugUnitTest assembleRelease
      - name: No non-free dependencies in the resolved tree
        working-directory: android
        run: |
          ./gradlew --no-daemon --quiet :app:dependencies --configuration releaseRuntimeClasspath > deps.txt
          ! grep -iE "com\.google\.android\.gms|firebase|crashlytics|play-services|com\.google\.android\.play" deps.txt
```

- [ ] **Step 4: Write the two ADRs**

`docs/adr/0001-kotlin-owns-database.md`:

```markdown
# ADR 0001: Kotlin owns the database

- Status: accepted
- Date: 2026-10-01
- Deciders: owner
- Amends: srs.md "Method" (TypeScript modules table, Data model)

## Context

srs.md placed `library` (SQLite) in TypeScript. But three writers run while the JS runtime may
not be alive: share intake creates an item after the share activity finishes (R-M02), the
Fetcher completes in the background (R-M03), and PlaybackService saves the position after every
sentence and archives the item with the screen off (R-M07, R-M11). Roadmap finding F11.

## Decision

The Kotlin `Store` holds the only SQLite connection (platform `android.database.sqlite`, no
dependency). JS reads and writes only through `ReadMeSpeech`. The TS `library` module is a typed
facade over those calls.

## Consequences

- No npm SQLite library, so SPIKE-04 has no SQLite dependency to vet.
- Schema and migrations are Kotlin, unit-tested in Kotlin. TS tests of `library` test the facade
  against a fake module.
- Every background write path is native, which non-negotiable 11 already requires for playback.
```

`docs/adr/0002-cc-by-data-packages.md`:

```markdown
# ADR 0002: CC-BY-4.0 data-only packages are allowed

- Status: accepted
- Date: 2026-10-01
- Deciders: owner
- Amends: srs.md R-M13 ("all dependencies under OSI licenses"), AGENTS.md non-negotiable 14

## Context

React Native's production tree includes `caniuse-lite` (CC-BY-4.0) through
react-native -> @react-native/codegen -> @babel/core -> browserslist (found by
license-checker-rseidelsohn 5.0.1 on the RN 0.87 template, 2026-10-01). CC-BY-4.0 is not an OSI
software license, so a strict OSI rule fails every build of a stock React Native app.
`caniuse-lite` is browser-support data, not executable code that ships logic into the app.

## Decision

A production dependency must be OSI-licensed, except a data-only package under CC-BY-4.0 that
is listed **by name** in `DATA_EXCEPTIONS` in `scripts/check-licenses.mjs`. Adding a name to that
list is an owner decision recorded by amending this ADR.

## Consequences

- `scripts/check-licenses.mjs` reports every violation, not only the first.
- F-Droid's inclusion policy accepts free-culture data licenses. Re-confirm when the F-Droid
  metadata is written (roadmap Phase 6).
```

- [ ] **Step 5: Amend `srs.md` for both ADRs**

- In the "TypeScript modules" table, change the `library` row to `| `library` | Typed facade over the native Store (ADR 0001): items, cuts, positions, archive; markdown export | `ReadMeSpeech` |`.
- Under "Data model", add one line before the code block: `Stored by the Kotlin Store (ADR 0001).`
- In R-M13, change "all dependencies under OSI licenses" to "all dependencies under OSI licenses, except CC-BY-4.0 data-only packages listed under ADR 0002".
- Owner decisions log, add two rows: `| 2026-10-01 | Kotlin owns the database; JS goes through ReadMeSpeech (ADR 0001). |` and `| 2026-10-01 | CC-BY-4.0 data-only packages allowed by name (ADR 0002). Spike probe code stays on its spike branch; only answers merge. |`.

- [ ] **Step 6: Update `AGENTS.md` and `CONTEXT.md`**

In `AGENTS.md`, replace the **Quality gates** section's text and code block with:

````markdown
## Quality gates

Established by the scaffold (REA-N). CI runs the first six on every PR and push to `main`
(`.github/workflows/ci.yml`); the device gates need the phone and run locally.

```bash
npm run typecheck                           # tsc --noEmit
npm run lint                                # eslint (app sources; .mjs dev scripts are not linted)
npm test                                    # Jest
node scripts/check-licenses.mjs             # OSI licenses, ADR 0002 exceptions
(cd android && ./gradlew testDebugUnitTest) # Kotlin unit tests
npm run build:release                       # assembleRelease
scripts/fdroid-scan.sh                      # F-Droid source + APK scan, resolved Gradle tree, licenses
npm run device:install                      # takes the device slot, installs the release build
npm run device:smoke                        # takes the slot, installs, launches, checks for crashes
```
````

Keep the "Device work: the one-phone slot" subsection, and add two sentences: `scripts/lib/device.sh implements it; every device script sources it. The phone has a secure lock screen, so device scripts stop with exit 5 when it is locked and the owner unlocks it.` Amend non-negotiable 14 so it matches ADR 0002: change "Every dependency is OSI-licensed." to "Every dependency is OSI-licensed, except CC-BY-4.0 data-only packages listed by name under ADR 0002 (`scripts/check-licenses.mjs`)." Under **Known state**, replace "No code." with "Scaffold only: RN 0.87.1 template app, gates and CI. No product modules yet." Also replace the toolchain bullet with the installed SDK packages from Task 1, Step 1.

In `CONTEXT.md`, replace "Planned source layout" with the real tree (`App.tsx`, `index.js`, `__tests__/`, `android/app/src/main/java/io/loopstring/readme/`, `scripts/`, `docs/adr/`). Keep the planned `src/` module directories, marked "planned". Change the "Kotlin owns the database" bullet's parenthetical to `(ADR 0001)`. Record the identifiers AGENTS.md "Known state" asks the scaffold to fill (its "Placeholders the scaffold must fill" bullet): `<applicationId>` = `io.loopstring.readme` (launcher `io.loopstring.readme/.MainActivity`, JS component `ReadMe`), and `<AppLogTag>` = `ReadMe` for native code, with `ReactNativeJS` as the JS console tag and `ReadMeSpike` used only on spike branches. Then delete that placeholders bullet from AGENTS.md, and check the commands in `.claude/commands/` and `.opencode/` for `<applicationId>` and `<AppLogTag>` with `grep -rn "<applicationId>\|<AppLogTag>" .claude .opencode`, replacing each one found.

- [ ] **Step 7: Write `BUILDING.md`**

````markdown
# Building Read Me

Requirements: Node >= 22.11, JDK 21, Android SDK with `platforms;android-37.0`,
`build-tools;37.0.0`, `ndk;27.1.12297006`, `cmake;3.22.1`.

```bash
npm ci
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > android/local.properties
npm run build:release
# output: android/app/build/outputs/apk/release/app-release.apk
```

Gates: see AGENTS.md "Quality gates". F-Droid-style checks: `scripts/fdroid-scan.sh`.
````

- [ ] **Step 8: Run every gate locally, then ship**

```bash
npm run typecheck && npm run lint && npm test && node scripts/check-licenses.mjs
(cd android && ./gradlew --quiet testDebugUnitTest)
npm run build:release && scripts/fdroid-scan.sh; echo "fdroid-scan exit=$?"
grep -rnP "\x{2014}" AGENTS.md CONTEXT.md srs.md BUILDING.md docs/adr || echo "no em-dashes"
git add scripts/check-licenses.mjs scripts/fdroid-scan.sh .github/workflows/ci.yml BUILDING.md \
  docs/adr/0001-kotlin-owns-database.md docs/adr/0002-cc-by-data-packages.md \
  AGENTS.md CONTEXT.md srs.md .gitignore
git commit -m "chore: quality gates, CI, license policy (ADR 0002), Kotlin-owned DB (ADR 0001)"
```

Expected: every gate exits 0, except that `fdroid-scan.sh` may report problems. Those are SPIKE-04 findings: note them for Task 3, don't suppress them. Then run `/ship`. After the PR exists, `gh run watch` must show both CI jobs green. A red Android job is fixed on this branch before merge.

---

### Task 3: SPIKE-04, F-Droid-clean from a clean checkout

Branch: `spike/rea-N-fdroid-clean`, from `main` after Task 2 merges. No probe code.

**Files:**
- Modify: `srs.md` (SPIKE-04 answer only)

- [ ] **Step 1: Clean clone, documented commands only**

```bash
CLEAN=$(mktemp -d)
git clone -q "$(git remote get-url origin)" "$CLEAN/rm" && cd "$CLEAN/rm"
npm ci && printf 'sdk.dir=%s\n' "$HOME/Android/Sdk" > android/local.properties
npm run build:release && ls -l android/app/build/outputs/apk/release/app-release.apk
scripts/fdroid-scan.sh 2>&1 | tee /tmp/claude-spike04.txt; echo "exit=${PIPESTATUS[0]}"
git rev-parse --short HEAD
```

Expected: the APK exists. Any extra step the build needed is a `BUILDING.md` bug, and that is part of the answer. The scan prints all four sections.

- [ ] **Step 2: Record the answer (template in Task 9) and ship it**

```bash
cd /home/joshshearer/Documents/Dev/Read-Me   # back from the throwaway clone
```

Task 3 has no probe code, so its branch is the answer branch: `/start-issue` creates `spike/rea-N-<slug>` from `main`, the answer is committed there, and `/ship` opens the PR. The answer must state: each section's result verbatim; how F-Droid treats anything flagged in RN's prebuilt artifacts (for example, the `react-android` or `hermes-android` AARs from Maven Central); "SQLite: platform `android.database.sqlite`, no dependency (ADR 0001)"; and "Not established: an actual F-Droid build server run". Stage `srs.md` only, commit, then `/ship`.

---

### Task 4: SPIKE-03, JS probe harness and `Intl.Segmenter` on Hermes

Branch: `spike/rea-N-hermes-segmenter`, from `main` after Task 2 merges.

**Files:**
- Modify: `android/app/src/main/java/io/loopstring/readme/MainActivity.kt`, `App.tsx`
- Create: `src/spikes/run.ts`, `src/spikes/segmenterProbe.ts`, `scripts/spike-js.sh`
- Test: `__tests__/spikes.test.ts`

**Interfaces:**
- Consumes: `scripts/lib/device.sh` (`device_take`, `device_require_unlocked`, `device_install_release`, `PKG`)
- Produces:
  - `type Probe = () => Promise<Record<string, unknown>>` and `PROBES: Record<string, Probe>`, which Task 5 extends.
  - `runSpike(name: string): Promise<string>`, which logs exactly one `SPIKE_RESULT {"spike":name,...}` line.
  - `scripts/spike-js.sh <name> [timeout_s] [runs]`: installs once, runs the probe `runs` times, and prints one JSON payload per run. Exits 1 on timeout, 2 if the app dies, 3 if the device slot is unavailable, 5 if the phone is locked.

- [ ] **Step 1: Write the failing tests**

`__tests__/spikes.test.ts`:

```ts
import {runSpike} from '../src/spikes/run';
import {segmenterProbe} from '../src/spikes/segmenterProbe';

function captureLogs(): {lines: string[]; restore: () => void} {
  const lines: string[] = [];
  const spy = jest.spyOn(console, 'log').mockImplementation((...a: unknown[]) => {
    lines.push(a.map(String).join(' '));
  });
  return {lines, restore: () => spy.mockRestore()};
}

function payload(line: string): Record<string, unknown> {
  expect(line.startsWith('SPIKE_RESULT ')).toBe(true);
  return JSON.parse(line.slice('SPIKE_RESULT '.length));
}

test('ping logs exactly one SPIKE_RESULT line', async () => {
  const log = captureLogs();
  const status = await runSpike('ping');
  log.restore();
  expect(status).toBe('done');
  expect(log.lines).toHaveLength(1);
  expect(payload(log.lines[0])).toMatchObject({spike: 'ping', ok: true});
});

test('unknown spike reports an error instead of throwing', async () => {
  const log = captureLogs();
  const status = await runSpike('nope');
  log.restore();
  expect(status).toBe('unknown spike');
  expect(payload(log.lines[0])).toMatchObject({spike: 'nope', error: 'unknown spike'});
});

test('segmenterProbe reports sentences with offsets when Intl.Segmenter exists (Node has it)', async () => {
  const r = await segmenterProbe();
  expect(r.present).toBe(true);
  const sentences = r.sentences as {index: number; text: string}[];
  expect(sentences.length).toBeGreaterThanOrEqual(4);
  expect(sentences[0].index).toBe(0);
  expect(typeof r.bigMs).toBe('number');
});
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `npx jest __tests__/spikes.test.ts`
Expected: FAIL with `Cannot find module '../src/spikes/run'`.

- [ ] **Step 3: Implement the harness and the probe**

`src/spikes/run.ts`:

```ts
// Phase 0 spike harness. A probe runs inside the release build, triggered by
// `am start --es spike <name>`, and reports one SPIKE_RESULT line to logcat
// (tag ReactNativeJS). Probes report numbers and flags only, never item text (R-M09.3).
import {segmenterProbe} from './segmenterProbe';

export type Probe = () => Promise<Record<string, unknown>>;

export const PROBES: Record<string, Probe> = {
  ping: async () => ({ok: true, hermes: 'HermesInternal' in globalThis}),
  segmenter: segmenterProbe,
};

function report(spike: string, result: Record<string, unknown>): void {
  console.log(`SPIKE_RESULT ${JSON.stringify({spike, ...result})}`);
}

export async function runSpike(name: string): Promise<string> {
  const probe = PROBES[name];
  if (!probe) {
    report(name, {error: 'unknown spike'});
    return 'unknown spike';
  }
  try {
    report(name, await probe());
    return 'done';
  } catch (e) {
    report(name, {error: e instanceof Error ? e.message : String(e)});
    return 'error';
  }
}
```

`src/spikes/segmenterProbe.ts`:

```ts
// SPIKE-03: is Intl.Segmenter (sentence granularity) present on Hermes, how does it
// treat abbreviations, and how fast is it on ~150k characters?
type Segment = {segment: string; index: number};
type SegmenterCtor = new (
  locale: string,
  opts: {granularity: 'sentence'},
) => {segment(text: string): Iterable<Segment>};

// Fixed sample: abbreviations, a decimal, a quote and a question are where segmenters differ.
const SAMPLE =
  'Dr. Smith went to Washington. He arrived at 3 p.m. on Jan. 5, 2026! ' +
  'Did it work? "Yes," she said. The U.S. economy grew 2.5% last year.';

export async function segmenterProbe(): Promise<Record<string, unknown>> {
  const Seg = (Intl as unknown as {Segmenter?: SegmenterCtor}).Segmenter;
  const hermes = 'HermesInternal' in globalThis;
  if (typeof Seg !== 'function') {
    return {present: false, hermes};
  }
  const seg = new Seg('en', {granularity: 'sentence'});
  const sentences = Array.from(seg.segment(SAMPLE), s => ({index: s.index, text: s.segment}));
  const big = SAMPLE.repeat(1000);
  const t0 = Date.now();
  const bigSentences = Array.from(seg.segment(big)).length;
  return {present: true, hermes, sentences, bigChars: big.length, bigSentences, bigMs: Date.now() - t0};
}
```

`SAMPLE` is a fixed test string, not item text, so logging its segmentation is allowed.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `npm test && npm run typecheck && npm run lint`
Expected: PASS, 4 tests (3 spike tests plus the App test). Typecheck and lint exit 0.

- [ ] **Step 5: Pass the launch extra to the root component**

`android/app/src/main/java/io/loopstring/readme/MainActivity.kt`:

```kotlin
package io.loopstring.readme

import android.os.Bundle
import com.facebook.react.ReactActivity
import com.facebook.react.ReactActivityDelegate
import com.facebook.react.defaults.DefaultNewArchitectureEntryPoint.fabricEnabled
import com.facebook.react.defaults.DefaultReactActivityDelegate

class MainActivity : ReactActivity() {

  override fun getMainComponentName(): String = "ReadMe"

  /**
   * Spike branch only: `am start ... --es spike <name>` reaches the root component as the
   * `spike` initial prop, so JS spikes run in the release build without a debug menu.
   */
  override fun createReactActivityDelegate(): ReactActivityDelegate =
      object : DefaultReactActivityDelegate(this, mainComponentName, fabricEnabled) {
        override fun getLaunchOptions(): Bundle? =
            intent?.getStringExtra("spike")?.let { Bundle().apply { putString("spike", it) } }
      }
}
```

`App.tsx`:

```tsx
import React, {useEffect, useState} from 'react';
import {StyleSheet, Text, View} from 'react-native';
import {runSpike} from './src/spikes/run';

type Props = {spike?: string};

export default function App({spike}: Props) {
  const [status, setStatus] = useState(spike ? `running spike: ${spike}` : 'Read Me');

  useEffect(() => {
    if (spike) {
      runSpike(spike).then(setStatus);
    }
  }, [spike]);

  return (
    <View style={styles.root}>
      <Text style={styles.text}>{status}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  root: {flex: 1, alignItems: 'center', justifyContent: 'center'},
  text: {fontSize: 18},
});
```

- [ ] **Step 6: Write `scripts/spike-js.sh`**

```bash
#!/usr/bin/env bash
# Run one JS spike probe in the release build, `runs` times, and print one JSON payload per run.
# Usage: scripts/spike-js.sh <ping|segmenter|extract> [timeout_s=120] [runs=1]
# Exit: 0 results printed, 1 timeout, 2 app died (crash lines printed), 3 no slot, 5 phone locked,
# 6 uncommitted changes.
set -euo pipefail
cd "$(dirname "$0")/.."
NAME=${1:?usage: spike-js.sh <name> [timeout_s] [runs]}
TIMEOUT=${2:-120}; RUNS=${3:-1}
. scripts/lib/device.sh
cleanup() { adb shell am force-stop "$PKG" >/dev/null 2>&1 || true; }
DEVICE_ON_EXIT=cleanup
device_take interactive
device_require_committed
device_require_unlocked
device_install_release
for run in $(seq "$RUNS"); do
  adb shell am force-stop "$PKG"
  adb logcat -c
  adb shell am start -n "$PKG/.MainActivity" --es spike "$NAME" >/dev/null
  sleep 2
  got=""
  for _ in $(seq "$TIMEOUT"); do
    line=$(adb logcat -d -s ReactNativeJS:I | grep -m1 'SPIKE_RESULT' || true)
    if [ -n "$line" ]; then got="${line#*SPIKE_RESULT }"; break; fi
    if [ -z "$(adb shell pidof "$PKG" | tr -d '\r')" ]; then
      echo "run $run: app process died during spike '$NAME'" >&2
      adb logcat -d -b crash,main | grep -E "AndroidRuntime|FATAL|libc|OutOfMemory|hermes" | tail -20 >&2
      exit 2
    fi
    sleep 1
  done
  [ -n "$got" ] || { echo "run $run: no SPIKE_RESULT within ${TIMEOUT}s" >&2; exit 1; }
  echo "$got"
done
```

```bash
chmod +x scripts/spike-js.sh
```

- [ ] **Step 7: Run on the device (Review Focus 1, then SPIKE-03)**

Commit the probe first: the scripts refuse a dirty tree, and the hash printed at the end is then the build that was measured.

```bash
git add App.tsx android/app/src/main/java/io/loopstring/readme/MainActivity.kt src/spikes \
  __tests__/spikes.test.ts scripts/spike-js.sh
git commit -m "spike: SPIKE-03 probe harness and Intl.Segmenter probe (not for merge)"
npm run build:release
scripts/spike-js.sh ping
scripts/spike-js.sh does-not-exist
scripts/spike-js.sh segmenter 120 3 | tee /tmp/claude-spike03.jsonl
git rev-parse --short HEAD
```

Expected:
- `ping` prints `{"spike":"ping","ok":true,"hermes":true}`. A timeout means release `console.log` isn't reaching logcat. Stop there: the JS harness has no output channel, and that is the first thing to fix.
- `does-not-exist` prints `{"spike":"does-not-exist","error":"unknown spike"}`.
- `segmenter` prints three lines. Either `present:false` or the sentence list with `bigMs` answers SPIKE-03. Note the short hash.

- [ ] **Step 8: Push the probe branch, record the answer, ship the answer**

Push the probe branch (`git push -u origin "$(git branch --show-current)"`) and do **not** open a PR for it. Then record the answer with the Task 9 template and ship it with Task 9's "Shipping a spike answer". The answer must state: `present` and `hermes`; how each abbreviation in `SAMPLE` was split (for example, whether `Dr.`, `p.m.` and `U.S.` ended a sentence); `bigMs` as the median of the 3 runs; the consequence for R-M08; and "Not established: segmentation of non-English text and CJK; behaviour on Hermes versions other than the one in RN 0.87.1".

---

### Task 5: SPIKE-02, Readability on Hermes with linkedom

Branch: probe branch from `/start-issue <SPIKE-02 issue>`, then `git merge --no-edit <Task 4 probe branch>` to stack it on the harness.

**Files:**
- Modify: `package.json`, `package-lock.json` (deps), `babel.config.js`, `jest.config.js`, `.gitignore`, `src/spikes/run.ts`, `__tests__/spikes.test.ts`
- Create: `scripts/fetch-spike-fixtures.sh`, `scripts/make-spike-fixtures.mjs`, `spikes/fixtures/*.html`, `src/spikes/extractProbe.ts`

**Interfaces:**
- Consumes: `PROBES`, `runSpike` (Task 4), `scripts/spike-js.sh`
- Produces: `extractProbe(): Promise<{results: ExtractResult[]}>`, where `ExtractResult = {name: string; bytes: number; parseMs: number; readabilityMs: number; blocksMs: number; titleChars: number; textChars: number; blocks: number}`. The F17 total is `parseMs + readabilityMs + blocksMs`: HTML to paragraphs, which is all of `extract` (CONTEXT.md).

Fixture licenses: Wikipedia and MDN text is CC BY-SA, and Project Gutenberg's *Pride and Prejudice* is US public domain. All three stay on this spike branch only. The downloads run on the dev machine as test setup. They are not app network use.

Pass line (roadmap F17, **confirmed by the owner 2026-10-01**): the 5 MB page extracts in 10 s or less (`parseMs + readabilityMs + blocksMs`, median of 3 runs) with no crash, and every page under 1 MB in 1.5 s or less, on the reference device in a release build. Pass means linkedom. Fail means the hidden WebView, with ADR 0003.

- [ ] **Step 1: Download the fixtures**

`scripts/fetch-spike-fixtures.sh`:

```bash
#!/usr/bin/env bash
# One-time download of SPIKE-02 fixtures (dev machine only; the app never fetches these).
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p spikes/fixtures
UA="Mozilla/5.0 (X11; Linux x86_64) ReadMe-spike-fixtures"
curl -sSfL -A "$UA" https://en.wikipedia.org/wiki/Speech_synthesis -o spikes/fixtures/wikipedia-speech-synthesis.html
curl -sSfL -A "$UA" https://developer.mozilla.org/en-US/docs/Web/API/SpeechSynthesis -o spikes/fixtures/mdn-speechsynthesis.html
curl -sSfL -A "$UA" https://www.gutenberg.org/cache/epub/1342/pg1342-images.html -o spikes/fixtures/gutenberg-pride-and-prejudice.html
ls -l spikes/fixtures
```

```bash
chmod +x scripts/fetch-spike-fixtures.sh && scripts/fetch-spike-fixtures.sh
```

Expected: three files. On 2026-10-01 they were 852,590 bytes (Gutenberg), 157,106 bytes (MDN) and 705,418 bytes (Wikipedia). Live pages drift, so record today's sizes.

- [ ] **Step 2: Add dependencies, the Babel plugin, the Jest transform list, and the generator**

```bash
npm install --save-exact @mozilla/readability@0.6.0 linkedom@0.18.13
npm install --save-dev --save-exact @babel/plugin-transform-export-namespace-from@7.29.7
```

`babel.config.js`:

```js
module.exports = {
  presets: ['module:@react-native/babel-preset'],
  // htmlparser2 (via linkedom) ships `export * as ns`; without this the release bundle fails.
  plugins: ['@babel/plugin-transform-export-namespace-from'],
};
```

`jest.config.js`:

```js
module.exports = {
  preset: '@react-native/jest-preset',
  // linkedom's CJS build requires ESM-only packages (css-select and friends); transform them.
  transformIgnorePatterns: [
    'node_modules/(?!((jest-)?react-native|@react-native(-community)?|linkedom|css-select|css-what|htmlparser2|domhandler|domutils|dom-serializer|domelementtype|entities|nth-check|boolbase)/)',
  ],
};
```

`scripts/make-spike-fixtures.mjs`:

```js
// Turns spikes/fixtures/*.html into src/spikes/fixtures.generated.ts (gitignored), plus a
// synthetic page of real prose at exactly R-M03's cap: the Gutenberg book's body repeated
// inside one <article>, cut on a tag boundary so the whole page is at most 5,242,880 UTF-8
// bytes (the largest body Fetcher can hand to extraction).
import {readFileSync, readdirSync, writeFileSync} from 'node:fs';
import {join} from 'node:path';

const CAP_BYTES = 5 * 1024 * 1024;
const dir = 'spikes/fixtures';
const fixtures = readdirSync(dir)
  .filter(f => f.endsWith('.html'))
  .sort()
  .map(f => ({name: f.replace(/\.html$/, ''), html: readFileSync(join(dir, f), 'utf8')}));

const book = fixtures.find(f => f.name === 'gutenberg-pride-and-prejudice');
if (!book) throw new Error('run scripts/fetch-spike-fixtures.sh first');
const body = book.html.slice(book.html.indexOf('<body'), book.html.lastIndexOf('</body>'));
const inner = body.slice(body.indexOf('>') + 1);
const head = '<!doctype html><html><head><title>Synthetic 5 MB</title></head><body><article>';
const tail = '</article></body></html>';
const room = CAP_BYTES - Buffer.byteLength(head + tail);
let big = '';
while (Buffer.byteLength(big) < room) big += inner;
// Cut to the byte budget, then back to the last tag start so no tag is split.
big = Buffer.from(big).subarray(0, room).toString('utf8');
big = big.slice(0, big.lastIndexOf('<'));
const html = head + big + tail;
if (Buffer.byteLength(html) > CAP_BYTES) throw new Error('synthetic page exceeds the cap');
fixtures.push({name: 'synthetic-5mb', html});

const out =
  '// Generated by scripts/make-spike-fixtures.mjs. Do not edit; gitignored.\n' +
  'export const FIXTURES: {name: string; html: string}[] = ' + JSON.stringify(fixtures) + ';\n';
writeFileSync('src/spikes/fixtures.generated.ts', out);
for (const f of fixtures) console.log(f.name, Buffer.byteLength(f.html), 'bytes');
```

In `package.json` `"scripts"`, add these. `postinstall` matters because `run.ts` imports the generated module, so a clean `npm ci` of this branch would otherwise fail at bundling.

```json
    "spike:fixtures": "node scripts/make-spike-fixtures.mjs",
    "postinstall": "node scripts/make-spike-fixtures.mjs",
    "pretest": "node scripts/make-spike-fixtures.mjs",
```

```bash
printf '\n# SPIKE-02 generated fixtures\nsrc/spikes/fixtures.generated.ts\n' >> .gitignore
npm run spike:fixtures
```

Expected: four lines. The last is `synthetic-5mb` at no more than 5,242,880 bytes; the scratch run produced 5,241,998.

- [ ] **Step 3: Write the failing tests**

In `__tests__/spikes.test.ts`, change the first import line to `import {PROBES, runSpike} from '../src/spikes/run';` and add `import {extractProbe} from '../src/spikes/extractProbe';` below it. Then append:

```ts
test('extractProbe extracts a titled, multi-block article from every fixture', async () => {
  const {results} = await extractProbe();
  expect(results.map(r => r.name)).toEqual([
    'gutenberg-pride-and-prejudice',
    'mdn-speechsynthesis',
    'wikipedia-speech-synthesis',
    'synthetic-5mb',
  ]);
  for (const r of results) {
    expect(r.titleChars).toBeGreaterThan(0);
    expect(r.blocks).toBeGreaterThanOrEqual(3);
    expect(r.textChars).toBeGreaterThanOrEqual(500);
  }
  expect(results[3].bytes).toBeLessThanOrEqual(5 * 1024 * 1024);
  expect(results[3].bytes).toBeGreaterThan(5 * 1024 * 1024 - 64 * 1024);
}, 120_000);

test('the extract probe is registered', () => {
  expect(Object.keys(PROBES)).toEqual(['ping', 'segmenter', 'extract']);
});
```

Run: `npx jest __tests__/spikes.test.ts`
Expected: FAIL with `Cannot find module '../src/spikes/extractProbe'`.

- [ ] **Step 4: Implement `src/spikes/extractProbe.ts` and register it**

```ts
// SPIKE-02: does Readability run on Hermes over a pure-JS DOM (linkedom), and how fast
// for pages up to R-M03's 5 MB cap? Reports sizes and timings, never extracted text.
import {Readability} from '@mozilla/readability';
import {parseHTML} from 'linkedom';
import {FIXTURES} from './fixtures.generated';

export type ExtractResult = {
  name: string;
  bytes: number;
  parseMs: number;
  readabilityMs: number;
  blocksMs: number;
  titleChars: number;
  textChars: number;
  blocks: number;
};

type ReadabilityDoc = ConstructorParameters<typeof Readability>[0];

// UTF-8 byte length without Buffer or TextEncoder, which Hermes may not provide.
function utf8Bytes(s: string): number {
  let n = 0;
  for (let i = 0; i < s.length; i++) {
    const c = s.charCodeAt(i);
    if (c < 0x80) n += 1;
    else if (c < 0x800) n += 2;
    else if (c >= 0xd800 && c <= 0xdbff) {
      n += 4;
      i++;
    } else n += 3;
  }
  return n;
}

export async function extractProbe(): Promise<{results: ExtractResult[]}> {
  const results: ExtractResult[] = [];
  for (const f of FIXTURES) {
    const t0 = Date.now();
    const {document} = parseHTML(f.html);
    const t1 = Date.now();
    const article = new Readability(document as unknown as ReadabilityDoc).parse();
    const t2 = Date.now();
    const blocks = article?.content
      ? parseHTML(article.content).document.querySelectorAll('p,h1,h2,h3,h4,h5,h6,li').length
      : 0;
    const t3 = Date.now();
    results.push({
      name: f.name,
      bytes: utf8Bytes(f.html),
      parseMs: t1 - t0,
      readabilityMs: t2 - t1,
      blocksMs: t3 - t2,
      titleChars: article?.title?.length ?? 0,
      textChars: article?.textContent?.length ?? 0,
      blocks,
    });
  }
  return {results};
}
```

In `src/spikes/run.ts`, add `import {extractProbe} from './extractProbe';` and the registry entry `extract: extractProbe,` after `segmenter`.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `npm test && npm run typecheck && npm run lint`
Expected: PASS, 6 tests in 2 suites. Typecheck and lint exit 0. This exact code passed all three in a scratch copy of the template on 2026-10-01 (see "Verified before writing").

- [ ] **Step 6: Run SPIKE-02 on the device, three times**

Commit the probe first: the scripts refuse a dirty tree, and the hash printed at the end is then the build that was measured.

```bash
git add package.json package-lock.json babel.config.js jest.config.js .gitignore \
  scripts/fetch-spike-fixtures.sh scripts/make-spike-fixtures.mjs spikes/fixtures \
  src/spikes/run.ts src/spikes/extractProbe.ts __tests__/spikes.test.ts
git commit -m "spike: SPIKE-02 Readability+linkedom probe with real-page fixtures (not for merge)"
npm run build:release
scripts/spike-js.sh extract 300 3 | tee /tmp/claude-spike02.jsonl
git rev-parse --short HEAD
```

Expected: three JSON lines with four results each. Exit 2 means Hermes crashed (likely OOM on `synthetic-5mb`), and that crash is itself the SPIKE-02 answer: record the crash lines. If `npm run build:release` fails, stop. That is a build defect on this branch, not a SPIKE-02 result, and must not be recorded as "linkedom fails on Hermes".

- [ ] **Step 7: Push the probe branch, record the answer, ship the answer**

Push the probe branch without a PR.

The answer must state:
- the per-fixture `parseMs + readabilityMs + blocksMs`, median of 3, next to the F17 line;
- the verdict (linkedom or hidden WebView, by the F17 line) and the build facts Phase 1 inherits: the Babel plugin and the Jest transform list;
- "Not established: pages that need JS to render, non-Latin scripts, memory headroom with the app's real UI loaded".

If the verdict is the WebView, also write `docs/adr/0003-extraction-in-webview.md`. Push the probe branch without a PR, then ship the answer (and the ADR) with Task 9's "Shipping a spike answer".

---

### Task 6: SPIKE-05, native harness and gapless queueing at 2x

Branch: `spike/rea-N-gapless-2x`, from `main` after Task 2 merges.

**Files:**
- Modify: `android/app/build.gradle` (JUnit), `android/app/src/main/AndroidManifest.xml`, `android/app/src/main/java/io/loopstring/readme/MainActivity.kt`
- Create: `android/app/src/main/java/io/loopstring/readme/spike/{SpikeText,GapStats,GapProbe,SynthLoad,SpikeService}.kt`
- Create: `android/app/src/main/assets/spike/corpus.txt`, `scripts/fetch-spike-corpus.sh`
- Create: `scripts/lib/spike.sh`, `scripts/spike-gap.sh`
- Test: `android/app/src/test/java/io/loopstring/readme/spike/{SpikeTextTest,GapStatsTest}.kt`

**Interfaces:**
- Consumes: `scripts/lib/device.sh`
- Produces (Tasks 7 and 8 rely on these):
  - `MainActivity` forwards `--ez nativeSpike true` plus every other extra to `SpikeService`.
  - `SpikeService` commands (`cmd` extra): `gap` (here), `bridge` (Task 8), anything else = stop. The `fgs` extra is `media` (default) or `special`.
  - `SpikeService.report(kind: String, json: JSONObject)` logs `SPIKE_<KIND> {json}` under tag `ReadMeSpike`.
  - `GapStats.percentile(sorted: List<Long>, p: Int): Long`, `GapStats.summarize(gapsMs: List<Long>): GapSummary`.
  - `scripts/lib/spike.sh`:
    - `spike_native <am extras...>` starts the activity with `nativeSpike`. It exits 4 if `am` reports an error, or if no `SPIKE_FGS` line appears within 10 s.
    - `spike_wait <PATTERN> <timeout_s>` prints the payload of the first `ReadMeSpike` line matching `PATTERN`.

Gap (R-M07): wall time from `onDone(n)` to `onStart(n+1)`, measured in the app process. A **stall** is a gap over 1,000 ms (R-M07's max). **Hung** means no `onStart` or `onDone` for 60 s (one 400-char sentence takes about 13 s at 2.0x). `usPerChar` is the microseconds of `onStart` to `onDone` per character of the utterance. Task 7 uses it to detect a rate leak between instances.

- [ ] **Step 1: Fetch the corpus**

`scripts/fetch-spike-corpus.sh`:

```bash
#!/usr/bin/env bash
# One-time download of the public-domain playback corpus (dev machine only).
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p android/app/src/main/assets/spike
curl -sSfL -A "Mozilla/5.0 ReadMe-spike-corpus" https://www.gutenberg.org/cache/epub/1342/pg1342.txt \
  | tr -d '\r' | awk '/^\*\*\* START OF/{on=1; next} /^\*\*\* END OF/{on=0} on' \
  > android/app/src/main/assets/spike/corpus.txt
wc -c android/app/src/main/assets/spike/corpus.txt
```

```bash
chmod +x scripts/fetch-spike-corpus.sh && scripts/fetch-spike-corpus.sh
```

Expected: about 738 KB (737,950 bytes on 2026-10-01), with no `*** START OF` line in it.

- [ ] **Step 2: Add JUnit and write the failing tests**

In `android/app/build.gradle`, inside `dependencies { ... }`, add `testImplementation("junit:junit:4.13.2")`.

`android/app/src/test/java/io/loopstring/readme/spike/SpikeTextTest.kt`:

```kotlin
package io.loopstring.readme.spike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpikeTextTest {
  @Test fun splitsOnTerminalPunctuation() {
    assertEquals(listOf("One.", "Two!", "Three?"), SpikeText.sentences("One. Two! Three?"))
  }

  @Test fun blankLinesSeparateParagraphsAndSingleNewlinesJoin() {
    assertEquals(listOf("A b.", "C."), SpikeText.sentences("A\nb.\n\nC."))
  }

  @Test fun closingQuoteStaysWithItsSentence() {
    assertEquals(listOf("He said \"Stop.\"", "Then left."), SpikeText.sentences("He said \"Stop.\" Then left."))
  }

  @Test fun capsLongSentencesAtTheLastSpaceBefore400() {
    val long = "word ".repeat(120).trim() + "."
    val out = SpikeText.sentences(long)
    assertTrue(out.all { it.length <= 400 })
    assertEquals(long, out.joinToString(" "))
  }

  @Test fun capsSentencesWithNoSpaceAtExactly400() {
    assertEquals(listOf(400, 400, 100), SpikeText.capLength("x".repeat(900)).map { it.length })
  }
}
```

`android/app/src/test/java/io/loopstring/readme/spike/GapStatsTest.kt`:

```kotlin
package io.loopstring.readme.spike

import org.junit.Assert.assertEquals
import org.junit.Test

class GapStatsTest {
  @Test fun nearestRankPercentiles() {
    val s = GapStats.summarize((1L..100L).shuffled())
    assertEquals(GapSummary(count = 100, p50 = 50, p95 = 95, max = 100, stalls = 0), s)
  }

  @Test fun gapsOverOneSecondAreStalls() {
    assertEquals(2, GapStats.summarize(listOf(10L, 1_000L, 1_001L, 5_000L)).stalls)
  }

  @Test fun emptyInputIsAllZero() {
    assertEquals(GapSummary(0, 0, 0, 0, 0), GapStats.summarize(emptyList()))
  }

  @Test fun singleValueIsEveryPercentile() {
    assertEquals(7L, GapStats.percentile(listOf(7L), 95))
  }
}
```

Run: `(cd android && ./gradlew --quiet :app:testDebugUnitTest)`
Expected: FAIL with `Unresolved reference 'SpikeText'` and `Unresolved reference 'GapStats'`.

- [ ] **Step 3: Implement `SpikeText.kt` and `GapStats.kt`**

`android/app/src/main/java/io/loopstring/readme/spike/SpikeText.kt`:

```kotlin
package io.loopstring.readme.spike

/**
 * Naive sentence split for native spikes only. The product segmenter is TS (R-M08); this
 * just turns the corpus into utterance-sized strings with the same 400-char cap.
 */
object SpikeText {
  private const val CAP = 400
  private val PARAGRAPH = Regex("\\n\\s*\\n")
  private val SPACES = Regex("\\s+")
  private val BOUNDARY = Regex("(?<=[.!?][\"')\\]]?)\\s+")

  fun sentences(text: String): List<String> =
      text.split(PARAGRAPH)
          .map { it.replace(SPACES, " ").trim() }
          .filter { it.isNotEmpty() }
          .flatMap { para -> para.split(BOUNDARY).filter { it.isNotBlank() } }
          .flatMap(::capLength)

  fun capLength(s: String): List<String> {
    if (s.length <= CAP) return listOf(s)
    val cut = s.lastIndexOf(' ', CAP).takeIf { it > 0 } ?: CAP
    return listOf(s.substring(0, cut).trim()) + capLength(s.substring(cut).trim())
  }
}
```

The same regex and cap logic in jshell on 2026-10-01 passed the first two tests and the quote case, and split `corpus.txt` into 7,734 sentences with a maximum of 400 chars.

`android/app/src/main/java/io/loopstring/readme/spike/GapStats.kt`:

```kotlin
package io.loopstring.readme.spike

/** R-M07 inter-utterance gap summary. A stall is a gap over [GapStats.STALL_MS]. */
data class GapSummary(val count: Int, val p50: Long, val p95: Long, val max: Long, val stalls: Int)

object GapStats {
  const val STALL_MS = 1_000L

  fun summarize(gapsMs: List<Long>): GapSummary {
    if (gapsMs.isEmpty()) return GapSummary(0, 0, 0, 0, 0)
    val s = gapsMs.sorted()
    return GapSummary(s.size, percentile(s, 50), percentile(s, 95), s.last(), s.count { it > STALL_MS })
  }

  /** Nearest-rank percentile of an ascending list. */
  fun percentile(sorted: List<Long>, p: Int): Long {
    val rank = Math.ceil(p / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
    return sorted[rank - 1]
  }
}
```

Run: `(cd android && ./gradlew --quiet :app:testDebugUnitTest)`
Expected: BUILD SUCCESSFUL. `android/app/build/test-results/testDebugUnitTest/*.xml` shows 9 tests with `failures="0"`.

- [ ] **Step 4: Implement `GapProbe.kt`**

```kotlin
package io.loopstring.readme.spike

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONObject
import java.util.Locale

/**
 * SPIKE-05: speak [sentences] with QUEUE_ADD at [rate], keeping [AHEAD] utterances queued
 * (srs.md, Playback), and record every onDone(n) -> onStart(n+1) gap until [durationMs].
 * Construct on the main thread; [onFinished] is called exactly once.
 */
class GapProbe(
    context: Context,
    private val sentences: List<String>,
    private val rate: Float,
    private val durationMs: Long,
    private val onFinished: (JSONObject) -> Unit,
) {
  private val lock = Any()
  private val handler = Handler(Looper.getMainLooper())
  private val gaps = ArrayList<Long>()
  private val usPerChar = ArrayList<Long>()
  private val startedAtById = HashMap<String, Long>()
  private var nextIndex = 0
  private var lastDoneAt = -1L
  private var lastProgressAt = 0L
  private var startedAt = 0L
  private var spoken = 0
  private var errors = 0
  private var stops = 0
  private var finished = false
  private var initStatus = Int.MIN_VALUE

  // If the engine fails to bind, onInit can run synchronously inside this constructor, before
  // `tts` is assigned. Posting defers every use of `tts` until construction has finished.
  private val tts: TextToSpeech =
      TextToSpeech(context.applicationContext) { status -> handler.post { onReady(status) } }

  init {
    handler.postDelayed({ if (initStatus == Int.MIN_VALUE) finish("init-timeout") }, INIT_TIMEOUT_MS)
  }

  fun cancel() = finish("cancelled")

  private fun onReady(status: Int) {
    initStatus = status
    if (status != TextToSpeech.SUCCESS) {
      finish("init-failed")
      return
    }
    tts.setLanguage(Locale.US)
    tts.setSpeechRate(rate)
    tts.setOnUtteranceProgressListener(Listener())
    synchronized(lock) {
      startedAt = SystemClock.elapsedRealtime()
      lastProgressAt = startedAt
      repeat(AHEAD) { enqueueNextLocked() }
    }
    handler.postDelayed(watchdog, WATCHDOG_MS)
  }

  private val watchdog: Runnable = object : Runnable {
    override fun run() {
      val idle = synchronized(lock) { SystemClock.elapsedRealtime() - lastProgressAt }
      if (idle > HUNG_MS) finish("hung") else handler.postDelayed(this, WATCHDOG_MS)
    }
  }

  private fun sentenceFor(id: String): String = sentences[id.substring(1).toInt() % sentences.size]

  private fun enqueueNextLocked() {
    if (finished) return
    val id = "g$nextIndex"
    tts.speak(sentenceFor(id), TextToSpeech.QUEUE_ADD, Bundle(), id)
    nextIndex++
  }

  /** Named class, not anonymous: see the d8 note in the prototype's MainActivity.Tap. */
  private inner class Listener : UtteranceProgressListener() {
    override fun onStart(id: String) {
      val now = SystemClock.elapsedRealtime()
      synchronized(lock) {
        lastProgressAt = now
        startedAtById[id] = now
        if (lastDoneAt >= 0) gaps.add(now - lastDoneAt)
      }
    }

    override fun onDone(id: String) {
      val now = SystemClock.elapsedRealtime()
      val timeUp = synchronized(lock) {
        lastDoneAt = now
        lastProgressAt = now
        spoken++
        startedAtById.remove(id)?.let { began ->
          usPerChar.add((now - began) * 1000 / sentenceFor(id).length.coerceAtLeast(1))
        }
        val up = now - startedAt >= durationMs
        if (!up) enqueueNextLocked()
        up
      }
      if (timeUp) handler.post { finish("done") }
    }

    @Deprecated("Deprecated in Java")
    override fun onError(id: String) {
      synchronized(lock) {
        errors++
        lastDoneAt = SystemClock.elapsedRealtime()
        startedAtById.remove(id)
        enqueueNextLocked()
      }
    }

    override fun onError(id: String, code: Int) {
      @Suppress("DEPRECATION") onError(id)
    }

    override fun onStop(id: String, interrupted: Boolean) {
      synchronized(lock) { stops++ }
    }
  }

  private fun finish(reason: String) {
    val result = synchronized(lock) {
      if (finished) return
      finished = true
      val s = GapStats.summarize(gaps)
      val per = usPerChar.sorted()
      JSONObject()
          .put("reason", reason)
          .put("initStatus", initStatus)
          .put("rate", rate.toDouble())
          .put("elapsedMs", if (startedAt == 0L) 0 else SystemClock.elapsedRealtime() - startedAt)
          .put("utterances", spoken)
          .put("errors", errors)
          .put("stopsBeforeFinish", stops)
          .put("gapCount", s.count)
          .put("gapP50", s.p50)
          .put("gapP95", s.p95)
          .put("gapMax", s.max)
          .put("stalls", s.stalls)
          .put("usPerCharP50", if (per.isEmpty()) 0 else GapStats.percentile(per, 50))
    }
    handler.removeCallbacksAndMessages(null)
    tts.stop()
    tts.shutdown()
    onFinished(result)
  }

  companion object {
    const val AHEAD = 3
    const val WATCHDOG_MS = 5_000L
    // A 400-char utterance runs ~13 s at 2.0x (482 chars measured 16.1 s, note-reader-local
    // AGENTS.md), so "hung" must sit well clear of one long sentence.
    const val HUNG_MS = 60_000L
    const val INIT_TIMEOUT_MS = 20_000L
  }
}
```

- [ ] **Step 5: Implement `SynthLoad.kt` (used idle here, active in Task 7)**

```kotlin
package io.loopstring.readme.spike

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * SPIKE-05/06: a second TextToSpeech instance in the same process. When [active], it loops
 * synthesizeToFile (the bridge's call) back to back at rate 1.0 until [stopAndReport]; when
 * not, it only exists, to test whether a second bound instance alone disturbs playback.
 * Construct on the main thread.
 */
class SynthLoad(context: Context, private val sentences: List<String>, private val active: Boolean) {
  private val cacheDir = context.cacheDir
  private val handler = Handler(Looper.getMainLooper())
  private val lock = Any()
  private val synthMs = ArrayList<Long>()
  private val bytesPerChar = ArrayList<Long>()
  private var currentChars = 1
  private var errors = 0
  private var rejected = 0
  private var stops = 0
  private var running = false
  private var stopped = false
  private var seq = 0
  private var current: File? = null
  private var currentStartedAt = 0L
  private var initStatus = Int.MIN_VALUE

  // Deferred for the same reason as GapProbe: onInit may run inside the constructor.
  private val tts: TextToSpeech =
      TextToSpeech(context.applicationContext) { status -> handler.post { onReady(status) } }

  private fun onReady(status: Int) {
    initStatus = status
    if (status != TextToSpeech.SUCCESS) return
    tts.setLanguage(Locale.US)
    tts.setSpeechRate(1.0f)
    tts.setOnUtteranceProgressListener(Listener())
    synchronized(lock) {
      if (stopped) return
      running = active
      if (running) nextLocked()
    }
  }

  private fun nextLocked() {
    val f = File(cacheDir, "spike-synth-$seq.wav")
    current = f
    currentStartedAt = SystemClock.elapsedRealtime()
    val text = sentences[seq % sentences.size]
    currentChars = text.length.coerceAtLeast(1)
    val rc = tts.synthesizeToFile(text, Bundle(), f, "s$seq")
    seq++
    if (rc != TextToSpeech.SUCCESS) {
      // Rejected outright: no callback will come, so stop instead of spinning. The report shows it.
      rejected++
      running = false
      f.delete()
    }
  }

  private fun complete(ok: Boolean) {
    synchronized(lock) {
      if (ok) {
        synthMs.add(SystemClock.elapsedRealtime() - currentStartedAt)
        // WAV size tracks audio duration, so bytes per char exposes a rate leaking INTO this
        // instance (faster audio = fewer bytes), which synth time alone would hide.
        current?.let { bytesPerChar.add(it.length() / currentChars) }
      } else {
        errors++
      }
      current?.delete()
      if (running) nextLocked()
    }
  }

  private inner class Listener : UtteranceProgressListener() {
    override fun onStart(id: String) {}
    override fun onDone(id: String) = complete(ok = true)
    @Deprecated("Deprecated in Java") override fun onError(id: String) = complete(ok = false)
    override fun onError(id: String, code: Int) = complete(ok = false)
    override fun onStop(id: String, interrupted: Boolean) {
      synchronized(lock) { stops++ }
    }
  }

  fun stopAndReport(): JSONObject {
    synchronized(lock) {
      running = false
      stopped = true
    }
    handler.removeCallbacksAndMessages(null)
    tts.stop()
    tts.shutdown()
    synchronized(lock) {
      current?.delete()
      val sorted = synthMs.sorted()
      return JSONObject()
          .put("active", active)
          .put("initStatus", initStatus)
          .put("synths", sorted.size)
          .put("synthP50", if (sorted.isEmpty()) 0 else GapStats.percentile(sorted, 50))
          .put("synthP95", if (sorted.isEmpty()) 0 else GapStats.percentile(sorted, 95))
          .put("bytesPerCharP50", bytesPerChar.sorted().let { if (it.isEmpty()) 0 else GapStats.percentile(it, 50) })
          .put("errors", errors)
          .put("rejected", rejected)
          .put("stops", stops)
    }
  }
}
```

- [ ] **Step 6: Implement `SpikeService.kt`, the activity forwarding, and the manifest entries**

`android/app/src/main/java/io/loopstring/readme/spike/SpikeService.kt`:

```kotlin
package io.loopstring.readme.spike

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import org.json.JSONObject

/**
 * Spike-branch foreground-service host for the native spikes. Started only by MainActivity
 * (adb cannot start an unexported service). Reports `SPIKE_<KIND> {json}` under [TAG].
 */
class SpikeService : Service() {
  private val handler = Handler(Looper.getMainLooper())
  private var gap: GapProbe? = null
  private var load: SynthLoad? = null
  private var wakeLock: PowerManager.WakeLock? = null

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    sweepCache()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val cmd = intent?.getStringExtra("cmd") ?: "stop"
    goForeground(intent?.getStringExtra("fgs") ?: "media", cmd)
    when (cmd) {
      "gap" -> startGap(intent!!)
      else -> {
        stopEverything()
        stopSelf()
      }
    }
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    stopEverything()
    super.onDestroy()
  }

  /** WAVs left by a killed run are removed before anything else runs (R-M12 cleanup habit). */
  private fun sweepCache() {
    val stale = cacheDir.listFiles { f -> f.name.startsWith("spike-") && f.name.endsWith(".wav") }.orEmpty()
    stale.forEach { it.delete() }
    report("SWEEP", JSONObject().put("deleted", stale.size))
  }

  private fun goForeground(fgs: String, cmd: String) {
    val nm = getSystemService(NotificationManager::class.java)
    val builder = if (Build.VERSION.SDK_INT >= 26) {
      nm.createNotificationChannel(NotificationChannel(CHANNEL, "Spikes", NotificationManager.IMPORTANCE_LOW))
      Notification.Builder(this, CHANNEL)
    } else {
      @Suppress("DEPRECATION") Notification.Builder(this)
    }
    val n = builder
        .setSmallIcon(android.R.drawable.ic_media_play)
        .setContentTitle("Read Me spike: $cmd ($fgs)")
        .setOngoing(true)
        .build()
    val type = when {
      fgs == "special" && Build.VERSION.SDK_INT >= 34 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
      Build.VERSION.SDK_INT >= 29 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
      else -> 0
    }
    if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, n, type) else startForeground(NOTIFICATION_ID, n)
    report("FGS", JSONObject().put("cmd", cmd).put("requested", fgs).put("type", type).put("sdk", Build.VERSION.SDK_INT))
  }

  private fun startGap(intent: Intent) {
    stopEverything()
    val mode = intent.getStringExtra("mode") ?: "single"
    val rate = intent.getFloatExtra("rate", 2.0f)
    val durationMs = intent.getIntExtra("minutes", 10) * 60_000L
    val useWakeLock = intent.getBooleanExtra("wakelock", false)
    val sentences = SpikeText.sentences(assets.open("spike/corpus.txt").bufferedReader().use { it.readText() })
    if (useWakeLock) acquireWakeLock(durationMs + 120_000L)
    if (mode != "single") {
      load = SynthLoad(this, sentences, active = mode == "concurrent" || mode == "synthonly")
    }
    if (mode == "synthonly") {
      handler.postDelayed({
        val l = load
        load = null
        report("RESULT", JSONObject().put("mode", mode).put("load", l?.stopAndReport()))
        releaseWakeLock()
        stopSelf()
      }, durationMs)
      return
    }
    gap = GapProbe(this, sentences, rate, durationMs) { result ->
      handler.post {
        val l = load
        load = null
        gap = null
        result.put("mode", mode).put("wakelock", useWakeLock).put("sentencesAvailable", sentences.size)
        if (l != null) result.put("load", l.stopAndReport())
        report("RESULT", result)
        releaseWakeLock()
        stopSelf()
      }
    }
  }

  private fun stopEverything() {
    gap?.cancel()
    gap = null
    load?.let { report("RESULT", JSONObject().put("cancelledLoad", it.stopAndReport())) }
    load = null
    handler.removeCallbacksAndMessages(null)
    releaseWakeLock()
  }

  private fun acquireWakeLock(timeoutMs: Long) {
    val pm = getSystemService(PowerManager::class.java)
    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ReadMe:spike").apply { acquire(timeoutMs) }
  }

  private fun releaseWakeLock() {
    wakeLock?.let { if (it.isHeld) it.release() }
    wakeLock = null
  }

  companion object {
    const val TAG = "ReadMeSpike"
    private const val CHANNEL = "spike"
    private const val NOTIFICATION_ID = 7001

    fun report(kind: String, json: JSONObject) {
      Log.i(TAG, "SPIKE_$kind $json")
    }
  }
}
```

`android/app/src/main/java/io/loopstring/readme/MainActivity.kt` (this branch starts from the template version, not the Task 4 one):

```kotlin
package io.loopstring.readme

import android.content.Intent
import android.os.Build
import android.os.Bundle
import com.facebook.react.ReactActivity
import com.facebook.react.ReactActivityDelegate
import com.facebook.react.defaults.DefaultNewArchitectureEntryPoint.fabricEnabled
import com.facebook.react.defaults.DefaultReactActivityDelegate
import io.loopstring.readme.spike.SpikeService

class MainActivity : ReactActivity() {

  override fun getMainComponentName(): String = "ReadMe"

  override fun createReactActivityDelegate(): ReactActivityDelegate =
      DefaultReactActivityDelegate(this, mainComponentName, fabricEnabled)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    forwardNativeSpike(intent)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    forwardNativeSpike(intent)
  }

  /**
   * Spike branch only. adb (uid 2000) cannot start the unexported SpikeService: AOSP lets only
   * root and system reach unexported components. So scripts start this exported activity with
   * `--ez nativeSpike true` plus the spike extras, and it forwards them while in the foreground.
   */
  private fun forwardNativeSpike(intent: Intent?) {
    if (intent?.getBooleanExtra("nativeSpike", false) != true) return
    val forward = Intent(this, SpikeService::class.java).putExtras(intent.extras ?: return)
    if (Build.VERSION.SDK_INT >= 26) startForegroundService(forward) else startService(forward)
  }
}
```

In `android/app/src/main/AndroidManifest.xml`, add these after the `INTERNET` permission:

```xml
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />
```

and this inside `<application>`, after the `MainActivity` element:

```xml
      <!-- Spike branch only (SPIKE-01, -05, -06). Started by MainActivity, never by adb. -->
      <service
        android:name=".spike.SpikeService"
        android:exported="false"
        android:foregroundServiceType="mediaPlayback|specialUse">
        <property
          android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
          android:value="Loopback text-to-speech bridge serving audio to another app on this device" />
      </service>
```

- [ ] **Step 7: Write `scripts/lib/spike.sh` and `scripts/spike-gap.sh`**

`scripts/lib/spike.sh`:

```bash
# Sourced after scripts/lib/device.sh. Native spike commands go through the exported
# MainActivity (adb cannot start the unexported SpikeService). Never pass item text here.

# spike_native <am extras...>: start MainActivity with nativeSpike=true and the extras, then
# require a SPIKE_FGS line within 10 s. Exits 4 if am errors or the service never started.
spike_native() {
  adb logcat -c
  local out
  out=$(adb shell am start -n "$PKG/.MainActivity" --ez nativeSpike true "$@" 2>&1)
  if printf '%s' "$out" | grep -qiE 'error|exception'; then
    echo "am start failed: $(printf '%s' "$out" | grep -iE 'error|exception' | head -2)" >&2
    exit 4
  fi
  if ! spike_wait SPIKE_FGS 10 >/dev/null; then
    echo "SpikeService did not start (no SPIKE_FGS in 10 s). Crash buffer:" >&2
    adb logcat -d -b crash | tail -15 >&2   # e.g. ForegroundServiceStartNotAllowedException: an answer
    exit 4
  fi
}

# spike_wait <PATTERN> <timeout_s>: print the payload of the first ReadMeSpike line matching.
spike_wait() {
  local pattern=$1 timeout=$2 line
  for _ in $(seq "$timeout"); do
    line=$(adb logcat -d -s ReadMeSpike:I | grep -m1 "$pattern" || true)
    if [ -n "$line" ]; then echo "${line#*"$pattern" }"; return 0; fi
    sleep 1
  done
  return 1
}
```

`scripts/spike-gap.sh`:

```bash
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
device_require_committed
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
```

```bash
chmod +x scripts/spike-gap.sh
```

- [ ] **Step 8: Unit tests, build, and a 1-minute smoke run**

Commit the probe first: the scripts refuse a dirty tree, and the hash printed at the end is then the build that was measured.

```bash
git add android/app/build.gradle android/app/src/main/AndroidManifest.xml \
  android/app/src/main/java/io/loopstring/readme/MainActivity.kt \
  android/app/src/main/java/io/loopstring/readme/spike android/app/src/test \
  android/app/src/main/assets/spike/corpus.txt scripts/fetch-spike-corpus.sh \
  scripts/lib/spike.sh scripts/spike-gap.sh
git commit -m "spike: SPIKE-05 gap probe and native spike harness (not for merge)"
(cd android && ./gradlew --quiet :app:testDebugUnitTest)
npm run build:release
scripts/spike-gap.sh single 1
```

Expected: `offline confirmed`, then about 70 s later a JSON line with `"reason":"done"`, `"initStatus":0`, `utterances` above 10, `gapCount` equal to `utterances - 1` (give or take one), and a non-zero `usPerCharP50`. The phone ends locked: ask the owner to unlock it before the next step.

- [ ] **Step 9: Reproduce the original refusal, and prove `spike_native` fails fast (Review Focus 4)**

```bash
( . scripts/lib/device.sh; . scripts/lib/spike.sh
  device_take interactive; device_require_unlocked
  echo "-- direct adb start of the unexported service (expected: refused)"
  adb shell am start-foreground-service -n "$PKG/.spike.SpikeService" --es cmd stop 2>&1 | tail -2
  echo "-- forwarding disabled (expected: exit 4 after ~10 s)"
  SECONDS=0; ( spike_native --es cmd stop --ez nativeSpike false ); echo "exit=$? after ${SECONDS}s"
  echo "-- forwarding enabled (expected: SPIKE_FGS seen, exit 0)"
  ( spike_native --es cmd stop ); echo "exit=$?" )
```

Expected: the direct start prints a refusal naming "not exported" or a SecurityException. This is the on-device confirmation of critique F2; record the exact line. The forwarding-disabled call ends `exit=4 after 10s` or `11s` (`am` applies the later `--ez nativeSpike false`). The normal call ends `exit=0`.

- [ ] **Step 10: Prove the phone is restored after an interrupted run (Review Focus 2)**

```bash
scripts/spike-gap.sh single 1 & PID=$!; sleep 25; kill -TERM $PID; wait $PID || true
adb shell settings get global airplane_mode_on
adb shell settings get global wifi_on
adb shell pidof io.loopstring.readme || echo "app stopped"
ls "$(dirname "$(git rev-parse --path-format=absolute --git-common-dir)")/.claude/device.lock" 2>/dev/null || echo "slot released"
```

Expected: the original airplane value (`0` if it was off before), the original Wi-Fi value, `app stopped`, `slot released`. The owner unlocks the phone afterwards.

- [ ] **Step 11: Run SPIKE-05**

Each run takes `minutes + ~1`. Use `run_in_background`, wait for the completion notification, and have the owner unlock the phone between runs.

```bash
scripts/spike-gap.sh single 10 | tee -a /tmp/claude-spike05.jsonl   # baseline, one instance
scripts/spike-gap.sh idle2 10  | tee -a /tmp/claude-spike05.jsonl   # as SPIKE-05 specifies: two instances alive
git rev-parse --short HEAD
```

Validity first: `idle2` counts only if its `load.initStatus` is `0`, meaning the second instance actually bound. Otherwise it was a `single` run; record why and rerun.

Decision rule: compare `idle2` `gapP95` and `gapMax` against R-M07: p95 at most 300 ms, max at most 1,000 ms, `stalls` 0, `reason` `done`. If `reason` is `hung` or `stalls` is above 0, rerun `scripts/spike-gap.sh idle2 10 2.0 true`. If the wake lock fixes it, PlaybackService needs a partial wake lock, and that is part of the answer.

- [ ] **Step 12: Record the answer, ship the answer**

If any step above changed code, it was committed before the run that measured it (the scripts enforce this).

The answer must state:
- `single` and `idle2` `gapP50`, `gapP95`, `gapMax`, `stalls` and `reason` against R-M07;
- whether a wake lock was needed;
- the Step 9 refusal line;
- whether R-M07's 300 ms target stands. If it has to change, amend R-M07 with the old value, the new value and the measurement;
- "Not established: other engines (`com.google.android.tts` is installed but was not the default), rates other than 2.0x, targetSdk 37, playback with a MediaSession and audio focus, battery power and Doze (the phone was on USB power: `USB powered: true`, `stay_on_while_plugged_in=7`, deviceidle ACTIVE, read 2026-10-01)". If the owner wants Doze covered, add an owner-approved run after `adb shell dumpsys battery unplug` and `adb shell dumpsys deviceidle force-idle`, and restore with `dumpsys battery reset` and `deviceidle unforce`.

Push the probe branch without a PR, then ship the answer with Task 9's "Shipping a spike answer".

---

### Task 7: SPIKE-06, two `TextToSpeech` instances

Branch: probe branch from `/start-issue <SPIKE-06 issue>`, then `git merge --no-edit <Task 6 probe branch>`. No new code; the branch pins the build that was measured.

- [ ] **Step 1: Run the concurrency matrix (owner unlocks the phone before each run)**

```bash
npm run build:release
scripts/spike-gap.sh concurrent 10 | tee -a /tmp/claude-spike06.jsonl   # speak() + synthesizeToFile() at once
scripts/spike-gap.sh synthonly 3   | tee -a /tmp/claude-spike06.jsonl   # synth baseline, no playback
git rev-parse --short HEAD
```

Compare against the Task 6 `idle2` line in `/tmp/claude-spike05.jsonl`.

- [ ] **Step 2: Apply the decision rule**

First, the load must have actually run. In both `concurrent` and `synthonly`, `load.initStatus` is `0`, `load.rejected` is `0` and `load.synths` is above `0`. If not, the run is invalid, not "independent": record why, and rerun or stop.

The instances are independent only if all of these hold in `concurrent`:
- `stopsBeforeFinish` 0, `errors` 0 and `load.errors` 0, so neither instance cancelled the other;
- `gapP95` within 20% of `idle2`;
- `load.synthP50` within 50% of the `synthonly` `load.synthP50`, so synthesis is not serializing behind playback;
- `load.bytesPerCharP50` within 10% of the `synthonly` value. A rate leaking from playback (2.0) into the load (1.0) would shrink the WAVs, which a faster `synthP50` would otherwise hide.
- `usPerCharP50` within 15% of `idle2`. Playback runs at 2.0 and the load runs at 1.0, so a rate leak between instances shows up as roughly twice the time per character. That would violate non-negotiable 9 inside the app.

If any check fails, record which one. AGENTS.md 13 then needs `docs/adr/0004-tts-contention-policy.md`, with the policy SPIKE-06 names, for example "the bridge answers 503 while Read Me is playing".

- [ ] **Step 3: Record and ship**

The answer must state: the comparisons with their numbers; the verdict; "Not established: two different engines, the bridge's real request pattern (plugin chunk sizes and pauses), targetSdk 37, battery power and Doze". Push the probe branch without a PR, then ship the answer (plus the ADR if one was written) with Task 9's "Shipping a spike answer".

---

### Task 8: SPIKE-01, bridge synthesis while backgrounded behind Obsidian

Branch: probe branch from `/start-issue <SPIKE-01 issue>`, then `git merge --no-edit <Task 6 probe branch>`.

**Files:**
- Create: `android/app/src/main/java/io/loopstring/readme/spike/SpikeBridge.kt`
- Modify: `android/app/src/main/java/io/loopstring/readme/spike/SpikeService.kt` (`bridge` command)
- Create: `scripts/spike-bridge.sh`, `scripts/obsidian-cdp-probe.mjs`

**Interfaces:**
- Consumes: `SpikeService.report`, `goForeground`, `stopEverything` (Task 6); `spike_native`, `spike_wait`, the `device.sh` functions
- Produces: `SpikeBridge(context: Context, token: String, port: Int = 8787)` with `fun close()`. The constructor throws `java.net.BindException` if the port is taken. Each request logs `SPIKE_BRIDGE {"method","route","status","ms","importance"}`. `route` is `/health`, `/synthesize` or `other`, never the client's path. `importance` is this process's `RunningAppProcessInfo.importance`: 100 means foreground, 125 means foreground service.

This is not R-M12's `BridgeServer`. It answers one question. It still keeps the non-negotiables: an explicit `127.0.0.1` bind, the token checked before the body and never logged, the caps, the read timeout, the `Throwable` catch, rate set on every request, and WAVs deleted on every path.

- [ ] **Step 1: Implement `SpikeBridge.kt`**

```kotlin
package io.loopstring.readme.spike

import android.app.ActivityManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * SPIKE-01 only: the smallest loopback bridge that answers "does synthesis keep working
 * while Read Me is backgrounded and Obsidian is foreground, under this FGS type?".
 * Construct on the main thread. Throws BindException if [port] is in use. The [token] is
 * generated by the calling script and is never logged (AGENTS.md 4).
 */
class SpikeBridge(context: Context, private val token: String, private val port: Int = 8787) {
  private val cacheDir = context.cacheDir
  private val handler = Handler(Looper.getMainLooper())
  private val pool = Executors.newFixedThreadPool(4)
  private val synthLock = Any()
  private var seq = 0
  @Volatile private var ttsReady = false
  @Volatile private var pendingId: String? = null
  @Volatile private var latch: CountDownLatch? = null
  @Volatile private var failed = false
  // R-M12: explicit IPv4 loopback; getLoopbackAddress() returned ::1 on Android 17.
  private val server = ServerSocket(port, 16, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
  // Deferred like GapProbe: onInit may run inside the TextToSpeech constructor.
  private val tts: TextToSpeech =
      TextToSpeech(context.applicationContext) { status -> handler.post { onReady(status) } }

  init {
    Thread(::acceptLoop, "spike-bridge").start()
  }

  fun close() {
    try { server.close() } catch (_: IOException) {}
    pool.shutdownNow()
    handler.removeCallbacksAndMessages(null)
    tts.shutdown()
  }

  private fun onReady(status: Int) {
    if (status != TextToSpeech.SUCCESS) return
    tts.setLanguage(Locale.US)
    tts.setOnUtteranceProgressListener(Listener())
    ttsReady = true
  }

  private inner class Listener : UtteranceProgressListener() {
    override fun onStart(id: String) {}
    override fun onDone(id: String) {
      if (id == pendingId) latch?.countDown()
    }
    @Deprecated("Deprecated in Java")
    override fun onError(id: String) {
      if (id == pendingId) {
        failed = true
        latch?.countDown()
      }
    }
    override fun onError(id: String, code: Int) {
      @Suppress("DEPRECATION") onError(id)
    }
  }

  private fun acceptLoop() {
    while (!server.isClosed) {
      val s = try { server.accept() } catch (_: IOException) { break }
      try {
        pool.execute { serve(s) }
      } catch (_: java.util.concurrent.RejectedExecutionException) {
        s.close()   // close() raced an accept; the bridge is shutting down
        break
      }
    }
  }

  private fun serve(s: Socket) {
    val t0 = SystemClock.elapsedRealtime()
    var method = "?"
    var route = "?"
    var status = 0
    try {
      s.use {
        it.soTimeout = 10_000
        val input = BufferedInputStream(it.getInputStream())
        val out = BufferedOutputStream(it.getOutputStream())
        val head = readHead(input)
        if (head == null) {
          status = send(out, 431, null, "text/plain", "headers too large".toByteArray())
          return@use
        }
        val lines = head.split("\r\n")
        val parts = lines.first().split(" ")
        if (parts.size < 2) {
          status = send(out, 400, null, "text/plain", "bad request".toByteArray())
          return@use
        }
        method = parts[0]
        val path = parts[1].substringBefore('?')
        val query = parts[1].substringAfter('?', "")
        route = if (path == "/health" || path == "/synthesize") path else "other"
        val headers = lines.drop(1).mapNotNull { l ->
          val i = l.indexOf(':')
          if (i <= 0) null else l.substring(0, i).trim().lowercase(Locale.ROOT) to l.substring(i + 1).trim()
        }.toMap()
        val origin = headers["origin"]
        status = when {
          method == "OPTIONS" -> send(out, 204, origin, "text/plain", ByteArray(0))
          method == "GET" && path == "/health" -> send(out, 200, origin, "application/json",
              JSONObject().put("ok", true).put("ttsReady", ttsReady).put("port", port).toString().toByteArray())
          method == "POST" && path == "/synthesize" -> synthesize(input, out, headers, query, origin)
          else -> send(out, 404, origin, "text/plain", "not found".toByteArray())
        }
      }
    } catch (t: Throwable) {
      // R-M12: Throwable, so one connection can never take the service down. Class name only.
      Log.w(SpikeService.TAG, "conn: ${t.javaClass.simpleName}")
    }
    // The method is client-controlled; log only a fixed token (AGENTS.md 1).
    val loggedMethod = if (method in KNOWN_METHODS) method else "other"
    SpikeService.report("BRIDGE", JSONObject().put("method", loggedMethod).put("route", route).put("status", status)
        .put("ms", SystemClock.elapsedRealtime() - t0).put("importance", importance()))
  }

  private fun readHead(input: InputStream): String? {
    val buf = StringBuilder()
    while (true) {
      val c = input.read()
      if (c == -1) return buf.toString()
      buf.append(c.toChar())
      if (buf.length > MAX_HEAD) return null
      if (buf.endsWith("\r\n\r\n")) return buf.substring(0, buf.length - 4)
    }
  }

  private fun synthesize(
      input: InputStream,
      out: OutputStream,
      headers: Map<String, String>,
      query: String,
      origin: String?,
  ): Int {
    // Token before body (R-M12).
    if (headers["authorization"] != "Bearer $token") return send(out, 401, origin, "text/plain", "unauthorized".toByteArray())
    // Rate is applied once, here, by the engine (AGENTS.md 9). Default 1.0, never the system default.
    val rateParam = query.split('&').firstOrNull { it.startsWith("rate=") }?.removePrefix("rate=")
    val rate = if (rateParam == null) 1.0f else rateParam.toFloatOrNull()?.takeIf { it in 0.5f..4.0f }
        ?: return send(out, 400, origin, "text/plain", "bad rate".toByteArray())
    val len = headers["content-length"]?.toIntOrNull() ?: return send(out, 400, origin, "text/plain", "bad content-length".toByteArray())
    if (len < 0) return send(out, 400, origin, "text/plain", "bad content-length".toByteArray())
    if (len > MAX_BODY) return send(out, 413, origin, "text/plain", "body too large".toByteArray())
    val body = ByteArray(len)
    var got = 0
    while (got < len) {
      val k = input.read(body, got, len - got)
      if (k < 0) break
      got += k
    }
    if (got != len) return send(out, 400, origin, "text/plain", "short body".toByteArray())
    if (!ttsReady) return send(out, 503, origin, "text/plain", "tts not ready".toByteArray())
    synchronized(synthLock) {
      val id = "b${seq++}"
      val f = File(cacheDir, "spike-bridge-$id.wav")
      try {
        val l = CountDownLatch(1)
        latch = l
        pendingId = id
        failed = false
        tts.setSpeechRate(rate)
        val t0 = SystemClock.elapsedRealtime()
        if (tts.synthesizeToFile(String(body, Charsets.UTF_8), Bundle(), f, id) != TextToSpeech.SUCCESS) {
          return send(out, 500, origin, "text/plain", "synthesize rejected".toByteArray())
        }
        if (!l.await(60, TimeUnit.SECONDS) || failed || !f.exists()) {
          return send(out, 500, origin, "text/plain", "synthesis failed".toByteArray())
        }
        val ms = SystemClock.elapsedRealtime() - t0
        return send(out, 200, origin, "audio/wav", f.readBytes(), "X-Synth-Ms: $ms\r\nX-Rate: $rate\r\n")
      } finally {
        f.delete()
      }
    }
  }

  private fun send(out: OutputStream, code: Int, origin: String?, type: String, body: ByteArray, extra: String = ""): Int {
    val cors = if (origin == ALLOWED_ORIGIN) {
      "Access-Control-Allow-Origin: $ALLOWED_ORIGIN\r\n" +
          "Access-Control-Allow-Methods: GET, POST\r\n" +
          "Access-Control-Allow-Headers: Authorization, Content-Type\r\n" +
          "Access-Control-Expose-Headers: X-Synth-Ms, X-Rate\r\n"
    } else {
      ""
    }
    val head = "HTTP/1.1 $code ${REASONS[code] ?: "Status"}\r\n" +
        "Content-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n$cors$extra\r\n"
    out.write(head.toByteArray(Charsets.UTF_8))
    out.write(body)
    out.flush()
    return code
  }

  private fun importance(): Int =
      ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }.importance

  companion object {
    private const val MAX_HEAD = 16 * 1024
    private const val MAX_BODY = 64 * 1024
    private const val ALLOWED_ORIGIN = "http://localhost"
    private val KNOWN_METHODS = setOf("GET", "POST", "OPTIONS")
    private val REASONS = mapOf(
        200 to "OK", 204 to "No Content", 400 to "Bad Request", 401 to "Unauthorized",
        404 to "Not Found", 413 to "Payload Too Large", 431 to "Request Header Fields Too Large",
        500 to "Internal Server Error", 503 to "Service Unavailable",
    )
  }
}
```

- [ ] **Step 2: Add the `bridge` command to `SpikeService.kt`**

Add a field next to `load`: `private var bridge: SpikeBridge? = null`.

Replace the `when (cmd)` block in `onStartCommand` with:

```kotlin
    when (cmd) {
      "gap" -> startGap(intent!!)
      "bridge" -> startBridge(intent!!)
      else -> {
        stopEverything()
        stopSelf()
      }
    }
```

Add this method:

```kotlin
  private fun startBridge(intent: Intent) {
    stopEverything()
    val token = intent.getStringExtra("token")
    if (token == null || !TOKEN.matches(token)) {
      report("RESULT", JSONObject().put("bridge", "bad-token"))
      return
    }
    try {
      bridge = SpikeBridge(this, token)
      report("RESULT", JSONObject().put("bridge", "listening").put("port", 8787))
    } catch (e: java.net.BindException) {
      report("RESULT", JSONObject().put("bridge", "bind-failed").put("error", e.javaClass.simpleName))
    }
  }
```

In the `companion object`, add `private val TOKEN = Regex("[0-9a-f]{32}")`. In `stopEverything()`, add `bridge?.close()` and `bridge = null` before `handler.removeCallbacksAndMessages(null)`.

- [ ] **Step 3: Write `scripts/obsidian-cdp-probe.mjs`**

```js
// SPIKE-01: from INSIDE Obsidian's WebView (origin http://localhost), call the spike bridge
// with fetch and with CapacitorHttp, the two paths the plugin can use. Driven over CDP, the
// same way note-reader-local/AGENTS.md measured the prototype. Dev tooling, not app code.
// The token arrives on stdin, so it never appears in argv or a URL.
// Usage: printf '%s' "$TOK" | node scripts/obsidian-cdp-probe.mjs
import {execFileSync} from 'node:child_process';
import {readFileSync} from 'node:fs';

const token = readFileSync(0, 'utf8').trim();
if (!/^[0-9a-f]{32}$/.test(token)) throw new Error('expected a 32-hex token on stdin');
const adb = (...a) => execFileSync('adb', a, {encoding: 'utf8'}).trim();
const pid = adb('shell', 'pidof', 'md.obsidian');
if (!pid) throw new Error('Obsidian is not running');
adb('forward', 'tcp:9333', `localabstract:webview_devtools_remote_${pid}`);
try {
  const pages = await (await fetch('http://127.0.0.1:9333/json', {signal: AbortSignal.timeout(10_000)})).json();
  const page = pages.find(p => p.type === 'page' && p.webSocketDebuggerUrl);
  if (!page) throw new Error('no debuggable Obsidian page');
  const expression = `(async () => {
    const out = {};
    const auth = {Authorization: 'Bearer ${token}', 'Content-Type': 'text/plain'};
    try { out.fetchHealth = (await fetch('http://127.0.0.1:8787/health')).status; }
    catch (e) { out.fetchHealth = String(e); }
    try {
      const r = await Capacitor.Plugins.CapacitorHttp.post({url: 'http://127.0.0.1:8787/synthesize?rate=1.0',
        headers: auth, data: 'Background bridge spike through CapacitorHttp.', responseType: 'blob'});
      out.capSynth = r.status;
      out.capRate = r.headers['X-Rate'] ?? r.headers['x-rate'] ?? null;
    } catch (e) { out.capSynth = String(e); }
    try {
      const r = await fetch('http://127.0.0.1:8787/synthesize?rate=1.0',
        {method: 'POST', headers: auth, body: 'Background bridge spike through fetch.'});
      out.fetchSynth = r.status; out.fetchSynthMs = r.headers.get('X-Synth-Ms'); out.fetchRate = r.headers.get('X-Rate');
    } catch (e) { out.fetchSynth = String(e); }
    try { out.fetchNoToken = (await fetch('http://127.0.0.1:8787/synthesize', {method: 'POST', body: 'x'})).status; }
    catch (e) { out.fetchNoToken = String(e); }
    return JSON.stringify(out);
  })()`;
  const ws = new WebSocket(page.webSocketDebuggerUrl);
  await new Promise((resolve, reject) => { ws.onopen = resolve; ws.onerror = reject; });
  // Bounded: a hung evaluate must not hold the device slot forever. The timer is cleared so a
  // successful probe exits at once instead of waiting out the 90 s.
  let timer;
  const reply = await Promise.race([
    new Promise(resolve => {
      ws.onmessage = m => { const d = JSON.parse(m.data); if (d.id === 1) resolve(d); };
      ws.send(JSON.stringify({id: 1, method: 'Runtime.evaluate',
        params: {expression, awaitPromise: true, returnByValue: true}}));
    }),
    new Promise((_, reject) => {
      timer = setTimeout(() => reject(new Error('CDP evaluate timed out after 90 s')), 90_000);
    }),
  ]).finally(() => clearTimeout(timer));
  ws.close();
  console.log('OBSIDIAN', reply.result?.result?.value ?? JSON.stringify(reply.result?.exceptionDetails ?? reply));
} finally {
  adb('forward', '--remove', 'tcp:9333');
}
```

The token is interpolated into the CDP expression, which is evaluated inside Obsidian on the same phone. It isn't a URL, isn't argv, and isn't logged.

- [ ] **Step 4: Write `scripts/spike-bridge.sh`**

```bash
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
device_require_committed
device_require_unlocked
device_install_release
TOK=$(openssl rand -hex 16)   # generated here; the app never logs it (AGENTS.md 4)
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null || true
adb shell am force-stop io.loopstring.ttsbridge 2>/dev/null || true
adb shell am force-stop "$PKG"
spike_native --es cmd bridge --es fgs "$FGS" --es token "$TOK"
spike_wait SPIKE_RESULT 10 | grep -q '"listening"' || { echo "bridge did not start" >&2; exit 1; }
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
```

```bash
chmod +x scripts/spike-bridge.sh
```

The last line must print `0`. The `curl` calls pass the token in a header on the host's own command line. That is the same exposure the prototype's `measure.sh` accepted, and it never reaches the phone's logs.

- [ ] **Step 5: Build and run SPIKE-01 under both types (owner unlocks the phone before each)**

Commit the probe first: the scripts refuse a dirty tree, and the hash printed at the end is then the build that was measured.

```bash
git add android/app/src/main/java/io/loopstring/readme/spike/SpikeBridge.kt \
  android/app/src/main/java/io/loopstring/readme/spike/SpikeService.kt \
  scripts/spike-bridge.sh scripts/obsidian-cdp-probe.mjs
git commit -m "spike: SPIKE-01 background bridge probe, driven from inside Obsidian (not for merge)"
(cd android && ./gradlew --quiet :app:testDebugUnitTest)
npm run build:release
scripts/spike-bridge.sh media   2>&1 | tee /tmp/claude-spike01-media.txt
scripts/spike-bridge.sh special 2>&1 | tee /tmp/claude-spike01-special.txt
git rev-parse --short HEAD
```

Expected for each type:
- `spike_native` succeeds (a `SPIKE_FGS` line), or it exits 4 with the `am` error. Either one is an answer.
- Both `OBSIDIAN` lines show `fetchHealth`, `capSynth` and `fetchSynth`. `fetchSynth` 200 proves the OPTIONS preflight path that the prototype lacked. `fetchRate` and `capRate` are `1.0`. `fetchNoToken` is a number (401), not a CORS error, which proves the 401 response is readable from the WebView.
- 20 `synth` lines, and the bridge summary shows importance `125`.
- The token count line prints `0`.

Decision rule: if `media` passes everything, the bridge can share PlaybackService's `mediaPlayback` type, so one service and one type. If only `special` passes, a bridge-only session runs under `specialUse`, playback under `mediaPlayback`, and the service declares both. If neither passes, record what failed; Phase 5 is blocked pending an amendment.

- [ ] **Step 6: Port in use (Review Focus 3)**

```bash
( . scripts/lib/device.sh; . scripts/lib/spike.sh
  cleanup_pf() { adb shell am force-stop io.loopstring.ttsbridge; adb shell am force-stop "$PKG"; }
  DEVICE_ON_EXIT=cleanup_pf
  device_take interactive; device_require_unlocked
  adb shell am start -n io.loopstring.ttsbridge/.MainActivity >/dev/null; sleep 3
  spike_native --es cmd bridge --es token "$(openssl rand -hex 16)"
  spike_wait SPIKE_RESULT 10
  adb shell pidof "$PKG" )
```

Expected: `{"bridge":"bind-failed","error":"BindException"}`, and `pidof` still prints a pid. Note that the prototype logs its own token to logcat by design; that is its behaviour, not Read Me's.

- [ ] **Step 7: Cache sweep**

```bash
( . scripts/lib/device.sh; . scripts/lib/spike.sh
  device_take interactive; device_require_unlocked
  adb shell am force-stop "$PKG"
  spike_native --es cmd stop; spike_wait SPIKE_SWEEP 5 )
```

Expected: a `{"deleted":N}` payload. The line has to exist, which proves the sweep runs at service start.

- [ ] **Step 8: Record the answer with an ADR, ship the answer**

The answer must state:
- per type: the `SPIKE_FGS` result, the CDP results, the 20-call status counts, the importance values, and the token count of `0`;
- the chosen service-type design;
- "Not established: Android 14-16 (only the Android 17 reference device was available), targetSdk 37, screen-off bridge use while Obsidian itself is backgrounded, the real plugin's request pattern".

Write `docs/adr/0005-foreground-service-types.md`, because the answer fixes R-M12's service type. Push the probe branch without a PR, then ship the answer and the ADR with Task 9's "Shipping a spike answer".

---

### Task 9: Answer template, and closing Phase 0

**Answer template.** Each spike task above records its answer with this, indented under the matching `**SPIKE-0N - ...**` bullet in `srs.md`:

```markdown
  **Answer (YYYY-MM-DD; Pixel 9 Pro XL, GrapheneOS, Android 17 (API 37); app targetSdk 36;
  build <short sha> on <spike branch>; `scripts/<script> <args>`):** <one-sentence verdict>.
  Observed: <the numbers that decide it, copied from the run output>.
  Not established: <what this run cannot speak to>.
  Consequence: <requirement or phase that changes, with the ADR if any, or "none">.
```

**Shipping a spike answer.** The probe branch holds the probe commits. The answer is one commit containing only `srs.md` and any new `docs/adr/*.md`:

```bash
PROBE=$(git branch --show-current)                 # e.g. spike/rea-7-hermes-segmenter
git add srs.md docs/adr && git commit -m "docs(srs): record SPIKE-0N answer"
ANSWER_SHA=$(git rev-parse HEAD)
git show --stat --format= "$ANSWER_SHA"           # must list only srs.md and docs/adr files
git push -u origin "$PROBE"                        # probe branch: pushed, never PR'd
git fetch origin main
# Gitignored spike output (e.g. src/spikes/fixtures.generated.ts) is ignored only on the probe
# branch and would show as untracked on main. Remove it; `npm run spike:fixtures` restores it.
rm -f src/spikes/fixtures.generated.ts
git checkout -b "$PROBE-answer" origin/main
[ -z "$(git status --porcelain)" ] || { echo "answer branch is not clean; stop"; git status --short; }
git config "branch.$PROBE-answer.base-branch" main
git cherry-pick "$ANSWER_SHA"
git diff --stat origin/main...HEAD                 # must list only srs.md and docs/adr files
```

Then run `/ship` on the `-answer` branch (it matches `spike/rea-{N}-*`, so `/ship` treats it as a spike). After it merges, run `/finish` on the `-answer` branch **but skip its Step 10** (doc edits committed directly on `main`): this plan allows no direct commits to `main`, and the Known-state and roadmap updates for all spikes go in one docs branch in Step 2 below. The probe branch stays (Step 3 below). To return to the probe branch: `git checkout "$PROBE" && npm run spike:fixtures` where that script exists.

Every number in an answer comes from that spike's saved output (AGENTS.md 17). An answer that rests on reasoning rather than a device run is not recorded. minSdk and targetSdk are **not** spike answers. If R-M13's "set by SPIKE-01 findings" turns out not to hold, say so in the SPIKE-01 answer and leave the values to an owner decision with an ADR.

**Closing Phase 0**, after the last spike PR merges, as part of that issue's `/finish`:

- [ ] **Step 1: Check that every answer is on `main`**

```bash
git checkout main && git pull --ff-only
# Count "Answer (" inside each spike's bullet block (up to the next spike bullet or heading).
for n in 1 2 3 4 5 6; do
  printf "SPIKE-0$n: "
  awk -v id="SPIKE-0$n" '/^- \*\*SPIKE-0[0-9]/ {blk = index($0, id) > 0; next} /^#/ {blk = 0} blk && /Answer \(/ {c++} END {print c + 0}' srs.md
done
grep -rnP "\x{2014}" srs.md AGENTS.md CONTEXT.md docs || echo "no em-dashes"
```

Expected: each spike shows `1`, and the last line is `no em-dashes`.

- [ ] **Step 2: Run `/update-docs`**

Bring AGENTS.md "Known state" (replace "Six spikes ... None has run" with one line per verdict), CONTEXT.md "Known structural gaps", and the roadmap's Phase 0 row ("done (date)", verdicts listed) in line with the merged answers. Commit on a `feature/rea-N-*` docs branch, then `/ship`.

- [ ] **Step 3: Keep the spike branches**

Push each `spike/rea-*` branch and leave it in place. `/finish` deletes an unmerged spike branch only after confirming its answer is on `origin/main` (`finish.md`). The owner has decided the probe code is not merged, so the branch is its only home. Before `/finish` deletes any of them, ask whether the owner wants that probe kept (for example, the gap probe for re-measuring R-M07 in Phase 3).
