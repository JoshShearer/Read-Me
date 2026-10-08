---
description: Create a Linear issue for Read Me from the current conversation, carrying the srs.md requirement or spike ID and, for bugs, a real reproduction.
---

Create a Linear issue from what we have been working on.

Conventions: `.claude/linear.md`.

Argument: `$ARGUMENTS` (optional) - one of `bug`, `feature`, `tech-debt`, `spec-gap`, `spike`,
or omitted to infer from the conversation.

## Linear tool naming

Do not hardcode an MCP tool prefix. The operations are `get_issue`, `list_issues`,
`save_issue`, `save_comment`, `list_teams`, `list_issue_statuses`, `list_issue_labels`,
`get_workspace`. Find the real prefixed names for the **`linear-rea`** server in your available
tool list and call those. Never file through `linear-nrl`: that server is authenticated to the
plugin's workspace and the ticket would land in the wrong team. If no `linear-rea` tool is
present, skip to Degradation and print the issue instead of creating it.

The team key `REA` is owner-stated and **unverified** in `.claude/linear.md`. If a lookup
rejects it, run `list_issues`, read the key off an identifier, and say so in your output so
`.claude/linear.md` can be corrected. The same applies to labels and statuses: they are
placeholders until discovery runs.

**Cross-repo work** - anything that changes the plugin's side of the bridge - belongs in the
plugin's workspace as or under NRL-130, filed from that repo. From here, file only the Read Me
half and link NRL-130 by URL in the body.

## Step 1: Type

| `$ARGUMENTS` | Meaning | Label (see `.claude/linear.md`) | Default priority |
|---|---|---|---|
| `bug` | Shipped behaviour is wrong | `Bug` | High (2) |
| `feature` | Capability that does not exist | `Feature` | Normal (3) |
| `tech-debt` | Works, but the structure is wrong | `Improvement` | Low (4) |
| `spec-gap` | `srs.md` demands it and the code does not do it | `Improvement` | Normal (3) |
| `spike` | One question to answer on the reference device | `Improvement`, title prefixed `SPIKE-0N:` | High (2) before the scaffold |
| omitted | Infer, then state which you chose and why | varies | varies |

Apply only a label that `list_issue_labels` actually returns, or none. Never create a label;
that is a change to a shared workspace and is the owner's call.

Before the scaffold lands almost everything is a `spec-gap` or a `spike`: there is no code, so
nothing can regress yet.

## Step 2: Requirement or spike ID

**Ask for or infer the `srs.md` ID.** IDs run `R-M01` through `R-M14`, `R-S01` through
`R-S05`, `R-C01` through `R-C04`, and `SPIKE-01` through `SPIKE-06`. Find the text with
`grep -n "### R-M07" srs.md` (spikes: `grep -n "SPIKE-05" srs.md`).

- Put the primary ID in the **title** as a trailing tag and in the `## Requirement` body
  section: `Share-sheet intake (R-M02)`. A spike instead leads with it:
  `SPIKE-02: Readability on Hermes`.
- If it maps to more than one, list all of them in the body, primary one in the title.
- If it maps to none, write `None` in `## Requirement` and say in one line why the work is
  worth doing anyway. Do not invent an ID or stretch one to cover unrelated work.
- If the work **contradicts** `srs.md`, that is allowed but not silently. Say so in the issue
  and note that closing it requires an ADR in `docs/adr/` plus an amendment to `srs.md`.
- A **spike's** deliverable is the recorded answer in `srs.md` "Spikes" (plus an ADR when it
  changes a decision), not the spike branch's code, which is throwaway. Say so in its
  acceptance criteria.

`/spec-check` reads these IDs. An issue without one is invisible to it.

## Step 3: Reproduction (bugs only, and it is mandatory)

`AGENTS.md` rule 16 requires a bug be reproduced end to end before it is fixed, on the device
where it was seen if it is a device behaviour. The issue carries the repro so the next session
does not rediscover it.

A reproduction here is one of:

- **A unit run against real input.** Once gates exist: the TS suite for `intake`, `extract`,
  `segment`, `library` or trim math, or `(cd android && ./gradlew testDebugUnitTest)` for
  `Fetcher`, `BridgeServer` and the queue. Check the script exists (`npm run`, `ls`) first.
  For extraction, use a saved real page as a fixture; never fetch in a test. Paste the actual
  output.
- **On the phone, as a user.** With `.claude/device.lock/` taken and `adb devices` showing
  exactly one device: install the build, drive it (`adb shell am start`, `adb shell input`,
  `adb shell uiautomator dump`), and capture `adb logcat` filtered by the app's tag. Name the
  device, Android version, build, and TTS engine. **Never paste item text** from logcat or a UI
  dump into the issue; the logs should not contain it (non-negotiable 1), and if they do, that
  is a second bug to file, described without quoting it.
