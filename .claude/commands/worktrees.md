---
description: List, inspect, create and remove the interactive worktree pool, including file-overlap checks and ownership of the single attached phone (the device slot).
---

Conventions: `.claude/linear.md`. Rules and gates: `AGENTS.md`. Architecture: `CONTEXT.md`.

## Lane rule

This command owns the **interactive** pool only: siblings of the primary repo named
`Read-Me-rea-{N}`.

`treehouse` owns the gnhf / parallel-agent pool. Never point both at the same directory, and
never create a worktree here for a gnhf run. Per the global rules a gnhf run belongs on a
throwaway worktree on a scratch branch with no push credentials; if someone asks for that here,
say no and point at treehouse. A sibling that matches neither pattern below is left alone and
listed only as "unmanaged".

**`Read-Me-run-<stamp>` is a `/run-tickets` lane, not unmanaged.** That command creates one per
run and removes it itself at the end. List it as `run lane` with its branch and whether it is
dirty, and **refuse to remove it**: if a run is live, removing its checkout destroys the tickets
in flight, and if the lane outlived its run, it is on disk precisely because cleanup found
unpushed commits or a dirty tree. Point at `$PRIMARY/.claude/pipeline-state.<stamp>.json` with
the matching stamp; its `worktree` field names the lane and its ticket entries say why it was
kept.

## Resolve the primary

From wherever you are:

```bash
PRIMARY="$(git rev-parse --show-toplevel)"
[[ "$(basename "$PRIMARY")" =~ -rea-[0-9].*$ ]] && PRIMARY="${PRIMARY%-rea-*}"
[[ "$(basename "$PRIMARY")" =~ -run-[0-9]+-[0-9]+$ ]] && PRIMARY="${PRIMARY%-run-*}"
git -C "$PRIMARY" rev-parse --git-dir   # must print ".git"; a worktrees/ path means the strip failed
echo "primary=$PRIMARY"
```

The `-rea-` pattern is `-rea-[0-9].*$`, not `-rea-[0-9]+$`: a worktree covering several issues
is named for all of them (`Read-Me-rea-12-13`), `+$` does not match that, and the strip then
silently leaves `$PRIMARY` pointing at the worktree so every glob below expands against the
wrong parent. That happened in the sibling plugin repo.

## The device slot

There is one attached phone (the reference device in `srs.md`). Installing a build replaces the
app on it, so **exactly one worktree at a time can hold the build the phone is actually
running**. Two sessions installing in turn give the worst failure mode: an on-device check that
passes or fails against someone else's code, with no error anywhere. `AGENTS.md` rule 15 means
every worktree needs that slot before it can claim a user-facing change works; this command's
job is to make the holder visible and the handover explicit.

**The lock** is `$PRIMARY/.claude/device.lock/`, taken atomically with `mkdir` (gitignored). It
is resolved against `$PRIMARY` deliberately: a lock inside a fresh worktree is free by
construction and would guard nothing. Whoever takes it writes `$PRIMARY/.claude/device.lock/owner`:

```
<absolute worktree path>
branch=<name> commit=<short hash> at=<ISO time> purpose=<interactive|run <runId>>
```

The lock is held for as long as the phone must not change under you: through the install **and**
the checks or manual test that follow. It is released by `rm -rf "$PRIMARY/.claude/device.lock"`
by the holder only. A lock whose holder is gone is reported with its age and owner; it is never
broken silently.

**The installed-from marker** is `$PRIMARY/.claude/scratch/device-installed-from`, same format
plus `tree=clean|dirty`, written by `npm run device:install` after every successful install. Its
commit is the APK stamp's (the commit the build came from), not HEAD's. It outlives the lock, so it says which build the phone is
running between sessions.

**The marker is a declaration, not proof.** A session elsewhere may have installed without
writing it. The check that needs no cooperation is a byte comparison of the APK on the phone
against each worktree's build output. It needs the scaffold (an `android/` tree and an
`applicationId`) and exactly one device:

```bash
[ -d "$PRIMARY/android" ] || { echo "DEVICE SLOT: no app yet (pre-scaffold)"; exit 0; }
N=$(adb devices | awk 'NR>1 && $2=="device"' | wc -l)
[ "$N" -eq 1 ] || { echo "DEVICE SLOT: $N devices attached; need exactly one"; exit 0; }
APP_ID=$(grep -oE 'applicationId[ =]+"[^"]+"' "$PRIMARY/android/app/build.gradle"* | head -1 | grep -oE '"[^"]+"' | tr -d '"')
SCRATCH=$(mktemp -d)
P=$(adb shell pm path "$APP_ID" | sed -n 's/^package://p' | grep base.apk)
[ -n "$P" ] || { echo "DEVICE SLOT: $APP_ID not installed"; exit 0; }
adb pull "$P" "$SCRATCH/installed.apk" >/dev/null
shopt -s nullglob
for d in "$PRIMARY" "${PRIMARY}"-rea-* "${PRIMARY}"-run-*; do
  for apk in "$d"/android/app/build/outputs/apk/*/*.apk; do
    if cmp -s "$apk" "$SCRATCH/installed.apk"; then echo "OWNS SLOT: $d ($apk)"; fi
  done
done
rm -rf "$SCRATCH"
```

The `-run-*` glob is in the loop because `/run-tickets` installs from its lane; leaving it out
attributes the phone to nobody. The `applicationId` extraction is a best guess written before the
scaffold exists: verify it against the real Gradle file once it lands (rule 18) and fix this
block if it is wrong. No match at all means the phone runs a build no worktree has on disk; say
so.

Report the lock, the marker and the byte comparison separately. When the marker and the byte
comparison disagree, the byte comparison wins and the marker is stale; say both.

## Input

`$ARGUMENTS` selects the subcommand.

| Subcommand | Purpose |
|---|---|
| *(none)* | List worktrees, with device-slot ownership |
| `REA-12`, `rea-12`, `12` | Inspect one in detail |
| `conflicts` | File overlap between active branches |
| `slot` | Who holds the phone, and nothing else |
| `create REA-12` | Create a worktree and make it usable |
| `device REA-12` | Take the device slot for that worktree and install its build |
| `release` | Release the device slot this session holds |
| `remove REA-12` | Remove one, with safety checks |

Parse: empty -> List. `REA-\d+` / `rea-\d+` / bare `\d+` -> Inspect. Otherwise match the keyword.

## Naming

```
~/Documents/Dev/
|-- Read-Me/                 <- primary workspace
|-- Read-Me-rea-12/          <- interactive worktree for REA-12
|-- Read-Me-rea-19/          <- interactive worktree for REA-19
`-- Read-Me-run-<stamp>/     <- /run-tickets lane (not managed here)
```

## Mode: List

```bash
shopt -s nullglob
for dir in "${PRIMARY}"-rea-*/; do
  echo "=== $(basename "$dir") ==="
  [ -f "$dir/.worktree-meta.json" ] && cat "$dir/.worktree-meta.json"
  BRANCH=$(git -C "$dir" branch --show-current 2>/dev/null || echo detached)
  DIRTY=$(git -C "$dir" status --short 2>/dev/null | wc -l | tr -d ' ')
  AHEAD=$(git -C "$dir" rev-list --count origin/main..HEAD 2>/dev/null || echo '?')
  if [ -f "$dir/package.json" ]; then DEPS=$([ -d "$dir/node_modules" ] && echo installed || echo MISSING); else DEPS=pre-scaffold; fi
  echo "  branch=$BRANCH ahead=$AHEAD dirty=$DIRTY deps=$DEPS"
done
for dir in "${PRIMARY}"-run-*/; do
  echo "=== run lane: $(basename "$dir") ==="
  echo "  branch=$(git -C "$dir" branch --show-current 2>/dev/null || echo detached)" \
       "dirty=$(git -C "$dir" status --short 2>/dev/null | wc -l | tr -d ' ')"
done
BRANCH=$(git -C "$PRIMARY" branch --show-current)
[[ "$BRANCH" =~ ^(feature|fix|spike)/ ]] && echo "PRIMARY: $BRANCH"
cat "$PRIMARY/.claude/device.lock/owner" 2>/dev/null || echo "device lock: free"
cat "$PRIMARY/.claude/scratch/device-installed-from" 2>/dev/null || echo "no install marker"
git -C "$PRIMARY" worktree list --porcelain
```

Then run the byte comparison above. Any `worktree list` path missing from disk -> tell the user
to run `git worktree prune`.

Format as a table. `deps=MISSING` is worth flagging loudly: the gates in that worktree will fail
for a reason that has nothing to do with the code. `deps=pre-scaffold` is expected until the
scaffold ticket lands.

```
| Issue | Branch | Ahead | Dirty | Deps | Device |
|---|---|---|---|---|---|
| REA-12 | feature/rea-12-share-intake | 3 | 0 | installed | OWNS (lock held) |
| REA-3 | spike/rea-3-hermes-readability | 1 | 4 | MISSING | - |
| primary | main | - | 0 | installed | - |
```

None found:

```
No active worktrees. To create one:
  /worktrees create REA-12
