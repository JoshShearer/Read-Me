---
description: Map the working tree against srs.md and report FULLY MET / PARTIAL / MISSING per requirement and ANSWERED / OPEN per spike, with file:line evidence, flagging anything that moved from the AGENTS.md baseline (the first run establishes it).
---

Audit the tree against `srs.md`, the written contract.

Conventions: `.claude/linear.md`. Rules: `AGENTS.md`.

Argument: `$ARGUMENTS`

- A requirement id (`R-M07`, `r-m07`, `M07`) or spike id (`SPIKE-05`) to audit one in depth.
- `all` to audit all 14 MUST requirements and all six spikes.
- `must`, `should`, `could`, `spikes` to audit one tier.
- Omitted: audit only what the current working tree touches. Work out which from
  `git status --short` and `git diff --name-only`, then map changed files back through the
  Requirement map below.

## The rule that makes this command worth anything

**Code reading cannot mark a user-observable requirement as met.** `srs.md` R-M14 says it in as
many words: a requirement is met only when observed on a real device, recorded with the date,
device and build. A green unit suite runs against fakes (rule 15).

So each requirement below is tagged:

- **CODE** - a grep, a manifest read or a resolved dependency tree settles it. You may mark
  FULLY MET on that evidence alone.
- **DEVICE** - user-observable. The highest verdict code reading may award is
  `PARTIAL (code present, unverified)`. FULLY MET needs an on-device observation: this session
  (via `/verify`'s install-and-drive flow, holding `.claude/device.lock/`), or a recorded one that
  names date, device and build and whose build still matches the code. If neither, say
  `needs device` in the evidence column.
- **BLOCKED** - cannot be assessed until a named spike answers. Say which.

Never quote a gap, an RTF, a size or a latency you did not measure (rule 17). R-M07's gap target
is checkable only by measuring it on the reference device over the stated 10 minutes and pasting
the numbers.

## Baseline

**No baseline has been recorded yet.** There is no code (`AGENTS.md` Known state), so every
requirement starts `MISSING` and every spike `OPEN`, and that is the expected first-run result,
not a finding to file tickets about.

The first run that produces a full (`all`) report **establishes** the baseline: offer to add a
`### Spec-check baseline` subsection to `AGENTS.md` "Known state" holding the date, the commit,
the score line and one line per requirement that is not `MISSING` and per spike that is
`ANSWERED`. Every later run reads that subsection and flags deltas against it. Do not write it
without being asked; report what you would write.

Once a baseline exists, **flag every change against it**. A move is the headline of your report:

- MISSING or PARTIAL to FULLY MET: verify hard. A DEVICE requirement with no device observation
  cannot move.
- FULLY MET to anything else: a regression against a promise, and a BLOCK. R-M06 (on-device
  speech only) and R-M09 (privacy and network) are also non-negotiables 1-7 in `AGENTS.md`; if
  either slipped, stop the audit and report that first.
- A spike moving to ANSWERED: confirm the answer is recorded in `srs.md` "Spikes" with device and
  date, not only in a PR or comment.

## Requirement map

Line numbers are into `srs.md` at the time this command was written; re-derive with
`grep -n '^### R-' srs.md` if they drift. Read the requirement before judging it; the summary is
a pointer, not the contract. The "Where" column is the planned location from `CONTEXT.md`; update
it once the scaffold sets the real tree.

### Must Have

| ID | srs.md | Demands, in short | Check | Where |
|---|---|---|---|---|
| R-M01 | 56 | Opens to the list; exactly four screens; no account, onboarding, feed | DEVICE | `src/reader/` |
| R-M02 | 68 | `ACTION_SEND` `text/plain`; one URL is a link item, else text item; native fetch; stale `fetching` recovered to `fetch-failed` on start | CODE + DEVICE | manifest, `ShareReceiver`, `src/intake/` |
| R-M03 | 85 | Native `Fetcher` only; `NO_COOKIES`, no cache, 5 manual redirects, 20 s call timeout, 5 MB streaming cap; failed fetch kept with Retry | CODE + DEVICE | `Fetcher` and its local-server tests |
| R-M04 | 111 | Readability on-device; raw HTML discarded; `extract-poor` under 3 paragraphs or 500 chars; text items split on blank lines | CODE + DEVICE | `src/extract/`, saved-page fixtures |
| R-M05 | 126 | Cut/restore, cut after, start here; non-destructive; position kept or moved to next kept paragraph | CODE + DEVICE | Trim screen, trim math |
| R-M06 | 140 | `TextToSpeech` only; `TTS_SERVICE` query; network voices hidden and never a fallback; blocking state when none | CODE + DEVICE | manifest, `PlaybackService` |
| R-M07 | 150 | Service owns the queue; own TTS instance; controls; rate once, 0.5-4.0x default 2.0x; highlight; screen-off; audio focus; gap target | DEVICE | `PlaybackService`, `src/reader/` |
| R-M08 | 178 | `Intl.Segmenter` or regex fallback; 400-char split; offsets carried; no persisted sentence index | CODE | `src/segment/` |
| R-M09 | 187 | No cloud TTS; R-M03 the only network use, test-enforced; no item text in logs; `allowBackup=false`; INTERNET the only network permission | CODE + DEVICE | network-guard test, manifest, logcat during `/verify` |
| R-M10 | 202 | Every failure a persistent state with the listed actions | DEVICE | `src/reader/`, `Store` |
| R-M11 | 215 | Position (item, paragraph, offset) saved per sentence and on pause/stop; resume by offset; archive at end; delete removes text | CODE + DEVICE | `PlaybackService`, `Store` |
| R-M12 | 224 | Bridge: `127.0.0.1`, token, caps, read timeout, own TTS instance, POST body, cache cleanup, CORS on every response | CODE + DEVICE | `BridgeServer`, Kotlin hostile-input tests; plugin side is NRL-130 in the plugin workspace |
| R-M13 | 258 | F-Droid-clean, OSI deps, builds from clean checkout, licenses screen; SDK levels from SPIKE-01 | CODE (+ DEVICE for the licenses screen) | resolved Gradle tree, `android/` |
| R-M14 | 268 | The listed unit, guard, Fetcher and CORS tests exist; the four scripted on-device checks run over adb | CODE + DEVICE | test dirs, `npm run device:smoke` |

### Should Have

| ID | srs.md | Demands, in short | Check |
|---|---|---|---|
| R-S01 | 297 | Offline voices and engines listed with preview; rebind via three-argument constructor | DEVICE |
| R-S02 | 301 | Sleep timer | DEVICE |
| R-S03 | 304 | Markdown export via share sheet | CODE + DEVICE |
| R-S04 | 308 | Bridge launch from Obsidian | DEVICE |

### Could Have

| ID | srs.md | Demands, in short | Check |
|---|---|---|---|
| R-C01 | 314 | Save to Obsidian | DEVICE |
| R-C02 | 318 | Per-site trim rules | CODE |
| R-C03 | 321 | iOS | BLOCKED (out of v1) |
| R-C04 | 325 | Shared files | DEVICE |

### Spikes

Read `srs.md` "Spikes" (around line 439). A spike's branch code is throwaway; only the recorded
answer counts.

| ID | Question, in short | Requirements it gates |
|---|---|---|
| SPIKE-01 | Foreground-service type for a backgrounded bridge, Android 14+ and 17 | R-M12, R-M13 SDK levels, R-S04 |
| SPIKE-02 | Readability on Hermes with a pure-JS DOM at 5 MB | R-M04 |
| SPIKE-03 | `Intl.Segmenter` on Hermes | R-M08 |
| SPIKE-04 | F-Droid-clean bare RN release build with the chosen SQLite library | R-M13 |
| SPIKE-05 | Inter-utterance gap at 2x with `QUEUE_ADD` and two instances | R-M07 gap target |
| SPIKE-06 | Two `TextToSpeech` instances concurrently | R-M07, R-M12, non-negotiable 13 |

Spike verdicts: `ANSWERED` (answer in `srs.md` with device and date, plus an ADR if it changed a
decision), `ANSWERED, NOT RECORDED` (the answer exists only in a PR, comment or branch; flag it
for `/update-docs`), `OPEN`.

## Known gaps that pre-empt some verdicts

From `CONTEXT.md` "Known structural gaps". Design-level, so do not report them as fresh
discoveries:

- The whole tree. Every requirement is MISSING until built, and DEVICE ones until observed.
- An open spike caps every requirement it gates at PARTIAL at best, and BLOCKED where the
  requirement's shape depends on the answer.
- `srs.md` still says TS `library` owns SQLite where the owner decided Kotlin does (F11). Judge
  R-M11 against the substance, not the module name, and note the pending amendment.

## Method

1. Read the requirement text in `srs.md`. Do not audit from the summary table above.
2. Gather evidence. Grep and read the files; for manifest claims read the merged manifest from a
   release build (`android/app/build/intermediates/merged_manifests/`), not only the source one.
   For behaviour that a unit can show, run the test rather than reason about it.
3. Cite `file:line`. A verdict with no location is not evidence, and the next session cannot
   check your work.
4. For a DEVICE requirement you want to mark FULLY MET, observe it on the phone (take
   `.claude/device.lock/`, `adb devices` shows exactly one device, `npm run device:install`, drive
   it over adb as `/verify` describes, never log item text). Name device and build in the evidence.
   If you did not, the verdict stays PARTIAL with `needs device`.
5. Compare each verdict to the baseline, if one exists, and mark the delta.

## Verdict definitions

| Verdict | Criterion |
|---|---|
| `FULLY MET` | Every MUST clause satisfied, with evidence. For DEVICE requirements, observed on a named device and build. |
| `PARTIAL` | Some clauses met. Name the specific clause that is not. "Partial" with no named gap is useless. |
| `MISSING` | No implementation, or one that does not run (wired and never called counts as MISSING for the user-facing clause). |
| `BLOCKED` | Cannot be assessed until a named spike answers, or out of scope for v1. |

A SHOULD clause inside a MUST requirement does not block FULLY MET on its own, but note it.

## Output

Lead with the deltas (or "first run, establishes baseline"), then the tables.

```
spec-check: <scope>   (commit <sha>; working tree: <clean | N files changed>)

Baseline: none recorded; this run establishes it   | or: changes since <date> baseline
  R-M08  MISSING -> PARTIAL   segment fallback at src/segment/index.ts:40; SPIKE-03 open

Requirements
| ID | Verdict | Evidence | vs baseline |
|---|---|---|---|
| R-M01 | MISSING | no src/ | new |
| R-M12 | PARTIAL | BridgeServer.kt:88 binds 127.0.0.1; needs device | new |
...

Spikes
| ID | Status | Where recorded |
|---|---|---|
| SPIKE-03 | OPEN | - |

Needs a device before any of these can move past PARTIAL
  R-M07, R-M10, R-M12

Score: N of 14 MUST fully met; M of 6 spikes answered
```

Keep the tables compact. One line per item, evidence in one clause.

## After the report

- First full run: offer the `### Spec-check baseline` subsection for `AGENTS.md` Known state, as
  above. Later runs: if any verdict moved, offer to update it. Do not edit without being asked.
- For each MISSING or PARTIAL that is not already ticketed, offer `/create-issue` with the
  requirement or spike ID prefilled. Run `list_issues` first to avoid duplicates. Pre-scaffold,
  do not offer one ticket per MISSING requirement; the roadmap covers them. Resolve the real
  prefixed tool names for the `linear-rea` server from your tool list; never file via
  `linear-nrl`. If Linear is unavailable, list what you would have filed.
- If the code deliberately diverges from `srs.md`, that is allowed but must not be silent: it
  needs an ADR in `docs/adr/` and an amendment to `srs.md`.
