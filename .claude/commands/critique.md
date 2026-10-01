---
description: Adversarially review the current changes against this codebase's real failure modes, score the risk, and record a verdict that /ship can read.
---

Rules and gates: `AGENTS.md`. Architecture: `CONTEXT.md`. Spec: `srs.md`.

Runs standalone, and `/ship` invokes it as its adversarial gate (Step 6). `/ship` reads the
verdict from `.claude/last-critique.md` and reuses it only when the recorded commit matches
`HEAD` and the recorded mode is a code review. Step 5 below writes that file; keep its shape.

**This command is read-only.** It reports. It does not fix, does not stage, does not commit.
The only file it writes is `.claude/last-critique.md`.

## Usage

| Invocation | Scope |
|---|---|
| `/critique` | Uncommitted, staged and branch commits (depth auto-detected) |
| `/critique workflow` | All of `.claude/` (always L2) |
| `/critique architecture` | `CONTEXT.md`, `srs.md`, `AGENTS.md`, `docs/adr/`, and once they exist the `ReadMeSpeech` module surface, `PlaybackService`, `BridgeServer`, `Fetcher`, `Store` and the TS `library` facade (always L2) |
| `/critique "<question>"` | Adversarial evaluation of one decision (always L2) |

`$ARGUMENTS` selects the mode. Empty means the diff.

## Your stance

You are not approving. You are finding what is wrong, what could break, and what was
overlooked. You succeed when you find a real issue. You fail when you rubber-stamp.

**Evidence requirement.** Every criticism cites one of:

- A specific code path that breaks, as `file:line` you actually opened
- A concrete input that produces the wrong output, runnable: a shared string, an HTML fixture,
  an HTTP request against the bridge
- A test that is missing, or one that was weakened
- A measurable risk: an uncaught exception that kills the service, a socket with no read
  timeout, a cache file left behind on a failure path, an unbounded read, a `TextToSpeech`
  instance never shut down, a JS listener that outlives its screen
- A contradiction between `srs.md`, `CONTEXT.md` or `AGENTS.md` and the implementation

No evidence means it is a **question** ("Have you verified that...?"), not a finding, and it
does not move the score.

**Reasoning from reading is weak evidence here.** The plugin repo this app grew out of found
most of its defects by running the real function on real input, and the bridge prototype's
three worst defects (the `::1` bind, the unauthenticated OOM crash, the stalled accept thread)
were invisible on reading and obvious on the device. When a finding is about `extract`,
`segment`, trim math, `Fetcher` or `BridgeServer`, run it: the TS unit runner against a fixture,
the Kotlin unit test against a local server, or `curl` against the bridge on the phone through
`adb forward`. A finding you reproduced outranks three you inferred.

## Step 1: Identify changes

```bash
BASE="$(git merge-base origin/main HEAD)"
git diff --stat "$BASE"
git diff --name-only "$BASE"
git log origin/main..HEAD --oneline
```

The merge base, not `HEAD`. `git diff HEAD` shows only uncommitted work, and `/ship` calls this
after committing, so with `HEAD` as the base every check below inspects an empty diff and
reports clean on code it never read. If `git diff --name-only "$BASE"` prints nothing on a
branch with commits, the range is wrong, not the code.

`workflow` and `architecture` use their own file lists instead.

## Step 2: Auto-pass check

Skip the review only when **every** changed file is `*.md` (other than `srs.md`),
`package-lock.json`, or whitespace-only. Then report
`Auto-pass: docs only, no code changes to review.` and stop.

`srs.md` is **not** auto-pass. An edit to `srs.md` is a change to the acceptance criteria, and
`AGENTS.md` requires an ADR in `docs/adr/` alongside it when it changes a decision. Review it as
a change. A spike branch's recorded answer under `srs.md` "Spikes" is exactly such a change:
review whether the recorded answer is supported by an observation named with date, device and
build, not by the probe code.

`workflow` and `architecture` bypass auto-pass.

## Step 3: Risk assessment

Score the diff. These are the areas where a mistake in this repo is silent rather than loud.
Paths are not fixed until the scaffold lands, so the conditions name modules and grep patterns;
match them against the diff, not against a guessed path.

