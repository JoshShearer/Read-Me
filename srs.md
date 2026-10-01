# SPEC-001 - Read Me

Status: draft for owner review, 2026-10-01. Revised the same day to resolve critique findings
F1-F10 (see "Critique resolutions" at the end).

## Background

Read Me is a light Android reader that speaks text aloud with the phone's own on-device
text-to-speech (TTS) engine. Reading material arrives mostly through Android's Share sheet:
an article link from a browser, or selected text from any app. The app extracts the readable
part, lets the user trim what is left, and reads it at speed, offline, with the screen off.

It has a second job. Obsidian's Android app gives plugins no speech API, and the
`local-tts-reader` Obsidian plugin (repo `JoshShearer/Note-Reader-Local`) cannot reach
Android's native TTS from inside Obsidian's WebView. Read Me runs a loopback HTTP bridge that
the plugin calls to have sentences synthesized by the native engine. This is "the Obsidian
connection".

The central product promise:

> Share it, trim it, listen to it. On the phone, offline, no account, no cloud voice.

### Why this exists (measured, not assumed)

All figures below were measured on 2026-09-30 / 2026-10-01 on a Pixel 9 Pro XL (GrapheneOS,
Android 17) and are recorded in `Note-Reader-Local/AGENTS.md`, section "Android playback
throughput, and the native-TTS bridge". They are cited, not re-measured here.

- Kokoro (neural TTS) inside Obsidian's Android WebView misses 2x playback by 8.2x. The
  WebView is not cross-origin isolated, so ONNX Runtime is capped at one WASM thread, and
  there is no WebGPU adapter. A plugin cannot fix either.
- Native Android TTS through a prototype bridge (`Note-Reader-Local/companion/android/`)
  ran at RTF 0.116 to 0.169 at rate 1.0 and outran 2x playback by 4.0x to 4.2x, offline.
- The bridge path works end to end from inside Obsidian (`CapacitorHttp` POST to
  `127.0.0.1` returned 200 with audio).
- An app targeting API 30+ must declare the `android.intent.action.TTS_SERVICE` query or it
  cannot bind a TTS engine. Proven causal by single-variable A/B on the device above.
- The engine on that device reports no word timings (`onRangeStart` fired zero times), so
  highlighting is per sentence.

### Relationship to the plugin spec

The plugin's `srs.md` R-M01 forbids requiring "a companion Android APK" or "a separately
running local server". Read Me is an optional companion, not a requirement of the plugin: the
plugin must keep working without it. That plugin repo still needs an ADR and an `srs.md`
amendment recording the optional bridge engine. That work is tracked there (NRL-130), not here.

---

# Requirements

MoSCoW. IDs are stable; cite them in tickets, commits and ADRs. "MUST" items define v1.

## Must Have

### R-M01 - Light, list-first interface

The app MUST open to the reading list. The UI MUST be limited to four screens in v1:

1. **List**: unread items (title, site or "Shared text", length estimate, progress) and an
   Archive view.
2. **Trim**: the extracted paragraphs of one item, with cut controls (R-M05).
3. **Reader**: the kept text with the current sentence highlighted and transport controls.
4. **Settings**: voice, default rate, bridge on/off and pairing token, storage.

There MUST be no account, sign-in, onboarding carousel, feed, or recommendations.

### R-M02 - Share-sheet intake

The app MUST register as an `ACTION_SEND` target for `text/plain`.

- If the shared text is a single URL, or contains exactly one `http(s)` URL (the common
  "Title https://..." shape), it MUST become a **link item**.
- Otherwise it MUST become a **text item**, stored as shared.
- More than one URL in shared text: text item (do not guess which link was meant).
- The item MUST appear in the list immediately, in a `fetching` state for links.
- Intake MUST NOT require the app to be open in the foreground afterwards; the share
  completes and returns the user to the source app.
- The fetch MUST run in native code that survives the share activity finishing (R-M03),
  not in the JS runtime.
- **Recovery:** on every app start, any item still in `fetching` whose fetch is not
  actually running MUST move to `fetch-failed` with reason `interrupted`, so no item can sit
  in `fetching` forever.

### R-M03 - Fetching a shared link

Sharing a link to Read Me IS the user's request to fetch it. No other code path may initiate
a network request (R-M09).

