package io.loopstring.readme.playback

/**
 * One utterance: `text` is exactly the paragraph's text from `start` to `end`. It carries item
 * text, so it is never logged or put in a log-bound message (AGENTS.md 1).
 */
data class SentenceRow(val paragraphIndex: Int, val start: Int, val end: Int, val text: String)

/**
 * What the service is doing now; `sentence` is the current (or paused-at) sentence.
 * `waitingItemId` is the item of a play request held while the engine starts (REA-35): the
 * queue has no item yet, but a cut or a stop must still reach that request.
 */
data class PlaybackSnapshot(
  val itemId: Long?,
  val playing: Boolean,
  val sentence: SentenceRow?,
  val rate: Float,
  val waitingItemId: Long? = null,
)

/** R-S05: an item continuous play reads next, segmented in Kotlin (ADR 0011). Carries item text. */
data class NextItem(val itemId: Long, val sentences: List<SentenceRow>, val startIndex: Int)

/**
 * The engine side of the queue (REA-31, ADR 0012): playback's own TextToSpeech writes each
 * sentence to a file; Read Me's player plays it. Answers arrive as PlaybackQueue.onSynthDone
 * and onSynthError.
 */
interface SentenceSynth {
  /** False when the engine refused the request: it is not bound, or its process died. */
  fun synthesize(id: String, text: String, file: java.io.File): Boolean
  /** Cancels every request in flight. */
  fun stop()
  fun setRate(rate: Float)
  /** Whether the engine's binding answers. A binder call: never under the queue's lock. */
  fun alive(): Boolean
}

/**
 * Where the queue's effects go. Called with the queue's lock held, on whichever thread drove
 * the queue: an implementation must not call back into the queue, and posts UI work.
 */
interface PlaybackSink {
  fun savePosition(itemId: Long, paragraphIndex: Int, charOffset: Int)
  fun finished(itemId: Long)

  /**
   * R-S05 continuous play, asked only after [finished] returned: the next item to read, never
   * one in [skip] (stopped for deletion), or null to stop as before. Null by default, and
   * whenever the setting is off. The one call made WITHOUT the queue's lock (it reads the Store
   * and segments a whole item): the queue may drop the result if a pause, stop, play or
   * deletion came first, so an implementation must not act on it beyond building it.
   */
  fun nextItem(finishedId: Long, skip: Set<Long>): NextItem? = null

  /** R-S05: the next item's first sentence started [ms] after the last one ended (a log line). */
  fun handedOver(fromId: Long, toId: Long, ms: Long) {}
  fun changed(snapshot: PlaybackSnapshot)
  /**
   * The engine refused work or died while the queue played; the queue has paused. An engine that
   * died while paused is reported at the next start instead (REA-51), never by itself.
   */
  fun engineLost()
  /** Milliseconds from a play request to the first frame heard (REA-31, logged). */
  fun firstAudio(itemId: Long, ms: Long) {}
  /** A gap over GapStats.STALL_MS and where it fell: ids, offsets, counts and durations only. */
  fun stall(detail: String) {}
}
