package io.loopstring.readme.playback

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * REA-31 (ADR 0012): the queue synthesizes sentences to files ahead of Read Me's own player.
 * A sentence starts and ends when the player says so, never when the engine does.
 */
class PlaybackQueueTest {
  private inner class FakeSynth : SentenceSynth {
    val requested = mutableListOf<String>() // utterance ids, in order
    val files = mutableMapOf<String, File>()
    var stops = 0
    val rates = mutableListOf<Float>()
    var refuse = false
    var alive = true
    var aliveCheck: () -> Unit = {}
    /** Answer inside synthesize, as an engine on the same thread could. */
    var answerAtOnce = false
    /** REA-51: TtsSpeaker forwards onStop as an error for each request still in flight. */
    var onStop: () -> Unit = {}
    var failRate = false
    override fun synthesize(id: String, text: String, file: File): Boolean {
      if (refuse) return false
      requested += id
      files[id] = file
      // TextToSpeech opens (creates) the file in this process before the engine writes it.
      file.writeText("wav")
      if (answerAtOnce) queue.onSynthDone(id)
      return true
    }
    override fun stop() { stops++; onStop() }
    override fun setRate(rate: Float) {
      if (failRate) throw IllegalStateException("engine gone")
      rates += rate
    }
    override fun alive(): Boolean { aliveCheck(); return alive }
  }

  private class FakePlayer : Player {
    val enqueued = mutableListOf<String>()
    val files = mutableListOf<File>()
    var plays = 0
    var pauses = 0
    var flushes = 0
    var progress = 0L
    var underruns = 0
    override fun enqueue(id: String, file: File) { enqueued += id; files += file }
    override fun play() { plays++ }
    override fun pause() { pauses++ }
    override fun flush() { flushes++ }
    override fun progress() = progress
    override fun takeUnderruns() = underruns.also { underruns = 0 }
  }

  private class FakeSink : PlaybackSink {
    val saves = mutableListOf<Triple<Long, Int, Int>>()
    val finished = mutableListOf<Long>()
    val snapshots = mutableListOf<PlaybackSnapshot>()
    val firstAudio = mutableListOf<Pair<Long, Long>>()
    var failSaves = false
    override fun savePosition(itemId: Long, paragraphIndex: Int, charOffset: Int) {
      // Stands in for SQLiteDiskIOException, a RuntimeException that Store.savePosition rethrows.
      if (failSaves) throw RuntimeException("disk I/O")
      saves += Triple(itemId, paragraphIndex, charOffset)
    }
    var failFinish = false
    // R-S05: the order of finish, next-item and snapshot events across a handover.
    val events = mutableListOf<String>()
    override fun finished(itemId: Long) {
      if (failFinish) throw RuntimeException("disk I/O")
      finished += itemId
      events += "finished:$itemId"
    }
    var next: NextItem? = null
    val nextAsked = mutableListOf<Pair<Long, Set<Long>>>()
    // Runs inside nextItem: stands in for a user action on another thread during the handover.
    var during: (() -> Unit)? = null
    // REA-46: thrown from nextItem, as segmenting a large item could (StackOverflowError, OOM).
    var failNext: Throwable? = null
    override fun nextItem(finishedId: Long, skip: Set<Long>): NextItem? {
      nextAsked += finishedId to skip.toSet()
      events += "next:$finishedId"
      during?.invoke()
      failNext?.let { throw it }
      return next.also { next = null }
    }
    val handovers = mutableListOf<Triple<Long, Long, Long>>()
    override fun handedOver(fromId: Long, toId: Long, ms: Long) { handovers += Triple(fromId, toId, ms) }
    override fun changed(snapshot: PlaybackSnapshot) {
      snapshots += snapshot
      events += "snapshot:${snapshot.itemId}:${snapshot.playing}"
    }
    var lost = 0
    override fun engineLost() { lost++ }
    override fun firstAudio(itemId: Long, ms: Long) { firstAudio += itemId to ms }
  }

  private var now = 0L
  private val synth = FakeSynth()
  private val player = FakePlayer()
  private val sink = FakeSink()
  private val dir = File(Files.createTempDirectory("q").toFile(), "playback")
  // Count-only prefetch (no audio target, no chunking) for the tests written before the gap fix.
  private val queue: PlaybackQueue = PlaybackQueue(synth, player, PlaybackFiles(dir), sink, { now }, aheadMs = 0, chunkChars = 0)

  // Paragraph 0: sentences 0,1. Paragraph 1: 2,3. Paragraph 3: 4,5 (paragraph 2 cut).
  private val rows = listOf(
    SentenceRow(0, 0, 4, "A0."), SentenceRow(0, 5, 9, "A1."),
    SentenceRow(1, 0, 4, "B0."), SentenceRow(1, 5, 9, "B1."),
    SentenceRow(3, 0, 4, "D0."), SentenceRow(3, 5, 9, "D1."),
  )

  private fun g() = synth.requested.last().substringBefore(':')
  private fun indices(ids: List<String>) = ids.map { it.substringAfter(':') }
  private fun synthDone(vararg i: Int) { val g = g(); i.forEach { queue.onSynthDone("$g:$it") } }
  private fun start(i: Int) = queue.onPlayerStart("${g()}:$i")
  private fun done(i: Int) = queue.onPlayerDone("${g()}:$i")
  private fun leftover() = dir.listFiles().orEmpty().map { it.name }.sorted()

  // REA-55 fix round 1 (critic 1 F4): the service's own publisher holds the queue's lock, so a
  // change the queue publishes on another thread waits for it and cannot be overtaken.
  @Test fun publishWithHoldsTheLockTheQueuePublishesUnder() {
    assertTrue(queue.load(7, rows, 0, 2.0f))
    synthDone(0)
    val inside = CountDownLatch(1)
    val release = CountDownLatch(1)
    var published: Long? = null
    val a = Thread { queue.publishWith { s -> inside.countDown(); release.await(); published = s.itemId } }
    a.start()
    inside.await()
    val before = sink.snapshots.size
    val b = Thread { queue.pause() }
    b.start()
    b.join(300)
    assertTrue("pause published while publishWith held the snapshot", b.isAlive)
    assertEquals(before, sink.snapshots.size)
    release.countDown()
    a.join()
    b.join()
    assertEquals(7L, published)
    assertEquals(false, sink.snapshots.last().playing)
  }

  @Test fun loadAppliesTheRateOnceAndPrefetchesThreeAhead() {
    assertTrue(queue.load(7, rows, 0, 2.0f))
    assertEquals(listOf(2.0f), synth.rates)
    assertEquals(listOf("0", "1", "2", "3"), indices(synth.requested))
    // The player starts once there is audio to play (REA-31 start buffer).
    assertEquals(0, player.plays)
    synthDone(0)
    assertEquals(1, player.plays)
    assertTrue(queue.snapshot().playing)
    assertEquals(7L, queue.snapshot().itemId)
  }

  @Test fun fileNamesCarryTheItemGenerationAndOffsetNeverText() {
    queue.load(7, rows, 2, 2.0f)
    val f = synth.files.getValue(synth.requested.first())
    assertEquals("play-7-${g()}-1-0.wav", f.name)
    assertEquals(dir, f.parentFile)
  }

  @Test fun loadRefusesAnEmptyListOrABadStart() {
    assertFalse(queue.load(7, emptyList(), 0, 2.0f))
    assertFalse(queue.load(7, rows, 6, 2.0f))
    assertTrue(synth.requested.isEmpty())
  }

  @Test fun finishedFilesGoToThePlayerInOrder() {
    queue.load(7, rows, 0, 2.0f)
    synthDone(1)
    assertTrue(player.enqueued.isEmpty())
    synthDone(0)
    assertEquals(listOf("0", "1"), indices(player.enqueued))
    assertEquals(synth.files.getValue(player.enqueued[0]), player.files[0])
  }

  @Test fun theEngineFinishingASentenceIsNotItsStart() {
    queue.load(7, rows, 0, 2.0f)
    val before = sink.snapshots.size
    synthDone(0, 1)
    assertEquals(before, sink.snapshots.size)
    start(0)
    assertEquals(SentenceRow(0, 0, 4, "A0."), sink.snapshots.last().sentence)
  }

  @Test fun eachPlayerDoneSavesTheNextSentenceStartAndPrefetchesOneMore() {
    queue.load(7, rows, 0, 2.0f)
    synthDone(0)
    start(0)
    done(0)
    assertEquals(Triple(7L, 0, 5), sink.saves.last())
    assertEquals(listOf("0", "1", "2", "3", "4"), indices(synth.requested))
  }

  @Test fun everySentenceStartIsPublished() {
    queue.load(7, rows, 0, 2.0f)
    synthDone(0, 1)
    start(0); done(0); start(1)
    assertEquals(SentenceRow(0, 5, 9, "A1."), sink.snapshots.last().sentence)
    assertTrue(sink.snapshots.last().playing)
  }

