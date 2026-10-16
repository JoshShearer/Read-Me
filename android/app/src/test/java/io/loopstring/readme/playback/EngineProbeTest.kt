package io.loopstring.readme.playback

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EngineProbeTest {
  @Test fun aThrowWhileBuildingTheProbeSettlesOnceAsNoEngine() {
    // REA-21: the throw escaped on the main thread (a crash) and getEngine's promise never settled.
    val got = mutableListOf<EngineProbe.Report>()
    EngineProbe.guarded({ got += it }) { throw IllegalStateException("bind") }
    assertEquals(listOf("no-engine"), got.map { it.status })
  }

  @Test fun aProbeThatReportedBeforeThrowingIsNotReportedTwice() {
    val got = mutableListOf<EngineProbe.Report>()
    EngineProbe.guarded({ got += it }) { once ->
      once(EngineProbe.Report("ready", emptyList(), "v"))
      throw IllegalStateException("late")
    }
    assertEquals(listOf("ready"), got.map { it.status })
  }

  @Test fun aProbeThatBuildsReportsOnlyWhatItFinds() {
    val got = mutableListOf<EngineProbe.Report>()
    EngineProbe.guarded({ got += it }) { once -> once(EngineProbe.Report("no-voice", emptyList(), null)) }
    assertEquals(listOf("no-voice"), got.map { it.status })
  }
}
