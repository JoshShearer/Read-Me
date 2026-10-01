# Commands - Read Me

Ported 2026-10-01 from the sibling `note-reader-local` repo (the Obsidian plugin), which adapted
them from job-radar with `/run-tickets` from ShroomSpy. Linear conventions live in `linear.md`;
every command reads from there rather than restating the team key, statuses, or branch patterns.

**These files work in both Claude Code and opencode.** The canonical copies are in
`.claude/commands/`. `.opencode/command/` holds per-file symlinks to them, so there is one
source of truth and an edit lands in both runtimes at once.

This index lives at `.claude/COMMANDS.md`, not inside `commands/`, so it is not exposed as a
`/COMMANDS` command.

---

## Workflow (8)

| Command | Purpose | Linear status |
|---|---|---|
| `/orient` | **Start of session** - git state, sync, stale branches, device-slot state, open issues, next action | - |
| `/create-issue` | Create an issue from conversation, carrying the `srs.md` requirement or spike ID and a real repro | to Todo |
| `/start-issue` | Start work - branch, context, the `AGENTS.md` rules for the area being touched | to In Progress |
| `/ship` | Gates, constraint audit, adversarial pass, commit, push, PR with an on-device test plan | stays In Progress + PR comment |
| `/test-issue` | Triage the PR's CI result - is a red check this branch's fault, one repair attempt, merge-readiness verdict | - (comments) |
| `/verify` | Run the real gates, install on the phone, drive it as a user would, post what was observed | - (comments) |
| `/finish` | **End of cycle** - delete branch both sides, sync, close, correct any doc the change invalidated | to Done |
| `/whats-next` | **Session handoff** - decisions, measurements taken, claims still unverified | - |

`/test-issue` and `/verify` are two axes. `/test-issue` answers "did CI go red, and is it this
branch's fault"; `/verify` answers "does it work on the phone" and owns the `VERIFIED ON DEVICE`
verdict.

## Quality (4)

| Command | Purpose |
|---|---|
| `/critique` | Adversarial review scored against this app's real silent-failure areas; writes a verdict `/ship` reads |
| `/check-constraints` | Mechanical pass over the non-negotiables in `AGENTS.md`; a BLOCK stops `/ship` |
| `/spec-check` | Map the tree against `srs.md` requirement and spike IDs and report what moved |
| `/update-docs` | Diff `AGENTS.md`, `srs.md` and `CONTEXT.md` against reality after a merge |

## Orchestration (2)

| Command | Purpose |
|---|---|
| `/worktrees` | Parallel sessions - list, inspect, create, remove; file overlap and device-slot ownership. Owns the `Read-Me-rea-*` pool only |
| `/run-tickets` | Run a batch of tickets end to end in fresh subagents, fully autonomously, in a disposable `Read-Me-run-*` lane; anything needing a human blocks that ticket and is reported at the end |

---

## The normal cycle

```
/orient -> /create-issue -> /start-issue -> ...work... -> /ship -> /verify -> merge -> /finish -> /update-docs
                                                                \
                                                                 -> /test-issue, if the PR's CI went red
```

## What differs from the plugin repo's commands

| Difference | Why |
|---|---|
| Verification is on a phone, not in Obsidian | The product is an Android app. `/verify` installs the release build and drives it over adb; bridge checks drive the plugin in Obsidian on the same phone over CDP. |
| One device slot, `.claude/device.lock/` | There is one attached phone, and installing replaces the app for every lane. Replaces the plugin's `deploy.lock`. |
| Gates may not exist yet | There is no code until the scaffold ticket. Commands report `GATES: NOT YET ESTABLISHED` instead of inventing a pass. |
| Spikes are a ticket type | `srs.md` gates implementation on six spikes. A spike ships its recorded answer (and an ADR if it changes a decision), not its probe code. |
| Risk factors are rewritten | Scored on item text in logs, network call sites outside `Fetcher`, bridge hardening, rate applied once, offset positions, queue ownership and F-Droid cleanliness. |

## Setup

The Linear MCP server is declared twice, once per runtime, both named **`linear-rea`**:

- `.mcp.json` for Claude Code
- `opencode.json` for opencode

It points at the **`read-me-tts`** workspace (<https://linear.app/read-me-tts>), team key `REA`,
which is a different workspace from the plugin's `linear-nrl`. Authenticate once per runtime
before any Linear-touching command works: in Claude Code run `/mcp` and pick `linear-rea`; in
opencode restart first so the config is picked up. Then run the discovery block in `linear.md`
and fill in its tables. Until then every command degrades to git-only and prints what it would
have sent.

Tool names are never hardcoded in a command, because the prefix differs by runtime
(`mcp__linear-rea__get_issue` vs `mcp_Linear-rea_get_issue`). Posting a comment is
**`save_comment`**; `create_comment` no longer exists, and calling it fails silently under the
degradation rule.

## Running the pipeline without a human

`opencode.json` sets `git push *`, `git branch -D *`, `git reset --hard*` and `rm -rf *` to
`ask`, and `/run-tickets` runs all four. Launch it so nothing prompts:

```bash
opencode run --auto --command run-tickets "REA-1,REA-2,REA-3"
```

In Claude Code the equivalent is a bypass-permissions session.
