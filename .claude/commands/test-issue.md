---
description: Triage a PR's CI result before merge - read the check conclusions, measure whether a red check is caused by this branch or pre-existing, attempt one repair, and emit a merge-readiness verdict. Hands the on-device half to /verify.
argument-hint: "[REA-12 | 12 | empty to detect from the branch]"
---

Conventions: `.claude/linear.md`. Rules and gates: `AGENTS.md`.

**CI does not exist until the scaffold ticket lands.** Before then there is nothing for this
command to triage: Step 2 finds no workflow, the verdict is `CI NOT ESTABLISHED`, and the
command stops. It never substitutes a local run for a CI result.

## What this command owns, and what it does not

`/verify` runs the gates, installs the build on the phone, drives it as a user would and emits
the `GATES GREEN, NOT VERIFIED` / `VERIFIED ON DEVICE` / `FAILED` verdict. This command does
**not** restate any of that. It owns the one thing `/verify` has no answer for: the PR's GitHub
Actions result, and whether a red check is this branch's fault.

| Question | Command that owns it |
|---|---|
| Do the gates pass on this machine? | `/verify` |
| Did GitHub Actions go red, and is it this branch's fault? | **this command** |
| Does the change actually work on the phone? | `/verify` |
| Is this PR ready to merge? | **this command**, on the CI axis only |

So the verdict here is always about CI. **Never emit anything resembling "verified on device"
from this command** - it does not install anything and it does not listen to speech. A green CI
result is `READY ON CI`, and the output says in as many words that the device half is still
`/verify`'s job.

Two things this command deliberately does not have:

| Absent | Why |
|---|---|
| A known-failures baseline file | **Deliberately not created.** Triage is measured per failure instead (Step 4). A baseline file would duplicate `AGENTS.md`'s Known state and rot against it. |
| An `In Review` precondition | Statuses are unverified in `.claude/linear.md`, and `In Review` is optional there. The precondition is **an open PR**, not a status. Do not transition the issue; `/finish` sets Done. |

## Input

Argument: `$ARGUMENTS` - optional Linear identifier, accepting `REA-12`, `rea-12` or `12`.

| Flag | Effect |
|---|---|
| *(none)* | Read the current conclusions once and triage whatever is already decided |
| `--watch` | Wait for the checks to conclude first, capped (see Step 3). Allowed here because a human ran this command |
| `--no-repair` | Triage and report only. Never spawn the repair attempt in Step 5 |

## Step 1: Resolve the issue and the PR

With an argument, normalise it and fetch with `get_issue`. Without one:

```bash
git branch --show-current
```

Branches are `feature/rea-{N}-{slug}`, `fix/rea-{N}-{slug}` or `spike/rea-{N}-{slug}`, so pull
`{N}` from that. If the branch carries no issue number, run `list_issues` with `assignee: "me"`
and a started status and ask which one.

**Do not hardcode an MCP tool prefix.** Operations used here are `get_issue`, `list_issues`,
`save_comment` and `list_issue_statuses`; resolve the real prefixed names for the `linear-rea`
server from your available tool list, as `.claude/linear.md` requires. Posting a comment is
`save_comment`; `create_comment` does not exist. Never use `linear-nrl`: it is the plugin's
workspace. If no Linear tool is present, do the whole triage anyway and print the comment body
instead of posting it, saying the post did not happen.

Then find the PR:

```bash
gh pr list --head "$(git branch --show-current)" --json number,url,title,state --limit 1
```

No PR means there is nothing to triage: say so and point at `/ship`. A merged PR means this ran
too late; report the merge and stop.

## Step 2: Know which workflows exist before reading their results

```bash
ls .github/workflows/ 2>&1
```

No directory or no workflow file: verdict `CI NOT ESTABLISHED`, say the scaffold ticket has not
landed or did not add CI, and stop.

Otherwise read each workflow file and record, before looking at any result: its trigger
(`push`, `pull_request`, tags), its jobs, and the ordered step names of each job. A failure's
meaning depends on which step failed, and the step names are not guessable from the check name.

Two shapes to expect and handle:

- **A workflow on both `push` and `pull_request` runs twice** for a branch with an open PR, so
  the rollup holds two entries for one job on one commit. Deduplicate by `name` plus the commit
  before counting: reporting "2 failing checks" when one job failed twice is a false count, and
  rule 17 applies to a count as much as to a latency.
- **A tag-only release workflow** should not appear on a branch push at all. If it does, read
  why from its log rather than assuming; record it as `STRUCTURAL` only when the cause is shown
  to be the ref and not this branch's code.

## Step 3: Read the conclusions

`gh pr checks` has no `--json` flag on some `gh` versions; check `gh pr checks --help` before
relying on it. `gh pr view` does, so use it and get structured fields rather than parsing
tab-separated text:

```bash
gh pr view <PR> --json number,headRefOid,statusCheckRollup
```

