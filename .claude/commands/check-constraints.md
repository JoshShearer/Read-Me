---
description: Fast mechanical pass over the 18 numbered non-negotiables and verification rules in AGENTS.md, reporting PASS, BLOCK, N-A or UNKNOWN for each.
---

Rules: `AGENTS.md`, the numbered list under Non-negotiables (1-14) and Verification rules
(15-18). This command does not restate them, it checks them.

Any **BLOCK** stops `/ship`. There is no override flag and one should not be added.

Keep the output small. Around 1k tokens: a table, then only the rows that are BLOCK or UNKNOWN,
with a `file:line` each. Do not narrate the greps.

**There is no code yet.** Until the scaffold lands, most rows are N-A on a docs-only diff, and
every check that needs a gate or a Gradle wrapper is UNKNOWN with the reason, never PASS. Paths
are not fixed yet, so the greps below match by pattern across the diff rather than by file.

## The diff range

Every check diffs against the **merge base**, not `HEAD`:

```bash
BASE="$(git merge-base origin/main HEAD)"
git diff --name-only "$BASE"
```

This is load-bearing. `git diff HEAD` shows only uncommitted work and `/ship` runs this gate
after committing, so with `HEAD` as the base every grep returns empty and the command reports
PASS on code it never looked at. If `git diff --name-only "$BASE"` prints nothing on a branch
with commits, the range is wrong. Fix the range before believing any result.

A grep that returns nothing is evidence only when the diff plausibly could have matched. If the
diff clearly touches `BridgeServer` and the bridge grep is empty, treat the check as wrong, not
the code as clean.

## Verdicts

| Verdict | Meaning |
|---|---|
| **PASS** | The check ran and found no violation. |
| **BLOCK** | A violation, with a `file:line` you read. |
| **N-A** | The diff cannot touch this constraint. Say why in one clause. |
| **UNKNOWN** | Not mechanically decidable, or the check could not be run. Never round this to PASS. |

## The checks

Run these. Each block is one constraint. Read a window around every hit before judging it;
a hit is a candidate, not a verdict.

### 1. No item text in any log

```bash
git diff "$BASE" | grep -nE '^\+.*(Log\.(v|d|i|w|e|wtf)\(|console\.(log|info|warn|error|debug)|println\(|printStackTrace|Exception\(|new Error\()'
```

For each added call site, is the payload a count, an id, a state, a duration, a fixed string, or
at most a host name? BLOCK on a paragraph, sentence, title, shared text, request body, or a URL
path or query reaching any of them, **including inside an exception message** that is later
logged or shown. `Log.e(TAG, msg, e)` prints `e`'s message, so check what built `e`.

False positives: a `new Error` in a test file; a fixed-string message; `uri.host` alone.

### 2. No telemetry

```bash
git diff "$BASE" | grep -niE '^\+.*(analytics|telemetry|sentry|bugsnag|acra|crashlytics|firebase|posthog|mixpanel|amplitude|remote.?config|update.?check|fonts\.googleapis|cdn)'
```

BLOCK on any hit that is not a comment saying there is none.

### 3. Item content stays app-private

```bash
git diff "$BASE" | grep -nE '^[-+].*(allowBackup|getExternal|Environment\.getExternalStorage|MediaStore|WRITE_EXTERNAL_STORAGE|fullBackupContent|dataExtractionRules)'
```

BLOCK on `allowBackup` not `"false"`, any external-storage write, or an item-delete path that
leaves stored text behind (read the delete path if `Store` changed).

### 4. The bridge token is never logged and never in a URL

```bash
git diff "$BASE" | grep -niE '^\+.*(token|Authorization|Bearer)'
```

For each hit, is it on a log, format, toast or URL line? BLOCK on the token reaching a log, a
query string (`?token=`), or an exception message. Showing it in Settings with a Copy button is
the sanctioned surface.

### 5. No cloud TTS, ever

```bash
git diff "$BASE" | grep -niE '^\+.*(api[_-]?key|apiKey|elevenlabs|azure|polly|openai|googleapis|deepgram|speechify|isNetworkConnectionRequired)'
```

BLOCK on an added endpoint, credential or account concept. For `isNetworkConnectionRequired`
hits, confirm a network voice is still never selectable and never a fallback.

### 6. `Fetcher` is the only network call site, and only on share or Retry

