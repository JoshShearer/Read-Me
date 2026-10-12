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

  @Test fun aSynthesisChunkLimitSplitsAtAClauseAndKeepsTheWholeSentence() {
    // REA-31: a whole sentence is synthesized before any of it plays, so a long one is cut
    // into chunks whose synthesis is short; the highlight still shows the whole sentence.
    val text = "First clause runs on for a while, " + "and the second clause also runs on and on; " +
      "the third one ends here."
    val rows = listOf(row(0, 0, "Short."), row(1, 5, text))
    val f = Utterances.fit(rows, 1, 3_900, chunk = 50)
    val pieces = f.rows.filter { it.paragraphIndex == 1 }
    assertTrue(pieces.size > 1)
    pieces.forEach { assertTrue(it.text.length <= 50) }
    assertEquals(text, pieces.joinToString("") { it.text })
    pieces.zipWithNext { a, b -> assertEquals(a.end, b.start) }
    // At a clause mark where there is one in the back half of the window.
    assertTrue(pieces.first().text.trimEnd().endsWith(","))
    assertEquals(1, f.startIndex)
    assertEquals(f.rows.size, f.sentences.size)
    f.rows.indices.filter { f.rows[it].paragraphIndex == 1 }.forEach { assertEquals(rows[1], f.sentences[it]) }
    assertEquals(rows[0], f.sentences[0])
  }

  @Test fun withoutAChunkLimitEachRowIsItsOwnSentence() {
    val rows = listOf(row(0, 0, "One."), row(0, 5, "Two."))
    assertEquals(rows, Utterances.fit(rows, 0, 100).sentences)
  }
}