| Factor | Points | Condition |
|---|---|---|
| A log or error call site touched | +2 | An added `Log.`, `console.`, `println`, `printStackTrace`, `throw ...Exception(` or `new Error(` anywhere. A log line that carries item text is non-negotiable 1. |
| Network surface touched | +2 | An added `fetch`, `XMLHttpRequest` or `WebSocket` in JS, or `Socket(`, `ServerSocket(`, `openConnection`, `OkHttpClient`, `URL(` in Kotlin, or `Fetcher` changed at all. |
| Bridge touched | +2 | `BridgeServer` changed: bind address, token check, caps, timeouts, CORS headers, cache cleanup, or route table. |
| Rate handling touched | +2 | `setSpeechRate`, `rate`, `speed`, `playbackRate` or `X-Rate` on an added line. Rate applied twice shipped once in the plugin. |
| Playback ownership touched | +2 | `PlaybackService`, its queue, `UtteranceProgressListener`, `MediaSession`, or a JS call that starts, advances or stops playback. |
| Persisted positions or items touched | +2 | `Store`, the `library` facade, the schema, a migration, or anything that writes a position or a cut. |
| Manifest or dependency touched | +2 | `AndroidManifest.xml`, any `build.gradle*`, `settings.gradle*`, or `package.json` `dependencies` / `devDependencies` gained an entry. F-Droid cleanliness and permissions live here. |
| Text pipeline touched | +1 | `intake`, `extract`, `segment` or trim math changed. A break here corrupts reading rather than crashing. |
| `TextToSpeech` construction touched | +1 | An added `TextToSpeech(`, `.shutdown()`, or engine selection. |
| No test changes alongside code | +1 | Code changed and no TS or Kotlin test did. |
| Deletes more than it adds | +1 | Net negative diff. |
| Files changed > 8 | +1 | |

**Depth:**

- 0-1 → **L1** (Quick Challenge, roughly 500 tokens)
- 2-3 → **L2** (Full Protocol, roughly 2000 tokens)
- 4+ → **L2+Double** (two independent runs, lower score wins)

Report the score and every contributing factor **before** reviewing, so the depth is not chosen
after you know what you found.

## Step 4: Review

### L1 - Quick Challenge

Three questions, answered against the diff:

1. **Most likely failure mode.** What breaks first on a real share on the real phone? Name the
   input: a paywalled link, a page over 5 MB, a redirect loop, a shared text with no blank
   lines, a URL with a tracking query, a CJK article, an empty share.
2. **The untested path.** Which branch has no coverage in the gates? `AGENTS.md`'s
   quality-gates block is the one place that names them; if it still says
   `NOT YET ESTABLISHED`, say every path is untested.
