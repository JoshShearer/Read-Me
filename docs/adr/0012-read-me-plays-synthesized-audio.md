# ADR 0012: Read Me plays synthesized audio itself

Date: 2026-10-04. Status: accepted. Issue: REA-31. Deciders: the owner delegated the choice
(2026-10-03); pipeline decisions D1-D6 recorded on REA-31.
Amends: srs.md R-M07 (queue, rate, gap definition), "Playback", R-M09.4; AGENTS.md's Phase 3
note on `MediaButtonClaim`. Written as ADR 0011 on 2026-10-04; renumbered 0012 on 2026-10-11
when the branch was rebased onto main, where ADR 0011 (continuous play, R-S05) had landed
first. The continuous-play paragraphs below (Decision and Consequences) were added then.

## Context

Phase 3 had playback's `TextToSpeech` speak each sentence with `speak()` and `QUEUE_ADD`. The
engine then plays the audio in its own process, under its own uid. Android sends media keys
(headset, lock screen, `cmd media_session dispatch`) to the session of the app that most
recently played audio, so Read Me's session never got a key until `MediaButtonClaim` played
200 ms of silence from Read Me's process at every start and resume.

That claim does not hold against an engine that has a media session of its own. REA-31,
reproduced on the reference device (Pixel 9 Pro XL, GrapheneOS, Android 17; 2026-10-04,
build c77cd94, Supertonic 3.2.7 as the default engine, after Supertonic's own app had played
a sample, which creates `SupertonicMediaSession`): the claim made Read Me the media-button
session at the start and at the resume, and Supertonic's session took it back 615 ms and
562 ms later, because the engine's audio played under Supertonic's uid. `dumpsys
media_session`, sampled about every 2.5 s, named Supertonic's session in all 123 samples. A
dispatched Pause went to Supertonic and Read Me logged nothing; a dispatched Play started
Supertonic's own player, whose audio-focus request paused Read Me. With the screen off a
listener then has no working control (R-M07). Archive: `.claude/scratch/REA-31/attempt2/`.

Re-claiming on every sentence was rejected: it races each engine's own timing and depends on
behaviour of Supertonic nobody verified. ADR 0010 makes the system's engine the only one from
Android 14, so a user cannot work around a misbehaving engine by choosing another.

Before building, the premise was measured (`.claude/scratch/REA-31/gate/`, 2026-10-04, a
throwaway probe on the same phone, 60 synthetic sentences each, `synthesizeToFile` run
serially): every file was valid 16-bit mono PCM WAV. GrapheneOS Speech Services (22050 Hz) at
2.0x: synthesis p50 881 ms, p95 1539 ms, max 3722 ms, real-time factor p95 0.496, max 0.931.
Supertonic (44100 Hz) at 2.0x: p50 815, p95 1080, max 1236 ms, real-time factor p95 0.372.
Replayed through a model of serial synthesis feeding a player, neither engine starved at any
prefetch depth from 1 up. The first file of a fresh instance took 3171 / 3722 ms (GrapheneOS,
1.0x / 2.0x) and 1400 / 1194 ms (Supertonic). Google TTS could not be measured: it is
installed only for the phone's user 12, and asking for it in the owner's user 0 bound
GrapheneOS Speech Services, which therefore became the baseline engine (orchestrator decision
posted to REA-31).

## Decision

Playback's `TextToSpeech` never speaks. It synthesizes sentences to files and Read Me plays
them on its own output.

- **Synthesis ahead.** `PlaybackQueue` asks the engine (`synthesizeToFile`) for the current
  sentence and at least 3 more, then more while less than 10 s of audio and 8 MB are ahead, at
  most 8 ahead (the gap fix below; `AHEAD`, `AHEAD_MS`, `MAX_AHEAD`, `AHEAD_BYTES`). Sentences
  longer than 160 characters are synthesized in pieces. The player starts once 2 s of audio is
  queued in it, or the item's last sentence is, or the limits ask for nothing more. Files live in
  `noBackupFilesDir/playback` (app-private, not backed up), named
  `play-<item>-<generation>-<paragraph>-<offset>.wav`: ids and offsets, never text
  (AGENTS.md 1, 3, 10).
