package io.loopstring.readme.playback

import io.loopstring.readme.store.Rate
import java.io.File

data class QueueStats(
  val gaps: GapSummary,
  val errors: Int,
  val saveErrors: Int,
  val underruns: Int = 0,
  val synthP50: Long = 0,
  val synthP95: Long = 0,
)

/**
 * R-M07 and srs.md "Playback": the sentence queue the native service owns (AGENTS.md 11).
 *
 * REA-31 (ADR 0012): playback's engine synthesizes each sentence to a file ahead of the one
 * playing (at least [AHEAD] sentences, more until [aheadMs] of audio is ready, at most
 * [MAX_AHEAD] and [AHEAD_BYTES]; long sentences in chunks of [chunkChars]), and Read Me's own
 * [Player] plays the files in order at
 * 1.0x. The rate is applied once, by the engine (AGENTS.md 9). A sentence starts and ends when
 * the player says so, so positions and R-M07's gap (player done(n) to player start(n+1)) are
 * what the listener hears. Pause is a true pause (D1): the player holds its place and the files
 * stay. A rate change, a jump, a new item or a lost engine discards the files not yet played
 * and starts again from the start of a sentence.
 *
 * R-S05 (ADR 0011): when the player finishes an item's last sentence the queue archives it and,
 * outside its lock, asks the sink for the next item; loading it is a new generation like any
 * other, so the finished item's files are discarded and the next item's synthesis starts only
 * then. Nothing is synthesized across the item boundary (ADR 0012).
 *
 * Ids are "<generation>:<index>". Every discard starts a new generation, so an answer that
 * arrives late for discarded work is ignored.
 *
 * REA-51: while paused, nothing the engine answers moves or saves the position, rebinds or
 * starts audio. A failed or lost request discards the prefetch instead, and the next Play
 * restarts at the start of the sentence the user heard (a lost engine is reported then).
 */
