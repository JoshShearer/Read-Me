---
description: Write a session handoff that carries the objective, the decisions and their rationale, real measurements taken, and an explicit list of claims that are still unverified.
---

Compact this session into a handoff a fresh agent can act on without re-deriving anything.

Conventions: `.claude/linear.md`.

Argument: `$ARGUMENTS` (optional) - a topic slug or a Linear id to name the file. Omitted:
infer from the branch or the work.

## What makes a handoff fail here

Two failure modes this repo is unusually exposed to:

1. **A claim arrives without its evidence.** The next session reads "playback survives screen
   off", trusts it, and builds on it. `AGENTS.md` rule 15 says a green suite is not a claim
   that something works, and R-M14 says a requirement is met only when observed on a real
   device with date, device and build recorded. If you did not do it on the phone, the handoff
   says so in those words.
2. **A number arrives without its measurement.** Rule 17 forbids asserting a measurement you
   did not take. RTF, inter-utterance gaps, APK size and latency measured this session are among
   the most valuable things in the handoff, because otherwise the next session cannot quote
   them and will re-measure. Carry each with its method.

Detail over brevity. Nothing that took a command to discover should need that command run
again.

## Capture

### 1. Objective

What was actually asked for at the start, not the scope that accreted. One paragraph. Name
the Linear issue and its current status. Confirm the status with `get_issue` rather than
recalling it; resolve the real prefixed tool name for `linear-rea` from your tool list, and
skip this if no Linear tool is connected.

### 2. Decisions and rationale

For each decision: what was chosen, the alternatives, and why. The reason is the part that
does not survive unwritten; the choice is visible in the diff.

Call out any decision that touches an `AGENTS.md` non-negotiable (item text in logs,
telemetry, `Fetcher` as sole network site and its limits, bridge bind/token/caps, rate applied
once, character-offset positions, service-owned queue, non-destructive trim, separate
`TextToSpeech` instances, F-Droid cleanliness), and any deliberate divergence from `srs.md`,
which needs an ADR in `docs/adr/` plus an amendment to `srs.md`.

For a spike: the answer, and whether it has been written into `srs.md` "Spikes" yet. Until it
is, the spike is not done, whatever the branch holds.

### 3. Done

Files changed and what each change does (module and symbol until paths settle). Commands run,
with their real output, not a paraphrase. Which requirement or spike IDs moved, if any, and on
what observation.

Committed or uncommitted? Run and record:

```bash
git status --short
git log --oneline -5
git branch --show-current
```

### 4. In flight

Work started and not finished. Where exactly it stopped and what the next edit was going to
be. If a half-applied change leaves the tree inconsistent, say so at the top of this section.

### 5. Blocked, and why

What cannot proceed, and what would unblock it. Distinguish:

- blocked on a decision from the owner
- blocked on a machine or device capability (no phone attached, device slot held, SDK or
  Gradle component not installed, Obsidian not on the phone)
- blocked on another issue, including plugin-side NRL-130 in the other workspace

### 6. Measurements taken this session

**The section this repo needs most.** Every real number produced, with how, so the next
session may quote it and cite where it was measured.

| Capture | How it was obtained |
|---|---|
| Gates | `GATES: NOT YET ESTABLISHED` if pre-scaffold; otherwise the exact output of each gate named in `AGENTS.md`, verbatim summary lines |
| Release build | real APK size (`ls -l` on the built APK) and the resolved-dependency check for F-Droid cleanliness, verbatim |
| Manifest | permissions and `<queries>` from the merged manifest (`aapt2 dump xmltree` or the merged-manifest file), verbatim |
| Synthesis | RTF = compute seconds / audio seconds, with input length, rate, engine and whether offline (airplane mode, ping unreachable) |
| Inter-utterance gap | `onDone(n)` to `onStart(n+1)` per R-M07, p95 and max, sample count, duration of the run |
| Bridge | exact `curl` status codes for the hostile-input set, and whether one process survived (pid before and after) |
| Device and build | device, Android version, TTS engine package, app `versionName`/`versionCode`, commit |

