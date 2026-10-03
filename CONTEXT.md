# Read Me - architecture map

The design as specified in `srs.md` ("Method"). Built so far: the TS text pipeline (`intake`,
`extract`, `segment`, `trim`; Phase 1). Everything else below is the intended shape; this file
describes it so commands, plans and reviews share one vocabulary. Update it when
the code changes the architecture, not for bug fixes.

## Layers

```text
TypeScript (Hermes)                       Kotlin: one native module, ReadMeSpeech
-----------------------------------       ------------------------------------------
intake    classify shared text, item      ShareReceiver  ACTION_SEND text/plain hand-off
                                          Store          SQLite (android.database.sqlite)
extract   HTML -> title/paragraphs        Fetcher        the only network call
segment   paragraphs -> sentences         PlaybackService foreground service, MediaSession,
library   typed facade over the native DB                 sentence queue, own TextToSpeech
trim      cuts and position remapping
reader    four screens; a VIEW of the     BridgeServer   127.0.0.1:8787 for the Obsidian
          playback service                               plugin, own TextToSpeech
```

`trim` is the R-M05 math beside `srs.md`'s five TypeScript modules: pure functions over a cut
set, used by `reader` and `library`.

Rules of the layering:

- **JS never touches the network.** `Fetcher` returns HTML to `extract` through the module.
- **JS never drives playback.** It hands `PlaybackService` the sentence list and a start offset;
  the service queues, advances, saves positions, and emits events JS may or may not be alive
  to receive.
- **Kotlin owns the database** (ADR 0001). Intake, `Fetcher` and `PlaybackService`
  write while JS may not be running, so JS reads and writes only through `ReadMeSpeech`.
- **The bridge knows nothing about items.** It turns POSTed text into WAV, nothing more.
- **Segmentation lives only in `segment`.** Everything persisted addresses text by
  (paragraph index, character offset).

## Data flow

```text
share -> intake -> [link] Fetcher -> fetched (body app-private) -> extract -> library
                                                        (ready | extract-poor | fetch-failed)
                -> [text] split on blank lines -> library (ready)
open item -> Trim (first time) -> Reader -> segment -> PlaybackService queue -> TTS
onDone(n) -> position saved (paragraph, offset) -> next utterance
app start -> stale `fetching` items -> fetch-failed (interrupted)
Obsidian plugin -> POST /synthesize?rate=1.0 -> WAV -> plugin Player applies rate
```

## Vocabulary

| Term | Meaning |
|---|---|
| item | One thing to read: a link item or a text item |
| paragraph | Extracted block of an item (`p`, `heading`, `li`), indexed from 0 |
| cut | A paragraph index the user removed in Trim; reversible |
| kept text | Paragraphs not cut, in order |
| sentence | Derived by `segment`; never stored, never addressed by index |
| position | (item, paragraph index, character offset) |
| utterance | One sentence queued to `TextToSpeech` with a stable id |
| inter-utterance gap | Wall time `onDone(n)` -> `onStart(n+1)`, measured in the service |
| bridge | `BridgeServer`; the Obsidian plugin's route to native TTS |
| reference device | Pixel 9 Pro XL, GrapheneOS, Android 17 (see `srs.md`) |

## Source layout

Real tree since the scaffold (2026-10-01):

