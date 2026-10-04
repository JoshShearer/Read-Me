---
description: Run a set of Linear tickets end to end, fully autonomously, in a disposable worktree it creates and removes - pre-flight triage, then start, plan, implement, ship, automated verify, merge and finish per ticket in fresh subagents. Never waits on a human; anything that needs one blocks that ticket and is reported at the end.
---

Conventions: `.claude/linear.md`. Rules and gates: `AGENTS.md`. Architecture: `CONTEXT.md`.
Spec: `srs.md`.

Ported from the sibling plugin repo's pipeline, which was itself adapted from a Jira one. Read
the fact table before the first run.

## Read this before the first run

| Fact | Consequence |
|---|---|
| **The run is fully autonomous.** The owner tests features by using the app and files new tickets for what they find. | No phase waits for a reply. Verify is automated, merge is automatic. Anything that would have needed a human blocks **that ticket only** and the run moves on. Everything blocked or decided on the owner's behalf is listed in the end-of-run report. |
| **There are no gates yet.** `AGENTS.md` says so until the scaffold ticket lands. | Every phase that runs gates uses the `Running the gates` block below. Pre-scaffold it prints `GATES: NOT YET ESTABLISHED` and nothing is called a pass. Post-scaffold, a gate `AGENTS.md` names that does not exist is a failure, not "not established". |
| **There is no CI yet.** No `.github/workflows/`. | When a workflow exists, a phase **may** read its conclusion once with the snippet under `Reading a CI conclusion` and **must not** wait on it. Never write a polling loop. Until then, record `CI: none`. |
| **A green suite is not a working feature** (`AGENTS.md` rule 15). Unit tests run against fakes. | Automated Verify also runs scripted on-device checks over adb, but only when exactly one device is attached and `.claude/device.lock/` is free. Otherwise device verification is recorded as `DEVICE: NOT RUN (<reason>)`. **Nothing is ever called VERIFIED ON DEVICE unless the device check actually ran and observed it**, and never "verified by a human". |
| **Bugs must be reproduced before they are fixed** (rule 16), on the device if it is a device behaviour. | Implement begins by reproducing, not by editing. A repro that fails, or that needs the phone when the phone is unavailable, blocks the ticket rather than proceeding on a guess. |
| **Spikes are first-class tickets.** `srs.md` lists SPIKE-01..06 and none has run. | A spike's deliverable is the recorded answer in `srs.md` "Spikes" (with date, device, build and the measurements), plus an ADR in `docs/adr/` when the answer changes a decision. Probe code is throwaway unless the ticket's Decisions say the owner keeps it; raw output is archived in `$PRIMARY/.claude/scratch/<ID>/`. Every spike needs the reference device. |
| **The run works in a disposable worktree it creates itself.** Step 0c adds `Read-Me-run-<stamp>` beside the primary and removes it at the end. | The run never touches the primary checkout, so the owner can keep working there. The run's *records* must outlive the lane, so the state file and the archive live in the **primary**. Step 0 resolves `$PRIMARY` first. |
| **One phone.** Installing a build replaces the app on it. | Only one lane at a time may use it. Take `$PRIMARY/.claude/device.lock/` atomically with `mkdir`, write its `owner` file, install, run the checks, release. **Resolved against `$PRIMARY` deliberately:** a lock inside the run's own fresh lane is free by construction and would guard nothing. A lane that cannot take it records `DEVICE: NOT RUN (lock held by <holder>)` and does not wait. |
| **The phone is the owner's own.** It holds their library, app-private by non-negotiable 3. | Never `adb uninstall`, `pm clear`, or reinstall with a data wipe to get a build onto it. A signature-mismatch install failure blocks the ticket. |
| **Base branch is `main`.** | No special casing. Still assert that `origin/HEAD` resolves rather than assuming. |
| **Parallel runs are supported.** Several `/run-tickets` may be in flight at once, each in its own lane with its own state file. | Nothing serialises a run as a whole. The two things really shared are guarded individually: the phone by `device.lock`, and `main` by git, where a conflict blocks one ticket and nothing else. **Give parallel runs disjoint ticket sets.** Two runs holding one ticket is duplicated work and two PRs for one fix. |
| **Every run's state file is its own**, `$PRIMARY/.claude/pipeline-state.<stamp>.json`, sharing the lane's stamp. | A run writes exactly one state path no other run can name. In the plugin repo two runs once shared a hardcoded state file; the second reinitialised it, destroyed the first run's records and its archive, then rewrote `main` and dropped an unpushed commit. **Never write another run's state file**, and never "tidy" one away. |
| **The device lock binds everyone; nothing else is run-wide.** | Interactive `/start-issue`, `/ship` and `/verify` in the primary or a `Read-Me-rea-*` worktree still run alongside a live run. What they must not do is install on the phone without the lock. |
| **Cross-repo work is not this run's.** The plugin side of the bridge is NRL-130 in the plugin's own Linear workspace. | A ticket that cannot be finished without plugin changes blocks with that reason. Never file or edit a ticket through `linear-nrl`, and never commit to `note-reader-local` from here. |

`gh` is installed and authenticated as `JoshShearer`. There is no required review, so merge
automation works. Never self-approve a PR to get around a review requirement if one is ever
added; block the ticket instead.

## How to launch it, per runtime

"Fully autonomous" is a property of **how the run is launched**, not of this file. Four commands
the pipeline runs are permission-gated in `opencode.json`, and a gated command is a prompt, which
is the human wait the design exists to avoid. Launched wrong, it stalls at the first Ship.

| Gated command | Where the pipeline runs it |
|---|---|
| `git push -u origin <branch>` | every Ship phase |
| `git branch -D <branch>` | every Finish phase (squash merge makes `-d` refuse); the run branch at Step 8 |
| `git reset --hard origin/main` | Step 0d, resyncing the lane between tickets |
| `rm -rf "$DEVICE_LOCK"` | releasing the phone, in Verify and in Finish |

`git worktree add` and `git worktree remove` match no rule and add no prompt.

- **opencode.** Run headless, which auto-approves everything not explicitly denied
  (`npm publish*` stays denied):

  ```bash
  opencode run --auto --command run-tickets "REA-1,REA-2"
  ```

  `--command` takes the command name and passes the message through as `$ARGUMENTS`.

- **Claude Code.** `/run-tickets REA-1,REA-2` inside a bypass-mode session. An ordinary session
  prompts on the same commands.

