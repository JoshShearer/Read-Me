---
description: After a merge, diff AGENTS.md, srs.md and CONTEXT.md against the actual tree and the recorded spike answers, and correct whichever one has drifted.
---

Conventions: `.claude/linear.md`. Rules: `AGENTS.md`. Architecture: `CONTEXT.md`. Spec: `srs.md`.

## Purpose

This repo's documentation is load-bearing. `AGENTS.md` is the instruction set every agent session
reads, `srs.md` is the acceptance criteria and the record of spike answers, and `CONTEXT.md` is
the map a session uses to decide where a change belongs. Each drifts in its own direction:

| Doc | How it goes wrong |
|---|---|
| `AGENTS.md` "Known state" | Says "no code", "no gates", "no spike has run", or lists a defect. A merge makes one false and the next session works around something that is no longer true |
| `AGENTS.md` "Quality gates" | Lists the intended gate set. The scaffold makes it real; a later change renames or adds a gate and the block lags |
| `srs.md` | A spike ran and its answer lives only in a PR or a Linear comment; or code deviates and nothing records it |
| `CONTEXT.md` | Describes the intended shape ("Nothing below is built yet"). Once code lands, the planned source layout, the layers and "Known structural gaps" must match the real tree |

**Diff the docs against reality. Do not trust either one.** A doc that says something is true is
a claim to verify, not a fact to preserve, and the same is true in reverse.

## When to run

After a merge, after `/finish`, or when `/orient` reports undocumented merges.

## Input

`$ARGUMENTS`: optional `REA-XX` identifiers to limit scope. Omitted means every merge into `main`
in the last 7 days that has no matching docs commit.

## Step 1: Find what merged and is undocumented

```bash
git checkout main && git pull --ff-only
git log main --oneline --since='7 days ago'
gh pr list --state merged --limit 20 --json number,title,headRefName,mergedAt,body
```

`gh` failing is not a blocker; the `git log` output is enough.

For each merged change:

1. Extract the identifier from the PR title, then the branch name (`(feature|fix|spike)/rea-(\d+)-`),
   then the commit body (`REA-(\d+)`).
2. Skip if the subject starts with `docs:`, or no `REA-XX` was found (note it as skipped).
3. Match against existing docs commits with a **word-boundary** check on `REA-{number}` followed
   by a non-digit, so `REA-1` does not match `REA-12`:

```bash
git log main --oneline --since='14 days ago' --grep='^docs'
```

4. Apply the `$ARGUMENTS` filter if given.

Also pull the requirement or spike ID (`R-M03`, `SPIKE-04`) out of each issue via `get_issue`.
**Do not hardcode a tool prefix.** Resolve the real name for the `linear-rea` server from your
available tool list, as `.claude/linear.md` requires. Linear unreachable means git-only: print
what you would have read.

Report the scan, then proceed. Nothing to do:

```
All merges from the last 7 days are already documented.
Docs drift can still exist without a merge. Run the Step 2 checks anyway if it has been a while.
```

## Step 2: Diff the docs against the tree

Run these regardless of what merged. They are cheap, and drift arrives without a PR.

### 2a. `AGENTS.md` Known state and Quality gates

Each claim is checked by a command, not by memory:

| Claim | How to test it now |
|---|---|
| "No code" | `git ls-files src android \| head` - anything listed makes it false |
| "There is no code yet, so there are no gates yet" | `npm run` and `ls android/gradlew .github/workflows/` - every gate that now exists must appear in the block, with the real command, and the CI workflow named |
| The intended gate list | Every command in the block must exist (`npm run`, `ls`). A listed gate that does not exist after the scaffold is drift (rule 18) |
| "Six spikes ... None has run" | Read `srs.md` "Spikes" for recorded answers; `git log --oneline --grep=SPIKE` |
| Build toolchain lines | `ls ~/Android/Sdk/build-tools`, `cat ~/Android/tools/jdk-path.txt`; re-verify before keeping a version number |
| "The GitHub repo is public" and the release state | `gh repo view --json visibility`; `gh release view v<ver> --json isDraft,targetCommitish` |
| Any reproduced defect listed | Reproduce it, on the phone if it is a device behaviour (rule 16) |

A false entry gets **removed** or rewritten to what is now true. A partially true one gets
**narrowed**, not deleted. Deleting an entry whose problem survives is the single most damaging
edit this command can make: the next session will trust the absence.

If Known state records a spec-check baseline (see `/spec-check`), re-check it only by running
`/spec-check`. Never move a met count by assumption; a requirement is met only when observed on a
device per R-M14, with date, device and build.

### 2b. `srs.md`: spikes and deviations

```bash
grep -n -E '^### R-|^- \*\*SPIKE-' srs.md
```

Requirements are `R-M01`...`R-M14`, `R-S01`...`R-S05`, `R-C01`...`R-C04`; spikes are
`SPIKE-01`...`SPIKE-06`.

- **A spike ran.** Its branch code is throwaway; what ships is the answer. It belongs under its
  bullet in "Spikes": the answer, the measurement, the device and the date. If the answer lives
  only in a PR body or a Linear comment, copy it into `srs.md`. If it changes a decision (for
  example SPIKE-06 replacing the separate-instances rule, or SPIKE-05 revising R-M07's 300 ms
  target), it also needs an ADR and the affected requirement amended.
- **The code now satisfies a requirement.** `srs.md` is a contract, not a tracker, so the
  requirement text usually does not change. Status lives in the Linear issue and `/spec-check`.
