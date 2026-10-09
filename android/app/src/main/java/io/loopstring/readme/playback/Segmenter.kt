package io.loopstring.readme.playback

import io.loopstring.readme.store.PositionRow

/**
 * R-S05, ADR 0011: a port of src/segment/segment.ts's fallback segmenter (the Hermes path,
 * SPIKE-03), src/trim/cuts.ts's remapPosition and src/segment/locate.ts's sentenceIndexAt,
 * used only when continuous play hands over to the next item with no JS. A JS-started play
 * still sends JS's sentences. SegmenterParityTest runs this on the golden that
 * __tests__/segmentParity.test.ts pins to the TS code; they must give identical offsets.
 * Indices are UTF-16 units on both sides. Nothing here logs: sentences are item text.
 */
object Segmenter {
  const val MAX_SENTENCE = 400

  private const val TERMINAL = ".!?…"
  private const val CJK_TERMINAL = "。！？"
  private const val CLOSERS = "\"')]}»”’」』"
  private const val NO_SPLIT_BEFORE = ",;:)]}"
  private val ABBREVIATIONS = setOf(
    "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "vs", "etc", "approx", "dept", "inc",
    "ltd", "co", "corp", "mt", "gen", "gov", "sen", "rep", "jan", "feb", "mar", "apr", "jun",
    "jul", "aug", "sep", "sept", "oct", "nov", "dec",
  )
  private val NUMBER_ABBREVIATIONS = setOf("no", "fig", "vol", "ch", "p", "pp", "art", "sec")
  private val INITIALS = Regex("^([A-Za-z]\\.)+[A-Za-z]$")

  private class Span(val start: Int, val end: Int)

  /** JS's `\s` exactly; Kotlin's isWhitespace differs (U+001C..1F, U+FEFF). */
  internal fun isSpace(c: Char): Boolean = when (c) {
    '\t', '\n', '\u000B', '\u000C', '\r', ' ', ' ', ' ', ' ', ' ',
    ' ', ' ', '　', '﻿' -> true
    else -> c in ' '..' '
  }

  // JS: c !== c.toUpperCase() && c === c.toLowerCase(), on one UTF-16 unit, locale-free.
  private fun isLower(c: Char): Boolean {
    val s = c.toString()
    return s != s.uppercase() && s == s.lowercase()
  }

  private fun isAsciiLetterOrDot(c: Char) = c == '.' || c in 'A'..'Z' || c in 'a'..'z'

  private fun isAbbreviation(text: String, dot: Int, next: Char): Boolean {
    var s = dot
    while (s > 0 && isAsciiLetterOrDot(text[s - 1])) s--
    val token = text.substring(s, dot)
    if (token.isEmpty()) return false
    if (token.length == 1 && token[0] in 'A'..'Z') return true
    if (INITIALS.matches(token)) return true
    val lower = token.lowercase()
    if (lower in ABBREVIATIONS) return true
    return lower in NUMBER_ABBREVIATIONS && next in '0'..'9'
  }

  private fun pushTrimmed(text: String, from: Int, to: Int, out: MutableList<Span>) {
    var start = from
    var end = to
    while (start < end && isSpace(text[start])) start++
    while (end > start && isSpace(text[end - 1])) end--
    if (end > start) out.add(Span(start, end))
  }

  private fun fallbackSpans(text: String): List<Span> {
    val spans = ArrayList<Span>()
    var start = 0
    var i = 0
    while (i < text.length) {
      val c = text[i]
      val cjk = CJK_TERMINAL.indexOf(c) >= 0
      if (!cjk && TERMINAL.indexOf(c) < 0) {
        i++
        continue
      }
      var j = i + 1
      while (j < text.length &&
        (TERMINAL.indexOf(text[j]) >= 0 || CJK_TERMINAL.indexOf(text[j]) >= 0 || CLOSERS.indexOf(text[j]) >= 0)
      ) {
        j++
      }
      var k = j
      while (k < text.length && isSpace(text[k])) k++
      if (k >= text.length) break
      val next = text[k]
      val boundary = cjk || (
        k > j &&
          !isLower(next) &&
          NO_SPLIT_BEFORE.indexOf(next) < 0 &&
          !(c == '.' && isAbbreviation(text, i, next))
        )
      if (boundary) {
        pushTrimmed(text, start, j, spans)
        start = k
        i = k
      } else {
        i = j
      }
    }
    pushTrimmed(text, start, text.length, spans)
    return spans
  }

