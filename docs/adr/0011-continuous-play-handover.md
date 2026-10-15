# ADR 0011: Continuous play hands over in the service, with a Kotlin segmenter

Date: 2026-10-09. Status: accepted. Issue: REA-41. Requirement: R-S05.

## Context

R-S05 (added 2026-10-08) asks that, with Settings' Continuous play switch on, the end of an
item archives it (R-M11) and starts the next unread item in List order with no user action,
with the screen off and Read Me in the background (R-M07). Until now only JS produced sentence
lists: it segments the kept paragraphs (R-M08, the regex fallback on Hermes since SPIKE-03) and
hands `PlaybackService` the list once per play. With the screen off the JS runtime may be
suspended or gone, and AGENTS.md 11 says JS is a view of the service, never its driver, so JS
cannot be asked for the next item's sentences at the handover.

Two smaller facts shaped the handover itself:

- `PlaybackQueue` used to clear itself at an item's end, publishing an `itemId=null` snapshot;
  the service then called `end()`, which leaves the foreground. Android 12+ refuses a new
  foreground start from the background, so a stop-then-start between items would fail with
  the screen off.
- R-M05's first-open Trim is keyed on `opened_at` (ADR 0007), which is set when the user opens
  an item. The owner decided (2026-10-08) that Trim never interrupts continuous play.

## Decision

- **Kotlin segmenter, for the handover only.** `playback/Segmenter.kt` ports the TS fallback
  segmenter (`segmentParagraph`, the 400-character cap), `remapPosition` and `sentenceIndexAt`,
  plus `plan()`. It is used only when continuous play loads the next item. A play the user
  starts still sends JS's sentences. Positions are character offsets (AGENTS.md 10), so a
  position saved under either segmenter resumes correctly under the other.
- **Parity is tested, not assumed.** `__tests__/segmentParity.test.ts` pins the TS fallback,
  remap and plan to a committed golden (`__tests__/fixtures/segment-parity.json`: the text
  fixture, the extracted weather.gov pages, the first 400 paragraphs of the Gutenberg page, and
  edge cases), and `SegmenterParityTest.kt` runs the Kotlin port on the same file and must give
  identical offsets and resume indices. A change on one side fails until the other follows.
- **Next item.** The first item after the finished one under `Store.items()`'s order
  (`created_at DESC, id DESC`) with `archived_at IS NULL`, `state = 'ready'` and at least one
  kept sentence, evaluated at the handover (since REA-49 also evaluated early, to prepare its
  first audio, and confirmed at the handover: see the amendment below). No wrap to the top: items shared during the chain
  (newer, above) are not picked up, and the bottom of the list stops playback. An item played
  from Archive uses the same key. Items stopped for deletion are skipped.
- **Archive, then load, with no empty snapshot between.** Under its lock the queue archives
  (`finishReading`) and enters a handover: still "playing" the finished item, nothing queued,
  nothing published. It then asks the sink for the next item WITHOUT the lock (Store reads and
  whole-item segmentation, which the main thread must not wait on), and takes the lock again to
  load it only if nothing happened meanwhile. Audio focus, the wake lock (its timeout
  restarted), the media session and the foreground are kept. The queue's rate is kept and not
  applied again (AGENTS.md 9). The bridge's 503-while-playing rule (ADR 0004) covers the whole
  chain, since playback never stops between items.
