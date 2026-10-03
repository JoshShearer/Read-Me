package io.loopstring.readme.bridge

import android.content.Context
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.nio.file.Files
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowTextToSpeech

@RunWith(RobolectricTestRunner::class)
class TtsSynthTest {
  private val ctx = ApplicationProvider.getApplicationContext<Context>()
  private val out = File(Files.createTempDirectory("c").toFile(), "x.wav")
  private val worker = Executors.newSingleThreadExecutor()
  private val logs = mutableListOf<String>()
  private var clock = 1_000_000L

  @After fun tearDown() {
    worker.shutdownNow()
    ShadowTextToSpeech.reset()
  }

  @Test fun beforeTheEngineIsReadyItIsNotReadyAndRefusesWork() {
    val s = TtsSynth(ctx, null)
    shadowOf(Looper.getMainLooper()).idle()
    assertFalse(s.ready)
    assertEquals(null, s.voice)
    assertEquals(SynthResult.FAILED, s.synthesize("Hello.", 1.0f, out) { true })
    s.cancel() // nothing in flight: a no-op
    s.shutdown()
  }

  @Test fun maxCharsIsTheEngineLimitLessHeadroom() {
    val s = TtsSynth(ctx, null)
    assertEquals(io.loopstring.readme.playback.TtsSpeaker.maxChars(), s.maxChars)
    s.shutdown()
  }

  @Test fun aLiveEngineSynthesizes() {
    val s = readySynth()
    engineWillSucceed()
    assertEquals(SynthResult.OK, run(s) { true })
    assertEquals(1, instances.size)
    s.shutdown()
  }

  @Test fun aLostEngineIsRebuiltAndTheRequestRetriedOnce() {
    val s = readySynth()
    val first = ShadowTextToSpeech.getLastTextToSpeechInstance()
    kill(first)
    assertEquals(SynthResult.OK, run(s) { true })
    val second = ShadowTextToSpeech.getLastTextToSpeechInstance()
    assertNotSame(first, second)
    assertTrue("the dead binding is released", shadowOf(first).isShutdown)
    assertEquals("Hello.", shadowOf(second).lastSynthesizeToFileText)
    assertTrue(s.ready)
    assertEquals(listOf("bridge engine lost; rebinding", "bridge engine rebound"), logs)
    s.shutdown()
  }

  @Test fun aFailedRebindIsNotRetriedInsideTheGapButIsAfterIt() {
    val s = readySynth()
    kill(ShadowTextToSpeech.getLastTextToSpeechInstance())
    // The rebound engine comes up unusable (no voice to pin), decided before it initialises so
    // the worker thread cannot race the harness.
    ShadowTextToSpeech.reset()
    instances.clear()
    instances += placeholder
    assertEquals(SynthResult.FAILED, run(s) { true })
    val rebuilt = ShadowTextToSpeech.getLastTextToSpeechInstance()
    assertNotSame(placeholder, rebuilt)
    assertFalse("a failed rebind is not reported ready", s.ready)

    clock += TtsSynth.REBIND_GAP_MS - 1
    assertFalse(s.ready)
    assertEquals(SynthResult.FAILED, run(s) { true })
    assertSame("no rebind inside the gap", rebuilt, ShadowTextToSpeech.getLastTextToSpeechInstance())

    // Past the gap it is ready again and the next request rebinds: never stuck for good.
    clock += 1
    ShadowTextToSpeech.addVoice(voice("en-us-x-test-local"))
    assertTrue(s.ready)
    assertEquals(SynthResult.OK, run(s) { true })
    assertNotSame(rebuilt, ShadowTextToSpeech.getLastTextToSpeechInstance())
    s.shutdown()
  }

  @Test fun afterTheGapALostEngineIsRebuiltAgain() {
    val s = readySynth()
    kill(ShadowTextToSpeech.getLastTextToSpeechInstance())
    assertEquals(SynthResult.OK, run(s) { true })
    clock += TtsSynth.REBIND_GAP_MS
    kill(ShadowTextToSpeech.getLastTextToSpeechInstance())
    assertEquals(SynthResult.OK, run(s) { true })
    assertEquals(3, instances.size)
    s.shutdown()
  }

  @Test fun aPreemptDuringTheRebindCancelsTheRetry() {
    val s = readySynth()
    kill(ShadowTextToSpeech.getLastTextToSpeechInstance())
    var calls = 0
    // ADR 0004: playback started while the engine was rebinding.
    assertEquals(SynthResult.CANCELLED, run(s) { ++calls == 1 })
    s.shutdown()
  }