**Do not "fix" a stall by loosening `opencode.json`.** Those rules guard every other session in
this repo, and `git push` and `rm -rf` guard exactly the destruction the state-file incident in
the fact table caused. If you are prompted mid-run, the run was launched wrong: stop, report which
command was gated, and relaunch with the flag.

## Tracker: Linear

MCP server `linear-rea`, team key `REA` (owner-stated; `.claude/linear.md` records whether it has
been verified). Operations used: `get_issue`, `list_issues`, `save_issue`, `save_comment`,
`list_issue_statuses`.

**Resolve the real tool names from your own available-tool list.** The prefix differs between
Claude Code and opencode and must never be hardcoded. If no Linear tool is present, the run still
works: every phase does its git work and prints what it would have sent, and the state file
carries it. A missing tracker never blocks a commit.

**The operation name can be stale too, not just the prefix.** Posting a comment is
`save_comment`; `create_comment` does not exist. Because a missing tracker never blocks a commit,
an unresolvable operation looks exactly like an unauthenticated server, so a run can carry on and
report success with nothing posted. That happened to every comment the plugin's pipeline wrote
before the name was fixed. If an operation does not resolve, check the name against your tool
list before concluding the server is down, and say in the end-of-run report what was not posted.

Status writes are `save_issue` with `id` and `state: "<name>"`; the parameter is `state`, never
`status`. Read the status back after every write. `In Review` is optional, per
`.claude/linear.md`.

If an issue lookup fails on an unknown identifier, re-run the discovery block in
`.claude/linear.md`: the team key may not be what was recorded.

## Why phases run in fresh subagents

Quality, not speed. The subagent that just spent its context writing a fix is the worst judge of
whether it is sound. Fresh context per phase means each phase reads only the state file and the
repo, the way a different person would. With no human gate this is the main defence against a
plausible-looking wrong fix, so do not merge phases to save time.

This orchestrating conversation stays alive for the whole run and never does the work: read state
-> spawn one subagent for exactly one phase of one ticket -> receive a short summary -> write it
to the state file -> spawn the next. Its context grows from summaries, not transcripts.

Spawn with the `Task` tool: `general-purpose` in Claude Code, `general` in opencode.

Every prompt must carry, as literal strings and not as rules for deriving them: **the lane's
absolute path** as the repo root, the ticket id, and **the resolved `$STATE_FILE` path**.
Subagents do not inherit your shell. The costly mistake is the repo root: a phase that resolves
to `$PRIMARY` commits into the owner's working tree instead of the lane.

## Input

Argument: `$ARGUMENTS`

- A comma-separated list of ids, e.g. `REA-12,REA-14`. Bare numbers mean `REA-<n>`.
- `all` - auto-discover assigned, not-done issues via `list_issues`, priority order. Drop anything
  in a blocked or cancelled state.
- `--no-merge` - stop each ticket after automated Verify with its PR open. Later tickets then
  branch from a `main` that lacks earlier fixes, so use it for one ticket or unrelated tickets.
- `--no-device` - never touch the phone this run; every device check is
  `DEVICE: NOT RUN (--no-device)`. Spikes and device-behaviour bugs then block.
- `--keep-worktree` - skip Step 8's cleanup and print the lane's path. For debugging a run.
- `--resume [<stamp>]` - re-validate a state file against Linear and git before continuing, and
  recreate the recorded lane if its directory is gone. With no stamp, pick the most recently
  modified `$PRIMARY/.claude/pipeline-state.*.json` that still holds an in-progress ticket, print
  which one and its `runId`, and stop rather than guess if two are in progress. A bare
  `/run-tickets <ids>` always starts a new run.

If no argument is given, ask which tickets to run. That is the only question this command asks.

## Step 0: Establish facts, every run

**Resolve the primary repo first.** Every recorded path is relative to it, and the lane does not
exist yet. Stripping a suffix makes the run indifferent to where it was launched from: the
primary, an interactive `-rea-*` worktree, or a previous run's `-run-*` lane.

```bash
REPO_ROOT=$(git rev-parse --show-toplevel)
PRIMARY="$REPO_ROOT"
[[ "$(basename "$PRIMARY")" =~ -rea-[0-9].*$ ]] && PRIMARY="${PRIMARY%-rea-*}"
[[ "$(basename "$PRIMARY")" =~ -run-[0-9]+-[0-9]+$ ]] && PRIMARY="${PRIMARY%-run-*}"

# One stamp names the lane, the run branch and the state file, so the three always match by eye.
STAMP=$(date -u +%Y%m%d-%H%M%S)
STATE_FILE="$PRIMARY/.claude/pipeline-state.${STAMP}.json"
DEVICE_LOCK="$PRIMARY/.claude/device.lock"
ARCHIVE_DIR="$PRIMARY/.claude/scratch"

echo "primary=$PRIMARY  launched-from=$REPO_ROOT  stamp=$STAMP"
git -C "$PRIMARY" rev-parse --git-dir   # must print ".git", not a worktrees/ path
git -C "$PRIMARY" status --short
```

If `--git-dir` prints a path under `.git/worktrees/`, the strip did not find the primary. Stop and
report both paths; a lane created from a linked worktree breaks the `${PRIMARY}-*` globs
`worktrees.md` depends on.

### Step 0a: Claim this run's own state file

There is no run-wide lock, deliberately. Each run has its own lane and its own state file, so two
runs share only `main` and the phone, and each of those has its own guard.

```bash
RUN_ID=$(date -u +%FT%TZ)
if [ -e "$STATE_FILE" ]; then
  echo "STATE FILE ALREADY EXISTS, this run must not start: $STATE_FILE"
  echo "Another run started in the same second. Re-run for a fresh stamp. Do not delete or reuse it."
  exit 1
fi
mkdir -p "$ARCHIVE_DIR"
printf '{"runId":"%s","stamp":"%s","primary":"%s","tickets":[]}\n' \
  "$RUN_ID" "$STAMP" "$PRIMARY" > "$STATE_FILE"
ls -1 "$PRIMARY"/.claude/pipeline-state.*.json 2>/dev/null | grep -vF "$STATE_FILE" || true
```

If another run's state file is listed, **name it in the first message** and carry on. Reading its
`tickets` array to check for overlap is the single permitted access to another run's file, and it
is read-only. Block any overlapping ticket in **your own** run with
`blockedReason: "also held by run <their runId>"`.

Refresh a `heartbeat` field at every phase transition, in the same write that appends to
`history`, so a reader can tell a live run from a dead one. Nothing blocks on it.

