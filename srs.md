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
throughput, and the native-TTS bridge". They are cited, not re-measured here. The reference
device has two TTS engines installed (checked over adb 2026-10-02): the default
`app.grapheneos.speechservices`, and `com.google.android.tts`, which also offers network
voices and is therefore the engine R-M06's network-voice filter is tested against.

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
4. **Settings**: voice, default rate, bridge on/off and pairing token, storage. Below Android
   14 also the TTS engine; from Android 14 the system's engine is used and named (ADR 0010).

There MUST be no account, sign-in, onboarding carousel, feed, or recommendations.

Every screen MUST follow the system light or dark mode and stay legible in both: text at
a WCAG contrast of 4.5:1 or better against its background, text drawn faint on purpose (a
cut paragraph, a disabled control) at 3:1 or better, and the status bar icons visible
(amended 2026-10-03, REA-22: React Native drew black text in dark mode and the light status
bar hid its icons; `npm run device:themes` measures both modes on the phone).
The colours are the LoopString web app's Material 3 palette (amended 2026-10-03, REA-24).

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
  in `fetching` forever. Recovery does not touch `fetched` items; JS extracts every `fetched`
  item on each start (ADR 0007).

### R-M03 - Fetching a shared link

Sharing a link to Read Me IS the user's request to fetch it, and the user's Retry of that
link's failed fetch is the same request. No other code path may initiate a network request
(R-M09).

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
- The fetched body is written to app-private storage and the item moves to `fetched` until JS
  extraction (R-M04) reads it through the native module (ADR 0007).
- A failed fetch MUST leave the item in a `fetch-failed` state with Retry, "Share the text
  instead" guidance and Delete (R-M10). It MUST NOT be silently deleted.

### R-M04 - Article extraction

Fetched HTML MUST be reduced to a title, an optional site name and byline, and an ordered list
of paragraphs, using Mozilla Readability (or an equivalent reader-mode algorithm) running
on-device.

- Headings and list items become their own paragraphs; tables, figures, code blocks, image
  captions and embedded media are dropped in v1.
- Raw HTML MUST be discarded after extraction: the stored body of a `fetched` item is deleted
  in the same transaction that stores the extracted structure (ADR 0007).
- If extraction yields fewer than 3 paragraphs or under 500 characters, the item MUST enter
  an `extract-poor` state that still shows what was found and offers Read anyway, "Share the
  text instead" guidance and Delete (R-M10). Pages built by JavaScript or behind paywalls are
  expected to land here.
- A page on which Readability is predicted to stall (over 4 s under 1 MiB, over 8 s above, or
  nested deeper than 200 levels) skips Readability and is `extract-poor` with the page's own
  text (ADR 0006). Slower pages still run Readability.
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
- Which engine binds follows ADR 0010: the system's engine from Android 14; below 14 the one
  chosen in Settings, else the default when it can be bound, else the first engine that can.

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
- After a user pause, those controls MUST keep working for at least 30 minutes; after that
  the session may end and resuming may need the app (ADR 0009, amended 2026-10-03, REA-25).
- Audio focus MUST be requested; playback pauses on transient loss (a call, a navigation
  prompt) and on headphones disconnecting.

**Performance target (to be measured, R-M14):** at 2.0x, sustained over 10 minutes of
offline playback on the reference device, the **inter-utterance gap**, defined as the wall
time from `onDone(n)` to `onStart(n+1)` measured in the service, MUST be at most 300 ms at
the 95th percentile and at most 1,000 ms at the maximum, with no stall. The 300 ms figure is
a target, not a measurement; SPIKE-05 measures the baseline and may revise it with an
amendment here.

**Measured (Phase 3, 2026-10-02, reference device, build 59324f5):** n=92 gaps, p50 7 ms,
p95 17 ms, max 31 ms, no stall, no engine error, over 10 minutes at 2.0x on battery
(`dumpsys battery unplug`) in forced deep Doze with the screen off and a partial wake lock
held while speaking (`GAP_MINUTES=10 npm run device:gap`). The target stands.

### R-M08 - Sentence segmentation

