---
description: Close out a merged issue - verify the merge, delete the remote and local branch, remove the worktree, sync main, set the issue Done, flag a stale build on the phone, and update srs.md or the AGENTS.md known state if the merge changed what is true.
---

Conventions: `.claude/linear.md`. Rules and gates: `AGENTS.md`. Spec: `srs.md`.

The unbreakable rule: every branch this command touches leaves both local **and** remote clean.
"Deleted local, remote still present" is a failure, not a partial success.

## Linear tool names

Operations used here: `get_issue`, `save_issue`, `save_comment`. **Do not hardcode a tool
prefix.** Resolve the real names for the `linear-rea` server from your available tool list.
Resolving the prefix is not enough: Linear folded its create/update pairs into `save_*`, so
posting a comment is `save_comment` and there is no `create_comment` (see `.claude/linear.md`).
Never touch an REA issue through `linear-nrl`; that is the plugin's workspace. If Linear is
unreachable, do the git work and print the status change for manual entry.

## Input

`$ARGUMENTS` is an optional branch name, e.g. `fix/rea-19-fetcher-redirect-cap`. Omitted: detect
from the current branch, or scan for stale ones.

## Step 1: Identify the branch

**Argument given**: use it.
**On a `feature/*`, `fix/*` or `spike/*` branch**: use the current branch.
**On `main`**: scan:

```bash
git fetch origin --prune
git branch -vv | grep '\[.*: gone\]' || echo "none gone"
```

Then catch merged branches whose remote still exists:

```bash
for b in $(git for-each-ref --format='%(refname:short)' refs/heads/ | grep -E '^(feature|fix|spike)/'); do
  pr=$(gh pr list --head "$b" --state merged --json number,mergedAt --jq '.[0]' 2>/dev/null)
  [ -n "$pr" ] && echo "$b -> $pr"
done
```

Present what you found and ask before deleting anything:

```
Found N branches to clean up:

| # | Branch | Local | Remote | Merge status |
|---|--------|-------|--------|--------------|
| 1 | fix/rea-19-fetcher-redirect-cap | present | gone | Merged 2 days ago |
| 2 | feature/rea-12-share-intake | present | present | No merged PR found |

Clean up? (y / n / select)
- y: delete branches with a verified merge only. Row 2 is excluded.
- select: give numbers, e.g. "1"
```

## Step 2: Verify the merge

```bash
gh pr list --head <branch> --state merged --json number,mergedAt,title
```

| Result | Action |
|---|---|
| Merged PR found | Proceed. Record the PR number and merge date. |
| No merged PR | Warn hard and require explicit confirmation. Check `git log origin/main --oneline \| grep -i rea-N` before believing either answer. |
| `gh` unavailable | Mark the branch Unverified. Require an explicit `select`; never include it in a default `y`. |

A **spike** branch is often not merged at all: its code is throwaway and what ships is the
recorded answer in `srs.md` "Spikes". Before deleting an unmerged `spike/*` branch, confirm that
answer is on `origin/main` (`git show origin/main:srs.md | grep -n SPIKE-0N`). If it is not, stop:
deleting the branch may delete the only record of the measurement.

There may be no PRs on this repo yet. The first run of this command finding nothing is the
correct answer rather than a bug.

## Step 3: Leave the branch before pulling

Order matters and getting it wrong is silent.

```bash
git config branch.<branch>.base-branch 2>/dev/null || echo main
git checkout <base-branch>
git pull --ff-only origin <base-branch>
```

Two reasons for that order:

- `git pull origin main` while standing on the feature branch merges main **into the feature
  branch**, which is the opposite of cleanup.
- `git branch -d` cannot delete the branch you are on.

Checkout failing on uncommitted changes: **stop and surface it**. Do not stash silently. The work
being cleaned up is supposed to be merged; unexpected local changes mean something is wrong.

## Step 4: Delete the remote first

Remote before local, so a partial failure leaves the local branch as a recovery anchor.

```bash
git ls-remote --heads origin <branch>
```

- Exists: `git push origin --delete <branch>`, and capture the exit code. If that fails because
  the ref is protected or the push is refused, the API route is the fallback:
  `gh api -X DELETE repos/{owner}/{repo}/git/refs/heads/<branch>`.
- Already gone: record `remote: already-deleted`.
- Both routes fail: **STOP**. Do not delete local.

