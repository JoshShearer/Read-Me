package io.loopstring.readme.playback

/**
 * TextToSpeech refuses input longer than getMaxSpeechInputLength() (documented as 4000), and a
 * paragraph with no sentence punctuation is one sentence. Such a sentence is split after
 * whitespace into pieces that keep exact character offsets, so positions stay offsets
 * (AGENTS.md 10).
 */
object Utterances {
  const val MAX_CHARS = 3_900

  data class Fitted(val rows: List<SentenceRow>, val startIndex: Int)

  fun fit(sentences: List<SentenceRow>, startIndex: Int, max: Int): Fitted {
    val out = ArrayList<SentenceRow>(sentences.size)
    var start = 0
    sentences.forEachIndexed { i, s ->
      if (i == startIndex) start = out.size
      val t = s.text
      var a = 0
      while (t.length - a > max) {
        val b = cutPoint(t, a, max)
        out += SentenceRow(s.paragraphIndex, s.start + a, s.start + b, t.substring(a, b))
        a = b
      }
      out += SentenceRow(s.paragraphIndex, s.start + a, s.end, t.substring(a))
    }
    return Fitted(out, start)
  }

  /** After the last whitespace in the back half of the window, else at the limit. */
  private fun cutPoint(t: String, from: Int, max: Int): Int {
    for (i in from + max - 1 downTo from + max / 2) {
      if (Character.isWhitespace(t[i])) return i + 1
    }
    val end = from + max
    return if (Character.isHighSurrogate(t[end - 1])) end - 1 else end
  }
}
