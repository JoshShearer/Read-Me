package io.loopstring.readme.intake

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Robolectric for org.json (the plain JVM test classpath has only stubs).
@RunWith(RobolectricTestRunner::class)
class IntakeTest {
  // Gradle runs unit tests with the module directory (android/app) as the working directory.
  private val vectors = JSONObject(File("../../__tests__/fixtures/intake-vectors.json").readText())

  @Test fun classifyMatchesTheSharedVectors() {
    val cases = vectors.getJSONArray("classify")
    for (i in 0 until cases.length()) {
      val c = cases.getJSONObject(i)
      val input = c.getString("in")
      val out = c.getJSONObject("out")
      val got = Intake.classify(input)
      val expected = when (out.getString("kind")) {
        "link" -> Intake.Share.Link(out.getString("url"))
        "text" -> Intake.Share.Text(input)
        else -> Intake.Share.Empty
      }
      assertEquals("case $i", expected, got)
    }
  }

  @Test fun splitMatchesTheSharedVectors() {
    val cases = vectors.getJSONArray("split")
    for (i in 0 until cases.length()) {
      val c = cases.getJSONObject(i)
      val out = c.getJSONArray("out")
      val expected = (0 until out.length()).map { out.getString(it) }
      assertEquals("case $i", expected, Intake.splitParagraphs(c.getString("in")))
    }
  }

  @Test fun textTitleIsTheFirstParagraphCutAtAWordWithin80() {
    assertEquals("Short note", Intake.textTitle(listOf("Short note", "more")))
    val long = "word ".repeat(30).trim()
    val title = Intake.textTitle(listOf(long))
    assertEquals(true, title.endsWith("\u2026"))
    assertEquals(true, title.length <= 81)
    assertEquals(false, title.dropLast(1).endsWith(" "))
  }

  @Test fun hostTitleDropsWww() {
    assertEquals("example.com", Intake.hostTitle("https://www.example.com/a/b?q=1"))
    assertEquals("news.example.org", Intake.hostTitle("http://news.example.org"))
  }
}