### Step 0b: Never destroy another run's or the owner's work

- **Never write, move, archive or delete a state file you did not create.** "The tickets look
  done" is not a reason; a finished-looking file can hold a blocked ticket with an open PR.
- **Never empty `$ARCHIVE_DIR`.** It is gitignored, it is the archive of record, and it holds
  spike raw output and the install marker.
- **Never rewrite a branch you did not create**, and never amend, rebase or reset `main`. The
  run's own `run/<stamp>` branch is the one exception: Step 0d resets it, and it exists only to be
  reset and deleted.
- **Never touch the primary checkout.** The run never `checkout`s, `reset`s or commits in
  `$PRIMARY`. Its only writes there are under `$PRIMARY/.claude/` (state file, device lock while
  held, archive) and the `git worktree add` / `remove` pair.
- **Never remove a worktree this run did not create.** Cleanup touches exactly the state file's
  `worktree` path. `Read-Me-rea-*` belongs to `worktrees.md`'s pool and any other sibling may
  belong to treehouse.

Assert before starting. These are the only conditions that stop the whole run, because nothing
has been touched yet:

- `$STATE_FILE` was claimed (Step 0a).
- `$PRIMARY` has no uncommitted changes under the paths a ticket branch touches:
  `git -C "$PRIMARY" status --short -- src android docs/adr srs.md AGENTS.md CONTEXT.md`. A human
  mid-edit there is a real hazard; stop and report, do not stash or discard. A dirty `.claude/`
  is **not** a reason to stop: the lane is cut from `origin/main` and the
  primary's working tree cannot reach a ticket branch. Say what is dirty and carry on.
- `git -C "$PRIMARY" fetch origin` succeeds and `origin/HEAD` resolves to `main`.

### Step 0c: Create the run's lane

```bash
RUN_WT="${PRIMARY}-run-${STAMP}"
RUN_BRANCH="run/${STAMP}"
git -C "$PRIMARY" worktree add --no-track -b "$RUN_BRANCH" "$RUN_WT" origin/main
cd "$RUN_WT"
```

**`--no-track` is load-bearing.** Without it the new branch's upstream is `origin/main`, so a bare
`git push` from the lane would target `main` directly. Ship always pushes with an explicit
`-u origin <branch>`, but this branch is reset every ticket and exists to be thrown away, and an
accidental push from it would go straight at the base branch.

**The name is deliberately outside `Read-Me-rea-*`.** That glob is the interactive pool, and
`finish.md` removes `Read-Me-rea-<N>` by name. A lane in that pool could be removed out from under
a running ticket by its own Finish.

Then make the lane usable, and prove it before any ticket touches it:

- `android/local.properties`: if the primary has one, **copy** it into the lane (Gradle needs
  `sdk.dir`; it is gitignored). Never copy a release keystore or signing properties.
- `[ -f package.json ] && npm ci` (not `npm install`: the lockfile is committed).
- Run the gates with the `Running the gates` block below.

**A gate failing here stops the whole run.** It is `origin/main` that is broken, not any ticket,
and branching further tickets off it compounds the problem. `GATES: NOT YET ESTABLISHED` does not
stop the run: pre-scaffold tickets (spikes, the scaffold itself, docs) proceed and say so.

Record `worktree`, `runBranch`, `primary` and `gatesAtStart` at the top level of the state file.
Every phase prompt from here on gets `$RUN_WT` as its repo root, never `$PRIMARY`.

**Under `--resume`,** do not create a new lane or mint a new stamp. Read `worktree`, `runBranch`
and `stamp` from the chosen state file. If the directory is gone, the branches still exist in the
primary's git dir, so recreate the checkout over the existing branch:

```bash
git -C "$PRIMARY" worktree add "$RUN_WT" "$RUN_BRANCH"
cd "$RUN_WT" && { [ -f package.json ] && npm ci || true; }
```

Then check out the in-progress ticket's `branch` and continue from its recorded `phase`. If
`$RUN_BRANCH` no longer exists either, the run cannot be resumed: say so and stop.

### Step 0d: Resyncing the lane between tickets

After each ticket reaches `done` or `blocked`, before the next ticket's Phase 1:

```bash
git -C "$RUN_WT" status --short   # must be empty; otherwise block, do not reset over it
git -C "$RUN_WT" checkout "$RUN_BRANCH"
git -C "$RUN_WT" fetch origin
git -C "$RUN_WT" reset --hard origin/main
```

`reset --hard` is safe here and only here: the lane is clean and this branch is the run's own. A
non-empty `status` means the previous ticket did not finish cleanly; resetting would destroy work.
A blocked ticket's local branch survives the reset, because `reset` moves only `$RUN_BRANCH`.

Then read: `AGENTS.md` (non-negotiables 1-14, verification rules 15-18, Known state),
`CONTEXT.md`, `.claude/linear.md`, and the commands this pipeline delegates to: `start-issue.md`,
`ship.md`, `finish.md`, `check-constraints.md`, `critique.md`. Call them; do not reimplement
them here. `verify.md` is the interactive check and is **not** used by this command. If a
delegated command file does not exist yet, say so in the first message and follow its intent
from this file's phase prompt; do not invent its contents.

## Running the gates

Every phase that runs gates uses this logic and records what it printed verbatim:

```bash
if [ ! -f package.json ]; then
  echo "GATES: NOT YET ESTABLISHED (no package.json)"
else
  LOGDIR=$(mktemp -d); echo "gate logs: $LOGDIR"
  SCRIPTS=$(jq -r '.scripts // {} | keys[]' package.json)
  for s in test typecheck lint; do
    if grep -qx "$s" <<<"$SCRIPTS"; then npm run "$s" >"$LOGDIR/$s.log" 2>&1 \
      && echo "GATE $s: pass" || echo "GATE $s: FAIL"
    else echo "GATE $s: MISSING"; fi
  done
  if [ -x android/gradlew ]; then
    (cd android && ./gradlew testDebugUnitTest) && echo "GATE gradle unit: pass" || echo "GATE gradle unit: FAIL"
  else echo "GATE gradle unit: MISSING"; fi
fi
```

The canonical list is `AGENTS.md`'s "Quality gates" block; when the scaffold rewrites it, follow
the new list and fix this block in the same PR. On a failure, quote the failing log's tail in
the phase summary; the temp directory does not outlive the session. The release build
(`./gradlew assembleRelease`) runs in Verify, and in Ship when the diff touches `android/`,
Gradle files, `package.json` or the lockfile.

