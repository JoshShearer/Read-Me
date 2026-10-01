package io.loopstring.readme.spike

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONObject
import java.util.Locale

/**
 * SPIKE-05: speak [sentences] with QUEUE_ADD at [rate], keeping [AHEAD] utterances queued
 * (srs.md, Playback), and record every onDone(n) -> onStart(n+1) gap until [durationMs].
 * Construct on the main thread; [onFinished] is called exactly once.
 */
class GapProbe(
    context: Context,
    private val sentences: List<String>,
    private val rate: Float,
    private val durationMs: Long,
    private val onFinished: (JSONObject) -> Unit,
) {
  private val lock = Any()
  private val handler = Handler(Looper.getMainLooper())
  private val gaps = ArrayList<Long>()
  private val usPerChar = ArrayList<Long>()
  private val startedAtById = HashMap<String, Long>()
  private var nextIndex = 0
  private var lastDoneAt = -1L
  private var lastProgressAt = 0L
  private var startedAt = 0L
  private var spoken = 0
  private var errors = 0
  private var stops = 0
  private var finished = false
  private var initStatus = Int.MIN_VALUE

  // If the engine fails to bind, onInit can run synchronously inside this constructor, before
  // `tts` is assigned. Posting defers every use of `tts` until construction has finished.
  private val tts: TextToSpeech =
      TextToSpeech(context.applicationContext) { status -> handler.post { onReady(status) } }

  init {
    handler.postDelayed({ if (initStatus == Int.MIN_VALUE) finish("init-timeout") }, INIT_TIMEOUT_MS)
  }

  fun cancel() = finish("cancelled")

  private fun onReady(status: Int) {
    initStatus = status
    if (status != TextToSpeech.SUCCESS) {
      finish("init-failed")
      return
    }
    tts.setLanguage(Locale.US)
    tts.setSpeechRate(rate)
    tts.setOnUtteranceProgressListener(Listener())
    synchronized(lock) {
      startedAt = SystemClock.elapsedRealtime()
      lastProgressAt = startedAt
      repeat(AHEAD) { enqueueNextLocked() }
    }
    handler.postDelayed(watchdog, WATCHDOG_MS)
  }

  private val watchdog: Runnable = object : Runnable {
    override fun run() {
      val idle = synchronized(lock) { SystemClock.elapsedRealtime() - lastProgressAt }
      if (idle > HUNG_MS) finish("hung") else handler.postDelayed(this, WATCHDOG_MS)
    }
  }

  private fun sentenceFor(id: String): String = sentences[id.substring(1).toInt() % sentences.size]

  private fun enqueueNextLocked() {
    if (finished) return
    val id = "g$nextIndex"
    tts.speak(sentenceFor(id), TextToSpeech.QUEUE_ADD, Bundle(), id)
    nextIndex++
  }

  /** Named class, not anonymous: see the d8 note in the prototype's MainActivity.Tap. */
  private inner class Listener : UtteranceProgressListener() {
    override fun onStart(id: String) {
      val now = SystemClock.elapsedRealtime()
      synchronized(lock) {
        lastProgressAt = now
        startedAtById[id] = now
        if (lastDoneAt >= 0) gaps.add(now - lastDoneAt)
      }
    }

    override fun onDone(id: String) {
      val now = SystemClock.elapsedRealtime()
      val timeUp = synchronized(lock) {
        lastDoneAt = now
        lastProgressAt = now
        spoken++
        startedAtById.remove(id)?.let { began ->
          usPerChar.add((now - began) * 1000 / sentenceFor(id).length.coerceAtLeast(1))
        }
        val up = now - startedAt >= durationMs
        if (!up) enqueueNextLocked()
        up
      }
      if (timeUp) handler.post { finish("done") }
    }

    @Deprecated("Deprecated in Java")
    override fun onError(id: String) {
      synchronized(lock) {
        errors++
        lastDoneAt = SystemClock.elapsedRealtime()
        startedAtById.remove(id)
        enqueueNextLocked()
      }
    }

    override fun onError(id: String, code: Int) {
      @Suppress("DEPRECATION") onError(id)
    }

    override fun onStop(id: String, interrupted: Boolean) {
      synchronized(lock) { stops++ }
    }
  }

  private fun finish(reason: String) {
    val result = synchronized(lock) {
      if (finished) return
      finished = true
      val s = GapStats.summarize(gaps)
      val per = usPerChar.sorted()
      JSONObject()
          .put("reason", reason)
          .put("initStatus", initStatus)
          .put("rate", rate.toDouble())
          .put("elapsedMs", if (startedAt == 0L) 0 else SystemClock.elapsedRealtime() - startedAt)
          .put("utterances", spoken)
          .put("errors", errors)
          .put("stopsBeforeFinish", stops)
          .put("gapCount", s.count)
          .put("gapP50", s.p50)
          .put("gapP95", s.p95)
          .put("gapMax", s.max)
          .put("stalls", s.stalls)
          .put("usPerCharP50", if (per.isEmpty()) 0 else GapStats.percentile(per, 50))
    }
    handler.removeCallbacksAndMessages(null)
    tts.stop()
    tts.shutdown()
    onFinished(result)
  }

  companion object {
    const val AHEAD = 3
    const val WATCHDOG_MS = 5_000L
    // A 400-char utterance runs ~13 s at 2.0x (482 chars measured 16.1 s, note-reader-local
    // AGENTS.md), so "hung" must sit well clear of one long sentence.
    const val HUNG_MS = 60_000L
    const val INIT_TIMEOUT_MS = 20_000L
  }
}