The fetch MUST be performed by the native `Fetcher` (Kotlin, on the OkHttp client React Native
already bundles, so no new dependency), not by JS `fetch` or `XMLHttpRequest`. Reasons, from
React Native's source: its `XMLHttpRequest` defaults `withCredentials` to `true`
(`Libraries/Network/XMLHttpRequest.js:174`), `whatwg-fetch` only overrides that for
`credentials: 'include'` or `'omit'`, and `NetworkingModule.kt:383` drops the persistent
cookie jar only when credentials are off, so a plain JS `fetch` sends and stores cookies. JS
`fetch` also buffers the whole body and exposes no redirect cap, so the limits below could not
be enforced from JS.

- Exactly one HTTP(S) GET of the shared URL. Redirects are followed manually, at most 5;
  a 6th is a failed fetch. Only `http` and `https` targets are followed.
- The client MUST use `CookieJar.NO_COOKIES` and no cache, and a test MUST assert that a
  response's `Set-Cookie` is not sent on a following request.
- No JavaScript executed, no images, stylesheets, fonts or sub-resources fetched.
- Limits: 20 s total timeout (call timeout, not per-read), 5 MB response cap enforced while
  streaming: the read aborts at the 5,242,881st byte regardless of `Content-Length`.
  Exceeding either is a failed fetch.
- The fetched body is handed to JS extraction (R-M04) through the native module.
- A failed fetch MUST leave the item in a `fetch-failed` state with Retry and
  "Share the text instead" guidance (R-M10). It MUST NOT be silently deleted.

### R-M04 - Article extraction

Fetched HTML MUST be reduced to a title, an optional site name and byline, and an ordered list
of paragraphs, using Mozilla Readability (or an equivalent reader-mode algorithm) running
on-device.

- Headings and list items become their own paragraphs; tables, figures, code blocks, image
  captions and embedded media are dropped in v1.
- Raw HTML MUST be discarded after extraction; only the extracted structure is stored.
- If extraction yields fewer than 3 paragraphs or under 500 characters, the item MUST enter
  an `extract-poor` state that still shows what was found and offers "Share the text
  instead". Pages built by JavaScript or behind paywalls are expected to land here.
- Text items skip fetching and extraction: shared text is split into paragraphs on blank
  lines (single newlines are joined).

### R-M05 - Trimming

The Trim screen MUST show the item's paragraphs and support, at minimum:

- Cut / restore a single paragraph (tap).
- "Cut everything after this".
- "Start here" (cut everything before this).

Trimming MUST be non-destructive: the original paragraphs are kept and cuts are stored as
a separate set of paragraph indices, so every cut is reversible. Trim opens automatically the
first time an item is opened; afterwards it is one tap away from the Reader. Changing cuts
after reading has begun MUST keep the reading position at the same character position if its
paragraph is still kept, else move to the start of the next kept paragraph.

### R-M06 - On-device speech

Speech MUST use Android's `android.speech.tts.TextToSpeech` only.

- The manifest MUST declare the `TTS_SERVICE` intent query.
- Any voice whose `isNetworkConnectionRequired()` is true MUST be hidden from selection
  and never used, including as a fallback.
- If no engine binds, or no offline voice exists, the app MUST show a blocking state that
  explains this and links to Android's TTS settings (R-M10).

### R-M07 - Reading and playback

- Kept paragraphs are split into sentences (R-M08). The **native side owns the queue**:
  when playback starts, JS hands `PlaybackService` the item's full ordered sentence list
  (paragraph index, start and end offsets, text) and a start position. The service queues
  utterances with `QUEUE_ADD` far enough ahead that boundaries do not produce audible gaps,
  and keeps doing so with the screen off whether or not the JS runtime is alive.
- In-app playback MUST use its own `TextToSpeech` instance, separate from the bridge's
  (R-M12), so pausing or flushing one never discards the other's queued work.
- Controls: play/pause, previous sentence, next sentence, back 1 paragraph, rate.
- **Rate is applied exactly once**, by the TTS engine (`setSpeechRate`). Nothing else may
  scale playback speed. Range 0.5x to 4.0x, default 2.0x, step 0.1x.
- The current sentence MUST be highlighted and kept in view while the Reader is visible.
  The service emits sentence-start events to JS when JS is attached; when JS reattaches it
  reads the current position from the service. No word highlight in v1.
- Playback MUST continue with the screen off and the app in the background, through a
  foreground service with a media session, so lock-screen, notification and headset
  controls work (play/pause, next, previous).
