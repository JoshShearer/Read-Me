package io.loopstring.readme.playback

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * REA-31: Read Me's own player, driven one step at a time against a fake output whose play
 * head the test moves, as the audio hardware would.
 */
class TrackPlayerTest {
  /** An AudioTrack stand-in: a ring of [capacity] frames; the head moves only while playing. */
  private class FakeOut(override val format: PcmFormat, val capacity: Int = 1_000) : AudioOut {
    val bytes = ByteArrayOutputStream()
    var playing = false
    var released = false
    var head = 0L
    var underrunCount = 0
    var onRelease: () -> Unit = {}
    var throwOnWrite: Throwable? = null
    override var startFrames = 0
    val written get() = bytes.size().toLong() / format.frameBytes
    override fun write(buf: ByteArray, off: Int, len: Int): Int {
      throwOnWrite?.let { throwOnWrite = null; throw it }
      val room = ((capacity - (written - head)) * format.frameBytes).toInt()
      val n = minOf(len, room)
      bytes.write(buf, off, n)
      return n
    }
    override fun play() { playing = true }
    override fun pause() { playing = false }
    override fun release() { released = true; onRelease() }
    override fun head() = head
    // AudioTrack.getUnderrunCount on a released track throws IllegalStateException (AOSP JNI).
    override fun underruns() = if (released) throw IllegalStateException("released") else underrunCount
    fun advance(frames: Long) { if (playing) head = minOf(head + frames, written) }
    /** Frame values written, as 16-bit mono samples. */
    fun samples(): ShortArray {
      val b = ByteBuffer.wrap(bytes.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
      return ShortArray(b.remaining() / 2) { b.short }
    }
  }

  private class Events : PlayerEvents {
    val log = mutableListOf<String>()
    var onEvent: (String) -> Unit = {}
    override fun onPlayerStart(id: String) { log += "start $id"; onEvent("start $id") }
    override fun onPlayerDone(id: String) { log += "done $id"; onEvent("done $id") }
    override fun onPlayerError(id: String) { log += "error $id"; onEvent("error $id") }
  }

  private val dir = Files.createTempDirectory("tp").toFile()
  private val outs = mutableListOf<FakeOut>()
  private val events = Events()
  private val mono = PcmFormat(1_000, 1, PcmFormat.Encoding.PCM_16) // 1 frame per ms
  private val player = TrackPlayer(events, { f -> FakeOut(f).also { outs += it } }, padMs = 40)
  private val out get() = outs.last()

  /** A PCM16 mono WAV of [frames] samples all equal to [value]. */
  private fun wav(name: String, frames: Int, value: Short = 1000, rate: Int = 1_000): File {
    val data = ByteBuffer.allocate(frames * 2).order(ByteOrder.LITTLE_ENDIAN).apply { repeat(frames) { putShort(value) } }.array()
    val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
      .put("RIFF".toByteArray()).putInt(36 + data.size).put("WAVE".toByteArray())
      .put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
      .put("data".toByteArray()).putInt(data.size).array()
    return File(dir, name).apply { writeBytes(h + data) }
  }

  private fun steps(n: Int = 5) = repeat(n) { player.step() }

  @Test fun aSentenceStartsWhenItsFirstFramePlaysAndIsDoneAtItsLast() {
    val f = wav("a.wav", 300)
    player.enqueue("0:0", f)
    player.play()
    steps()
    assertEquals(300L, out.written)
    assertTrue(out.playing)
    assertTrue(events.log.isEmpty()) // nothing has been heard yet
    assertFalse("deleted once fully written", f.exists())
    out.advance(1); steps(1)
    assertEquals(listOf("start 0:0"), events.log)
    out.advance(298); steps(1)
    assertEquals(listOf("start 0:0"), events.log)
    out.advance(1); steps(1)
    assertEquals(listOf("start 0:0", "done 0:0"), events.log)
  }