Sentences MUST be produced by `Intl.Segmenter` with sentence granularity if the JS runtime
provides it, with a regex fallback otherwise. A sentence longer than 400 characters MUST be
split at the last clause boundary (`, ; :` or whitespace) before the cap. Every sentence MUST
carry its paragraph index and character offsets so highlight and resume are exact.
Segmentation is not assumed stable across app or runtime versions, so nothing persisted may
address a sentence by its index (R-M11).

Amended 2026-10-09 (REA-41, ADR 0011): the regex fallback also exists in Kotlin
(`playback/Segmenter.kt`), used only when continuous play (R-S05) loads the next item with no
JS. A parity test holds it to the TS fallback's exact offsets on the committed fixtures.

### R-M09 - Privacy and network

These are promises. Breaking one is a release blocker.

1. **No cloud TTS, ever**, and no automatic fallback to a network voice (R-M06).
2. **The only outbound network use is R-M03.** No analytics, crash reporting, telemetry,
   update checks, remote config, web fonts or CDN loads. A test MUST enforce this (R-M14):
   no JS source references `fetch`, `XMLHttpRequest` or `WebSocket`, and no Kotlin file other
   than `Fetcher` makes an outbound connection (`Socket(`, `openConnection`, `OkHttpClient`);
   `ServerSocket` is allowed only in `BridgeServer`. Because bundled third-party JS is not
   scanned, release builds also replace `fetch`, `XMLHttpRequest` and `WebSocket` with
   throwing stubs before the app loads.
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
| `fetch-failed` | item badge + reason class (timeout, too large, too many redirects, HTTP status, offline, network, unsupported redirect, bad URL, interrupted) | Retry, "Share the text instead" guidance, Delete |
| `extract-poor` | item badge, partial text visible | Read anyway, "Share the text instead" guidance, Delete |
| no TTS engine / no offline voice | blocking card on Reader | Open TTS settings |
| bridge port in use | Settings bridge row | Retry |
| bridge request rejected | HTTP status to caller; counter in Settings | none |

### R-M11 - Reading position and archive

The position (item id, paragraph index, character offset within that paragraph) MUST be
saved by the service after every completed sentence and on pause/stop, and restored when the
item is reopened by resuming at the start of the sentence that contains that offset under the
current segmentation. A sentence index MUST NOT be persisted. An item that reaches its
last kept sentence MUST move to Archive (not be deleted): `archivedAt` is set and the item
keeps its state (ADR 0007). Archive items can be restored (which clears `archivedAt`) or
deleted. Deleting an item deletes all of its stored text.

### R-M12 - Obsidian bridge

When the user enables it in Settings, the app MUST serve a loopback HTTP bridge for the
`local-tts-reader` plugin, hosted by the same foreground service as playback.

- Binds `127.0.0.1` explicitly (not `getLoopbackAddress()`, which returned `::1` on
  Android 17), on a fixed default port 8787, configurable.
- Every route except `GET /health` requires `Authorization: Bearer <token>`. `/health` stays
  unauthenticated by owner decision (2026-10-02, roadmap F21): any app on the device can see
  that the bridge runs and which engine and voice it uses, but no item data. The token is
  128 bits of randomness, generated once, shown in Settings with a Copy button, persisted
  until the user regenerates it. It MUST NOT be logged.
- Header bytes capped at 16 KiB (431), body capped at 64 KiB (413), Content-Length
  validated (400), token checked **before** the body is read (401), every connection's
  failure caught at `Throwable` so no request can kill the service. A response sent before
  the body is read (401, 413, 503) is followed by shutting down the output and draining the
  unread body, at most 64 KiB within the read timeout, before the socket closes, so the
  client reads the status instead of a TCP reset (ADR 0004).
- Every accepted socket has a read timeout (10 s). Connections are handled on a worker
  pool; synthesis itself is serialized. A silent client MUST NOT block `/health` or other
  requests.
