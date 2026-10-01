---
description: Run the local gates if they exist, install the release build on the attached phone, walk a manual test plan over adb, and post what was actually observed to the Linear issue.
---

Verify a change. There is no code yet, so there are no gates and no CI workflow yet; the
scaffold ticket establishes both (`AGENTS.md`, "Quality gates"). Until then this command reports
`GATES: NOT YET ESTABLISHED` and never invents a pass. Once CI exists it is a backstop, not the
source of truth: you **may** read a check conclusion once and report it; you **must not** wait on
it.

Conventions: `.claude/linear.md`. Rules: `AGENTS.md`. Spec: `srs.md`.

Argument: `$ARGUMENTS` (optional Linear identifier such as `REA-12`, `rea-12`, or `12`).

## The distinction this command exists to enforce

**Tests pass** and **verified working** are different claims. Unit tests run against fakes.
`AGENTS.md` rule 15 is explicit: a green test suite is not a claim that something works, and
`srs.md` R-M14 says a requirement is met only when observed on a real device, recorded with the
date, device and build.

So the verdict has four levels, and you must pick the honest one:

| Verdict | Means |
|---|---|
| `GATES: NOT YET ESTABLISHED` | No gate scripts exist yet. Nothing was run, so nothing passed. Combine with a device result if one was taken. |
| `GATES GREEN, NOT VERIFIED` | Gates pass. The change was not exercised on the phone. |
| `VERIFIED ON DEVICE` | Gates green (or not yet established, said so) **and** a user-observable check ran against the installed build on a named device, and the observation is recorded below with device and build. |
| `FAILED` | A gate is red, or the device check did not do what the issue said it would. |

Never emit `VERIFIED ON DEVICE` on the strength of the test suite, and never without naming the
device (model, OS) and the build (commit and how it was built). If the phone was not driven, the
verdict is `GATES GREEN, NOT VERIFIED` and the comment says so in those words.

A spike issue (`SPIKE-0N`, branch `spike/*`) is verified differently: the deliverable is the
recorded answer in `srs.md` "Spikes", not shipped code. Verify that the answer cites a
measurement taken on a named device, and skip Steps 3-4 if the spike branch has no gates.

## Linear tool naming

Do not hardcode an MCP tool prefix. Operations used here: `get_issue`, `list_issues`,
`save_comment`. Resolve the real prefixed names for the `linear-rea` server from your available
tool list. Resolving the prefix is not enough: Linear folded its create/update pairs into
`save_*`, so posting a comment is `save_comment` and there is no `create_comment` (see
`.claude/linear.md`). If no Linear tool is present, print the comment body instead of posting
it. Never post a Read Me result through `linear-nrl`.

## Step 1: Resolve the issue

With an argument: normalise it (accepts `REA-12`, `rea-12`, `12`) and fetch it with `get_issue`.

Without an argument:

```bash
git branch --show-current
```

Branches are `feature/rea-{N}-{slug}`, `fix/rea-{N}-{slug}` or `spike/rea-{N}-{slug}`. Pull
`{N}` from that. If the branch carries no issue number, run `list_issues` with `assignee: "me"`
and a started status, and ask which one. If there is no Linear at all, verify anyway and report
locally.

Read the issue's **Acceptance criteria** section. That is the manual test plan for Step 6. If
the issue has none, build one from the diff and the `srs.md` requirement it names, and say in
the comment that the plan was inferred rather than taken from the issue.

## Step 2: Work out which gates are relevant

```bash
git status --short
git diff --name-only $(git merge-base HEAD main)...HEAD
```

| Changed | Gate required |
|---|---|
| Anything under `src/` or TS tests | `npm test`, `npm run typecheck`, `npm run lint` |
| Anything under `android/` | `(cd android && ./gradlew testDebugUnitTest)` |
| Dependencies (`package.json`, lockfile, any `build.gradle`) | plus `(cd android && ./gradlew assembleRelease)` and the dependency check in Step 4 |
| Only `*.md` | Gates optional. Say which you skipped and why. |