  @Test fun theNextSentenceFollowsWithNoSilenceWhenItIsReady() {
    player.enqueue("0:0", wav("a.wav", 300))
    player.enqueue("0:1", wav("b.wav", 200))
    player.play()
    steps()
    assertEquals(500L, out.written)
    out.advance(301); steps(1)
    assertEquals(listOf("start 0:0", "done 0:0", "start 0:1"), events.log)
    assertTrue(out.samples().all { it == 1000.toShort() })
  }

  @Test fun aStarvedTrackIsFedSilenceSoTheGapIsHeardAndMeasured() {
    // Without it the end of a sentence waits in the buffer for the next one, and the gap
    // between them would read as zero however long the engine took.
    player.enqueue("0:0", wav("a.wav", 100))
    player.play()
    steps()
    assertEquals(100L, out.written) // 100 ms buffered: no silence needed yet
    out.advance(100); steps(1)
    assertEquals(listOf("start 0:0", "done 0:0"), events.log)
    assertEquals(140L, out.written) // then 40 ms of silence keep the output running
    out.advance(40); steps(1) // the engine is still writing the next sentence: more silence
    val silent = out.written - 100
    assertTrue(silent >= 80)
    val speechAt = out.written // the next sentence goes after the silence already buffered
    player.enqueue("0:1", wav("b.wav", 100))
    steps()
    out.advance(speechAt - out.head); steps(1)
    assertEquals(listOf("start 0:0", "done 0:0"), events.log) // the silence is not speech
    out.advance(1); steps(1)
    assertEquals("start 0:1", events.log.last())
  }

  @Test fun pauseHoldsTheSentenceAndPlayGoesOnFromTheSameFrame() {
    // D1: a true pause. Nothing is rewritten or restarted.
    player.enqueue("0:0", wav("a.wav", 300))
    player.play()
    steps()
    out.advance(120); steps(1)
    player.pause()
    steps()
    assertFalse(out.playing)
    val written = out.written
    out.advance(100)
    assertEquals(120L, out.head)
    steps()
    assertEquals("no silence is written while paused", written, out.written)
    player.play()
    steps(1)
    assertTrue(out.playing)
    out.advance(180); steps(1)
    assertEquals(listOf("start 0:0", "done 0:0"), events.log)
    assertEquals(1, outs.size)
  }

  @Test fun flushDiscardsEverythingAndDeletesTheFiles() {
    val a = wav("a.wav", 3_000) // more than the output holds: still being written
    val b = wav("b.wav", 100)
    player.enqueue("0:0", a)
    player.enqueue("0:1", b)
    player.play()
    steps()
    out.advance(10); steps(1)
    val first = out
    player.flush()
    assertFalse("a queued file is deleted at once", b.exists())
    steps(1)
    assertTrue(first.released)
    assertFalse("the file being written is deleted", a.exists())
    assertEquals(listOf("start 0:0"), events.log)
    player.enqueue("1:0", wav("c.wav", 100))
    player.play()
    steps()
    assertTrue(out !== first)
    out.advance(100); steps(1)
    assertEquals(listOf("start 0:0", "start 1:0", "done 1:0"), events.log)
  }

  @Test fun aFlushFromInsideAnEventStopsTheRestOfThatRound() {
    // The queue reacts to "done" on this thread, and may flush (the item ended, a rate change).
    player.enqueue("0:0", wav("a.wav", 100))
    player.enqueue("0:1", wav("b.wav", 100))
    player.play()
    steps()
    events.onEvent = { if (it == "done 0:0") player.flush() }
    out.advance(150); steps(1)
    assertEquals(listOf("start 0:0", "done 0:0"), events.log)
    steps(2)
    assertEquals(listOf("start 0:0", "done 0:0"), events.log)
  }

  @Test fun anUnreadableFileIsAnErrorAndTheNextOnePlays() {
    val bad = File(dir, "bad.wav").apply { writeText("not audio") }
    player.enqueue("0:0", bad)
    player.enqueue("0:1", wav("b.wav", 100))
    player.play()
    steps()
    assertFalse(bad.exists())
    out.advance(100); steps(1)
    assertEquals(listOf("error 0:0", "start 0:1", "done 0:1"), events.log)
  }

