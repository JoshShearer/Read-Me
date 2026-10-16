# ADR 0014: Read Me reads the plugin's text aloud itself (bridge sessions)

Date: 2026-10-16. Status: accepted. Issue: REA-39. Deciders: the owner ("implement 39",
2026-10-16), after the 2026-10-08 reproduction on REA-39.
Amends: srs.md R-M12 and "Bridge contract (v1)" (three routes added, `/health` gains `play`),
ADR 0004 (a bridge session is playback, not a synthesis), ADR 0005 (the service now plays the
plugin's audio, which `mediaPlayback` describes better than before). Plugin side: the
`local-tts-reader` plugin's Read Me engine (NRL-130's follow-up in the plugin repository).

## Context

Through the bridge, Read Me synthesized a WAV per chunk and Obsidian's WebView played it with
`new Audio()`. Reproduced on the reference device (Pixel 9 Pro XL, GrapheneOS, Android 17,
Read Me 1.0.3, 2026-10-08, REA-39): about 5 s after Obsidian left the foreground (screen off,
or Obsidian sent Home with the screen on), Android logged `AudioHardening background playback
muted for md.obsidian ... usage: USAGE_MEDIA` and then blocked Obsidian's audio-focus requests.
The plugin's audio element stayed paused while the plugin reported `playing`. Read Me's
service stayed in the foreground and kept answering `/synthesize`; Obsidian's "Unrestricted"
battery setting did not help. Android 17 mutes background media from an app with no
foreground service of its own, and the plugin cannot give Obsidian one. Read Me's own playback
was unaffected in the same runs (three engines, 190 s each, screen off).

## Decision

- Read Me plays bridge text in its own `PlaybackService`, the same queue, engine instance,
  `AudioTrack`, audio focus, media session and notification as an article (ADR 0012). A
  bridge **session** is the plugin's chunks, in order.
- `POST /play?rate=<f>` (token): the body is the chunks as UTF-8, separated by U+001E (record
  separator), at most 1 MiB and 10,000 chunks; no chunk may be blank, so the plugin's chunk n
  is index n. Starts a new session, replacing an earlier one, and answers
  `{"session":n,"chunks":k}`. While Read Me is reading one of its own items it answers ADR
  0004's `503 {"error":"busy","reason":"playback"}`; a paused item gives way (its position is
  already saved).
- `GET /status` (token): `{"session":n,"state":s,"index":i}`, state one of `none`, `starting`
  (waiting for the engine), `playing`, `paused`, `ended` (the last chunk was read), `stopped`
  (stopped, or replaced by Read Me's own playback). `index` is the chunk being read.
- `POST /control?action=pause|resume|stop&session=n` (token): acts only on the newest session;
  an older number is `409 {"error":"stale-session","session":<newest>}`.
- `/health` gains `"play":true`, and its `busy` no longer counts the plugin's own session (the
  engine is speaking for the plugin, so the plugin must not see itself as blocked).
  `version` stays 1: the routes are additions, and an older plugin keeps working through
  `/synthesize`.
- The rate comes with `/play` and is applied by the engine (AGENTS.md 9). The plugin, which
  then owns no audio, must not apply it again.
- A session is item `PlaybackService.BRIDGE_ITEM` (-1). It is never in the store: no position
  is saved, it is never archived, continuous play neither hands over from it nor prepares
  after it (R-S05), and Read Me's screens never open or follow it. The notification and lock
  screen say "Obsidian note", never the note's text or name. Logs carry the session number,
  chunk count, rate and states only (AGENTS.md 1).
- Read Me's own Play wins: starting an article replaces the session, which then reads
  `stopped`. Lock-screen, notification and headset controls act on the session like any item.

## Consequences

- Reading a note through the bridge survives the screen going off and Obsidian going to the
  background, because the audio belongs to an app with a `mediaPlayback` foreground service.
- The plugin's highlight follows `/status`, which it polls; while Obsidian is in the
  background its JavaScript may be throttled, but the reading does not depend on it.
- Word timings stay unavailable (no `onRangeStart`, as before).
- `/synthesize` is unchanged and still answers 503 while anything, a session included, is
  speaking (ADR 0004).
- A session pause from the lock screen is not pushed to the plugin; it sees `paused` on its
  next poll.