  @Test fun aRebindThatLandsOnAnotherEngineIsRefusedNotUsed() {
    val s = readySynth()
    assertEquals("en-us-x-test-local", s.voice)
    kill(ShadowTextToSpeech.getLastTextToSpeechInstance())
    // Android fell back to another engine: the pinned voice is not among its voices.
    ShadowTextToSpeech.reset()
    instances.clear()
    instances += placeholder
    ShadowTextToSpeech.addVoice(voice("zh-cn-other-local"))
    assertEquals(SynthResult.FAILED, run(s) { true })
    assertFalse(s.ready)
    assertEquals(null, ShadowTextToSpeech.getLastTextToSpeechInstance().let { shadowOf(it).lastSynthesizeToFileText })
    assertEquals("bridge engine no-voice after rebind", logs.last())

    // After the gap the next request tries again, and the right engine is back.
    clock += TtsSynth.REBIND_GAP_MS
    ShadowTextToSpeech.addVoice(voice("en-us-x-test-local"))
    assertTrue(s.ready)
    assertEquals(SynthResult.OK, run(s) { true })
    assertEquals("en-us-x-test-local", s.voice)
    s.shutdown()
  }

  @Test fun aServiceErrorFromALiveEngineIsAFailureNotARebind() {
    val s = readySynth()
    // The engine reports ERROR_SERVICE for this input but is still bound and answering.
    shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance())
      .simulateSynthesizeToFileResult(TextToSpeech.ERROR_SERVICE)
    assertEquals(SynthResult.FAILED, run(s) { true })
    assertEquals(1, instances.size)
    assertTrue(s.ready)
    assertEquals(emptyList<String>(), logs)
    s.shutdown()
  }

  @Test fun anOrdinaryEngineErrorIsNotTreatedAsALostEngine() {
    val s = readySynth()
    shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance())
      .simulateSynthesizeToFileResult(TextToSpeech.ERROR_SYNTHESIS)
    assertEquals(SynthResult.FAILED, run(s) { true })
    assertEquals(1, instances.size)
    assertTrue(s.ready)
    s.shutdown()
  }

  // --- harness ---

  private val instances = mutableListOf<TextToSpeech>()

  private fun readySynth(): TtsSynth {
    ShadowTextToSpeech.addVoice(voice("en-us-x-test-local"))
    val s = TtsSynth(ctx, null, log = { logs += it }, readyWaitMs = 5_000L, now = { clock })
    initNewEngines()
    assertTrue(s.ready)
    return s
  }

  private fun voice(name: String) =
    Voice(name, Locale.US, Voice.QUALITY_HIGH, Voice.LATENCY_NORMAL, false, emptySet())

  /** Stands in for an engine dropped by ShadowTextToSpeech.reset(), so the next one is new. */
  private val placeholder by lazy { TextToSpeech(ctx) {} }

  /** The engine's process died: it errors, and getVoice on the dead binding answers null. */
  private fun kill(tts: TextToSpeech) {
    shadowOf(tts).simulateSynthesizeToFileResult(TextToSpeech.ERROR_SERVICE)
    tts.setVoice(null)
  }

  private fun engineWillSucceed() {
    shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance()).simulateSynthesizeToFileResult(TextToSpeech.SUCCESS)
  }

  /** Brings up every TextToSpeech made since the last call, as a bound engine would. */
  private fun initNewEngines() {
    val main = shadowOf(Looper.getMainLooper())
    main.idle()
    val last = ShadowTextToSpeech.getLastTextToSpeechInstance() ?: return
    if (instances.lastOrNull() !== last) {
      val first = instances.isEmpty()
      instances += last
      if (!first) shadowOf(last).simulateSynthesizeToFileResult(TextToSpeech.SUCCESS)
      shadowOf(last).onInitListener.onInit(TextToSpeech.SUCCESS)
      main.idle()
    }
  }

  /** synthesize() blocks and never runs on the main thread; pump the main looper meanwhile. */
  private fun run(s: TtsSynth, proceed: () -> Boolean): SynthResult {
    val f: Future<SynthResult> = worker.submit<SynthResult> { s.synthesize("Hello.", 1.0f, out, proceed) }
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    while (!f.isDone && System.nanoTime() < deadline) {
      initNewEngines()
      Thread.sleep(2)
    }
    return f.get(1, TimeUnit.SECONDS)
  }
}
