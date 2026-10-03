# ADR 0009: A paused session stays in the foreground for 30 minutes

Date: 2026-10-03. Status: accepted. Issue: REA-25 (from REA-18).

## Context

R-M07 requires lock-screen, notification and headset controls to work with the app in the
background. Phase 3 released the foreground on a user pause. On the reference device
(Pixel 9 Pro XL, Android 17, build ef0fbc6), Android then stopped the paused service 60 s
after Read Me went to the background; the notification and the media session went with it,
and a headset Play did nothing. The service cannot rebuild the queue by itself after that:
the sentence list comes from JS (R-M07, AGENTS.md 11).

## Decision

A user pause keeps the `mediaPlayback` foreground service, its notification and its media
session for 30 minutes (`PauseWindow.HOLD_MS`). Nothing else is held: no wake lock, no audio
focus, no noisy receiver. After 30 minutes the service leaves the foreground as before; the
notification stays until swiped or until Android stops the service. A focus pause (a call) is
unchanged: it holds everything until focus returns.

## Consequences

- Headset, lock-screen and notification Play work for 30 minutes after a pause. After that,
  resuming needs the app; the position is saved (R-M11).
- The notification cannot be swiped away for those 30 minutes on Android versions that keep
  foreground notifications ongoing.
- The window is at least 30 minutes, not exactly 30: the expiry is a `Handler` delay, whose
  clock stops while the phone is in deep sleep, so with the screen off it can end later.
- Verified on the reference device (2026-10-03, build 9332d61): Play from the headset key
  75 s after a pause, past the 60 s at which Android removed the service before. The full
  30 minutes, and the expiry, are covered by unit tests only.
- 30 minutes is a choice, not a measurement: long enough for a conversation or a stop, short
  enough not to sit in the status bar all day. Change `PauseWindow.HOLD_MS` and this ADR together.
