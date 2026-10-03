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
  private var saveErrors = 0
  // Items stopped for deletion. Ids are AUTOINCREMENT (Store.kt), never reused.
  private val deleted = HashSet<Long>()

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

  /** The item is being deleted: stop without saving into it, and never load it again. */
  fun stopItem(id: Long) = synchronized(lock) {
    deleted += id
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
    val s = QueueStats(GapStats.summarize(gaps), errors, saveErrors)
    gaps.clear()
    errors = 0
    saveErrors = 0
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
    saveRowLocked(id, rows[next])
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
