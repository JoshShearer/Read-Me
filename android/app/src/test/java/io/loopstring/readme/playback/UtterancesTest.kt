package io.loopstring.readme.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UtterancesTest {
  private fun row(p: Int, start: Int, text: String) = SentenceRow(p, start, start + text.length, text)

  @Test fun shortSentencesPassThrough() {
    val rows = listOf(row(0, 0, "One."), row(0, 5, "Two."))
    val f = Utterances.fit(rows, 1, 100)
    assertEquals(rows, f.rows)
    assertEquals(1, f.startIndex)
  }

  @Test fun aLongSentenceSplitsAtWhitespaceAndKeepsOffsets() {
    // A paragraph with no punctuation is one sentence; the engine refuses input over its limit.
    val text = (1..60).joinToString(" ") { "word$it" } // 60 words, ~ 400 chars
    val rows = listOf(row(0, 0, "Before."), row(2, 10, text), row(3, 0, "After."))
    val f = Utterances.fit(rows, 2, 100)
    val pieces = f.rows.filter { it.paragraphIndex == 2 }
    assertTrue(pieces.size > 1)
    pieces.forEach { assertTrue(it.text.length <= 100) }
    assertEquals(text, pieces.joinToString("") { it.text })
    assertEquals(10, pieces.first().start)
    assertEquals(10 + text.length, pieces.last().end)
    pieces.zipWithNext { a, b -> assertEquals(a.end, b.start) }
    pieces.forEach { assertEquals(text.substring(it.start - 10, it.end - 10), it.text) }
    pieces.dropLast(1).forEach { assertTrue(it.text.endsWith(" ")) }
    assertEquals(f.rows.indexOfFirst { it.paragraphIndex == 3 }, f.startIndex)
  }

  @Test fun aLongRunWithNoSpaceIsCutAtTheLimitButNeverBetweenSurrogates() {
    val text = "a".repeat(99) + "😀" + "b".repeat(50) // emoji straddles index 100
    val f = Utterances.fit(listOf(row(0, 0, text)), 0, 100)
    assertEquals(99, f.rows[0].text.length)
    assertEquals(text, f.rows.joinToString("") { it.text })
  }
}