  @Test fun aMissingFileIsAnError() {
    player.enqueue("0:0", File(dir, "gone.wav"))
    player.play()
    steps()
    assertEquals(listOf("error 0:0"), events.log)
  }

  @Test fun anotherFormatWaitsForTheLastSentenceThenOpensANewOutput() {
    player.enqueue("0:0", wav("a.wav", 100))
    player.enqueue("1:0", wav("b.wav", 200, rate = 2_000))
    player.play()
    steps()
    assertEquals(1, outs.size)
    out.advance(100); steps()
    assertEquals(2, outs.size)
    assertTrue(outs[0].released)
    assertEquals(2_000, out.format.sampleRate)
    assertTrue(out.playing)
    out.advance(200); steps(1)
    assertEquals(listOf("start 0:0", "done 0:0", "start 1:0", "done 1:0"), events.log)
  }

  @Test fun progressCountsSpeechFramesOnlyAndUnderrunsAreTaken() {
    player.enqueue("0:0", wav("a.wav", 100))
    player.play()
    steps()
    out.advance(60); steps(1)
    assertEquals(60L, player.progress())
    out.advance(100); steps(1) // 40 of speech, then silence
    assertEquals(100L, player.progress())
    out.underrunCount = 2
    assertEquals(2, player.takeUnderruns())
    assertEquals(0, player.takeUnderruns())
  }

  @Test fun underrunsAreNeverReadFromAReleasedOutput() {
    // Critique F3: the main thread's takeUnderruns raced the writer releasing the track at an
    // item's end. Here the read lands inside release(), the window the race hits.
    player.enqueue("0:0", wav("a.wav", 100))
    player.play()
    steps()
    out.underrunCount = 3
    var read: Result<Int>? = null
    out.onRelease = { read = runCatching { player.takeUnderruns() } }
    player.flush()
    steps(1)
    assertTrue(out.released)
    assertEquals(3, read!!.getOrThrow())
  }

  @Test fun anErrorOnTheWriterThreadFailsTheSentenceAndTheThreadGoesOn() {
    // Critique F4: the writer caught Exception only, so an Error ended the thread silently and
    // the queue later blamed the engine.
    val p = TrackPlayer(events, { f -> FakeOut(f).also { if (outs.isEmpty()) it.throwOnWrite = StackOverflowError(); outs += it } }, padMs = 40)
    p.start()
    try {
      p.enqueue("0:0", wav("a.wav", 100))
      p.play()
      val deadline = System.currentTimeMillis() + 2_000
      while ("error 0:0" !in synchronized(events.log) { events.log.toList() } && System.currentTimeMillis() < deadline) Thread.sleep(10)
      assertTrue(events.log.contains("error 0:0"))
      p.enqueue("0:1", wav("b.wav", 100))
      while ((outs.size < 2 || outs.last().written == 0L) && System.currentTimeMillis() < deadline) Thread.sleep(10)
      assertEquals(2, outs.size)
      assertTrue(outs.last().written > 0)
    } finally {
      p.release()
    }
  }

  @Test fun aShortFirstSentenceIsPaddedUntilTheOutputCanStart() {
    // An AudioTrack starts only once its start threshold is buffered; a sentence shorter than
    // that would otherwise sit unheard until the next one arrived.
    val start = TrackPlayer(events, { f -> FakeOut(f).also { it.startFrames = 300; outs += it } }, padMs = 40)
    start.enqueue("0:0", wav("a.wav", 100))
    start.play()
    repeat(3) { start.step() }
    assertEquals(300L, out.written)
    out.advance(100); start.step()
    assertEquals(listOf("start 0:0", "done 0:0"), events.log)
    out.advance(200); start.step()
    // Started: from now on 40 ms buffered is enough.
    assertEquals(340L, out.written)
  }

  @Test fun nothingIsOpenedOrPlayedUntilThereIsAudio() {
    player.play()
    steps()
    assertTrue(outs.isEmpty())
  }

  @Test fun releaseDeletesQueuedFiles() {
    val a = wav("a.wav", 100)
    player.enqueue("0:0", a)
    player.release()
    assertFalse(a.exists())
  }
}
