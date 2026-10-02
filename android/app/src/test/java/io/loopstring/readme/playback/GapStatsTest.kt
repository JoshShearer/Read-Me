package io.loopstring.readme.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class GapStatsTest {
  @Test fun nearestRankPercentilesAndStalls() {
    val s = GapStats.summarize((1L..100L).toList() + 1_500L)
    assertEquals(101, s.count)
    assertEquals(51L, s.p50)
    assertEquals(96L, s.p95)
    assertEquals(1_500L, s.max)
    assertEquals(1, s.stalls)
  }

  @Test fun emptyIsAllZero() {
    assertEquals(GapSummary(0, 0, 0, 0, 0), GapStats.summarize(emptyList()))
  }
}
