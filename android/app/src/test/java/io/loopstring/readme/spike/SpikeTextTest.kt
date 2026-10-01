package io.loopstring.readme.spike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpikeTextTest {
  @Test fun splitsOnTerminalPunctuation() {
    assertEquals(listOf("One.", "Two!", "Three?"), SpikeText.sentences("One. Two! Three?"))
  }

  @Test fun blankLinesSeparateParagraphsAndSingleNewlinesJoin() {
    assertEquals(listOf("A b.", "C."), SpikeText.sentences("A\nb.\n\nC."))
  }

  @Test fun closingQuoteStaysWithItsSentence() {
    assertEquals(listOf("He said \"Stop.\"", "Then left."), SpikeText.sentences("He said \"Stop.\" Then left."))
  }

  @Test fun capsLongSentencesAtTheLastSpaceBefore400() {
    val long = "word ".repeat(120).trim() + "."
    val out = SpikeText.sentences(long)
    assertTrue(out.all { it.length <= 400 })
    assertEquals(long, out.joinToString(" "))
  }

  @Test fun capsSentencesWithNoSpaceAtExactly400() {
    assertEquals(listOf(400, 400, 100), SpikeText.capLength("x".repeat(900)).map { it.length })
  }
}