Label anything estimated rather than measured ESTIMATE. If nothing was measured, write "no
measurements taken this session" rather than leaving the section out. Numbers already recorded
in the plugin repo's `AGENTS.md` ("Android playback throughput, and the native-TTS bridge") are
cited from there, not presented as this session's.

### 7. Unverified claims

An explicit list. For each: the claim, and what would settle it.

Default to listing a thing here. Anything not exercised on the phone belongs here, including
changes whose unit tests are green. Also record:

- Whether a build was installed this session, from which worktree, and whether
  `.claude/device.lock/` was taken **and released**. A lock left behind blocks every other lane.
- Which build the phone is running now, since the next lane's manual test depends on it.
- Whether the bridge was exercised from Obsidian on the phone (over CDP via
  `adb forward ... localabstract:webview_devtools_remote_<pid>`) or only by `curl` over
  `adb forward`. They are different evidence.
- Whether a run was genuinely offline, and how that was checked.
- Whether logcat was checked for item text during the run (non-negotiable 1).

### 8. Next concrete action

One action, specific enough to start on without re-reading the session. "Add the 6th-redirect
case to the `Fetcher` test against the local test server and make it fail before changing the
loop" rather than "harden the fetcher".

Then the two or three actions after it, in order.

### 9. Files that matter

Only the ones a fresh session must open, each with one line on why. Include untouched files
that constrain the work (`srs.md` sections, an ADR). Do not paste the repo layout; `CONTEXT.md`
has it.

### 10. Dead ends

What was tried and did not work, and why, so it is not retried. Include approaches considered
and rejected, with the reason.

## Where it goes

Write to `.claude/scratch/YYYY-MM-DD-<slug>-handoff.md`, using `rea-{N}` as the slug when the
work maps to a Linear issue. `.claude/scratch/` is already in `.gitignore`; confirm with
`grep -n '.claude/scratch/' .gitignore` before writing, and add it if it has gone missing.

Handoffs are working notes. Durable knowledge belongs in `AGENTS.md` ("Known state"),
`CONTEXT.md` (structure), `srs.md` (the contract, including spike answers), or `docs/adr/`
(a decision), not in a scratch file.

## Template

```markdown
# Handoff: <topic> - YYYY-MM-DD

Issue: REA-{N} (<status>) | Branch: <branch> | Worktree: <path>

## Objective

## Decisions
- **<decision>** - chose X over Y because Z. Touches AGENTS.md non-negotiable <n>: <how it is respected>.

## Done
- `<module>/<symbol>` - <what and why>
- Committed: <yes/no>. `git status --short`: <output>

## In flight
- Stopped at <where>. Next edit was going to be <...>.

## Blocked
- <what> - blocked on <decision | device/machine | issue>. Unblocks when <...>.

## Measurements taken
| What | Value | How |
|---|---|---|
| Gates | GATES: NOT YET ESTABLISHED | pre-scaffold |
| (none) | | say so rather than omitting |

## Unverified
- <claim> - would be settled by <...>
- Installed on phone: <yes, build <x> from <worktree> | no>. Device lock: <taken and released | never taken>.
- Bridge exercised from Obsidian on the phone: <yes | curl only | no>.

## Next action
1. <one concrete action>
2. <...>

## Files that matter
- `path` - why

## Dead ends
- <approach> - failed because <...>
```

## Optional: leave a trail in Linear

If there is an issue and a `linear-rea` tool is connected, offer to post the Objective, Done,
Blocked and Next action sections as a comment via `save_comment`. Resolve the real prefixed
tool name from your tool list; the operation is `save_comment`, not `create_comment`, which
does not exist. Do not post the whole handoff, and never paste item text into it.

If no Linear tool is connected, say so and write the file anyway.

## When to use

- Context is running out mid-task
- Ending a session with work incomplete
- Handing to a parallel worktree, which must state whether it holds the device slot
- Ending a spike whose answer is not yet in `srs.md`
