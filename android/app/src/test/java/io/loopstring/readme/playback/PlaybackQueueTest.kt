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
