package io.loopstring.readme.playback

import io.loopstring.readme.store.Rate

data class QueueStats(val gaps: GapSummary, val errors: Int, val saveErrors: Int)

/**
 * R-M07 and srs.md "Playback": the sentence queue the native service owns (AGENTS.md 11).
 * Utterance ids are "<generation>:<index>". Every flush (pause, jump, rate change, new item)
 * starts a new generation, so a callback the engine delivers late for discarded work is
 * ignored. Gaps (onDone(n) to onStart(n+1)) are measured only across continuous play.
 */
class PlaybackQueue(
  private var speaker: Speaker,
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
  private var saveErrors = 0
  // Reset only by onDone: an engine may report onStart and then onError for the same utterance.
  private var consecutiveErrors = 0
  private var firstErrorIndex = -1
  private var stallTicks = 0
  // Items stopped for deletion. Ids are AUTOINCREMENT (Store.kt), never reused.
  private val deleted = HashSet<Long>()
  // R-S05: set at a continuous-play handover until the next item's first sentence starts.
  private var handoverFrom = -1L
  private var handoverAt = -1L
  // R-S05: non-zero while the sink builds the next item outside the lock (ADR 0011). Any
  // pause, stop, play or deletion meanwhile clears it, and the built item is then dropped.
  private var handover = 0L
  private var handoverSeq = 0L

  private class Pending(val token: Long, val finishedId: Long, val skip: Set<Long>)

  fun load(itemId: Long, sentences: List<SentenceRow>, startIndex: Int, rate: Float): Boolean =
    synchronized(lock) {
      if (itemId in deleted || startIndex !in sentences.indices) return false
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
    if (handover != 0L) {
      // ADR 0011: the finished item is archived and the next has not started, so a pause here
      // ends the chain like an item end with the switch off; no position goes into either.
      clearLocked()
      return true
    }
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
    if (itemId == null || handover != 0L || current + 1 >= rows.size) return false
    jumpLocked(current + 1)
    true
  }

  fun previous(): Boolean = synchronized(lock) {
    if (itemId == null || handover != 0L) return false
    jumpLocked(maxOf(current - 1, 0))
    true
  }

  /** To the first sentence of the previous paragraph; at the first paragraph, to its start. */
  fun backParagraph(): Boolean = synchronized(lock) {
    if (itemId == null || handover != 0L) return false
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
    // REA-35: with no item an empty snapshot reads as "the item ended", and the service dropped
    // a play request waiting for the engine. load() takes the rate from Settings.
    // During a handover the next item starts at this rate; the finished one is not restarted.
    if (itemId == null || handover != 0L) return
    if (playing) restartLocked(current) else publishLocked()
  }

  fun stop() = synchronized(lock) {
    if (itemId == null) return
    flushLocked()
    saveLocked()
    clearLocked()
  }

  /** The item is being deleted: stop without saving into it, and never load it again. */
  fun stopItem(id: Long) = synchronized(lock) {
    deleted += id
    if (itemId != id) return
    flushLocked()
    clearLocked()
  }

  fun onStart(id: String) = synchronized(lock) {
    val i = indexOf(id) ?: return
    stallTicks = 0
    if (lastDoneAt >= 0) gaps += clock() - lastDoneAt
    lastDoneAt = -1
    current = i
    if (handoverAt >= 0) {
      sink.handedOver(handoverFrom, itemId!!, clock() - handoverAt)
      handoverAt = -1
    }
    publishLocked()
  }

  fun onDone(id: String) {
    val pending = synchronized(lock) {
      val i = indexOf(id) ?: return
      stallTicks = 0
      consecutiveErrors = 0
      lastDoneAt = clock()
      advanceLocked(i)
    }
    if (pending != null) handOver(pending)
  }

  fun onError(id: String) {
    val pending = synchronized(lock) {
      val i = indexOf(id) ?: return
      stallTicks = 0
      errors++
      lastDoneAt = -1
      if (consecutiveErrors == 0) firstErrorIndex = i
      consecutiveErrors++
      if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
        pauseAtLocked(firstErrorIndex)
        return
      }
      advanceLocked(i)
    }
    if (pending != null) handOver(pending)
  }

  /** Stops at [index] and saves it, so the user resumes where the engine started failing. */
  private fun pauseAtLocked(index: Int) {
    flushLocked()
    playing = false
    current = index
    consecutiveErrors = 0
    saveLocked()
    publishLocked()
  }

  fun snapshot(): PlaybackSnapshot = synchronized(lock) { snapshotLocked() }

  fun takeStats(): QueueStats = synchronized(lock) {
    val s = QueueStats(GapStats.summarize(gaps), errors, saveErrors)
    gaps.clear()
    errors = 0
    saveErrors = 0
    s
  }

  /** Non-null when the item was read to the end and archived: the caller runs [handOver]. */
  private fun advanceLocked(i: Int): Pending? {
    val id = itemId ?: return null
    val next = i + 1
    if (next >= rows.size) {
      if (consecutiveErrors > 0) {
        // The last sentences failed: that is not reading to the end (R-M11 archives only then).
        pauseAtLocked(firstErrorIndex)
        return null
      }
      flushLocked()
      // A failed archive must still clear the queue, or the session shows "Reading" forever.
      // It also ends a continuous-play chain: an item that did not archive is not "read".
      try {
        sink.finished(id)
      } catch (e: RuntimeException) {
        saveErrors++
        clearLocked()
        return null
      }
      // Still "playing" the finished item, with nothing queued, until handOver decides: no
      // snapshot is published here, so the service keeps focus, the session and the foreground.
      handover = ++handoverSeq
      return Pending(handover, id, HashSet(deleted))
    }
    current = next
    saveRowLocked(id, rows[next])
    topUpLocked()
    return null
  }

  /**
   * R-S05, ADR 0011: archive-then-load in one step. No itemId=null snapshot is published
   * between the two items, so the service keeps focus, the wake lock, the session and the
   * foreground (Android 12+ refuses a new foreground start from the background). The rate is
   * the queue's own and is not applied again (AGENTS.md 9).
   */
  private fun handOver(p: Pending) {
    // Store reads and whole-item segmentation run outside the lock, so the main thread (stall
    // checks, session and notification controls, JS's getPlayback) never waits on them.
    // Throwable, not RuntimeException: an Error escaping here (StackOverflowError or OOM while
    // segmenting a big item) left the queue playing silently with the wake lock held (REA-46).
    val built = try {
      sink.nextItem(p.finishedId, p.skip)
        ?.takeIf { it.startIndex in it.sentences.indices }
        ?.let { it to Utterances.fit(it.sentences, it.startIndex, maxChars) }
    } catch (t: Throwable) {
      null
    }
    val next = built?.first
    val fitted = built?.second
    synchronized(lock) {
      // A pause, stop, play or deletion of the finished item took over meanwhile.
      if (handover != p.token) return
      handover = 0L
      if (next == null || fitted == null || next.itemId in deleted) {
        clearLocked()
        return
      }
      itemId = next.itemId
      rows = fitted.rows
      restartLocked(fitted.startIndex)
      // After the restart: its flush clears a handover time in progress.
      handoverFrom = p.finishedId
      handoverAt = clock()
    }
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
    // A user play during a handover wins over it (ADR 0011).
    handover = 0L
    flushLocked()
    stallTicks = 0
    // A new generation: errors before a jump or another item are not this run's.
    consecutiveErrors = 0
    current = index
    queuedUntil = index
    playing = true
    topUpLocked()
    publishLocked()
  }

  private fun flushLocked() {
    // A pause, jump or stop before the next item's first sentence: no handover time to report.
    handoverAt = -1
    generation++
    speaker.stop()
    lastDoneAt = -1
  }

  private fun topUpLocked() {
    while (playing && queuedUntil < rows.size && queuedUntil - current < AHEAD) {
      if (!speaker.speak("$generation:$queuedUntil", rows[queuedUntil].text)) {
        lostLocked()
        return
      }
      queuedUntil++
    }
  }

  /** Called every few seconds by the service while playing. True when the engine is lost. */
  fun checkStall(): Boolean = synchronized(lock) {
    // Nothing is queued during a handover by design: that is not a lost engine.
    if (!playing || handover != 0L || speaker.isSpeaking()) {
      stallTicks = 0
      return false
    }
    if (++stallTicks < STALL_TICKS) return false
    lostLocked()
    true
  }

  /** A rebound engine (PlaybackService). Paused stays paused; the rate is applied once more. */
  fun swapSpeaker(s: Speaker) = synchronized(lock) {
    speaker = s
    s.setRate(rate)
  }

  private fun lostLocked() {
    flushLocked()
    playing = false
    stallTicks = 0
    saveLocked()
    publishLocked()
    sink.engineLost()
  }

  private fun saveLocked() {
    // During a handover the item is archived; a position written now would bring it back.
    if (handover != 0L) return
    val id = itemId ?: return
    val r = rows.getOrNull(current) ?: return
    saveRowLocked(id, r)
  }

  /** R-M11 asks for a save per sentence; a failed save must not stop the reading. */
  private fun saveRowLocked(id: Long, r: SentenceRow) {
    try {
      sink.savePosition(id, r.paragraphIndex, r.start)
    } catch (e: RuntimeException) {
      saveErrors++
    }
  }

  private fun clearLocked() {
    handover = 0L
    itemId = null
    rows = emptyList()
    current = 0
    queuedUntil = 0
    playing = false
    lastDoneAt = -1
    consecutiveErrors = 0
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
    const val MAX_CONSECUTIVE_ERRORS = 3
    const val STALL_TICKS = 3
  }
}
