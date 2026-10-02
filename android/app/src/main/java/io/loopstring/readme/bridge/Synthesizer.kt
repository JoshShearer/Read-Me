package io.loopstring.readme.bridge

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

enum class SynthResult { OK, FAILED, TIMEOUT, CANCELLED }

/** The bridge's speech engine (AGENTS.md 13: never playback's instance). */
interface Synthesizer {
  val ready: Boolean
  /** The engine package, or "unknown". Reported by /health; no item data. */
  val engine: String
  /** The voice in use, or null. Reported by /health. */
  val voice: String?
  /** The longest text one synthesis accepts (ADR 0008). */
  val maxChars: Int

  /** Blocks until [out] holds the WAV or the synthesis ends otherwise. Never on the main thread. */
  fun synthesize(text: String, rate: Float, out: File): SynthResult

  /** Ends a synthesis in flight with CANCELLED (ADR 0004). Any thread; a no-op when idle. */
  fun cancel()
}

/**
 * One synthesis at a time: the engine's callbacks (binder threads) and a cancel (any thread)
 * race to end it, and the first one wins.
 */
class SynthWait {
  private val lock = Any()
  private var id: String? = null
  private var latch: CountDownLatch? = null
  private var result = SynthResult.FAILED

  fun begin(id: String) = synchronized(lock) {
    this.id = id
    latch = CountDownLatch(1)
    result = SynthResult.FAILED
  }

  fun finish(id: String, r: SynthResult) = synchronized(lock) {
    val l = latch
    if (id != this.id || l == null || l.count == 0L) return
    result = r
    l.countDown()
  }

  /** False when nothing was in flight. */
  fun cancel(): Boolean = synchronized(lock) {
    val l = latch
    if (l == null || l.count == 0L) return false
    result = SynthResult.CANCELLED
    l.countDown()
    true
  }

  fun await(ms: Long): SynthResult {
    val l = synchronized(lock) { latch } ?: return SynthResult.FAILED
    val done = l.await(ms, TimeUnit.MILLISECONDS)
    return synchronized(lock) {
      val r = if (done) result else SynthResult.TIMEOUT
      id = null
      latch = null
      r
    }
  }
}
