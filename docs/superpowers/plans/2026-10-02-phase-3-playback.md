# Phase 3: Playback Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Tap an item and hear it read aloud at 2.0x by on-device TTS from a native `PlaybackService` that keeps reading with the screen off, answers lock-screen, notification and headset controls, pauses for calls and unplugged headphones, saves the position after every sentence, and archives the item at its end (REA-17).

**Architecture:** A pure-Kotlin `PlaybackQueue` owns the sentence queue (QUEUE_ADD, 3 ahead, generation-tagged utterance ids) behind two interfaces, `Speaker` (a `TextToSpeech` wrapper) and `PlaybackSink` (Store writes plus UI fan-out), so its rules are unit-tested without a device. `PlaybackService` (a `mediaPlayback` foreground service, ADR 0005) wires it to a framework `MediaSession`, a MediaStyle notification, audio focus, the noisy-audio broadcast and a partial wake lock. JS segments the kept text (Phase 1 `segment` + `trim`), maps the saved character offset to a start sentence, hands the list over once per play through `ReadMeSpeech`, and from then on only views the service through events (AGENTS.md 11).

**Tech Stack:** Kotlin, `android.speech.tts.TextToSpeech`, `android.media.session.MediaSession`, `Notification.MediaStyle`, `AudioFocusRequest`, SQLite (existing `Store`), SharedPreferences, Robolectric 4.17; TypeScript (RN 0.87.1 TurboModule codegen), Jest; bash device scripts over adb.

**Spec:** `srs.md` (R-M06, R-M07, R-M11, "Playback", "Foreground service", SPIKE-01, SPIKE-05, SPIKE-06), ADR 0004 (bridge answers 503 while playing), ADR 0005 (one `mediaPlayback` service hosts playback and later the bridge), ADR 0007 (archive is a timestamp, state kept). Roadmap row: `docs/superpowers/plans/2026-10-01-roadmap.md`, Phase 3.

## Global Constraints

- Speech uses `android.speech.tts.TextToSpeech` only. A voice whose `isNetworkConnectionRequired()` is true is never selected, including as a fallback (R-M06, AGENTS.md 5).
- No engine, or no offline voice: the app shows a blocking state that explains it and links to Android's TTS settings (R-M06).
- JS hands `PlaybackService` the full ordered sentence list (paragraph index, start, end, text) and a start position; the service queues with `QUEUE_ADD` and keeps at least 3 utterances queued (R-M07, srs "Playback").
- Controls: play/pause, previous sentence, next sentence, back 1 paragraph, rate (R-M07).
- Rate range 0.5x to 4.0x, default 2.0x, step 0.1x, applied exactly once by `setSpeechRate` (R-M07, AGENTS.md 9).
- Pause = `stop()` plus remembering the current sentence; resume re-queues from that sentence's start; a rate change flushes and refills from the current sentence (srs "Playback").
- Playback continues with the screen off and the app in the background through a foreground service with a media session (lock screen, notification, headset: play/pause, next, previous) (R-M07).
- Audio focus is requested; playback pauses on transient loss and when headphones disconnect (R-M07).
- Position = (item id, paragraph index, character offset), saved by the service after every completed sentence and on pause/stop; restore resumes at the start of the sentence containing the offset under the current segmentation; never persist a sentence index (R-M11, AGENTS.md 10).
- The last kept sentence finishing sets `archivedAt`; the state is kept (R-M11, ADR 0007).
- Gap target: at 2.0x over 10 minutes of offline playback, `onDone(n)` to `onStart(n+1)` p95 at most 300 ms, max at most 1,000 ms, no stall (R-M07).
- Playback has its own `TextToSpeech` instance (AGENTS.md 13). The bridge (Phase 5) reads a shared "speaking" flag to answer 503 (ADR 0004).
- No item text, title, URL path or query in any log or logged exception message (AGENTS.md 1). The notification shows the title; nothing logs it.
- No new dependency. Framework APIs only (AGENTS.md 14).
- No JS source contains the words `fetch`, `XMLHttpRequest` or `WebSocket`, even in a comment (`__tests__/networkGuard.test.ts`).
- Never put item text on an `adb shell` argv; send the command on stdin (AGENTS.md, SPIKE-01).

## Review Focus

1. **A TTS callback that arrives after a flush** (pause, next, rate change, a new item) must not save a position, advance, or queue anything. Pinned in Task 2 (`aLateCallbackFromFlushedWorkIsIgnored`).
2. **An item deleted while it plays** must stop playback and leave no position row, and a position save racing the delete must not crash the service. Pinned in Task 1 (`savingThePositionOfADeletedItemReturnsFalse`) and Task 2 (`stopItemClearsWithoutSaving`), wired in Task 5 (`deleteItem`).
3. **Audio focus coming back** resumes only playback that a transient loss paused, never playback the user paused or a permanent loss stopped. Pinned in Task 3 (`FocusPolicyTest`).
4. **A notification action or media button after the process died** (no queue, no pending request) must stop the service cleanly, not crash or start silent. Pinned in Task 3 (`aControlWithNothingLoadedStops`).
5. **A rate change while paused** must not start speaking; the new rate applies on resume. Pinned in Task 2 (`aRateChangeWhilePausedDoesNotSpeak`).

Also pinned, outside the five: a sentence longer than the engine's input limit (a paragraph with no punctuation) is split at whitespace into utterances that keep exact character offsets (Task 2, `UtterancesTest`).

## Verified before writing (2026-10-02)

- `positions` table exists in schema v1 (`Store.kt:82`): `item_id INTEGER PRIMARY KEY REFERENCES items(id) ON DELETE CASCADE, paragraph_index, char_offset, updated_at`; foreign keys on in `onConfigure`. No migration needed.
- `Store.archive(id, now)` (`Store.kt:270`) sets `archived_at` and fires `ItemEvents`. `ItemEvents` is a `CopyOnWriteArraySet` object.
- `ReadMeSpeechModule.settle` rejects with `E_LIBRARY` and the class name only.
- TS: `segment(paragraphs, cuts: ReadonlySet<number>)`, `sentenceIndexAt(sentences, position)` returns -1 past the end, `remapPosition(position, cuts, count)` returns null when nothing is left.
- `minSdkVersion 24`, `compileSdkVersion 37`, `targetSdkVersion 36`. Device SDK 37.
- On the phone: `dumpsys deviceidle force-idle|unforce`, `dumpsys battery unplug|reset`, `cmd media_session dispatch <key>` exist (help output read 2026-10-02).
- Spike code read with `git show spike/rea-0-gapless-2x:...`: `GapStats` (nearest-rank percentile, `STALL_MS = 1_000`), `SpikeService.goForeground` (type `MEDIA_PLAYBACK` on 29+), `GapProbe` (post `onInit` to the main handler: it can run synchronously in the constructor).
- Method names appear as strings in `libappmodules.so` (checked for `completeExtraction`, `restoreItem`, `archiveItem`), so the build can check every spec method is compiled in.
- `__tests__/fixtures/pages/gutenberg-1342.html` (public domain) is long enough for 10 minutes at 2x.

## File structure

| File | Responsibility |
|---|---|
| `android/.../store/Store.kt` (modify) | `PositionRow`, `savePosition`, `position`, `finishReading` |
| `android/.../store/Settings.kt` (create) | `Rate` bounds and rounding; `Settings` persisted rate |
| `android/.../playback/SentenceRow.kt` (create) | `SentenceRow`, `PlaybackSnapshot`, `Speaker`, `PlaybackSink` |
| `android/.../playback/Utterances.kt` (create) | Split over-long sentences at whitespace, offsets kept |
| `android/.../playback/GapStats.kt` (create) | Gap summary (from the spike) |
| `android/.../playback/PlaybackQueue.kt` (create) | The queue: load, pause, resume, next, previous, back paragraph, rate, callbacks, gaps |
| `android/.../playback/Policies.kt` (create) | `VoicePicker`, `FocusPolicy`, `PlaybackCommands` |
| `android/.../playback/TtsSpeaker.kt` (create) | `TextToSpeech` wrapper implementing `Speaker`, engine status |
| `android/.../playback/PlaybackHub.kt` (create) | In-process hand-off: pending request, current snapshot, engine, speaking flag, controller, listeners |
| `android/.../playback/PlaybackService.kt` (create) | Foreground service, MediaSession, notification, focus, noisy, wake lock, logs |
| `android/app/src/main/AndroidManifest.xml` (modify) | FGS + media-playback + wake-lock permissions, the service |
| `android/.../ReadMeSpeechModule.kt` (modify) | Playback methods, `ReadMePlayback` event, delete stops playback |
| `src/native/NativeReadMeSpeech.ts` (modify) | Spec additions |
| `src/library/playback.ts` (create) | `plan`, `playItem`, `toggle`, controls, `getPlayback`, `onPlayback` |
| `App.tsx` (modify) | Tap a row to play/pause; playing/paused/archived marker; engine-problem line |
| `scripts/build-release.sh` (modify) | Clear a stale native cache when the spec changed; check every spec method is compiled in |
| `scripts/device-playback-e2e.sh` (create) | `npm run device:playback` |
| `scripts/device-gap.sh` (create) | `npm run device:gap` (10 min at 2x, battery + Doze) |
| Docs (modify) | `AGENTS.md`, `CONTEXT.md`, `srs.md` (SPIKE-05 / R-M07 measured line) |

Kotlin paths below abbreviate `android/app/src/main/java/io/loopstring/readme` as `K/` and `android/app/src/test/java/io/loopstring/readme` as `T/`.

---

### Task 1: Store positions, finish-reading archive, and the rate setting

**Files:**
- Modify: `K/store/Store.kt` (after `restore`, line 278)
- Create: `K/store/Settings.kt`
- Test: `T/store/StorePositionTest.kt`, `T/store/SettingsTest.kt`

**Interfaces:**
- Produces: `data class PositionRow(val paragraphIndex: Int, val charOffset: Int)`; `Store.savePosition(id: Long, paragraphIndex: Int, charOffset: Int, now: Long): Boolean`; `Store.position(id: Long): PositionRow?`; `Store.finishReading(id: Long, now: Long)`; `object Rate { MIN = 0.5f; MAX = 4.0f; DEFAULT = 2.0f; fun clamp(r: Float): Float }`; `class Settings(context: Context) { var rate: Float }`.

- [ ] **Step 1: Write the failing tests**

`T/store/StorePositionTest.kt`:

```kotlin
package io.loopstring.readme.store

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StorePositionTest {
  private lateinit var store: Store

  @Before fun setUp() {
    store = Store.get(ApplicationProvider.getApplicationContext<Context>())
  }

  @After fun tearDown() = Store.resetForTest()

  @Test fun aSavedPositionReadsBackAndTheLatestWins() {
    val id = store.insertText("t", listOf("a", "b"), 1000)
    assertNull(store.position(id))
    assertTrue(store.savePosition(id, 0, 5, 2000))
    assertTrue(store.savePosition(id, 1, 12, 3000))
    assertEquals(PositionRow(1, 12), store.position(id))
  }

  @Test fun savingThePositionOfADeletedItemReturnsFalse() {
    // Review Focus 2: the service's onDone can race a delete from the list.
    val id = store.insertText("t", listOf("a"), 1000)
    store.delete(id)
    assertFalse(store.savePosition(id, 0, 0, 2000))
    assertNull(store.position(id))
  }

  @Test fun savingAPositionDoesNotNotifyTheList() {
    // It happens after every sentence; a list refresh per sentence would be waste.
    val id = store.insertText("t", listOf("a"), 1000)
    var n = 0
    val l: () -> Unit = { n++ }
    ItemEvents.add(l)
    try {
      store.savePosition(id, 0, 1, 2000)
    } finally {
      ItemEvents.remove(l)
    }
    assertEquals(0, n)
  }

  @Test fun finishingArchivesKeepsTheStateAndClearsThePosition() {
    val id = store.insertText("t", listOf("a"), 1000)
    store.savePosition(id, 0, 3, 2000)
    store.finishReading(id, 5000)
    val item = store.item(id)!!
    assertEquals(5000L, item.archivedAt)
    assertEquals(States.READY, item.state)
    assertNull(store.position(id))
  }

  @Test fun deletingAnItemDeletesItsPosition() {
    val id = store.insertText("t", listOf("a"), 1000)
    store.savePosition(id, 0, 3, 2000)
    store.delete(id)
    assertNull(store.position(id))
  }
}
```

`T/store/SettingsTest.kt`:

```kotlin
package io.loopstring.readme.store

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsTest {
  @Test fun rateIsClampedAndRoundedToATenth() {
    assertEquals(0.5f, Rate.clamp(0.1f))
    assertEquals(4.0f, Rate.clamp(9f))
    assertEquals(1.7f, Rate.clamp(1.74f))
    assertEquals(1.8f, Rate.clamp(1.75f))
    assertEquals(Rate.DEFAULT, Rate.clamp(Float.NaN))
  }

  @Test fun theRateDefaultsTo2AndPersists() {
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    assertEquals(2.0f, Settings(ctx).rate)
    Settings(ctx).rate = 1.3f
    assertEquals(1.3f, Settings(ctx).rate)
    Settings(ctx).rate = 12f
    assertEquals(4.0f, Settings(ctx).rate)
  }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `(cd android && ./gradlew testDebugUnitTest --tests 'io.loopstring.readme.store.StorePositionTest' --tests 'io.loopstring.readme.store.SettingsTest')`
Expected: compilation FAIL, `Unresolved reference: position` / `savePosition` / `Rate` / `Settings`.

- [ ] **Step 3: Implement**

In `K/store/Store.kt`, add the import `android.database.sqlite.SQLiteConstraintException`, add after `data class ParagraphRow`:

```kotlin
/** R-M11: a character offset in a paragraph, never a sentence index (AGENTS.md 10). */
data class PositionRow(val paragraphIndex: Int, val charOffset: Int)
```

and after `restore`:

```kotlin
  /**
   * R-M11: written by PlaybackService after every sentence and on pause. False when the item is
   * gone (deleted while it played): the foreign key refuses the row. No ItemEvents: a list
   * refresh per sentence would be waste, and the list does not show positions.
   */
  fun savePosition(id: Long, paragraphIndex: Int, charOffset: Int, now: Long): Boolean =
    try {
      writableDatabase.execSQL(
        "INSERT OR REPLACE INTO positions (item_id, paragraph_index, char_offset, updated_at) " +
          "VALUES (?, ?, ?, ?)",
        arrayOf<Any>(id, paragraphIndex, charOffset, now),
      )
      true
    } catch (e: SQLiteConstraintException) {
      false
    }

  fun position(id: Long): PositionRow? =
    readableDatabase.rawQuery(
      "SELECT paragraph_index, char_offset FROM positions WHERE item_id = ?",
      arrayOf(id.toString()),
    ).use { c -> if (c.moveToFirst()) PositionRow(c.getInt(0), c.getInt(1)) else null }

  /**
   * R-M11, ADR 0007: the last kept sentence finished. Archive (state kept) and drop the
   * position, so reopening the item starts from the top rather than past its end.
   */
  fun finishReading(id: Long, now: Long) {
    val db = writableDatabase
    db.beginTransaction()
    try {
      db.execSQL("UPDATE items SET archived_at = ? WHERE id = ?", arrayOf<Any>(now, id))
      db.delete("positions", "item_id = ?", arrayOf(id.toString()))
      db.setTransactionSuccessful()
    } finally {
      db.endTransaction()
    }
    ItemEvents.changed()
  }