class PlaybackQueue(
  private var synth: SentenceSynth,
  private val player: Player,
  private val files: PlaybackFiles,
  private val sink: PlaybackSink,
  private val clock: () -> Long,
  private val maxChars: Int = Utterances.MAX_CHARS,
  private val aheadMs: Long = AHEAD_MS,
  private val chunkChars: Int = CHUNK_CHARS,
  private val audioMs: (File) -> Long = WavPcm::durationMs,
) {
  private val lock = Any()
  private var itemId: Long? = null
  private var rows: List<SentenceRow> = emptyList()
  private var sentences: List<SentenceRow> = emptyList() // the whole sentence of each row
  private var current = 0
  private var generation = 0
  private var playing = false
  private var rate = Rate.DEFAULT
  // The pipeline of this generation: [fed, requested) are with the engine or finished and
  // waiting their turn; the player holds [inPlayer] sentences it has not finished.
  private var pipelined = false
  // The player starts only once [START_MS] of audio is in it (or the item's last sentence is).
  private var primed = false
  private var fedMs = 0L
  private var requested = 0
  private var fed = 0
  private var inPlayer = 0
  private val fileOf = HashMap<Int, File>()
  private val outcome = HashMap<Int, Boolean>()
  private val requestedAt = HashMap<Int, Long>()
  // Synthesized audio of the sentences ahead, for the prefetch target and the stall log.
  private val durMs = HashMap<Int, Long>()
  private val bytesOf = HashMap<Int, Long>()
  private val synthOf = HashMap<Int, Long>()
  private val aheadAtRequest = HashMap<Int, Long>()
  // What this load's synthesized sentences measured: estimates the ones not yet synthesized.
  private var seenChars = 0L
  private var seenMs = 0L
  private var seenBytes = 0L
  private var lastDoneAt = -1L
  private val gaps = ArrayList<Long>()
  private val synthMs = ArrayList<Long>()
  private var errors = 0
  private var saveErrors = 0
  private var loadedAt = -1L
  // Reset only by a sentence that synthesized: a failing engine must not read silence to the end.
  private var consecutiveErrors = 0
  private var firstErrorIndex = -1
  private var stallTicks = 0
  private var events = 0L
  private var lastProgress = 0L
  // Items stopped for deletion. Ids are AUTOINCREMENT (Store.kt), never reused.
  private val deleted = HashSet<Long>()
  // R-S05: set at a continuous-play handover until the next item's first sentence starts.
  private var handoverFrom = -1L
  private var handoverAt = -1L
  // R-S05: non-zero while the sink builds the next item outside the lock (ADR 0011). Any
  // pause, stop, play or deletion meanwhile clears it, and the built item is then dropped.
  private var handover = 0L
  private var handoverSeq = 0L
  // REA-51: the engine died (ERROR_SERVICE) while paused. Reported to the sink at the next start,
  // so the rebind follows a user action and Read Me never starts speaking by itself.
  private var engineLostWhilePaused = false

  private class Pending(val token: Long, val finishedId: Long, val skip: Set<Long>)

  fun load(itemId: Long, sentences: List<SentenceRow>, startIndex: Int, rate: Float): Boolean =
    synchronized(lock) {
      if (itemId in deleted || startIndex !in sentences.indices) return false
      saveLocked()
      val fitted = Utterances.fit(sentences, startIndex, maxChars, chunkChars)
      // Discard the previous item's files while their names are still its own.
      flushLocked()
      this.itemId = itemId
      rows = fitted.rows
      this.sentences = fitted.sentences
      resetEstimateLocked()
      this.rate = rate
      synth.setRate(rate)
      loadedAt = clock()
      restartLocked(fitted.startIndex)
      true
    }

  /** D1: the player holds its place mid-sentence; the files stay for the resume. */
  fun pause(): Boolean = synchronized(lock) {
    if (!playing) return false
    if (handover != 0L) {
      // ADR 0011: the finished item is archived and the next has not started, so a pause here
      // ends the chain like an item end with the switch off; no position goes into either.
      // The finished item's pipeline was already discarded at its end: no files are left.
      clearLocked()
      return true
    }
    playing = false
    lastDoneAt = -1
    player.pause()
    saveLocked()
    publishLocked()
    true
  }

  fun resume(): Boolean = synchronized(lock) {
    if (playing || itemId == null) return false
    if (pipelined) {
      playing = true
      stallTicks = 0
      lastDoneAt = -1
      if (primed) player.play() else primeLocked()
      topUpLocked()
      publishLocked()
    } else {
      restartLocked(current)
    }
    true
  }

  /** To the next sentence (past any further chunks of this one). */
  fun next(): Boolean = synchronized(lock) {
    if (itemId == null || handover != 0L) return false
    var j = current + 1
    while (j < rows.size && sentences[j] == sentences[current]) j++
    if (j >= rows.size) return false
    jumpLocked(j)
    true
  }

  /** To the start of the previous sentence; at the first, to its start. */
  fun previous(): Boolean = synchronized(lock) {
    if (itemId == null || handover != 0L) return false
    val here = sentenceStartLocked(current)
    jumpLocked(if (here == 0) 0 else sentenceStartLocked(here - 1))
    true
  }

  private fun sentenceStartLocked(i: Int): Int {
    var j = i
    while (j > 0 && sentences[j - 1] == sentences[i]) j--
    return j
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

  /**
   * setSpeechRate is the only rate lever (AGENTS.md 9). Files made at the old rate are
   * discarded; playing restarts at the current sentence's start, paused stays paused.
   */
  fun setRate(rate: Float) = synchronized(lock) {
    this.rate = rate
    synth.setRate(rate)
    // Durations measured at the old rate say nothing about the new one.
    resetEstimateLocked()
    // REA-35: with no item an empty snapshot reads as "the item ended", and the service dropped
    // a play request waiting for the engine. load() takes the rate from Settings.
    // During a handover the next item is synthesized at this rate; the finished one is not
    // restarted, and nothing of it is left to discard.
    if (itemId == null || handover != 0L) return
    if (playing) {
      restartLocked(current)
    } else {
      flushLocked()
      publishLocked()
    }
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

  /** ADR 0009's window ended: keep only the position, so nothing is held for a stopped service. */
  fun dropPrefetch() = synchronized(lock) {
    if (!playing && pipelined) flushLocked()
  }

  // --- the engine's answers (its binder thread) ---

  fun onSynthDone(id: String) {
    val pending = synchronized(lock) {
      val i = indexOf(id) ?: return
      if (i !in fed until requested || outcome.containsKey(i)) return
      events++
      requestedAt.remove(i)?.let { synthMs += clock() - it; synthOf[i] = clock() - it }
      consecutiveErrors = 0
      outcome[i] = true
      fileOf[i]?.let { f ->
        val ms = audioMs(f)
        if (ms >= 0) {
          durMs[i] = ms
          bytesOf[i] = f.length()
          seenChars += rows[i].text.length
          seenMs += ms
          seenBytes += f.length()
        }
      }
      val ended = feedLocked()
      // The engine is kept busy while the player plays: never wait for a sentence to end.
      topUpLocked()
      ended
    }
    if (pending != null) handOver(pending)
  }

  /**
   * [lost]: the engine's process died (ERROR_SERVICE); else the sentence is skipped and counted.
   * While paused neither applies (REA-51): see [pausedAnswerLocked].
   */
  fun onSynthError(id: String, lost: Boolean) {
    val pending = synchronized(lock) {
      val i = indexOf(id) ?: return
      if (i !in fed until requested || outcome.containsKey(i)) return
      events++
      if (!playing) {
        pausedAnswerLocked(lost)
        return
      }
      if (lost) {
        lostLocked()
        return
      }
      requestedAt.remove(i)
      fileOf.remove(i)?.delete()
      failedLocked(i)
      if (pipelined) feedLocked() else null
    }
    if (pending != null) handOver(pending)
  }

  // --- the player's events (its thread) ---

  fun onPlayerStart(id: String) = synchronized(lock) {
    val i = indexOf(id) ?: return
    events++
    stallTicks = 0
    if (lastDoneAt >= 0) {
      val gap = clock() - lastDoneAt
      gaps += gap
      if (gap > GapStats.STALL_MS) stallLocked(i, gap)
    }
    lastDoneAt = -1
    if (loadedAt >= 0) {
      itemId?.let { sink.firstAudio(it, clock() - loadedAt) }
      loadedAt = -1
    }
    current = i
    if (handoverAt >= 0) {
      sink.handedOver(handoverFrom, itemId!!, clock() - handoverAt)
      handoverAt = -1
    }
    topUpLocked()
    publishLocked()
  }

  /**
   * The last sentence's done hands over on this (the player's) thread: the player was flushed at
   * the item's end and has nothing to write while the sink builds the next item.
   */
  fun onPlayerDone(id: String) {
    val pending = synchronized(lock) {
      val i = indexOf(id) ?: return
      events++
      stallTicks = 0
      inPlayer--
      lastDoneAt = clock()
      advanceLocked(i)
    }
    if (pending != null) handOver(pending)
  }

  fun onPlayerError(id: String) {
    val pending = synchronized(lock) {
      val i = indexOf(id) ?: return
      events++
      inPlayer--
      lastDoneAt = -1
      failedLocked(i)
      if (pipelined) advanceLocked(i) else null
    }
    if (pending != null) handOver(pending)
  }

  fun snapshot(): PlaybackSnapshot = synchronized(lock) { snapshotLocked() }

  fun takeStats(): QueueStats = synchronized(lock) {
    val synth = synthMs.sorted()
    val s = QueueStats(
      GapStats.summarize(gaps), errors, saveErrors, player.takeUnderruns(),
      if (synth.isEmpty()) 0 else GapStats.percentile(synth, 50),
      if (synth.isEmpty()) 0 else GapStats.percentile(synth, 95),
    )
    gaps.clear()
    synthMs.clear()
    errors = 0
    saveErrors = 0
    s
  }

  /**
   * Called every few seconds by the service while playing. True when the engine is lost: no
   * sentence audio and no answer for [STALL_TICKS] ticks and the engine's binding is dead, or
   * none for [HUNG_TICKS] whatever the binding says. A slow sentence on a live engine is not
   * a stall (a cold engine took 8 s to first audio, AGENTS.md).
   */
  fun checkStall(): Boolean {
    val gen = synchronized(lock) {
      val progress = player.progress() + events
      // Nothing is asked of the engine during a handover by design: that is not a lost engine.
      if (!playing || handover != 0L || progress != lastProgress) {
        lastProgress = progress
        stallTicks = 0
        return false
      }
      if (++stallTicks < STALL_TICKS) return false
      if (stallTicks >= HUNG_TICKS) {
        lostLocked()
        return true
      }
      generation
    }
    // getVoice goes to the engine's process; asked outside the lock so callbacks are not held.
    val alive = synth.alive()
    synchronized(lock) {
      if (alive || !playing || generation != gen || stallTicks < STALL_TICKS) return false
      lostLocked()
      return true
    }
  }

  /**
   * A rebound engine or a new voice (PlaybackService). The rate is applied once more. Files the
   * old engine made are discarded (ADR 0012), and so are its requests still in flight, which it
   * may never answer: the next Play restarts at the current sentence's start on the new engine.
   *
   * REA-51: [retire] shuts the old speaker down, and runs only after the flush has left its
   * generation, so the errors its stop() turns in-flight requests into are ignored. It runs
   * outside the lock (binder calls) and in a finally, so the old speaker is never leaked.
   * While playing, the new speaker is not ready yet (its synthesis would fail and read as a
   * lost engine), so the queue pauses at the heard sentence and the service resumes on onReady;
   * during a handover the chain ends instead, as a pause does there (ADR 0011).
   */
  fun swapSynth(s: SentenceSynth, retire: () -> Unit = {}) {
    try {
      synchronized(lock) {
        if (pipelined) flushLocked()
        synth = s
        engineLostWhilePaused = false
        if (playing) {
          if (handover != 0L) {
            clearLocked()
          } else {
            playing = false
            lastDoneAt = -1
            saveLocked()
            publishLocked()
          }
        }
        s.setRate(rate)
      }
    } finally {
      retire()
    }
  }

  /**
   * REA-51: an engine answer while paused (a request asked for before the pause failed, or the
   * engine died). It must not move or save the position, count against the engine, or rebind,
   * so the pipeline is discarded (every other answer of it is then ignored) and the next start
   * restarts the current sentence; a lost engine is reported to the sink at that start.
   */
  private fun pausedAnswerLocked(lost: Boolean) {
    flushLocked()
    if (lost) engineLostWhilePaused = true
  }

  private fun failedLocked(i: Int) {
    errors++
    if (consecutiveErrors == 0) firstErrorIndex = i
    consecutiveErrors++
    outcome[i] = false
    if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) pauseAtLocked(firstErrorIndex)
  }

  /**
   * The player finished sentence [i]: save the next one's start and keep the engine busy.
   * Non-null when that was the item's end and it was archived: the caller runs [handOver].
   */
  private fun advanceLocked(i: Int): Pending? {
    val id = itemId ?: return null
    val next = i + 1
    if (next < rows.size && next > current) {
      current = next
      saveRowLocked(id, rows[next])
    }
    topUpLocked()
    return endLocked()
  }

  /** Hands finished files to the player in order; a failed sentence is skipped. As [endLocked]. */
  private fun feedLocked(): Pending? {
    while (fed < requested && outcome.containsKey(fed)) {
      val ok = outcome.remove(fed) == true
      val f = fileOf.remove(fed)
      if (ok && f != null) {
        player.enqueue("$generation:$fed", f)
        inPlayer++
        // A file not read as WAV counts as enough: the player reports it as an error at once.
        fedMs += durMs[fed]?.takeIf { it >= 0 } ?: START_MS
      }
      fed++
    }
    primeLocked()
    return endLocked()
  }

  /**
   * REA-31 start buffer: a short first sentence played while a cold engine still wrote the
   * next one, and the output ran dry (device:gap 2026-10-04 09:23: 1154 ms at index 1, the
   * next sentence's synthesis 6678 ms from the load). So the player starts only when
   * [START_MS] of audio is queued in it, or the item's last sentence is, or everything asked for
   * is in it and the prefetch limits ask for nothing more (sentences too short to add up to
   * [START_MS] within [MAX_AHEAD] would otherwise never start).
   */
  private fun primeLocked() {
    if (primed || !playing || !pipelined || inPlayer == 0) return
    val more = requested < rows.size && wantMoreLocked()
    if (fedMs < START_MS && fed < rows.size && (fed < requested || more)) return
    primed = true
    player.play()
  }

  /**
   * Everything is played or failed: archive (R-M11), or pause at a failing end. Non-null when
   * the item was archived while playing: the caller runs [handOver] outside the lock.
   */
  private fun endLocked(): Pending? {
    val id = itemId ?: return null
    if (!pipelined || fed < rows.size || inPlayer > 0) return null
    if (consecutiveErrors > 0) {
      // The last sentences failed: that is not reading to the end (R-M11 archives only then).
      pauseAtLocked(firstErrorIndex)
      return null
    }
    // The finished item's pipeline goes now: anything of it not yet deleted (AGENTS.md 3).
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
    // D1: a pause holds the output, but frames already heard can still finish the item. It is
    // read to the end and archived; the user's pause ends the chain (ADR 0011).
    if (!playing) {
      clearLocked()
      return null
    }
    // Still "playing" the finished item, with nothing queued, until handOver decides: no
    // snapshot is published here, so the service keeps focus, the session and the foreground.
    handover = ++handoverSeq
    return Pending(handover, id, HashSet(deleted))
  }

  /**
   * R-S05, ADR 0011: archive-then-load in one step. No itemId=null snapshot is published
   * between the two items, so the service keeps focus, the wake lock, the session and the
   * foreground (Android 12+ refuses a new foreground start from the background). The rate is
   * the queue's own and is not applied again (AGENTS.md 9). The next item starts as a new
   * generation from its start sentence: its synthesis begins only now (ADR 0012).
   */
  private fun handOver(p: Pending) {
    // Store reads and whole-item segmentation run outside the lock, so the main thread (stall
    // checks, session and notification controls, JS's getPlayback) never waits on them.
    // Throwable, not RuntimeException: an Error escaping here (StackOverflowError or OOM while
    // segmenting a big item) left the queue playing silently with the wake lock held (REA-46).
    val built = try {
      sink.nextItem(p.finishedId, p.skip)
        ?.takeIf { it.startIndex in it.sentences.indices }
        ?.let { it to Utterances.fit(it.sentences, it.startIndex, maxChars, chunkChars) }
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
      sentences = fitted.sentences
      // As load(): this item's synthesized sentences make the prefetch estimate.
      resetEstimateLocked()
      // The handover line reports this item's first audio; first-audio is a user play's.
      loadedAt = -1
      restartLocked(fitted.startIndex)
      // After the restart: its flush clears a handover time in progress. A refused first
      // request has already paused it (engine lost): nothing to time then.
      if (playing) {
        handoverFrom = p.finishedId
        handoverAt = clock()
      }
    }
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

  private fun jumpLocked(i: Int) {
    if (playing) {
      restartLocked(i)
    } else {
      flushLocked()
      current = i
      publishLocked()
    }
    saveLocked()
  }

  private fun restartLocked(index: Int) {
    // A user play during a handover wins over it (ADR 0011).
    handover = 0L
    if (engineLostWhilePaused) {
      // REA-51: the engine died while paused; report it now that the user asked for audio.
      engineLostWhilePaused = false
      current = index
      lostLocked()
      return
    }
    flushLocked()
    stallTicks = 0
    // A new generation: errors before a jump or another item are not this run's.
    consecutiveErrors = 0
    current = index
    requested = index
    fed = index
    inPlayer = 0
    pipelined = true
    playing = true
    primed = false
    fedMs = 0
    topUpLocked()
    publishLocked()
  }

  /** Discards the pipeline: the engine's work in flight, the player's, and their files. */
  private fun flushLocked() {
    // A pause, jump or stop before the next item's first sentence: no handover time to report.
    handoverAt = -1
    generation++
    if (pipelined) {
      synth.stop()
      player.flush()
    }
    // Deleting a file the engine is still writing is safe: TextToSpeech opened it here and the
    // engine writes through that descriptor, so nothing recreates it.
    fileOf.values.forEach { it.delete() }
    fileOf.clear()
    outcome.clear()
    requestedAt.clear()
    durMs.clear()
    bytesOf.clear()
    synthOf.clear()
    aheadAtRequest.clear()
    pipelined = false
    primed = false
    fedMs = 0
    inPlayer = 0
    lastDoneAt = -1
  }

  private fun topUpLocked() {
    val id = itemId ?: return
    while (playing && pipelined && requested < rows.size && wantMoreLocked()) {
      val i = requested
      val f = files.file(id, generation, rows[i])
      fileOf[i] = f
      requestedAt[i] = clock()
      aheadAtRequest[i] = aheadLocked().first
      // Counted before the call: an engine that answers inside it must not be asked again.
      requested++
      if (!synth.synthesize("$generation:$i", rows[i].text, f)) {
        lostLocked()
        return
      }
    }
  }

  /**
   * [AHEAD] sentences after the current one always; beyond that, while less than [aheadMs] of
   * audio and [AHEAD_BYTES] are ahead, up to [MAX_AHEAD]. Sentences not yet synthesized are
   * estimated from what this load's synthesized ones measured per character; before any has,
   * only [AHEAD].
   */
  private fun wantMoreLocked(): Boolean {
    val n = requested - current
    if (n <= AHEAD) return true
    if (aheadMs <= 0 || n > MAX_AHEAD || seenChars == 0L) return false
    val (ms, bytes) = aheadLocked()
    return ms < aheadMs && bytes < AHEAD_BYTES
  }

  /** Audio ms and file bytes synthesized or asked for after the current sentence. */
  private fun aheadLocked(): Pair<Long, Long> {
    var ms = 0L
    var bytes = 0L
    for (i in current + 1 until requested) {
      val chars = rows[i].text.length
      ms += durMs[i] ?: if (seenChars > 0) chars * seenMs / seenChars else 0
      bytes += bytesOf[i] ?: if (seenChars > 0) chars * seenBytes / seenChars else 0
    }
    return ms to bytes
  }

  private fun resetEstimateLocked() {
    seenChars = 0
    seenMs = 0
    seenBytes = 0
  }

  /** Where a stall fell, for the log (no text): which sentence, how long it took, what was ahead. */
  private fun stallLocked(i: Int, gap: Long) {
    val r = rows[i]
    sink.stall(
      "ms=$gap index=$i paragraph=${r.paragraphIndex} offset=${r.start} chars=${r.text.length} " +
        "synthMs=${synthOf[i] ?: -1} audioMs=${durMs[i] ?: -1} aheadMsAtRequest=${aheadAtRequest[i] ?: -1} " +
        "depth=${requested - current} aheadMsNow=${aheadLocked().first}",
    )
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
    engineLostWhilePaused = false
    itemId = null
    rows = emptyList()
    sentences = emptyList()
    current = 0
    requested = 0
    fed = 0
    playing = false
    lastDoneAt = -1
    loadedAt = -1
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
    PlaybackSnapshot(itemId, playing, if (itemId == null) null else sentences.getOrNull(current), rate)

  private fun publishLocked() = sink.changed(snapshotLocked())

  companion object {
    /** Sentences always synthesized ahead of the one playing (planNote; the gate's sim needed 1). */
    const val AHEAD = 3
    /** Audio kept synthesized ahead when the sentences are short (REA-31 gap fix). */
    const val AHEAD_MS = 10_000L
    const val MAX_AHEAD = 8
    /** At most this much synthesized audio waits on disk (AGENTS.md 3 keeps it app-private). */
    const val AHEAD_BYTES = 8 * 1024 * 1024
    /**
     * Longer sentences are synthesized in pieces of at most this many characters, so no single
     * piece takes long to synthesize. A choice, not a measurement.
     */
    const val CHUNK_CHARS = 160
    /** Audio queued in the player before it starts (after a load, jump or rate change). */
    const val START_MS = 2_000L
    const val MAX_CONSECUTIVE_ERRORS = 3
    const val STALL_TICKS = 3
    /** About two minutes at the service's 5 s tick: the bridge's own synthesis timeout. */
    const val HUNG_TICKS = 24
  }
}