```text
App.tsx, index.js            React Native entry; App.tsx is a plain item list until the reader lands;
                             index.js installs the release network stub first
index.devcheck.js            devcheck bundle entry (never the product entry)
src/types.ts                 Paragraph, ParagraphKind, Sentence, Position
src/intake/                  R-M02: shared text to link, text or empty; R-M04: shared text to paragraphs
src/extract/                 R-M04: HTML to title, site, byline, paragraphs, poor flag (Readability over linkedom)
src/segment/                 R-M08: paragraphs to sentences with offsets, Intl or regex fallback, 400 cap;
                             R-M11: which sentence a position resumes at
src/trim/                    R-M05: cut operations and position remapping
src/native/                  NativeReadMeSpeech.ts: the TurboModule spec (codegen)
src/library/                 TS facade over ReadMeSpeech: items, actions, fetched drain, change events;
                             playback.ts: R-M07/R-M11 play plan (start sentence from the saved offset), controls, playback events
src/ui/                      R-M01 screens: model.ts (routes, R-M10 actions, list rows, reader
                             spans; pure), List, Trim, Reader, Settings, Licenses; theme.ts (the
                             LoopString Material 3 palette, light and dark, and the type scale)
                             and Text.tsx (the themed Text every screen uses, with a tone)
src/net/                     R-M09: release runtime stub over fetch, XMLHttpRequest, WebSocket
src/devcheck/                devcheck bundle only: fingerprints and the DevCheck root component
                             (fixtures.generated.ts is gitignored, written by make-devcheck-fixtures.mjs)
__tests__/                   Jest tests; fixtures/pages (real public-domain pages), fixtures/text
android/app/src/main/java/io/loopstring/readme/   Kotlin:
  MainActivity, MainApplication (start-up recovery), ShareActivity (R-M02 share target),
  ReadMeSpeechModule + ReadMeSpeechPackage (the TurboModule),
  intake/ (classify and split shared text), store/ (SQLite items, paragraphs, cuts, positions;
  lifecycle per ADR 0007; Settings: the rate, voice and bridge (on/off, port, token)), fetch/ (Fetcher, FetchWorker on WorkManager, Recovery),
  playback/ (PlaybackService: the mediaPlayback foreground service, media session, focus, noisy,
  wake lock; PlaybackQueue: the sentence queue; TtsSpeaker; EngineProbe: engine and offline voices before any play;
  PlaybackHub: in-process hand-off and
  ADR 0004's speaking flag; Policies; Utterances; MediaButtonClaim; GapStats),
  bridge/ (R-M12, hosted by PlaybackService while enabled: BridgeServer: plain-JVM loopback HTTP
  server, caps, token, CORS, drain; Http: request-head parsing; TtsSynth: the bridge's own
  TextToSpeech; BridgeFiles: cache WAVs and the start-up sweep; BridgeView: Settings state and
  the sensitive-clip Copy)
scripts/                     build-release.sh (APK + commit stamp), device-install.sh, device-smoke.sh,
                             fdroid-scan.sh, check-licenses.mjs, lib/device.sh (one-phone slot),
                             devcheck.sh + devcheck-report.mjs (pipeline on Hermes vs Node),
                             device-intake-e2e.sh (share a link, a text and a dead link),
                             device-playback-e2e.sh (play, pause, media keys screen-off, archive),
                             device-gap.sh (R-M07 gaps, 10 min at 2x, battery + Doze),
                             device-ui-e2e.sh (every screen), make-notices.mjs (Settings > Licenses asset),
                             device-bridge-e2e.sh + obsidian-cdp-bridge.mjs (R-M12 over adb forward and
                             from inside Obsidian's WebView),
                             make-devcheck-fixtures.mjs, fetch-page-fixtures.sh
docs/adr/                    ADRs (0001 Kotlin owns the DB, 0002 CC-BY data packages, 0004 TTS
                             contention, 0005 foreground service type, 0006 Readability stall guard,
                             0007 item lifecycle, 0008 bridge text limit)
.github/workflows/ci.yml     CI: js job + android job
```

**Identifiers:** application id `io.loopstring.readme`; launcher `io.loopstring.readme/.MainActivity`;
JS component `ReadMe`. Log tags: `ReadMe` (native, product code), `ReactNativeJS` (JS console),
`ReadMeSpike` (spike branches only).

## Known structural gaps

- Everything but the text pipeline: native module, storage, playback, bridge, screens. The
  pipeline's functions are tested and run on Hermes, but no requirement is met until a user can
  reach it on the device.
- Spike answers that bind later phases (`srs.md`, "Spikes"): playback and bridge synthesis
  serialize on the reference engine, so the bridge answers 503 while playback speaks (ADR 0004);
  one `mediaPlayback` service hosts both (ADR 0005); `segment` has no `Intl.Segmenter` on Hermes;
  `extract` is Readability over linkedom on the JS thread, so a heavy page freezes the screen
  for seconds (ADR 0006). Not covered by any
  spike: Android 14-16, targetSdk 37, battery power and Doze, the Google TTS engine.
- Roadmap findings F11-F21 are applied (`docs/superpowers/plans/2026-10-01-roadmap.md`;
  ADR 0007, REA-14).
