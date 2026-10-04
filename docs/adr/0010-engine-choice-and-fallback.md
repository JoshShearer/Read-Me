# ADR 0010: Which TTS engine Read Me binds

Date: 2026-10-03. Status: accepted. Issue: REA-32.

## Context

R-M06 says to use Android's `TextToSpeech` and to show a blocking state when no engine binds.
It says nothing about which engine. On the Huawei MatePad VRD-W09 (secondary device, Android 10),
Supertonic and Marmalade declare `android.permission.BIND_TEXT_TO_SPEECH_SERVICE` on their
service. That permission only exists from Android 14, so on Android 10 no app can hold it and
Android refuses every app's bind (`Permission Denial ... requires
android.permission.BIND_TEXT_TO_SPEECH_SERVICE`). `TextToSpeech` then throws instead of
falling back to the next engine, so with either as the default Read Me crashed (REA-32) and,
once the crash was caught, had no engine at all. Google TTS and iFlytek on the same tablet
declare no permission and bind. On the reference device (Pixel 9 Pro XL, Android 17)
Supertonic and Marmalade bind.

## Decision

Owner decision, 2026-10-03: below Android 14 the engine is chosen in Read Me; from Android 14
the system's engine is always used.

- **Below Android 14** Settings lists the installed engines; one Android will not bind is
  shown, disabled, with "Needs Android 14 or later". With no choice made, or the chosen engine
  gone or unbindable, Read Me uses Android's default when it can be bound, else the first
  bindable engine, system engines first (`TtsOpen`).
- **From Android 14** Read Me requests no engine, so Android binds its default, and Settings
  names it.
- Playback, the engine probe and the bridge ask the same function, so they agree on one engine.
- A bridge rebind pinned to an engine never falls back (REA-29 stands: a stop, not a different
  voice). The fall-back above happens only when an engine is first chosen.
- A changed engine or voice applies from the next play, even while `PlaybackService` runs.

## Consequences

- On Android 10, with an unbindable default, Read Me speaks with another engine instead of
  showing the blocking state. Settings says which engine, so the switch is not silent.
- Network voices stay excluded on every engine (R-M06, AGENTS.md 5).
- Marmalade and Supertonic cannot be used below Android 14 unless their developers drop the
  permission. Nothing in Read Me can get round it.
- Verified: on the tablet, build b9ffadc, Read Me bound iFlytek with Marmalade as the default
  and read a sentence. On the Pixel (build 0ee68a3 plus a label fix) Settings named
  Supertonic, and later Marmalade, after the owner switched Android's default.