- **During the handover** (between the archive and the next item's load): a pause or Stop ends
  the chain, as an item end with the switch off does, and writes no position into either item
  (the finished one is archived; the next was never started); a user play wins and the built
  item is dropped; a rate change applies to the next item; next, previous and back-paragraph
  do nothing; the stall check does not count the silence as a lost engine; an item deleted
  meanwhile is refused and ends the chain.
- **A pause in the handover is not held (REA-47).** ADR 0009 holds a user pause (foreground,
  focus, session) so Play resumes the paused item. In the handover window there is no such item:
  the finished one is archived and the next has not started. So a pause there ends the session
  as an item end with the switch off does: the foreground, focus and media session are released,
  no ADR 0009 window opens, and the next item stays unread at its old position. Holding it would
  need a new rule for which item Play resumes. The window is short (Store reads and segmentation;
  the 0.7-1 s handover silences measured on the phone run from the load to the first audio, after
  it). A pause after the next item has loaded is an ordinary held pause under ADR 0009
  (`PlaybackQueueTest.aPauseAfterTheNextItemLoadsIsAnOrdinaryHeldPause`).
- **Title.** `nextItem` only offers the next item's title; it becomes the notification and
  session title when a snapshot of that item reaches the main thread, so an earlier snapshot
  never shows it and an offer the queue dropped never does.
- **What ends the chain.** A user pause or Stop (also during the handover, where a pause is not
  held: see above), an engine error run at the end (which pauses
  instead of archiving), a failed archive, the switch turned off (read at each handover), a
  new playback speaker swapped in during the handover (a voice or engine change, or a rebind;
  REA-51, as a pause there), or no next item.
- **Trim state is left alone.** An item read by continuous play keeps `opened_at` NULL and gets
  no cuts (AGENTS.md 12), so the user's own first open still shows Trim. Its saved position is
  kept and remapped as for any play.
- **The Reader follows (REA-55, 2026-10-15; owner decision: "continuous means keep moving").**
  App, which stays mounted while screens come and go, watches the service's state; when it moves
  straight from item A to item B (`handedOver`: both non-null, different, and B not a Play that
  was waiting for the engine), a Reader route for A becomes one for B (`followHandover`). No
  Kotlin marker is needed: the handover never publishes "no item" between A and B (a pause or
  Stop in its window does, and ends the chain), and every JS start is for the Reader's or Trim's
  own item, so a Reader for A can only see A then B through a handover. A control that starts
  another item while a Reader is open would have to revisit this. Missed events are caught by
  reading the service's state when App mounts and when it is active again; an answer older than
  an event is dropped. The follow only replaces a route (the keyed screen remounts, as List to
  Reader does): no play, pause or resume. It is not an open: no `markOpened`, no Trim, so the
  item keeps `opened_at` NULL as above. Trim open over a Reader that followed returns to the
  followed Reader on Done (`trimDone` pops onto any Reader below). A Reader opened on an item
  that is not being read never switches. The Reader's Play/Pause reads the service's state at
  the tap: a Play from a view one event behind (it saw its item, the service is on another) is
  dropped, a Pause pauses what plays.
- **Fix round 1 of REA-55 (2026-10-15).** Trim during a handover (orchestrator's decision):
  Trim stays on the item it was opened on, and its cuts are saved to that item as before. If the
  service hands over away from that item while Trim is open, the Trim route records the item now
  being read (`followHandover` sets its `reading`, and a second handover moves it on), and Done
  opens that item's Reader: the follow applies at Done. This matters most on a continuous-played
  item's first open, which always shows Trim (its `opened_at` is NULL), so its Done once built a
  Reader of the finished item whose Play replaced the item being read. A first-open Trim of an
  item that was not being read keeps its old Done: its own Reader. A tap on Pause (the view
  showed this item playing) is a Pause, whatever happened since: it pauses whatever the service
  is playing, and does nothing when nothing plays (the item ended, Stop, or a pause from
  elsewhere); it never plays or resumes an item, the finished one included, and the event that
  follows refreshes the view. If the service's state cannot be read at the tap, the view's copy
  decides alone. The service publishes its own state (`publishState`, main thread) under the
  queue's lock, as the queue publishes its changes, so the two publishers cannot reorder a
  handover's snapshots (A, B, A would make the Reader follow backwards).
- **Logs** carry ids and counts only (AGENTS.md 1): `playback continue from=<id> item=<id>
  sentences=<n> start=<i> ms=<n>` (ms: the time to build it), `playback continue none
  after=<id> ms=<n>`, and `playback handover
  from=<id> item=<id> ms=<n>` (the time from the last sentence's end to the next item's first
  sentence; information, not an R-M07 inter-utterance gap).

## Consequences

- Two segmenters exist. Only the fallback is ported; the `Intl.Segmenter` branch stays JS-only
  (Hermes has none). The parity golden must be regenerated (`UPDATE_PARITY=1`) when the TS
  segmenter changes, and the Kotlin port then changed to match.
- R-S02 (sleep timer) is not built. When it is, its "at the end of the current item" must win
  over continuous play: the sink's `nextItem` returns null while that timer is armed.
- The device check (`npm run device:continuous`) runs on the owner's library without clearing
  it, so the chain must never reach one of the owner's items: the script identifies its items
  by List position and sentence count, turns the switch off through Settings while the last
  (long) one plays, which needs the owner to unlock, and otherwise force-stops Read Me while
  that item still plays. "Stops after the last unread item" is covered by the queue and
  service unit tests, not on the phone. Since REA-47 a fourth, long test item (the buffer) is
  shared first, so it sits directly below the last one: if that item ends before the switch is
  off, the chain reaches the buffer, not the owner's newest item, and a background watcher
  force-stops Read Me within about a second of the handover line (the script checks the watcher
  is alive and stops Read Me itself if not). The run then fails as not completed. Nothing plays
  unless the four are the top four unread rows and all ready. A full run refuses to start while
  an earlier run's saved switch setting is still pending its cleanup. Residual risk: if the
  host loses adb or power mid-run, nothing stops the chain, and the buffer's length (at least the
  last item's) is all that stands before the owner's items.

## Amendment 2026-10-15 (REA-49): decided early, confirmed at the handover

The next item's first audio is prepared before the current item ends, so the handover no longer
waits for its synthesis (ADR 0012 amended the same day).

- **When.** Once the current item's last row is synthesized and nothing of it is with the engine
  (playing, no engine error run, no handover open), the queue asks the sink which item would come
  next (`PlaybackSink.peekNext`, the same rule as `nextItem`: `ContinuousPlay.next`, the switch
  read then). It runs outside the queue lock on the service's own preparation thread
  (`ReadMePrepare`, a single worker created in `onCreate` and shut down in `onDestroy`), never
  on the main, JS, binder or player thread: `resume()`, which re-arms it after a pause, is called
  from main and JS. No title is offered and nothing is written to the Store.
- **What.** The next item's rows from its start, one engine request at a time, until 2 s of audio
  (`START_MS`, the start buffer) is ready, at most `MAX_AHEAD` (8) rows or the item's end, at the
  queue's rate (no `setRate`; AGENTS.md 9). The start buffer binds rather than a sentence count,
  so the player can start at the handover on prepared audio alone. Files are
  `play-<item>-p<seq>-<paragraph>-<offset>.wav` in the same directory, deleted with their item
  (`deleteItem`) and by the start-up and destroy sweeps (AGENTS.md 1, 3).
- **Confirmed at the handover.** `nextItem` is still asked at the handover, so the choice is ADR
  0011's rule applied then (switch, deletions, the List). The prepared audio is played only if the
  fresh answer has the same item, the same rows from the same start (paragraph, offset and text),
  the same cuts and opened state, the queue's current rate and the same playback speaker. The
  finished prefix of prepared rows is handed to the player as the new generation's first rows; a
  prepared request still in flight is stopped and every prepared file not handed over is deleted.
  Any mismatch discards it all and the next item is synthesized cold, as before. A discard never
  ends the chain by itself.
- **Discarded before the handover** by: a user pause (resume prepares again), Stop, playing
  another item, a jump, a rate change, a voice or engine change or a rebind (`swapSynth`), a lost
  or failed prepared request (counted against nothing: a dead engine is found as before), and
  deletion of the prepared item. An item opened, trimmed, archived or moved, an item between
  becoming eligible, or the switch turned off is caught by the confirmation.
- **Logs.** `playback prepare from=<id> item=<id> sentences=<n> start=<i> ms=<n>` and `playback
  prepare none after=<id> ms=<n>` (ms: the time to build it); the handover line gains
  `prepared=<0|1>`, 1 only when the player started on prepared audio at the load. The
  acceptance read is `playback handover ... ms=<n> prepared=1`.