```

Create `K/store/Settings.kt`:

```kotlin
package io.loopstring.readme.store

import android.content.Context

/** R-M07: 0.5x to 4.0x in 0.1x steps, default 2.0x. Applied once, by setSpeechRate. */
object Rate {
  const val MIN = 0.5f
  const val MAX = 4.0f
  const val DEFAULT = 2.0f

  fun clamp(r: Float): Float {
    if (r.isNaN()) return DEFAULT
    return (Math.round(r.coerceIn(MIN, MAX) * 10) / 10f)
  }
}

/** App settings in app-private SharedPreferences (allowBackup is false, AGENTS.md 3). */
class Settings(context: Context) {
  private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

  var rate: Float
    get() = Rate.clamp(prefs.getFloat(KEY_RATE, Rate.DEFAULT))
    set(value) {
      prefs.edit().putFloat(KEY_RATE, Rate.clamp(value)).apply()
    }

  private companion object {
    const val KEY_RATE = "rate"
  }
}
```

- [ ] **Step 4: Run them to verify they pass**

Run: the Step 2 command.
Expected: `BUILD SUCCESSFUL`; 7 tests pass. If `savingThePositionOfADeletedItemReturnsFalse` fails with an uncaught exception other than `SQLiteConstraintException`, read its class: Robolectric's SQLite mode decides the class, and the device throws `SQLiteConstraintException` (code 787). Catch exactly what the device throws plus what Robolectric throws, and ledger it.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/io/loopstring/readme/store android/app/src/test/java/io/loopstring/readme/store
git commit -m "feat(store): positions, finish-reading archive and the rate setting

R-M11 positions are written per sentence by the service, so savePosition fires no list
event and returns false for an item deleted mid-play. finishReading archives and drops
the position in one transaction. Rate bounds per R-M07.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: The pure playback queue

**Files:**
- Create: `K/playback/SentenceRow.kt`, `K/playback/Utterances.kt`, `K/playback/GapStats.kt`, `K/playback/PlaybackQueue.kt`
- Test: `T/playback/UtterancesTest.kt`, `T/playback/GapStatsTest.kt`, `T/playback/PlaybackQueueTest.kt`

**Interfaces:**
- Consumes: `Rate.DEFAULT` (Task 1).
- Produces:
  - `data class SentenceRow(val paragraphIndex: Int, val start: Int, val end: Int, val text: String)`
  - `data class PlaybackSnapshot(val itemId: Long?, val playing: Boolean, val sentence: SentenceRow?, val rate: Float)`
  - `interface Speaker { fun speak(id: String, text: String); fun stop(); fun setRate(rate: Float) }`
  - `interface PlaybackSink { fun savePosition(itemId: Long, paragraphIndex: Int, charOffset: Int); fun finished(itemId: Long); fun changed(snapshot: PlaybackSnapshot) }`
  - `object Utterances { const val MAX_CHARS = 3_900; data class Fitted(val rows: List<SentenceRow>, val startIndex: Int); fun fit(sentences: List<SentenceRow>, startIndex: Int, max: Int): Fitted }`
  - `data class GapSummary(count, p50, p95, max, stalls)`, `object GapStats { STALL_MS; summarize(List<Long>) }`
  - `data class QueueStats(val gaps: GapSummary, val errors: Int)`
  - `class PlaybackQueue(speaker: Speaker, sink: PlaybackSink, clock: () -> Long, maxChars: Int = Utterances.MAX_CHARS)` with `load(itemId, sentences, startIndex, rate): Boolean`, `pause(): Boolean`, `resume(): Boolean`, `next(): Boolean`, `previous(): Boolean`, `backParagraph(): Boolean`, `setRate(rate: Float)`, `stop()`, `stopItem(itemId: Long)`, `onStart(id: String)`, `onDone(id: String)`, `onError(id: String)`, `snapshot(): PlaybackSnapshot`, `takeStats(): QueueStats`; `PlaybackQueue.AHEAD = 3`.

- [ ] **Step 1: Write the failing tests**

`T/playback/UtterancesTest.kt`:

```kotlin
package io.loopstring.readme.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UtterancesTest {
  private fun row(p: Int, start: Int, text: String) = SentenceRow(p, start, start + text.length, text)

  @Test fun shortSentencesPassThrough() {
    val rows = listOf(row(0, 0, "One."), row(0, 5, "Two."))
    val f = Utterances.fit(rows, 1, 100)
    assertEquals(rows, f.rows)
    assertEquals(1, f.startIndex)
  }

  @Test fun aLongSentenceSplitsAtWhitespaceAndKeepsOffsets() {
    // A paragraph with no punctuation is one sentence; the engine refuses input over its limit.
    val text = (1..60).joinToString(" ") { "word$it" } // 60 words, ~ 400 chars
    val rows = listOf(row(0, 0, "Before."), row(2, 10, text), row(3, 0, "After."))
    val f = Utterances.fit(rows, 2, 100)
    val pieces = f.rows.filter { it.paragraphIndex == 2 }
    assertTrue(pieces.size > 1)
    pieces.forEach { assertTrue(it.text.length <= 100) }
    assertEquals(text, pieces.joinToString("") { it.text })
    assertEquals(10, pieces.first().start)
    assertEquals(10 + text.length, pieces.last().end)
    pieces.zipWithNext { a, b -> assertEquals(a.end, b.start) }
    pieces.forEach { assertEquals(text.substring(it.start - 10, it.end - 10), it.text) }
    pieces.dropLast(1).forEach { assertTrue(it.text.endsWith(" ")) }
    assertEquals(f.rows.indexOfFirst { it.paragraphIndex == 3 }, f.startIndex)
  }

  @Test fun aLongRunWithNoSpaceIsCutAtTheLimitButNeverBetweenSurrogates() {
    val text = "a".repeat(99) + "😀" + "b".repeat(50) // emoji straddles index 100
    val f = Utterances.fit(listOf(row(0, 0, text)), 0, 100)
    assertEquals(99, f.rows[0].text.length)
    assertEquals(text, f.rows.joinToString("") { it.text })
  }
}
```

`T/playback/GapStatsTest.kt`:

```kotlin
package io.loopstring.readme.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class GapStatsTest {
  @Test fun nearestRankPercentilesAndStalls() {
    val s = GapStats.summarize((1L..100L).toList() + 1_500L)
    assertEquals(101, s.count)
    assertEquals(51L, s.p50)
    assertEquals(96L, s.p95)
    assertEquals(1_500L, s.max)
    assertEquals(1, s.stalls)
  }

  @Test fun emptyIsAllZero() {
    assertEquals(GapSummary(0, 0, 0, 0, 0), GapStats.summarize(emptyList()))
  }
}
```

`T/playback/PlaybackQueueTest.kt`:

```kotlin
package io.loopstring.readme.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackQueueTest {
  private class FakeSpeaker : Speaker {
    val spoken = mutableListOf<String>() // utterance ids, in order
    var stops = 0
    val rates = mutableListOf<Float>()
    override fun speak(id: String, text: String) { spoken += id }
    override fun stop() { stops++ }
    override fun setRate(rate: Float) { rates += rate }
  }

  private class FakeSink : PlaybackSink {
    val saves = mutableListOf<Triple<Long, Int, Int>>()
    val finished = mutableListOf<Long>()
    val snapshots = mutableListOf<PlaybackSnapshot>()
    override fun savePosition(itemId: Long, paragraphIndex: Int, charOffset: Int) {
      saves += Triple(itemId, paragraphIndex, charOffset)
    }
    override fun finished(itemId: Long) { finished += itemId }
    override fun changed(snapshot: PlaybackSnapshot) { snapshots += snapshot }
  }

  private var now = 0L
  private val speaker = FakeSpeaker()
  private val sink = FakeSink()
  private val queue = PlaybackQueue(speaker, sink, { now })

  // Paragraph 0: sentences 0,1. Paragraph 1: 2,3. Paragraph 3: 4,5 (paragraph 2 cut).
  private val rows = listOf(
    SentenceRow(0, 0, 4, "A0."), SentenceRow(0, 5, 9, "A1."),
    SentenceRow(1, 0, 4, "B0."), SentenceRow(1, 5, 9, "B1."),
    SentenceRow(3, 0, 4, "D0."), SentenceRow(3, 5, 9, "D1."),
  )

  private fun lastId() = speaker.spoken.last()
  private fun gen(id: String) = id.substringBefore(':')

  @Test fun loadAppliesTheRateOnceAndQueuesThreeAhead() {
    assertTrue(queue.load(7, rows, 0, 2.0f))
    assertEquals(listOf(2.0f), speaker.rates)
    assertEquals(3, speaker.spoken.size)
    assertEquals(listOf("0", "1", "2"), speaker.spoken.map { it.substringAfter(':') })
    assertTrue(queue.snapshot().playing)
    assertEquals(7L, queue.snapshot().itemId)
  }

  @Test fun loadRefusesAnEmptyListOrABadStart() {
    assertFalse(queue.load(7, emptyList(), 0, 2.0f))
    assertFalse(queue.load(7, rows, 6, 2.0f))
    assertTrue(speaker.spoken.isEmpty())
  }

  @Test fun eachDoneSavesTheNextSentenceStartAndTopsUp() {
    queue.load(7, rows, 0, 2.0f)
    val g = gen(lastId())
    queue.onStart("$g:0")
    queue.onDone("$g:0")
    assertEquals(Triple(7L, 0, 5), sink.saves.last())
    assertEquals("$g:3", lastId())
    assertEquals(4, speaker.spoken.size)
  }

  @Test fun theLastSentenceFinishesTheItem() {
    queue.load(7, rows, 4, 2.0f)
    val g = gen(lastId())
    queue.onDone("$g:4")
    queue.onDone("$g:5")
    assertEquals(listOf(7L), sink.finished)
    assertNull(queue.snapshot().itemId)
    assertFalse(queue.snapshot().playing)
    assertFalse(queue.resume())
  }

  @Test fun pauseStopsAndSavesTheCurrentSentenceAndResumeRequeuesFromIt() {
    queue.load(7, rows, 0, 2.0f)
    val g = gen(lastId())
    queue.onStart("$g:0"); queue.onDone("$g:0"); queue.onStart("$g:1")
    val stopsBefore = speaker.stops
    assertTrue(queue.pause())
    assertEquals(stopsBefore + 1, speaker.stops)
    assertEquals(Triple(7L, 0, 5), sink.saves.last())
    assertFalse(queue.snapshot().playing)
    assertEquals(SentenceRow(0, 5, 9, "A1."), queue.snapshot().sentence)
    val spokenBefore = speaker.spoken.size
    assertTrue(queue.resume())
    val resumed = speaker.spoken.drop(spokenBefore)
    assertEquals(listOf("1", "2", "3"), resumed.map { it.substringAfter(':') })
    assertTrue(gen(resumed[0]) != g)
  }

  @Test fun aLateCallbackFromFlushedWorkIsIgnored() {
    // Review Focus 1: TextToSpeech can deliver onDone for an utterance stop() discarded.
    queue.load(7, rows, 0, 2.0f)
    val old = gen(lastId())
    queue.onStart("$old:0")
    queue.pause()
    val saves = sink.saves.size
    val spoken = speaker.spoken.size
    queue.onDone("$old:0")
    queue.onError("$old:1")
    queue.onStart("$old:2")
    assertEquals(saves, sink.saves.size)
    assertEquals(spoken, speaker.spoken.size)
    assertEquals(SentenceRow(0, 0, 4, "A0."), queue.snapshot().sentence)
    assertEquals(0, queue.takeStats().errors)
  }

  @Test fun aRateChangeWhilePausedDoesNotSpeak() {
    // Review Focus 5.
    queue.load(7, rows, 0, 2.0f)
    queue.pause()
    val spoken = speaker.spoken.size
    queue.setRate(3.0f)
    assertEquals(spoken, speaker.spoken.size)
    assertFalse(queue.snapshot().playing)
    assertEquals(3.0f, queue.snapshot().rate)
    assertEquals(3.0f, speaker.rates.last())
    queue.resume()
    assertEquals(spoken + 3, speaker.spoken.size)
    assertEquals(listOf(2.0f, 3.0f), speaker.rates) // never applied twice
  }

  @Test fun aRateChangeWhilePlayingFlushesAndRefillsFromTheCurrentSentence() {
    queue.load(7, rows, 0, 2.0f)
    val g = gen(lastId())
    queue.onStart("$g:0"); queue.onDone("$g:0"); queue.onStart("$g:1")
    val stops = speaker.stops
    val spoken = speaker.spoken.size
    queue.setRate(1.5f)
    assertEquals(stops + 1, speaker.stops)
    assertEquals(listOf("1", "2", "3"), speaker.spoken.drop(spoken).map { it.substringAfter(':') })
  }

  @Test fun nextPreviousAndBackParagraph() {
    queue.load(7, rows, 3, 2.0f) // B1
    assertTrue(queue.next())
    assertEquals(4, rows.indexOf(queue.snapshot().sentence))
    assertTrue(queue.next())
    assertFalse(queue.next()) // on the last sentence: ignored
    assertEquals(5, rows.indexOf(queue.snapshot().sentence))
    assertTrue(queue.backParagraph()) // from D1 to the first sentence of paragraph 1 (B0)
    assertEquals(2, rows.indexOf(queue.snapshot().sentence))
    assertTrue(queue.backParagraph()) // from B0 to A0
    assertEquals(0, rows.indexOf(queue.snapshot().sentence))
    assertTrue(queue.backParagraph()) // at the top: stays
    assertEquals(0, rows.indexOf(queue.snapshot().sentence))
    assertTrue(queue.previous())
    assertEquals(0, rows.indexOf(queue.snapshot().sentence))
    assertEquals(Triple(7L, 0, 0), sink.saves.last())
  }

  @Test fun aJumpWhilePausedMovesWithoutSpeaking() {
    queue.load(7, rows, 0, 2.0f)
    queue.pause()
    val spoken = speaker.spoken.size
    queue.next()
    assertEquals(spoken, speaker.spoken.size)
    assertFalse(queue.snapshot().playing)
    assertEquals(Triple(7L, 0, 5), sink.saves.last())
  }

  @Test fun stopItemClearsWithoutSaving() {
    // Review Focus 2: the item is being deleted; there is nothing to save into.
    queue.load(7, rows, 0, 2.0f)
    queue.stopItem(8)
    assertEquals(7L, queue.snapshot().itemId)
    val saves = sink.saves.size
    queue.stopItem(7)
    assertNull(queue.snapshot().itemId)
    assertEquals(saves, sink.saves.size)
  }

  @Test fun loadingAnotherItemSavesTheFirstOnesPosition() {
    queue.load(7, rows, 2, 2.0f)
    queue.load(9, rows, 0, 2.0f)
    assertEquals(Triple(7L, 1, 0), sink.saves.last())
    assertEquals(9L, queue.snapshot().itemId)
  }

  @Test fun anEngineErrorSkipsTheSentenceAndCounts() {
    queue.load(7, rows, 0, 2.0f)
    val g = gen(lastId())
    queue.onStart("$g:0")
    queue.onError("$g:0")
    assertEquals(Triple(7L, 0, 5), sink.saves.last())
    assertEquals(1, queue.takeStats().errors)
  }

  @Test fun gapsAreDoneToNextStartInContinuousPlayOnly() {
    queue.load(7, rows, 0, 2.0f)
    val g = gen(lastId())
    now = 100; queue.onStart("$g:0")
    now = 1000; queue.onDone("$g:0")
    now = 1030; queue.onStart("$g:1")
    now = 2000; queue.onDone("$g:1")
    queue.pause()
    now = 9000; queue.resume()
    val g2 = gen(lastId())
    now = 9100; queue.onStart("$g2:2")
    val s = queue.takeStats().gaps
    assertEquals(1, s.count)
    assertEquals(30L, s.max)
    assertEquals(0, queue.takeStats().gaps.count) // taking resets
  }

  @Test fun everySentenceStartIsPublished() {
    queue.load(7, rows, 0, 2.0f)
    val g = gen(lastId())
    queue.onStart("$g:0"); queue.onDone("$g:0"); queue.onStart("$g:1")
    assertEquals(SentenceRow(0, 5, 9, "A1."), sink.snapshots.last().sentence)
    assertTrue(sink.snapshots.last().playing)
  }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `(cd android && ./gradlew testDebugUnitTest --tests 'io.loopstring.readme.playback.*')`
Expected: compilation FAIL, `Unresolved reference: SentenceRow` (and the other new names).

- [ ] **Step 3: Implement**

`K/playback/SentenceRow.kt`:

```kotlin
package io.loopstring.readme.playback

/**
 * One utterance: `text` is exactly the paragraph's text from `start` to `end`. It carries item
 * text, so it is never logged or put in a log-bound message (AGENTS.md 1).
 */
data class SentenceRow(val paragraphIndex: Int, val start: Int, val end: Int, val text: String)

/** What the service is doing now; `sentence` is the current (or paused-at) sentence. */
data class PlaybackSnapshot(
  val itemId: Long?,
  val playing: Boolean,
  val sentence: SentenceRow?,
  val rate: Float,
)

/** The engine side of the queue. PlaybackService backs it with a TextToSpeech instance. */
interface Speaker {
  fun speak(id: String, text: String)
  fun stop()
  fun setRate(rate: Float)
}

/**
 * Where the queue's effects go. Called with the queue's lock held, on whichever thread drove
 * the queue: an implementation must not call back into the queue, and posts UI work.
 */
interface PlaybackSink {
  fun savePosition(itemId: Long, paragraphIndex: Int, charOffset: Int)
  fun finished(itemId: Long)
  fun changed(snapshot: PlaybackSnapshot)
}
```

`K/playback/Utterances.kt`:

```kotlin
package io.loopstring.readme.playback

/**
 * TextToSpeech refuses input longer than getMaxSpeechInputLength() (documented as 4000), and a
 * paragraph with no sentence punctuation is one sentence. Such a sentence is split after
 * whitespace into pieces that keep exact character offsets, so positions stay offsets
 * (AGENTS.md 10).
 */
object Utterances {
  const val MAX_CHARS = 3_900

  data class Fitted(val rows: List<SentenceRow>, val startIndex: Int)

  fun fit(sentences: List<SentenceRow>, startIndex: Int, max: Int): Fitted {
    val out = ArrayList<SentenceRow>(sentences.size)
    var start = 0
    sentences.forEachIndexed { i, s ->
      if (i == startIndex) start = out.size
      val t = s.text
      var a = 0
      while (t.length - a > max) {
        val b = cutPoint(t, a, max)
        out += SentenceRow(s.paragraphIndex, s.start + a, s.start + b, t.substring(a, b))
        a = b
      }
      out += SentenceRow(s.paragraphIndex, s.start + a, s.end, t.substring(a))
    }
    return Fitted(out, start)
  }

  /** After the last whitespace in the back half of the window, else at the limit. */
  private fun cutPoint(t: String, from: Int, max: Int): Int {
    for (i in from + max - 1 downTo from + max / 2) {
      if (Character.isWhitespace(t[i])) return i + 1
    }
    val end = from + max
    return if (Character.isHighSurrogate(t[end - 1])) end - 1 else end
  }
}
```

`K/playback/GapStats.kt` (from the SPIKE-05 probe, `spike/rea-0-gapless-2x`):

```kotlin
package io.loopstring.readme.playback

/** R-M07 inter-utterance gap summary. A stall is a gap over [GapStats.STALL_MS]. */
data class GapSummary(val count: Int, val p50: Long, val p95: Long, val max: Long, val stalls: Int)

object GapStats {
  const val STALL_MS = 1_000L

  fun summarize(gapsMs: List<Long>): GapSummary {
    if (gapsMs.isEmpty()) return GapSummary(0, 0, 0, 0, 0)
    val s = gapsMs.sorted()
    return GapSummary(s.size, percentile(s, 50), percentile(s, 95), s.last(), s.count { it > STALL_MS })
  }

  /** Nearest-rank percentile of an ascending list. */
  fun percentile(sorted: List<Long>, p: Int): Long {
    val rank = Math.ceil(p / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
    return sorted[rank - 1]
  }
}
```

`K/playback/PlaybackQueue.kt`:

```kotlin
package io.loopstring.readme.playback

import io.loopstring.readme.store.Rate

data class QueueStats(val gaps: GapSummary, val errors: Int)

/**
 * R-M07 and srs.md "Playback": the sentence queue the native service owns (AGENTS.md 11).
 * Utterance ids are "<generation>:<index>". Every flush (pause, jump, rate change, new item)
 * starts a new generation, so a callback the engine delivers late for discarded work is
 * ignored. Gaps (onDone(n) to onStart(n+1)) are measured only across continuous play.
 */
class PlaybackQueue(
  private val speaker: Speaker,
  private val sink: PlaybackSink,
  private val clock: () -> Long,
  private val maxChars: Int = Utterances.MAX_CHARS,
) {
  private val lock = Any()
  private var itemId: Long? = null
  private var rows: List<SentenceRow> = emptyList()
  private var current = 0
  private var queuedUntil = 0
  private var generation = 0
  private var playing = false
  private var rate = Rate.DEFAULT
  private var lastDoneAt = -1L
  private val gaps = ArrayList<Long>()
  private var errors = 0

  fun load(itemId: Long, sentences: List<SentenceRow>, startIndex: Int, rate: Float): Boolean =
    synchronized(lock) {
      if (startIndex !in sentences.indices) return false
      saveLocked()
      val fitted = Utterances.fit(sentences, startIndex, maxChars)
      this.itemId = itemId
      rows = fitted.rows
      this.rate = rate
      speaker.setRate(rate)
      restartLocked(fitted.startIndex)
      true
    }

  fun pause(): Boolean = synchronized(lock) {
    if (!playing) return false
    flushLocked()
    playing = false
    saveLocked()
    publishLocked()
    true
  }

  fun resume(): Boolean = synchronized(lock) {
    if (playing || itemId == null) return false
    restartLocked(current)
    true
  }

  fun next(): Boolean = synchronized(lock) {
    if (itemId == null || current + 1 >= rows.size) return false
    jumpLocked(current + 1)
    true
  }

  fun previous(): Boolean = synchronized(lock) {
    if (itemId == null) return false
    jumpLocked(maxOf(current - 1, 0))
    true
  }

  /** To the first sentence of the previous paragraph; at the first paragraph, to its start. */
  fun backParagraph(): Boolean = synchronized(lock) {
    if (itemId == null) return false
    val here = rows[current].paragraphIndex
    val before = (current - 1 downTo 0).firstOrNull { rows[it].paragraphIndex < here }
    val target = if (before == null) {
      0
    } else {
      val p = rows[before].paragraphIndex
      (0..before).first { rows[it].paragraphIndex == p }
    }
    jumpLocked(target)
    true
  }

  /** setSpeechRate is the only rate lever (AGENTS.md 9). Paused stays paused. */
  fun setRate(rate: Float) = synchronized(lock) {
    this.rate = rate
    speaker.setRate(rate)
    if (playing) restartLocked(current) else publishLocked()
  }

  fun stop() = synchronized(lock) {
    if (itemId == null) return
    flushLocked()
    saveLocked()
    clearLocked()
  }

  /** The item is being deleted: stop without saving into it. */
  fun stopItem(id: Long) = synchronized(lock) {
    if (itemId != id) return
    flushLocked()
    clearLocked()
  }

  fun onStart(id: String) = synchronized(lock) {
    val i = indexOf(id) ?: return
    if (lastDoneAt >= 0) gaps += clock() - lastDoneAt
    lastDoneAt = -1
    current = i
    publishLocked()
  }

  fun onDone(id: String) = synchronized(lock) {
    val i = indexOf(id) ?: return
    lastDoneAt = clock()
    advanceLocked(i)
  }

  fun onError(id: String) = synchronized(lock) {
    val i = indexOf(id) ?: return
    errors++
    lastDoneAt = -1
    advanceLocked(i)
  }

  fun snapshot(): PlaybackSnapshot = synchronized(lock) { snapshotLocked() }

  fun takeStats(): QueueStats = synchronized(lock) {
    val s = QueueStats(GapStats.summarize(gaps), errors)
    gaps.clear()
    errors = 0
    s
  }

  private fun advanceLocked(i: Int) {
    val id = itemId ?: return
    val next = i + 1
    if (next >= rows.size) {
      flushLocked()
      playing = false
      sink.finished(id)
      clearLocked()
      return
    }
    current = next
    sink.savePosition(id, rows[next].paragraphIndex, rows[next].start)
    topUpLocked()
  }

  private fun jumpLocked(i: Int) {
    if (playing) {
      restartLocked(i)
    } else {
      current = i
      publishLocked()
    }
    saveLocked()
  }

  private fun restartLocked(index: Int) {
    flushLocked()
    current = index
    queuedUntil = index
    playing = true
    topUpLocked()
    publishLocked()
  }

  private fun flushLocked() {
    generation++
    speaker.stop()
    lastDoneAt = -1
  }

  private fun topUpLocked() {
    while (playing && queuedUntil < rows.size && queuedUntil - current < AHEAD) {
      speaker.speak("$generation:$queuedUntil", rows[queuedUntil].text)
      queuedUntil++
    }
  }

  private fun saveLocked() {
    val id = itemId ?: return
    val r = rows.getOrNull(current) ?: return
    sink.savePosition(id, r.paragraphIndex, r.start)
  }

  private fun clearLocked() {
    itemId = null
    rows = emptyList()
    current = 0
    queuedUntil = 0
    playing = false
    lastDoneAt = -1
    publishLocked()
  }

  private fun indexOf(id: String): Int? {
    if (itemId == null) return null
    val g = id.substringBefore(':', "").toIntOrNull() ?: return null
    if (g != generation) return null
    return id.substringAfter(':').toIntOrNull()?.takeIf { it in rows.indices }
  }

  private fun snapshotLocked() =
    PlaybackSnapshot(itemId, playing, if (itemId == null) null else rows.getOrNull(current), rate)

  private fun publishLocked() = sink.changed(snapshotLocked())

  companion object {
    const val AHEAD = 3
  }
}
```

Note on `load`: `saveLocked()` with nothing loaded is a no-op, so the first load saves nothing.

- [ ] **Step 4: Run them to verify they pass**

Run: `(cd android && ./gradlew testDebugUnitTest --tests 'io.loopstring.readme.playback.*')`
Expected: `BUILD SUCCESSFUL`; 20 tests pass (3 + 2 + 15).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/io/loopstring/readme/playback android/app/src/test/java/io/loopstring/readme/playback
git commit -m "feat(playback): the service-owned sentence queue

QUEUE_ADD three ahead with generation-tagged utterance ids, so late callbacks for
flushed work are ignored. Positions are the next sentence's start offset after each
onDone. Over-long sentences are split at whitespace with offsets kept. Gaps measured
done-to-start across continuous play only (R-M07).

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Voice, focus and command policies

**Files:**
- Create: `K/playback/Policies.kt`
- Test: `T/playback/PoliciesTest.kt`

**Interfaces:**
- Produces:
  - `data class VoiceInfo(val name: String, val language: String, val networkRequired: Boolean, val notInstalled: Boolean, val quality: Int)`; `object VoicePicker { fun pick(default: VoiceInfo?, voices: List<VoiceInfo>, language: String): VoiceInfo? }`
  - `enum class FocusAction { PAUSE, PAUSE_TRANSIENT, RESUME, NONE }`; `object FocusPolicy { fun onChange(change: Int, pausedForFocus: Boolean): FocusAction }`
  - `object PlaybackCommands { ACTION_START, ACTION_PLAY, ACTION_PAUSE, ACTION_TOGGLE, ACTION_NEXT, ACTION_PREVIOUS, ACTION_BACK_PARAGRAPH, ACTION_STOP; enum class Route { START, CONTROL, IGNORE, STOP }; fun route(action: String?, hasPending: Boolean, hasItem: Boolean): Route }`

- [ ] **Step 1: Write the failing tests**

`T/playback/PoliciesTest.kt`:

```kotlin
package io.loopstring.readme.playback

import android.media.AudioManager
import io.loopstring.readme.playback.PlaybackCommands.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoicePickerTest {
  private fun v(name: String, lang: String = "en", net: Boolean = false, missing: Boolean = false, q: Int = 300) =
    VoiceInfo(name, lang, net, missing, q)

  @Test fun anOfflineDefaultIsKept() {
    assertEquals("d", VoicePicker.pick(v("d"), listOf(v("d"), v("x", q = 500)), "en")?.name)
  }

  @Test fun aNetworkDefaultIsReplacedByTheBestOfflineVoiceInItsLanguage() {
    val voices = listOf(v("net", net = true), v("fr", "fr", q = 500), v("en-lo", q = 200), v("en-hi", q = 400))
    assertEquals("en-hi", VoicePicker.pick(v("net", net = true), voices, "en")?.name)
  }

  @Test fun anyOfflineVoiceBeatsNone() {
    assertEquals("fr", VoicePicker.pick(null, listOf(v("fr", "fr")), "en")?.name)
  }

  @Test fun networkOnlyOrUninstalledVoicesGiveNull() {
    // R-M06 / AGENTS.md 5: never a network voice, not even as a fallback.
    assertNull(VoicePicker.pick(v("a", net = true), listOf(v("a", net = true), v("b", missing = true)), "en"))
  }
}

class FocusPolicyTest {
  @Test fun permanentLossPauses() {
    assertEquals(FocusAction.PAUSE, FocusPolicy.onChange(AudioManager.AUDIOFOCUS_LOSS, false))
  }

  @Test fun transientLossAndDuckPauseForFocus() {
    assertEquals(FocusAction.PAUSE_TRANSIENT, FocusPolicy.onChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, false))
    assertEquals(
      FocusAction.PAUSE_TRANSIENT,
      FocusPolicy.onChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK, false),
    )
  }

  @Test fun gainResumesOnlyWhatATransientLossPaused() {
    // Review Focus 3: a user's pause is never undone by focus coming back.
    assertEquals(FocusAction.RESUME, FocusPolicy.onChange(AudioManager.AUDIOFOCUS_GAIN, true))
    assertEquals(FocusAction.NONE, FocusPolicy.onChange(AudioManager.AUDIOFOCUS_GAIN, false))
  }
}

class PlaybackCommandsTest {
  @Test fun aStartWithARequestStarts() {
    assertEquals(Route.START, PlaybackCommands.route(PlaybackCommands.ACTION_START, true, false))
    assertEquals(Route.START, PlaybackCommands.route(PlaybackCommands.ACTION_START, true, true))
  }

  @Test fun aControlWithNothingLoadedStops() {
    // Review Focus 4: a notification action or media button after the process died.
    for (a in listOf(PlaybackCommands.ACTION_PLAY, PlaybackCommands.ACTION_NEXT, PlaybackCommands.ACTION_TOGGLE)) {
      assertEquals(Route.STOP, PlaybackCommands.route(a, false, false))
    }
    assertEquals(Route.STOP, PlaybackCommands.route(PlaybackCommands.ACTION_START, false, false))
    assertEquals(Route.STOP, PlaybackCommands.route(null, false, false))
  }

  @Test fun aControlWithAnItemIsAControl() {
    assertEquals(Route.CONTROL, PlaybackCommands.route(PlaybackCommands.ACTION_PAUSE, false, true))
    assertEquals(Route.IGNORE, PlaybackCommands.route("other", false, true))
    assertEquals(Route.IGNORE, PlaybackCommands.route(PlaybackCommands.ACTION_START, false, true))
  }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `(cd android && ./gradlew testDebugUnitTest --tests 'io.loopstring.readme.playback.*')`
Expected: compilation FAIL, `Unresolved reference: VoiceInfo`.

- [ ] **Step 3: Implement**

`K/playback/Policies.kt`:

```kotlin
package io.loopstring.readme.playback

import android.media.AudioManager

/** A TextToSpeech Voice reduced to what the choice needs, so the rule is testable. */
data class VoiceInfo(
  val name: String,
  val language: String,
  val networkRequired: Boolean,
  val notInstalled: Boolean,
  val quality: Int,
)

/**
 * R-M06, AGENTS.md 5: the engine's default voice if it is offline and installed, else the best
 * offline voice in the default's language, else the best offline voice at all, else null (the
 * blocking "no offline voice" state). A network voice is never returned.
 */
object VoicePicker {
  fun pick(default: VoiceInfo?, voices: List<VoiceInfo>, language: String): VoiceInfo? {
    fun usable(v: VoiceInfo) = !v.networkRequired && !v.notInstalled
    if (default != null && usable(default)) return default
    val offline = voices.filter(::usable)
    return offline.filter { it.language == language }.maxByOrNull { it.quality }
      ?: offline.maxByOrNull { it.quality }
  }
}

enum class FocusAction { PAUSE, PAUSE_TRANSIENT, RESUME, NONE }

/** R-M07: pause on any loss; resume on gain only what a transient loss paused. */
object FocusPolicy {
  fun onChange(change: Int, pausedForFocus: Boolean): FocusAction = when (change) {
    AudioManager.AUDIOFOCUS_LOSS -> FocusAction.PAUSE
    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> FocusAction.PAUSE_TRANSIENT
    AudioManager.AUDIOFOCUS_GAIN -> if (pausedForFocus) FocusAction.RESUME else FocusAction.NONE
    else -> FocusAction.NONE
  }
}

/**
 * What PlaybackService does with an intent. A notification action can arrive after the process
 * died: the new process has no queue and no request, so the service stops instead of sitting
 * in the foreground with nothing to say.
 */
object PlaybackCommands {
  private const val P = "io.loopstring.readme.playback."
  const val ACTION_START = P + "START"
  const val ACTION_PLAY = P + "PLAY"
  const val ACTION_PAUSE = P + "PAUSE"
  const val ACTION_TOGGLE = P + "TOGGLE"
  const val ACTION_NEXT = P + "NEXT"
  const val ACTION_PREVIOUS = P + "PREVIOUS"
  const val ACTION_BACK_PARAGRAPH = P + "BACK_PARAGRAPH"
  const val ACTION_STOP = P + "STOP"

  private val CONTROLS = setOf(
    ACTION_PLAY, ACTION_PAUSE, ACTION_TOGGLE, ACTION_NEXT, ACTION_PREVIOUS, ACTION_BACK_PARAGRAPH, ACTION_STOP,
  )

  enum class Route { START, CONTROL, IGNORE, STOP }

  fun route(action: String?, hasPending: Boolean, hasItem: Boolean): Route = when {
    action == ACTION_START && hasPending -> Route.START
    action in CONTROLS && hasItem -> Route.CONTROL
    hasItem -> Route.IGNORE
    else -> Route.STOP
  }
}
```

- [ ] **Step 4: Run them to verify they pass**

Run: `(cd android && ./gradlew testDebugUnitTest --tests 'io.loopstring.readme.playback.*')`
Expected: `BUILD SUCCESSFUL`; 30 playback tests pass (20 + 10). `AudioManager` constants are compile-time ints, so these run on the plain JVM.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/io/loopstring/readme/playback/Policies.kt android/app/src/test/java/io/loopstring/readme/playback/PoliciesTest.kt
git commit -m "feat(playback): offline voice, audio focus and intent routing rules

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: The Android shell: TtsSpeaker, PlaybackHub, PlaybackService, manifest

**Files:**
- Create: `K/playback/TtsSpeaker.kt`, `K/playback/PlaybackHub.kt`, `K/playback/PlaybackService.kt`
- Modify: `android/app/src/main/AndroidManifest.xml`
- Test: `T/playback/PlaybackHubTest.kt`

**Interfaces:**
- Consumes: Task 1 `Store.savePosition`, `Store.finishReading`, `Settings.rate`; Task 2 `PlaybackQueue`, `Speaker`, `PlaybackSink`, `PlaybackSnapshot`, `SentenceRow`; Task 3 `VoicePicker`, `VoiceInfo`, `FocusPolicy`, `FocusAction`, `PlaybackCommands`.
- Produces:
  - `class TtsSpeaker(context: Context, callbacks: TtsSpeaker.Callbacks) : Speaker` with `enum class EngineStatus(val wire: String) { PENDING("pending"), READY("ready"), NO_ENGINE("no-engine"), NO_VOICE("no-voice") }`, `val status`, `fun shutdown()`, `TtsSpeaker.ATTRIBUTES`.
  - `object PlaybackHub`: `data class Request(itemId: Long, title: String, sentences: List<SentenceRow>, startIndex: Int)`; `offer(r)`, `take(): Request?`, `hasPending(): Boolean`; `@Volatile engine: String` (`"unknown"` until a service reports); `@Volatile speaking: Boolean` (ADR 0004); `val last: PlaybackSnapshot`; `publish(s)`; `addListener/removeListener((PlaybackSnapshot) -> Unit)`; `@Volatile queue: PlaybackQueue?`; `@Volatile controller: ((String) -> Boolean)?`; `control(action: String): Boolean`; `resetForTest()`.
  - `PlaybackService.start(context: Context, request: PlaybackHub.Request)`.
  - Log lines (tag `ReadMe`), the device scripts match them: `playback start item=<id> sentences=<n> from=<i> rate=<r>`, `playback paused item=<id> paragraph=<p> offset=<o>`, `playback resumed item=<id>`, `playback gaps n=<n> p50=<ms> p95=<ms> max=<ms> stalls=<n> errors=<n>`, `playback finished item=<id>`, `playback engine <wire>`, `playback resume refused: <ExceptionClass>`.

- [ ] **Step 1: Write the failing test**

`T/playback/PlaybackHubTest.kt`:

```kotlin
package io.loopstring.readme.playback

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackHubTest {
  @After fun tearDown() = PlaybackHub.resetForTest()

  @Test fun aRequestIsTakenOnce() {
    val r = PlaybackHub.Request(1, "t", listOf(SentenceRow(0, 0, 1, "a")), 0)
    PlaybackHub.offer(r)
    assertTrue(PlaybackHub.hasPending())
    assertEquals(r, PlaybackHub.take())
    assertNull(PlaybackHub.take())
  }

  @Test fun publishingSetsSpeakingAndNotifiesListeners() {
    // ADR 0004: the bridge reads `speaking` to answer 503 while Read Me plays.
    val seen = mutableListOf<PlaybackSnapshot>()
    val l: (PlaybackSnapshot) -> Unit = { seen += it }
    PlaybackHub.addListener(l)
    PlaybackHub.publish(PlaybackSnapshot(1, true, null, 2f))
    assertTrue(PlaybackHub.speaking)
    PlaybackHub.publish(PlaybackSnapshot(1, false, null, 2f))
    assertFalse(PlaybackHub.speaking)
    PlaybackHub.removeListener(l)
    PlaybackHub.publish(PlaybackSnapshot(null, false, null, 2f))
    assertEquals(2, seen.size)
    assertNull(PlaybackHub.last.itemId)
  }

  @Test fun controlWithNoServiceIsFalse() {
    assertFalse(PlaybackHub.control(PlaybackCommands.ACTION_PAUSE))
    PlaybackHub.controller = { it == PlaybackCommands.ACTION_PAUSE }
    assertTrue(PlaybackHub.control(PlaybackCommands.ACTION_PAUSE))
  }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `(cd android && ./gradlew testDebugUnitTest --tests 'io.loopstring.readme.playback.PlaybackHubTest')`
Expected: compilation FAIL, `Unresolved reference: PlaybackHub`.

- [ ] **Step 3: Implement PlaybackHub**

`K/playback/PlaybackHub.kt`:

```kotlin
package io.loopstring.readme.playback

import androidx.annotation.VisibleForTesting
import io.loopstring.readme.store.Rate
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicReference

/**
 * In-process meeting point between ReadMeSpeech (JS), PlaybackService and, in Phase 5, the
 * bridge. The sentence list travels here, not in an Intent: a long article's list would pass
 * the 1 MB Binder transaction limit. `speaking` is ADR 0004's flag.
 */
object PlaybackHub {
  data class Request(val itemId: Long, val title: String, val sentences: List<SentenceRow>, val startIndex: Int)

  private val pending = AtomicReference<Request?>(null)
  private val listeners = CopyOnWriteArraySet<(PlaybackSnapshot) -> Unit>()
  private val idle = PlaybackSnapshot(null, false, null, Rate.DEFAULT)

  @Volatile var last: PlaybackSnapshot = idle
    private set
  @Volatile var speaking = false
    private set
  @Volatile var engine = "unknown"
  @Volatile var queue: PlaybackQueue? = null
  @Volatile var controller: ((String) -> Boolean)? = null

  fun offer(r: Request) = pending.set(r)
  fun take(): Request? = pending.getAndSet(null)
  fun hasPending() = pending.get() != null

  fun publish(s: PlaybackSnapshot) {
    last = s
    speaking = s.playing
    for (l in listeners) l(s)
  }

  fun addListener(l: (PlaybackSnapshot) -> Unit) { listeners.add(l) }
  fun removeListener(l: (PlaybackSnapshot) -> Unit) { listeners.remove(l) }

  fun control(action: String): Boolean = controller?.invoke(action) ?: false

  @VisibleForTesting
  fun resetForTest() {
    pending.set(null)
    listeners.clear()
    last = idle
    speaking = false
    engine = "unknown"
    queue = null
    controller = null
  }
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: the Step 2 command.
Expected: `BUILD SUCCESSFUL`; 3 tests pass.

- [ ] **Step 5: Implement TtsSpeaker**

`K/playback/TtsSpeaker.kt`:

```kotlin
package io.loopstring.readme.playback

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale

/**
 * Playback's own TextToSpeech instance (AGENTS.md 13). setSpeechRate is the only rate lever
 * (AGENTS.md 9). The engine's voice is chosen by VoicePicker so a network voice is never used
 * (AGENTS.md 5). Construct on the main thread; callbacks other than onReady arrive on the
 * engine's binder thread.
 */
class TtsSpeaker(context: Context, private val callbacks: Callbacks) : Speaker {
  interface Callbacks {
    fun onReady(status: EngineStatus)
    fun onStart(id: String)
    fun onDone(id: String)
    fun onError(id: String)
  }

  enum class EngineStatus(val wire: String) {
    PENDING("pending"), READY("ready"), NO_ENGINE("no-engine"), NO_VOICE("no-voice")
  }

  private val main = Handler(Looper.getMainLooper())

  @Volatile var status = EngineStatus.PENDING
    private set

  // onInit can run synchronously inside this constructor when the engine fails to bind, before
  // `tts` is assigned (SPIKE-05 probe). Posting defers every use of `tts` until it exists.
  private val tts = TextToSpeech(context.applicationContext) { s -> main.post { onInit(s) } }

  private fun onInit(result: Int) {
    status = if (result != TextToSpeech.SUCCESS) EngineStatus.NO_ENGINE else chooseVoice()
    if (status == EngineStatus.READY) {
      tts.setAudioAttributes(ATTRIBUTES)
      tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
        override fun onStart(id: String?) { if (id != null) callbacks.onStart(id) }
        override fun onDone(id: String?) { if (id != null) callbacks.onDone(id) }
        @Deprecated("Deprecated in Java")
        override fun onError(id: String?) { if (id != null) callbacks.onError(id) }
        override fun onError(id: String?, errorCode: Int) { if (id != null) callbacks.onError(id) }
      })
    }
    callbacks.onReady(status)
  }

  private fun chooseVoice(): EngineStatus {
    val voices = runCatching { tts.voices }.getOrNull().orEmpty().toList()
    val default = runCatching { tts.defaultVoice }.getOrNull()
    val language = (default?.locale ?: Locale.getDefault()).language
    val pick = VoicePicker.pick(default?.let(::info), voices.map(::info), language)
      ?: return EngineStatus.NO_VOICE
    val voice = (voices + listOfNotNull(default)).first { it.name == pick.name }
    return if (tts.setVoice(voice) == TextToSpeech.SUCCESS) EngineStatus.READY else EngineStatus.NO_VOICE
  }

  private fun info(v: Voice) = VoiceInfo(
    name = v.name,
    language = v.locale.language,
    networkRequired = v.isNetworkConnectionRequired,
    notInstalled = v.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true,
    quality = v.quality,
  )

  override fun speak(id: String, text: String) {
    tts.speak(text, TextToSpeech.QUEUE_ADD, null, id)
  }

  override fun stop() {
    tts.stop()
  }

  override fun setRate(rate: Float) {
    tts.setSpeechRate(rate)
  }

  fun shutdown() {
    tts.stop()
    tts.shutdown()
  }

  companion object {
    val ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
      .setUsage(AudioAttributes.USAGE_MEDIA)
      .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
      .build()

    /** The engine's documented input limit, less headroom. */
    fun maxChars(): Int = minOf(Utterances.MAX_CHARS, TextToSpeech.getMaxSpeechInputLength() - 100)
  }
}
```

- [ ] **Step 6: Implement PlaybackService**

`K/playback/PlaybackService.kt`:

```kotlin
package io.loopstring.readme.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import io.loopstring.readme.MainActivity
import io.loopstring.readme.playback.PlaybackCommands.Route
import io.loopstring.readme.store.Settings
import io.loopstring.readme.store.Store