## Step 3: Run the gates that exist

**Check each gate exists before running it** (rule 18). A missing script is
`NOT YET ESTABLISHED`, not a failure and not a pass:

```bash
npm run 2>/dev/null | grep -E '^\s+(test|typecheck|lint|device:install|device:smoke)$' || echo "no npm scripts"
ls android/gradlew 2>/dev/null || echo "no android project"
ls .github/workflows/ 2>/dev/null || echo "no CI workflows"
```

In a fresh worktree `node_modules` and the Gradle cache do not exist; run `npm ci` first or a
gate cannot pass or fail.

Run every relevant gate that exists, from the set in `AGENTS.md` "Quality gates" (that block is
the one place the list lives; do not copy it here). Report each one's real output.

**Stop here if any gate is red.** Report the failing output verbatim and do not install. Do not
summarise a failure into a guess about its cause unless you checked.

## Step 4: Build and dependency checks, when the release build ran

Non-negotiable 14 (F-Droid-clean) is a property of the resolved dependency tree, not of
`package.json`:

```bash
(cd android && ./gradlew :app:dependencies --configuration releaseRuntimeClasspath) \
  | grep -i -E 'gms|firebase|crashlytics|play-services|com\.google\.android\.play' || echo "none found"
```

Any hit is a BLOCK. Record the APK size and anything else you quote from what you actually saw.
Rule 17: do not quote a size, a gap or a latency you did not measure in this session.

## Step 5: Take the phone and install

One phone, one lane. Take the slot first, in the primary repo:

```bash
LOCK="$HOME/Documents/Dev/Read-Me/.claude/device.lock"
mkdir "$LOCK" 2>/dev/null || { echo "HELD BY ANOTHER LANE - stop: $(head -2 "$LOCK/owner" 2>/dev/null | tr '\n' ' ')"; exit 1; }
# Same owner format as worktrees.md and run-tickets.md, so scripts/lib/device.sh can see
# this lane already holds the slot when npm run device:install runs below.
printf '%s\nbranch=%s commit=%s at=%s purpose=interactive\n' "$(git rev-parse --show-toplevel)" "$(git branch --show-current)" "$(git rev-parse --short HEAD)" "$(date -Is)" > "$LOCK/owner"
adb devices
```

`mkdir` failing means another lane holds the phone: stop, do not install. `adb devices` must
show exactly one device in state `device`; zero, two, or `unauthorized` means stop and say so.

```bash
npm run device:install
```

If that script does not exist yet, the device half is `NOT RUN: device:install not established`.
Do not hand-roll an install of an unknown build and call it verified. Installing replaces
whatever build another lane installed; say in the Linear comment which worktree and commit
installed.

Record the device: `adb shell getprop ro.product.model`, `adb shell getprop
ro.build.version.release`, and the commit (`git rev-parse --short HEAD`).

Release the slot when Step 6 is done, pass or fail:

```bash
rm -rf "$HOME/Documents/Dev/Read-Me/.claude/device.lock"
```

## Step 6: Drive the app as a user would

Use adb to do what a user does, then read what happened. `$PKG` is the app's applicationId as
recorded in `CONTEXT.md` once the scaffold sets it.

```bash
adb shell am start -n "$PKG/.MainActivity"
adb shell am start -a android.intent.action.SEND -t text/plain \
  --es android.intent.extra.TEXT 'https://example.org/some-article'
adb shell input tap <x> <y>          # coordinates from the dump below
adb shell uiautomator dump /sdcard/ui.xml && adb pull /sdcard/ui.xml
adb logcat -d -s <app log tag>       # the app's own tag only
```

**Never paste item text into the Linear comment, a log excerpt or this report** (non-negotiable
1). Quote states, counts, ids and durations; a `uiautomator` dump contains article text, so read
it locally and report only the facts you needed from it. If logcat shows item text, that is
itself a BLOCK to report.

