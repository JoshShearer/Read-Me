# ADR 0005: One foreground service, type `mediaPlayback`, hosts playback and the bridge

- Status: accepted
- Date: 2026-10-01
- Deciders: owner (decision rule set in the Phase 0 plan, Task 8), measured by SPIKE-01
- Amends: srs.md "Foreground service"; records SPIKE-01's answer

## Context

Android 14+ requires every foreground service to declare a type. Playback is `mediaPlayback`.
The bridge serves audio to another app and plays none itself, so it was open whether it may
run under `mediaPlayback` or needs `specialUse` (srs.md, "Foreground service"; SPIKE-01).

SPIKE-01 measured both on the reference device (srs.md, SPIKE-01 answer; build 2ed01ac): a
spike bridge in a foreground service of each type, Read Me in the background, Obsidian in the
foreground, called from inside Obsidian's WebView with `fetch` and `CapacitorHttp` and over
`adb forward` for about 5 minutes. Both types passed every check, with the process at
importance 125 (foreground service) throughout.

## Decision

Read Me has one foreground service, declared `foregroundServiceType="mediaPlayback"`, holding
the `FOREGROUND_SERVICE_MEDIA_PLAYBACK` permission. It hosts PlaybackService's playback and,
when enabled, the bridge, including bridge-only sessions in which nothing plays in Read Me.
No `specialUse` type, permission or subtype property is declared.

This follows the plan's decision rule: if `mediaPlayback` passes everything, the bridge shares
it, so there is one service and one type.

## Consequences

- One service type and one notification channel; R-M12's "hosted by the same foreground service
  as playback" holds as written.
- A bridge-only session runs as `mediaPlayback` while producing audio for another app rather
  than playing it. Android 17 accepted this (measured). A later Android release that enforces
  the type's semantics (for example, requiring an active MediaSession) would break bridge-only
  sessions; the fallback, also measured working, is `specialUse` for those sessions.
- Not established: Android 14-16 (only the Android 17 reference device was available),
  targetSdk 37, screen-off bridge use while Obsidian itself is backgrounded, the real plugin's
  request pattern.
