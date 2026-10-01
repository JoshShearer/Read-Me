---
description: Run the gates, take an adversarial pass over the diff, commit, push, and open a PR with implementation notes and an on-device manual test plan.
---

Conventions: `.claude/linear.md`. Rules and gates: `AGENTS.md`. Architecture: `CONTEXT.md`.
Spec: `srs.md`.

**There are no gates and no CI yet.** The scaffold ticket establishes both and records them in
`AGENTS.md`'s quality-gates block. Until then this command reports `GATES: NOT YET ESTABLISHED`
in Step 4 and in the PR, and never invents a pass. Once CI exists, you **may** read a check
conclusion once and report it; you **must not** wait on it, and nothing runs the gates for you
at the moment you commit.

## Linear tool names

Operations used here: `get_issue`, `save_issue`, `save_comment`, `list_issue_statuses`.
**Do not hardcode a tool prefix.** Resolve the real names for the `linear-rea` server from your
available tool list. Resolving the prefix is not enough: Linear folded its create/update pairs
into `save_*`, so posting a comment is `save_comment` and there is no `create_comment` (see
`.claude/linear.md`). Never use `linear-nrl` here; it is the plugin's workspace. Linear being
unreachable never blocks a commit: do the git work and print what you would have sent.

## Hard refusals

These are not advisory. Each is a promise the product makes, and `AGENTS.md` calls breaking one
a BLOCK.

1. **Never claim a change works on the strength of a unit suite.** Unit tests run against fakes
   and a local test server. If the diff changes user-visible behaviour and it has not been
   exercised on the phone, the PR says `NOT VERIFIED ON DEVICE` and the report says it out loud.
2. **Never ship past a non-negotiable violation** found in Step 5. There is no override flag.
3. **Never weaken a test to make a gate pass.** If a gate fails, the code is wrong until proven
   otherwise. Tests that pin the rate, the bridge's hostile-input responses, `Fetcher`'s limits
   or the network guard exist because the failure is silent.
4. **Never assert a measurement you did not take** in the PR body. No RTF, gap, latency or size
   unless you measured it this session or you cite where it was measured.
5. **Never report a gate that does not exist as passing.** `GATES: NOT YET ESTABLISHED` is a
   true statement; a green tick is not.

## Step 1: Branch and base

```bash
git branch --show-current
git status --short
git config branch."$(git branch --show-current)".base-branch 2>/dev/null || echo "base=main"
```

**STOP if on `main`.** Ask for a `feature/rea-N-*`, `fix/rea-N-*` or `spike/rea-N-*` branch, or
offer to move the work onto one.

## Step 2: Identify the issue

Parse `rea-(\d+)` out of the branch name. Found → `get_issue`, then confirm:
`Detected REA-12: {title}. Link this PR?`

Read the requirement or spike ID out of the issue (`R-M03`, `R-S02`, `SPIKE-04`) and pull its
text from `srs.md`. The PR body has to show that the change actually satisfies it, not merely
that it builds.

**Spike branches ship differently.** A `spike/rea-*` branch's probe code is throwaway. What
ships is the recorded answer in `srs.md` "Spikes" (date, device, build, what was observed, what
was not established), plus an ADR in `docs/adr/` when the answer changes a decision. Stage the
doc change, not the probe code, unless the owner has said to keep the probe. If the probe code
is kept, say why in the PR. If no answer has been recorded, there is nothing to ship: stop.

## Step 3: Read the diff

```bash
git diff --stat "$(git merge-base origin/main HEAD)"
git diff "$(git merge-base origin/main HEAD)"
```

Diffing from the merge base covers committed, staged and unstaged work in one range, so
re-running `/ship` after a mid-implementation commit still reviews everything. Summarise what
changed and confirm with the user before going further.

Note which of these the diff touches, because it decides Step 4:

| Touched | Consequence |
|---|---|
| Any Gradle file, `AndroidManifest.xml`, `package.json` dependencies | Release build mandatory, plus the dependency-tree audit in Step 5 |
| Kotlin (`Fetcher`, `BridgeServer`, `PlaybackService`, `Store`, `ShareReceiver`) | Kotlin unit tests plus the release build, then the device |
| TS (`intake`, `extract`, `segment`, `library`, `reader`) | TS tests, typecheck, lint, then the device if user-visible |
| docs, `srs.md`, `docs/adr/`, `.claude/` only | Gates still run if they exist; the device requirement does not apply |

## Step 4: Gates

`AGENTS.md`'s quality-gates block names the intended set. **Check each one exists before running
it**, rule 18:

```bash
node -e "console.log(Object.keys(require('./package.json').scripts||{}).join(' '))" 2>/dev/null || echo "no package.json"
ls android/gradlew 2>&1
```

For every gate that exists, run it:

```bash
npm test
npm run typecheck
npm run lint
(cd android && ./gradlew testDebugUnitTest)
(cd android && ./gradlew assembleRelease)
```

A gate that does not exist is reported as missing, by name. If none exist, the result for this
step is `GATES: NOT YET ESTABLISHED`, and that string goes into the PR verbatim. Do not repeat
the gate list or counts in your report beyond what you ran; `AGENTS.md` is the one place they
live.

A fresh worktree has no `node_modules` and no Gradle cache. Run `npm ci` first or a gate result
means nothing.

Any gate red → stop and fix. Do not commit around it.

### Step 4b: Exercise it on the phone

`AGENTS.md` verification rule 15. Skip only for a docs-only diff, or before the scaffold when
there is no app to install (then say so).

Take the one-phone slot, and check there is exactly one device:

```bash
LOCK="$HOME/Documents/Dev/Read-Me/.claude/device.lock"
mkdir "$LOCK" 2>/dev/null || { echo "device slot held: $(cat "$LOCK/owner" 2>/dev/null)"; exit 1; }
echo "$(git branch --show-current) $(date -Is)" > "$LOCK/owner"
adb devices
```

The lock lives in the primary repo, never in a worktree, so every lane sees the same one.
If the slot is held, stop and say by whom. If `adb devices` does not show exactly one device,
release the lock and stop: name what it showed.

```bash
npm run device:install
```

Installing replaces whatever build another lane had on the phone. Say in your report that you
did. Then drive the change as a user would, over adb:

```bash
adb shell am start -n <applicationId>/.MainActivity
adb shell input tap <x> <y>          # coordinates from the dump below, not guessed
adb shell uiautomator dump /sdcard/ui.xml && adb pull /sdcard/ui.xml
adb logcat -d -s <AppLogTag>:V       # never paste item text from it; there should be none
```

What to do, by area:

| Changed | Do this on the phone |
|---|---|
| `ShareReceiver` / `intake` | Share a real article from a browser and a plain-text snippet from another app. Confirm both land in the list in the right state. |
| `Fetcher` / `extract` | Share a long article, a paywalled link and a dead link. Confirm `ready`, `extract-poor` and `fetch-failed` with the right reason class, and that Retry works. |
| Trim | Cut paragraphs, reopen the item, restore one. Confirm nothing was lost. |
| `PlaybackService` | Play at 2.0x, lock the screen, wait, unlock. Confirm audio continued, the notification controls work, and the position resumed at the right sentence. Confirm the speed is 2x and not 4x. |
| `Store` / `library` | Kill the app (`adb shell am force-stop`), reopen, confirm items and positions survived. |
| `BridgeServer` | From Obsidian on the same phone, read a note through the plugin while Read Me is backgrounded. Drive the plugin over CDP via `adb forward tcp:<port> localabstract:webview_devtools_remote_<pid>`, as the plugin's `AGENTS.md` records. Then run the hostile-input set against the running service and confirm it survives. |

After every run, scan the filtered logcat for item text, URLs and the token. Any hit is a Step 5
BLOCK even if the feature worked.

Release the slot when done, whatever happened:

```bash
rm -rf "$HOME/Documents/Dev/Read-Me/.claude/device.lock"
```

Record exactly what you did and what happened, with the device and the build. It goes in the PR
under Testing verbatim. `VERIFIED ON DEVICE` is the claim only when you observed it.

## Step 5: Non-negotiable audit (BLOCK gate)

Run `/check-constraints` if it exists in `.claude/commands/`; it is the full mechanical pass.
Otherwise read the Step 3 diff against each row. Any hit is a BLOCK: stop, show the line, do not
offer an override.

```bash
BASE="$(git merge-base origin/main HEAD)"
git diff "$BASE" | grep -nE '^\+.*(Log\.(v|d|i|w|e)\(|console\.(log|warn|error)|printStackTrace|Exception\(|new Error\()'
git diff "$BASE" -- '*.ts' '*.tsx' '*.js' | grep -nE '^\+.*(fetch\(|XMLHttpRequest|WebSocket)'
git diff "$BASE" -- '*.kt' | grep -nE '^\+.*(Socket\(|ServerSocket\(|openConnection|OkHttpClient|URL\()'
git diff "$BASE" | grep -nE '^[-+].*(getLoopbackAddress|127\.0\.0\.1|setSoTimeout|Throwable|Access-Control)'
git diff "$BASE" | grep -nE '^[-+].*(setSpeechRate|PlaybackParams|setPlaybackSpeed|playbackRate|X-Rate)'
git diff "$BASE" | grep -niE '^\+.*(token|Authorization|Bearer)'
git diff "$BASE" | grep -nE '^[-+].*(allowBackup|foregroundServiceType|TTS_SERVICE|uses-permission)'
```

| Rule | BLOCK when |
|---|---|
| 1, privacy | A log line or exception message carries item text, a title, or a URL path or query. Counts, ids, states, durations and a host name are fine. |
| 2, privacy | Anything resembling telemetry, crash reporting, analytics, remote config or update checks. |
| 3, privacy | `allowBackup` not false, an external-storage write, or item delete leaving text behind. |
| 4, privacy | The bridge token in a log or a URL. |
| 5, network | A cloud TTS endpoint, or a network voice selectable or used as a fallback. |
| 6, network | A JS network reference, a Kotlin connection outside `Fetcher` (`ServerSocket` in `BridgeServer` excepted), or a fetch not triggered by a share or Retry. |
| 7, network | `Fetcher` lost `NO_COOKIES`, gained a cache, follows redirects automatically or past 5, lost the 20 s timeout, or checks the 5 MB cap other than while streaming. |
| 8, bridge | Not bound to explicit `127.0.0.1`; body read before the token check; caps or per-socket timeout missing; catch narrower than `Throwable`; text from the URL; an error response without CORS headers. |
| 9, rate | A second speed stage anywhere, or anything telling the plugin to send a rate other than 1.0. |
| 10, positions | A persisted sentence or utterance index. |
| 11, queue | JS enqueuing, advancing or saving position on a playback event. |
| 12, trim | Trim deleting or overwriting paragraph text. |
| 13, instances | One `TextToSpeech` shared by playback and the bridge without an ADR for SPIKE-06. |
| 14, distribution | A Play Services, Firebase, Crashlytics or proprietary dependency in the resolved tree, a non-OSI license, a binary without source, or a manifest that lost the `TTS_SERVICE` query or a service type. |

The dependency-tree audit, after any Gradle, manifest or `package.json` dependency change:

```bash
(cd android && ./gradlew :app:dependencies --configuration releaseRuntimeClasspath) \
  | grep -niE 'com\.google\.android\.gms|firebase|crashlytics|play-services|com\.google\.android\.play'
```

If `android/gradlew` does not exist, say the audit could not run. A grep returning nothing is not
proof of cleanliness when the diff clearly touches source. Treat that as the check being wrong
and widen the range before you believe it.

## Step 6: Adversarial pass