- The bridge MUST use its own `TextToSpeech` instance, separate from in-app playback
  (R-M07). The two do not run concurrently on the reference engine (SPIKE-06), so while
  playback is speaking the bridge answers `POST /synthesize` with 503, and a synthesis in
  flight when playback starts is stopped and answered 503 (ADR 0004).
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
- `minSdk` 24 and `targetSdk` 36 (owner, 2026-10-02: SPIKE-01 found nothing that sets them;
  24 is React Native 0.87's floor). `targetSdk` follows the current F-Droid and Android
  requirements at release time.

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

### R-S05 - Continuous play
Added 2026-10-08 (owner request, REA-41). A Settings switch, off by default. With it on, when
an item reaches its last kept sentence it is archived as R-M11 requires, and the service then
starts the next unread item in List order (the next row down) without user action, from its
saved position or its start. Items that are not `ready`, or are archived, are skipped; when no
unread item is left, playback stops. Trim never interrupts the chain: an item that has never
been opened is read with all paragraphs kept, and R-M05's first-open Trim applies only when the
user opens an item. The service makes the handover itself (R-M07: screen off, JS not
involved). R-S02's "at the end of the current item" overrides it. With the switch off,
behaviour is R-M11's: archive and stop.

Amended 2026-10-09 (REA-41, ADR 0011): "List order" is `created_at DESC, id DESC`, and the next
item is the first unarchived `ready` item after the finished one under that order that has at
least one kept sentence, evaluated at the handover; it never wraps to the top, so items shared
during the chain are not picked up. An item read this way keeps `opened_at` unset and gets no
cuts. A pause, Stop, engine error or failed archive ends the chain; an item being deleted is
skipped. The service segments the next item itself with a Kotlin port of R-M08's fallback.

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

*Partly done (REA-30, 2026-10-03):* `.md` and `.txt` are accepted. Obsidian opens a note as
`ACTION_VIEW` of a `content://` URI typed `text/markdown` (observed on the Huawei VRD-W09 with
Obsidian 1.13.8); other apps send `ACTION_SEND` with `EXTRA_STREAM`. The file is read once
through the sender's grant (5 MB cap while reading), becomes a text item, and is never fetched,
even when it holds only a link. Markdown is read as prose (front matter, code and comments
dropped; headings and list items kept as paragraph kinds; links and wikilinks read as their
text). `.html` and `.epub` remain open.

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
           state(fetching|fetched|fetch-failed|extract-poor|ready), failReason?,
           openedAt?, archivedAt?                      (ADR 0007)
Body       itemId, html                       (only while state = fetched)
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

Continuous play (R-S05, ADR 0011): when the switch is on and the last kept sentence finishes,
the service archives the item and, in the same queue step, loads the next unread item with
sentences from its own Kotlin segmenter, without leaving the foreground or releasing focus,
the wake lock or the media session in between. This is the one case where the service builds a
sentence list itself.

Pause = `stop()` on the playback engine plus remembering the current sentence; resume
re-queues from that sentence's start (Android TTS has no true pause). A rate change flushes
the playback engine and refills from the current sentence. None of this touches the bridge's
engine instance.

## Bridge contract (v1)

| Route | Method | Auth | Response |
|---|---|---|---|
| `/health` | GET | none | `{ok, version:1, ttsReady, engine, voice, port, busy, maxChars}` |
| `/synthesize?rate=<f>` | POST | token | `audio/wav`; headers `X-Synth-Ms`, `X-Rate`. `503` `{"error":"busy","reason":"playback"}` while Read Me is playing (ADR 0004) |

- Body: UTF-8 text, at most 64 KiB, and at most `maxChars` characters (ADR 0008; 413
  `{"error":"too-long","maxChars":N}` otherwise).
- Errors are JSON `{"error":"<code>"}`: 400 (bad request, length, rate, UTF-8, empty), 401, 404,
  405, 413, 431, 500, 503 (`busy` or `tts-not-ready`), 504 (synthesis over 120 s) (ADR 0008).
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
The bridge, which serves audio to another app and plays none itself, runs in the same service
under the same `mediaPlayback` type, including bridge-only sessions (SPIKE-01, ADR 0005).
`specialUse` also passed SPIKE-01 and is the fallback if a later Android enforces
`mediaPlayback`'s semantics.

---

# Spikes (before implementation)

Each spike answers one question on the reference device and records the answer here.

- **SPIKE-01 - Service type and background bridge.** Can the bridge synthesize while Read Me
  is backgrounded and Obsidian is foreground, under which foreground-service type, on
  Android 14+ and Android 17?
  **Answer (2026-10-01; Pixel 9 Pro XL, GrapheneOS, Android 17 (API 37); app targetSdk 36;
  build 2ed01ac on spike/rea-0-background-bridge; `scripts/spike-bridge.sh media` and
  `scripts/spike-bridge.sh special`; engine `app.grapheneos.speechservices`):** yes, under
  either type. The bridge kept synthesizing for 5 minutes with Read Me backgrounded behind
  Obsidian under `mediaPlayback` and under `specialUse`. One service with `mediaPlayback` hosts
  both playback and the bridge (ADR 0005).
  Observed, identical for both types: the service started in the foreground
  (`types=0x00000002` for `media`, `types=0x40000000` for `special`). From inside Obsidian's
  WebView (origin `http://localhost`), before and after the 5 minutes: `fetch` `/health` 200;
  `CapacitorHttp` `/synthesize` 200 with `X-Rate` `1.0`; `fetch` `/synthesize` 200 (so the
  OPTIONS preflight works) with `X-Rate` `1.0` and `X-Synth-Ms` 304 to 600; a `fetch` with no
  token got a readable 401, not a CORS error. 20 of 20 `adb forward` calls over 5 minutes
  returned 200. The bridge's per-request log showed importance 125 (foreground service) on
  every request: 24 `/synthesize` 200, 2 `/health` 200, 2 OPTIONS 204, 2 `/synthesize` 401.
  The token appeared 0 times in logcat. A first run at build 8a222f2 found it once, in adbd's
  own log of the `adb shell am start ... --es token` command line the script used to deliver
  it (Read Me never logged it); the script now sends that command on stdin, which adbd logs as
  `raw:`. With port 8787 held by the prototype bridge, the spike bridge reported
  `{"bridge":"bind-failed","error":"BindException"}` and the process stayed up: `adb shell pidof
  io.loopstring.readme`, run right after the bind-failed result (plan Task 8 Step 6 on
  `spike/rea-0-background-bridge`), printed pid 8731 (`.claude/scratch/SPIKE-01/step6.txt`,
  gitignored). One check at one moment; the pid before the bind was not recorded, so a crash
  and restart in those seconds is not excluded. The service's cache sweep ran at start (`{"deleted":0}`) (both at build 8a222f2).
  Not established: Android 14-16 (only the Android 17 reference device was available),
  targetSdk 37, screen-off bridge use while Obsidian itself is backgrounded, the real plugin's
  request pattern.
  Consequence: Phase 5's bridge lives in PlaybackService's foreground service under
  `mediaPlayback` (ADR 0005); delivering a secret to the app over `adb shell` argv leaks it
  into logcat, so device scripts never do. SPIKE-01 found nothing that sets `minSdk` or
  `targetSdk` (R-M13): the scaffold's 24 and 36 stand until an owner decision.
- **SPIKE-02 - Extraction on Hermes.** Does Readability run on Hermes with a pure-JS DOM
  (e.g. linkedom), at acceptable speed for a 5 MB page? If not, extract in a hidden WebView.
  Pass line (owner, 2026-10-01): on the reference device in a release build, a 5 MB page
  extracts in 10 s or less with no crash, and every page under 1 MB in 1.5 s or less (median
  of 3 runs). Amended 2026-10-02 (ADR 0006): the 1.5 s is a target, reported; the hard line
  for a page under 1 MB is 5 s.
  **Answer (2026-10-01; Pixel 9 Pro XL, GrapheneOS, Android 17 (API 37); app targetSdk 36;
  build 6139cf0 on spike/rea-0-readability-hermes; `scripts/spike-js.sh extract 300 3`):**
  passes. `@mozilla/readability` 0.6.0 over `linkedom` 0.18.13 runs on Hermes in the release
  build within the pass line on every fixture, with no crash, so extraction stays in JS with
  linkedom; no hidden WebView, no ADR 0003.
  Observed (HTML to paragraphs, `parseMs + readabilityMs + blocksMs`, runs 1/2/3, median):
  synthetic page of real prose at the cap, 5,241,998 bytes: 7982/8193/9337 ms, median 8193
  (limit 10000); Gutenberg *Pride and Prejudice*, 852,590 bytes: 1196/1251/1349, median 1251
  (limit 1500); Wikipedia "Speech synthesis", 705,129 bytes: 1293/1354/1431, median 1354 (limit
  1500); MDN "SpeechSynthesis", 157,106 bytes: 200/206/224, median 206 (limit 1500). Readability
  is the largest share (5502 ms of the 5 MB median run). Every run found a title and 28 to
  14,363 blocks. Each fixture was slower in each successive run (the 5 MB page by 17% from run 1
  to run 3), and Wikipedia's median sits at 90% of its limit.
  Build facts Phase 1 inherits: `@babel/plugin-transform-export-namespace-from` in
  `babel.config.js` (htmlparser2, via linkedom, ships `export * as ns`), and Jest's
  `transformIgnorePatterns` must transform `linkedom|css-select|css-what|htmlparser2|domhandler|domutils|dom-serializer|domelementtype|entities|nth-check|boolbase`.
  Readability is Apache-2.0 and linkedom ISC; the licence check passed with them added.
  Not established: pages that need JS to render, non-Latin scripts, memory headroom with the
  app's real UI loaded; the cause of the run-to-run slowdown (thermal or heap state; the three
  runs were back to back, each in a fresh process); timings on a slower device.
  Consequence: Phase 1 `extract` uses Readability over linkedom on Hermes. Since a median under
  1 MB is at 90% of its limit and the runs drifted upward, Phase 1 re-measures `extract` on the
  device with its own code. The JS thread is busy for seconds on a large page, so Phase 4's
  list shows an extracting state rather than waiting on JS.
