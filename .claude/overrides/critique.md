# /critique - Read-Me additions

Rules are `AGENTS.md` "Non-negotiables" 1-14 and "Verification rules" 15-18; numbers in brackets
below are those rules. This file adds only the checks a grep of the rule text would not find.

## Usage: `architecture` mode (adds to the file list)

The `ReadMeSpeech` module surface, `PlaybackService`, `BridgeServer`, `Fetcher`, `Store` and the
TS `library` facade.

## Your stance: how to reproduce here

The bridge prototype's worst defects (the `::1` bind, the unauthenticated OOM, the stalled accept
thread) were invisible on reading. For `extract`, `segment`, trim math, `Fetcher` or
`BridgeServer`, run it: Jest against a fixture, the Kotlin unit test against a local server, or
`curl` against the bridge on the phone through `adb forward`.

## Step 4: failure-shaped inputs (L1 q1, L2 phase 2)

A paywalled link, a page over 5 MB, a redirect loop, a redirect to `file:` or `intent:`, a share
that is a URL plus a sentence, a non-http scheme, a chunked body with no `Content-Length`, a page
with no `<article>`, shared text with no blank lines, a URL with a tracking query, a CJK article,
a one-word paragraph, an item trimmed to nothing, an empty share, a position past the end of a
paragraph a later trim removed.

Pre-mortem shapes: logcat held a sentence; playback stopped on screen lock because JS was
suspended; the plugin's read came out at 4x; `/health` stopped answering while one client hung; a
reopened item resumed at the wrong sentence after a runtime update; a trimmed paragraph was gone
for good; F-Droid rejected a transitive Google dependency.

Layering to check in L2 phase 3 (`CONTEXT.md` "Layers"): JS never touches the network, JS never
drives playback, Kotlin owns the database, the bridge knows nothing about items, segmentation
lives only in `segment`.

## What to attack: per risk area

- **Logs [1, 4].** `Log.e(TAG, msg, e)` prints `e`'s message: check what built `e`, including an
  `Error` crossing the native bridge into a `console.error`. `throw IOException("... $url")` leaks
  the path and query. Grep the token's variable name and `Authorization` on log/format lines; never
  `?token=`.
- **Network [5-7].** JS hit outside a fixture or the network-guard test: BLOCK (a library that
  fetches on its own counts). Kotlin hit outside `Fetcher`: BLOCK, except `ServerSocket` in
  `BridgeServer` and a parse-only `URL(` (prove it never opens). Trace every `Fetcher` caller. The
  5 MB cap must be enforced while streaming, not after `body.string()`. The network-guard test must
  still cover both halves. A voice with `isNetworkConnectionRequired()` stays unselectable.
- **Bridge [8].** Read `BridgeServer` whole. Bind via `InetAddress.getByName("127.0.0.1")`, never
  `getLoopbackAddress()`/`0.0.0.0`/unbound. Token before body; header cap 16 KiB (431), body cap
  64 KiB (413), malformed or negative `Content-Length` 400. Read timeout per socket, handling off
  the accept thread, catch at `Throwable`. `OPTIONS` answered and CORS (`Access-Control-Allow-Origin:
  http://localhost` + Expose-Headers) on every response including 401/413/500. WAVs deleted on every
  exit path and swept at service start. When possible run the hostile set: no token,
  `Content-Length` `2000000000`, `-5`, `abc`, `65537`, a 20 KB unterminated header, one silent
  client while `/health` is polled.
- **Rate [9].** `setSpeechRate` is the only lever in `PlaybackService`; any `PlaybackParams`,
  `setPlaybackSpeed`, time-stretch or JS multiplier is BLOCK. Bridge passes `rate` once and reports
  `X-Rate`, never resamples. Any text telling the plugin to send its user rate instead of `rate=1.0`
  is BLOCK. A changed expected value in a rate-pinning test is BLOCK.
- **Positions, queue, trim, instances [10-13].** Persisted sentence/utterance index: BLOCK. Position
  saves happen in the service's `onDone`, not on a JS event. Trim never deletes or overwrites
  paragraph text; item delete removes everything [3]. Separate `TextToSpeech` instances (ADR 0004).
- **Distribution [14].** The resolved Gradle tree is the check, not `package.json`:
  `(cd android && ./gradlew :app:dependencies --configuration releaseRuntimeClasspath) | grep -niE 'com\.google\.android\.gms|firebase|crashlytics|play-services|com\.google\.android\.play'`.
  Could not resolve: say so, never "clean". OSI licenses read, not inferred (ADR 0002 exceptions);
  no prebuilt `.so`/`.aar`/`.jar` without source; RN autolinking pulling Play Services. Manifest:
  `INTERNET` the only network permission, `TTS_SERVICE` `<queries>` present, `allowBackup="false"`,
  every foreground service has a `foregroundServiceType` (a changed bridge type cites SPIKE-01 /
  ADR 0005).
- **Concurrency and lifetime.** `UtteranceProgressListener` callbacks are on a binder thread; stop
  cancels queued utterances and utterance ids are checked so a late `onDone` cannot save onto the
  next item; every `TextToSpeech` and the bridge executor shut down on destroy; JS screens remove
  native subscriptions on unmount; app start moves stale `fetching` items to `fetch-failed`.

## Spec alignment and measurements

`CONTEXT.md` records owner decisions `srs.md` may not have absorbed; a change built on one cites
it. The plugin repo's `AGENTS.md` is a valid citation for the measurements `srs.md` rests on.

## Rules: green suite

"Exercised" means `npm run device:install` and real use on the phone, holding
`.claude/device.lock/` per `AGENTS.md` "Device work: the one-phone slot".