Run `/critique` and read the verdict from `.claude/last-critique.md`. Reuse a recorded verdict
only when its `**Commit:**` matches `git rev-parse HEAD` **and** its `**Mode:**` is
`Code Review`. Otherwise it is stale: discard it and run `/critique` again.

Read `**Depth:**`, `**Score:**` and `**Verdict:**`. A `BLOCK` stops here. `CONCERNS` findings go
in the PR under Review Notes, each with why it is acceptable to ship. A finding that is actually
a Step 5 violation goes back to Step 5 and blocks.

If `/critique` is unavailable, do the pass inline and keep it short. Five questions, answered
against the diff, not from memory:

1. What input makes this wrong? Name one concretely: a share, a page, an HTTP request.
2. Which `CONTEXT.md` layering rule or unanswered spike does this change assume away?
3. Does this satisfy the requirement in `srs.md` or only look like it does? If it deviates,
   `AGENTS.md` requires an ADR in `docs/adr/` and an amendment to `srs.md`.
4. Does it change anything recorded under Known state in `AGENTS.md`? If yes, that entry needs
   updating, and `/finish` will ask about it.
5. What did you not test, and what would break first if you are wrong?

## Step 7: Commit

Already committed while implementing → verify the messages and skip to Step 8. Do not create an
empty commit, and do not amend anything already pushed.

```bash
git log origin/main..HEAD --oneline
```

```bash
git add -A
git commit -m "<type>(<scope>): <description>

<why, and what the reader cannot reconstruct from the diff>

Resolves REA-XX"
```

Types: `feat`, `fix`, `docs`, `refactor`, `test`, `chore`, `spike`.
Scopes mirror `CONTEXT.md`: `intake`, `extract`, `segment`, `library`, `reader`, `trim`,
`share`, `store`, `fetcher`, `playback`, `bridge`, `build`, `spec`.
Description: imperative, lowercase, no trailing period. No em-dashes anywhere in the message.

> `Resolves REA-XX` is for a human reading `git log`. Do not assume it closes the Linear issue;
> `/finish` closes the issue.

Never stage build output, device dumps or secrets. Check before committing:

```bash
git status --short | grep -E '(node_modules|android/(app/)?build/|\.gradle/|\.apk$|\.aab$|ui\.xml$|\.keystore$|\.jks$|device\.lock|last-critique\.md)' || echo "nothing unwanted staged or untracked"
```

If anything shows up as tracked or staged, stop and check `.gitignore` rather than committing it.
On a spike branch, also confirm the probe code is not staged unless the owner kept it.

## Step 8: Push

```bash
git push -u origin "$(git branch --show-current)"
```

## Step 9: Open the PR

Verify `gh` works rather than assuming:

```bash
gh auth status
```

```bash
gh pr create --base <base-branch> --title "<type>(<scope>): <description>" --body "<body>"
```

If you edit the body afterwards, do not trust `gh pr edit`: here it has left a PR body unchanged
with no error. Use `gh api repos/{owner}/{repo}/pulls/<n> -X PATCH -f body=...` and read the body
back with `gh pr view <n> --json body` to confirm it landed.

Body template. Every section is filled or explicitly marked `none`:

```markdown
## Summary
- <what changed and why, in the user's terms>

## Requirement
`R-M03` - <the srs.md text, quoted>
<how this change satisfies it, or what part of it remains open>
<for a spike: the question, the recorded answer, and the ADR if any>

## Implementation Notes

### Files Changed
- `<path>` - <purpose>

### Approach
<strategy, and the alternative you rejected>

### Key Decisions
- <decision + why>

### Dependencies
<added, or none. If added: license, and the dependency-tree audit result>

## Testing

### Gates
- <each gate run and its result line, verbatim>
<or:>
- GATES: NOT YET ESTABLISHED

### Exercised on device
- <device>, build <versionName / commit>, installed with `npm run device:install`, then: <exactly
  what you did over adb and what happened>. VERIFIED ON DEVICE.
<or, if not done:>
- NOT VERIFIED ON DEVICE. Unit tests run against fakes and are not evidence this works.

### Not tested
- <what you did not cover>

## Manual Test Plan
- [ ] `npm run device:install` on the reference device
- [ ] <named user action, e.g. "share a long article from the browser, confirm it lands as
      `ready`, open it, trim two paragraphs">
- [ ] <named user action, e.g. "play at 2.0x, lock the screen for 60 s, unlock, confirm the
      position advanced and speed is 2x">
- [ ] <bridge, if touched: "in Obsidian, read a note with the plugin's native engine while
      Read Me is backgrounded">

## Review Notes {omit if the adversarial pass was clean}
- <concern + why it is acceptable to ship>

## Non-negotiables
- [ ] No item text, URL path or token in any log
- [ ] No network outside `Fetcher`; `Fetcher` limits intact
- [ ] Bridge: explicit 127.0.0.1, token before body, caps, timeouts, Throwable, CORS on every response
- [ ] Rate applied exactly once; plugin calls rate=1.0
- [ ] Positions are character offsets; native service owns the queue; trim non-destructive
- [ ] Separate TextToSpeech instances
- [ ] F-Droid-clean dependency tree and manifest

Resolves REA-XX
```

The Manual Test Plan must name real user actions on real screens. `Test the app` is not a test
plan.

## Step 10: Update Linear

`In Review` optionality, per `.claude/linear.md`. Statuses are unverified there until discovery
runs, so check rather than assume.

1. `list_issue_statuses` for the team, and look for `In Review`.
2. Exists → `save_issue` with `state: "In Review"`.
3. Does not exist → leave the issue **In Progress**. The open PR is the review signal. Do not
   invent a status, and do not move it to Done: it is not done.
4. Either way, `save_comment` with the PR link and the implementation summary.
5. If a Linear call fails to resolve, check the operation name against your tool list before
   concluding the server is down, and say in the report that nothing was posted.

Plugin-side bridge work is NRL-130 in the plugin's own workspace. Link it by URL in the comment
if relevant; never post to it from here.

## Step 11: Report

```
Shipped.

Commit: abc1234 fix(fetcher): enforce the 5 MB cap while streaming
PR: <url from gh>

Gates: <each gate's result line> {or: GATES: NOT YET ESTABLISHED}

Device: VERIFIED ON DEVICE on <device>, build <x>. <what you did, what happened>
        This lane installed over whatever build was on the phone.
{or}
Device: NOT VERIFIED ON DEVICE. This change is unproven.

Non-negotiable audit: clean
Adversarial pass: <verdict, score, concerns listed in the PR>

Linear REA-12: In Review {or: In Progress, no In Review status, PR is the signal}
               {or: nothing posted, Linear unavailable}

Next:
1. Review the PR
2. Merge
3. `/finish` to clean up and close the issue
```

## Error handling

| Scenario | Action |
|---|---|
| On `main` | Stop. Ask for a feature, fix or spike branch. |
| A gate fails | Stop. Fix the code. Never soften the test. |
| A gate does not exist | Report it missing by name. Never report it as passing. |
| Release build fails | Stop. There is nothing to install. |
| Device slot held | Stop. Name the holder from `.claude/device.lock/owner`. Never remove another lane's lock. |
| `adb devices` shows zero or several devices | Release the lock, stop, name what it showed. Mark the change NOT VERIFIED ON DEVICE if the user chooses to ship anyway. |
| `npm run device:install` fails | Release the lock, report it, mark NOT VERIFIED ON DEVICE. |
| Obsidian or the plugin not available for a bridge check | Report the bridge half "did not run". Never report it as passing. |
| Non-negotiable violation | Stop. No override exists. |
| Push rejected | Show the remote state. Surface the force-push decision to the user; never force-push unasked. |
| `gh` unauthenticated or `gh pr create` fails | Show the error, print the PR body so it can be pasted, and report the branch as pushed but unreviewed. |
| Linear unreachable | Warn and continue. Print the status change and the comment as text, and say it was not posted. |