Baseline checks, in addition to the issue's acceptance criteria, where the build has the feature:

| Check | What to actually do | Watch for |
|---|---|---|
| Share intake | The SEND intent above, then return to the source app | Item appears in the list in `fetching`, then `ready`, `extract-poor` or `fetch-failed` (R-M02, R-M10) |
| Playback | Open the item, play at 2.0x | Audible speech; current sentence highlighted (R-M07) |
| Rate once | Change rate mid-read | Applied once by the engine. 2.0x that sounds like 4x is non-negotiable 9 broken |
| Screen off | `adb shell input keyevent 26`, wait, wake | Playback continued; position correct after unlock (R-M07, R-M11) |
| Offline | Airplane mode on, confirm with `adb shell ping -c1 1.1.1.1` returning unreachable | Any offline claim is made only under this condition (R-M09) |
| Bridge | Obsidian on the same phone with `local-tts-reader`; CDP via `adb forward tcp:<port> localabstract:webview_devtools_remote_<pid>` | `/health` 200 and `/synthesize` returns audio from inside Obsidian's WebView; token absent from logcat (R-M12) |

The bridge row's plugin side is the sibling repo's NRL-130; drive it as that repo's `AGENTS.md`
records, but file nothing there from here.

**Record what you observed, not what you expected.** Past tense, concrete terms: "item reached
`ready` 4.1 s after the intent, by logcat timestamps" beats "intake works". If a step could not
be run, mark it `NOT RUN` with the reason. Never mark an unrun step as passed. Restore the phone
afterwards (airplane mode off, test items deleted) and say that you did.

## Step 7: Summary

```
Verification for REA-12
=======================

Gates
  npm test            GATES: NOT YET ESTABLISHED (no script)
  gradle unit tests   GATES: NOT YET ESTABLISHED (no android/)

Installed
  npm run device:install from <worktree path> at <commit>
  Device: Pixel 9 Pro XL, Android 17 (GrapheneOS); slot taken and released

Observed on device
  1. Share intent with a real article URL   OBSERVED: item reached ready, 11 paragraphs
  2. Play at 2.0x, screen off 60 s          OBSERVED: speech continued, position held
  3. Bridge from Obsidian                    NOT RUN: bridge not in this build

Not verified
  - Offline: airplane mode not used this run

Verdict: VERIFIED ON DEVICE (Pixel 9 Pro XL, <commit>; gates not yet established)
```

## Step 8: Post to Linear

`save_comment` on the issue with the Step 7 summary verbatim.

The comment **must** contain an explicit sentence separating the two claims, for example:

```
Unit tests pass (or do not exist yet). That is not a claim the change works. What was
exercised on the phone is listed under "Observed"; everything under "Not verified" was not tested.
```

If a Linear tool is unavailable, print the comment body for manual pasting and say the post did
not happen.

## Step 9: Final output

```
REA-12: <verdict>

Gates:  <pass/fail/not established per gate>
Device: <model, build, n observed, n not run>
Posted: <yes | no, Linear unavailable>

Next: <merge | fix and re-run /verify | run the untested check>
```

## Error handling

| Scenario | Action |
|---|---|
| No issue id and no branch match | Verify locally, report, skip the Linear post |
| Gate script absent | Report `GATES: NOT YET ESTABLISHED` for it; never a pass |
| A gate red | Print failing output, stop before install |
| `node_modules` / Gradle cache absent | `npm ci` first; a gate cannot pass or fail without it |
| `.claude/device.lock/` held | Stop the device half; report `NOT RUN: phone held by another lane` |
| `adb devices` not exactly one device | Stop the device half and say what it showed |
| `device:install` absent | Device half `NOT RUN`; verdict cannot be `VERIFIED ON DEVICE` |
| Item text seen in logcat | BLOCK under non-negotiable 1; report without quoting it |
| Linear post fails | Warn, print the comment, keep the verdict |