/**
 * R-M07: the `mediaPlayback` foreground service that owns playback (ADR 0005; Phase 5's bridge
 * moves in later). The queue runs on TTS binder threads and never needs JS. Logs carry ids,
 * counts, offsets and durations only; the title appears in the notification, never in a log
 * (AGENTS.md 1).
 */
class PlaybackService : Service(), PlaybackSink, TtsSpeaker.Callbacks {
  private val main = Handler(Looper.getMainLooper())
  private lateinit var store: Store
  private lateinit var audio: AudioManager
  private lateinit var speaker: TtsSpeaker
  private lateinit var queue: PlaybackQueue
  private lateinit var session: MediaSession
  private var focusRequest: AudioFocusRequest? = null
  @Volatile private var pausedForFocus = false
  private var wakeLock: PowerManager.WakeLock? = null
  private var noisyRegistered = false
  private var waiting: PlaybackHub.Request? = null
  private var title = ""
  private var shown: PlaybackSnapshot? = null
  @Volatile private var destroyed = false

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    store = Store.get(this)
    audio = getSystemService(AudioManager::class.java)
    if (Build.VERSION.SDK_INT >= 26) {
      getSystemService(NotificationManager::class.java).createNotificationChannel(
        NotificationChannel(CHANNEL, "Playback", NotificationManager.IMPORTANCE_LOW),
      )
    }
    session = MediaSession(this, "ReadMe").apply { setCallback(sessionCallback, main) }
    speaker = TtsSpeaker(this, this)
    queue = PlaybackQueue(speaker, this, { SystemClock.elapsedRealtime() }, TtsSpeaker.maxChars())
    PlaybackHub.queue = queue
    PlaybackHub.controller = ::handle
    PlaybackHub.engine = speaker.status.wire
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    // startForegroundService obliges startForeground within seconds, whatever happens next.
    goForeground(queue.snapshot())
    when (PlaybackCommands.route(intent?.action, PlaybackHub.hasPending(), queue.snapshot().itemId != null)) {
      Route.START -> PlaybackHub.take()?.let(::begin)
      Route.CONTROL -> handle(intent!!.action!!)
      Route.IGNORE -> {}
      Route.STOP -> {
        end()
        return START_NOT_STICKY
      }
    }
    // A control that changed nothing (or left playback paused) must not keep the service in
    // the foreground; the queue's own snapshot posts run first, so this sees the final state.
    main.post { if (!destroyed && waiting == null) syncForeground(queue.snapshot()) }
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    destroyed = true
    queue.pause() // saves the position if it was playing
    logStats()
    main.removeCallbacksAndMessages(null)
    releasePlayingResources(abandon = true)
    PlaybackHub.controller = null
    PlaybackHub.queue = null
    PlaybackHub.publish(queue.snapshot().copy(playing = false))
    session.release()
    speaker.shutdown()
    super.onDestroy()
  }

  // --- requests and controls ---

  private fun begin(r: PlaybackHub.Request) {
    when (speaker.status) {
      TtsSpeaker.EngineStatus.PENDING -> waiting = r
      TtsSpeaker.EngineStatus.READY -> load(r)
      else -> end()
    }
  }

  private fun load(r: PlaybackHub.Request) {
    title = r.title
    val rate = Settings(this).rate
    if (queue.load(r.itemId, r.sentences, r.startIndex, rate)) {
      Log.i(TAG, "playback start item=${r.itemId} sentences=${r.sentences.size} from=${r.startIndex} rate=$rate")
    } else {
      end()
    }
  }

  /** Every control path (JS, notification, media session, noisy, focus) comes through here. */
  private fun handle(action: String): Boolean = when (action) {
    PlaybackCommands.ACTION_PAUSE -> {
      pausedForFocus = false
      queue.pause()
    }
    PlaybackCommands.ACTION_PLAY -> queue.resume()
    PlaybackCommands.ACTION_TOGGLE ->
      if (queue.snapshot().playing) handle(PlaybackCommands.ACTION_PAUSE) else queue.resume()
    PlaybackCommands.ACTION_NEXT -> queue.next()
    PlaybackCommands.ACTION_PREVIOUS -> queue.previous()
    PlaybackCommands.ACTION_BACK_PARAGRAPH -> queue.backParagraph()
    PlaybackCommands.ACTION_STOP -> {
      queue.stop()
      true
    }
    else -> false
  }

  // --- TtsSpeaker.Callbacks ---

  override fun onReady(status: TtsSpeaker.EngineStatus) {
    PlaybackHub.engine = status.wire
    val r = waiting
    waiting = null
    if (status == TtsSpeaker.EngineStatus.READY) {
      if (r != null) load(r)
    } else {
      Log.i(TAG, "playback engine ${status.wire}")
      PlaybackHub.publish(queue.snapshot())
      end()
    }
  }

  override fun onStart(id: String) = queue.onStart(id)
  override fun onDone(id: String) = queue.onDone(id)
  override fun onError(id: String) = queue.onError(id)

  // --- PlaybackSink (queue lock held; no calls back into the queue) ---

  override fun savePosition(itemId: Long, paragraphIndex: Int, charOffset: Int) {
    store.savePosition(itemId, paragraphIndex, charOffset, System.currentTimeMillis())
  }

  override fun finished(itemId: Long) {
    store.finishReading(itemId, System.currentTimeMillis())
    Log.i(TAG, "playback finished item=$itemId")
  }

  override fun changed(snapshot: PlaybackSnapshot) {
    PlaybackHub.publish(snapshot)
    main.post { if (!destroyed) onSnapshot(snapshot) }
  }

  // --- main thread ---

  private fun onSnapshot(s: PlaybackSnapshot) {
    val before = shown
    shown = s
    if (s.itemId == null) {
      logStats()
      end()
      return
    }
    if (s.playing && before?.playing != true) {
      if (before?.itemId == s.itemId) Log.i(TAG, "playback resumed item=${s.itemId}")
      if (!startPlaying()) return
    } else if (!s.playing && before?.playing == true) {
      Log.i(TAG, "playback paused item=${s.itemId} paragraph=${s.sentence?.paragraphIndex} offset=${s.sentence?.start}")
      logStats()
      releasePlayingResources(abandon = !pausedForFocus)
    }
    updateSession(s)
    syncForeground(s)
  }

  /** False when playback could not be granted focus or the foreground, and was paused. */
  private fun startPlaying(): Boolean {
    pausedForFocus = false
    if (!requestFocus()) {
      queue.pause()
      return false
    }
    if (wakeLock == null) {
      // SPIKE-05 measured gaps on USB power without a wake lock; battery and Doze were never
      // measured, so playback holds one while speaking (device:gap measures it).
      wakeLock = getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ReadMe:playback")
        .apply { setReferenceCounted(false); acquire(WAKE_LOCK_MS) }
    }
    if (!noisyRegistered) {
      val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
      if (Build.VERSION.SDK_INT >= 33) registerReceiver(noisy, filter, RECEIVER_NOT_EXPORTED)
      else registerReceiver(noisy, filter)
      noisyRegistered = true
    }
    return true
  }

  private fun releasePlayingResources(abandon: Boolean) {
    wakeLock?.let { if (it.isHeld) it.release() }
    wakeLock = null
    if (noisyRegistered) {
      unregisterReceiver(noisy)
      noisyRegistered = false
    }
    if (abandon) abandonFocus()
  }

  private fun syncForeground(s: PlaybackSnapshot) {
    if (s.itemId == null) return
    if (s.playing) {
      goForeground(s)
    } else {
      // Paused: the notification stays (with Play) but can be swiped away, and the system may
      // stop the service; the position is already saved.
      stopForeground(STOP_FOREGROUND_DETACH)
      getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(s))
    }
  }

  private fun goForeground(s: PlaybackSnapshot) {
    val n = notification(s)
    try {
      if (Build.VERSION.SDK_INT >= 29) {
        startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
      } else {
        startForeground(NOTIFICATION_ID, n)
      }
    } catch (e: IllegalStateException) {
      // Android 12+ refuses a foreground start from the background outside the exemptions
      // (notification action, media button). Stay paused rather than speak unprotected.
      Log.i(TAG, "playback resume refused: ${e.javaClass.simpleName}")
      queue.pause()
    }
  }

  private fun end() {
    waiting = null
    releasePlayingResources(abandon = true)
    session.isActive = false
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  private fun logStats() {
    val st = queue.takeStats()
    val g = st.gaps
    if (g.count == 0 && st.errors == 0) return
    Log.i(TAG, "playback gaps n=${g.count} p50=${g.p50} p95=${g.p95} max=${g.max} stalls=${g.stalls} errors=${st.errors}")
  }

  // --- focus, noisy, session, notification ---

  private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
    when (FocusPolicy.onChange(change, pausedForFocus)) {
      FocusAction.PAUSE -> handle(PlaybackCommands.ACTION_PAUSE)
      FocusAction.PAUSE_TRANSIENT -> if (queue.snapshot().playing) {
        pausedForFocus = true
        queue.pause()
      }
      FocusAction.RESUME -> {
        pausedForFocus = false
        queue.resume()
      }
      FocusAction.NONE -> {}
    }
  }

  private fun requestFocus(): Boolean {
    val result = if (Build.VERSION.SDK_INT >= 26) {
      val req = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(TtsSpeaker.ATTRIBUTES)
        .setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener(focusListener, main)
        .build()
        .also { focusRequest = it }
      audio.requestAudioFocus(req)
    } else {
      @Suppress("DEPRECATION")
      audio.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
    }
    return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
  }

  private fun abandonFocus() {
    if (Build.VERSION.SDK_INT >= 26) {
      focusRequest?.let { audio.abandonAudioFocusRequest(it) }
    } else {
      @Suppress("DEPRECATION")
      audio.abandonAudioFocus(focusListener)
    }
  }

  private val noisy = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
      if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) handle(PlaybackCommands.ACTION_PAUSE)
    }
  }

  private val sessionCallback = object : MediaSession.Callback() {
    override fun onPlay() { handle(PlaybackCommands.ACTION_PLAY) }
    override fun onPause() { handle(PlaybackCommands.ACTION_PAUSE) }
    override fun onSkipToNext() { handle(PlaybackCommands.ACTION_NEXT) }
    override fun onSkipToPrevious() { handle(PlaybackCommands.ACTION_PREVIOUS) }
    override fun onStop() { handle(PlaybackCommands.ACTION_STOP) }
  }

  private fun updateSession(s: PlaybackSnapshot) {
    session.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, title).build())
    session.setPlaybackState(
      PlaybackState.Builder()
        .setActions(
          PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP,
        )
        .setState(
          if (s.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
          PlaybackState.PLAYBACK_POSITION_UNKNOWN,
          if (s.playing) 1f else 0f,
        )
        .build(),
    )
    session.isActive = true
  }

  private fun notification(s: PlaybackSnapshot): Notification {
    val b = if (Build.VERSION.SDK_INT >= 26) {
      Notification.Builder(this, CHANNEL)
    } else {
      @Suppress("DEPRECATION") Notification.Builder(this)
    }
    val toggle = if (s.playing) {
      action(android.R.drawable.ic_media_pause, "Pause", PlaybackCommands.ACTION_PAUSE, 2)
    } else {
      action(android.R.drawable.ic_media_play, "Play", PlaybackCommands.ACTION_PLAY, 2)
    }
    val open = PendingIntent.getActivity(
      this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
    )
    return b.setSmallIcon(android.R.drawable.ic_media_play)
      .setContentTitle(title)
      .setContentText(if (s.playing) "Reading" else "Paused")
      .setContentIntent(open)
      .setOngoing(s.playing)
      .addAction(action(android.R.drawable.ic_media_previous, "Previous", PlaybackCommands.ACTION_PREVIOUS, 1))
      .addAction(toggle)
      .addAction(action(android.R.drawable.ic_media_next, "Next", PlaybackCommands.ACTION_NEXT, 3))
      .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
      .build()
  }

  private fun action(icon: Int, label: String, act: String, code: Int): Notification.Action {
    val i = Intent(this, PlaybackService::class.java).setAction(act)
    val pi = if (Build.VERSION.SDK_INT >= 26) {
      PendingIntent.getForegroundService(this, code, i, PendingIntent.FLAG_IMMUTABLE)
    } else {
      PendingIntent.getService(this, code, i, PendingIntent.FLAG_IMMUTABLE)
    }
    return Notification.Action.Builder(Icon.createWithResource(this, icon), label, pi).build()
  }

  companion object {
    private const val TAG = "ReadMe"
    private const val CHANNEL = "playback"
    private const val NOTIFICATION_ID = 3001
    private const val WAKE_LOCK_MS = 4 * 60 * 60 * 1000L

    /** Called from ReadMeSpeech while the app is in the foreground (the user tapped play). */
    fun start(context: Context, request: PlaybackHub.Request) {
      PlaybackHub.offer(request)
      val i = Intent(context, PlaybackService::class.java).setAction(PlaybackCommands.ACTION_START)
      if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
    }
  }
}
```

- [ ] **Step 7: Declare the service**

In `android/app/src/main/AndroidManifest.xml`, after the `INTERNET` permission:

```xml
    <!-- R-M07, ADR 0005: playback (and in Phase 5 the bridge) runs in one mediaPlayback
         foreground service; the wake lock is held only while speaking. No POST_NOTIFICATIONS:
         a media-session notification is exempt from the Android 13 notification permission. -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />
```

and inside `<application>`, after the `ShareActivity` element:

```xml
      <service
        android:name=".playback.PlaybackService"
        android:exported="false"
        android:foregroundServiceType="mediaPlayback" />
```

- [ ] **Step 8: Build and run every Kotlin test**

Run: `(cd android && ./gradlew testDebugUnitTest) > .superpowers/task4-kotlin.log 2>&1; tail -5 .superpowers/task4-kotlin.log`
Expected: `BUILD SUCCESSFUL`. A compile error in `PlaybackService` (an API name differing on compileSdk 37) is fixed against the SDK, not by removing the behaviour; ledger the change.

Run: `npm run test:scripts` (the manifest test reads `AndroidManifest.xml`).
Expected: all pass. If a manifest test pins the permission list to `INTERNET` only, extend its allow-list with the three permissions above and say why in the test (AGENTS.md: only `INTERNET` plus what the foreground service and media session need).

- [ ] **Step 9: Commit**

```bash
git add android/app/src/main android/app/src/test scripts/tests
git commit -m "feat(playback): PlaybackService with media session, focus and wake lock

A mediaPlayback foreground service (ADR 0005) hosts the queue with its own
TextToSpeech instance and an offline voice. Media session, MediaStyle notification,
audio focus (transient loss resumes on gain), noisy-audio pause, a partial wake lock
while speaking. PlaybackHub carries the sentence list in-process (Binder limit) and
ADR 0004's speaking flag.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: ReadMeSpeech playback methods, the TS facade and the list

