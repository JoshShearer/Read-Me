package io.loopstring.readme.playback

/**
 * TextToSpeech refuses input longer than getMaxSpeechInputLength() (documented as 4000), and a
 * paragraph with no sentence punctuation is one sentence. Such a sentence is split after
 * whitespace into pieces that keep exact character offsets, so positions stay offsets
 * (AGENTS.md 10).
 */
object Utterances {
  const val MAX_CHARS = 3_900

  /** [sentences] holds, for each row, the whole sentence it came from: what the Reader highlights. */
  data class Fitted(val rows: List<SentenceRow>, val startIndex: Int, val sentences: List<SentenceRow>)

  /**
   * [chunk] > 0 also cuts sentences longer than it (REA-31, ADR 0012): Read Me plays a sentence
   * only once the engine has synthesized all of it, so one long sentence after short ones ran
   * the player dry (device:gap, 2026-10-04: one 1161 ms stall).
   */
  fun fit(sentences: List<SentenceRow>, startIndex: Int, max: Int, chunk: Int = 0): Fitted {
    val limit = if (chunk > 0) minOf(max, chunk) else max
    val out = ArrayList<SentenceRow>(sentences.size)
    val whole = ArrayList<SentenceRow>(sentences.size)
    var start = 0
    sentences.forEachIndexed { i, s ->
      if (i == startIndex) start = out.size
      val t = s.text
      var a = 0
      while (t.length - a > limit) {
        val b = cutPoint(t, a, limit)
        out += SentenceRow(s.paragraphIndex, s.start + a, s.start + b, t.substring(a, b))
        whole += s
        a = b
      }
      out += SentenceRow(s.paragraphIndex, s.start + a, s.end, t.substring(a))
      whole += s
    }
    return Fitted(out, start, whole)
  }

  /**
   * After a clause mark (`, ; :` then whitespace) in the back half of the window, else after
   * the last whitespace there, else at the limit: a cut at a clause sounds like a pause.
   */
  private fun cutPoint(t: String, from: Int, max: Int): Int {
    for (i in from + max - 1 downTo from + max / 2) {
      if (Character.isWhitespace(t[i]) && i > from && t[i - 1] in ",;:") return i + 1
    }
    for (i in from + max - 1 downTo from + max / 2) {
      if (Character.isWhitespace(t[i])) return i + 1
    }
    val end = from + max
    return if (Character.isHighSurrogate(t[end - 1])) end - 1 else end
  }
}