3. **The implicit assumption.** What does this assume without validating? Check it against the
   layering rules and known structural gaps in `CONTEXT.md` and the six spikes in `srs.md`: a
   change that quietly depends on an unanswered spike (two `TextToSpeech` instances coexisting,
   `Intl.Segmenter` on Hermes, Readability on Hermes, the bridge's service type) is assuming the
   answer.

Verdict: PASS / CONCERNS / BLOCK with brief reasoning.

### L2 - Full Protocol

Five phases, sequential, no skipping.

1. **Claim extraction.** List every explicit and implicit claim the change makes. Read the
   changed files whole, not just the hunks. State the intent, the scope and the blast radius.

2. **Adversarial verification.** For each claim, seek counter-evidence. **At least half the
   investigation must seek disconfirmation.** Trace the real call chain across the JS / Kotlin
   boundary; do not trust the comments, which can outlive the code. Check the inputs this app
   will actually meet: a share whose text is a URL plus a sentence, a non-http scheme, a
   redirect to `file:` or `intent:`, a chunked body with no `Content-Length`, a page with no
   `<article>`, a paragraph of one word, an item trimmed to nothing, a position past the end of
   a paragraph a later trim removed.

3. **Belief gap analysis.** What the author wishes were true versus what the code does. Does the
   change respect the layering in `CONTEXT.md`: JS never touches the network, JS never drives
   playback, Kotlin owns the database, the bridge knows nothing about items, segmentation lives
   only in `segment`?

4. **Pre-mortem.** "Two weeks after shipping, this caused a report. What happened?" Three
   scenarios, each tied to a `file:line`. Useful shapes here: logcat held a sentence of an
   article; playback stopped when the screen locked because JS was suspended; the plugin's read
   came out at 4x; the bridge stopped answering `/health` while one client hung; a reopened
   item resumed at the wrong sentence after a runtime update; a trimmed paragraph was gone for
   good; an F-Droid scan rejected a transitive Google dependency.

5. **Verdict.** Score 0-100. PASS (80+), CONCERNS (50-79), BLOCK (<50).

Number findings `F1`, `F2`, ... with severity `HIGH` (will break, or violates a non-negotiable),
`MEDIUM` (likely rework), `LOW` (worth fixing, not blocking).

### L2+Double

Two independent L2 reviews. The second must not reference the first. Then:

- Scores diverge by more than 10 → the lower wins
- Both PASS → PASS
- Either BLOCK → BLOCK
- Otherwise → CONCERNS

## What to attack in this codebase

Generic review misses everything that matters here. Work through these. Each is checkable, and
an answer of "probably fine" is not an answer. The numbers in brackets are `AGENTS.md`
non-negotiables.

### Privacy: can any log line or error message now carry item text [1, 4]

```bash
rg -n 'Log\.(v|d|i|w|e|wtf)\(|console\.(log|info|warn|error|debug)|println\(|printStackTrace|Exception\(|new Error\(' 
```

For every added or changed call site: is the payload a count, an id, a state, a duration, a
fixed string, or at most a host name? Anything carrying a paragraph, a sentence, a title, the
shared text, a URL path or query, a request body, or the bridge token is a **BLOCK**.

The non-obvious path is an exception whose *message* was built from item text or a URL, later
logged or shown: `throw IOException("fetch failed for $url")` leaks the path and query.
`Log.e(TAG, "...", e)` prints `e`'s message and stack, so check what built `e`. The same for
an `Error` crossing the native bridge into JS and landing in a `console.error`.

The token: grep for its variable name and for `Authorization` on any log or format line. The
token must never be in a URL either (`?token=`).

### Network: is `Fetcher` still the only call site [5, 6, 7]

```bash
rg -n 'fetch\(|XMLHttpRequest|WebSocket' --glob '*.{ts,tsx,js,jsx}'
rg -n 'Socket\(|ServerSocket\(|openConnection|HttpURLConnection|OkHttpClient|URL\(' --glob '*.kt'
```

- Any JS hit outside a test fixture or the network-guard test itself is a **BLOCK**. A library
  that fetches on its own (an image loader, a font loader, a Readability fork that resolves
  relative URLs by requesting them) counts.
- Any Kotlin hit outside `Fetcher` is a **BLOCK**, except `ServerSocket` inside `BridgeServer`.
  `URL(` used only for parsing is fine if it never opens a connection; prove it.
- `Fetcher` runs only on a share or an explicit Retry. Trace every caller; a fetch reachable
  from app start, a list refresh or a background job is a BLOCK even to a legitimate host.
- `Fetcher`'s limits, every one still present and not loosened: `CookieJar.NO_COOKIES`, no
  cache, `followRedirects(false)` with a manual loop capped at 5 and http(s) targets only, a
  20 s call timeout, and the 5 MB cap enforced **while streaming**, not by trusting
  `Content-Length`. A cap checked after `body.string()` has already read the whole body.
- The network-guard test still exists and still covers both halves (JS references and
  non-`Fetcher` Kotlin connections). A guard that greps a narrower path set is weakened.
- Anything telemetry-shaped: an analytics import, a crash reporter, an update check, remote
  config, a web font or CDN load. **BLOCK**. No cloud TTS endpoint or API key, ever; a voice
  with `isNetworkConnectionRequired()` true must stay unselectable [5].

### Bridge: is it still loopback-only and hostile-input safe [8]

Read `BridgeServer` whole for any change that touches it.

- Bind address is `127.0.0.1` built explicitly (`InetAddress.getByName("127.0.0.1")` or the
  byte form). `getLoopbackAddress()`, `0.0.0.0` or an unbound `ServerSocket(port)` is a
  **BLOCK**; the first returned `::1` on Android 17 and the prototype's documents were wrong
  about it.
- The token is checked **before** the body is read, and the body buffer is never sized from an
  unvalidated `Content-Length`. Header cap 16 KiB (431), body cap 64 KiB (413), malformed or
  negative `Content-Length` 400. The prototype killed its whole process with one unauthenticated
  `Content-Length: 2000000000`.
- Every accepted socket gets a read timeout, and handling runs off the accept thread. A silent
  client must not stall `/health`; the prototype reproduced exactly that.
- Each connection is caught at `Throwable`, not `Exception`. An `OutOfMemoryError` is not an
  `Exception`.
- Text arrives in the POST body. A route reading text from the query string is a BLOCK.
- CORS: `OPTIONS` answered, and **every** response including 401, 413 and 500 carries
  `Access-Control-Allow-Origin: http://localhost` and the Expose-Headers line. An error path
  that builds its own response without them makes the WebView caller blind.
- Synthesized WAVs deleted on every exit path, including failure and timeout, and swept at
  service start.
- The bridge has its own `TextToSpeech` instance [13], and knows nothing about items.

Run the hostile set rather than reading for it, when a device or the Kotlin unit tests are
available: no token, `Content-Length` of `2000000000`, `-5`, `abc`, `65537`, a 20 KB
unterminated header, and one connected-but-silent client while `/health` is polled.

### Rate: is it still applied exactly once [9]

The engine applies rate at synthesis. Read Me's own player must never also scale speed, and the
bridge's audio is already at the requested rate.

- In `PlaybackService`: `setSpeechRate` is the only rate lever. An added `PlaybackParams`,
  `setPlaybackSpeed`, sonic/time-stretch step, or a JS-side speed multiplier on top is a
  **BLOCK**.
- In `BridgeServer`: the `rate` query parameter reaches the engine once, and `X-Rate` reports
  it. Nothing in the bridge resamples.
- In docs and the bridge contract: the plugin calls `rate=1.0` and its Player applies the user's
  rate. Any text in this repo that tells the plugin to send its user rate is a BLOCK, because
  the plugin's Player will then apply it again.
- If a test pins the rate (for example that a 2.0 request is passed to the engine as 2.0 and the
  player applies 1.0), check its expected values did not change. A softened rate assertion is
  itself the violation.

### Correctness: positions, queue ownership, trim, instances [10-13]

- **Positions are (item, paragraph index, character offset).** An added persisted field that
  stores a sentence index, an utterance index or an utterance id as a resume point is a
  **BLOCK**. Resume must re-segment and find the sentence containing the offset.
- **The native service owns the queue.** JS hands `PlaybackService` the sentence list and a
  start offset, and is a view after that. A JS timer, `onDone` listener or event handler that
  enqueues the next utterance, saves the position, or decides what plays next is a **BLOCK**:
  with the screen off the JS runtime may not be running. Check that position saves happen in
  the service's `onDone`, not on a JS event.
- **Trim is non-destructive.** Cuts are a separate set of paragraph indices; the original
  paragraphs stay. A `DELETE` or an overwrite of paragraph text from the trim path is a BLOCK.
  Deleting an *item* deletes all its stored text; check that path removes everything [3].
- **Separate `TextToSpeech` instances** for playback and the bridge, unless an ADR records
  SPIKE-06's replacement policy. A shared singleton is a BLOCK.

### Distribution: does the build stay F-Droid-clean [14]

`package.json` is not the check; the resolved Gradle tree is. For any dependency or build-file
change:

```bash
(cd android && ./gradlew :app:dependencies --configuration releaseRuntimeClasspath) \
  | grep -niE 'com\.google\.android\.gms|firebase|crashlytics|play-services|com\.google\.android\.play|gms'
```

Any hit is a **BLOCK**. Also check: every new dependency's license is OSI-approved (read it, do
not infer from the name), no prebuilt `.so`, `.aar` or `.jar` without source, and React Native
autolinking did not pull a native module that brings Play Services transitively. If the Gradle
wrapper does not exist yet, say the check could not run; do not report it as clean.

