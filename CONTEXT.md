# Read Me - architecture map

The design as specified in `srs.md` ("Method"). **Nothing below is built yet**; this file
describes the intended shape so commands, plans and reviews share one vocabulary. Update it when
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
reader    four screens; a VIEW of the     BridgeServer   127.0.0.1:8787 for the Obsidian
          playback service                               plugin, own TextToSpeech
```

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
share -> intake -> [link] Fetcher -> extract -> library (ready | extract-poor | fetch-failed)
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
App.tsx, index.js            React Native entry (template screen until the reader lands)
__tests__/                   Jest tests
android/app/src/main/java/io/loopstring/readme/   Kotlin (MainActivity, MainApplication; ReadMeSpeech planned)
scripts/                     build-release.sh (APK + commit stamp), device-install.sh, device-smoke.sh,
                             fdroid-scan.sh, check-licenses.mjs, lib/device.sh (one-phone slot)
docs/adr/                    ADRs (0001 Kotlin owns the DB, 0002 CC-BY data packages)
.github/workflows/ci.yml     CI: js job + android job
```

Planned: `src/` for TS modules (`intake/`, `extract/`, `segment/`, `library/`, `reader/`).

**Identifiers:** application id `io.loopstring.readme`; launcher `io.loopstring.readme/.MainActivity`;
JS component `ReadMe`. Log tags: `ReadMe` (native, product code), `ReactNativeJS` (JS console),
`ReadMeSpike` (spike branches only).

## Known structural gaps

- The whole tree. Every requirement is unmet until built and observed on the device.
- Six spikes (`srs.md`) can each change a design decision; SPIKE-06 can replace the
  separate-instances rule.
- Roadmap findings F11-F21 (`docs/superpowers/plans/2026-10-01-roadmap.md`) propose SRS
  amendments not yet applied, including a `fetched` state between fetch and extraction (F12).