- **SPIKE-03 - `Intl.Segmenter` on Hermes.** Present? If not, R-M08's fallback is the path.
  **Answer (2026-10-01; Pixel 9 Pro XL, GrapheneOS, Android 17 (API 37); app targetSdk 36;
  build 5e74cc0 on spike/rea-0-hermes-segmenter; `scripts/spike-js.sh segmenter 120 3`):**
  absent. `Intl.Segmenter` is not a function on Hermes in the RN 0.87.1 release build, so
  R-M08's regex fallback is the only path on the device.
  Observed: three runs each printed `{"spike":"segmenter","present":false,"hermes":true}`;
  `scripts/spike-js.sh ping` printed `{"spike":"ping","ok":true,"hermes":true}` (confirming the
  probe ran on Hermes and release `console.log` reaches logcat). Abbreviation splits (`Dr.`,
  `p.m.`, `U.S.`) and the ~150k-character timing could not be measured, since there is no
  segmenter to measure. The first `ping` after installing the build timed out (no
  `SPIKE_RESULT` within 120 s); the next four `ping` runs and every later probe run succeeded.
  Not established: whether a later Hermes adds `Intl.Segmenter`; segmentation of non-English
  text and CJK; behaviour on Hermes versions other than the one in RN 0.87.1; the cause of the
  one first-launch timeout.
  Consequence: Phase 1 `segment` ships the regex fallback as the device path and owns
  abbreviation, decimal and quote handling itself. Per F19 its tests run the fallback forced
  (Node has `Intl.Segmenter`, so Jest would otherwise test a path the phone never takes), and
  an on-device Hermes check covers it. The `Intl.Segmenter` branch stays, per R-M08's text, for
  runtimes that provide it. No ADR: R-M08 already names this fallback.