- Audio focus MUST be requested; playback pauses on transient loss (a call, a navigation
  prompt) and on headphones disconnecting.

**Performance target (to be measured, R-M14):** at 2.0x, sustained over 10 minutes of
offline playback on the reference device, the **inter-utterance gap**, defined as the wall
time from `onDone(n)` to `onStart(n+1)` measured in the service, MUST be at most 300 ms at
the 95th percentile and at most 1,000 ms at the maximum, with no stall. The 300 ms figure is
a target, not a measurement; SPIKE-05 measures the baseline and may revise it with an
amendment here.

### R-M08 - Sentence segmentation

Sentences MUST be produced by `Intl.Segmenter` with sentence granularity if the JS runtime
provides it, with a regex fallback otherwise. A sentence longer than 400 characters MUST be
split at the last clause boundary (`, ; :` or whitespace) before the cap. Every sentence MUST
carry its paragraph index and character offsets so highlight and resume are exact.
Segmentation is not assumed stable across app or runtime versions, so nothing persisted may
address a sentence by its index (R-M11).

### R-M09 - Privacy and network

These are promises. Breaking one is a release blocker.

1. **No cloud TTS, ever**, and no automatic fallback to a network voice (R-M06).
2. **The only outbound network use is R-M03.** No analytics, crash reporting, telemetry,
   update checks, remote config, web fonts or CDN loads. A test MUST enforce this (R-M14).
3. **No item text in any log.** Logs carry counts, ids, states and durations only. Error
   messages MUST NOT be built from item text or URLs' paths and queries; a host name is
   the most a log line may carry.
4. **Item content stays in app-private storage.** `android:allowBackup="false"`; no
   external-storage writes in v1.
5. The INTERNET permission is required for R-M03 and is the only network permission
   requested.

### R-M10 - Visible failure states

Every failure MUST be represented as a persistent item or app state, not only a transient
toast:

| State | Shown as | User actions |
|---|---|---|
| `fetch-failed` | item badge + reason class (timeout, too large, too many redirects, HTTP status, offline, interrupted) | Retry, Delete |
| `extract-poor` | item badge, partial text visible | Read anyway, Delete |
| no TTS engine / no offline voice | blocking card on Reader | Open TTS settings |
| bridge port in use | Settings bridge row | Retry |
| bridge request rejected | HTTP status to caller; counter in Settings | none |

### R-M11 - Reading position and archive

The position (item id, paragraph index, character offset within that paragraph) MUST be
saved by the service after every completed sentence and on pause/stop, and restored when the
item is reopened by resuming at the start of the sentence that contains that offset under the
current segmentation. A sentence index MUST NOT be persisted. An item that reaches its
last kept sentence MUST move to Archive (not be deleted). Archive items can be restored or
deleted. Deleting an item deletes all of its stored text.

### R-M12 - Obsidian bridge

When the user enables it in Settings, the app MUST serve a loopback HTTP bridge for the
`local-tts-reader` plugin, hosted by the same foreground service as playback.

- Binds `127.0.0.1` explicitly (not `getLoopbackAddress()`, which returned `::1` on
  Android 17), on a fixed default port 8787, configurable.
- Every route except `GET /health` requires `Authorization: Bearer <token>`. The token is
  128 bits of randomness, generated once, shown in Settings with a Copy button, persisted
  until the user regenerates it. It MUST NOT be logged.
- Header bytes capped at 16 KiB (431), body capped at 64 KiB (413), Content-Length
  validated (400), token checked **before** the body is read (401), every connection's
  failure caught at `Throwable` so no request can kill the service.
- Every accepted socket has a read timeout (10 s). Connections are handled on a worker
  pool; synthesis itself is serialized. A silent client MUST NOT block `/health` or other
  requests.
- The bridge MUST use its own `TextToSpeech` instance, separate from in-app playback
  (R-M07), so both can run at once.
- Text arrives in the POST body, never the URL.
- Synthesized files live in the app cache and MUST be deleted on every exit path,
  including failure and timeout; stale files are swept at service start.