  private fun lastClauseCut(text: String, start: Int, windowEnd: Int): Int {
    val half = start + MAX_SENTENCE / 2
    var p = windowEnd - 1
    while (p >= half) {
      if (",;:".indexOf(text[p]) >= 0 && p + 1 < text.length && isSpace(text[p + 1])) return p + 1
      p--
    }
    return -1
  }

  private fun lastSpaceCut(text: String, start: Int, windowEnd: Int): Int {
    var p = minOf(windowEnd, text.length - 1)
    while (p > start) {
      if (isSpace(text[p])) return p
      p--
    }
    return -1
  }

  private fun capSpan(text: String, span: Span, out: MutableList<Span>) {
    var start = span.start
    val end = span.end
    while (end - start > MAX_SENTENCE) {
      val windowEnd = start + MAX_SENTENCE
      var cut = lastClauseCut(text, start, windowEnd)
      if (cut <= start) cut = lastSpaceCut(text, start, windowEnd)
      if (cut <= start) {
        cut = windowEnd
        if (Character.isHighSurrogate(text[cut - 1])) cut--
      }
      pushTrimmed(text, start, cut, out)
      start = cut
      while (start < end && isSpace(text[start])) start++
    }
    pushTrimmed(text, start, end, out)
  }

  fun segmentParagraph(text: String, paragraphIndex: Int): List<SentenceRow> {
    val capped = ArrayList<Span>()
    for (s in fallbackSpans(text)) capSpan(text, s, capped)
    return capped.map { SentenceRow(paragraphIndex, it.start, it.end, text.substring(it.start, it.end)) }
  }

  fun segment(paragraphs: List<String>, cuts: Set<Int>): List<SentenceRow> {
    val out = ArrayList<SentenceRow>()
    paragraphs.forEachIndexed { i, p -> if (i !in cuts) out.addAll(segmentParagraph(p, i)) }
    return out
  }

  /** R-M05: stays if its paragraph is kept, else the start of the next kept one; null past the end. */
  fun remapPosition(position: PositionRow, cuts: Set<Int>, count: Int): PositionRow? {
    val p = position.paragraphIndex
    if (p in 0 until count && p !in cuts) return position
    for (i in maxOf(p + 1, 0) until count) if (i !in cuts) return PositionRow(i, 0)
    return null
  }

  /** R-M11: the sentence containing the offset, under this segmentation; -1 past the end. Never persisted. */
  fun sentenceIndexAt(sentences: List<SentenceRow>, position: PositionRow): Int {
    for ((i, s) in sentences.withIndex()) {
      if (s.paragraphIndex < position.paragraphIndex) continue
      if (s.paragraphIndex > position.paragraphIndex) return i
      if (position.charOffset < s.end) return i
    }
    return -1
  }

  class Plan(val sentences: List<SentenceRow>, val startIndex: Int, val pastEnd: Boolean)

  /** src/library/playback.ts plan(): null when nothing is kept; past the end starts over. */
  fun plan(paragraphs: List<String>, cuts: Set<Int>, saved: PositionRow?): Plan? {
    val sentences = segment(paragraphs, cuts)
    if (sentences.isEmpty()) return null
    val moved = saved?.let { remapPosition(it, cuts, paragraphs.size) }
    val i = if (moved != null) sentenceIndexAt(sentences, moved) else 0
    return Plan(sentences, if (i < 0) 0 else i, saved != null && (moved == null || i < 0))
  }
}
