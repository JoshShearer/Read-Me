package io.loopstring.readme.bridge

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import io.loopstring.readme.playback.TtsSpeaker
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * The bridge's own TextToSpeech (AGENTS.md 13), separate from playback's TtsSpeaker. Same voice
 * rule (AGENTS.md 5). setSpeechRate on this instance is the only rate lever (AGENTS.md 9);
 * SPIKE-06 saw no rate leak between instances. Construct on the main thread.
 */
class TtsSynth(context: Context, private val preferredVoice: String?) : Synthesizer {
  private val main = Handler(Looper.getMainLooper())
  private val power = context.getSystemService(PowerManager::class.java)
  private val wait = SynthWait()
  private val seq = AtomicInteger()
  @Volatile private var status = TtsSpeaker.EngineStatus.PENDING
  @Volatile override var engine: String = "unknown"
    private set
  @Volatile override var voice: String? = null
    private set
  override val maxChars: Int = TtsSpeaker.maxChars()
  override val ready: Boolean get() = status == TtsSpeaker.EngineStatus.READY

  // onInit can run inside the constructor before `tts` is assigned (SPIKE-05): defer it.
  private val tts = TextToSpeech(context.applicationContext) { s -> main.post { onInit(s) } }

  private fun onInit(result: Int) {
    if (result != TextToSpeech.SUCCESS) {
      status = TtsSpeaker.EngineStatus.NO_ENGINE
      return
    }
    engine = runCatching { tts.defaultEngine }.getOrNull() ?: "unknown"
    val v = TtsSpeaker.chooseVoice(tts, preferredVoice)
    if (v == null || tts.setVoice(v) != TextToSpeech.SUCCESS) {
      status = TtsSpeaker.EngineStatus.NO_VOICE
      return
    }
    voice = v.name
    tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
      override fun onStart(id: String?) {}
      override fun onDone(id: String?) { if (id != null) wait.finish(id, SynthResult.OK) }
      @Deprecated("Deprecated in Java")
      override fun onError(id: String?) { if (id != null) wait.finish(id, SynthResult.FAILED) }
      override fun onError(id: String?, errorCode: Int) { if (id != null) wait.finish(id, SynthResult.FAILED) }
      override fun onStop(id: String?, interrupted: Boolean) { if (id != null) wait.finish(id, SynthResult.CANCELLED) }
    })
    status = TtsSpeaker.EngineStatus.READY
  }

  override fun synthesize(text: String, rate: Float, out: File, proceed: () -> Boolean): SynthResult {
    if (!ready) return SynthResult.FAILED
    val id = "bridge-${seq.incrementAndGet()}"
    wait.begin(id)
    // ADR 0004: a preempt before begin() found nothing to cancel; this catches it.
    if (!proceed()) {
      wait.cancel()
      wait.await(0)
      return SynthResult.CANCELLED
    }
    // A screen-off request from Obsidian must finish; held only for this one synthesis.
    val lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ReadMe:bridge")
      .apply { setReferenceCounted(false); acquire(TIMEOUT_MS + 5_000) }
    try {
      tts.setSpeechRate(rate)
      if (tts.synthesizeToFile(text, Bundle(), out, id) != TextToSpeech.SUCCESS) {
        wait.cancel()
        wait.await(0)
        return SynthResult.FAILED
      }
      val r = wait.await(TIMEOUT_MS)
      // A cancel can land before the engine took the request; stop it now that it has.
      if (r == SynthResult.TIMEOUT || r == SynthResult.CANCELLED) tts.stop()
      return r
    } finally {
      if (lock.isHeld) lock.release()
    }
  }

  override fun cancel() {
    if (wait.cancel()) tts.stop()
  }

  fun shutdown() {
    cancel()
    tts.stop()
    tts.shutdown()
  }

  companion object {
    const val TIMEOUT_MS = 120_000L
  }
}