**Interpretation.** Pre-scaffold: `GATES: NOT YET ESTABLISHED`, never a pass. Post-scaffold: any
`FAIL` or `MISSING` is a failure. A `MISSING` gate that `AGENTS.md` names is drift and is reported
by name.

**Dependency check (rule 14).** When the diff touches `package.json`, the lockfile or any Gradle
file, also print the resolved Gradle dependency tree
(`cd android && ./gradlew :app:dependencies --configuration releaseRuntimeClasspath`) and fail on
any Google Play Services, Firebase, Crashlytics or other non-free artifact. `package.json` alone
is not the dependency set.

## Phase 0: Pre-flight triage, before any ticket's Phase 1

Without this, "needs a decision" is discovered after a branch exists and Linear says In Progress.

1. Fetch every ticket in scope with `get_issue`.
2. Spawn **one** fresh subagent for the whole batch, read-only:

   > For each of these tickets, in `<run-worktree>`: fetch the full description with
   > `get_issue`, including any "Decisions" section, which records answers the owner already
   > gave. Read `srs.md` and `CONTEXT.md` and every file the ticket names; do not judge by title.
   > Return a table with ticket id, kind (feature / fix / spike / docs), judgment (simple /
   > complex / needs-decomposition), the `srs.md` requirement or spike ID it closes, whether it
   > needs the reference device (every spike does; so does any change whose acceptance is a
   > device behaviour), whether it needs Obsidian with the plugin on the phone (bridge checks),
   > whether it needs plugin-side work tracked as NRL-130, and whether it would amend `srs.md`.
   > Flag inter-ticket dependencies (a feature whose design hangs on an unanswered spike is
   > blocked by that spike) and file overlap for the given order.
   >
   > For every open question the Decisions section does not answer, give the question, **your
   > recommended answer, and one line of reasoning**. Prefer the answer that matches `srs.md`,
   > the non-negotiables and the owner decisions log. Mark a question `unresolvable` only if no
   > defensible default exists: the ticket contradicts itself or `srs.md`, it needs product intent
   > with nothing to infer it from, it cannot be finished inside this repo, or it needs hardware
   > this machine lacks (a second phone, a non-GrapheneOS device, iOS for R-C03). Make no changes.

3. Fill in the `tickets` array, every in-scope ticket `pending` at phase `start`. Do not
   reinitialise the file; Step 0c's top-level fields stay. For each recommendation, write it into
   `clarification` with `decidedBy: "pipeline"` and post it with `save_comment` as "Decided by
   /run-tickets (owner may override): <question> -> <answer>, because <reason>." For each
   `unresolvable` ticket, set `status: "blocked"` with the question as `blockedReason`.
4. **Order:** a spike runs before any in-scope ticket that depends on its answer. If the spike
   blocks, the dependent ticket blocks with `blockedReason: "waits on <spike ID>"`.
5. Print the triage table and the decisions in **one** message, then continue immediately with
   Phase 1 for the first non-blocked ticket. Do not wait for a reply.

Special cases:

- **No requirement or spike ID and no clear acceptance criteria:** block it.
- **The scaffold ticket** establishes the gates. Its acceptance includes rewriting `AGENTS.md`'s
  "Quality gates" and `CONTEXT.md`'s "Planned source layout" with the real commands and tree, and
  correcting the `Running the gates` block and `worktrees.md`'s `applicationId` extraction if
  they do not match. Plan must include those edits.
- **A ticket that would amend `srs.md`** needs an ADR per `AGENTS.md`. Record that in
  `clarification` so Plan requires one; both ship in the ticket's own PR. Roadmap findings that
  propose amendments (see `CONTEXT.md`) are not applied by a ticket that merely mentions them.
- **A device-needing ticket under `--no-device`, or with no device attached at triage:** do not
  block yet; a device may be attached by the time it runs. Implement and Verify decide.

## The 7 phases

| # | Phase | Delegates to | What the fresh subagent does |
|---|---|---|---|
| 1 | Start | `start-issue.md` | Branch off `origin/main` inside the lane, Linear to In Progress, snapshot the issue into state |
| 2 | Plan | this file | Write the plan using Phase 0's decisions; new ambiguity is decided or blocks |
| 3 | Implement | this file | Reproduce first for bugs; run the probe for spikes; then fix or record; then run the gates |
| 4 | Ship | `ship.md` | Gates, `check-constraints`, `critique`, commit, push, PR against `main` |
| 5 | Verify | this file | Automated: gates on the PR head, the acceptance inputs, scripted on-device checks when the phone is free |
| 6 | Merge | this file | Squash-merge once Verify recorded `pass` (skipped with `--no-merge`) |
| 7 | Finish | `finish.md` | Linear to Done, lane resynced, merged `main` installed if the phone is free, docs corrected. **Not** the lane's removal, which is Step 8 |

**Sequencing is not optional.** Tickets run one at a time through phase 7 before the next one's
phase 1. Branches come off `origin/main`, which only carries ticket N's change once ticket N's
Merge has landed it.

## State file: `$STATE_FILE`

Machine-local, gitignored (`.claude/pipeline-state*.json`), never committed. Always
`$PRIMARY/.claude/pipeline-state.<stamp>.json`, one file per run, in the primary. It lives there
because the lane is removed at Step 8 and the state file is the run's record.

```json
{
  "runId": "2026-10-02T14:00:00Z",
  "stamp": "20261002-140000",
  "primary": "/home/joshshearer/Documents/Dev/Read-Me",
  "worktree": "/home/joshshearer/Documents/Dev/Read-Me-run-20261002-140000",
  "runBranch": "run/20261002-140000",
  "worktreeRemoved": false,
  "baseBranch": "main",
  "gatesAtStart": "GATES: NOT YET ESTABLISHED",
  "merge": true,
  "device": true,
  "keepWorktree": false,
  "heartbeat": "2026-10-02T14:05:00Z",
  "tickets": [
    {
      "id": "REA-3",
      "title": "SPIKE-03: Intl.Segmenter on Hermes",
      "requirement": "SPIKE-03",
      "type": "spike",
      "needsDevice": true,
      "reproduction": null,
      "descriptionSnapshot": "<full issue body, captured once in phase 1>",
      "branch": "spike/rea-3-intl-segmenter-hermes",
      "phase": "implement",
      "status": "in_progress",
      "clarification": { "question": null, "answer": null, "decidedBy": null },
      "planNote": null,
      "reproConfirmed": false,
      "archive": null,
      "implementationSummary": null,
      "prNumber": null, "prUrl": null, "commitSha": null,
      "gates": null, "device": null, "ci": null,
      "verifyVerdict": null, "verifyNotes": null,
      "blockedReason": null,
      "history": [{ "phase": "start", "at": "2026-10-02T14:01:00Z", "result": "branch created" }]
    }
  ]
}
```

