# ADR 0004: The bridge answers 503 while Read Me is playing

- Status: accepted
- Date: 2026-10-01
- Deciders: owner
- Amends: srs.md R-M12 (bridge), "Bridge contract (v1)"; records SPIKE-06's answer for
  AGENTS.md non-negotiable 13

## Context

R-M12 gave the bridge its own `TextToSpeech` instance "so both can run at once". SPIKE-06
measured that on the reference device (srs.md, SPIKE-06 answer; build 46a4f8e): two instances
bound to `app.grapheneos.speechservices`, one speaking at 2.0x with `QUEUE_ADD` and one looping
`synthesizeToFile()` at 1.0, for 10 minutes, offline, screen off.

Neither instance cancelled the other and no rate leaked between them, but they do not run
concurrently. Playback's inter-utterance gap went from p95 11 ms and max 22 ms (SPIKE-05,
second instance idle) to p95 2,751 ms and max 3,549 ms, with 16 stalls over 1 s in 119
utterances, which breaks R-M07. Synthesis went from p50 1,676 ms alone to 13,852 ms under
playback, 8.3 times slower.

## Decision

Playback has priority. While Read Me's playback is speaking, the bridge does not synthesize:

- `POST /synthesize` answers `503` with `{"error":"busy","reason":"playback"}`. The token is
  checked first (401 stays 401), and the 503 is sent before the body is read. The server then
  shuts down its output and drains the unread body, at most 64 KiB within the 10 s read
  timeout, before closing: closing a socket with unread data in its receive buffer sends a TCP
  reset on Linux, and a WebView `fetch` would then see a network error instead of the 503. The
  same applies to 401 and 413 (R-M12). Like every response, it carries the R-M12 CORS headers.
  Not yet measured: Phase 5 sends a 64 KiB unauthenticated POST and a 64 KiB POST during
  playback from Obsidian's WebView and checks that both statuses are readable.
- "Speaking" is PlaybackService's playing state. Paused and stopped count as idle, so the bridge
  serves whenever the user is not listening in Read Me.
- If playback starts while a bridge synthesis is in flight, the bridge stops its own instance
  (`stop()` on the bridge's `TextToSpeech` only), deletes the partial file, and answers that
  request 503.
- `GET /health` adds `busy` (boolean), so the plugin can tell before it sends text.
- Playback and the bridge keep separate `TextToSpeech` instances (non-negotiable 13). This
  policy is mutual exclusion on top of them, not a shared instance.

## Consequences

- R-M07's gap target holds while the bridge is enabled.
- The bridge is unavailable while Read Me plays. The plugin treats 503 as "use another engine or
  retry later"; that is the plugin's side (NRL-130 in the plugin's workspace).
- Contract v1 gains a status code and a `/health` field. Both are additive; existing callers that
  ignore them see a 503 as a failed request.
- Not covered by the measurement: another engine (`com.google.android.tts`) might run the two
  instances concurrently. The policy applies to every engine; revisit it only with a SPIKE-06
  run on that engine.