`gh pr merge --delete-branch` run from inside a worktree errors on the local-branch half
(the branch is checked out there), **but the merge lands anyway** and the remote ref is left
behind. A branch that arrives here that way is merged; delete its remote ref with the `gh api`
call above and continue.

## Step 5: Delete the local branch

```bash
git branch -d <branch>
```

`-d` failing because GitHub squash-merged is the dominant case. Fall back to `-D` **only** after
`gh pr list --head <branch> --state merged` has confirmed the merge. Any other failure: stop and
surface it.

## Step 6: Prune and verify both sides

```bash
git fetch origin --prune
git show-ref --verify --quiet refs/heads/<branch>   # must exit 1
git ls-remote --heads origin <branch>                # must print nothing
```

If either still resolves, that branch is a FAILED row in the summary. This command does not
report success without this pass.

## Step 7: Remove the worktree

```bash
ISSUE_NUM=$(echo "<branch>" | grep -oE 'rea-([0-9]+)' | grep -oE '[0-9]+')
ls -d "$HOME/Documents/Dev/Read-Me-rea-${ISSUE_NUM}" 2>/dev/null
```

Inside that worktree right now: tell the user to `cd` to the primary repo first and re-run. A
worktree cannot remove itself.

From the primary repo:

```bash
git worktree list
git -C "$HOME/Documents/Dev/Read-Me" status --short   # confirm you are in the primary
git worktree remove "$HOME/Documents/Dev/Read-Me-rea-${ISSUE_NUM}"
git worktree prune
```

`git worktree remove` refusing because the tree is dirty: show what is dirty and ask. Only use
`--force` after the user has seen the file list and said so. A dirty worktree after a merged PR
usually holds `node_modules`, `android/build/`, `android/app/build/` and `.gradle/`, which are
safe to discard, but confirm rather than assume.

If the removed worktree held `.claude/device.lock/` (a lane that died mid-verify), say so; do not
remove the lock silently, since another lane may have taken it since.

**Never touch a worktree outside the `Read-Me-rea-*` naming.** That pool belongs to `treehouse` /
gnhf, per the lane rule in `.claude/linear.md`.

### The phone may now hold an orphaned build

If the removed worktree was the last to run `npm run device:install`, the attached phone runs a
build whose source tree no longer exists. Check what is installed, read-only:

```bash
adb devices
adb shell dumpsys package "$PKG" | grep -E 'versionName|versionCode|lastUpdateTime'
```

`$PKG` is the applicationId recorded in `CONTEXT.md`. If the installed build does not match
merged `main` (or you cannot tell), tell the user to reinstall from the primary repo with
`npm run device:install` once main is synced, taking `.claude/device.lock/` first. **Never install
from this command.** No phone attached: say the check was not run.

## Step 8: Confirm sync

Step 3 already moved and pulled. Confirm, do not repeat:

```bash
git branch --show-current                              # the base branch
git rev-list --left-right --count origin/main...HEAD   # 0 0
git status --short                                     # empty
```

## Step 9: Close the issue

`save_issue` with `state: "Done"`. Check the current state first and skip only if it is already
Done. `In Review` is optional on this team (`.claude/linear.md`); the issue may be coming from
In Progress.

**This step is load-bearing, not a safety net.** `Resolves REA-XX` in a commit footer is a GitHub
issue-closing keyword and does not close a Linear issue. Unless Linear's GitHub integration has
been verified connected for this repo and recorded in `.claude/linear.md`, nothing else will move
the issue.

Post a `save_comment` with the merged PR link and the merge date if `/ship` did not already. For a
spike, the comment also names the `srs.md` line where the answer was recorded.

## Step 10: Does the spec or the known state need updating?

This is the step that keeps `AGENTS.md`, `srs.md` and `CONTEXT.md` from going stale, and it is
the reason this command exists rather than a git alias. For a full sweep, run `/update-docs`;
this step covers only what this one merge changed.

### 10a: `AGENTS.md` Known state

```bash
git log origin/main --oneline -5
git show --stat HEAD
```

| If the merge | Check whether this entry is now false |
|---|---|
| was the scaffold | "No code", and the Quality gates "no gates yet" paragraph. Both must be replaced with the real gate commands and CI workflow, verified to exist |
| answered a spike | "Six spikes precede implementation ... None has run" |
| fixed a reproduced defect listed there | That defect's entry |