`status`: `pending` | `in_progress` | `blocked` | `done`.
`phase`: `start` | `plan` | `implement` | `ship` | `verify` | `merge` | `finish`.
`type`: `feature` | `bug` | `spike` | `docs`.
`verifyVerdict`: `null` | `pass` | `fail`. It records the **automated** Verify only.
`device`: the verbatim device line, e.g. `DEVICE: RAN (<serial>, build <sha>): <results>` or
`DEVICE: NOT RUN (<reason>)`.
`clarification.decidedBy`: `null` | `"owner"` (from the ticket's Decisions section) | `"pipeline"`.
`archive`: `$ARCHIVE_DIR/<ID>/` when the ticket produced raw measurements.
`worktree` is the **only** path cleanup may remove. Timestamps come from `date -u +%FT%TZ`, never
from a guess.

## Taking the phone

Every phase that touches the device uses exactly this, and never waits:

```bash
N=$(adb devices | awk 'NR>1 && $2=="device"' | wc -l)
if [ "$N" -ne 1 ]; then echo "DEVICE: NOT RUN ($N devices attached; need exactly one)"
elif ! mkdir "$DEVICE_LOCK" 2>/dev/null; then
  echo "DEVICE: NOT RUN (lock held by $(head -2 "$DEVICE_LOCK/owner" 2>/dev/null | tr '\n' ' '))"
else
  printf '%s\nbranch=%s commit=%s at=%s purpose=run %s\n' "$(git rev-parse --show-toplevel)" "$(git branch --show-current)" \
    "$(git rev-parse --short HEAD)" "$(date -u +%FT%TZ)" "$RUN_ID" > "$DEVICE_LOCK/owner"
  echo "DEVICE: LOCK TAKEN"
fi
```

Also `DEVICE: NOT RUN` when `--no-device` was passed, or when `npm run device:install` /
`npm run device:smoke` do not exist (pre-scaffold). After installing, write
`$ARCHIVE_DIR/device-installed-from` in the format `worktrees.md` describes and say in the report
that this run replaced whatever build was on the phone. Release with `rm -rf "$DEVICE_LOCK"` as
soon as the checks finish, including on failure. Never remove a lock whose `owner` is not this
run.

Bridge checks additionally need Obsidian with the `local-tts-reader` plugin on the phone, driven
over CDP via `adb forward ... localabstract:webview_devtools_remote_<pid>` as the plugin's
`AGENTS.md` records. If Obsidian or the plugin is absent, record that bridge check as NOT RUN with
the reason. Never install, enable or reconfigure the plugin from here.

**Privacy while measuring.** Probes and smoke scripts read logcat filtered to the app's own tags.
A probe that captures item text into the archive breaks non-negotiable 1 as surely as the app
logging it; test inputs are synthetic text written for the probe, never the owner's library.

## Phase subagent prompts

Substitute the literal absolute paths Step 0 resolved:

| Placeholder | Value |
|---|---|
| `<run-worktree>` | `$RUN_WT`, the lane. **The repo root every phase works in.** |
| `<state-file>` | `$STATE_FILE` |
| `<run-branch>` | `$RUN_BRANCH` |
| `<device-lock>` | `$DEVICE_LOCK`, i.e. `$PRIMARY/.claude/device.lock` |
| `<archive>` | `$ARCHIVE_DIR/<ID>/` |

**1. Start** - "Read `.claude/commands/start-issue.md` and follow it for `<ID>` in
`<run-worktree>`, non-interactively; the ticket is already chosen. The state file is
`<state-file>`.

You are in a disposable run lane, not the primary. `main` is checked out elsewhere; do not try to
check it out here. `<run-branch>` is the base and sits at `origin/main`: confirm that
(`git fetch origin && git rev-parse HEAD origin/main` agree) and branch from it, using the kind
`.claude/linear.md` gives (`feature/`, `fix/` or `spike/rea-<N>-<slug>`). Skip any step that
creates a worktree or installs on the phone.

Write `title`, `requirement`, `type`, `needsDevice`, `descriptionSnapshot` and `branch` into this
ticket's state entry. Set Linear to In Progress with `save_issue` (`id`, `state: \"In Progress\"`;
the parameter is `state`, never `status`) and read it back. Set `phase: \"plan\"`,
`status: \"in_progress\"`, append history. If a branch collision or anything else needs a decision
`start-issue.md` cannot make, set `status: \"blocked\"` with `blockedReason` and stop. Do not
guess."

**2. Plan** - "You are in `<run-worktree>`. Read `<ID>`'s `descriptionSnapshot`, `clarification`
and `reproduction` from `<state-file>`. Earlier tickets in this run may have moved the code: read
the current tree, not the ticket's line numbers. Write a concise ordered `planNote` naming real
files and functions and incorporating every recorded decision. Name the non-negotiables (1-14)
the change touches and how the plan keeps each. If a genuinely new ambiguity appears that Phase 0
missed, decide it the way Phase 0 would, record it in `clarification` with
`decidedBy: \"pipeline\"`, and post it as a 'Decided by /run-tickets' comment. Only if no
defensible default exists, set `status: \"blocked\"`,
`blockedReason: \"Unanticipated by pre-flight: <question>\"`, and stop.

For a spike: the plan names the exact measurement, the device and build it runs on, the
pass/fail threshold `srs.md` implies, and where raw output goes (`<archive>`). For anything that
amends `srs.md`: the plan includes an ADR in `docs/adr/` in `NNNN-kebab-title.md` format,
numbered after the highest existing one. Set `phase: \"implement\"`."

**3. Implement** - "You are on `<branch>` in `<run-worktree>`. Read `<ID>`'s
`descriptionSnapshot`, `planNote` and `reproduction` from `<state-file>`. Do not touch any
directory outside this lane except `<archive>` and `<device-lock>`.