**Files:**
- Modify: `src/native/NativeReadMeSpeech.ts`, `K/ReadMeSpeechModule.kt`, `App.tsx`, `__tests__/App.test.tsx`, `scripts/build-release.sh`
- Create: `src/library/playback.ts`
- Test: `__tests__/playback.test.ts`

**Interfaces:**
- Consumes: Task 1 `Store.position`, `Rate.clamp`, `Settings`; Task 4 `PlaybackHub`, `PlaybackService.start`, `PlaybackCommands`.
- Produces (TS): `plan(paragraphs: readonly Paragraph[], cuts: readonly number[], saved: Position | null): Plan | null`; `playItem(id: number): Promise<boolean>`; `toggle(id: number, current: Playback | null): Promise<boolean>`; `pause()`, `resume()`, `next()`, `previous()`, `backParagraph()`: `Promise<boolean>`; `setRate(rate: number): Promise<number>`; `getPlayback(): Promise<Playback>`; `onPlayback(cb: (p: Playback) => void): () => void`; `marker(item: Item, p: Playback | null): string`; `engineProblem(p: Playback | null): boolean`; `type Playback = { itemId: number | null; playing: boolean; sentence: { paragraphIndex: number; start: number; end: number } | null; rate: number; engine: string }`.

- [ ] **Step 1: Write the failing TS test**