- **SPIKE-04 - F-Droid-clean bare React Native.** A hello-world release build with the
  chosen SQLite library passes an F-Droid-style scan (no proprietary dependencies) from a
  clean checkout.
  **Answer (2026-10-01; build host only, no device needed; RN 0.87.1 scaffold, build a091d37
  on feature/rea-0-scaffold; fresh `git clone`, `npm ci`, `npm run build:release`,
  `scripts/fdroid-scan.sh`, then fdroidserver 2.4.5 `scanner.scan_source` over the clone after
  `npm ci`):** no proprietary dependencies, but not F-Droid-clean as is: the release build runs
  a prebuilt `hermesc`, and the tree after `npm ci` holds binaries an F-Droid recipe must remove
  or replace.
  Observed: the documented commands built `app-release.apk` (54055877 bytes, stamp a091d37 clean)
  with no extra step. `scripts/fdroid-scan.sh` printed all four sections and `== result: CLEAN`:
  source scan of the git export `source problems: 0`; APK dexdump scan found no non-free
  classes; resolved Gradle tree `none` for gms, firebase, crashlytics and play-services; npm
  `450 production packages, 1 recorded data exception(s)` (`caniuse-lite`, ADR 0002). The
  F-Droid scanner over the clone after `npm ci` (build output excluded) reported 46 errors in
  four packages: three prebuilt `hermesc` (Linux, macOS, Windows) in `node_modules/hermes-compiler`
  (RN's Gradle plugin runs the Linux one to compile the release bundle unless
  `react.hermesCommand` names another); five `fb-dotslash` launchers; 36 iOS-only
  `fbt_language_pack.bin` files in `node_modules/react-native/React/I18n`; and unknown maven repos
  in `react-native/ReactAndroid/publish.gradle` and `react-native-safe-area-context/android/build.gradle`.
  It also warned on nine Windows DLLs beside `hermesc.exe`.
  SQLite: platform `android.database.sqlite`, no dependency (ADR 0001).
  Not established: an actual F-Droid build server run; whether F-Droid accepts the
  `react-android` and `hermes-android` AARs (prebuilt native libraries, resolved from Maven
  Central) or requires them built from source; a hermesc built from source producing the same
  bundle. The caches in `~/.gradle` and `~/.npm` were warm, so this was a clean checkout, not a
  clean machine.
  Consequence: R-M13 holds for npm licences and for the absence of known non-free Gradle
  dependencies (gms, firebase, crashlytics, play-services); no gate checks the licences of Maven
  artifacts yet. The Phase 6 F-Droid recipe needs a
  `prebuild` that builds `hermesc` from the Hermes source and sets `react.hermesCommand`, and
  `scandelete` for the dotslash, iOS and Windows binaries; `scripts/fdroid-scan.sh` should then
  scan the tree after `npm ci` with the same deletions, since a git export misses everything in
  `node_modules`. No ADR: no decision changes.
