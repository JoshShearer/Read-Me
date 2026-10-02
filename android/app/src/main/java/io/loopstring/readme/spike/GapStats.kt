package io.loopstring.readme.spike

/** R-M07 inter-utterance gap summary. A stall is a gap over [GapStats.STALL_MS]. */
data class GapSummary(val count: Int, val p50: Long, val p95: Long, val max: Long, val stalls: Int)

object GapStats {
  const val STALL_MS = 1_000L

  fun summarize(gapsMs: List<Long>): GapSummary {
    if (gapsMs.isEmpty()) return GapSummary(0, 0, 0, 0, 0)
    val s = gapsMs.sorted()
    return GapSummary(s.size, percentile(s, 50), percentile(s, 95), s.last(), s.count { it > STALL_MS })
  }

  /** Nearest-rank percentile of an ascending list. */
  fun percentile(sorted: List<Long>, p: Int): Long {
    val rank = Math.ceil(p / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
    return sorted[rank - 1]
  }
}
