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
    var refuse = false
    var speaking = true
    override fun speak(id: String, text: String): Boolean {
      if (refuse) return false
      spoken += id
      return true
    }
    override fun stop() { stops++ }
    override fun setRate(rate: Float) { rates += rate }
    override fun isSpeaking() = speaking
  }

  private class FakeSink : PlaybackSink {
    val saves = mutableListOf<Triple<Long, Int, Int>>()
    val finished = mutableListOf<Long>()
    val snapshots = mutableListOf<PlaybackSnapshot>()
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

  @Test fun aRateChangeWithNoItemPublishesNothing() {
    // REA-35 #1: an empty snapshot told PlaybackService the item had ended, and it dropped the
    // play request waiting for a cold engine. The next load reads the rate from Settings.
    queue.setRate(2.1f)
    assertTrue(sink.snapshots.isEmpty())
    assertEquals(2.1f, queue.snapshot().rate)
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

  @Test fun anItemStoppedForDeletionCannotBeLoadedAfterwards() {
    // REA-18: deleteItem stops the queue, then deletes; the service may have checked the
    // item exists just before and load it just after.
    queue.stopItem(7)
    assertFalse(queue.load(7, rows, 0, 2.0f))
    assertTrue(speaker.spoken.isEmpty())
    assertNull(queue.snapshot().itemId)
  }

  @Test fun stoppingAnotherItemLeavesPlaybackAlone() {
    queue.load(7, rows, 0, 2.0f)
    queue.stopItem(8)
    assertTrue(queue.snapshot().playing)
    assertEquals(7L, queue.snapshot().itemId)
    assertTrue(queue.load(9, rows, 0, 2.0f))
  }

  @Test fun aFailedSaveStillQueuesTheNextSentenceAndIsCounted() {
    // REA-18: Store.savePosition rethrows anything but a constraint error; a throw before the
    // top-up left the queue playing with nothing queued.
    queue.load(7, rows, 0, 2.0f)
    val g = gen(lastId())
    sink.failSaves = true
    queue.onDone("$g:0")
    assertEquals("$g:3", lastId())
    assertTrue(queue.snapshot().playing)
    assertTrue(queue.pause())
    assertFalse(queue.snapshot().playing)
    assertEquals(2, queue.takeStats().saveErrors)
  }

  @Test fun threeErrorsInARowPauseAtTheFirstFailedSentence() {
    queue.load(7, rows, 0, 2.0f)
    val g = gen(lastId())
    queue.onError("$g:0")
    queue.onError("$g:1")
    queue.onError("$g:2")
    assertFalse(queue.snapshot().playing)
    assertEquals(7L, queue.snapshot().itemId)
    assertEquals(Triple(7L, 0, 0), sink.saves.last()) // sentence 0 starts paragraph 0 at 0
    assertTrue(sink.finished.isEmpty())
  }

  @Test fun anErrorBetweenGoodSentencesStillAdvances() {
    queue.load(7, rows, 0, 2.0f)
    val g = gen(lastId())
    queue.onError("$g:0")
    queue.onStart("$g:1")
    queue.onDone("$g:1")
    queue.onError("$g:2")
    assertTrue(queue.snapshot().playing)
  }

  @Test fun errorsAtTheEndPauseInsteadOfArchiving() {
    queue.load(7, rows, 4, 2.0f)
    val g = gen(lastId())
    queue.onDone("$g:4")
    queue.onError("$g:5")
    assertTrue(sink.finished.isEmpty())
    assertFalse(queue.snapshot().playing)
    assertEquals(Triple(7L, 3, 5), sink.saves.last()) // sentence 5 is paragraph 3 offset 5
  }

  @Test fun aRefusedSpeakPausesAndReportsTheEngineLost() {
    // REA-18: after the engine process died, resume "played" silently with the wake lock held.
    speaker.refuse = true
    queue.load(7, rows, 2, 2.0f)
    assertFalse(queue.snapshot().playing)
    assertEquals(1, sink.lost)
    assertEquals(Triple(7L, 1, 0), sink.saves.last())
  }

  @Test fun anEngineThatStopsSpeakingIsLostAfterThreeChecks() {
    queue.load(7, rows, 0, 2.0f)
    speaker.speaking = false
    assertFalse(queue.checkStall())
    assertFalse(queue.checkStall())
    assertTrue(queue.checkStall())
    assertFalse(queue.snapshot().playing)
    assertEquals(1, sink.lost)
  }

  @Test fun aSlowFirstSentenceIsNotAStall() {
    // A cold engine took 8 s to first audio (AGENTS.md); progress or speaking resets the count.
    queue.load(7, rows, 0, 2.0f)
    val g = gen(lastId())
    speaker.speaking = false
    queue.checkStall()
    queue.checkStall()
    queue.onStart("$g:0")
    assertFalse(queue.checkStall())
    assertFalse(queue.checkStall())
    assertTrue(queue.snapshot().playing)
    assertEquals(0, sink.lost)
  }

  @Test fun aPausedQueueIsNeverStalled() {
    queue.load(7, rows, 0, 2.0f)
    queue.pause()
    speaker.speaking = false
    repeat(5) { assertFalse(queue.checkStall()) }
  }

  @Test fun aSwappedSpeakerGetsTheRateAndTheNextResume() {
    queue.load(7, rows, 2, 1.5f)
    speaker.refuse = true
    queue.next() // refused: lost
    val fresh = FakeSpeaker()
    queue.swapSpeaker(fresh)
    assertEquals(listOf(1.5f), fresh.rates)
    assertTrue(queue.resume())
    assertEquals(3, fresh.spoken.size)
  }

  @Test fun errorsReportedAfterAStartStillCountAsARun() {
    // Final review: Android delivers onStart then onError when an engine starts an utterance
    // before failing it; resetting the run on onStart let a failing engine read silence to the end.
    queue.load(7, rows, 0, 2.0f)
    val g = gen(lastId())
    for (i in 0..2) {
      queue.onStart("$g:$i")
      queue.onError("$g:$i")
    }
    assertFalse(queue.snapshot().playing)
    assertEquals(Triple(7L, 0, 0), sink.saves.last())
  }

  @Test fun aJumpEndsTheErrorRun() {
    // Critique: two errors, Next, Next, then one error paused back at sentence 0.
    queue.load(7, rows, 0, 2.0f)
    val g = gen(lastId())
    queue.onError("$g:0")
    queue.onError("$g:1")
    queue.next()
    queue.next()
    queue.onError("${gen(lastId())}:4")
    assertTrue(queue.snapshot().playing)
  }

  @Test fun aNewItemStartsWithNoErrorRun() {
    queue.load(7, rows, 0, 2.0f)
    val g = gen(lastId())
    queue.onError("$g:0")
    queue.onError("$g:1")
    queue.pause()
    queue.load(8, rows.take(2), 0, 2.0f)
    queue.onError("${gen(lastId())}:0")
    assertTrue(queue.snapshot().playing)
    assertEquals(8L, queue.snapshot().itemId)
  }

  @Test fun aFailedArchiveStillClearsTheQueue() {
    sink.failFinish = true
    queue.load(7, rows.take(1), 0, 2.0f)
    val g = gen(lastId())
    queue.onStart("$g:0")
    queue.onDone("$g:0")
    assertEquals(null, queue.snapshot().itemId)
    assertFalse(queue.snapshot().playing)
  }

  // --- R-S05 continuous play: the sink offers the next item after the archive ---

  private val rows8 = listOf(SentenceRow(0, 0, 4, "E0."), SentenceRow(0, 5, 9, "E1."), SentenceRow(1, 0, 4, "F0."))

  /** Plays item 7's last two sentences to the end; returns the generation they ran in. */
  private fun finishSeven(): String {
    queue.load(7, rows, 4, 2.0f)
    val g = gen(lastId())
    queue.onStart("$g:4")
    queue.onDone("$g:4")
    queue.onStart("$g:5")
    queue.onDone("$g:5")
    return g
  }

  @Test fun withNoNextItemTheEndIsUnchanged() {
    finishSeven()
    assertEquals(listOf(7L), sink.finished)
    assertEquals(listOf(7L to emptySet<Long>()), sink.nextAsked)
    assertNull(queue.snapshot().itemId)
    assertFalse(queue.snapshot().playing)
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
    // A new generation from the saved start: indices 1 and 2 of item 8.
    val g2 = gen(lastId())
    assertTrue(g2 != g)
    assertEquals(listOf("1", "2"), speaker.spoken.filter { gen(it) == g2 }.map { it.substringAfter(':') })
  }

  @Test fun theHandoverKeepsTheRateWithoutApplyingItAgain() {
    sink.next = NextItem(8, rows8, 0)
    finishSeven()
    assertEquals(listOf(2.0f), speaker.rates)
    assertEquals(2.0f, queue.snapshot().rate)
  }

  @Test fun theChainGoesOnUntilNoItemIsLeft() {
    sink.next = NextItem(8, rows8, 2)
    finishSeven()
    val g = gen(lastId())
    queue.onStart("$g:2")
    queue.onDone("$g:2")
    assertEquals(listOf(7L, 8L), sink.finished)
    assertEquals(listOf(7L, 8L), sink.nextAsked.map { it.first })
    assertNull(queue.snapshot().itemId)
  }

  @Test fun theFirstSentenceOfTheNextItemReportsTheHandoverTime() {
    sink.next = NextItem(8, rows8, 0)
    finishSeven()
    now = 250
    queue.onStart("${gen(lastId())}:0")
    assertEquals(listOf(Triple(7L, 8L, 250L)), sink.handovers)
    // The handover is not an R-M07 sentence gap: only 7's sentence 4 to 5 (0 ms) is counted.
    val gaps = queue.takeStats().gaps
    assertEquals(1, gaps.count)
    assertEquals(0L, gaps.max)
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
    val g = gen(lastId())
    queue.onError("$g:5")
    assertTrue(sink.nextAsked.isEmpty())
    assertEquals(7L, queue.snapshot().itemId)
    assertFalse(queue.snapshot().playing)
  }

  @Test fun aPauseHoldsTheCurrentItemAndLateCallbacksDoNotContinue() {
    sink.next = NextItem(8, rows8, 0)
    queue.load(7, rows, 5, 2.0f)
    val g = gen(lastId())
    queue.onStart("$g:5")
    queue.pause()
    queue.onDone("$g:5") // the engine's late report for the flushed utterance
    assertTrue(sink.nextAsked.isEmpty())
    assertEquals(7L, queue.snapshot().itemId)
    assertFalse(queue.snapshot().playing)
  }

  @Test fun aStopEndsTheChain() {
    sink.next = NextItem(8, rows8, 0)
    queue.load(7, rows, 5, 2.0f)
    val g = gen(lastId())
    queue.stop()
    queue.onDone("$g:5")
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
    assertTrue(speaker.spoken.all { gen(it) == g })
  }

  // --- REA-46: a failure while building the next item ends the chain like no next item ---

  /** Sentences whose every read throws [t]: Utterances.fit fails on them. */
  private fun failingRows(t: Throwable) = object : AbstractList<SentenceRow>() {
    override val size = 1
    override fun get(index: Int): SentenceRow = throw t
  }

  /** Finishes 7 with nothing escaping onDone, then checks the queue ended as with no next item. */
  private fun assertTheChainEndsLikeNoNextItem() {
    try {
      finishSeven()
    } catch (t: Throwable) {
      throw AssertionError("escaped onDone: ${t.javaClass.simpleName}", t)
    }
    assertEquals(listOf(7L), sink.finished)
    assertEquals(listOf(7L), sink.nextAsked.map { it.first })
    assertEquals("snapshot:null:false", sink.events.last())
    val s = queue.snapshot()
    assertNull(s.itemId)
    assertFalse(s.playing)
    // The handover is over: the queue is idle, not "playing" with nothing queued.
    speaker.speaking = false
    repeat(PlaybackQueue.STALL_TICKS + 1) { assertFalse(queue.checkStall()) }
    assertFalse(queue.pause())
    assertFalse(queue.resume())
    assertEquals(0, sink.lost)
    // And a later play starts normally.
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
    assertTrue(speaker.spoken.all { gen(it) == g })
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
  }

  @Test fun aRateChangeDuringTheHandoverAppliesToTheNextItemOnce() {
    sink.next = NextItem(8, rows8, 0)
    val done = duringHandover { queue.setRate(1.5f) }
    val g = finishSeven()
    assertTrue(done())
    assertEquals(listOf(2.0f, 1.5f), speaker.rates)
    assertEquals(8L, queue.snapshot().itemId)
    assertEquals(1.5f, queue.snapshot().rate)
    // 7's last sentence was not restarted.
    assertEquals(listOf("4", "5"), speaker.spoken.filter { gen(it) == g }.map { it.substringAfter(':') })
  }

  @Test fun transportAndStallChecksAreIgnoredDuringTheHandover() {
    sink.next = NextItem(8, rows8, 0)
    speaker.speaking = false
    val done = duringHandover {
      assertFalse(queue.next())
      assertFalse(queue.previous())
      assertFalse(queue.backParagraph())
      repeat(PlaybackQueue.STALL_TICKS + 1) { assertFalse(queue.checkStall()) }
    }
    finishSeven()
    assertTrue(done())
    assertEquals(0, sink.lost)
    assertEquals(8L, queue.snapshot().itemId)
    assertEquals(rows8[0], queue.snapshot().sentence)
  }
}
