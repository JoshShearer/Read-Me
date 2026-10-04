---
description: Start a session - report git state, sync with origin, check what build the attached phone is running and who holds the device slot, pull Linear issues, and name the next action.
---

Conventions: `.claude/linear.md`. Rules and gates: `AGENTS.md`. Architecture: `CONTEXT.md`.
Spec: `srs.md`.

This command reads and reports. The only things it is allowed to change are: a fast-forward
pull, a stash/pop around a pull on `main`, and `npm ci` in a worktree with no `node_modules`
(only once a `package.json` exists). It never rebases, never installs on the phone, never takes
or releases `.claude/device.lock/`, and never touches Linear state.

## Linear tool names

Operations used here: `list_issues`, `get_issue`. **Do not hardcode a tool prefix.** Find the
real names for the `linear-rea` server in your available tool list and call those. Never use
`linear-nrl`: that is the plugin's workspace. If no Linear tool is present, follow the
degradation rule in `.claude/linear.md`: give a git-only orientation and say Linear was
unreachable.

## Parallelism

Two independent lanes. Start them together, then synthesize in Step 7.

1. **Bash lane**: Steps 0, 1, 2, 4, 5
2. **Linear lane**: Step 3

## Step 0: Detect where you are

```bash
CURRENT_DIR="$(basename "$(pwd)")"
if [[ "$CURRENT_DIR" =~ ^Read-Me-rea-([0-9]+)$ ]]; then
  echo "SESSION_TYPE=worktree"
  echo "ISSUE_NUM=${BASH_REMATCH[1]}"
else
  echo "SESSION_TYPE=primary"
fi
[ -f package.json ] || echo "PACKAGE_JSON=absent (pre-scaffold)"
[ -f package.json ] && { ls node_modules/.package-lock.json >/dev/null 2>&1 || echo "NODE_MODULES=missing"; }
```

**worktree**: condensed report. Skip Step 2 (sibling scan). In Step 3 fetch only `REA-{ISSUE_NUM}`.
If `NODE_MODULES=missing`, say so loudly and run `npm ci` before claiming any gate result.

**primary**: full report.

`PACKAGE_JSON=absent` is the expected state until the scaffold ticket lands. Report
`GATES: NOT YET ESTABLISHED` and skip every npm and Gradle step; do not invent a pass.

## Step 1: Git state

```bash
git fetch origin --prune && \
echo "BRANCH=$(git branch --show-current)" && \
git status --short && \
echo "SYNC(behind ahead)=$(git rev-list --left-right --count origin/main...HEAD 2>/dev/null || echo '? ?')" && \
git log --oneline -5 && \
echo "=== UNPUSHED ===" && \
(git log --oneline @{u}..HEAD 2>/dev/null || echo "no upstream") && \
echo "=== STALE ===" && \
(git branch -vv | grep '\[.*: gone\]' || echo "none")
```

Baseline for sanity: the repo began with `08888dc docs: initial SRS for Read Me`; remote
`origin` is `git@github.com:JoshShearer/Read-Me.git` (public; `gh repo view`, 2026-10-04). A short history with no
`android/` or `src/` is the expected pre-scaffold state, not a broken clone.

Open PRs, if `gh` is available:

```bash
gh pr list --state open --json number,title,headRefName,isDraft 2>/dev/null || echo "gh unavailable"
```

`gh` failing is not an error worth stopping for. Report "PR state unknown" and continue.

## Step 2: Sibling worktrees (primary session only)

```bash
ROOT="$HOME/Documents/Dev/Read-Me"
shopt -s nullglob
for dir in "${ROOT}"-rea-*/; do
  [ -e "$dir/.git" ] || continue
  NUM=$(basename "$dir" | grep -oE '[0-9]+$')
  BRANCH=$(git -C "$dir" branch --show-current 2>/dev/null || echo detached)
  DIRTY=$(git -C "$dir" status --porcelain 2>/dev/null | wc -l | tr -d ' ')
  AHEAD=$(git -C "$dir" rev-list --count origin/main..HEAD 2>/dev/null || echo '?')
  DEPS=$([ ! -f "$dir/package.json" ] && echo n/a || { [ -d "$dir/node_modules" ] && echo ok || echo MISSING; })
  echo "REA-$NUM branch=$BRANCH ahead=$AHEAD dirty=$DIRTY deps=$DEPS"
done
echo "=== END WORKTREES ==="
```

Only `Read-Me-rea-*` siblings belong to this pool. A worktree with any other name is
`treehouse` / gnhf territory: report it if you see it, do not read into it and never touch it.