- CORS, so both `fetch` and `CapacitorHttp` work from Obsidian's WebView, whose page origin
  is `http://localhost`:
  - `OPTIONS` preflight answered with `Access-Control-Allow-Origin: http://localhost`,
    `Access-Control-Allow-Methods: GET, POST`, `Access-Control-Allow-Headers:
    Authorization, Content-Type`.
  - **Every** response, including errors, carries `Access-Control-Allow-Origin:
    http://localhost` and `Access-Control-Expose-Headers: X-Synth-Ms, X-Rate`, or a
    WebView `fetch` cannot read the status or headers.
  - Any other `Origin` gets no CORS headers.
- The bridge contract is specified under "Bridge contract" below and is versioned.

The bridge MUST run only while enabled, and its foreground notification MUST say so.

### R-M13 - Distribution

- v1 ships as a signed APK on GitHub Releases, then on F-Droid.
- The build MUST be F-Droid-clean from v1: no Google Play Services, Firebase, Crashlytics,
  proprietary SDKs, or binary blobs without source; all dependencies under OSI licenses, except CC-BY-4.0 data-only packages listed
  under ADR 0002;
  builds from a clean checkout with documented commands.
- Third-party license notices MUST ship in the app (Settings > Licenses).
- `minSdk` and `targetSdk` are set by SPIKE-01 findings; `targetSdk` follows the current
  F-Droid and Android requirements at release time.

### R-M14 - Verification

A green unit suite is not proof that the app works. A requirement is met only when observed
on a real device, and the observation is recorded with the date, device and build.

- **Unit (TypeScript):** URL detection, extraction against saved real pages (stored as
  fixtures, not fetched in tests), paragraph splitting, segmentation, trim math and position
  remapping, library persistence.
- **Unit (Kotlin):** the bridge's hostile inputs (no token, huge/negative/garbage
  Content-Length, oversized headers, silent client), token-before-body, cache cleanup.
- **Network guard:** a test that fails if any JS source references `fetch`,
  `XMLHttpRequest` or `WebSocket`, or if any Kotlin file other than `Fetcher` opens a
  connection.
- **Fetcher (Kotlin, against a local test server):** cookies are never sent back, the 6th
  redirect fails, a chunked 6 MB body with no `Content-Length` aborts at the cap, a
  non-http redirect target is refused.
- **Bridge CORS:** an error response (401) still carries `Access-Control-Allow-Origin` and
  is readable from a WebView `fetch`.
- **On-device, scripted over adb and repeatable** (in the spirit of the prototype's
  `measure.sh`):
  1. Share a real article from a browser, trim, play at 2.0x, lock the screen, unlock,
     confirm position.
  2. Airplane mode, 10 minutes at 2.0x: record inter-sentence gaps and stalls against
     R-M07's target.
  3. Obsidian plugin reads a note through the bridge while Read Me is backgrounded.
  4. The R-M12 hostile-input set against the running service; the service survives.

## Should Have

### R-S01 - Voice and engine selection
List offline voices (and engines, if more than one is installed), with a preview button.
Rebinding to another engine uses the three-argument `TextToSpeech` constructor.

### R-S02 - Sleep timer
Stop after N minutes or at the end of the current item.

### R-S03 - Markdown export of an item
Export one item (title, source URL, date, kept paragraphs) as a markdown file via the
Android share sheet. This is the data path the later "Save to Obsidian" (R-C01) builds on.

### R-S04 - Bridge launch from Obsidian
A `readme://bridge/start` deep link (or equivalent intent) that the plugin can open to start
the bridge when it is not running. Feasibility from Obsidian's WebView is unverified.

## Could Have

### R-C01 - Save to Obsidian
Write an item into the user's vault as a markdown note (via the Storage Access Framework
folder picker, or an Obsidian URI). Owner decision 2026-10-01: after v1.

### R-C02 - Per-site trim rules
Remember "always drop this kind of block from this site". After v1.

### R-C03 - iOS
Same React Native codebase, standalone reader only. iOS likely needs no bridge, because
WKWebView normally exposes `speechSynthesis`; unverified inside Obsidian for iOS.

### R-C04 - Shared files
Accept shared `.txt`, `.md`, `.html` and `.epub` files.

## Won't Have - v1

- Cloud or neural TTS of any kind.
- Accounts, sync, or any server component.
- Google Play distribution.
- Word-level highlight.
- In-app browser, feeds, or RSS.
- AI or model-based trimming (owner decision 2026-10-01: rule-based only).
- Vault access (R-C01 is later).

---

# Method

## Stack