- **The code deliberately does something else.** Deviating is allowed and deviating **silently**
  is not: record an ADR in `docs/adr/` and amend `srs.md`. Number ADRs from the highest existing:

```bash
ls docs/adr/
```

  The ADR names the requirement, what the code does instead, why, and what was given up. The
  amendment points at the ADR filename. **Never edit `srs.md` to match code without writing the
  ADR in the same commit.** An unexplained amendment turns a deviation into an invisible
  requirement change.

Known pending amendment, not drift to "fix" here: `CONTEXT.md` records the owner decision that
Kotlin owns the database while `srs.md` still says TS `library` on SQLite (roadmap finding F11).
It needs its ADR and amendment as its own change; report it as still open if it is.

### 2c. `CONTEXT.md` against the real architecture

**The source layout.** Until the scaffold lands, "Planned source layout" is intent. After it:

```bash
git ls-files src android/app/src/main | sed 's#/[^/]*$##' | sort -u
```

The section must describe the real tree, and the "Nothing below is built yet" line must go once
it is false. Record the applicationId and the app's log tag there; `/verify` and `/finish` read
them.

**Layering rules.** Each is checkable once code exists:

```bash
rg -n '\b(fetch|XMLHttpRequest|WebSocket)\b' src/        # must be empty (non-negotiable 6)
rg -n -l 'openConnection|OkHttpClient|HttpURLConnection' android/ # only Fetcher (and BridgeServer's server socket)
```

A layering rule the code now breaks is a code defect, not a doc edit (see Step 3).

**Known structural gaps.** "The whole tree" and the spike entry close as code and answers land.
A closed gap is removed; a narrowed one is rewritten.

## Step 3: Make the edits

Only touch the doc that is actually wrong. Editing all three because one drifted produces a diff
nobody can review.

- No em-dashes. A plain hyphen or a rephrase.
- Match the surrounding voice: terse, specific, with where the thing lives.
- **Never relax a rule to match code that broke it.** If the code contradicts a non-negotiable in
  `AGENTS.md`, the code is wrong. Open an issue; do not edit the rule.
- Never assert a measurement you did not take (rule 17). If a change invalidated a number, either
  re-measure it this session on a named device or leave it and note where and when it was
  measured. Measurements cited from the plugin repo stay cited, not restated as local.

## Step 4: Verify the docs still match after editing

The claims you just wrote are checkable, so check them. Every path named in `AGENTS.md` or
`CONTEXT.md` must exist:

```bash
rg -o -N '`((src|android|docs|\.claude)/[^`]+)`' -r '$1' AGENTS.md CONTEXT.md | sort -u \
  | while read -r p; do [ -e "${p%%:*}" ] || echo "MISSING: $p"; done
```

Then run the gates **that exist** (check first, rule 18). A docs-only change cannot break them, so
a red result means the branch was not clean when you started; say so rather than committing on top
of it. No gates yet: report `GATES: NOT YET ESTABLISHED`.

## Step 5: Commit

Stage only the doc files you edited, by name. Never `git add` a directory wholesale:
another session's uncommitted work may be sitting in the tree.

```bash
git add AGENTS.md CONTEXT.md srs.md docs/adr/<new-adr>.md
git commit -m "docs: record SPIKE-03 answer after REA-5 merge

<the answer in one line>, measured on <device, OS> at <commit> on <date>.
AGENTS.md spike line narrowed; R-M08 unchanged.

Refs REA-5"
```

Every `REA-XX` documented must appear in the **subject line**: Step 1's cross-reference scan reads
subjects, so a number only in the body will be re-documented next week.

Docs-only changes may go straight to `main`; that is the documented exception to the
branch-and-PR flow in `/ship`. An ADR is not docs-only in spirit, because it records a spec
deviation: mention it in the commit body and post the ADR path as a `save_comment` on the issue
that caused it.

```bash
git push origin main
```

## Step 6: Report

```
Documentation Updated

## Scan
| Merge | Issue | Status | Requirement |
|---|---|---|---|
| a1b2c3d | REA-5 | documented now | SPIKE-03 |
| e4f5g6h | REA-2 | already documented (commit 9z8y7x6) | R-M13 |
| i7j8k9l | (none) | skipped, no REA id | - |

## Doc diffs found
- srs.md: SPIKE-03 answer recorded under Spikes (measurement cited from REA-5's comment).
- AGENTS.md Known state: spike line narrowed to five unrun.
- CONTEXT.md: no drift; still pre-scaffold.

## Not verified
- <claim kept because it could not be checked here, and why>

## Committed
<hash> - docs: record SPIKE-03 answer after REA-5 merge
Pushed to origin/main
```

## Error handling

| Scenario | Action |
|---|---|
| No recent merges | Still run Step 2; drift arrives without a PR |
| `gh` fails or is unauthenticated | Use the `git log main` fallback, say `gh` was skipped |
| A claim cannot be tested on this machine (no phone attached) | Keep the entry, report it under "Not verified". Never delete an unverified claim |
| The code contradicts a rule in `AGENTS.md` | Do not edit the rule. Open an issue and say so in the report |
| A merged change deviates from `srs.md` | ADR in `docs/adr/` first, then amend `srs.md` in the same commit, then link both from Linear |
| A spike answer exists only outside `srs.md` | Copy it in with its device and date; if the source has no device or date, report it as unrecorded rather than inventing one |
| Linear tool absent | Do the doc work; print what you would have sent |
| Requirement status ambiguous | Do not guess. Run `/spec-check` for that requirement, or report it as unresolved |
