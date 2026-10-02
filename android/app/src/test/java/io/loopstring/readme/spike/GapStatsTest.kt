package io.loopstring.readme.spike

import org.junit.Assert.assertEquals
import org.junit.Test

class GapStatsTest {
  @Test fun nearestRankPercentiles() {
    val s = GapStats.summarize((1L..100L).shuffled())
    assertEquals(GapSummary(count = 100, p50 = 50, p95 = 95, max = 100, stalls = 0), s)
  }

  @Test fun gapsOverOneSecondAreStalls() {
    assertEquals(2, GapStats.summarize(listOf(10L, 1_000L, 1_001L, 5_000L)).stalls)
  }

  @Test fun emptyInputIsAllZero() {
    assertEquals(GapSummary(0, 0, 0, 0, 0), GapStats.summarize(emptyList()))
  }

  @Test fun singleValueIsEveryPercentile() {
    assertEquals(7L, GapStats.percentile(listOf(7L), 95))
  }
}
