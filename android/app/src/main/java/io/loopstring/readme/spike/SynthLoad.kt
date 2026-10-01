package io.loopstring.readme.spike

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * SPIKE-05/06: a second TextToSpeech instance in the same process. When [active], it loops
 * synthesizeToFile (the bridge's call) back to back at rate 1.0 until [stopAndReport]; when
 * not, it only exists, to test whether a second bound instance alone disturbs playback.
 * Construct on the main thread.
 */
class SynthLoad(context: Context, private val sentences: List<String>, private val active: Boolean) {
  private val cacheDir = context.cacheDir
  private val handler = Handler(Looper.getMainLooper())
  private val lock = Any()
  private val synthMs = ArrayList<Long>()
  private val bytesPerChar = ArrayList<Long>()
  private var currentChars = 1
  private var errors = 0
  private var rejected = 0
  private var stops = 0
  private var running = false
  private var stopped = false
  private var seq = 0
  private var current: File? = null
  private var currentStartedAt = 0L
  private var initStatus = Int.MIN_VALUE

  // Deferred for the same reason as GapProbe: onInit may run inside the constructor.
  private val tts: TextToSpeech =
      TextToSpeech(context.applicationContext) { status -> handler.post { onReady(status) } }

  private fun onReady(status: Int) {
    initStatus = status
    if (status != TextToSpeech.SUCCESS) return
    tts.setLanguage(Locale.US)
    tts.setSpeechRate(1.0f)
    tts.setOnUtteranceProgressListener(Listener())
    synchronized(lock) {
      if (stopped) return
      running = active
      if (running) nextLocked()
    }
  }

  private fun nextLocked() {
    val f = File(cacheDir, "spike-synth-$seq.wav")
    current = f
    currentStartedAt = SystemClock.elapsedRealtime()
    val text = sentences[seq % sentences.size]
    currentChars = text.length.coerceAtLeast(1)
    val rc = tts.synthesizeToFile(text, Bundle(), f, "s$seq")
    seq++
    if (rc != TextToSpeech.SUCCESS) {
      // Rejected outright: no callback will come, so stop instead of spinning. The report shows it.
      rejected++
      running = false
      f.delete()
    }
  }

  private fun complete(ok: Boolean) {
    synchronized(lock) {
      if (ok) {
        synthMs.add(SystemClock.elapsedRealtime() - currentStartedAt)
        // WAV size tracks audio duration, so bytes per char exposes a rate leaking INTO this
        // instance (faster audio = fewer bytes), which synth time alone would hide.
        current?.let { bytesPerChar.add(it.length() / currentChars) }
      } else {
        errors++
      }
      current?.delete()
      if (running) nextLocked()
    }
  }

  private inner class Listener : UtteranceProgressListener() {
    override fun onStart(id: String) {}
    override fun onDone(id: String) = complete(ok = true)
    @Deprecated("Deprecated in Java") override fun onError(id: String) = complete(ok = false)
    override fun onError(id: String, code: Int) = complete(ok = false)
    override fun onStop(id: String, interrupted: Boolean) {
      synchronized(lock) { stops++ }
    }
  }

  fun stopAndReport(): JSONObject {
    synchronized(lock) {
      running = false
      stopped = true
    }
    handler.removeCallbacksAndMessages(null)
    tts.stop()
    tts.shutdown()
    synchronized(lock) {
      current?.delete()
      val sorted = synthMs.sorted()
      return JSONObject()
          .put("active", active)
          .put("initStatus", initStatus)
          .put("synths", sorted.size)
          .put("synthP50", if (sorted.isEmpty()) 0 else GapStats.percentile(sorted, 50))
          .put("synthP95", if (sorted.isEmpty()) 0 else GapStats.percentile(sorted, 95))
          .put("bytesPerCharP50", bytesPerChar.sorted().let { if (it.isEmpty()) 0 else GapStats.percentile(it, 50) })
          .put("errors", errors)
          .put("rejected", rejected)
          .put("stops", stops)
    }
  }
}