- **SPIKE-05 - Gapless queueing at 2x.** Measure the inter-utterance gap as R-M07 defines
  it (`onDone(n)` to `onStart(n+1)`), with `QUEUE_ADD` and two `TextToSpeech` instances
  alive, on the reference device.
  **Answer (2026-10-01; Pixel 9 Pro XL, GrapheneOS, Android 17 (API 37); app targetSdk 36;
  build 46a4f8e on spike/rea-0-gapless-2x; `scripts/spike-gap.sh single 10` and
  `scripts/spike-gap.sh idle2 10`; engine `app.grapheneos.speechservices`, offline (airplane
  mode, Wi-Fi off, confirmed by a failed ping), screen off, app backgrounded, foreground service
  type `mediaPlayback`, no wake lock):** gapless. With two instances alive the gap is far inside
  R-M07, and the 300 ms target stands.
  Observed: `idle2` (playback instance plus a second bound, idle instance; `load.initStatus` 0):
  10 min, 127 utterances, 0 errors, gap p50 4 ms, p95 11 ms, max 22 ms, 0 stalls, `reason`
  `done`, `usPerCharP50` 34923. `single` (one instance, baseline): 10 min, 128 utterances, 0
  errors, gap p50 2 ms, p95 8 ms, max 16 ms, 0 stalls, `reason` `done`, `usPerCharP50` 34923.
  Neither run hung or stalled without a wake lock, but on USB power with
  `stay_on_while_plugged_in=7` the CPU may never have been allowed to sleep, so this does not
  show that playback needs no wake lock on battery. The screen was off during both runs
  (`mWakefulness=Dozing`, read over adb mid-run). A direct `adb shell am start-foreground-service` of the unexported service was
  refused with `Error: Requires permission not exported from uid 10346`, so spike runs start it
  through the exported activity.
  Not established: a second instance that is synthesizing rather than idle (SPIKE-06); other
  engines (`com.google.android.tts` is installed but was not the default); rates other than
  2.0x; targetSdk 37; playback with a MediaSession and audio focus; battery power and Doze (the
  phone was on USB power, `USB powered: true`, `stay_on_while_plugged_in=7`).
  Consequence: R-M07 unchanged. PlaybackService can queue with `QUEUE_ADD` three utterances
  ahead under a `mediaPlayback` foreground service. Whether it needs a partial wake lock on
  battery and under Doze is open; Phase 3 measures it. Battery and Doze: measured in Phase 3
  with a wake lock held while speaking (R-M07, "Measured").
