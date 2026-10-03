# ADR 0008: A bridge synthesis takes at most the engine's input limit

- Status: accepted (owner reviews with the Phase 5 plan)
- Date: 2026-10-02
- Deciders: owner
- Amends: srs.md "Bridge contract (v1)"

## Context

The contract caps the body at 64 KiB. `TextToSpeech.synthesizeToFile` refuses text longer than
`getMaxSpeechInputLength()` (documented as 4000 characters; playback uses 3900, `TtsSpeaker.maxChars()`).
A 64 KiB body is roughly an hour of speech at rate 1.0: one WAV of about 200 MB, after minutes of
synthesis. The local-tts-reader plugin sends chunks of at most 220 characters
(note-reader-local `AGENTS.md`, chunk-cap note).

## Decision

- `POST /synthesize` answers 413 `{"error":"too-long","maxChars":N}` when the decoded text is longer
  than N, the engine limit less headroom. The 64 KiB body cap stays.
- `GET /health` reports `maxChars` (additive).
- Errors are JSON `{"error":"<code>"}`. Added codes: 404 unknown route, 405 wrong method on a known
  route, 503 `tts-not-ready` before the engine is ready, 504 when one synthesis exceeds 120 s.

## Consequences

- No caller gets an hour-long WAV in one response; the plugin's chunks are far under the limit.
- Splitting and joining WAVs in the bridge was rejected for that reason.
- All changes are additive to contract v1; the version stays 1.