  @Test fun theLastSentenceFinishesTheItem() {
    queue.load(7, rows, 4, 2.0f)
    synthDone(4, 5)
    start(4); done(4); start(5)
    assertTrue(sink.finished.isEmpty())
    done(5)
    assertEquals(listOf(7L), sink.finished)
    assertNull(queue.snapshot().itemId)
    assertFalse(queue.snapshot().playing)
    assertFalse(queue.resume())
    assertEquals(1, player.flushes)
  }

  @Test fun pauseIsATruePauseAndResumeGoesOnMidSentence() {
    // D1: the player pauses where it is; nothing is synthesized again.
    queue.load(7, rows, 0, 2.0f)
    synthDone(0, 1)
    start(0); done(0); start(1)
    val requested = synth.requested.size
    assertTrue(queue.pause())
    assertEquals(1, player.pauses)
    assertEquals(0, player.flushes)
    assertEquals(0, synth.stops)
    assertEquals(Triple(7L, 0, 5), sink.saves.last())
    assertFalse(queue.snapshot().playing)
    assertEquals(SentenceRow(0, 5, 9, "A1."), queue.snapshot().sentence)
    assertTrue(queue.resume())
    assertEquals(2, player.plays)
    assertEquals(requested, synth.requested.size)
    assertTrue(queue.snapshot().playing)
    // The same generation goes on: the player's events still count.
    done(1)
    assertEquals(Triple(7L, 1, 0), sink.saves.last())
  }

  @Test fun nothingMoreIsSynthesizedWhilePaused() {
    queue.load(7, rows, 0, 2.0f)
    synthDone(0)
    start(0)
    queue.pause()
    val requested = synth.requested.size
    done(0) // the last frames already in the output
    assertEquals(requested, synth.requested.size)
    queue.resume()
    assertEquals(listOf("0", "1", "2", "3", "4"), indices(synth.requested))
  }

  @Test fun aLateCallbackFromFlushedWorkIsIgnored() {
    // Review Focus 1: callbacks can arrive for work a flush discarded.
    queue.load(7, rows, 0, 2.0f)
    val old = g()
    synthDone(0)
    start(0)
    queue.next()
    val saves = sink.saves.size
    val requested = synth.requested.size
    val enqueued = player.enqueued.size
    queue.onPlayerDone("$old:0")
    queue.onSynthError("$old:1", lost = false)
    queue.onPlayerStart("$old:2")
    queue.onSynthDone("$old:3")
    queue.onSynthError("$old:3", lost = true)
    assertEquals(saves, sink.saves.size)
    assertEquals(requested, synth.requested.size)
    assertEquals(enqueued, player.enqueued.size)
    assertEquals(SentenceRow(0, 5, 9, "A1."), queue.snapshot().sentence)
    assertEquals(0, queue.takeStats().errors)
    assertEquals(0, sink.lost)
  }

  @Test fun aRateChangeWhilePausedDiscardsThePrefetchAndStaysPaused() {
    // Review Focus 5, and AGENTS.md 9: the rate lives in the files, so they go.
    queue.load(7, rows, 0, 2.0f)
    val g0 = g()
    synthDone(0)
    queue.pause()
    assertEquals(4, leftover().size)
    queue.setRate(3.0f)
    assertEquals(1, player.flushes)
    assertEquals(1, synth.stops)
    assertEquals("the unplayed files are deleted", listOf("play-7-$g0-0-0.wav"), leftover()) // handed to the player, which deletes its own
    assertFalse(queue.snapshot().playing)
    assertEquals(3.0f, queue.snapshot().rate)
    val requested = synth.requested.size
    assertEquals(requested, synth.requested.size)
    queue.resume()
    assertEquals(listOf("0", "1", "2", "3"), indices(synth.requested.drop(requested)))
    assertEquals(listOf(2.0f, 3.0f), synth.rates) // never applied twice
  }

  @Test fun aRateChangeWithNoItemPublishesNothing() {
    // REA-35 #1: an empty snapshot told PlaybackService the item had ended, and it dropped the
    // play request waiting for a cold engine. The next load reads the rate from Settings.
    queue.setRate(2.1f)
    assertTrue(sink.snapshots.isEmpty())
    assertEquals(2.1f, queue.snapshot().rate)
  }