## Step 3: Linear issues (parallel with the bash lane)

`list_issues` with `assignee: "me"`:

1. `state: "In Progress"`
2. `state: "Todo"`, limit 5

`In Review` is optional (`.claude/linear.md`). Do not query it blind. Split the In Progress
results by whether an open PR exists for the branch
(`gh pr list --search "head:feature/rea-{N}" --state open`, likewise `fix/` and `spike/`) and
report those under an **In Review** heading. If `list_issue_statuses` shows a real `In Review`,
query it directly and say so, so the conventions file can be corrected.

**Worktree variant**: `get_issue` with `id: "REA-{ISSUE_NUM}"` only.

Carry any requirement or spike ID (`R-M07`, `R-S01`, `R-C02`, `SPIKE-05`) from a title or
description into the report. That ID is the acceptance criteria and it lives in `srs.md`.

If `.claude/linear.md` still says "unverified" for the team key or statuses and a call
succeeds, note what the server actually returned so that file can be filled in.

## Step 4: What is on the phone, and who holds it

The device slot is single: one attached phone, and installing replaces whatever build another
lane put there. This check stops a test result being attributed to the wrong build.

```bash
LOCK="$HOME/Documents/Dev/Read-Me/.claude/device.lock"
if [ -d "$LOCK" ]; then
  echo "DEVICE_LOCK=held since $(stat -c '%y' "$LOCK")"
  ls -A "$LOCK" 2>/dev/null | head -5     # an owner note, if the holder left one
else
  echo "DEVICE_LOCK=free"
fi
command -v adb >/dev/null || echo "ADB=not on PATH (try ~/Android/Sdk/platform-tools/adb)"
adb devices 2>/dev/null | awk 'NR>1 && $2=="device"' | wc -l | xargs echo "DEVICES_ATTACHED="
```

If exactly one device is attached and an app id is known (`applicationId` in
`android/app/build.gradle` once the scaffold exists), read the installed build without changing
anything:

```bash
APP_ID=$(grep -oE 'applicationId\s+"[^"]+"' android/app/build.gradle 2>/dev/null | grep -oE '"[^"]+"' | tr -d '"')
[ -n "$APP_ID" ] && adb shell dumpsys package "$APP_ID" | grep -E 'versionName|versionCode|lastUpdateTime' | head -3
```

| Result | What to report |
|---|---|
| no `android/` yet | Nothing to install. Every requirement is unmet until observed on the device. |
| `DEVICES_ATTACHED=0` | No phone. Any on-device claim this session is impossible; say so. |
| `DEVICES_ATTACHED` > 1 | Device commands would stop. Name the serials; the user must detach one. |
| `DEVICE_LOCK=held` | Another lane is installing or testing. Do not touch the phone. Say since when. |
| App not installed | Nothing to test against yet. |
| Installed `lastUpdateTime` older than this tree's last change | The phone runs an older or another lane's build. A manual test now proves nothing about this tree. |

Do **not** run `npm run device:install` from `/orient`. Taking the slot is the user's call.

## Step 5: Sync