Bare React Native (no Expo), TypeScript, Hermes. Owner decision 2026-10-01: bare over Expo so
the Android project and the F-Droid build are fully under our control.

One hand-written native module, `ReadMeSpeech` (Kotlin), owns everything the JS layer cannot
or must not do: TTS, the sentence queue, the foreground service, the media session, the
share-intent hand-off, the one network fetch, and the bridge.

```text
                 TypeScript (Hermes)
 Share sheet ──► intake ──────────────► extract ──► library (SQLite) ◄──► reader UI
                   │                       ▲                                 │
 ──────────────────┼───── JS / native ─────┼─────────────────────────────────┼─────
                   ▼                       │                                 ▼
                 Fetcher ──── HTML ────────┘                         PlaybackService
                 (only network call, R-M03)                          foreground service,
                                                                     MediaSession, sentence
                 BridgeServer  127.0.0.1:8787, own TextToSpeech      queue, own TextToSpeech
                   ▲
                   └── Obsidian plugin (local-tts-reader)

                 All native parts live in one Kotlin module, ReadMeSpeech.
```

## TypeScript modules

| Module | Responsibility | Depends on |
|---|---|---|
| `intake` | Classify shared text (R-M02), create the item | native share hand-off |
| `extract` | HTML to title/byline/paragraphs (R-M04) | Readability + a JS DOM implementation |
| `segment` | Paragraphs to sentences with offsets (R-M08) | `Intl.Segmenter` or fallback |
| `library` | Typed facade over the native Store (ADR 0001): items, cuts, positions, archive; markdown export | `ReadMeSpeech` |
| `reader` | Screens; a view of `PlaybackService`, never its driver (R-M07) | `library`, `segment`, `ReadMeSpeech` |

There is no JS network module. The R-M09 network guard asserts that no JS source references
`fetch`, `XMLHttpRequest` or `WebSocket`, and that the only Kotlin network call site is
`Fetcher`.

## Data model

Stored by the Kotlin Store (ADR 0001).

```text
Item       id, kind(link|text), url?, title, site?, byline?, createdAt,
           state(fetching|fetch-failed|extract-poor|ready|archived), failReason?
Paragraph  itemId, index, kind(p|heading|li), text
Cut        itemId, paragraphIndex            (presence = cut)
Position   itemId, paragraphIndex, charOffset, updatedAt
```

Sentences are derived, never stored or addressed by index, because segmentation can change
between runtime versions (R-M08). A position is a character offset and resolves to whichever
sentence contains it under the current segmentation.

## Playback

JS segments the kept text and hands `PlaybackService` the item's full sentence list once per
play (or again after a trim change). From then on the service is authoritative: it keeps at
least 3 utterances queued, `onStart` emits a sentence-start event (to JS if attached) and
`onDone` writes the position (R-M11) and tops up the queue. JS is a view of the service, not
its driver, so screen-off playback does not depend on the JS runtime.

Pause = `stop()` on the playback engine plus remembering the current sentence; resume
re-queues from that sentence's start (Android TTS has no true pause). A rate change flushes
the playback engine and refills from the current sentence. None of this touches the bridge's
engine instance.

## Bridge contract (v1)

| Route | Method | Auth | Response |
|---|---|---|---|
| `/health` | GET | none | `{ok, version:1, ttsReady, engine, voice, port}` |
| `/synthesize?rate=<f>` | POST | token | `audio/wav`; headers `X-Synth-Ms`, `X-Rate` |

- Body: UTF-8 text, at most 64 KiB.
- `rate` defaults to 1.0. Whatever rate is requested is applied by the engine, so the
  returned audio is already at that rate.
- **The `local-tts-reader` plugin MUST call with `rate=1.0`** and let its Player apply the
  user's rate. Its Player passes a rate only to engines with `ownsPlayback: true`
  (`src/audio/player.ts:344`) and sets `playbackRate` on every buffer it plays
  (`player.ts:391`); a WAV-returning engine is a buffer engine, `ownsPlayback: false`, and
  `ownsPlayback: true` with a buffer is the combination that shipped 2.25x once. Measured
  RTF at 1.0 (0.116 to 0.169, cited above) already clears 2x playback. Any caller that
  requests `rate != 1.0` MUST NOT also speed up playback.
- No word timings are promised. The plugin's engine declares `timing: "none"`.
- Voice and engine are Read Me settings (R-S01), not request parameters. `/health` reports
  which voice is in use so the plugin can display it. The prototype's `/speak`, `/voices`,
  `/engines` and `/setengine` routes are dropped.