  @Test fun aRateChangeWhilePlayingRestartsFromTheCurrentSentenceStart() {
    queue.load(7, rows, 0, 2.0f)
    val first = g()
    synthDone(0, 1)
    start(0); done(0); start(1)
    val requested = synth.requested.size
    queue.setRate(1.5f)
    assertEquals(1, synth.stops)
    assertEquals(1, player.flushes)
    assertEquals(listOf("1", "2", "3", "4"), indices(synth.requested.drop(requested)))
    assertTrue(g() != first)
    assertTrue(queue.snapshot().playing)
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

  @Test fun aJumpWhilePlayingRestartsAtTheTargetSentence() {
    queue.load(7, rows, 0, 2.0f)
    val requested = synth.requested.size
    queue.next()
    assertEquals(listOf("1", "2", "3", "4"), indices(synth.requested.drop(requested)))
    assertEquals(1, player.flushes)
  }

  @Test fun aJumpWhilePausedMovesWithoutSynthesizingAndDropsThePrefetch() {
    queue.load(7, rows, 0, 2.0f)
    queue.pause()
    val requested = synth.requested.size
    queue.next()
    assertEquals(requested, synth.requested.size)
    assertFalse(queue.snapshot().playing)
    assertEquals(Triple(7L, 0, 5), sink.saves.last())
    assertEquals(1, player.flushes)
    assertTrue(leftover().isEmpty())
    queue.resume()
    assertEquals(listOf("1", "2", "3", "4"), indices(synth.requested.drop(requested)))
  }

  @Test fun stopItemClearsWithoutSavingAndDeletesTheFiles() {
    // Review Focus 2: the item is being deleted; there is nothing to save into (AGENTS.md 3).
    queue.load(7, rows, 0, 2.0f)
    queue.stopItem(8)
    assertEquals(7L, queue.snapshot().itemId)
    val saves = sink.saves.size
    queue.stopItem(7)
    assertNull(queue.snapshot().itemId)
    assertEquals(saves, sink.saves.size)
    assertTrue(leftover().isEmpty())
    assertEquals(1, player.flushes)
  }

  @Test fun stopSavesAndDeletesTheFiles() {
    queue.load(7, rows, 2, 2.0f)
    queue.stop()
    assertEquals(Triple(7L, 1, 0), sink.saves.last())
    assertTrue(leftover().isEmpty())
    assertNull(queue.snapshot().itemId)
  }

  @Test fun loadingAnotherItemSavesTheFirstOnesPosition() {
    queue.load(7, rows, 2, 2.0f)
    queue.load(9, rows, 0, 2.0f)
    assertEquals(Triple(7L, 1, 0), sink.saves.last())
    assertEquals(9L, queue.snapshot().itemId)
    assertTrue(leftover().all { it.startsWith("play-9-") })
  }

  @Test fun aSynthesisErrorSkipsTheSentenceAndCounts() {
    queue.load(7, rows, 0, 2.0f)
    queue.onSynthError("${g()}:0", lost = false)
    synthDone(1)
    assertEquals(listOf("1"), indices(player.enqueued))
    assertFalse("the failed file is deleted", leftover().contains("play-7-${g()}-0-0.wav"))
    assertEquals(1, queue.takeStats().errors)
  }

  @Test fun aPlayerErrorCountsAndTheNextSentencePlays() {
    queue.load(7, rows, 0, 2.0f)
    synthDone(0, 1)
    queue.onPlayerError("${g()}:0")
    start(1)
    assertTrue(queue.snapshot().playing)
    assertEquals(SentenceRow(0, 5, 9, "A1."), queue.snapshot().sentence)
    assertEquals(1, queue.takeStats().errors)
  }

  @Test fun gapsArePlayerDoneToNextPlayerStartInContinuousPlayOnly() {
    // D3: R-M07's gap is what the listener hears, on Read Me's own clock.
    queue.load(7, rows, 0, 2.0f)
    synthDone(0, 1, 2)
    now = 100; start(0)
    now = 1000; done(0)
    now = 1030; start(1)
    now = 2000; done(1)
    queue.pause()
    now = 9000; queue.resume()
    now = 9100; start(2)
    val s = queue.takeStats().gaps
    assertEquals(1, s.count)
    assertEquals(30L, s.max)
    assertEquals(0, queue.takeStats().gaps.count) // taking resets
  }

  @Test fun synthesisTimesAndUnderrunsAreReported() {
    now = 1000
    queue.load(7, rows, 0, 2.0f)
    now = 1800; synthDone(0)
    now = 2000; synthDone(1)
    player.underruns = 3
    val st = queue.takeStats()
    assertEquals(800L, st.synthP50)
    assertEquals(1000L, st.synthP95)
    assertEquals(3, st.underruns)
  }

  @Test fun firstAudioIsTimedFromLoadToThePlayersFirstStartOnce() {
    now = 1000
    queue.load(7, rows, 0, 2.0f)
    synthDone(0, 1)
    now = 2500; start(0)
    done(0); start(1)
    assertEquals(listOf(7L to 1500L), sink.firstAudio)
  }

  @Test fun anItemStoppedForDeletionCannotBeLoadedAfterwards() {
    // REA-18: deleteItem stops the queue, then deletes; the service may have checked the
    // item exists just before and load it just after.
    queue.stopItem(7)
    assertFalse(queue.load(7, rows, 0, 2.0f))
    assertTrue(synth.requested.isEmpty())
    assertNull(queue.snapshot().itemId)
  }

  @Test fun stoppingAnotherItemLeavesPlaybackAlone() {
    queue.load(7, rows, 0, 2.0f)
    queue.stopItem(8)
    assertTrue(queue.snapshot().playing)
    assertEquals(7L, queue.snapshot().itemId)
    assertTrue(queue.load(9, rows, 0, 2.0f))
  }

  @Test fun aFailedSaveStillPrefetchesTheNextSentenceAndIsCounted() {
    // REA-18: Store.savePosition rethrows anything but a constraint error; a throw before the
    // top-up left the queue playing with nothing queued.
    queue.load(7, rows, 0, 2.0f)
    synthDone(0)
    sink.failSaves = true
    start(0); done(0)
    assertEquals("4", indices(synth.requested).last())
    assertTrue(queue.snapshot().playing)
    assertTrue(queue.pause())
    assertFalse(queue.snapshot().playing)
    assertEquals(2, queue.takeStats().saveErrors)
  }

  @Test fun threeErrorsInARowPauseAtTheFirstFailedSentence() {
    queue.load(7, rows, 0, 2.0f)
    val g = g()
    queue.onSynthError("$g:0", lost = false)
    queue.onSynthError("$g:1", lost = false)
    queue.onSynthError("$g:2", lost = false)
    assertFalse(queue.snapshot().playing)
    assertEquals(7L, queue.snapshot().itemId)
    assertEquals(Triple(7L, 0, 0), sink.saves.last()) // sentence 0 starts paragraph 0 at 0
    assertTrue(sink.finished.isEmpty())
    assertEquals(0, sink.lost)
  }

  @Test fun anErrorBetweenGoodSentencesStillPlays() {
    queue.load(7, rows, 0, 2.0f)
    val g = g()
    queue.onSynthError("$g:0", lost = false)
    synthDone(1)
    queue.onSynthError("$g:2", lost = false)
    synthDone(3)
    assertTrue(queue.snapshot().playing)
    assertEquals(listOf("1", "3"), indices(player.enqueued))
  }

  private fun longRows(n: Int) = (0 until n).map { SentenceRow(it, 0, 4, "S$it.") }

  @Test fun aPlayerErrorWhilePausedMovesNothing() {
    queue.load(7, longRows(20), 0, 2.0f)
    val g = g()
    synthDone(0, 1, 2)
    start(0)
    queue.pause()
    val saves = sink.saves.size
    queue.onPlayerError("$g:0")
    queue.onPlayerError("$g:1")
    queue.onPlayerError("$g:2")
    assertEquals(saves, sink.saves.size)
    assertEquals(0, queue.snapshot().sentence?.paragraphIndex)
    assertEquals(0, queue.takeStats().errors)
    assertTrue(queue.resume())
    assertTrue(queue.snapshot().playing)
    // The restart asks for the heard sentence, first in a new generation.
    val fresh = synth.requested.filter { !it.startsWith("$g:") }
    assertEquals("0", fresh.first().substringAfter(':'))
  }

  @Test fun errorsAtTheEndPauseInsteadOfArchiving() {
    queue.load(7, rows, 4, 2.0f)
    synthDone(4)
    queue.onSynthError("${g()}:5", lost = false)
    start(4); done(4)
    assertTrue(sink.finished.isEmpty())
    assertFalse(queue.snapshot().playing)
    assertEquals(Triple(7L, 3, 5), sink.saves.last()) // sentence 5 is paragraph 3 offset 5
  }

  @Test fun aRefusedSynthesisPausesAndReportsTheEngineLost() {
    // REA-18: after the engine process died, resume "played" silently with the wake lock held.
    synth.refuse = true
    queue.load(7, rows, 2, 2.0f)
    assertFalse(queue.snapshot().playing)
    assertEquals(1, sink.lost)
    assertEquals(Triple(7L, 1, 0), sink.saves.last())
    assertTrue(leftover().isEmpty())
  }

  @Test fun aServiceErrorIsTheEngineLostNotASkippedSentence() {
    // D5: ERROR_SERVICE means the engine's process is gone.
    queue.load(7, rows, 0, 2.0f)
    synthDone(0)
    start(0)
    queue.onSynthError("${g()}:1", lost = true)
    assertFalse(queue.snapshot().playing)
    assertEquals(1, sink.lost)
    assertEquals(0, queue.takeStats().errors)
    assertEquals(Triple(7L, 0, 0), sink.saves.last())
  }

  @Test fun noAudioProgressWithADeadEngineIsLostAfterThreeChecks() {
    queue.load(7, rows, 0, 2.0f)
    synth.alive = false
    assertFalse(queue.checkStall())
    assertFalse(queue.checkStall())
    assertTrue(queue.checkStall())
    assertFalse(queue.snapshot().playing)
    assertEquals(1, sink.lost)
  }

  @Test fun aSlowSynthesisOnALiveEngineIsNotAStall() {
    // A cold engine took 8 s to first audio (AGENTS.md); a long sentence takes longer still.
    queue.load(7, rows, 0, 2.0f)
    repeat(PlaybackQueue.HUNG_TICKS - 1) { assertFalse(queue.checkStall()) }
    assertTrue(queue.snapshot().playing)
    assertEquals(0, sink.lost)
  }

  @Test fun anEngineThatAnswersButNeverFinishesIsLostInTheEnd() {
    queue.load(7, rows, 0, 2.0f)
    repeat(PlaybackQueue.HUNG_TICKS - 1) { queue.checkStall() }
    assertTrue(queue.checkStall())
    assertEquals(1, sink.lost)
  }

  @Test fun audioPlayingOrAnyEventResetsTheStallCount() {
    queue.load(7, rows, 0, 2.0f)
    synth.alive = false
    queue.checkStall(); queue.checkStall()
    player.progress = 500 // a long sentence playing
    assertFalse(queue.checkStall())
    queue.checkStall(); queue.checkStall()
    synthDone(0)
    assertFalse(queue.checkStall())
    assertFalse(queue.checkStall())
    assertTrue(queue.snapshot().playing)
  }

  @Test fun theEngineIsAskedOutsideTheQueueLock() {
    // getVoice is a binder call into the engine; under the lock it would block every callback.
    queue.load(7, rows, 0, 2.0f)
    var free = false
    synth.aliveCheck = {
      val t = Thread { queue.snapshot() }
      t.start(); t.join(2_000)
      free = !t.isAlive
    }
    repeat(PlaybackQueue.STALL_TICKS) { queue.checkStall() }
    assertTrue(free)
  }

  @Test fun aPausedQueueIsNeverStalled() {
    queue.load(7, rows, 0, 2.0f)
    queue.pause()
    synth.alive = false
    repeat(PlaybackQueue.HUNG_TICKS + 1) { assertFalse(queue.checkStall()) }
  }

  @Test fun aSwappedSynthGetsTheRateAndTheNextResume() {
    queue.load(7, rows, 2, 1.5f)
    synth.refuse = true
    queue.next() // refused: lost
    val fresh = FakeSynth()
    queue.swapSynth(fresh)
    assertEquals(listOf(1.5f), fresh.rates)
    assertTrue(queue.resume())
    assertEquals(listOf("3", "4", "5"), indices(fresh.requested))
  }

  @Test fun aSynthSwappedWhilePausedDiscardsTheOldEnginesAudio() {
    // Critique F1 (REA-33's path): pause, change the voice or engine in Settings, Play. The old
    // engine's files must not play, and its unanswered requests must not hold the new run.
    queue.load(7, rows, 0, 2.0f)
    val old = g()
    synthDone(0, 1)
    start(0)
    queue.pause()
    val flushes = player.flushes
    val fresh = FakeSynth()
    queue.swapSynth(fresh)
    assertTrue(player.flushes > flushes)
    // The queue's own files (the ones the player was not given) are gone; the player deletes its own on flush.
    assertFalse(synth.files.getValue("$old:2").exists())
    assertFalse(synth.files.getValue("$old:3").exists())
    assertTrue(queue.resume())
    // From the start of the sentence that was playing, all of it asked of the new engine.
    assertEquals(listOf("0", "1", "2", "3"), indices(fresh.requested))
    assertTrue(fresh.requested.none { it.startsWith("$old:") })
    // A late answer from the old engine is ignored.
    val enqueued = player.enqueued.size
    queue.onSynthDone("$old:2")
    assertEquals(enqueued, player.enqueued.size)
  }

  // --- REA-51: while paused, late engine answers move nothing, and a swap retires the old
  // speaker only after the queue has left its generation ---

  /** Item 7 playing sentence 0, with 1-3 asked of the engine and unanswered; their generation. */
  private fun inFlight(): String {
    queue.load(7, rows, 0, 2.0f)
    synthDone(0)
    start(0)
    assertEquals(listOf("0", "1", "2", "3"), indices(synth.requested))
    return g()
  }

  @Test fun oldEngineStopsBeforeAPausedSwapDoNotSkipText() {
    // The ticket's probe: pause in sentence 0, the old engine's stop answers 1-3 as errors, the
    // swap, Play. The new engine was asked for [1..4] and sentence 0's rest was never heard.
    val g = inFlight()
    queue.pause()
    synth.onStop = { (1..3).forEach { queue.onSynthError("$g:$it", lost = false) } }
    val fresh = FakeSynth()
    queue.swapSynth(fresh) { synth.stop() }
    assertTrue(queue.resume())
    assertEquals(listOf("0", "1", "2", "3"), indices(fresh.requested))
    assertEquals(Triple(7L, 0, 0), sink.saves.last())
    assertEquals(0, queue.takeStats().errors)
  }

  @Test fun aSwapWhilePlayingFlushesBeforeTheOldSpeakerIsRetired() {
    // begin(): a voice changed in Settings, then Play for another item while this one plays.
    val g = inFlight()
    synth.onStop = { (1..3).forEach { queue.onSynthError("$g:$it", lost = false) } }
    val flushes = player.flushes
    val fresh = FakeSynth()
    queue.swapSynth(fresh) { synth.stop() }
    assertTrue(player.flushes > flushes)
    assertFalse(queue.snapshot().playing)
    assertEquals(rows[0], queue.snapshot().sentence)
    assertEquals(Triple(7L, 0, 0), sink.saves.last())
    assertEquals(0, sink.lost)
    assertEquals(0, queue.takeStats().errors)
    // The restart is the new engine's, once it is ready (the service's onReady).
    assertTrue(queue.resume())
    assertEquals(listOf("0", "1", "2", "3"), indices(fresh.requested))
  }

  @Test fun theOldSpeakerIsRetiredAfterTheFlushEvenIfTheSwapThrows() {
    val g = inFlight()
    val flushes = player.flushes
    var retiredAfterFlush = false
    val fresh = FakeSynth().apply { failRate = true }
    val swapped = runCatching { queue.swapSynth(fresh) { retiredAfterFlush = player.flushes > flushes } }
    assertTrue(swapped.isFailure)
    assertTrue(retiredAfterFlush)
    // The old generation is left: its answers no longer count.
    queue.onSynthError("$g:1", lost = true)
    assertEquals(0, sink.lost)
  }

  @Test fun lateSynthesisErrorsWhilePausedLeaveThePositionAlone() {
    val g = inFlight()
    queue.pause()
    val saves = sink.saves.size
    (1..3).forEach { queue.onSynthError("$g:$it", lost = false) }
    assertEquals(saves, sink.saves.size)
    assertEquals(rows[0], queue.snapshot().sentence)
    assertFalse(queue.snapshot().playing)
    assertEquals(0, sink.lost)
    assertEquals(0, queue.takeStats().errors)
    val before = synth.requested.size
    assertTrue(queue.resume())
    val after = synth.requested.drop(before)
    assertTrue(after.none { it.startsWith("$g:") })
    assertEquals(listOf("0", "1", "2", "3"), indices(after))
  }

  @Test fun oneLateErrorWhilePausedRestartsTheHeardSentenceOnPlay() {
    val g = inFlight()
    queue.pause()
    queue.onSynthError("$g:1", lost = false)
    val before = synth.requested.size
    assertTrue(queue.resume())
    val after = synth.requested.drop(before)
    assertTrue(after.none { it.startsWith("$g:") })
    assertEquals(listOf("0", "1", "2", "3"), indices(after))
    assertEquals(Triple(7L, 0, 0), sink.saves.last())
    assertEquals(0, queue.takeStats().errors)
  }

  @Test fun anEngineDeathWhilePausedWaitsForPlayToRebind() {
    val g = inFlight()
    queue.pause()
    val saves = sink.saves.size
    val asked = synth.requested.size
    queue.onSynthError("$g:1", lost = true)
    assertEquals(0, sink.lost)
    assertEquals(saves, sink.saves.size)
    assertEquals(asked, synth.requested.size)
    assertFalse(queue.snapshot().playing)
    // Play: the engine is reported lost now, at the heard sentence; nothing is asked of it.
    assertTrue(queue.resume())
    assertEquals(1, sink.lost)
    assertFalse(queue.snapshot().playing)
    assertEquals(Triple(7L, 0, 0), sink.saves.last())
    assertEquals(asked, synth.requested.size)
    // The service rebinds (recoverEngine's swap) and onReady resumes on the new engine.
    val fresh = FakeSynth()
    queue.swapSynth(fresh)
    assertTrue(queue.resume())
    assertEquals(listOf("0", "1", "2", "3"), indices(fresh.requested))
    assertEquals(1, sink.lost)
  }

  @Test fun answersFromALeftGenerationAreIgnoredAfterASwap() {
    val g = inFlight()
    queue.pause()
    queue.swapSynth(FakeSynth())
    val saves = sink.saves.size
    val enqueued = player.enqueued.size
    queue.onSynthError("$g:1", lost = false)
    queue.onSynthError("$g:2", lost = true)
    queue.onSynthDone("$g:3")
    assertEquals(saves, sink.saves.size)
    assertEquals(0, sink.lost)
    assertEquals(enqueued, player.enqueued.size)
    assertEquals(0, queue.takeStats().errors)
  }

  @Test fun aSwapClearsAnEngineLostWhilePaused() {
    // follow(): the engine died while paused, then a voice change and Play bind a new speaker.
    val g = inFlight()
    queue.pause()
    queue.onSynthError("$g:1", lost = true)
    val fresh = FakeSynth()
    queue.swapSynth(fresh)
    assertTrue(queue.resume())
    assertEquals(0, sink.lost)
    assertTrue(queue.snapshot().playing)
    assertEquals(listOf("0", "1", "2", "3"), indices(fresh.requested))
  }

  @Test fun aJumpEndsTheErrorRun() {
    // Critique: two errors, Next, Next, then one error paused back at sentence 0.
    queue.load(7, rows, 0, 2.0f)
    val g = g()
    queue.onSynthError("$g:0", lost = false)
    queue.onSynthError("$g:1", lost = false)
    queue.next()
    queue.next()
    queue.onSynthError("${g()}:4", lost = false)
    assertTrue(queue.snapshot().playing)
  }

  @Test fun aNewItemStartsWithNoErrorRun() {
    queue.load(7, rows, 0, 2.0f)
    val g = g()
    queue.onSynthError("$g:0", lost = false)
    queue.onSynthError("$g:1", lost = false)
    queue.pause()
    queue.load(8, rows.take(2), 0, 2.0f)
    queue.onSynthError("${g()}:0", lost = false)
    assertTrue(queue.snapshot().playing)
    assertEquals(8L, queue.snapshot().itemId)
  }

  @Test fun aFailedArchiveStillClearsTheQueue() {
    sink.failFinish = true
    queue.load(7, rows.take(1), 0, 2.0f)
    synthDone(0)
    start(0)
    done(0)
    assertEquals(null, queue.snapshot().itemId)
    assertFalse(queue.snapshot().playing)
  }

  @Test fun dropPrefetchKeepsThePositionAndResumeRestartsAtTheSentenceStart() {
    // ADR 0009's window ended: the service may be stopped, so nothing is kept but the position.
    queue.load(7, rows, 0, 2.0f)
    synthDone(0, 1)
    start(0); done(0); start(1)
    queue.pause()
    queue.dropPrefetch()
    assertEquals(1, player.flushes)
    assertEquals(1, synth.stops)
    assertTrue(leftover().all { it in player.files.map { f -> f.name } })
    val requested = synth.requested.size
    queue.resume()
    assertEquals(listOf("1", "2", "3", "4"), indices(synth.requested.drop(requested)))
  }

  @Test fun anEngineAnsweringInsideSynthesizeIsNotAskedTwice() {
    synth.answerAtOnce = true
    queue.load(7, rows, 0, 2.0f)
    assertEquals(listOf("0", "1", "2", "3"), indices(synth.requested))
    assertEquals(listOf("0", "1", "2", "3"), indices(player.enqueued))
  }

  // --- REA-31 gap fix: prefetch by buffered audio, chunked synthesis, stall diagnostics ---

  private val durations = HashMap<String, Long>() // file name -> ms of audio
  private fun audioQueue(synth: FakeSynth, aheadMs: Long = 10_000, chunk: Int = 0) =
    PlaybackQueue(synth, player, PlaybackFiles(dir), sink, { now }, aheadMs = aheadMs, chunkChars = chunk,
      audioMs = { f -> durations[f.name] ?: -1L })

  private fun many(n: Int) = (0 until n).map { SentenceRow(it, 0, 3, "S$it.") }

  @Test fun shortSentencesAreSynthesizedUntilTenSecondsAreBufferedUpToEight() {
    val s = FakeSynth()
    val q = audioQueue(s)
    q.load(7, many(20), 0, 2.0f)
    val g = s.requested.last().substringBefore(':')
    // Every sentence turns out to be half a second: 3 ahead is 1.5 s, far under the target.
    for (i in 0 until 20) {
      val id = "$g:$i"
      if (id !in s.requested) break
      durations[s.files.getValue(id).name] = 500
      q.onSynthDone(id)
    }
    // While sentence 0 plays, the engine is kept busy without waiting for the player.
    assertEquals((0..8).map { "$it" }, indices(s.requested))
  }

  @Test fun aLongSentenceAheadMeetsTheTargetWithThreeAhead() {
    val s = FakeSynth()
    val q = audioQueue(s)
    q.load(7, many(20), 0, 2.0f)
    val g = s.requested.last().substringBefore(':')
    durations[s.files.getValue("$g:1").name] = 20_000
    // The long one is measured first; then nothing estimates the ones after it as short.
    for (i in listOf(1, 0, 2, 3)) { durations.putIfAbsent(s.files.getValue("$g:$i").name, 500); q.onSynthDone("$g:$i") }
    assertEquals((0..3).map { "$it" }, indices(s.requested))
  }

  @Test fun aLongSentenceAfterShortOnesIsAskedForBeforeTheShortOnesRunOut() {
    // The device:gap stall (2026-10-04): short sentences, then a long one whose synthesis took
    // longer than the audio ahead of it. Counting sentences asked for it too late.
    val s = FakeSynth()
    val q = audioQueue(s)
    val rows = many(12)
    q.load(7, rows, 0, 2.0f)
    val g = s.requested.last().substringBefore(':')
    for (i in 0..3) { durations[s.files.getValue("$g:$i").name] = 400; q.onSynthDone("$g:$i") }
    assertTrue("sentence 6 is asked for while sentence 0 still plays", "$g:6" in s.requested)
  }

  @Test fun theBufferTargetIsCappedByBytesToo() {
    val s = FakeSynth()
    val q = audioQueue(s)
    q.load(7, many(20), 0, 2.0f)
    val g = s.requested.last().substringBefore(':')
    for (i in 0..3) {
      val f = s.files.getValue("$g:$i")
      f.writeBytes(ByteArray(PlaybackQueue.AHEAD_BYTES / 3 + 1))
      durations[f.name] = 100
      q.onSynthDone("$g:$i")
    }
    assertEquals((0..3).map { "$it" }, indices(s.requested))
  }

  @Test fun aLongSentenceIsSynthesizedInChunksButHighlightedWhole() {
    val s = FakeSynth()
    val q = audioQueue(s, chunk = 40)
    val long = SentenceRow(1, 10, 10 + 100, "This first part is a clause, and here comes a second clause; then a third bit.".padEnd(100, 'x'))
    val rows = listOf(SentenceRow(0, 0, 4, "One."), long, SentenceRow(2, 0, 4, "Two."))
    q.load(7, rows, 1, 2.0f)
    val g = s.requested.last().substringBefore(':')
    assertTrue(s.requested.size >= 3)
    assertEquals(long, q.snapshot().sentence)
    // Fitted rows: "One." is 0, the long sentence's chunks start at 1.
    q.onSynthDone("$g:1"); q.onPlayerStart("$g:1"); q.onPlayerDone("$g:1")
    q.onSynthDone("$g:2"); q.onPlayerStart("$g:2")
    assertEquals(long, q.snapshot().sentence) // the second chunk: still the whole sentence
    assertTrue(sink.saves.last().third > 10) // the position is the chunk's own offset
    assertEquals(1, sink.saves.last().second)
  }

  @Test fun aStallIsLoggedWithWhereItFellAndWhatWasBuffered() {
    val s = FakeSynth()
    val stalls = mutableListOf<String>()
    val q = PlaybackQueue(s, player, PlaybackFiles(dir), object : PlaybackSink by sink {
      override fun stall(detail: String) { stalls += detail }
    }, { now }, aheadMs = 0, chunkChars = 0, audioMs = { f -> durations[f.name] ?: -1L })
    q.load(7, rows, 0, 2.0f)
    val g = s.requested.last().substringBefore(':')
    now = 0; durations[s.files.getValue("$g:0").name] = 900; q.onSynthDone("$g:0")
    now = 100; q.onPlayerStart("$g:0")
    now = 1000; q.onPlayerDone("$g:0")
    now = 2400; durations[s.files.getValue("$g:1").name] = 900; q.onSynthDone("$g:1")
    now = 2500; q.onPlayerStart("$g:1")
    assertEquals(1, stalls.size)
    val d = stalls[0]
    assertTrue(d, d.contains("ms=1500") && d.contains("index=1") && d.contains("paragraph=0") && d.contains("offset=5"))
    assertTrue(d, d.contains("synthMs=") && d.contains("chars=") && d.contains("aheadMsAtRequest=") && d.contains("depth="))
    assertFalse(d.contains("A1"))
  }

  // --- REA-31: start buffer (device:gap 2026-10-04 09:23: a 1154 ms stall at index 1, a short
  // first sentence played while the cold engine was still writing the second) ---

  @Test fun aShortFirstSentenceWaitsForTheNextBeforePlaying() {
    val s = FakeSynth()
    val q = audioQueue(s)
    q.load(7, many(10), 0, 2.0f)
    val g = s.requested.last().substringBefore(':')
    durations[s.files.getValue("$g:0").name] = 600
    q.onSynthDone("$g:0")
    assertEquals("not yet: 600 ms would run out before the next is written", 0, player.plays)
    durations[s.files.getValue("$g:1").name] = 3_000
    q.onSynthDone("$g:1")
    assertEquals(1, player.plays)
    assertEquals(listOf("0", "1"), indices(player.enqueued))
  }

  @Test fun aLongFirstSentencePlaysAtOnce() {
    val s = FakeSynth()
    val q = audioQueue(s)
    q.load(7, many(10), 0, 2.0f)
    val g = s.requested.last().substringBefore(':')
    durations[s.files.getValue("$g:0").name] = PlaybackQueue.START_MS
    q.onSynthDone("$g:0")
    assertEquals(1, player.plays)
  }

  @Test fun aShortLastSentencePlaysWithNothingAfterIt() {
    val s = FakeSynth()
    val q = audioQueue(s)
    q.load(7, many(1), 0, 2.0f)
    val g = s.requested.last().substringBefore(':')
    durations[s.files.getValue("$g:0").name] = 300
    q.onSynthDone("$g:0")
    assertEquals(1, player.plays)
  }

  @Test fun sentencesTooShortToFillTheStartBufferStillStartOnceNothingMoreIsAsked() {
    // Critique F2: nine sentences of 150 ms (dialogue at 2x) are under the start buffer, and the
    // prefetch cap then asks for nothing more: the player never started, silent while "playing".
    val s = FakeSynth()
    val q = audioQueue(s)
    q.load(7, many(30), 0, 2.0f)
    val g = s.requested.last().substringBefore(':')
    for (i in 0 until 30) {
      val id = "$g:$i"
      if (id !in s.requested) break
      durations[s.files.getValue(id).name] = 150
      q.onSynthDone(id)
    }
    assertEquals((0..8).map { "$it" }, indices(s.requested))
    assertEquals(1, player.plays)
  }

  @Test fun aPauseBeforeTheStartBufferFillsStaysPausedAndPlayWaitsForIt() {
    val s = FakeSynth()
    val q = audioQueue(s)
    q.load(7, many(10), 0, 2.0f)
    val g = s.requested.last().substringBefore(':')
    durations[s.files.getValue("$g:0").name] = 600
    q.onSynthDone("$g:0")
    q.pause()
    q.resume()
    assertEquals(0, player.plays)
    durations[s.files.getValue("$g:1").name] = 3_000
    q.onSynthDone("$g:1")
    assertEquals(1, player.plays)
  }

  // --- R-S05 continuous play (ADR 0011) on REA-31's pipeline (ADR 0012): the sink offers the
  // next item after the archive, and loading it is a new generation whose synthesis starts then ---

  private val rows8 = listOf(SentenceRow(0, 0, 4, "E0."), SentenceRow(0, 5, 9, "E1."), SentenceRow(1, 0, 4, "F0."))

  /** Item 7's last two sentences, synthesized and played to the end; returns their generation. */
  private fun finishSeven(): String {
    queue.load(7, rows, 4, 2.0f)
    val g = g()
    queue.onSynthDone("$g:4")
    queue.onSynthDone("$g:5")
    queue.onPlayerStart("$g:4")
    queue.onPlayerDone("$g:4")
    queue.onPlayerStart("$g:5")
    queue.onPlayerDone("$g:5")
    return g
  }

  @Test fun withNoNextItemTheEndIsUnchanged() {
    finishSeven()
    assertEquals(listOf(7L), sink.finished)
    assertEquals(listOf(7L to emptySet<Long>()), sink.nextAsked)
    assertNull(queue.snapshot().itemId)
    assertFalse(queue.snapshot().playing)
    assertEquals(1, player.flushes)
  }

  @Test fun theNextItemLoadsAfterTheArchiveWithNoEmptySnapshotBetween() {
    sink.next = NextItem(8, rows8, 1)
    val g = finishSeven()
    assertEquals(listOf(7L), sink.finished)
    val tail = sink.events.dropWhile { it != "finished:7" }
    assertEquals(listOf("finished:7", "next:7", "snapshot:8:true"), tail)
    assertTrue(sink.snapshots.none { it.itemId == null })
    assertTrue(sink.snapshots.none { !it.playing })
    val s = queue.snapshot()
    assertEquals(8L, s.itemId)
    assertTrue(s.playing)
    assertEquals(rows8[1], s.sentence)
    // A new generation from the saved start: indices 1 and 2 of item 8 are asked for.
    val g2 = g()
    assertTrue(g2 != g)
    assertEquals(listOf("1", "2"), indices(synth.requested.filter { it.startsWith("$g2:") }))
  }

  @Test fun theFinishedItemsPipelineIsDiscardedAndTheNextItemsFilesAreItsOwn() {
    sink.next = NextItem(8, rows8, 0)
    val g = finishSeven()
    // The engine is stopped and the player flushed once, at 7's end; 7's files the queue held are
    // deleted, and the ones it gave the player are the player's to delete (TrackPlayer does).
    assertEquals(1, synth.stops)
    assertEquals(1, player.flushes)
    val given = player.files.map { it.name }.toSet()
    assertTrue(leftover().filter { it.startsWith("play-7-") }.all { it in given })
    val g2 = g()
    assertEquals(
      listOf("play-8-$g2-0-0.wav", "play-8-$g2-0-5.wav", "play-8-$g2-1-0.wav"),
      leftover().filter { it.startsWith("play-8-") },
    )
    // Nothing of 8 was asked for before 7 ended: no prefetch across the item boundary.
    assertEquals(listOf("4", "5"), indices(synth.requested.filter { it.startsWith("$g:") }))
    // The player starts 8 only once its audio is ready, as after any load (start buffer).
    val plays = player.plays
    queue.onSynthDone("$g2:0")
    assertEquals(plays + 1, player.plays)
    assertEquals(listOf("$g2:0"), player.enqueued.filter { it.startsWith("$g2:") })
  }

  @Test fun lateAnswersForTheFinishedItemAreIgnoredAfterTheHandover() {
    sink.next = NextItem(8, rows8, 0)
    val g = finishSeven()
    val requested = synth.requested.size
    val enqueued = player.enqueued.size
    queue.onPlayerDone("$g:5")
    queue.onPlayerStart("$g:5")
    queue.onSynthDone("$g:5")
    queue.onSynthError("$g:4", lost = true)
    queue.onPlayerError("$g:4")
    assertEquals(listOf(7L), sink.finished)
    assertEquals(1, sink.nextAsked.size)
    assertEquals(requested, synth.requested.size)
    assertEquals(enqueued, player.enqueued.size)
    assertEquals(0, sink.lost)
    assertEquals(8L, queue.snapshot().itemId)
    assertEquals(rows8[0], queue.snapshot().sentence)
  }

  @Test fun theHandoverKeepsTheRateWithoutApplyingItAgain() {
    sink.next = NextItem(8, rows8, 0)
    finishSeven()
    assertEquals(listOf(2.0f), synth.rates)
    assertEquals(2.0f, queue.snapshot().rate)
  }

  @Test fun theChainGoesOnUntilNoItemIsLeft() {
    sink.next = NextItem(8, rows8, 2)
    finishSeven()
    val g = g()
    queue.onSynthDone("$g:2")
    queue.onPlayerStart("$g:2")
    queue.onPlayerDone("$g:2")
    assertEquals(listOf(7L, 8L), sink.finished)
    assertEquals(listOf(7L, 8L), sink.nextAsked.map { it.first })
    assertNull(queue.snapshot().itemId)
    assertEquals(2, player.flushes)
  }

  @Test fun theFirstSentenceOfTheNextItemReportsTheHandoverTime() {
    sink.next = NextItem(8, rows8, 0)
    finishSeven()
    val g = g()
    now = 100
    queue.onSynthDone("$g:0")
    now = 250
    queue.onPlayerStart("$g:0")
    // From the next item's load to its first frame heard: its synthesis is inside it.
    assertEquals(listOf(Triple(7L, 8L, 250L)), sink.handovers)
    // The handover is not an R-M07 sentence gap: only 7's sentence 4 to 5 (0 ms) is counted.
    val gaps = queue.takeStats().gaps
    assertEquals(1, gaps.count)
    assertEquals(0L, gaps.max)
    // first-audio is a user play's: 7's load only.
    assertEquals(listOf(7L), sink.firstAudio.map { it.first })
  }

  @Test fun aFailedArchiveEndsTheChain() {
    sink.failFinish = true
    sink.next = NextItem(8, rows8, 0)
    finishSeven()
    assertTrue(sink.nextAsked.isEmpty())
    assertNull(queue.snapshot().itemId)
  }

  @Test fun errorsAtTheEndPauseAndDoNotContinue() {
    sink.next = NextItem(8, rows8, 0)
    queue.load(7, rows, 5, 2.0f)
    queue.onSynthError("${g()}:5", lost = false)
    assertTrue(sink.nextAsked.isEmpty())
    assertEquals(7L, queue.snapshot().itemId)
    assertFalse(queue.snapshot().playing)
  }

  @Test fun aPauseOnTheLastSentenceEndsTheChainEvenIfItsLastFramesFinishIt() {
    // D1 keeps the generation across a pause, so the player can still report the end of frames
    // heard before it. The item was read to the end (R-M11); the user's pause ends the chain.
    sink.next = NextItem(8, rows8, 0)
    queue.load(7, rows, 5, 2.0f)
    val g = g()
    queue.onSynthDone("$g:5")
    queue.onPlayerStart("$g:5")
    assertTrue(queue.pause())
    queue.onPlayerDone("$g:5")
    assertEquals(listOf(7L), sink.finished)
    assertTrue(sink.nextAsked.isEmpty())
    assertNull(queue.snapshot().itemId)
    assertFalse(queue.snapshot().playing)
  }

  @Test fun aStopEndsTheChain() {
    sink.next = NextItem(8, rows8, 0)
    queue.load(7, rows, 5, 2.0f)
    val g = g()
    queue.onSynthDone("$g:5")
    queue.stop()
    queue.onPlayerStart("$g:5")
    queue.onPlayerDone("$g:5")
    assertTrue(sink.nextAsked.isEmpty())
    assertNull(queue.snapshot().itemId)
  }

  @Test fun aNextItemStoppedForDeletionIsOfferedAsSkipAndEndsTheChainIfStillReturned() {
    queue.stopItem(8) // deleted while 7 played
    sink.next = NextItem(8, rows8, 0)
    val g = finishSeven()
    // The sink is told to skip 8 (ContinuousPlay.next does); a sink that returns it anyway is refused.
    assertEquals(setOf(8L), sink.nextAsked.single().second)
    assertNull(queue.snapshot().itemId)
    assertTrue(sink.snapshots.none { it.itemId == 8L })
    assertTrue(synth.requested.all { it.startsWith("$g:") })
    assertTrue(leftover().none { it.startsWith("play-8-") })
  }

  // --- REA-46: a failure while building the next item ends the chain like no next item ---

  /** Sentences whose every read throws [t]: Utterances.fit fails on them. */
  private fun failingRows(t: Throwable) = object : AbstractList<SentenceRow>() {
    override val size = 1
    override fun get(index: Int): SentenceRow = throw t
  }

  /** Finishes 7 with nothing escaping the player's done, then checks it ended as with no next item. */
  private fun assertTheChainEndsLikeNoNextItem() {
    val g = try {
      finishSeven()
    } catch (t: Throwable) {
      throw AssertionError("escaped onPlayerDone: ${t.javaClass.simpleName}", t)
    }
    assertEquals(listOf(7L), sink.finished)
    assertEquals(listOf(7L), sink.nextAsked.map { it.first })
    assertEquals("snapshot:null:false", sink.events.last())
    val s = queue.snapshot()
    assertNull(s.itemId)
    assertFalse(s.playing)
    // The pipeline is released: the engine stopped, the player flushed and never asked to play
    // again, nothing synthesized after 7, and no file of the queue's left (AGENTS.md 3).
    assertEquals(1, synth.stops)
    assertEquals(1, player.flushes)
    val plays = player.plays
    assertTrue(synth.requested.all { it.startsWith("$g:") })
    assertTrue(leftover().all { it in player.files.map { f -> f.name } })
    // The handover is over: the queue is idle, not "playing" with nothing queued.
    synth.alive = false
    repeat(PlaybackQueue.HUNG_TICKS + 1) { assertFalse(queue.checkStall()) }
    assertFalse(queue.pause())
    assertFalse(queue.resume())
    assertEquals(0, sink.lost)
    assertEquals(plays, player.plays)
    // And a later play starts normally.
    synth.alive = true
    assertTrue(queue.load(9, rows8, 0, 2.0f))
    assertEquals(9L, queue.snapshot().itemId)
  }

  @Test fun anErrorFromNextItemEndsTheChain() {
    sink.failNext = StackOverflowError()
    assertTheChainEndsLikeNoNextItem()
  }

  @Test fun anErrorWhileFittingTheNextItemEndsTheChain() {
    sink.next = NextItem(8, failingRows(OutOfMemoryError()), 0)
    assertTheChainEndsLikeNoNextItem()
    assertTrue(sink.snapshots.none { it.itemId == 8L })
  }

  @Test fun anExceptionWhileFittingTheNextItemEndsTheChain() {
    sink.next = NextItem(8, failingRows(IllegalStateException()), 0)
    assertTheChainEndsLikeNoNextItem()
    assertTrue(sink.snapshots.none { it.itemId == 8L })
  }

  @Test fun aNextItemWithABadStartIsRefused() {
    sink.next = NextItem(8, rows8, 9)
    finishSeven()
    assertNull(queue.snapshot().itemId)
  }

  // --- R-S05: the next item is built outside the queue lock; actions during that window ---

  /** Runs [action] on another thread while the sink builds the next item; true if it finished. */
  private fun duringHandover(action: () -> Unit): () -> Boolean {
    var done = false
    sink.during = {
      val t = Thread { action(); done = true }
      t.start()
      t.join(2_000)
    }
    return { done }
  }

  @Test fun theNextItemIsBuiltWithoutHoldingTheQueueLock() {
    sink.next = NextItem(8, rows8, 0)
    var seen: PlaybackSnapshot? = null
    val done = duringHandover { seen = queue.snapshot() }
    finishSeven()
    assertTrue("the main thread waited on the lock during the handover", done())
    // Between the two items the queue still reads as 7 playing: no empty snapshot.
    assertEquals(7L, seen!!.itemId)
    assertTrue(seen!!.playing)
    assertEquals(8L, queue.snapshot().itemId)
  }

  @Test fun aPauseDuringTheHandoverEndsTheChainAndWritesNoPosition() {
    sink.next = NextItem(8, rows8, 1)
    val done = duringHandover { assertTrue(queue.pause()) }
    finishSeven()
    assertTrue(done())
    assertTrue(sink.saves.none { it.first == 8L })
    assertEquals(listOf(7L), sink.finished)
    assertNull(queue.snapshot().itemId)
    assertFalse(queue.snapshot().playing)
    assertTrue(sink.snapshots.none { it.itemId == 8L })
    assertFalse(queue.resume())
    assertTrue(leftover().none { it.startsWith("play-8-") })
  }

  @Test fun aPauseAfterTheNextItemLoadsIsAnOrdinaryHeldPause() {
    // REA-47, ADR 0011: once the next item has loaded the handover is over, so a pause holds
    // that item as ADR 0009 holds any pause (unlike a pause inside the handover window).
    sink.next = NextItem(8, rows8, 0)
    finishSeven()
    assertEquals(8L, queue.snapshot().itemId)
    val saves = sink.saves.size
    assertTrue(queue.pause())
    val s = queue.snapshot()
    assertEquals(8L, s.itemId)
    assertFalse(s.playing)
    assertEquals(Triple(8L, rows8[0].paragraphIndex, rows8[0].start), sink.saves.drop(saves).last())
    assertEquals(listOf(7L), sink.finished)
    assertTrue(queue.resume())
    assertEquals(8L, queue.snapshot().itemId)
    assertTrue(queue.snapshot().playing)
  }

  @Test fun aSwapDuringAHandoverEndsTheChain() {
    // REA-51, ADR 0011: a new speaker while the next item is built ends the chain as a pause does.
    sink.next = NextItem(8, rows8, 0)
    val fresh = FakeSynth()
    val done = duringHandover { queue.swapSynth(fresh) }
    finishSeven()
    assertTrue(done())
    assertEquals(listOf(7L), sink.finished)
    assertNull(queue.snapshot().itemId)
    assertFalse(queue.snapshot().playing)
    assertTrue(sink.snapshots.none { it.itemId == 8L })
    assertTrue(sink.saves.none { it.first == 8L })
    assertTrue(fresh.requested.isEmpty())
  }

  @Test fun aStopDuringTheHandoverEndsTheChain() {
    sink.next = NextItem(8, rows8, 0)
    var saves = -1
    val done = duringHandover { saves = sink.saves.size; queue.stop() }
    finishSeven()
    assertTrue(done())
    assertEquals(saves, sink.saves.size)
    assertNull(queue.snapshot().itemId)
    assertTrue(sink.snapshots.none { it.itemId == 8L })
  }

  @Test fun aDeletionOfTheNextItemDuringTheHandoverRefusesIt() {
    sink.next = NextItem(8, rows8, 0)
    val done = duringHandover { queue.stopItem(8) }
    val g = finishSeven()
    assertTrue(done())
    assertNull(queue.snapshot().itemId)
    assertTrue(synth.requested.all { it.startsWith("$g:") })
  }

  @Test fun aUserPlayDuringTheHandoverWins() {
    sink.next = NextItem(8, rows8, 0)
    var saves = -1
    val done = duringHandover { saves = sink.saves.size; assertTrue(queue.load(9, rows8, 2, 2.0f)) }
    finishSeven()
    assertTrue(done())
    assertEquals(9L, queue.snapshot().itemId)
    assertEquals(rows8[2], queue.snapshot().sentence)
    assertTrue(sink.snapshots.none { it.itemId == 8L })
    // The user's play saved nothing into the archived 7.
    assertTrue(sink.saves.drop(saves).none { it.first == 7L })
    assertTrue(leftover().none { it.startsWith("play-8-") })
  }

  @Test fun aRateChangeDuringTheHandoverAppliesToTheNextItemOnce() {
    sink.next = NextItem(8, rows8, 0)
    val done = duringHandover { queue.setRate(1.5f) }
    val g = finishSeven()
    assertTrue(done())
    assertEquals(listOf(2.0f, 1.5f), synth.rates)
    assertEquals(8L, queue.snapshot().itemId)
    assertEquals(1.5f, queue.snapshot().rate)
    // 7's last sentence was not synthesized again.
    assertEquals(listOf("4", "5"), indices(synth.requested.filter { it.startsWith("$g:") }))
  }

  @Test fun transportAndStallChecksAreIgnoredDuringTheHandover() {
    sink.next = NextItem(8, rows8, 0)
    synth.alive = false
    val done = duringHandover {
      assertFalse(queue.next())
      assertFalse(queue.previous())
      assertFalse(queue.backParagraph())
      repeat(PlaybackQueue.HUNG_TICKS + 1) { assertFalse(queue.checkStall()) }
    }
    finishSeven()
    assertTrue(done())
    assertEquals(0, sink.lost)
    assertEquals(8L, queue.snapshot().itemId)
    assertEquals(rows8[0], queue.snapshot().sentence)
  }

  // --- R-S05 through Read Me's real player (ADR 0012): WAV files on disk, an output whose play
  // head the test moves, and the handover on the player's own thread ---

  /** An AudioTrack stand-in that takes everything written; the test moves its play head. */
  private class Out(override val format: PcmFormat) : AudioOut {
    var written = 0L
    var head = 0L
    var playing = false
    var released = false
    override val startFrames = 0
    /** REA-52: a write error (a negative return, as AudioTrack's) while this holds. */
    var failWhen: (Out) -> Boolean = { false }
    /** A non-blocking track's room in frames: it takes only what fits (REA-52). */
    var cap = Long.MAX_VALUE
    override fun write(buf: ByteArray, off: Int, len: Int): Int {
      if (failWhen(this)) return -1
      val frames = minOf((len / format.frameBytes).toLong(), (cap - (written - head)).coerceAtLeast(0))
      written += frames
      return (frames * format.frameBytes).toInt()
    }
    override fun play() { playing = true }
    override fun pause() { playing = false }
    override fun release() { released = true; playing = false }
    override fun head() = head
    override fun underruns() = 0
  }

  /** A PCM16 mono WAV of [ms] frames at 1000 Hz: one frame per millisecond. */
  private fun writeWav(f: File, ms: Int) {
    val data = ByteArray(ms * 2) { if (it % 2 == 0) 0x10 else 0 }
    val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
      .put("RIFF".toByteArray()).putInt(36 + data.size).put("WAVE".toByteArray())
      .put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(1_000).putInt(2_000)
      .putShort(2).putShort(16)
      .put("data".toByteArray()).putInt(data.size).array()
    f.writeBytes(h + data)
  }

  /** A queue on a real TrackPlayer, stepped by the test as its writer thread would be. */
  private inner class Rig(private val openOut: (PcmFormat) -> Out? = { f -> Out(f) }) {
    val outs = mutableListOf<Out>()
    val engine = FakeSynth()
    private val answered = HashSet<String>()
    lateinit var q: PlaybackQueue
    val player = TrackPlayer(
      object : PlayerEvents {
        override fun onPlayerStart(id: String) = q.onPlayerStart(id)
        override fun onPlayerDone(id: String) = q.onPlayerDone(id)
        override fun onPlayerError(id: String) = q.onPlayerError(id)
      },
      { f -> openOut(f)?.also { outs += it } },
      padMs = 40,
    )

    init {
      q = PlaybackQueue(engine, player, PlaybackFiles(dir), sink, { now }, aheadMs = 0, chunkChars = 0)
    }

    /** The engine writes every sentence asked for and not yet answered, [ms] of audio each. */
    fun synthesizeAll(ms: Int = 1_500) {
      for (id in engine.requested.toList()) {
        if (!answered.add(id)) continue
        writeWav(engine.files.getValue(id), ms)
        q.onSynthDone(id)
      }
    }

    private val failAll = TrackPlayer::class.java.getDeclaredMethod("failAll").apply { isAccessible = true }

    /** As TrackPlayer.loop does: a throw from step() reports every sentence it had buffered. */
    fun step() {
      try {
        player.step()
      } catch (t: Throwable) {
        failAll.invoke(player)
      }
    }

    /** Synthesis and playback in turn while the queue plays, as a phone would. */
    fun drive(rounds: Int, ms: Long = 250) {
      for (k in 0 until rounds) {
        if (!q.snapshot().playing) break
        synthesizeAll()
        play(ms)
      }
    }

    /** Lets the output play [ms], 10 ms per writer step. */
    fun play(ms: Long) {
      repeat(3) { step() }
      var left = ms
      while (left > 0) {
        val n = minOf(left, 10L)
        outs.lastOrNull()?.let { if (it.playing) it.head = minOf(it.head + n, it.written) }
        left -= n
        step()
      }
      repeat(3) { step() }
    }
  }

  @Test fun aHandoverThroughTheRealPlayerDeletesTheFinishedItemsFilesAndPlaysTheNext() {
    val r = Rig()
    sink.next = NextItem(8, rows8, 0)
    r.q.load(7, rows, 4, 2.0f)
    r.synthesizeAll()
    r.play(3_100)
    // 7 was heard to the end and archived; 8 was built and its synthesis asked for.
    assertEquals(listOf(7L), sink.finished)
    assertEquals(8L, r.q.snapshot().itemId)
    assertTrue(r.q.snapshot().playing)
    assertTrue("7's output is released", r.outs.single().released)
    assertTrue("no file of 7 is left", leftover().none { it.startsWith("play-7-") })
    val g2 = r.engine.requested.last().substringBefore(':')
    assertEquals(listOf("play-8-$g2-0-0.wav", "play-8-$g2-0-5.wav", "play-8-$g2-1-0.wav"), leftover())
    // 8 plays on a new output once its audio is ready; its first frame reports the handover.
    now = 400
    r.synthesizeAll()
    r.play(1)
    assertEquals(2, r.outs.size)
    assertEquals(listOf(Triple(7L, 8L, 400L)), sink.handovers)
    assertEquals(rows8[0], r.q.snapshot().sentence)
    r.play(4_600)
    // 8 ends with nothing after it: the chain ends, and nothing is left on disk or playing.
    assertEquals(listOf(7L, 8L), sink.finished)
    assertNull(r.q.snapshot().itemId)
    assertTrue(leftover().isEmpty())
    assertTrue(r.outs.all { it.released })
  }

  @Test fun aFailedHandoverThroughTheRealPlayerLeavesNoFilesAndNoOutput() {
    // REA-46: the player's writer catches Exception only; an Error escaping its done event would
    // end the writer thread. The queue catches it, and the chain ends as with no next item.
    val r = Rig()
    sink.failNext = OutOfMemoryError()
    r.q.load(7, rows, 4, 2.0f)
    r.synthesizeAll()
    try {
      r.play(3_100)
    } catch (t: Throwable) {
      throw AssertionError("escaped the player's done: ${t.javaClass.simpleName}", t)
    }
    assertEquals(listOf(7L), sink.finished)
    assertEquals(listOf(7L), sink.nextAsked.map { it.first })
    assertNull(r.q.snapshot().itemId)
    assertFalse(r.q.snapshot().playing)
    assertTrue(leftover().isEmpty())
    assertTrue(r.outs.single().released)
    assertEquals(2, r.engine.requested.size) // nothing asked for after 7
    // The player still works: a later play opens a new output and is heard.
    assertTrue(r.q.load(9, rows8, 0, 2.0f))
    r.synthesizeAll()
    r.play(1)
    assertEquals(2, r.outs.size)
    assertTrue(r.outs.last().playing)
    assertEquals(rows8[0], r.q.snapshot().sentence)
  }

  // --- REA-52: the output fails, the engine does not (real TrackPlayer, real queue) ---

  private fun rigPaused(r: Rig, at: Int) {
    assertFalse(r.q.snapshot().playing)
    assertTrue("not archived", sink.finished.isEmpty())
    assertEquals(0, sink.lost)
    assertEquals(Triple(7L, at, 0), sink.saves.last())
    assertTrue("nothing saved past $at: ${sink.saves}", sink.saves.all { it.second <= at })
  }

  @Test fun anOutputThatNeverOpensPausesAtTheFirstSentenceAfterThreeTries() {
    val r = Rig { null }
    r.q.load(7, longRows(20), 0, 1.0f)
    r.drive(20)
    rigPaused(r, 0)
    assertEquals(3, r.q.takeStats().errors)
    assertTrue(leftover().isEmpty())
  }

  @Test fun anOutputThatPlaysItsFirstFramesThenFailsPausesInsteadOfReadingTheItemThrough() {
    val r = Rig { f -> Out(f).also { o -> o.cap = 500; o.failWhen = { it.head > 0 } } }
    r.q.load(7, longRows(20), 0, 1.0f)
    r.drive(20)
    rigPaused(r, 0)
    assertEquals(3, r.outs.size)
  }

  @Test fun aTransientWriterFailureReplaysTheSentenceAndSkipsNothing() {
    var opened = 0
    val r = Rig { f -> Out(f).also { o -> o.cap = 500; if (opened++ == 0) o.failWhen = { it.head >= 200 } } }
    r.q.load(7, longRows(3), 0, 1.0f)
    r.synthesizeAll()
    r.play(600) // sentence 0 is heard for 200 ms, then the write fails with 1-2 sentences buffered
    assertTrue(r.q.snapshot().playing)
    assertTrue("nothing saved past the sentence not finished: ${sink.saves}", sink.saves.all { it.second == 0 })
    val g0 = r.engine.requested.first().substringBefore(':')
    val fresh = r.engine.requested.filter { !it.startsWith("$g0:") }
    assertEquals("0", fresh.first().substringAfter(':')) // asked for again, first
    assertTrue("the failed output is released", r.outs.first().released)
    repeat(8) { r.synthesizeAll(); r.play(1_000) }
    assertEquals(listOf(7L), sink.finished)
    assertEquals(1, r.q.takeStats().errors)
    assertTrue(leftover().isEmpty())
    assertEquals(2, r.outs.size)
  }

  @Test fun aFileRefusedOnOpenWhileAnEarlierSentencePlaysRestartsAtTheEarlierOne() {
    val r = Rig()
    r.q.load(7, longRows(4), 0, 1.0f)
    val g = r.engine.requested.last().substringBefore(':')
    // Sentence 1's file is not a WAV: reported at once, before sentence 0 (earlier) is heard.
    for (id in r.engine.requested.toList()) {
      if (id.endsWith(":1")) r.engine.files.getValue(id).writeText("not a wav") else writeWav(r.engine.files.getValue(id), 1_500)
      r.q.onSynthDone(id)
    }
    r.play(300)
    assertTrue(r.q.snapshot().playing)
    assertEquals(0, r.q.snapshot().sentence?.paragraphIndex)
    assertTrue(sink.saves.all { it.second == 0 })
    val again = r.engine.requested.filter { !it.startsWith("$g:") }
    assertEquals("0", again.first().substringAfter(':'))
  }

  @Test fun sentencesHeardBetweenOutputFailuresEndTheRun() {
    // Each output plays one sentence to its end before failing: never three without progress.
    val r = Rig { f -> Out(f).also { o -> o.failWhen = { it.head >= 1_600 } } }
    r.q.load(7, longRows(6), 0, 1.0f)
    r.drive(40, 400)
    assertEquals(listOf(7L), sink.finished)
    assertTrue(r.outs.size > 3)
  }
}