- **Read Me's player** (`TrackPlayer`) streams each file's PCM into one `AudioTrack`
  (USAGE_MEDIA, CONTENT_TYPE_SPEECH, `MODE_STREAM`) at 1.0x, deleting the file once it is in
  the output. Rate is applied once, by the engine at synthesis (AGENTS.md 9); nothing scales
  playback speed, and a test scans the sources for it. Audio focus, the noisy-headphones
  pause and the wake lock now guard Read Me's own output. `MediaButtonClaim` is deleted.
- **A sentence starts when the player plays its first frame and ends at its last** (the play
  head passing frame counts), not at the engine's callbacks. Positions stay character offsets,
  saved when the player finishes a sentence (R-M11).
- **D1, a true pause.** Pause holds the output mid-sentence and keeps the files; Play goes on
  from the same frame. A rate, voice or engine change, a jump, new cuts, a lost engine, or the
  end of ADR 0009's 30-minute window discards the unplayed files and restarts at the start of
  the current sentence, or of its current piece when it was split. A voice or engine change
  made while paused discards them at the swap, with any requests the old engine has not
  answered.
- **D3, the gap.** R-M07's gap is the player's end of sentence n to its start of n+1, on the
  service's clock. When the next file is not ready the output is fed silence rather than left
  to run dry: a dry `AudioTrack` holds the end of the last sentence back, and the gap would
  read as zero however long the engine took. The `playback gaps` log line keeps its fields and
  order and appends `underruns=`, `synthP50=` and `synthP95=`; `playback first-audio ms=` is
  logged once per item a user plays, not on a resume or at a continuous-play handover (which
  logs `playback handover ms=` instead).