- **The bridge.** `adb forward tcp:<port> tcp:8787`, then the exact `curl` with its exact
  status and headers (redact the token: non-negotiable 4). From Obsidian on the phone, the
  plugin side is driven over CDP via `adb forward ... localabstract:webview_devtools_remote_<pid>`;
  record which route was used.

If the conversation never actually ran the failing path, write this verbatim under
`## Reproduction`:

```
NOT REPRODUCED. Suspected from code reading only, at <module>/<symbol>. Reproduce before fixing.
```

Do not invent plausible steps. A fabricated repro is worse than an honest gap, because the
next session will trust it.

Check `AGENTS.md` "Known state" and `list_issues` before filing, and link an existing issue
instead of filing a duplicate.

## Step 4: Constraints that must travel with the ticket

Add a `## Constraints` section naming every `AGENTS.md` non-negotiable the work touches.
Breaking one is a BLOCK, and it is far cheaper to state now than to catch in review.

| Area touched | Constraint to name in the issue |
|---|---|
| Any logging, exception messages | No item text, title or URL path/query in any log (1); no telemetry (2) |
| `Fetcher` or anything network-shaped | Only `Fetcher` opens connections; no JS `fetch`/`XMLHttpRequest`/`WebSocket` (6); `NO_COOKIES`, no cache, 5 manual redirects, 20 s, 5 MB streaming cap (7) |
| `BridgeServer` | Token never logged or in a URL (4); `127.0.0.1` bound explicitly, token before body, caps, read timeout, catch `Throwable`, CORS on every response (8); separate `TextToSpeech` (13) |
| Rate, speed, `PlaybackService` | Rate applied exactly once: the engine applies it, the player never re-scales, the plugin calls `rate=1.0` (9); the service owns the queue (11) |
| Voices, engine selection | No network voice selectable or as fallback (5) |
| `segment`, `library`, `Store`, positions, trim | Positions are character offsets (10); trimming is non-destructive (12); `allowBackup="false"`, delete removes all text (3) |
| Gradle, `package.json`, `AndroidManifest.xml` | F-Droid-clean resolved dependency tree (14); permissions limited to `INTERNET` plus foreground-service/media needs; `TTS_SERVICE` query present |
| Any performance claim (RTF, gap, size, latency) | Rule 17: measured this session, or cited with where it was measured |

## Step 5: Body template

```markdown
## Context

What prompted this, in two or three sentences. Where it was noticed. Device, Android
version, build and TTS engine, if that matters.

## Reproduction

(Bugs only. Mandatory. Exact commands and exact observed output, or the literal
"NOT REPRODUCED" line from Step 3.)

## Requirement

R-Mxx - one-line restatement of what srs.md actually demands.
Or: SPIKE-0N - the question, verbatim from srs.md.
Or: None. <why this is worth doing anyway>

## Acceptance criteria

- [ ] Observable outcome, not an implementation step
- [ ] Which gate proves it, by the names in AGENTS.md "Quality gates" (none exist yet;
      say "gate to be established by the scaffold" if so)
- [ ] What must be observed on the phone after `npm run device:install`, if user-facing.
      A green unit suite is not proof (AGENTS.md rule 15). Name the device.
- [ ] (Spike) the answer recorded in srs.md "Spikes", plus an ADR if a decision changes

## Constraints

(From the Step 4 table. Omit only if genuinely nothing applies.)

## Out of scope

What this issue deliberately does not do. Name the IDs that stay open.
Plugin-side work: NRL-130 (link by URL).
```

## Step 6: Confirm

Print title, type, label, priority, requirement or spike ID, and whether a reproduction is
attached. Wait for a yes before calling `save_issue`.

If the type is `bug` with no reproduction and no explicit NOT REPRODUCED line, say so and ask
whether to reproduce it first. Do not file it quietly.

## Step 7: Create and report

Call `save_issue`. Use the `url` field from the response; do not construct a URL.

```
Issue created: REA-{id}
Title:         {title}
Type:          {bug|feature|tech-debt|spec-gap|spike}
Requirement:   {R-Mxx | SPIKE-0N | none}
Reproduced:    {yes | no, NOT REPRODUCED noted in body}

URL: {url from the response}

Branch: {feature|fix|spike}/rea-{id}-{slug}
Worktree, if you want one: ~/Documents/Dev/Read-Me-rea-{id}
```

Remind the user that a fresh worktree has no `node_modules` or Gradle cache, so `npm ci` runs
before any gate means anything, and that only one worktree at a time may hold the phone
(`.claude/device.lock/`).

## Degradation

If no `linear-rea` tool is available, print the full issue body exactly as it would have been
sent, plus a table of title, type, label, priority, requirement ID, assignee. Suggest the
branch name anyway. A missing ticket system is never a reason to block work.
