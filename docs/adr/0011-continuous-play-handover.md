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
  kept sentence, evaluated at the handover. No wrap to the top: items shared during the chain
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
- **Title.** `nextItem` only offers the next item's title; it becomes the notification and
  session title when a snapshot of that item reaches the main thread, so an earlier snapshot
  never shows it and an offer the queue dropped never does.
- **What ends the chain.** A user pause or Stop (also during the handover), an engine error run at the end (which pauses
  instead of archiving), a failed archive, the switch turned off (read at each handover), or
  no next item.
- **Trim state is left alone.** An item read by continuous play keeps `opened_at` NULL and gets
  no cuts (AGENTS.md 12), so the user's own first open still shows Trim. Its saved position is
  kept and remapped as for any play.
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
  it, so the chain must never reach one of the owner's items: the script identifies its three
  items by List position and sentence count, turns the switch off through Settings while the
  last (long) one plays, which needs the owner to unlock, and otherwise force-stops Read Me
  while that item still plays. "Stops after the last unread item" is covered by the queue and
  service unit tests, not on the phone.