A fixed entry is **removed**. Do not soften it to "partially fixed". If the fix is partial,
rewrite the entry to describe exactly what remains, since a vague entry is what makes the next
agent rediscover it.

If the merge closed a requirement, the spec-check baseline in Known state may have moved. Only
change it if a device observation per R-M14 backs it (date, device, build); never move a count
on a code reading or a green suite.

### 10b: `srs.md`

| Situation | Action |
|---|---|
| The implementation matches the requirement | No edit. `srs.md` is the contract, not a changelog. |
| A spike answered its question | Record the answer under "Spikes" with the measurement, device and date. If it changes a decision, also an ADR. |
| The implementation deviates from the requirement | An ADR in `docs/adr/` **and** an amendment to `srs.md`, in the same commit. Deviating is allowed; doing it silently is not. |
| The requirement was ambiguous and the ticket resolved it | Amend `srs.md` so the next reader gets the resolved version. |

### 10c: `CONTEXT.md`

Edit it only if the merge changed the architecture: the real source tree once the scaffold lands,
a new term in the vocabulary table, or a structural gap that has closed. Do not log bug fixes
there.

Any doc edit from this step is a separate commit on `main`, so the cleanup and the doc update
stay distinguishable in `git log`:

```bash
git commit -m "docs: record SPIKE-03 answer after REA-5 merge"
```

## Step 11: Summary

Every row shows local **and** remote. A row saying only "Deleted" is incomplete and forbidden.

```
Cleanup Complete

## Branches
| Branch | Local | Remote |
|--------|-------|--------|
| fix/rea-19-fetcher-redirect-cap | Deleted | Already deleted by GitHub |

## Worktree
- Read-Me-rea-19: removed
- Phone: installed build does not match main. Run `npm run device:install` from the primary repo.

## Repo
- main: up to date, clean

## Linear
- REA-19: Done

## Docs
{one of:}
- AGENTS.md: removed the fixed entry from Known state
- srs.md: SPIKE-03 answer recorded
- Nothing to update

{if any FAILED row:}
## Action Required
<N> deletion(s) failed. Inspect with `git ls-remote --heads origin` and
`git for-each-ref refs/heads/`, clear manually, then re-run.

## Next
1. `/orient`
2. `/start-issue REA-XX`
```

**Already clean** (on `main`, nothing stale, no worktrees): say so plainly. "No feature branch
detected. Local and remote clean, main synced." Then offer `/orient`. Do not invent work.

## Error handling

| Scenario | Action |
|---|---|
| Branch missing locally | Check `git ls-remote`. Remote present: offer a remote-only delete. |
| PR not merged | Warn hard, require explicit confirmation, log it loudly in the summary. |
| Unmerged spike branch, answer not on main | Stop. The branch may be the only record. |
| Uncommitted changes blocking checkout | Stop. Do not stash silently. |
| `git branch -d` fails, PR merged | Fall back to `-D`. |
| `git branch -d` fails, PR not merged | Stop. Surface it. |
| Remote delete fails (push and `gh api`) | Stop. Do not delete local. |
| `gh pr merge --delete-branch` errored in a worktree | The merge landed; delete the remote ref via `gh api` and continue. |
| `git worktree remove` refuses on a dirty tree | Show the dirty files, ask, `--force` only on an explicit yes. |
| Running inside the worktree being removed | Stop. Tell the user to `cd` to the primary repo. |
| Pull conflicts on the base branch | Stop. That needs a decision. |
| `gh` unavailable | Skip merge verification, mark rows Unverified, require `select`. |
| No phone attached | Report the orphaned-build check as not run. |
| Linear unreachable | Do the git work, print the Done transition as text for manual entry. |
| Step 6 finds residue | FAILED row. Do not claim success. |

## Safety invariants

1. Local and remote both clean, every time. One side only is a failure.
2. Remote is deleted before local, so the local branch remains a recovery anchor.
3. Never `-D` without `gh` confirming the merge.
4. Never delete a remote branch whose PR is not merged, even on confirmation, without logging it.
5. Never remove a worktree outside the `Read-Me-rea-*` pool.
6. The Step 6 verification pass is mandatory.
7. Never install on the phone from this command; only report that a reinstall is needed.
