package io.loopstring.readme.bridge

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

enum class SynthResult {
  OK, FAILED, TIMEOUT, CANCELLED,
  /** The engine went away (REA-29). TtsSynth's own signal; synthesize never returns it. */
  LOST,
}

/** The bridge's speech engine (AGENTS.md 13: never playback's instance). */
interface Synthesizer {
  val ready: Boolean
  /** The engine package, or "unknown". Reported by /health; no item data. */
  val engine: String
  /** The voice in use, or null. Reported by /health. */
  val voice: String?
  /** The longest text one synthesis accepts (ADR 0008). */
  val maxChars: Int

  /**
   * Blocks until [out] holds the WAV or the synthesis ends otherwise. Never on the main thread.
   * [proceed] is checked once the synthesis is registered for [cancel] and before the engine
   * starts: false means playback started in between (ADR 0004), and the result is CANCELLED.
   */
  fun synthesize(text: String, rate: Float, out: File, proceed: () -> Boolean): SynthResult

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

  /**
   * Waits up to [ms]. Between [sliceMs] slices [alive] is asked whether the engine is still
   * there; false ends the wait with LOST, since a dead engine never calls back (REA-29).
   */
  fun await(ms: Long, sliceMs: Long = ms, alive: () -> Boolean = { true }): SynthResult {
    require(sliceMs > 0 || ms <= 0) { "sliceMs must be positive" }
    val l = synchronized(lock) { latch } ?: return SynthResult.FAILED
    var left = ms
    var done = false
    var lost = false
    while (!done && left > 0) {
      val slice = minOf(sliceMs, left)
      done = l.await(slice, TimeUnit.MILLISECONDS)
      left -= slice
      if (!done && left > 0 && !alive()) lost = true
      if (lost) break
    }
    return synchronized(lock) {
      // A callback can land between the last slice and here; it wins.
      val r = when {
        done || l.count == 0L -> result
        lost -> SynthResult.LOST
        else -> SynthResult.TIMEOUT
      }
      id = null
      latch = null
      r
    }
  }
}
