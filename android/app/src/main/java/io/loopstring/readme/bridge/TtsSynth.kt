package io.loopstring.readme.bridge

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import io.loopstring.readme.playback.TtsSpeaker
import io.loopstring.readme.playback.VoicePicker
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The bridge's own TextToSpeech (AGENTS.md 13), separate from playback's TtsSpeaker. Same voice
 * rule (AGENTS.md 5). setSpeechRate on this instance is the only rate lever (AGENTS.md 9);
 * SPIKE-06 saw no rate leak between instances. Construct on the main thread.
 *
 * REA-29: when the engine's process dies (EMUI kills a frozen engine within minutes of screen
 * off), TextToSpeech does not rebind by itself and every later request is refused. A request
 * that finds the engine gone rebinds it, which restarts the engine, and is tried once more.
 * A rebind must come back with the same voice: when the engine cannot be bound, Android falls
 * back to another installed engine (on the Huawei tablet, iFlytek), and a voice the user did
 * not choose is worse than a 503.
 */
class TtsSynth(
  context: Context,
  private val preferredVoice: String?,
  private val log: (String) -> Unit = {},
  private val readyWaitMs: Long = READY_WAIT_MS,
  private val now: () -> Long = SystemClock::elapsedRealtime,
) : Synthesizer {
  private val app = context.applicationContext
  private val main = Handler(Looper.getMainLooper())
  private val power = app.getSystemService(PowerManager::class.java)
  private val wait = SynthWait()
  private val seq = AtomicInteger()
  private val rebindLock = Any()
  private var lastRebindAt = NEVER // guarded by rebindLock
  @Volatile private var closed = false
  @Volatile private var current = Engine(null)

  override val engine: String get() = current.name
  override val voice: String? get() = current.voice
  override val maxChars: Int = TtsSpeaker.maxChars()
  /**
   * False while a lost engine waits out [REBIND_GAP_MS], so /health says so and requests get
   * 503; true again after it, when the next request rebinds.
   */
  override val ready: Boolean get() {
    val e = current
    return if (e.lost) rebindAllowed() else e.status == TtsSpeaker.EngineStatus.READY
  }

  private fun rebindAllowed() = synchronized(rebindLock) {
    lastRebindAt == NEVER || now() - lastRebindAt >= REBIND_GAP_MS
  }

  /** The engine and voice a rebind must come back with. */
  private class Pin(val engine: String, val voice: String)

  /** One TextToSpeech binding, made on the main thread; a rebind's carries a [pin]. */
  private inner class Engine(val pin: Pin?) {
    @Volatile var status = TtsSpeaker.EngineStatus.PENDING
    @Volatile var name = "unknown"
    @Volatile var voice: String? = null
    @Volatile var lost = false
    val settled = CountDownLatch(1)

    // onInit can run inside the constructor before `tts` is assigned (SPIKE-05): defer it.
    val tts = TextToSpeech(app, { s -> main.post { onInit(s) } }, pin?.engine)

    private fun onInit(result: Int) {
      status = choose(result)
      settled.countDown()
    }

    private fun choose(result: Int): TtsSpeaker.EngineStatus {
      if (result != TextToSpeech.SUCCESS) return TtsSpeaker.EngineStatus.NO_ENGINE
      name = runCatching { tts.defaultEngine }.getOrNull() ?: "unknown"
      val v = if (pin == null) TtsSpeaker.chooseVoice(tts, preferredVoice) else pinned(pin)
      if (v == null || tts.setVoice(v) != TextToSpeech.SUCCESS) return TtsSpeaker.EngineStatus.NO_VOICE
      voice = v.name
      tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
        override fun onStart(id: String?) {}
        override fun onDone(id: String?) { if (id != null) wait.finish(id, SynthResult.OK) }
        @Deprecated("Deprecated in Java")
        override fun onError(id: String?) { if (id != null) wait.finish(id, SynthResult.FAILED) }
        override fun onError(id: String?, errorCode: Int) {
          if (id == null) return
          wait.finish(id, if (errorCode == TextToSpeech.ERROR_SERVICE) SynthResult.LOST else SynthResult.FAILED)
        }
        override fun onStop(id: String?, interrupted: Boolean) { if (id != null) wait.finish(id, SynthResult.CANCELLED) }
      })
      return TtsSpeaker.EngineStatus.READY
    }

    /** Null when this binding does not offer the pinned voice: Android bound another engine. */
    private fun pinned(pin: Pin): Voice? {
      val voices = runCatching { tts.voices }.getOrNull().orEmpty()
      return voices.firstOrNull { it.name == pin.voice && VoicePicker.usable(TtsSpeaker.info(it)) }
    }

    /** getVoice goes to the engine; with the binding gone it answers null. */
    fun alive(): Boolean = runCatching { tts.voice }.getOrNull() != null

    fun shutdown() {
      runCatching { tts.stop() }
      runCatching { tts.shutdown() }
    }
  }

  override fun synthesize(text: String, rate: Float, out: File, proceed: () -> Boolean): SynthResult {
    val e = current
    if (!ready) return SynthResult.FAILED
    // A screen-off request from Obsidian must finish, rebind included; held for this request only.
    val lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ReadMe:bridge")
      .apply { setReferenceCounted(false); acquire(2 * TIMEOUT_MS + 2 * readyWaitMs + 5_000) }
    try {
      // A rebind that settled late is lost but may be fine now; use it rather than restart it.
      val usable = !e.lost || (e.status == TtsSpeaker.EngineStatus.READY && e.alive())
      val r = if (usable) attempt(e, text, rate, out, proceed) else SynthResult.LOST
      if (r != SynthResult.LOST) {
        if (r == SynthResult.OK) e.lost = false
        return r
      }
      val fresh = rebind(e) ?: return SynthResult.FAILED
      val again = attempt(fresh, text, rate, out, proceed)
      if (again == SynthResult.LOST) fresh.lost = true
      return if (again == SynthResult.LOST) SynthResult.FAILED else again
    } finally {
      if (lock.isHeld) lock.release()
    }
  }

  /** LOST only when the engine is really gone: a live engine's refusal or error is FAILED. */
  private fun attempt(e: Engine, text: String, rate: Float, out: File, proceed: () -> Boolean): SynthResult {
    val id = "bridge-${seq.incrementAndGet()}"
    wait.begin(id)
    // ADR 0004: a preempt before begin() found nothing to cancel; this catches it.
    if (!proceed()) {
      wait.cancel()
      wait.await(0)
      return SynthResult.CANCELLED
    }
    e.tts.setSpeechRate(rate)
    // A dead binding refuses at once; that is how the next request after a kill finds out.
    if (e.tts.synthesizeToFile(text, Bundle(), out, id) != TextToSpeech.SUCCESS) {
      wait.cancel()
      wait.await(0)
      return if (e.alive()) SynthResult.FAILED else SynthResult.LOST
    }
    val r = wait.await(TIMEOUT_MS, ALIVE_CHECK_MS) { e.alive() }
    // A cancel can land before the engine took the request; stop it now that it has. A lost
    // engine is stopped too, in case it was not gone after all and is still writing [out].
    if (r == SynthResult.TIMEOUT || r == SynthResult.CANCELLED || r == SynthResult.LOST) runCatching { e.tts.stop() }
    return if (r == SynthResult.LOST && e.alive()) SynthResult.FAILED else r
  }

  /**
   * Replaces [dead] with a new binding and waits for it to be ready. At most one rebind per
   * [REBIND_GAP_MS], so an engine that is truly gone (uninstalled, disabled) is not rebound in
   * a loop: requests in between fail as before.
   */
  private fun rebind(dead: Engine): Engine? {
    synchronized(rebindLock) {
      if (current !== dead) return current.takeIf { it.status == TtsSpeaker.EngineStatus.READY }
      val t = now()
      if (lastRebindAt != NEVER && t - lastRebindAt < REBIND_GAP_MS) {
        dead.lost = true
        log("bridge engine lost; not rebinding again yet")
        return null
      }
      lastRebindAt = t
    }
    dead.lost = true
    log("bridge engine lost; rebinding")
    val pin = dead.pin ?: dead.voice?.let { Pin(dead.name, it) }
    val made = CountDownLatch(1)
    main.post {
      if (!closed && current === dead) {
        dead.shutdown()
        current = Engine(pin)
      }
      made.countDown()
    }
    if (!made.await(readyWaitMs, TimeUnit.MILLISECONDS)) return null
    val fresh = current
    if (fresh === dead || closed) return null
    // A failed rebind is itself lost, so a request after the gap tries again.
    if (!fresh.settled.await(readyWaitMs, TimeUnit.MILLISECONDS)) {
      fresh.lost = true
      log("bridge engine rebind timed out")
      return null
    }
    if (fresh.status != TtsSpeaker.EngineStatus.READY) {
      fresh.lost = true
      // Logged as no-voice when Android bound a different engine than the pinned one.
      log("bridge engine ${fresh.status.wire} after rebind")
      main.post { fresh.shutdown() }
      return null
    }
    log("bridge engine rebound")
    return fresh
  }

  override fun cancel() {
    if (wait.cancel()) current.tts.stop()
  }

  fun shutdown() {
    closed = true
    cancel()
    current.shutdown()
  }

  companion object {
    const val TIMEOUT_MS = 120_000L
    /** A cold Google TTS took 4.9 s to its first file on the Huawei tablet (REA-29). */
    const val READY_WAIT_MS = 20_000L
    const val REBIND_GAP_MS = 10_000L
    const val ALIVE_CHECK_MS = 5_000L
    private const val NEVER = Long.MIN_VALUE
  }
}