| Branch | Tree | vs upstream | Action |
|---|---|---|---|
| main | clean | behind | `git pull --ff-only` |
| main | dirty | behind | `git stash push -u -m orient-autostash-<ts>` then `git pull --ff-only` then `git stash pop`. On pop conflict, leave the stash and surface its name. |
| feature/*, fix/*, spike/* | clean | behind its own upstream | `git pull --ff-only` |
| feature/*, fix/*, spike/* | dirty | behind | Skip with a warning. WIP is not worth the risk. |
| any | any | no upstream | Skip silently |
| any | any | up to date | Report "up to date" |

**Never auto-rebase a branch onto main.** Report the `behind main` count and let the user ask.

If the lockfile moved under you, deps are stale and the gates are meaningless:

```bash
git diff HEAD@{1} HEAD -- package-lock.json package.json android/build.gradle android/app/build.gradle 2>/dev/null | head -1
```

Non-empty and `package.json` exists: run `npm ci` and report its tail. A Gradle file moved:
report that the resolved dependency tree must be re-checked for F-Droid cleanliness
(non-negotiable 14) before the next release build.

## Step 6: Hazards for what the user is about to touch

Work out the likely target area from the branch name, the In Progress issue, and any
uncommitted files. Surface **only** the matching rows. `AGENTS.md` holds the authoritative
text; these are pointers. Module names are from `CONTEXT.md`; paths do not exist until the
scaffold lands, so match by name.

| If the area is | Surface |
|---|---|
| Any logging (`Log.`, `console.`) | **Non-negotiable 1**: counts, ids, states, durations, at most a host. Never a sentence, title, or URL path/query, not even in an exception message. **2**: no telemetry. |
| `Fetcher`, or any network code | **6**: `Fetcher` is the only call site; no JS `fetch`/`XMLHttpRequest`/`WebSocket`, no other Kotlin connection. **7**: `NO_COOKIES`, no cache, at most 5 manual redirects, 20 s timeout, 5 MB cap while streaming. |
| `BridgeServer` | **4**: token never logged or in a URL. **8**: bind `127.0.0.1` explicitly, token before body, header/body caps, read timeout, catch `Throwable`, CORS headers on every response including errors. **9**: rate applied once; the plugin calls `rate=1.0`. **13**: its own `TextToSpeech`. |
| `PlaybackService`, `reader` | **11**: the native service owns the queue; JS is a view. **9**: the player never scales speed a second time. **13**: separate `TextToSpeech` from the bridge. |
| `segment`, `library`, `Store`, trim | **10**: positions are character offsets, never sentence indices. **12**: trimming is non-destructive. **3**: `allowBackup="false"`, delete removes all stored text. |
| `AndroidManifest.xml`, Gradle files, `package.json` | **14**: F-Droid-clean; check the resolved Gradle tree for `com.google.android.gms`, `firebase`, `crashlytics`. Permissions: only `INTERNET` plus what the foreground service and media session need; `TTS_SERVICE` query present (proven causal); `allowBackup="false"`; foreground-service type per SPIKE-01. |
| A `spike/rea-*` branch | The code is throwaway. What ships is the answer recorded in `srs.md` "Spikes", plus an ADR if it changes a decision. |

## Step 7: Output

```
## Session Orientation

Context: {primary | worktree REA-N}
Gates: {NOT YET ESTABLISHED | named in AGENTS.md}

### Repo State
| Branch | Clean | Sync vs origin/main | Unpushed | Deps |
|--------|-------|---------------------|----------|------|
| main | Yes | up to date | 0 | n/a (pre-scaffold) |

{if uncommitted changes, list them here, before anything else:}
### Uncommitted changes
| File | Status |
|------|--------|

### Device
| Attached | Slot | Installed build | Matches this tree |
|----------|------|-----------------|-------------------|
| 1 (Pixel 9 Pro XL) | free | not installed | - |

### Linear
**In Progress:**
- REA-12: Share-sheet intake (R-M02) <- this branch

**In Review (open PR):**
- REA-9: ... - PR #3

**Todo:**
- REA-3: SPIKE-02 Readability on Hermes

### Active Worktrees {omit if none}
| Issue | Branch | Ahead | Dirty | Deps |
|-------|--------|-------|-------|------|

### Hazards for this work {omit if no area is identifiable}
- BridgeServer: token before body (non-negotiable 8)

### Housekeeping {omit if nothing found}
| Stale branch | Action |
|--------------|--------|
| fix/rea-9-... | `/finish fix/rea-9-...` |

### Next action
{one sentence, one command}
```

The next action is one line and one command. Candidates, in priority order:

1. Merge conflicts or a dirty tree blocking work: resolve that first, nothing else.
2. On a `feature/rea-N-*`, `fix/rea-N-*` or `spike/rea-N-*` branch: `/start-issue REA-N`.
3. An open PR: review it, merge, then `/finish`.
4. Clean `main` with Todo issues: `/start-issue REA-N` for the top one. Before the scaffold,
   spikes come first (`srs.md`, "Spikes"); none has run.
5. Clean `main`, nothing assigned: say so and point at `srs.md`. No requirement is met; every
   one needs an on-device observation (R-M14).

## Error handling

| Scenario | Action |
|---|---|
| `git fetch` fails | Warn about the network, orient from local state |
| `git pull --ff-only` rejected | Skip, report `behind`, let the user rebase explicitly |
| `git stash pop` conflicts | Leave the stash, print its name |
| Merge conflicts in the tree | Stop. That is the whole report. |
| Linear tools absent or unauthenticated | Git-only orientation, say so, point at the first-run setup in `.claude/linear.md` |
| `gh` missing or unauthenticated | Report "PR state unknown", skip the In Review split |
| `npm ci` fails | Report the error, flag every gate result as untrustworthy |
| `adb` missing or no device | Report it; do not try to install platform-tools from here |
