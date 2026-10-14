package io.loopstring.readme.playback

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * REA-31 (ADR 0012) and AGENTS.md 9: the engine applies the rate when it synthesizes, and Read
 * Me's player plays at 1.0x. Nothing in playback may scale speed a second time, speak through
 * the engine's own audio, or claim media buttons with silence.
 */
class PlaybackSourceTest {
  // Gradle runs unit tests from the module directory.
  private val sources = File("src/main/java/io/loopstring/readme")

  private fun kotlin() = sources.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

  private fun code(f: File) = f.readLines().filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }.joinToString("\n")

  @Test fun theSourcesAreFound() {
    assertTrue(kotlin().size > 10)
  }

  @Test fun nothingScalesPlaybackSpeed() {
    val bad = Regex("""PlaybackParams|setPlaybackRate|setPlaybackParams|\.setSpeed\(""")
    assertEquals(emptyList<String>(), kotlin().filter { bad.containsMatchIn(code(it)) }.map { it.name })
  }

  @Test fun playbackNeverSpeaksThroughTheEngine() {
    val playback = kotlin().filter { it.parentFile.name == "playback" }
    assertEquals(emptyList<String>(), playback.filter { Regex("""\.speak\(""").containsMatchIn(code(it)) }.map { it.name })
  }

  @Test fun theSilenceClaimIsGone() {
    assertFalse(kotlin().any { it.name == "MediaButtonClaim.kt" })
    assertEquals(emptyList<String>(), kotlin().filter { code(it).contains("MediaButtonClaim") }.map { it.name })
  }
}