```bash
git diff "$BASE" -- '*.ts' '*.tsx' '*.js' '*.jsx' | grep -nE '^\+.*(fetch\(|XMLHttpRequest|WebSocket)'
git diff "$BASE" -- '*.kt' | grep -nE '^\+.*(Socket\(|ServerSocket\(|openConnection|HttpURLConnection|OkHttpClient|URL\()'
```

BLOCK on any JS hit outside the network-guard test or a fixture. BLOCK on any Kotlin hit outside
`Fetcher`, except `ServerSocket` in `BridgeServer` and `URL(` used only for parsing (prove it
never opens). For a `Fetcher` call site change, trace the callers: BLOCK if a fetch is reachable
from anything but a share or an explicit Retry. BLOCK if the network-guard test was narrowed or
deleted.

### 7. `Fetcher` keeps its limits

```bash
git diff "$BASE" | grep -nE '^[-+].*(NO_COOKIES|CookieJar|cache\(|followRedirects|followSslRedirects|callTimeout|MAX_REDIRECTS|MAX_BYTES|5 \* 1024|20)'
```

If `Fetcher` did not change, N-A. Otherwise read it and confirm all of: `CookieJar.NO_COOKIES`,
no cache, `followRedirects(false)` with a manual loop of at most 5 that refuses non-http(s)
targets, a 20 s call timeout, and the 5 MB cap enforced while streaming rather than from
`Content-Length` or after a full read. BLOCK on any one loosened.

### 8. Bridge: loopback only, hostile-input safe

```bash
git diff "$BASE" | grep -nE '^[-+].*(getLoopbackAddress|0\.0\.0\.0|127\.0\.0\.1|ServerSocket|soTimeout|setSoTimeout|Content-Length|Throwable|catch \(e: Exception\)|Access-Control|getQueryParameter)'
```

If `BridgeServer` did not change, N-A. Otherwise read it whole and confirm: binds `127.0.0.1`
explicitly; token checked before the body is read; header cap 16 KiB and body cap 64 KiB, with
`Content-Length` validated before any buffer is sized from it; a per-socket read timeout and
handling off the accept thread; each connection caught at `Throwable`; text from the POST body,
never the URL; CORS headers on every response including errors. BLOCK on any missing.

If the bridge can be run (Kotlin unit tests, or the app on the phone behind `adb forward`), run
the hostile set and report what came back; otherwise say the check was by reading only.

### 9. Rate applied exactly once

```bash
git diff "$BASE" | grep -nE '^[-+].*(setSpeechRate|PlaybackParams|setPlaybackSpeed|playbackRate|\bspeed\b|\brate\b|X-Rate)'
```

The engine applies rate at synthesis. BLOCK on a second speed stage in Read Me's player
(`PlaybackParams`, time-stretch, a JS multiplier), on the bridge resampling, or on any text that
tells the plugin to send its user rate instead of `rate=1.0`. BLOCK if a test that pins rate
values had its expected numbers changed; weakening it is itself the violation.

### 10. Positions are character offsets

```bash
git diff "$BASE" | grep -niE '^\+.*(sentence_?index|sentenceIdx|utterance_?index|currentSentence|position)'
```

BLOCK on a persisted field, column or saved value that addresses a sentence or utterance by
index. A transient in-memory index is fine; a stored one is not.

### 11. The native service owns the playback queue

```bash
git diff "$BASE" -- '*.ts' '*.tsx' | grep -nE '^\+.*(speak|enqueue|next|advance|savePosition|onDone|setInterval|setTimeout)'
```

BLOCK on JS that enqueues the next utterance, advances the queue, or saves a position in
response to a playback event. JS may start a read (sentence list plus start offset) and send
user commands; the service decides what plays next and saves positions in its own `onDone`.

### 12. Trimming is non-destructive

```bash
git diff "$BASE" | grep -niE '^\+.*(DELETE FROM paragraphs|UPDATE paragraphs|cuts|trim)'
```

BLOCK on the trim path deleting or overwriting paragraph text. Cuts are a separate, reversible
set of paragraph indices.

### 13. Separate `TextToSpeech` instances

```bash
git diff "$BASE" -- '*.kt' | grep -nE '^[-+].*(TextToSpeech\(|object .*Tts|lateinit var tts|companion object)'
```

BLOCK on a shared instance between `PlaybackService` and `BridgeServer` unless an ADR in
`docs/adr/` records SPIKE-06's replacement policy.