**If `type` is `bug`, reproduce it before changing anything** (rule 16). A device behaviour is
reproduced on the device: take the phone with the `Taking the phone` block, install the
unmodified build, drive it over adb, record what you observed, release the lock. If the phone is
unavailable, block with that reason; do not fix a device bug you have not seen. A logic bug is
reproduced by running the real module against the real input from the session scratchpad, never
by reasoning. Set `reproConfirmed: true` only when you saw the failure yourself; otherwise set
`status: \"blocked\"` with what you tried and stop.

**If `type` is `spike`,** write the probe in the session scratchpad or under `<archive>`, not in
the repo tree unless the ticket's Decisions say the owner keeps it. Take the phone, run the probe
on the reference device, release the lock. Save raw output verbatim under `<archive>`. If the
phone is unavailable, block: a spike answered without the device is not an answer. Then record
the answer in `srs.md`'s Spikes entry: the question, the answer, the date, the device and build,
and the numbers, every one of which must appear in the archived raw output (rule 17). If the
answer changes a design decision, write the ADR and amend the affected requirement. A negative
answer is a valid answer.

Otherwise implement exactly what the acceptance criteria describe, honouring any Out of Scope
section. Write the regression tests first and **confirm they fail against the unchanged code**
before claiming they verify anything; guard tests that pin already-correct behaviour pass both
before and after and are labelled as guards. Never weaken an existing test. Keep every
non-negotiable the plan named; in particular no item text and no bridge token in any log, no
network call outside `Fetcher`, rate applied once, positions as character offsets, and no
non-free dependency.

Run the gates yourself with the `Running the gates` block. Write a 3 to 6 sentence
`implementationSummary` listing every deviation from the plan and every known miss, set
`phase: \"ship\"`. Do not commit, push, or open a PR."

**4. Ship** - "Read `.claude/commands/ship.md` and follow it for the current branch in
`<run-worktree>`, skipping any install-on-device step. PR base is `main`. Use
`implementationSummary` for the PR body's approach section.

Run `/check-constraints`. A BLOCK is not overridable: set `status: \"blocked\"` with the findings
and stop without committing. Same for a `/critique` BLOCK. With no human reviewing the diff,
treat any finding where **item text or the bridge token can reach a log, a network call appears
outside `Fetcher`, bridge input is read before the token check, rate is applied twice, a position
is stored as a sentence index, or a non-free dependency enters the tree** as must-fix: fix it with
a test that fails first, re-run the gates, then commit. Lower findings go in the PR body as known
leftovers.

The PR body carries the gates output verbatim (or `GATES: NOT YET ESTABLISHED`), a literal
`NOT VERIFIED ON DEVICE` line, and an on-device test plan: exact steps over the share sheet,
Trim, Reader, lock screen and bridge, with what to observe for each, so the owner can check it
while using the app. If a PR body must change after creation, do not use `gh pr edit --body`: it
can exit 0 and leave the body unchanged. Use `gh api -X PATCH repos/JoshShearer/Read-Me/pulls/<n>
-f body=...` and read the body back.

If a CI workflow exists you **may** read its conclusion once with the snippet under `Reading a CI
conclusion` and **must not** wait on it; record what you saw, including \"not concluded\" or
\"CI: none\", in `ci`. Record `prNumber`, `prUrl`, `commitSha`, set `phase: \"verify\"`."

**5. Verify** - automated, in a fresh subagent that did not write the change.

"You are verifying PR `<prNumber>` for `<ID>` in `<run-worktree>`, on `<branch>`. Read
`descriptionSnapshot`, `planNote`, `implementationSummary` and `reproduction` from
`<state-file>`. You did not write this; find out whether it does what the acceptance criteria
say. Do not edit tracked files.

1. Confirm the tree is clean and `HEAD` equals `commitSha`. Run the gates with the `Running the
   gates` block, plus the release build when the scaffold exists, plus the dependency check if
   the diff touched dependencies. Record the output verbatim in `gates`.
2. Run every acceptance input the ticket and the PR's test plan name that can run off-device,
   including the original reproduction, against the real code. Compare actual to expected.
3. Take the phone with the `Taking the phone` block. If it is taken: `npm run device:install`
   from this lane, `npm run device:smoke`, then the ticket's own acceptance checks scripted over
   adb, and the original reproduction if it was a device bug. Release the lock. If not taken,
   record the `DEVICE: NOT RUN` line verbatim. Never uninstall or clear data; a signature-mismatch
   install failure is a fail with that reason.
4. For a spike: confirm every number in the `srs.md` diff appears in the raw output under
   `<archive>`, and that the recorded device and build match. If the phone is free, re-run the
   probe once and record whether the answer reproduced; a re-measure that contradicts the
   recorded answer is a fail.

`verifyVerdict: \"pass\"` requires: gates pass, or are `NOT YET ESTABLISHED` on a branch that
does not create them; every off-device probe matches; and every device check that ran passed.
**A device check that did not run is not a fail and not a pass**: record it as NOT RUN. For a
ticket whose acceptance can only be observed on the device, NOT RUN means
`verifyVerdict: \"fail\"`, `status: \"blocked\"`,
`blockedReason: \"device verification not run: <reason>\"`; merging it would claim something
nobody observed. On any other failure set `verifyVerdict: \"fail\"`,
`status: \"blocked\"`, and put the failing inputs with actual versus expected in `blockedReason`.
Do not attempt a fix. Write a short `verifyNotes`, append history, and on pass set
`phase: \"merge\"`."

On pass, the orchestrator posts a Linear comment with `save_comment` stating as separate points:
the gates result verbatim; the off-device probes and their results; the device line verbatim
(`VERIFIED ON DEVICE: <what was observed>, <serial>, build <sha>, <date>` only if it ran, else
`DEVICE: NOT RUN (<reason>)`); and the CI line.

On fail, the ticket is blocked with its PR left open. Continue with the next ticket.

**6. Merge** - done by the orchestrator. Skipped under `--no-merge`, which leaves the ticket
`done` at phase `verify` with its PR open. Only when `verifyVerdict` is `pass`:

```bash
gh pr view <prNumber> --json state,mergeable
gh pr merge <prNumber> --squash
gh pr view <prNumber> --json state,mergedAt,mergeCommit
gh api -X DELETE repos/JoshShearer/Read-Me/git/refs/heads/<branch>
git ls-remote --heads origin '<branch>'   # must print nothing
```

No `--delete-branch`: from a linked worktree it fails trying to check out `main` (which the
primary holds). The merge lands anyway, but the remote branch is left behind, so delete the ref
through the API and confirm. If the PR is already merged, record that and move on. If it is not
mergeable, block the ticket; do not resolve conflicts in this phase. Never merge a ticket whose
Verify did not record a pass, and never self-approve. Record the merge commit and set
`phase: \"finish\"`.