- **D5, engine loss** keeps its behaviour (REA-18, REA-25): a refused request or an
  `ERROR_SERVICE` pauses at the current sentence and rebinds once per user Play. The stall
  check no longer asks `isSpeaking`: no sentence audio and no engine answer for 15 s with a
  dead binding (`getVoice` asked outside the queue's lock), or for 2 minutes whatever the
  binding says, is a lost engine. A slow sentence on a live engine is not.
- The bridge is unchanged: its own instance (AGENTS.md 13) and 503 while Read Me plays
  (ADR 0004). Playback's synthesis competes with the bridge's exactly as its speech did.
- **Continuous play (ADR 0011).** An item ends when the player reports its last sentence's
  last frame (not the engine's `onDone`). Under the queue's lock the item is archived and its
  pipeline discarded: a new generation, the engine stopped, the player flushed, and every file
  of the item the queue or the player still holds deleted (at that point normally none: the
  end means every file was played or failed). The next item is then built outside the lock as
  ADR 0011 says, on the thread that reported the end (usually the player's writer thread, which
  has nothing to write meanwhile), and loaded as a new generation like any `load`: its
  synthesis starts only then, and the player starts once the start buffer (2 s of audio, or the
  item's last sentence) is ready. Nothing is synthesized across the item boundary, because
  ADR 0011 picks the next item at the handover (the switch, deletions and the List as they are
  then). A pause during the handover ends the chain as ADR 0011 says. A pause just before the
  end keeps the generation (D1), so the player can still report the last frames heard before
  it; the item is then read to the end and archived, and the pause ends the chain. A failure
  building the next item (REA-46, an `Error` included) ends the chain with the pipeline
  already released: no files left and the output not asked to play. `playback handover ms=`
  now runs from the next item's load to its first frame heard, so it includes that item's
  first synthesis; `playback first-audio` stays a user play's.

Rejected: `MediaPlayer` with `setNextMediaPlayer` (no frame-level sentence boundaries, and a
prepare per file); streaming the engine's `onAudioAvailable` buffers into the track (the
decision on REA-31 was files; it would also tie playback to an optional callback).

## Consequences

- Measured so far on the reference device (2026-10-04, build c77cd94 plus this change,
  uncommitted, `READ_ME_ENGINE=app.grapheneos.speechservices npm run device:playback`, one run,
  `.claude/scratch/REA-31/impl/playback-gos-1.log`): audio played through Read Me's output,
  Android logged "Media button session is changed to io.loopstring.readme" as the first
  sentence started, the media-key pause and play with the screen off reached Read Me, and the
  item archived. Gaps n=9 p50 15 p95 16 max 16 ms and n=23 p50 15 p95 19 max 20 ms, stalls 0,
  underruns 0. That run failed one check: its "after start" sample was taken before the first
  sentence was heard (the script now waits for `playback first-audio`). First audio took
  7426 ms from the queue's load, of which the engine's own `time-to-first-audio` log says
  6807 ms (a cold engine; Phase 3 saw 8056 ms with `speak()`).
- A second run with the fixed script (same build, `impl/playback-gos-2.log`) passed every
  check: Read Me held the media-button session after first audio, after the Reader's resume and
  after the media-key resume; gaps n=19 p50 12 p95 16 max 16 ms, stalls 0, underruns 0; first
  audio 7306 ms with a just-switched (cold) engine.
- Supertonic acceptance (same build, 07:40-07:42, `impl/run-supertonic.sh`): Supertonic's own
  app played a sample first, creating `SupertonicMediaSession`, which was the media-button
  session until Read Me played. `device:playback` with Supertonic as the default then passed:
  every one of 17 `dumpsys media_session` samples from first audio to the end named Read Me's
  session, dispatched pause and play reached Read Me, and the item archived; gaps n=19 p50 15
  p95 19 max 19 ms, stalls 0, underruns 0. `device:lifecycle` (Supertonic), `device:ui` and
  `device:bridge` (503 while playing) passed on this build.
- First audio, from the queue's load to the first frame (`impl/first-audio-*.log`): GrapheneOS
  Speech Services 5527 ms cold (engine force-stopped; the engine's own log 5070 ms) and 593 ms
  warm (a second item while the paused service kept its binding); a new service binding to
  the running engine took 5233 ms, because this engine reloads per connection (its log 4763
  ms). Supertonic 1479 ms cold and 622 ms warm. With `speak()` the only number recorded is a
  cold 8056 ms (AGENTS.md, engine log, build 446b630); no warm `speak()` figure was measured.
- `device:gap` (2 minutes, GrapheneOS Speech Services, 2.0x, battery, forced Doze, screen off;
  `impl/gap-gos.log`, 2026-10-04 08:17) FAILED R-M07: n=18 p50 14 p95 1161 max 1161 ms,
  stalls 1, errors 0, underruns 0, synthP50 1627 synthP95 7203 ms. One gap over 1 s at 18
  boundaries; where it fell was not logged.
  Root cause, from the code and timestamps reconstructed from that run's engine log
  (`impl/gap-gos-logcat.txt`; inferred, the run logged no per-sentence lines): nothing
  serialized synthesis behind playback (no shared lock or thread); requests were gated by
  count, current sentence plus 3, and the engine synthesizes one request at a time. The
  engine began a sentence at 08:17:06.1 that took about 9 s to synthesize; the 4 sentences
  ahead of it held about 10 s of audio from first audio at 08:17:04.2, so the player ran out
  at about 08:17:14.2 and the sentence followed about 1.2 s later. A whole sentence must be
  synthesized before any of it plays, so one long sentence's synthesis time is a gap
  waiting to happen. (The "34 s wait" was the next request held while that 34 s sentence
  played: count gating idles the engine, harmless there, a stall when a long sentence
  follows short ones.)
  Fix: sentences over 160 characters are synthesized in pieces cut at a clause mark or
  whitespace (`Utterances.fit` with `CHUNK_CHARS`; the Reader still highlights the whole
  sentence, positions stay the piece's offset, Next and Previous move by sentence), and
  beyond 3 sentences ahead the queue keeps asking while less than 10 s of audio is ahead
  (estimated per character from this load's synthesized sentences), up to 8 sentences and
  8 MB. A finished synthesis tops up at once rather than waiting for the player. Every gap
  over 1 s logs `playback stall` with the index, paragraph, offset, length, synthesis time,
  audio ahead when it was asked for and now, and queue depth. Consequence: a sentence over
  160 characters can carry a slight pause where a piece ends.
- After the fix, rebased onto 8e4af79 (1.0.1; uncommitted, 2026-10-04 09:15,
  `impl/playback-gos-3.log`): `device:playback` with GrapheneOS Speech Services passed, Read Me
  held the media-button session after first audio and both resumes, gaps n=20 p50 8 p95 17
  max 17 ms, stalls 0, underruns 0.
- `device:gap` on that build (09:23, `impl/gap-gos-3.log`) still FAILED: n=27 p50 14 p95 18
  max 1154 ms, stalls 1, underruns 0, synthP50 1329 synthP95 6857 ms, first audio 5239 ms.
  The new stall line placed it: `ms=1154 index=1 paragraph=1 offset=0 chars=82 synthMs=6678
  audioMs=6803 aheadMsAtRequest=0 depth=4`. The first sentence ("Gap run", the title) was
  short, and the player started it while the cold engine was still writing the second.
  Fix: a start buffer. After a load, jump or rate change the player starts only once 2 s of
  audio (`START_MS`) is queued in it, or the item's last sentence is.
- `device:gap` after the start buffer (09:28, `impl/gap-gos-4.log`): PASSED R-M07. n=27 p50 14
  p95 19 max 20 ms, stalls 0, errors 0, underruns 0, synthP50 1261 synthP95 6840 ms; first
  audio 6662 ms (cold engine; the start buffer waits for the second sentence here).
- Not yet run on the start-buffer build: `device:playback` and the Supertonic acceptance.

- First audio waits for the whole first sentence to be synthesized, where `speak()` started
  as the engine produced audio.
- The engine's files take app storage while they wait: at most the current sentence and 8
  more, capped at 8 MB ahead, as 16-bit PCM, about 88 KB per second of audio at Supertonic's
  44100 Hz mono and 44 KB at GrapheneOS Speech Services' 22050 Hz (arithmetic from the gate's
  formats).
- On every engine, audio focus belongs to Read Me's output, so another app's focus request
  pauses Read Me exactly as R-M07 says, and an engine's own player cannot.
- Google TTS has not been run on the reference device for this change (not installed for user
  0). Other engines (Marmalade) are untested.
- Continuous play's handover is no longer near-instant. With `speak()`, `playback handover
  ms=` (the next item's load to its first `onStart`) read 35 and 20 ms (`device:continuous`,
  2026-10-09, build 77a8d15, AGENTS.md). Now the listener hears silence from the last frame of
  one item to the first of the next: the next item's build (`playback continue ... ms=`) plus
  `playback handover ms=`, which now holds the synthesis of at least 2 s of its audio.
  Measured on 69b3db1 (`device:continuous`, 2026-10-13, GrapheneOS Speech Services): handovers
  731 and 818 ms, next-item builds 9 and 12 ms. Accepted by the owner for this change.
  Synthesizing the next item's start before the end would hide it, at the cost of deciding the
  next item before the handover (ADR 0011 decides at it); that is REA-49.
- The figures above without a build named were measured on 2026-10-04 builds (c77cd94 or
  8e4af79, plus this change, uncommitted). After the rebase onto continuous play,
  `device:playback` (Supertonic, f40e264, 2026-10-11) and `device:playback`,
  `device:lifecycle`, `device:gap` (2 min) and `device:continuous` (GrapheneOS Speech Services,
  69b3db1, 2026-10-13) passed; AGENTS.md "Known state" has the numbers.