### 14. F-Droid-clean

```bash
git diff --name-only "$BASE" | grep -E '(build\.gradle|settings\.gradle|gradle\.properties|libs\.versions\.toml|package\.json|AndroidManifest\.xml)'
```

Nothing matched: N-A. Otherwise the resolved tree is the check, not the manifest:

```bash
ls android/gradlew && (cd android && ./gradlew :app:dependencies --configuration releaseRuntimeClasspath) \
  | grep -niE 'com\.google\.android\.gms|firebase|crashlytics|play-services|com\.google\.android\.play'
```

BLOCK on any hit, on a new dependency whose license is not OSI-approved (read it), on a prebuilt
binary without source, or on an `AndroidManifest.xml` that lost the `TTS_SERVICE` `<queries>`
entry, gained a permission beyond what a requirement needs, set `allowBackup` true, or declares a
foreground service without a `foregroundServiceType`. If `android/gradlew` does not exist,
**UNKNOWN**: the tree could not be resolved.

### 15. A green suite is not a claim that something works

Mechanical proxy: is the build on the phone newer than the change?

```bash
adb devices
adb shell dumpsys package io.loopstring.readme | grep -E 'versionName|lastUpdateTime'
git log -1 --format=%ci
```

If the install predates the last commit, or no device is attached, report **UNKNOWN** with "not
exercised on the device since <commit>". If the diff is docs-only, N-A. The applicationId comes
from the Android build file; if it does not exist yet, N-A on the grounds that there is no app.

This proves only that a build was installed, never that anyone used it. Whether the change was
actually exercised is not mechanically checkable; say so rather than upgrading it to PASS.

### 16. Reproduce a bug end-to-end before fixing it

Not mechanically checkable. The available signal:

```bash
git log "$BASE"..HEAD --format='%s%n%b' | grep -niE 'reproduc|repro|observed|before/after|on device'
git diff --name-only "$BASE" | grep -iE 'test'
```

A `fix(...)` commit with no test change and no reproduction note in the body is **UNKNOWN**, and
worth one line in the report. A non-fix change is N-A.

### 17. Never assert a measurement you did not take

```bash
git diff "$BASE" | grep -nE '^\+.*[0-9]+ *(ms|s\b|MB|KiB|KB|GB|x faster|%|RTF|fps)'
```

For each added number with a unit in a comment, in `srs.md`, in an ADR or in a commit message:
was it measured this session, or does it cite where (the plugin repo's `AGENTS.md` counts)?
BLOCK on an unsourced number that justifies a decision. UNKNOWN on a decorative one. Numbers
inside code (a cap, a timeout constant, a test expectation) are N-A, provided they match the
spec's values.

### 18. Repo existence is not install-path existence

```bash
git diff "$BASE" | grep -nE '^\+.*(npm run |npx |gradlew|sdkmanager|adb |\.sh\b)'
```

For each new script, binary or SDK component the change depends on, verify it on this machine:

```bash
node -e "console.log(Object.keys(require('./package.json').scripts||{}).join(' '))" 2>/dev/null || echo "no package.json"
ls android/gradlew ~/Android/Sdk/build-tools ~/Android/Sdk/platforms 2>&1
which adb
```

BLOCK on a dependency on something absent here that the change presents as available. A gate
named in `AGENTS.md` that does not exist yet is not a BLOCK; it is `GATES: NOT YET ESTABLISHED`.

## Output

```markdown
## Constraint Check

Branch: <branch>   Range: <base short hash>..working tree   Files changed: <n>

| # | Constraint | Status |
|---|---|---|
| 1 | No item text in logs | PASS |
| ... | | |

Constraints <list> are N-A: <one clause why>.

### Blocks
- **`<file>:<line>`** - <what the code now does, and the input that makes it violate the
  constraint>
  - Constraint: #8
  - Fix: <remediation>

### Unknown
- **#15** - no device attached; not exercised on the phone since <commit>.

### All clear
No violations found in the current changes. <n> constraints checked, <n> N-A, <n> unknown.
```

Collapse untouched rows to a single line. Do not print a grep that returned nothing.

One honesty rule: a constraint you could not check is **UNKNOWN**, not PASS. Reporting PASS on
something you did not check is the failure this command exists to prevent, and it is worse than
reporting nothing, because `/ship` believes it.