**7. Finish** - "Read `.claude/commands/finish.md` and follow it for the merged branch `<branch>`
in `<run-worktree>`, with these exceptions.

**Skip its worktree-removal step entirely.** It removes `Read-Me-rea-<N>`, which does not exist
here, and the lane is removed once for the whole run by Step 8. A phase that removed its own lane
would delete the checkout the next ticket needs.

**Do not check out `main`**; the primary holds it. Finish on `<run-branch>` reset to
`origin/main`:

```bash
git checkout <run-branch> && git fetch origin && git reset --hard origin/main
```

Verify the merge by content, not branch state: this repo squash-merges, so `git branch -d` says
'not merged' for work fully in `main`. Grep the resynced lane for a distinctive line the PR added,
then delete the local branch with `-D`. Set Linear to Done with `save_issue` (`id`,
`state: \"Done\"`) and read it back. If the ticket closed a requirement or answered a spike and
the PR did not already record it in `srs.md` or `AGENTS.md` Known state, make that doc edit on a
`docs/rea-<N>-finish` branch, open a PR, and squash-merge it the same way; never commit to `main`
directly. Only call a requirement met with a device observation named by date, device and build
(`srs.md` R-M14).

Then, if a scaffold exists and `--no-device` was not passed, take the phone with the `Taking the
phone` block, `npm run device:install` from the resynced lane so the owner's phone carries the
latest merged `main`, write the install marker, and release. If the phone is unavailable, skip
and name why; do not wait. Leave the lane on `<run-branch>`, clean and at `origin/main`. Set
`phase: \"finish\"`, `status: \"done\"`."

## Step 8: Clean up the lane, once, at the end of the run

Runs after the last ticket reaches `done` or `blocked`. Skipped under `--keep-worktree`, which
prints the path and says cleanup was skipped on purpose.

**Cleanup is conditional on nothing being lost.** A blocked ticket can leave work that never
reached origin: Implement blocks after reproducing a bug and before any commit, and that
reproduction is exactly what rule 16 says is expensive to obtain.

Measure, in the lane, and print what you measured:

```bash
cd "$RUN_WT"
git status --short                  # must be empty
git stash list                      # must be empty
git worktree list --porcelain       # confirm $RUN_WT is the tree you are about to remove
for B in <ticket branches from the state file>; do
  git rev-parse --verify "$B" >/dev/null 2>&1 || continue
  if git rev-parse --verify "origin/$B" >/dev/null 2>&1; then
    echo "$B unpushed=$(git rev-list --count "origin/$B..$B")"
  else
    echo "$B unpushed=NO-REMOTE merged=$(git merge-base --is-ancestor "$B" origin/main && echo yes || echo no)"
  fi
done
```

A branch with no remote is fine **only** if it is an ancestor of `origin/main`. Otherwise it is
unpushed work. (A squash-merged branch was already deleted with `-D` in Finish after its content
check.)

**All clean:**

```bash
cd "$PRIMARY"
git worktree remove "$RUN_WT"
git branch -D "$RUN_BRANCH"
git worktree prune
```

`worktree remove` without `--force`, deliberately: it refuses on a dirty tree, so it is a second
independent check. If it refuses after you measured clean, believe it and keep the lane.
`--force` is never correct in this step. Set `worktreeRemoved: true`.

**Anything not clean:** keep the lane and report the path, the dirty files verbatim, the stash
entries, and every branch with its unpushed commit count and subjects, then:

```
Lane kept: /home/joshshearer/Documents/Dev/Read-Me-run-20261002-140000
  branch fix/rea-19-... has 2 unpushed commits:
    abc1234 fix(fetcher): ...
    def5678 test(fetcher): ...
Reason: REA-19 blocked at implement; the work is not on origin.
To inspect:  cd <lane> && git log --oneline origin/main..
To discard:  git -C <primary> worktree remove --force <lane> && git -C <primary> branch -D run/<stamp>
```

Never run that discard command yourself. A kept lane is not a failure of the run and changes no
ticket's status. Do not push a blocked ticket's work to make cleanup unconditional: a
half-finished branch on origin is worse than a lane on disk.

## When something needs a human

Nothing waits. Each of these blocks the ticket it happens in, records why in `blockedReason`, and
the run continues:

- Phase 0 or Plan finds a question with no defensible default
- A bug that cannot be reproduced, or a device bug with no phone available
- A spike with no phone available
- A device-only acceptance whose device check did not run
- A `check-constraints` BLOCK or a `critique` BLOCK
- A regression test whose core cases pass against the unchanged code
- Automated Verify fails
- A merge conflict with `main`
- An install that fails on a signature mismatch
- Work that needs the plugin side (NRL-130) or hardware this machine lacks
- A branch or issue collision `start-issue.md` flags

A blocked ticket keeps its branch and any open PR. Its Linear status stays In Progress and the
orchestrator posts the `blockedReason` as a comment.

A decision the pipeline took on the owner's behalf is **not** a block. It is recorded in state,
posted to Linear, and listed in the end-of-run report so it can be overridden.

## End-of-run report

When every ticket is `done` or `blocked` and Step 8 has run, print one message:

- A table: ticket, PR, merge commit, gates line, device line, one line on what changed.
- **Device:** for each ticket, `VERIFIED ON DEVICE` only where a device check ran and observed it;
  every other ticket says `DEVICE: NOT RUN (<reason>)`. Which build the phone now runs, and
  whether this run replaced another lane's build. If the lock was held by someone else, name the
  holder.
- `GATES: NOT YET ESTABLISHED` wherever that was the result, so a pre-scaffold run never reads as
  a green one.
- Any PR whose CI went red or never concluded, with the run URL, and the line: run
  `/test-issue <ID>` to triage it. This run reads a conclusion at most once and never waits.
- Spike answers recorded, each with its archive path.
- **The lane**: its path and whether Step 8 removed it; if kept, the reason and the unpushed or
  dirty items verbatim.
- Every decision with `decidedBy: "pipeline"`, with a link to its Linear comment.
- Every blocked ticket with its reason, branch and PR.
- Follow-up tickets filed during the run, and known leftovers from each PR. Anything that belongs
  to the plugin is named as such for the owner to file in the plugin's workspace.