## Foreground service

Android 14+ requires a declared foreground-service type. Playback uses `mediaPlayback`.
Whether the bridge, which serves audio to another app and plays none itself, may run under
`mediaPlayback` or needs `specialUse` is SPIKE-01's question; F-Droid distribution places no
store review on `specialUse`.

---

# Spikes (before implementation)

Each spike answers one question on the reference device and records the answer here.

- **SPIKE-01 - Service type and background bridge.** Can the bridge synthesize while Read Me
  is backgrounded and Obsidian is foreground, under which foreground-service type, on
  Android 14+ and Android 17?
- **SPIKE-02 - Extraction on Hermes.** Does Readability run on Hermes with a pure-JS DOM
  (e.g. linkedom), at acceptable speed for a 5 MB page? If not, extract in a hidden WebView.
  Pass line (owner, 2026-10-01): on the reference device in a release build, a 5 MB page
  extracts in 10 s or less with no crash, and every page under 1 MB in 1.5 s or less (median
  of 3 runs).
- **SPIKE-03 - `Intl.Segmenter` on Hermes.** Present? If not, R-M08's fallback is the path.
- **SPIKE-04 - F-Droid-clean bare React Native.** A hello-world release build with the
  chosen SQLite library passes an F-Droid-style scan (no proprietary dependencies) from a
  clean checkout.
- **SPIKE-05 - Gapless queueing at 2x.** Measure the inter-utterance gap as R-M07 defines
  it (`onDone(n)` to `onStart(n+1)`), with `QUEUE_ADD` and two `TextToSpeech` instances
  alive, on the reference device.
- **SPIKE-06 - Two engine instances.** Do two `TextToSpeech` instances in one process,
  bound to the same engine, run `speak()` and `synthesizeToFile()` concurrently without one
  cancelling or serializing behind the other? If not, R-M07/R-M12 need a contention policy
  instead (for example, the bridge answers 503 while Read Me is playing).

# Reference device

Pixel 9 Pro XL, GrapheneOS, Android 17, engine `app.grapheneos.speechservices` (one offline
`en_US` voice). Every requirement marked met cites an observation on this device or another
named one.

# Owner decisions log

| Date | Decision |
|---|---|
| 2026-10-01 | Read Me is a standalone reader that also hosts the Obsidian TTS bridge. |
| 2026-10-01 | Items live in Read Me's own list for v1; Save to Obsidian is later. |
| 2026-10-01 | Trimming: automatic extraction, then a manual trim view. Rule-based, no AI. |
| 2026-10-01 | Distribution: GitHub APK and F-Droid; no Play in v1. iOS later. |
| 2026-10-01 | Bare React Native, not Expo. |
| 2026-10-01 | SPIKE-02 pass line set (5 MB in 10 s or less; under 1 MB in 1.5 s or less). |
| 2026-10-01 | Kotlin owns the database; JS goes through ReadMeSpeech (ADR 0001). |
| 2026-10-01 | CC-BY-4.0 data-only packages allowed by name (ADR 0002). Spike probe code stays on its spike branch; only answers merge. |

# Critique resolutions (2026-10-01)

| Finding | Resolution |
|---|---|
| F1 bridge rate vs plugin Player | Plugin calls `rate=1.0`; Player applies rate (Bridge contract) |
| F2 RN fetch sends cookies | Native `Fetcher` with `NO_COOKIES`; no JS networking (R-M03, Method) |
| F3 shared TTS instance | Separate instances for playback and bridge (R-M07, R-M12, SPIKE-06) |
| F4 sentence-index positions | Positions are character offsets (R-M05, R-M08, R-M11, data model) |
| F5 stuck `fetching` | Native fetch plus start-up recovery to `fetch-failed` (R-M02) |
| F6 JS-driven queue | Service owns the sentence queue (R-M07, Playback) |
| F7 unenforceable limits | Streaming cap and manual redirect cap in `Fetcher` (R-M03) |
| F8 CORS on responses | Allow-Origin and Expose-Headers on every response (R-M12) |
| F9 gap target baseline | Gap defined as `onDone(n)` to `onStart(n+1)`, p95 and max (R-M07) |
| F10 `voice=` parameter | Removed; voice is a Read Me setting reported by `/health` |
