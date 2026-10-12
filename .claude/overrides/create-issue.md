# Override: /create-issue - Read Me

## Step 1: extra type `spike`

| Type | Meaning | Label | Priority |
|---|---|---|---|
| `spike` | One question from `srs.md` "Spikes" to answer on the reference device | `Improvement`, title **leads** with the ID: `SPIKE-02: Readability on Hermes` | High (2) |

A spike's deliverable is the recorded answer in `srs.md` "Spikes" (date, device, build,
measurement), plus an ADR in `docs/adr/` when it changes a decision. Its branch code is
throwaway. Say so in its acceptance criteria. Its `## Requirement` is
`SPIKE-0N - the question, verbatim from srs.md`.

## Cross-repo work

Anything that changes the plugin's side of the bridge belongs in the plugin's workspace as or
under NRL-130, filed from `note-reader-local`. From here file only the Read Me half, link NRL-130
by URL in the body, and add `Plugin-side work: NRL-130` to `## Out of scope`. Never file through
`linear-nrl`.

## Step 3: reproduction routes

- **Unit run**: Jest for `intake`, `extract`, `segment`, `library`, trim math;
  `(cd android && ./gradlew testDebugUnitTest)` for `Fetcher`, `BridgeServer`, the queue. Saved
  real pages as extraction fixtures.
- **On the phone**: take the slot per AGENTS.md "Device work: the one-phone slot", `adb devices`
  shows exactly one device, install, drive with `adb shell am start` / `input` /
  `uiautomator dump`, `adb logcat` filtered by the app's tag. Name device, Android version, build
  and TTS engine in `## Context`. Never paste item text from logcat or a dump (non-negotiable 1).
- **The bridge**: `adb forward tcp:<port> tcp:8787`, then the exact `curl` with exact status and
  headers, token redacted (non-negotiable 4). Plugin side over CDP as AGENTS.md describes; record
  which route was used.

## Step 4: area to constraint (AGENTS.md "Non-negotiables" numbers)

| Area touched | Name |
|---|---|
| Any logging, exception messages | 1, 2 |
| `Fetcher` or anything network-shaped | 6, 7 |
| `BridgeServer` | 4, 8, 13 |
| Rate, speed, `PlaybackService` | 9, 11 |
| Voices, engine selection | 5 |
| `segment`, `library`, `Store`, positions, trim | 3, 10, 12 |
| Gradle, `package.json`, `AndroidManifest.xml` | 14; permissions only `INTERNET` plus foreground-service/media needs; `TTS_SERVICE` query present |
| Any performance claim | Verification rule 17 |

## Step 5: acceptance criteria wording

The on-target line reads: what must be observed on the phone after `npm run device:install`.
Name the device.