- Requirement status changes, with the evidence for each.
- This run's state file path, and **any other run's state file live alongside it**.
- **Whether every Linear write actually landed.** Name any comment or status change that was
  printed instead of posted, and why. A run whose comments all silently failed must not close
  with a report that looks like one where they all succeeded.

## Example usage

```
/run-tickets REA-3
```
One spike. Needs the phone attached and the device lock free, or it blocks with that reason.

```
/run-tickets REA-1,REA-2,REA-3 --no-merge
```
Three tickets, each left as an open PR.

```
/run-tickets all --no-device
```
Everything assigned and open, without touching the phone; device-only tickets block.

```
/run-tickets --resume 20261002-140000
```
Continues that run, recreating its lane from `worktree` and `runBranch`.

## Error handling

| Scenario | Action |
|---|---|
| No Linear tool in the available list | Continue git-only, print what would have been sent, record it in state, and **say so in the end-of-run report**. |
| A named Linear operation is missing but others resolve | The operation was renamed. Find the equivalent (`save_comment`, not `create_comment`) and use it. Do not fall through to git-only; that hides a fixable name behind a success report. |
| A status write reads back wrong | Confirm the state name with `list_issue_statuses`, retry once, then block the ticket and continue. |
| Any Linear call routed to `linear-nrl` | Wrong workspace. Stop the call; nothing for this repo goes there. |
| A permission prompt appears mid-run | The run was launched wrong. Stop and report which command was gated. Never edit `opencode.json` or Claude Code settings from inside a run. |
| No `package.json` | Pre-scaffold. `GATES: NOT YET ESTABLISHED`; never invent a pass. |
| A gate `AGENTS.md` names is missing post-scaffold | Gate drift. Fail the phase and name it. |
| `origin/main` fails its gates at Step 0c | Stop the run and report it. Check that `npm ci` ran and `local.properties` was copied before blaming the code. |
| `adb devices` shows 0 or 2+ devices | `DEVICE: NOT RUN` with the count. Never pick one of two. |
| Device lock held | `DEVICE: NOT RUN (lock held by <owner>)`. Never wait, never break it. |
| A device lock whose owner is this run, left behind by a crash | Release it, and say so in the report. |
| Install fails on a signature mismatch | Block the ticket. Never uninstall or clear data. |
| `gh` auth expires mid-run | Stop the run and report the step. Every later ticket would fail the same way. |
| `gh pr merge` errors with "'main' is already used by worktree" | The merge landed. Confirm with `gh pr view`, delete the remote ref via `gh api`, continue. |
| `gh pr edit --body` exits 0 | Do not trust it. Read the body back; use `gh api -X PATCH` if it did not change. |
| Another run's state file is present and in progress | Expected. Name it, compare ticket sets read-only, block only the shared tickets. Never write it. |
| `$STATE_FILE` already exists at Step 0a | Same-second stamp collision. Stop and re-run. Never delete or adopt the existing file. |
| `git worktree add` fails because `$RUN_WT` or `$RUN_BRANCH` exists | A previous run left it. Do not remove, reuse or `-D` it. Report which state file references it and stop. |
| The lane's `HEAD` moved under you | Something else is working in the lane. Stop, report expected versus found branch, do not commit. |
| A `-run-*` lane belongs to no live run | Report it as an orphan with the matching state file's `runId`. Do not remove it; `--resume <stamp>` can still pick it up. |
| A PR's CI goes red | Record it and carry on; there is no branch protection. Name it in the report with `/test-issue <ID>`. Do not run `/test-issue` from inside the run: it waits on a conclusion. |
| A phase needs a decision not covered above | Decide it with a recorded default if one is defensible, otherwise block the ticket. Never wait. |

## Configuration

| Setting | Value |
|---|---|
| **Primary repo** | `git rev-parse --show-toplevel`, then strip a `-rea-*` or `-run-*` suffix. Assert `--git-dir` prints `.git` |
| **Run lane** | `${PRIMARY}-run-<YYYYMMDD-HHMMSS>` on `run/<stamp>`, from `origin/main` with `--no-track`, removed at Step 8 only if nothing would be lost |
| **State, device lock, archive** | `$PRIMARY/.claude/{pipeline-state.<stamp>.json, device.lock/, scratch/}`. All in the primary, all gitignored |
| **Base branch** | `main`. The lane never checks it out |
| **Remote** | `git@github.com:JoshShearer/Read-Me.git` (public; `gh repo view`, 2026-10-04) |
| **Tracker** | Linear workspace `read-me-tts`, MCP server `linear-rea`, team key `REA` |
| **Gates** | `AGENTS.md` "Quality gates". None exist until the scaffold; then run once at Step 0c and in every Implement, Ship and Verify |
| **Device** | One phone, the reference device in `srs.md`. Exactly one attached and the lock free, or `DEVICE: NOT RUN` |
| **CI** | None yet. When one exists: read once, never waited on |
| **Permission gate** | Four commands are `ask` in `opencode.json`. Launch headless or in a Claude Code bypass session |
| **Human gate** | None. The owner tests by using the app and files new tickets for what they find |

## Reading a CI conclusion

Not applicable until `.github/workflows/` exists. Then: **`gh run list` reports the workflow
name, never a job name**, and has no `jobs` field, so a `select(.name == "<job>")` filter matches
nothing, prints nothing and exits 0. A loop built on that silence never breaks. Select on the
commit and the workflow name, print every matching row (a PR branch gets a `push` run and a
`pull_request` run that do not conclude together), and read it **once**:

```bash
WF="<workflow name: from the workflow file's top-level name:>"
SHA="$(git rev-parse HEAD)"
gh run list --branch "$(git branch --show-current)" --limit 20 \
  --json headSha,workflowName,event,status,conclusion,databaseId \
| jq -r --arg sha "$SHA" --arg wf "$WF" '
    [ .[] | select(.headSha == $sha and .workflowName == $wf) ] as $runs
    | if ($runs | length) == 0 then "CI: no run recorded yet for \($sha)"
      else ($runs | map("\(.event) \(.status) \(.conclusion // "-") \(.databaseId)") | join("  |  "))
           + (if ($runs | all(.status == "completed")) then "  -> all concluded"
              else "  -> still running; do NOT wait" end)
      end'
```

A job's own conclusion is reachable only per run: `gh run view <id> --json jobs`. If nothing has
concluded, that is the answer: record `still running` and move on. When the CI workflow lands,
fill in `WF` here and confirm the snippet against a real run (rule 18).