```

## Mode: Inspect

```bash
D="${PRIMARY}-rea-<NUM>"
ls -d "$D" && cat "$D/.worktree-meta.json" 2>/dev/null
git -C "$D" branch --show-current
git -C "$D" diff --name-only "$(git -C "$D" merge-base origin/main HEAD)"
git -C "$D" log --oneline "$(git -C "$D" merge-base origin/main HEAD)"..HEAD
gh pr list --head "$(git -C "$D" branch --show-current)" --json number,title,state,url --limit 1
```

Linear status via the `get_issue` operation. **Do not hardcode a tool prefix**: resolve the real
name for the `linear-rea` server from your available tool list, as `.claude/linear.md` requires.
Never use `linear-nrl`; that is the plugin's workspace. Linear unreachable is never a blocker:
show the git data and say Linear was skipped.

Report path, branch, created time, Linear status and requirement or spike ID, open PR, changed
files, whether `node_modules` exists, and whether this worktree holds the device slot. Finish
with:

```
Actions
  Work here:    cd <path> && claude     then /start-issue REA-<NUM>
  Take phone:   /worktrees device REA-<NUM>
  Remove:       /worktrees remove REA-<NUM>
```

## Mode: Conflicts

```bash
shopt -s nullglob
for dir in "$PRIMARY/" "${PRIMARY}"-rea-*/; do
  NAME=$(basename "$dir")
  B=$(git -C "$dir" branch --show-current 2>/dev/null)
  [[ "$NAME" == "$(basename "$PRIMARY")" && ! "$B" =~ ^(feature|fix|spike)/ ]] && continue
  echo "--- $NAME ($B)"
  git -C "$dir" diff --name-only "$(git -C "$dir" merge-base origin/main HEAD)" 2>/dev/null