`__tests__/playback.test.ts`:

```ts
import type {
  NativePlayback,
  NativeSentence,
} from '../src/native/NativeReadMeSpeech';

const mockPlays: { id: number; title: string; sentences: NativeSentence[]; start: number }[] = [];
const mockState = { resume: true, position: null as null | { paragraphIndex: number; charOffset: number } };

jest.mock('../src/native/NativeReadMeSpeech', () => ({
  __esModule: true,
  default: {
    getItem: jest.fn(async (id: number) =>
      id === 404
        ? null
        : {
            item: {
              id, kind: 'text', url: null, title: 'T', site: null, byline: null, createdAt: 1,
              state: 'ready', failReason: null, openedAt: null, archivedAt: null,
            },
            paragraphs: [
              { kind: 'p', text: 'One here. Two here.' },
              { kind: 'p', text: 'Three here.' },
            ],
            cuts: id === 2 ? [0, 1] : [],
          },
    ),
    getPosition: jest.fn(async () => mockState.position),
    play: jest.fn(async (id: number, title: string, sentences: NativeSentence[], start: number) => {
      mockPlays.push({ id, title, sentences, start });
      return true;
    }),
    pause: jest.fn(async () => true),
    resume: jest.fn(async () => mockState.resume),
    addListener: jest.fn(),
    removeListeners: jest.fn(),
  },
}));

import Native from '../src/native/NativeReadMeSpeech';
import { engineProblem, marker, plan, playItem, toggle, type Playback } from '../src/library/playback';
import type { Item } from '../src/library/library';

const paragraphs = [
  { kind: 'p' as const, text: 'One here. Two here.' },
  { kind: 'p' as const, text: 'Cut away.' },
  { kind: 'p' as const, text: 'Three here.' },
];

beforeEach(() => {
  mockPlays.length = 0;
  mockState.resume = true;
  mockState.position = null;
  jest.clearAllMocks();
});

describe('plan', () => {
  test('no saved position starts at the first kept sentence', () => {
    const p = plan(paragraphs, [1], null)!;
    expect(p.sentences.map(s => s.text)).toEqual(['One here. ', 'Two here.', 'Three here.']);
    expect(p.startIndex).toBe(0);
  });

  test('a saved offset resumes at the start of the sentence containing it', () => {
    expect(plan(paragraphs, [1], { paragraphIndex: 0, charOffset: 13 })!.startIndex).toBe(1);
  });

  test('a position in a paragraph cut since moves to the next kept paragraph', () => {
    expect(plan(paragraphs, [1], { paragraphIndex: 1, charOffset: 4 })!.startIndex).toBe(2);
  });

  test('a position past the end starts over', () => {
    expect(plan(paragraphs, [1, 2], { paragraphIndex: 2, charOffset: 0 })!.startIndex).toBe(0);
  });

  test('everything cut gives nothing to play', () => {
    expect(plan(paragraphs, [0, 1, 2], null)).toBeNull();
  });
});

describe('playItem', () => {
  test('hands the native side the kept sentences and the start', async () => {
    mockState.position = { paragraphIndex: 1, charOffset: 0 };
    expect(await playItem(1)).toBe(true);
    expect(mockPlays).toHaveLength(1);
    expect(mockPlays[0].title).toBe('T');
    expect(mockPlays[0].sentences).toHaveLength(3);
    expect(mockPlays[0].start).toBe(2);
  });

  test('a missing item or an item trimmed to nothing does not play', async () => {
    expect(await playItem(404)).toBe(false);
    expect(await playItem(2)).toBe(false);
    expect(mockPlays).toHaveLength(0);
  });
});

describe('toggle', () => {
  const at = (itemId: number | null, playing: boolean): Playback => ({
    itemId, playing, sentence: null, rate: 2, engine: 'ready',
  });

  test('pauses the item that is playing', async () => {
    await toggle(1, at(1, true));
    expect(Native.pause).toHaveBeenCalled();
    expect(mockPlays).toHaveLength(0);
  });

  test('resumes the paused item', async () => {
    await toggle(1, at(1, false));
    expect(Native.resume).toHaveBeenCalled();
    expect(mockPlays).toHaveLength(0);
  });

  test('plays again from the saved position when the service is gone', async () => {
    mockState.resume = false;
    await toggle(1, at(1, false));
    expect(mockPlays).toHaveLength(1);
  });

  test('plays another item', async () => {
    await toggle(3, at(1, true));
    expect(mockPlays[0].id).toBe(3);
  });
});

test('markers and the engine problem', () => {
  const item = { id: 1, archivedAt: undefined } as Item;
  const playing: Playback = { itemId: 1, playing: true, sentence: null, rate: 2, engine: 'ready' };
  expect(marker(item, playing)).toBe('playing');
  expect(marker(item, { ...playing, playing: false })).toBe('paused');
  expect(marker({ ...item, archivedAt: 5 }, null)).toBe('archived');
  expect(marker(item, null)).toBe('');
  expect(engineProblem({ ...playing, engine: 'no-voice' })).toBe(true);
  expect(engineProblem({ ...playing, engine: 'no-engine' })).toBe(true);
  expect(engineProblem(playing)).toBe(false);
  expect(engineProblem(null)).toBe(false);
});

// Keeps the NativePlayback import used: the facade's event shape is the spec's.
export type _Shape = NativePlayback;
```

- [ ] **Step 2: Run it to verify it fails**

Run: `npx jest __tests__/playback.test.ts`
Expected: FAIL, `Cannot find module '../src/library/playback'` (and TS type errors for `NativeSentence`).

- [ ] **Step 3: Extend the spec**

In `src/native/NativeReadMeSpeech.ts`, after `NativeItemDetail`:

```ts
// One utterance for PlaybackService (R-M07): text is paragraph.text.slice(start, end).
export type NativeSentence = {
  paragraphIndex: number;
  start: number;
  end: number;
  text: string;
};

export type NativePosition = { paragraphIndex: number; charOffset: number };

// The service's state. paragraphIndex is -1 when there is no current sentence. engine is
// 'unknown' until a service has started, then 'pending', 'ready', 'no-engine' or 'no-voice'.
export type NativePlayback = {
  itemId: number | null;
  playing: boolean;
  paragraphIndex: number;
  start: number;
  end: number;
  rate: number;
  engine: string;
};
```

and in `Spec`, before `addListener`:

```ts
  play(
    itemId: number,
    title: string,
    sentences: NativeSentence[],
    startIndex: number,
  ): Promise<boolean>;
  pause(): Promise<boolean>;
  resume(): Promise<boolean>;
  next(): Promise<boolean>;
  previous(): Promise<boolean>;
  backParagraph(): Promise<boolean>;
  setRate(rate: number): Promise<number>;
  getRate(): Promise<number>;
  getPlayback(): Promise<NativePlayback>;
  getPosition(itemId: number): Promise<NativePosition | null>;
```

- [ ] **Step 4: Write the facade**

`src/library/playback.ts`:

```ts
// The JS view of PlaybackService (R-M07, AGENTS.md 11). JS segments the kept text once per
// play and hands over the sentence list and a start; the service then owns the queue,
// position saves and the archive. Nothing here advances playback on an event. Never logs.
import { NativeEventEmitter } from 'react-native';
import Native, { type NativePlayback } from '../native/NativeReadMeSpeech';
import { sentenceIndexAt } from '../segment/locate';
import { segment } from '../segment/segment';
import { remapPosition } from '../trim/cuts';
import type { Paragraph, Position, Sentence } from '../types';
import { getItem, type Item } from './library';

export type Plan = { sentences: Sentence[]; startIndex: number };

export type Playback = {
  itemId: number | null;
  playing: boolean;
  sentence: { paragraphIndex: number; start: number; end: number } | null;
  rate: number;
  engine: string;
};

/**
 * R-M11: resume at the start of the sentence that contains the saved offset under today's
 * segmentation. A position in a paragraph cut since moves to the next kept one; a position
 * past the end starts over. Null when nothing is kept.
 */
export function plan(
  paragraphs: readonly Paragraph[],
  cuts: readonly number[],
  saved: Position | null,
): Plan | null {
  const cutSet = new Set(cuts);
  const sentences = segment(paragraphs, cutSet);
  if (sentences.length === 0) return null;
  const moved = saved && remapPosition(saved, cutSet, paragraphs.length);
  const i = moved ? sentenceIndexAt(sentences, moved) : 0;
  return { sentences, startIndex: i < 0 ? 0 : i };
}

export async function playItem(id: number): Promise<boolean> {
  const detail = await getItem(id);
  if (detail === null) return false;
  const p = plan(detail.paragraphs, detail.cuts, await Native.getPosition(id));
  if (p === null) return false;
  return Native.play(id, detail.item.title, p.sentences, p.startIndex);
}

/** A row tap: pause what plays, resume what is paused, otherwise play this item. */
export async function toggle(id: number, current: Playback | null): Promise<boolean> {
  if (current?.itemId === id) {
    if (current.playing) return Native.pause();
    // False when the service is gone (process restarted): play again from the saved position.
    if (await Native.resume()) return true;
  }
  return playItem(id);
}

export const pause = () => Native.pause();
export const resume = () => Native.resume();
export const next = () => Native.next();
export const previous = () => Native.previous();
export const backParagraph = () => Native.backParagraph();
export const setRate = (rate: number) => Native.setRate(rate);

export function toPlayback(n: NativePlayback): Playback {
  return {
    itemId: n.itemId,
    playing: n.playing,
    sentence:
      n.paragraphIndex < 0
        ? null
        : { paragraphIndex: n.paragraphIndex, start: n.start, end: n.end },
    rate: n.rate,
    engine: n.engine,
  };
}

export async function getPlayback(): Promise<Playback> {
  return toPlayback(await Native.getPlayback());
}

export function onPlayback(cb: (p: Playback) => void): () => void {
  const sub = new NativeEventEmitter(Native).addListener(
    'ReadMePlayback',
    (n: NativePlayback) => cb(toPlayback(n)),
  );
  return () => sub.remove();
}

export function marker(item: Item, p: Playback | null): string {
  if (p?.itemId === item.id) return p.playing ? 'playing' : 'paused';
  return item.archivedAt === undefined ? '' : 'archived';
}

/** R-M06: no engine bound, or no offline voice. */
export function engineProblem(p: Playback | null): boolean {
  return p?.engine === 'no-engine' || p?.engine === 'no-voice';
}
```