Each rollup entry carries `workflowName`, `name`, `status`, `conclusion` and `detailsUrl`.
Classify:

| `status` / `conclusion` | Treat as |
|---|---|
| `COMPLETED` / `SUCCESS` | pass |
| `COMPLETED` / `FAILURE`, `TIMED_OUT`, `CANCELLED` | red, triage it in Step 4 |
| `QUEUED`, `IN_PROGRESS` | not concluded |
| `COMPLETED` / `SKIPPED`, `NEUTRAL` | not applicable, report as such |

Confirm the rollup belongs to the commit you think it does: compare `headRefOid` against
`git rev-parse HEAD`. A rollup for an older push is the quiet way to triage a failure that the
current head already fixed.

**Nothing concluded yet.** Without `--watch`, report that plainly and stop - do not guess, and do
not write a polling loop. With `--watch`, one bounded wait, and the cap is not optional:

```bash
timeout 900 gh pr checks <PR> --watch --interval 30
```

A `timeout` exit of 124 is `CI INCONCLUSIVE`, not a pass and not a failure. Say which it was.

## Step 4: Triage each red check, by measurement

The question is only ever: **did this branch cause it?** Answer it by reproducing, never by
reading the check name and inferring. This is `AGENTS.md` rule 16 applied to a CI failure and
rule 18 applied to the claim that a gate is "pre-existing".

1. **Find the failing step.** The rollup gives `detailsUrl`, whose numeric run id feeds:

   ```bash
   gh run view <runId> --log-failed
   ```

   Record which step failed, by the name the workflow file gives it (Step 2).

2. **Reproduce on the PR head, locally.** You are on the branch. Run only the gate that step
   runs, as `AGENTS.md`'s quality-gates block names it. Typical mapping, to confirm against the
   workflow file:

   | Failing step | Run locally |
   |---|---|
   | Install | `npm ci` |
   | Typecheck | `npm run typecheck` |
   | Lint | `npm run lint` |
   | TS tests | `npm test`, then the single failing test file |
   | Kotlin tests | `(cd android && ./gradlew testDebugUnitTest)` |
   | Release build / dependency audit | `(cd android && ./gradlew assembleRelease)`, and the dependency-tree grep from `/check-constraints` #14 |

   Check the gate exists before running it (rule 18). Reproduces locally: it is **NEW** and this
   branch's. Stop triaging and go to Step 5.

3. **It did not reproduce. Look for a local-versus-CI difference first**, before concluding
   anything about `main`. Compare the workflow's runner image, Node and JDK versions, Android
   SDK components (`sdkmanager --list_installed` locally), environment variables the workflow
   sets, and whether CI starts from a clean Gradle cache. A failure that needs a device
   (anything `adb`) cannot run in CI at all; if a CI step tries, that is a workflow defect.

   If a difference explains it, the verdict is **CI-ENVIRONMENT-ONLY**. Name which difference,
   and say that the code was not shown to be at fault rather than that it was shown to be fine.

4. **Reproduce on `origin/main`,** in a throwaway detached tree. Do this rather than stashing or
   checking out: the branch you are on must not move, and `main` is checked out in the primary
   repo so it cannot be checked out twice.

   ```bash
   TMP=$(mktemp -d)
   git worktree add --detach "$TMP/main" origin/main
   cd "$TMP/main" && npm ci && <the failing gate>
   ```

   Red on `main` too: **PRE-EXISTING**. That is a serious finding on its own, because a green
   `main` is the precondition for starting a ticket. Report it loudly and file it; do not quietly
   wave the PR through.

   Green on `main` and green on the branch locally but red in CI: **CI-ENVIRONMENT-ONLY** with no
   identified cause. Say exactly that. Do not round it to "flaky".

   Clean up when done, and do not leave the tree behind:

   ```bash
   cd - && git worktree remove --force "$TMP/main" && git worktree prune && rm -rf "$TMP"
   ```

   `--force` is safe here: nothing was written to it but build output, `node_modules` and the
   Gradle cache.

Record a verdict per failing check. `NEW` | `PRE-EXISTING` | `CI-ENVIRONMENT-ONLY` |
`STRUCTURAL` (shown in Step 2 to be caused by the ref, not the code).

## Step 5: One repair attempt for a NEW failure

Skipped under `--no-repair`, and skipped entirely for `PRE-EXISTING`, `CI-ENVIRONMENT-ONLY` and
`STRUCTURAL` - none of those is fixed by editing this branch.

Exactly one attempt, in a **fresh subagent**. Fresh because the context that wrote the code is
the worst judge of why it failed. Spawn with the `Task` tool; the subagent type is
`general-purpose` in Claude Code and `general` in opencode.

Its prompt must carry the absolute repo root, the branch, the failing step, the local
reproduction you observed in Step 4, and:

> Fix this. You already have the local reproduction, so do not re-derive it. Write or extend a
> regression test and **confirm it fails before your fix**; a test that passes on the unfixed
> code proves nothing. Never weaken an existing test to make a gate green - if the test is right
> and the code is wrong, fix the code. Consult the `AGENTS.md` non-negotiable for the area you
> touch: anything logging means no item text and no token ever; `Fetcher` means its limits stay;
> `BridgeServer` means explicit 127.0.0.1, token before body, caps, timeouts, Throwable, CORS on
> every response; playback means rate applied once and the native service owns the queue; a
> dependency means the resolved Gradle tree stays F-Droid-clean. Run the gates yourself, then
> commit and push to this branch. Report what you changed and every deviation.

Then re-read the conclusions **once**, the Step 3 way, allowing `--watch` if the invocation did.

**A second red conclusion stops.** Report the failing checks with the `detailsUrl` for each and
the repair that did not work. Do not attempt a third; the point of a cap is that an unbounded
repair loop can spend a whole session on one PR.

## Step 6: Verdict

```
Merge readiness for REA-12  (CI axis only)
==========================================

PR        #14  <url>
Head      <sha>, rollup matches HEAD

Checks (deduplicated: <job> ran twice, push + pull_request)
  <workflow> / <job>   FAILURE   <detailsUrl>

Triage
  <workflow> / <job>, step "<step>": reproduced locally on the branch
    <failing test and the assertion, verbatim>
    -> NEW, caused by this branch

Repair
  One attempt: <what changed>, pushed as <sha>. Re-read: <workflow> / <job> SUCCESS

Verdict: READY ON CI
Not covered by this command: nothing was installed on the phone and nothing was heard.
Run /verify REA-12 for the device half before claiming the change works.
```

Pick the honest verdict:

| Verdict | Means |
|---|---|
| `READY ON CI` | Every gate check concluded `SUCCESS`. Any red check was `STRUCTURAL` |
| `CI RED - NEW FAILURE` | At least one failure is this branch's and survived the repair attempt. Do not merge |
| `CI RED - NOT THIS BRANCH` | Every failure is `PRE-EXISTING` or `CI-ENVIRONMENT-ONLY`. Mergeable on the CI axis, and the pre-existing finding needs its own ticket |
| `CI INCONCLUSIVE` | Nothing concluded, or `--watch` hit its cap. Merging is a judgment call on the local gates alone; say so rather than implying CI passed |
| `CI NOT ESTABLISHED` | No workflow exists yet. There is no CI axis to judge |

## Step 7: Post to Linear

`save_comment` on the issue, with the Step 6 block verbatim plus one explicit sentence separating
the two claims, for example:

```
This is a CI triage only. GitHub Actions is green, which is not a claim the change works:
nothing was installed on the phone and no speech was heard during this check. The manual test
plan in the PR body is still unrun.
```

Do not transition the status; `/finish` owns the move to Done.

If a Linear tool is unavailable, or a named operation does not resolve, print the body and **say
the post did not happen**. A triage whose comment silently failed must not read identically to
one that posted.

If the failure is in plugin-side bridge code, it belongs to NRL-130 in the plugin's workspace.
Say so in the report; never file or comment on it through this repo's Linear server.

## Step 8: Next step

```
REA-12: <verdict>

CI:       <n passed, n red (n structural)>
Triage:   <NEW | PRE-EXISTING | CI-ENVIRONMENT-ONLY | STRUCTURAL per check>
Repair:   <not needed | one attempt, now green | one attempt, still red | skipped (--no-repair)>
Posted:   <yes | no, Linear unavailable>

Next: <run /verify REA-12, then merge | fix and re-run | file the pre-existing failure>
```

## Error handling

| Scenario | Action |
|---|---|
| No `.github/workflows/` | `CI NOT ESTABLISHED`. Stop |
| No PR for the branch | Nothing to triage. Point at `/ship` |
| PR already merged | Report the merge and stop; this ran too late to gate anything |
| `statusCheckRollup` is empty | The push may not have landed, or Actions is disabled. Check `gh run list --limit 5` and say which |
| Rollup `headRefOid` is not `HEAD` | You are triaging an older push. Say so, and re-read after the current head's run concludes |
| Two entries for one job with different conclusions | The `push` and `pull_request` runs disagree, which means something non-deterministic. Report both `detailsUrl`s and treat it as `CI-ENVIRONMENT-ONLY` with no identified cause; do not pick the green one |
| A red check is red on `main` too | `PRE-EXISTING`. Report loudly and file a ticket |
| The failing gate does not exist locally | Rule 18. Report that the workflow runs a command this repo does not define; that is a workflow defect, `NEW` if this branch changed the workflow |
| `node_modules` absent | `npm ci` first. A gate cannot pass or fail without it, and a fresh worktree has none |
| `gh` not authenticated | Stop. Every step here needs it; there is no degraded path |
| The temp `main` worktree will not remove | `git worktree remove --force`, then `git worktree prune`. Never leave it behind |
| Repair attempt makes it worse | Report both the original failure and the new one. Do not attempt a third |