done
```

Build a file -> branches map and report any file appearing in two or more. The range above covers
committed, staged and unstaged work in one pass, which is the point.

**High-collision files.** Flag these loudly when two or more branches touch them, because a
conflict there is a correctness problem rather than a merge inconvenience. Paths below `src/` and
`android/` are the planned layout from `CONTEXT.md`; match by the module name until the scaffold
records the real tree, then correct this table.

| File | Why it collides |
|---|---|
| `srs.md` | The contract. Two branches recording a spike answer or a requirement status will silently pick one |
| `AGENTS.md`, `CONTEXT.md` | Gates, non-negotiables and Known state shrink or grow from both sides |
| `android/app/src/main/AndroidManifest.xml` | Permissions, `allowBackup`, the `TTS_SERVICE` query, the service type; one line dropped in a clean merge breaks a non-negotiable |
| `android/app/build.gradle*`, `package.json`, `package-lock.json` | Dependencies. A clean merge can add a non-F-Droid-clean transitive dependency (rule 14) |
| `ReadMeSpeech` module, `PlaybackService` | The JS-to-native contract and the queue that owns playback (rule 11) |
| `BridgeServer` | Hostile-input handling (rule 8); two edits to the read path can each keep the token-before-body order and break it together |
| `Fetcher` | The only network call site and its limits (rules 6, 7) |
| `Store` / schema | Kotlin owns the database; two migrations collide by version number |
| `src/segment/` | Positions are character offsets (rule 10); two edits can each preserve that and break it together |

No overlap -> `All clear - no file overlap between active branches.`

## Mode: Slot

Print the lock owner, the install marker and the byte comparison, and stop. Cheap, so it is safe
to run before any on-device check.

## Mode: Create

1. Parse the number. Refuse if `${PRIMARY}-rea-<NUM>` already exists.

2. If you are inside a worktree, say so and create from the primary instead. Nested worktree
   siblings break the `${PRIMARY}-rea-*` glob everything here depends on.

3. Create it:

```bash
git -C "$PRIMARY" fetch origin
git -C "$PRIMARY" worktree add --no-track "${PRIMARY}-rea-<NUM>" -b <kind>/rea-<NUM>-<slug> origin/main
```

Kind and slug per `.claude/linear.md`: `feature/`, `fix/` or `spike/`; slug lowercased from the
issue title, non-alphanumerics to `-`, near 50 chars. Slug unknown -> use `feature/rea-<NUM>` and
let `/start-issue REA-<NUM>` rename it in the new worktree. Cutting from `origin/main` rather than
the primary's `HEAD` keeps a primary that is behind or dirty out of the new branch.

4. Metadata:

```bash
printf '{\n  "issue": "REA-%s",\n  "created": "%s"\n}\n' "<NUM>" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  > "${PRIMARY}-rea-<NUM>/.worktree-meta.json"
```

5. **Machine-local files.** `android/local.properties` (the `sdk.dir` line) is gitignored and a
   Gradle build in the worktree needs it. If the primary has one, **copy** it (not symlink: a
   symlink edited in one tree changes all of them). Never copy a release keystore or signing
   properties into a worktree; if the scaffold needs signing for `device:install`, follow what
   `AGENTS.md` says then.

6. **Install dependencies**, only if `package.json` exists. Use `npm ci`, not `npm install`:
   the lockfile is committed and `ci` reproduces it exactly in a throwaway tree.

```bash
cd "${PRIMARY}-rea-<NUM>" && [ -f package.json ] && npm ci
```

A fresh worktree also has no Gradle build cache, so the first Gradle gate is slow. If `npm ci`
fails, report it: the worktree exists but is unusable, and say that plainly rather than letting
the next command fail confusingly.

7. Prove the worktree is usable before handing it over. Run the gates `AGENTS.md` lists, each
   only if it exists on disk (`jq -r '.scripts | keys[]' package.json`, `ls android/gradlew`). With
   no `package.json`, report `GATES: NOT YET ESTABLISHED` and do not invent a pass. Once the
   scaffold has landed, a gate `AGENTS.md` names that does not exist is drift: report it as a
   failure, not as "not established".

8. Do **not** install on the phone. Creating a worktree never takes the device slot. Report who
   holds it and require `/worktrees device REA-<NUM>` as a separate, deliberate act.

9. Run the conflicts check if other worktrees exist.

10. Report:

```
Worktree created: ../Read-Me-rea-<NUM>/
Branch: feature/rea-<NUM>-<slug>
npm ci: ok | skipped (pre-scaffold)     gates: <results> | GATES: NOT YET ESTABLISHED

Device slot: held by ../Read-Me-rea-12 (REA-12) since <time> | free
  This worktree cannot claim an on-device result until it takes the slot:
  /worktrees device REA-<NUM>

To start working:
  cd ../Read-Me-rea-<NUM> && claude
  # then: /start-issue REA-<NUM>