- [ ] **Step 5: Run the TS test**

Run: `npx jest __tests__/playback.test.ts`
Expected: PASS, 14 tests. If `plan`'s first case shows different sentence texts (the segmenter's trailing-space rule), copy the segmenter's actual output into the expectation only after checking `src/segment/segment.ts` gives `text === paragraph.text.slice(start, end)`; the start indices are what this test pins.

- [ ] **Step 6: Implement the module methods**

In `K/ReadMeSpeechModule.kt` add imports `io.loopstring.readme.playback.PlaybackCommands`, `io.loopstring.readme.playback.PlaybackHub`, `io.loopstring.readme.playback.PlaybackService`, `io.loopstring.readme.playback.PlaybackSnapshot`, `io.loopstring.readme.playback.SentenceRow`, `io.loopstring.readme.store.Rate`, `io.loopstring.readme.store.Settings`. Add the field and listener:

```kotlin
  private val settings get() = Settings(reactApplicationContext)
  private val onPlayback: (PlaybackSnapshot) -> Unit = {
    reactApplicationContext.emitDeviceEvent(EVENT_PLAYBACK, it.toMap())
  }
```

In `initialize` add `PlaybackHub.addListener(onPlayback)`; in `invalidate` add `PlaybackHub.removeListener(onPlayback)` before `super.invalidate()`.

Replace `deleteItem`:

```kotlin
  // Review Focus 2: stop first, so no onDone saves into a row that is going away.
  override fun deleteItem(id: Double, promise: Promise) = settle(promise) {
    PlaybackHub.queue?.stopItem(id.toLong())
    store.delete(id.toLong())
    null
  }
```

Add, before `addListener`:

```kotlin
  override fun play(itemId: Double, title: String, sentences: ReadableArray, startIndex: Double, promise: Promise) =
    settle(promise) {
      val rows = (0 until sentences.size()).map { i ->
        val s = sentences.getMap(i)!!
        SentenceRow(s.getInt("paragraphIndex"), s.getInt("start"), s.getInt("end"), s.getString("text")!!)
      }
      val start = startIndex.toInt()
      if (start !in rows.indices) return@settle false
      PlaybackService.start(reactApplicationContext, PlaybackHub.Request(itemId.toLong(), title, rows, start))
      true
    }

  override fun pause(promise: Promise) = settle(promise) { PlaybackHub.control(PlaybackCommands.ACTION_PAUSE) }

  override fun resume(promise: Promise) = settle(promise) { PlaybackHub.control(PlaybackCommands.ACTION_PLAY) }

  override fun next(promise: Promise) = settle(promise) { PlaybackHub.control(PlaybackCommands.ACTION_NEXT) }

  override fun previous(promise: Promise) = settle(promise) { PlaybackHub.control(PlaybackCommands.ACTION_PREVIOUS) }

  override fun backParagraph(promise: Promise) =
    settle(promise) { PlaybackHub.control(PlaybackCommands.ACTION_BACK_PARAGRAPH) }

  override fun setRate(rate: Double, promise: Promise) = settle(promise) {
    val r = Rate.clamp(rate.toFloat())
    settings.rate = r
    PlaybackHub.queue?.setRate(r)
    Math.round(r * 10) / 10.0
  }

  override fun getRate(promise: Promise) = settle(promise) { Math.round(settings.rate * 10) / 10.0 }

  override fun getPlayback(promise: Promise) = settle(promise) { PlaybackHub.last.toMap() }

  override fun getPosition(itemId: Double, promise: Promise) = settle(promise) {
    store.position(itemId.toLong())?.let { p ->
      Arguments.createMap().apply {
        putInt("paragraphIndex", p.paragraphIndex)
        putInt("charOffset", p.charOffset)
      }
    }
  }

  // Offsets and ids only; the sentence text stays native (JS already has it).
  private fun PlaybackSnapshot.toMap(): WritableMap = Arguments.createMap().apply {
    val id = itemId
    if (id == null) putNull("itemId") else putDouble("itemId", id.toDouble())
    putBoolean("playing", playing)
    putInt("paragraphIndex", sentence?.paragraphIndex ?: -1)
    putInt("start", sentence?.start ?: 0)
    putInt("end", sentence?.end ?: 0)
    putDouble("rate", Math.round(rate * 10) / 10.0)
    putString("engine", PlaybackHub.engine)
  }
```

and in the companion: `const val EVENT_PLAYBACK = "ReadMePlayback"`.

`settle` is `inline`, so `return@settle false` returns from the lambda as the existing `getItem` does.

- [ ] **Step 7: The list**

Replace `App.tsx` with:

```tsx
// A plain list of items. Tap a row to play it, tap again to pause or resume; the marker
// shows what the service is doing. The real list, Trim and Reader screens are Phase 4.
import React, { useCallback, useEffect, useState } from 'react';
import {
  FlatList,
  Linking,
  Pressable,
  StatusBar,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import {
  SafeAreaProvider,
  useSafeAreaInsets,
} from 'react-native-safe-area-context';
import {
  drainFetched,
  listItems,
  onItemsChanged,
  type Item,
} from './src/library/library';
import {
  engineProblem,
  getPlayback,
  marker,
  onPlayback,
  toggle,
  type Playback,
} from './src/library/playback';

function Library() {
  const insets = useSafeAreaInsets();
  const [items, setItems] = useState<Item[]>([]);
  const [playback, setPlayback] = useState<Playback | null>(null);

  const refresh = useCallback(() => {
    listItems().then(setItems, () => setItems([]));
  }, []);

  useEffect(() => {
    refresh();
    drainFetched().catch(() => undefined);
    return onItemsChanged(() => {
      refresh();
      drainFetched().catch(() => undefined);
    });
  }, [refresh]);

  useEffect(() => {
    getPlayback().then(setPlayback, () => undefined);
    return onPlayback(setPlayback);
  }, []);

  const onPress = useCallback(
    (id: number) => {
      toggle(id, playback).catch(() => undefined);
    },
    [playback],
  );

  return (
    <View style={[styles.root, { paddingTop: insets.top }]}>
      {engineProblem(playback) ? (
        <View style={styles.problem}>
          <Text>
            No offline text-to-speech voice is available, so Read Me cannot read aloud.
          </Text>
          <Pressable
            onPress={() =>
              Linking.sendIntent('com.android.settings.TTS_SETTINGS').catch(
                () => undefined,
              )
            }>
            <Text style={styles.link}>Open text-to-speech settings</Text>
          </Pressable>
        </View>
      ) : null}
      <FlatList
        data={items}
        keyExtractor={item => String(item.id)}
        ListEmptyComponent={<Text style={styles.empty}>Share a link or text to Read Me.</Text>}
        renderItem={({ item }) => {
          const mark = marker(item, playback);
          return (
            <Pressable style={styles.row} onPress={() => onPress(item.id)}>
              <Text style={styles.title}>{item.title}</Text>
              <Text style={styles.state}>
                {item.state}
                {item.failReason ? `: ${item.failReason}` : ''}
              </Text>
              {mark ? <Text style={styles.state}>{mark}</Text> : null}
            </Pressable>
          );
        }}
      />
    </View>
  );
}

export default function App() {
  return (
    <SafeAreaProvider>
      <StatusBar barStyle="default" />
      <Library />
    </SafeAreaProvider>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1 },
  empty: { padding: 24 },
  problem: { padding: 16, gap: 8 },
  link: { textDecorationLine: 'underline' },
  row: { paddingHorizontal: 16, paddingVertical: 12 },
  title: { fontSize: 16 },
  state: { fontSize: 13, opacity: 0.7 },
});
```

The state stays on its own `Text`, so `device:intake`'s `text="ready"` match still holds.

In `__tests__/App.test.tsx`, add to the mock's `default` object:

```ts
    getPlayback: jest.fn(async () => ({
      itemId: null, playing: false, paragraphIndex: -1, start: 0, end: 0, rate: 2, engine: 'unknown',
    })),
```

- [ ] **Step 8: Make the release build catch a stale native cache and a missing method**

In `scripts/build-release.sh`, replace the line `( cd android && ./gradlew --quiet assembleRelease )` with:

```bash
# A spec change needs a fresh codegen; a CMake cache from before it silently keeps the old one
# (the 964fef0 trap). The stamp records which spec the cache was built from.
SPEC_TS=src/native/NativeReadMeSpeech.ts
CXX_STAMP=android/app/.cxx/.readme-spec-stamp
if [ -d android/app/.cxx ] && { [ ! -f "$CXX_STAMP" ] || [ "$SPEC_TS" -nt "$CXX_STAMP" ]; }; then
  rm -rf android/app/.cxx android/app/build/intermediates/cxx
  echo "the TurboModule spec changed since the native build cache was made; cleared it"
fi
( cd android && ./gradlew --quiet assembleRelease )
mkdir -p android/app/.cxx && touch "$CXX_STAMP"
```

and replace the `SPEC=... fi` codegen block with:

```bash
SPEC=$(node -e "console.log((require('./package.json').codegenConfig||{}).name||'')")
if [ -n "$SPEC" ]; then
  SO=$(mktemp)
  unzip -p "$APK" lib/arm64-v8a/libappmodules.so > "$SO"
  missing=""
  # Every method of the spec interface must be compiled in, not only the module name: a cache
  # that predates one method leaves the app crashing when JS first calls it.
  for m in "$SPEC" $(node -e "
    const s=require('fs').readFileSync('$SPEC_TS','utf8');
    const body=s.slice(s.indexOf('interface Spec'));
    console.log([...body.matchAll(/^  (\w+)\(/gm)].map(x=>x[1]).join(' '))"); do
    [ "$(grep -ac "$m" "$SO" || true)" = 0 ] && missing="$missing $m"
  done
  rm -f "$SO"
  if [ -n "$missing" ]; then
    rm -f "$APK"
    echo "refused: libappmodules.so lacks the app codegen for:$missing; the native build cache is" >&2
    echo "stale. Run: rm -rf android/app/.cxx android/app/build/intermediates/cxx, then rebuild" >&2
    exit 1
  fi
fi
```

- [ ] **Step 9: Run every JS gate and the release build**

Run: `npm run typecheck && npm run lint && npm test > .superpowers/task5-jest.log 2>&1; echo "jest exit $?"; tail -6 .superpowers/task5-jest.log`
Expected: typecheck and lint clean; `jest exit 0`; all suites pass, including `networkGuard` (the new files contain no banned word) and `App.test`.

Run: `(cd android && ./gradlew testDebugUnitTest) > .superpowers/task5-kotlin.log 2>&1; echo "gradle exit $?"; tail -3 .superpowers/task5-kotlin.log`
Expected: `gradle exit 0`.

Run: `npm run build:release`
Expected: first line `the TurboModule spec changed since the native build cache was made; cleared it` (no stamp exists yet), then `built <hash> (dirty)`. No `refused:` line.

- [ ] **Step 10: Commit**

```bash
git add src App.tsx __tests__ android/app/src/main/java/io/loopstring/readme/ReadMeSpeechModule.kt scripts/build-release.sh
git commit -m "feat(playback): play, pause and resume from the list through ReadMeSpeech

JS segments the kept text, maps the saved offset to a start sentence (R-M11) and hands
the list to PlaybackService once; after that it only listens. Deleting an item stops
its playback first. The release build clears a native cache older than the spec and
checks every spec method is compiled in.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: On the phone: device:playback, device:gap, and the docs

**Files:**
- Create: `scripts/device-playback-e2e.sh`, `scripts/device-gap.sh`
- Modify: `package.json` (scripts), `AGENTS.md`, `CONTEXT.md`, `srs.md`

**Interfaces:**
- Consumes: the Task 4 log lines; `scripts/lib/device.sh` (`device_take`, `device_require_unlocked`, `device_install_release`, `device_has`, `device_crash_seen`, `$PKG`).

- [ ] **Step 1: Write device:playback**

`scripts/device-playback-e2e.sh`:

```bash
#!/usr/bin/env bash
# npm run device:playback - R-M07/R-M11 on the phone, as a user would. Clears Read Me's data,
# shares a text, taps it to play, pauses and resumes by tapping, then turns the screen off,
# pauses and resumes with media-button presses, and waits for the item to finish. Checks the
# service's log lines, that reading advanced with the screen off, that the item archived, and
# that no log line carries the text (AGENTS.md 1). Leaves the screen off: unlock it after.
set -euo pipefail
cd "$(dirname "$0")/.."
. scripts/lib/device.sh
device_take interactive
device_require_unlocked
device_install_release

MARK='Quietly the heron waited'
text="$MARK by the water while the morning went on without it."
for i in $(seq 2 9); do
  text+="

Paragraph $i begins here. It has a second sentence for the queue. A third one keeps the reader busy. The fourth closes paragraph $i."
done

adb shell pm clear "$PKG" >/dev/null
adb logcat -c
# The text goes on stdin, not the adb argv, which adbd logs (AGENTS.md, SPIKE-01).
printf '%s\n' "am start -W -n $PKG/.ShareActivity -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT $(printf '%q' "$text")" \
  | adb shell >/dev/null
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null

