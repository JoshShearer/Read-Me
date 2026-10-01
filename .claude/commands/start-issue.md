---
description: Start or resume work on a Linear issue - fetch it, create the branch, set In Progress, and surface the AGENTS.md rules that apply to the area it touches.
---

Conventions: `.claude/linear.md`. Rules and gates: `AGENTS.md`. Architecture: `CONTEXT.md`.
Spec: `srs.md`.

## Linear tool names

Operations used here: `get_issue`, `list_issues`, `save_issue`. **Do not hardcode a tool
prefix.** Resolve the real names for the `linear-rea` server from your available tool list
(never `linear-nrl`, which is the plugin's workspace). If no Linear tool is present, create the
branch anyway and print the status change you would have made, per the degradation rule in
`.claude/linear.md`.

## Input

`$ARGUMENTS` is the issue identifier. Accepts `REA-12`, `rea-12`, or bare `12`. The key `REA`
is owner-stated and unverified; if `get_issue` rejects it, run `list_issues`, read the key off
an identifier, and say `.claude/linear.md` needs correcting.

## Step 1: Resolve the issue

**Argument given**: parse the number.

**No argument**: check the current branch:

```bash
git branch --show-current
```

Matches `feature/rea-{N}-*`, `fix/rea-{N}-*` or `spike/rea-{N}-*`: resume that issue.

Otherwise `list_issues` with `assignee: "me"` across Todo / In Progress / Backlog and present a
numbered picker, with the requirement or spike ID next to each:

```
No issue ID given. Your assigned issues:

In Progress:
  1. REA-12: Share-sheet intake (R-M02)

Todo:
  2. REA-3: SPIKE-02: Readability on Hermes
  3. REA-7: Fetcher redirect and size limits (R-M03)

Enter a number, or an issue ID:
```

## Step 2: Fetch the issue

`get_issue`: title, description, labels, state, assignee, parent, and `url`. Use the returned
`url` verbatim; do not build one.

Pull the ID out of the title or description (`R-M01`-`R-M14`, `R-S01`-`R-S04`,
`R-C01`-`R-C04`, `SPIKE-01`-`SPIKE-06`). If there is one, read its text out of `srs.md` and
show it. The ticket is a pointer; `srs.md` is the acceptance criteria.

```bash
grep -n -A 20 "### R-M03" srs.md      # a spike: grep -n "SPIKE-02" srs.md
```

No ID and the issue looks like a spec gap: say so, and suggest adding one before implementing.
`/spec-check` reads it.

## Step 3: Pre-flight git checks

```bash
git status --short
git branch --show-current
git ls-remote --heads origin "feature/rea-{N}-*" "fix/rea-{N}-*" "spike/rea-{N}-*"
```

| Finding | Action |
|---|---|
| Uncommitted changes | Stop. Offer stash or commit. Do not create a branch over a dirty tree. |
| Not on `main` and not resuming this issue | Ask whether to switch to `main` first. Default base is `main`. |
| Branch already exists locally | Offer: check out the existing branch (resume), or pick a new slug. Never delete it. |
| Branch exists on the remote only | Offer `git checkout -b <branch> origin/<branch>` to resume someone else's push. |
| Issue is In Progress and assigned to someone else | Warn, offer to proceed or pick another. Assigned to you: resume silently. |

## Step 4: Branch name

| Issue | Pattern |
|---|---|
| Bug, or label `Bug` | `fix/rea-{N}-{slug}` |
| Title starts `SPIKE-0N:` | `spike/rea-{N}-{slug}` |
| Anything else | `feature/rea-{N}-{slug}` |

Slug: lowercase title, non-alphanumerics to `-`, collapse runs, truncate near 50 chars.

```bash
git checkout main && git pull --ff-only origin main && git checkout -b <branch-name>
```

Sub-issue whose parent has a branch on the remote: offer the parent branch as the base and
record it:

```bash
git config branch.<branch-name>.base-branch <base-branch>
```

`/ship` reads that config for the PR target and `/finish` reads it to decide where to return.

**Spike branches are throwaway.** Say so in the output: the code on a `spike/` branch is not
merged as product code. What merges is the recorded answer in `srs.md` "Spikes" (with the
date, device, build and the measurement), plus an ADR in `docs/adr/` when the answer changes a
decision (SPIKE-06, for one, can replace non-negotiable 13).

## Step 5: Worktree question (primary sessions only)

If other issues are already In Progress, mention that `Read-Me-rea-{N}` siblings are the
pattern for parallel work, and state the constraint plainly: **only one worktree at a time can
hold the phone**. `npm run device:install` replaces the app on the one attached device, so take
`.claude/device.lock/` in the primary with `mkdir` first and say so. A fresh worktree has no
`node_modules` or Gradle cache, so `npm ci` comes before any gate.

Do not create a worktree from this command. Report that the option exists.

## Step 6: Set the status

`save_issue` with `state: "In Progress"`, only when the current state is `Backlog` or `Todo`.
Set `assignee: "me"` if unassigned. Never move an issue backwards.

## Step 7: Area hazards

Decide the area from the title, description, ID, and modules it names. Print **only** the
matching rows. `AGENTS.md` is authoritative. Paths do not exist until the scaffold lands, so
areas are module names from `CONTEXT.md`.

| Area | What you must know before writing code |
|---|---|
| Anything that logs (`Log.`, `console.`, exception messages) | **Non-negotiable 1.** Counts, ids, states, durations, at most a host name. Never a sentence, paragraph, title, or URL path or query, not even inside an exception message that reaches a log. **2**: no analytics, crash reporting, beacons, remote config or update checks. |
| `intake`, `ShareReceiver` | The fetch runs in native code that survives the share activity finishing (R-M02/R-M03). Stale `fetching` items become `fetch-failed` at start-up. A failed fetch is visible, never silently deleted (R-M10). |
| `Fetcher` | **Non-negotiable 6**: the only network call site in either language, running only on a share or an explicit Retry; a test enforces both halves (`grep -rnE '\b(fetch|XMLHttpRequest|WebSocket)\b' src/`, and no `HttpURLConnection`/`OkHttpClient`/`Socket(` in Kotlin outside `Fetcher`). **7**: `CookieJar.NO_COOKIES`, no cache, at most 5 manually followed http(s) redirects, 20 s call timeout, 5 MB cap enforced while streaming (a chunked body with no `Content-Length` must still abort). |
| `extract` | Pure JS on Hermes, input from `Fetcher` via the module. Whether Readability runs there is SPIKE-02. Tests use saved real pages as fixtures, never a live fetch. |
| `segment` | **Non-negotiable 10.** Sentences are derived, never stored, never addressed by index; positions are (paragraph, character offset). `Intl.Segmenter` on Hermes is SPIKE-03. |
| `library`, `Store`, trim | Kotlin owns the database (CONTEXT.md; `srs.md` amendment pending). **12**: trimming is non-destructive, cuts are a separate set. **3**: `allowBackup="false"`, no external storage, delete removes all stored text. **10** again for positions. |
| `PlaybackService`, `reader` | **Non-negotiable 11**: the service owns the queue; JS is a view, so screen-off playback never depends on the JS runtime. **9**: the engine applies rate at synthesis; the player never scales speed again. **13**: its own `TextToSpeech`. **5**: no network-requiring voice selectable or used as a fallback. Gap target is SPIKE-05's to baseline; do not quote one you did not measure (rule 17). |
| `BridgeServer` | **Non-negotiable 8**: bind `127.0.0.1` explicitly, never `getLoopbackAddress()` (returned `::1` on Android 17); token checked before the body is read; header and body caps; per-socket read timeout; catch `Throwable` per connection; text in the POST body. **4**: token never logged or in a URL. **9**: the plugin calls `/synthesize` with `rate=1.0`. CORS headers on every response, errors included (R-M12). **13**: separate `TextToSpeech`. Plugin side is NRL-130 in the other workspace. |
| `AndroidManifest.xml`, Gradle, `package.json` | **Non-negotiable 14**: F-Droid-clean; after any dependency change inspect the resolved tree (`./gradlew :app:dependencies --configuration releaseRuntimeClasspath`) for `com.google.android.gms`, `firebase`, `crashlytics`, proprietary SDKs or blobs. Permissions: only `INTERNET` plus what the foreground service and media session need. The `TTS_SERVICE` `<queries>` entry must stay (proven causal). Foreground-service type per SPIKE-01. |

## Step 8: Reproduce before fixing (bug issues only)

`AGENTS.md` rule 16. Reasoning about the code is not reproduction. Before writing a fix, make
the failure happen:

- Logic in a TS module or a Kotlin class: add a temporary case to the relevant suite and watch
  it fail for the reason the ticket claims. Check the gate script exists first; before the
  scaffold there is none, and the reproduction has to wait for it or happen on the device.
- Device behaviour: take `.claude/device.lock/` (`mkdir`), confirm `adb devices` shows exactly
  one device, `npm run device:install`, then do what a user does (`adb shell am start`,
  `adb shell input`, `adb shell uiautomator dump`) and watch `adb logcat` filtered by the app's
  tag. Never copy item text out of a dump or a log into the ticket. Remove the lock when done
  and say you held it.

## Step 9: Output

```
{Started | Resuming} REA-12: Share-sheet intake

| Property | Value |
|----------|-------|
| Branch | feature/rea-12-share-sheet-intake {(checked out) if resumed} |
| Base | main |
| Status | In Progress {(unchanged) if resumed} |
| Requirement | R-M02 |
| Issue | {url returned by the MCP} |

## Requirement text (srs.md)
> {the lines grep found}

## Description
{issue body}

## Remaining tasks {omit if none in the body}
- [ ] ...

## Hazards for this area
- ...

## Gates before you commit
{GATES: NOT YET ESTABLISHED, until the scaffold replaces AGENTS.md "Quality gates".
 After that, the commands named there, each checked to exist before running.}

A green suite is not a claim that this works. Install on the phone and drive it as a user
before saying it does (AGENTS.md rule 15).

{spike branch:}
This is a spike. The code here is throwaway; the deliverable is the answer in srs.md "Spikes".

{footer if other issues are In Progress:}
---
Also In Progress: REA-14. Parallel work goes in ../Read-Me-rea-14.
Only one worktree at a time can hold the phone (.claude/device.lock/).
```

## Error handling

| Scenario | Action |
|---|---|
| Issue not found | Show the error. Run `list_issues` in case the team key differs, and report the real one. |
| Branch exists locally | Offer checkout-existing or a new slug. Never delete. |
| Uncommitted changes | Stop. Stash or commit first. |
| Not on `main` | Ask before switching. Report what you would base on. |
| Linear tools absent or unauthenticated | Create the branch, print the status change as a table for manual entry, point at the first-run setup in `.claude/linear.md`. |
| `git pull --ff-only origin main` rejected | Stop. `main` has diverged locally; that needs a decision. |
| `node_modules` missing (fresh worktree) | `npm ci` first, if `package.json` exists. Report that gates were untrustworthy until it finished. |
| Device slot held, or not exactly one device | Do not install. Report who holds it or which serials are attached. |