```

## Mode: Device (handover)

This is the only path in this command that may take the phone, and it refuses to do it silently.

1. Check prerequisites: `npm run device:install` exists (`jq -r '.scripts | keys[]' package.json`);
   `adb devices` shows exactly one device. Either missing -> say which and stop.

2. Determine the current holder: read `device.lock/owner`, the install marker, and run the byte
   comparison. If the requested worktree already holds the lock, say so and stop.

3. If the lock is held by anyone else, **stop and ask** with `AskUserQuestion`. Show the holder's
   path, branch, purpose, age, whether it has uncommitted work, and its open PR. A holder whose
   purpose is `run <runId>` is a live `/run-tickets` lane mid-check: recommend waiting, because
   taking the phone invalidates that run's on-device result. Only on explicit approval does the
   current holder's lock get removed, and say out loud which session lost it.

4. Take the lock and install:

```bash
mkdir "$PRIMARY/.claude/device.lock" || { echo "lock taken by someone else just now"; exit 1; }
cd "${PRIMARY}-rea-<NUM>"
LINE="branch=$(git branch --show-current) commit=$(git rev-parse --short HEAD) at=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
printf '%s\n%s purpose=interactive\n' "$(pwd)" "$LINE" > "$PRIMARY/.claude/device.lock/owner"
npm run device:install   # refuses an APK not stamped from HEAD; writes the installed-from marker
```

5. **Never wipe the app's data to get an install through.** If the install fails on a signature
   mismatch, stop and report it. `adb uninstall`, `pm clear` or a reinstall-with-wipe would delete
   the owner's library on their own phone (non-negotiable 3 keeps it app-private, so there is no
   copy elsewhere). That is the owner's call, never the command's.

6. Tell the user the lock stays held until `/worktrees release`, and that `/run-tickets` will
   record its device checks as NOT RUN while it is held.

## Mode: Release

Release only a lock whose `owner` names this worktree. Read it first; if it names another path,
show it and stop.

```bash
head -1 "$PRIMARY/.claude/device.lock/owner"
rm -rf "$PRIMARY/.claude/device.lock"
```

The install marker stays: it still describes what the phone runs.

## Mode: Remove

0. **Refuse a `Read-Me-run-*` lane.** It belongs to `/run-tickets`. Read the matching
   `$PRIMARY/.claude/pipeline-state.<stamp>.json` for the ticket that kept it and its
   `blockedReason`, and print the lane's unpushed commits. If the owner still wants it gone, they
   run the discard command `/run-tickets` printed at cleanup; this command does not do it for them.

1. Confirm the directory exists.

2. Safety checks:

```bash
D="${PRIMARY}-rea-<NUM>"
git -C "$D" status --short
git -C "$D" log --oneline @{u}..HEAD 2>/dev/null || git -C "$D" log --oneline "$(git -C "$D" merge-base origin/main HEAD)"..HEAD
git -C "$D" stash list
gh pr list --head "$(git -C "$D" branch --show-current)" --json number,state,mergedAt --limit 1
```

Plus `get_issue` for the Linear status (resolve the tool name; do not hardcode a prefix).

3. Present the findings and use `AskUserQuestion`. Warn on: uncommitted changes, stashes,
   unpushed commits, no merged PR, an issue not Done, **this worktree holding the device lock**,
   and **the phone running this worktree's build**. The last two matter because removing it
   leaves the phone on a build whose source no longer exists on disk, the hardest state to debug.
   Offer to release the lock first. For a spike worktree, also warn on probe output not yet
   archived under `$PRIMARY/.claude/scratch/`: the probe code is throwaway, its raw measurements
   are not (rule 17).

4. If the shell is inside the worktree being removed, tell the user to `cd` out first rather than
   forcing it.

```bash
git -C "$PRIMARY" worktree remove "${PRIMARY}-rea-<NUM>" --force
git -C "$PRIMARY" worktree prune
```

`--force` also deletes `node_modules`, the copied `local.properties` and Gradle build output. That
is fine: all of it is gitignored and reproducible. It is used only after step 3's approval.

5. If that worktree's build is on the phone, say so, and recommend reinstalling from the primary
   when it next takes the slot.

## Error handling

| Scenario | Action |
|---|---|
| Directory not found | Show the error, list what exists |
| `git worktree add` fails, branch exists | Offer to check out the existing branch with `worktree add <dir> <branch>` |
| `npm ci` fails | Report it; the worktree exists but is unusable. Do not proceed to the gates |
| No `package.json` | Pre-scaffold. Skip install and gates, report `GATES: NOT YET ESTABLISHED` |
| A gate `AGENTS.md` names is missing after the scaffold | Report it as a failure (drift), not as "not established" |
| `adb devices` shows 0 or 2+ devices | Say so and do not install; device checks are NOT RUN |
| Install fails on a signature mismatch | Stop and report. Never uninstall or clear data |
| Device lock held, holder gone | Report owner and age. Remove only on the user's explicit approval |
| Install marker missing | Fall back to the byte comparison; if nothing matches, say the phone runs an unknown build |
| Linear tool absent | Do the git work, print what you would have asked Linear |
| `gh` not authenticated | Skip the PR lookup, say it was skipped |
| Inside the worktree being removed | Tell the user to exit; do not force |
| A sibling matching `Read-Me-run-*` | List as `run lane`, touch nothing, refuse `remove`; read the matching state file to say why it is there |
| A sibling matching neither pattern | List as unmanaged, touch nothing. It may belong to treehouse |

Every worktree branch still passes `/critique` and `/check-constraints` through `/ship` before
merge. A worktree is a place to work, not an exemption from the gates.