`AndroidManifest.xml`, on any change: permissions minimal (`INTERNET` is the only network
permission), the `TTS_SERVICE` intent `<queries>` entry present (the prototype proved it causal:
without it TTS init fails on API 30+), `android:allowBackup="false"`, and every foreground
service declares a `foregroundServiceType` with its matching permission. A changed service type
for the bridge needs SPIKE-01's answer cited.

### Concurrency and lifetime

- `UtteranceProgressListener` callbacks arrive on a binder thread. Does state they touch get
  synchronised or posted to one thread?
- Does stop/pause cancel queued utterances, and can a late `onDone` from a stopped item save a
  position onto the next one? Utterance ids must be stable and checked.
- Does every `TextToSpeech` get `shutdown()` on service destroy? Does the bridge's executor
  shut down?
- Does a JS screen remove its native event subscriptions on unmount?
- App start: are stale `fetching` items moved to `fetch-failed` (interrupted), as `CONTEXT.md`'s
  data flow requires?

### Spike work

On a `spike/rea-*` branch the code is throwaway. Critique the **recorded answer**: is it in
`srs.md` "Spikes", does it name date, device, build and what was observed, does it say what it
did not establish, and, if it changes a decision, is there an ADR in `docs/adr/`? A spike answer
that rests on reasoning rather than an observation on the device is a HIGH finding.