- **SPIKE-06 - Two engine instances.** Do two `TextToSpeech` instances in one process,
  bound to the same engine, run `speak()` and `synthesizeToFile()` concurrently without one
  cancelling or serializing behind the other? If not, R-M07/R-M12 need a contention policy
  instead (for example, the bridge answers 503 while Read Me is playing).
  **Answer (2026-10-01; Pixel 9 Pro XL, GrapheneOS, Android 17 (API 37); app targetSdk 36;
  build 46a4f8e on spike/rea-0-tts-instances; `scripts/spike-gap.sh concurrent 10` and
  `scripts/spike-gap.sh synthonly 3`; engine `app.grapheneos.speechservices`, offline, screen
  off, app backgrounded, `mediaPlayback` foreground service, no wake lock):** no. The two
  instances neither cancel each other nor leak rate, but they serialize: playback stalls while
  the other instance synthesizes, and synthesis slows behind playback. The owner chose the
  contention policy: the bridge answers 503 while Read Me is playing (ADR 0004).
  Observed: both runs valid (`load.initStatus` 0, `load.rejected` 0; 47 and 92 synths).
  `concurrent` against the decision rule: cancellations `stopsBeforeFinish` 0, `errors` 0,
  `load.errors` 0 (pass); gap p95 2751 ms against `idle2`'s 11 ms (fail; p50 4 ms, max 3549 ms,
  16 stalls in 119 utterances); `load.synthP50` 13852 ms against `synthonly`'s 1676 ms (fail,
  8.3 times); `load.bytesPerCharP50` 3207 against 2988 (pass, +7.3%, so no rate leak into the
  1.0 instance); `usPerCharP50` 35397 µs against `idle2`'s 34923 (pass, +1.4%, so no rate leak
  into playback). The one `stops` in `synthonly` is the end-of-run `stop()`.
  Not established: two different engines (`com.google.android.tts` might not serialize); the
  bridge's real request pattern (plugin chunk sizes and pauses); targetSdk 37; battery power and
  Doze (USB power).
  Consequence: R-M12 and the bridge contract amended (503 while playing, `busy` on `/health`).
  Non-negotiable 13 stands: two instances, with mutual exclusion on top. Phase 5's bridge and
  Phase 3's PlaybackService share a playing flag; the plugin handles 503 (NRL-130).

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
| 2026-10-01 | TTS contention (SPIKE-06): the bridge answers 503 while Read Me is playing (ADR 0004). |
| 2026-10-01 | One foreground service, type `mediaPlayback`, hosts playback and the bridge (SPIKE-01, ADR 0005). |
| 2026-10-02 | Reference device lists both TTS engines; the R-M06 network-voice test uses `com.google.android.tts` (roadmap F18). |
| 2026-10-02 | REA-14 (delegated to Claude by the owner): F12 `fetched` state and F15 `openedAt`/`archivedAt` (ADR 0007); F13 Retry is the same request; F14 failure actions unified; F16 runtime JS guard and Kotlin outbound-only rule; F21 unauthenticated `/health` accepted; minSdk 24, targetSdk 36; ADR 0004's in-flight rule kept. |
| 2026-10-02 | Readability runs on every real page; F17's 1.5 s becomes a target, 5 s the hard line; only a predicted stall skips Readability (ADR 0006). |

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