ui() {
  adb shell uiautomator dump /sdcard/readme-ui.xml >/dev/null 2>&1 || true
  adb shell cat /sdcard/readme-ui.xml 2>/dev/null || true
}
# Taps the centre of the first node whose text matches $1 (an ERE).
tap() {
  local b
  b=$(ui | grep -oE "text=\"$1\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" | head -1 \
    | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' | grep -oE '[0-9]+' | tr '\n' ' ' || true)
  [ -n "$b" ] || { echo "FAIL: nothing on screen matches $1"; exit 1; }
  set -- $b
  adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
}
logs() { adb logcat -d -s ReadMe:I; }
# wait_count <ERE> <count> <seconds>
wait_count() {
  for _ in $(seq "$3"); do
    [ "$(logs | grep -cE "$1" || true)" -ge "$2" ] && return 0
    sleep 1
  done
  echo "FAIL: waited $3 s for $2 x '$1'"; return 1
}
on_screen() {
  for _ in $(seq 10); do device_has "$(ui)" "$1" && return 0; sleep 1; done
  echo "FAIL: '$1' not on screen"; return 1
}

fail=0
on_screen 'text="ready"' || fail=1
tap 'ready'
wait_count 'playback start item=1 ' 1 20 || fail=1
on_screen 'text="playing"' || fail=1
sleep 4
tap 'ready'
wait_count 'playback paused item=1 ' 1 10 || fail=1
on_screen 'text="paused"' || fail=1
tap 'ready'
wait_count 'playback resumed item=1' 1 10 || fail=1

# Screen off, then media-button controls (lock screen / headset path, R-M07).
adb shell input keyevent KEYCODE_SLEEP
sleep 8
adb shell cmd media_session dispatch pause
wait_count 'playback paused item=1 ' 2 10 || fail=1
first=$(logs | grep -oE 'playback paused item=1 paragraph=[0-9]+ offset=[0-9]+' | sed -n 1p)
second=$(logs | grep -oE 'playback paused item=1 paragraph=[0-9]+ offset=[0-9]+' | sed -n 2p)
key() { sed -E 's/.*paragraph=([0-9]+) offset=([0-9]+)/\1 \2/' <<<"$1"; }
read -r p1 o1 <<<"$(key "$first")"; read -r p2 o2 <<<"$(key "$second")"
if [ "${p2:-0}" -lt "${p1:-0}" ] || { [ "${p2:-0}" -eq "${p1:-0}" ] && [ "${o2:-0}" -le "${o1:-0}" ]; }; then
  echo "FAIL: reading did not advance with the screen off ($first -> $second)"; fail=1
fi
adb shell cmd media_session dispatch play
wait_count 'playback resumed item=1' 2 10 || fail=1
wait_count 'playback finished item=1' 1 180 || fail=1
wait_count 'playback gaps n=' 1 5 || fail=1
logs | grep -E 'playback (start|paused|resumed|gaps|finished|resume refused|engine)' | sed 's/^/  /'

all=$(adb logcat -d)
if device_has "$all" "$MARK|heron|Paragraph [0-9] begins"; then
  echo "FAIL: a log line carries the shared text"; fail=1
fi
pid=$(adb shell pidof "$PKG" 2>/dev/null | tr -d '\r' || true)
if device_crash_seen "${pid%% *}" <<<"$(adb logcat -d -b crash,main)"; then
  echo "FAIL: crash logged"; fail=1
fi
adb shell rm -f /sdcard/readme-ui.xml
echo "the screen is off: unlock the phone to use it"
echo "device:playback $([ $fail -eq 0 ] && echo PASS || echo FAIL)"
exit $fail
```

Archive on screen is not checked here (the phone is locked by then); `playback finished` is logged by `PlaybackService.finished` right after `Store.finishReading`, and `StorePositionTest` pins what that writes.

- [ ] **Step 2: Write device:gap**

`scripts/device-gap.sh`:

```bash
#!/usr/bin/env bash
# npm run device:gap - R-M07's gap target on the reference device: 10 minutes at 2.0x on
# (simulated) battery in forced Doze with the screen off. Reads the service's own
# "playback gaps" line (onDone(n) to onStart(n+1), measured in PlaybackService). Shares a long
# public-domain text (Pride and Prejudice, the gutenberg-1342 fixture). Resets battery and
# Doze state on exit. Takes about 12 minutes.
set -euo pipefail
cd "$(dirname "$0")/.."
. scripts/lib/device.sh
device_take gap
device_require_unlocked
device_install_release
MINUTES=${GAP_MINUTES:-10}

TXT=$(mktemp)
node -e '
  const html = require("fs").readFileSync("__tests__/fixtures/pages/gutenberg-1342.html", "utf8");
  const ents = { amp: "&", lt: "<", gt: ">", quot: "\"", "#39": "\x27", nbsp: " " };
  const paras = [...html.matchAll(/<p[^>]*>([\s\S]*?)<\/p>/g)]
    .map(m => m[1].replace(/<[^>]+>/g, "").replace(/&(\w+|#\d+);/g, (x, e) => ents[e] ?? " ")
      .replace(/\s+/g, " ").trim())
    .filter(p => p.length > 40);
  let out = "Gap run\n\n", i = 0;
  while (out.length < 60000 && i < paras.length) out += paras[i++] + "\n\n";
  process.stdout.write(out);
' > "$TXT"
adb push "$TXT" /data/local/tmp/readme-gap.txt >/dev/null
rm -f "$TXT"

restore() {
  adb shell dumpsys deviceidle unforce >/dev/null 2>&1 || true
  adb shell dumpsys battery reset >/dev/null 2>&1 || true
  adb shell rm -f /data/local/tmp/readme-gap.txt /sdcard/readme-ui.xml >/dev/null 2>&1 || true
}
DEVICE_ON_EXIT=restore

adb shell pm clear "$PKG" >/dev/null
adb logcat -c
# The device's shell expands the file into the extra; nothing long rides the adb argv.
echo "am start -W -n $PKG/.ShareActivity -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT \"\$(cat /data/local/tmp/readme-gap.txt)\"" \
  | adb shell >/dev/null
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
sleep 3
adb shell uiautomator dump /sdcard/readme-ui.xml >/dev/null 2>&1 || true
b=$(adb shell cat /sdcard/readme-ui.xml | grep -oE 'text="ready"[^>]*bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' \
  | head -1 | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' | grep -oE '[0-9]+' | tr '\n' ' ' || true)
[ -n "$b" ] || { echo "FAIL: the shared text is not on screen as ready"; exit 1; }
set -- $b
adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
for _ in $(seq 20); do adb logcat -d -s ReadMe:I | grep -q 'playback start item=1 ' && break; sleep 1; done
adb logcat -d -s ReadMe:I | grep -q 'playback start item=1 ' || { echo "FAIL: playback did not start"; exit 1; }

adb shell dumpsys battery unplug
adb shell input keyevent KEYCODE_SLEEP
sleep 2
adb shell dumpsys deviceidle force-idle | sed 's/^/  deviceidle: /'
echo "playing for $MINUTES min on battery, screen off, forced Doze ($(date +%T))"
sleep $(( MINUTES * 60 ))
restore
adb shell cmd media_session dispatch pause
for _ in $(seq 10); do adb logcat -d -s ReadMe:I | grep -q 'playback gaps n=' && break; sleep 1; done
line=$(adb logcat -d -s ReadMe:I | grep -oE 'playback gaps n=.*' | tail -1 || true)
echo "$line"
[ -n "$line" ] || { echo "device:gap FAIL: no gaps line"; exit 1; }
num() { grep -oE "$1=[0-9]+" <<<"$line" | cut -d= -f2; }
fail=0
[ "$(num n)" -ge $(( MINUTES * 5 )) ] || { echo "FAIL: only $(num n) gaps in $MINUTES min"; fail=1; }
[ "$(num p95)" -le 300 ] || { echo "FAIL: p95 $(num p95) ms > 300"; fail=1; }
[ "$(num max)" -le 1000 ] || { echo "FAIL: max $(num max) ms > 1000"; fail=1; }
[ "$(num stalls)" -eq 0 ] || { echo "FAIL: $(num stalls) stalls"; fail=1; }
pid=$(adb shell pidof "$PKG" 2>/dev/null | tr -d '\r' || true)
if device_crash_seen "${pid%% *}" <<<"$(adb logcat -d -b crash,main)"; then
  echo "FAIL: crash logged"; fail=1
fi
echo "the screen is off: unlock the phone to use it"
echo "device:gap $([ $fail -eq 0 ] && echo PASS || echo FAIL)"
exit $fail
```

Check `DEVICE_ON_EXIT` is honoured by `_device_exit` in `scripts/lib/device.sh` (read lines 64-74) before relying on it; if it is not, add `trap 'restore; _device_exit' EXIT` after `device_take` instead and ledger it.

- [ ] **Step 3: Register both and syntax-check**

In `package.json` `scripts`, after `device:intake`:

```json
    "device:playback": "bash scripts/device-playback-e2e.sh",
    "device:gap": "bash scripts/device-gap.sh"
```

Run: `bash -n scripts/device-playback-e2e.sh && bash -n scripts/device-gap.sh && npm run test:scripts`
Expected: no output from `bash -n`; test:scripts all pass.

- [ ] **Step 4: Commit the scripts, then build what the phone will run**

```bash
git add scripts/device-playback-e2e.sh scripts/device-gap.sh package.json
git commit -m "test(playback): device:playback and device:gap

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
npm run build:release
```

Expected: `built <HEAD hash> (clean)`.

- [ ] **Step 5: Run device:playback on the phone**

Run: `npm run device:playback` (needs the phone unlocked with the screen on; exit 5 means ask the owner to unlock it).
Expected: the summary lists `playback start item=1 sentences=<n> from=0 rate=2.0` (n about 33), two `paused` lines with the second further on, two `resumed`, a `gaps` line, `finished item=1`; then `device:playback PASS`. Listen once if the owner is near: speech at 2x, not 4x.

A failure here is a bug: reproduce it with the script, find the cause in the service, add a unit test where the rule is unit-testable, fix, rebuild, rerun.

- [ ] **Step 6: Run device:gap on the phone**

Run: `npm run device:gap` in the background (about 12 minutes), then read its output.
Expected: a line `playback gaps n=<n> p50=<ms> p95=<ms> max=<ms> stalls=0 errors=0` with p95 at most 300 and max at most 1000, then `device:gap PASS`. Record the exact line; it is the R-M07 measurement.

If it fails on p95 or max: the measurement stands as measured. Do not tune the threshold; report the number, and the owner decides between an srs amendment and more work.

- [ ] **Step 7: Run the earlier device gates against this build**

Run: `npm run device:intake` (needs the phone unlocked again).
Expected: `device:intake PASS`; the list layout change kept `text="ready"` matching.

- [ ] **Step 8: Docs**

`AGENTS.md`:
- Quality gates block, after the `device:intake` line:

```bash
npm run device:playback   # shares a text, plays it, pauses/resumes by tap and media button with the screen off, waits for the archive; checks logs for text (clears app data)
npm run device:gap        # R-M07 gap target: 10 min at 2x on battery, forced Doze, screen off (about 12 min)
```

- Known state: after the Phase 2 bullet add, with the numbers from Steps 5 and 6:

```markdown
- **Playback (Phase 3, REA-17):** `PlaybackService` (`mediaPlayback` FGS, ADR 0005) owns a
  sentence queue on its own `TextToSpeech` with an offline voice; media session,
  notification, audio focus, noisy pause, partial wake lock while speaking; position saved
  per sentence, archive at the end. Verified by `npm run device:playback` (<date>, build
  <hash>) and `npm run device:gap` (<date>, build <hash>: n=<n> p50=<ms> p95=<ms> max=<ms>
  stalls=0, 10 min at 2x, battery, forced Doze, screen off). No reader, trim screen or
  bridge yet.
```

- Change the Phase 2 bullet's last line from `No playback, reader, trim screen or bridge yet.` to nothing (the Phase 3 bullet now says what is missing).
- In the SPIKE-05 bullet, replace `(battery and Doze untested; Phase 3 measures)` with `(battery and Doze: see Playback above)`.

`CONTEXT.md` source layout: add `K/playback/` (`PlaybackService`, `PlaybackQueue`, `TtsSpeaker`, `PlaybackHub`, `Policies`, `Utterances`, `GapStats`), `store/Settings.kt`, `src/library/playback.ts`, `scripts/device-playback-e2e.sh`, `scripts/device-gap.sh`; remove `PlaybackService` from "Planned:".

`srs.md`, under R-M07's performance target, append one line with the measured result:

```markdown
**Measured (Phase 3, <date>, reference device, build <hash>):** n=<n>, p50 <ms> ms, p95 <ms>
ms, max <ms> ms, no stall, over 10 minutes at 2.0x on battery (`dumpsys battery unplug`) in
forced Doze with the screen off and a partial wake lock held (`npm run device:gap`).
```

and under SPIKE-05's answer, append: `Battery and Doze: measured in Phase 3 (R-M07, "Measured").`

Commit:

```bash
git add AGENTS.md CONTEXT.md srs.md
git commit -m "docs: Phase 3 playback known state, gates and the measured R-M07 gaps

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Self-review

**Spec coverage.**
- R-M06: offline voice only (Task 3 `VoicePicker`, Task 4 `TtsSpeaker.chooseVoice`); no engine / no voice state with a settings link (Task 4 `onReady`, Task 5 `engineProblem` + `App.tsx`); `TTS_SERVICE` query already in the manifest.
- R-M07: JS hands the list and a start (Task 5 `playItem`); QUEUE_ADD 3 ahead (Task 2); own instance (Task 4); controls play/pause/next/previous/back paragraph/rate (Task 2 queue, Task 4 `handle`, Task 5 spec); rate once (Task 2 `aRateChangeWhilePausedDoesNotSpeak` asserts the rate list); sentence-start events (Task 2 `everySentenceStartIsPublished`, Task 5 `ReadMePlayback`) and reattach (`getPlayback`); screen-off FGS + media session + notification + headset (Task 4, checked Task 6 Step 5); focus and noisy (Task 3, Task 4); gap target (Task 6 Step 6). The highlighted current sentence in a Reader is Phase 4 (no Reader screen exists).
- R-M11: per-sentence and pause saves (Task 2), resume at the containing sentence (Task 5 `plan`), no index persisted (only offsets reach `Store`), archive at the end with state kept (Task 1 `finishReading`).
- srs "Playback": pause = stop + remember, resume re-queues, rate change flushes and refills (Task 2).
- ADR 0004: `PlaybackHub.speaking` (Task 4 test). ADR 0005: `mediaPlayback` type (Task 4 manifest + `goForeground`).

**Placeholders.** The only angle-bracket fields are the measured numbers, dates and hashes in Task 6 Step 8, which come from Steps 5 and 6; they cannot be known before the run.

**Type consistency.** `SentenceRow`, `PlaybackSnapshot`, `Speaker`, `PlaybackSink` (Task 2) are used unchanged in Tasks 4 and 5; `PlaybackHub.Request(itemId, title, sentences, startIndex)` matches `PlaybackService.start` and the module; `PlaybackCommands.ACTION_*` names match between Tasks 3, 4 and 5; TS `NativePlayback` fields match `PlaybackSnapshot.toMap`; `Playback.engine` wire values match `EngineStatus.wire` plus `"unknown"`.

**Review Focus.** Each of the five has a named test in its owning task; the over-long sentence has `UtterancesTest`.

**Known gaps, stated.** The noisy-audio pause and focus loss to a phone call are not driven on the device (the shell cannot send the protected `AUDIO_BECOMING_NOISY` broadcast or place a call); they rest on `FocusPolicyTest` and code reading. The POST_NOTIFICATIONS exemption for media-session notifications is from the platform documentation, not verified on the device; Task 6 Step 5's media-button checks pass without it either way.
