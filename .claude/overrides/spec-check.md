# /spec-check - Read-Me additions

`TARGET` is the phone. `srs.md` R-M14 is the rule in as many words: a requirement is met only when
observed on a real device, recorded with date, device and build (`AGENTS.md` rule 15).

## Baseline

`AGENTS.md` "Known state", subsection `### Spec-check baseline`. **None recorded yet**: the first
`all` run offers it.

R-M06 (on-device speech only) and R-M09 (privacy and network) are also non-negotiables 1-7. If
either moved off FULLY MET, stop the audit and report that first.

## Check tags (read the requirement in `srs.md`; this is a pointer)

| Tag | Requirements |
|---|---|
| CODE | R-M08, R-C02 |
| CODE + DEVICE | R-M02, R-M03, R-M04, R-M05, R-M06, R-M09, R-M11, R-M12, R-M13 (DEVICE only for the licenses screen), R-M14, R-S03, R-S05 |
| DEVICE | R-M01, R-M07, R-M10, R-S01, R-S02, R-S04, R-C01, R-C04 |
| BLOCKED | R-C03 (iOS, out of v1) |

File hints (`git ls-files src android` if moved): R-M01 `src/ui/ListScreen.tsx`; R-M02 manifest,
`ShareActivity.kt`, `intake/`, `fetch/Recovery.kt`, `src/intake/`; R-M03 `Fetcher` + local-server
tests; R-M04 `src/extract/` + saved-page fixtures; R-M06 manifest, `PlaybackService`; R-M07
`PlaybackService`, `PlaybackQueue`, `src/ui/ReaderScreen.tsx`; R-M08 `src/segment/`,
`playback/Segmenter.kt`; R-M09 network-guard test, manifest, logcat during `/verify`; R-M11
`PlaybackService`, `Store`; R-M12 `BridgeServer` + Kotlin hostile-input tests (plugin side is
NRL-130 in the plugin workspace, not filed here); R-M13 resolved Gradle tree; R-M14 test dirs,
`npm run device:smoke`.

## Spikes

In `srs.md` "# Spikes (before implementation)". Gating:

| Spike | Gates |
|---|---|
| SPIKE-01 | R-M12, R-M13 SDK levels, R-S04 |
| SPIKE-02 | R-M04 |
| SPIKE-03 | R-M08 |
| SPIKE-04 | R-M13 |
| SPIKE-05 | R-M07 gap target |
| SPIKE-06 | R-M07, R-M12, non-negotiable 13 |

## Method additions

- Manifest claims: read the merged manifest from a release build
  (`android/app/build/intermediates/merged_manifests/`), not only the source one.
- R-M07's gap target is checkable only by `GAP_MINUTES=10 npm run device:gap` on the reference
  device, pasting the numbers.
- Device observation: `.claude/device.lock/`, `adb devices` shows exactly one device,
  `npm run device:install`, drive over adb as `/verify` describes, never log item text.