### Spec alignment

If the change claims a requirement, open that requirement in `srs.md` and read it. The headings
are `### R-M01` through `### R-C04`, and the spikes are `SPIKE-01` through `SPIKE-06`. Does the
code satisfy the text, or only resemble it? `CONTEXT.md` records owner decisions that `srs.md`
has not absorbed yet (Kotlin owns the database); a change built on one cites it.

If the change deviates from `srs.md`, `AGENTS.md` requires an ADR in `docs/adr/` and an
amendment to `srs.md`. A deviation with no ADR is a finding.

### Claims in the diff itself

`AGENTS.md` 17: never assert a measurement you did not take. Scan added comments, added `srs.md`
prose and the commit messages for a number with a unit: ms, s, MB, KiB, RTF, a gap, a ratio, x
faster. For each, was it measured this session, or is it cited with where it was measured? The
plugin repo's `AGENTS.md` is a valid citation for the measurements this spec rests on. An
unsourced number is a finding at LOW unless it is load-bearing for a decision, in which case
MEDIUM.

## Step 5: Record the verdict

Write `.claude/last-critique.md`, overwriting it:

```markdown
## Last Critic Verdict
- **Date:** <ISO 8601 timestamp>
- **Commit:** <full HEAD hash at review time>
- **Mode:** Code Review
- **Depth:** L1 | L2 | L2+Double
- **Score:** <0-100>
- **Verdict:** PASS | CONCERNS | BLOCK
- **Risk Score:** <N> (<contributing factors>)
- **Files Reviewed:** <count>
- **Reproduced:** <what you actually ran, or "nothing run">

### Summary
<one line>

### Findings
- **F1** HIGH `<file>:<line>` - <what it does, the input, the wrong result>
```

Get the hash from `git rev-parse HEAD`. Emit `**Mode:**`, `**Depth:**`, `**Score:**` and
`**Verdict:**` verbatim on their own lines: `/ship` parses them and will discard the file if the
commit does not match `HEAD` or the mode is not a code review.

For `/critique workflow`, `architecture` or a bare question, still record it, but set
`**Mode:** Architecture Review`, `Workflow Review` or `Question` and omit `Commit:`. None of
those read the shipping diff, so `/ship` must not be able to reuse them in place of its gate.

## Step 6: Report

Show the verdict, the risk score with its factors, and every finding with a `file:line` you
actually opened. For CONCERNS or BLOCK, each finding gets a concrete remediation. Say plainly
what you did not check and what you could not reproduce.

## Rules

1. **Be specific.** "This could have bugs" is worthless. A finding reads
   "`<file>:<line>` does X, so input Y produces Z". Name the module and function you traced.
2. **Run it rather than reason about it** whenever the code can be run: a unit test, a fixture,
   `curl` against the bridge on the phone.
3. **A green suite is not evidence.** Unit tests run against fakes and a local test server. If
   the change is user-visible and was not exercised on the phone via `npm run device:install`
   and real use, say so in the verdict. If gates do not exist yet, say
   `GATES: NOT YET ESTABLISHED` rather than implying a pass.
4. **Do not nitpick style.** Linting is a gate's job once it exists. Matching house style is not
   a finding either way.
5. **Score honestly.** A 95 means you traced the paths and found almost nothing. If you did not
   trace them, you have not earned it. A non-negotiable violation caps the score below 50, no
   matter how good the rest is.
6. **Report, do not fix.** No edits to source, no staging, no commits. Fixes happen after the
   verdict, in a separate pass.
