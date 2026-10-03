package io.loopstring.readme.intake

import io.loopstring.readme.store.ParagraphRow

/**
 * R-C04: a shared markdown note (Obsidian's file Share) read as prose, so the engine never says
 * "hash" or "asterisk". Line-based and forgiving rather than a CommonMark parser: the output is
 * listened to, not rendered. Never logs and never builds a message from the text.
 */
object Markdown {
  data class Note(val title: String?, val paragraphs: List<ParagraphRow>)

  // Every pattern is linear: bounded repetition that cannot cross a line or its own delimiter,
  // so a hostile or huge file costs time in proportion to its size (REA-30 review, F1/F2).
  private val CRLF = Regex("\\r\\n?")
  private val FRONT_MATTER = Regex("\\A---[ \\t]*\\n.*?\\n(?:---|\\.\\.\\.)[ \\t]*(?:\\n|\\z)", RegexOption.DOT_MATCHES_ALL)
  private val FENCE = Regex("^ {0,3}(```+|~~~+)")
  private val ATX = Regex("^ {0,3}#{1,6}(?=[ \\t]|$)")
  private val SETEXT = Regex("^ {0,3}(?:=+|-+)[ \\t]*$")
  private val ITEM = Regex("^[ \\t]*(?:[-*+]|\\d{1,9}[.)])[ \\t]+(?:\\[[ xX/-]][ \\t]+)?")
  private val QUOTE = Regex("^ {0,3}>[ \\t]?")
  private val CALLOUT = Regex("^\\[!\\w{1,30}][+-]?[ \\t]*")
  private val FOOTNOTE_DEF = Regex("^\\[\\^[^\\]\\n]{1,100}]:[ \\t]*")
  private val INDENTED = Regex("^(?: {2,}|\\t)\\S")

  private const val TARGET = "(?:[^()\\n]|\\([^()\\n]{0,200}\\)){0,500}"
  private val EMBED = Regex("!\\[\\[[^\\[\\]\\n]{0,300}]]|!\\[[^\\[\\]\\n]{0,300}]\\($TARGET\\)")
  private val WIKILINK = Regex("\\[\\[([^\\[\\]|\\n]{0,200})(?:\\|([^\\[\\]\\n]{0,200}))?]]")
  private val LINK = Regex("\\[([^\\[\\]\\n]{0,200})]\\($TARGET\\)")
  private val REF_LINK = Regex("\\[([^\\[\\]\\n]{1,200})]\\[[^\\[\\]\\n]{0,100}]")
  private val AUTOLINK = Regex("<(https?://[^<>\\s]{1,500})>")
  private val FOOTNOTE_REF = Regex("\\[\\^[^\\[\\]\\n]{1,100}]")
  private val CODE = Regex("`{1,3}([^`\\n]{0,300})`{1,3}")
  private val STRONG = Regex("\\*\\*(?=\\S)([^*\\n]{1,300}?)(?<=\\S)\\*\\*|__(?=\\S)([^_\\n]{1,300}?)(?<=\\S)__")
  private val EM_STAR = Regex("\\*(?=\\S)([^*\\n]{1,300}?)(?<=\\S)\\*")
  private val EM_UNDERSCORE = Regex("(?<![\\p{L}\\p{N}_])_(?=\\S)([^_\\n]{1,300}?)(?<=\\S)_(?![\\p{L}\\p{N}_])")
  private val STRIKE_HIGHLIGHT = Regex("~~(?=\\S)([^~\\n]{1,300}?)(?<=\\S)~~|==(?=\\S)([^=\\n]{1,300}?)(?<=\\S)==")
  private val TAG = Regex("(?<![\\p{L}\\p{N}_&/])#([\\p{L}_][\\p{L}\\p{N}_/-]{0,100})")
  private val BLOCK_ID = Regex("\\s\\^[A-Za-z0-9-]{1,40}\\s*$")
  private val HTML_TAG = Regex("</?[A-Za-z][A-Za-z0-9-]{0,30}(?:\\s[^<>\\n]{0,200})?/?>")
  private val ESCAPE = Regex("\\\\([\\\\`*_{}\\[\\]()#+\\-.!|~=<>$])")
  private val SPACES = Regex("\\s+")
  private const val PARK = 0xE000
  private val PARKED = Regex("[\\uE000-\\uE07F]")

  fun toNote(markdown: String): Note {
    val text = markdown.removePrefix("﻿").replace(CRLF, "\n")
      .replaceFirst(FRONT_MATTER, "")
      .let { stripBetween(it, "%%", "%%") }
      .let { stripBetween(it, "<!--", "-->") }
    val out = mutableListOf<ParagraphRow>()
    val buf = StringBuilder()
    var bufKind = "p"
    var fence: String? = null

    fun flush() {
      val t = inline(buf.toString())
      if (t.isNotEmpty()) out += ParagraphRow(bufKind, t)
      buf.setLength(0)
      bufKind = "p"
    }

    fun append(kind: String, line: String) {
      if (buf.isNotEmpty() && kind != bufKind) flush()
      bufKind = kind
      if (buf.isNotEmpty()) buf.append(' ')
      buf.append(line.trim())
    }

    for (raw in text.split('\n')) {
      // Code is skipped: read aloud it is noise, and it is never what a note is for.
      val f = FENCE.find(raw)
      if (fence != null) {
        if (f != null && f.groupValues[1].startsWith(fence)) fence = null
        continue
      }
      if (f != null) {
        flush()
        fence = f.groupValues[1].take(3)
        continue
      }
      var line = raw
      if (QUOTE.containsMatchIn(line)) {
        line = unquote(line).replaceFirst(CALLOUT, "")
      }
      if (line.isBlank()) {
        flush()
        continue
      }
      val setext = SETEXT.find(line)
      if (setext != null && buf.isNotEmpty() && bufKind == "p") {
        bufKind = "heading"
        flush()
        continue
      }
      if (isRule(line)) {
        flush()
        continue
      }
      val atx = ATX.find(line)
      if (atx != null) {
        flush()
        append("heading", closeAtx(line.substring(atx.range.last + 1)))
        flush()
        continue
      }
      if (isTableRule(line)) continue
      if (line.trimStart().startsWith("|")) {
        flush()
        append("p", line.trim().trim('|').split('|').joinToString(", ") { it.trim() })
        flush()
        continue
      }
      val item = ITEM.find(line)
      if (item != null) {
        flush()
        append("li", line.substring(item.range.last + 1))
        continue
      }
      if (bufKind == "li" && INDENTED.containsMatchIn(line)) {
        append("li", line)
        continue
      }
      append(if (bufKind == "li") "p" else bufKind, line.replaceFirst(FOOTNOTE_DEF, ""))
    }
    flush()
    val title = out.firstOrNull { it.kind == "heading" }?.text
    return Note(title, out)
  }

  /** Inline markup to the words a listener should hear. */
  fun inline(s: String): String {
    // Escaped characters are parked in the private use area so no later rule reads them as markup.
    var t = ESCAPE.replace(s) { (PARK + it.groupValues[1][0].code).toChar().toString() }
    t = EMBED.replace(t, "")
    t = WIKILINK.replace(t) { m ->
      val alias = m.groupValues[2]
      if (alias.isNotBlank()) alias
      else m.groupValues[1].substringBefore("#^").replace('#', ' ')
    }
    t = LINK.replace(t) { it.groupValues[1] }
    t = REF_LINK.replace(t) { it.groupValues[1] }
    t = AUTOLINK.replace(t) { it.groupValues[1] }
    t = FOOTNOTE_REF.replace(t, "")
    t = CODE.replace(t) { it.groupValues[1] }
    t = HTML_TAG.replace(t, " ")
    repeat(2) {
      t = STRONG.replace(t) { it.groupValues[1] + it.groupValues[2] }
      t = STRIKE_HIGHLIGHT.replace(t) { it.groupValues[1] + it.groupValues[2] }
      t = EM_STAR.replace(t) { it.groupValues[1] }
      t = EM_UNDERSCORE.replace(t) { it.groupValues[1] }
    }
    t = TAG.replace(t) { it.groupValues[1] }
    t = BLOCK_ID.replace(t, "")
    t = PARKED.replace(t) { (it.value[0].code - PARK).toChar().toString() }
    return t.replace(SPACES, " ").trim()
  }

  /** Drops every [open]...[close] span; an unclosed one is kept, as Obsidian shows it. */
  private fun stripBetween(s: String, open: String, close: String): String {
    val out = StringBuilder(s.length)
    var i = 0
    while (true) {
      val a = s.indexOf(open, i)
      if (a < 0) break
      val b = s.indexOf(close, a + open.length)
      if (b < 0) break
      out.append(s, i, a)
      i = b + close.length
    }
    return out.append(s, i, s.length).toString()
  }

  private fun unquote(line: String): String {
    var i = 0
    while (true) {
      var j = i
      while (j < line.length && j - i < 3 && line[j] == ' ') j++
      if (j >= line.length || line[j] != '>') return line.substring(i)
      j++
      if (j < line.length && (line[j] == ' ' || line[j] == '\t')) j++
      i = j
    }
  }

  /** A thematic break: three or more of one of `*`, `-`, `_`, spaces allowed between. */
  private fun isRule(line: String): Boolean {
    val t = line.trim()
    if (t.isEmpty() || line.length - line.trimStart().length > 3) return false
    val c = t[0]
    if (c != '*' && c != '-' && c != '_') return false
    var n = 0
    for (ch in t) {
      if (ch == c) n++ else if (ch != ' ' && ch != '\t') return false
    }
    return n >= 3
  }

  /** A table's `| --- | :-: |` row. */
  private fun isTableRule(line: String): Boolean =
    line.contains('|') && line.contains('-') && line.all { it in "|:- \t" }

  /** An ATX heading's text without its optional closing run of `#`. */
  private fun closeAtx(rest: String): String {
    val t = rest.trim()
    val stripped = t.trimEnd('#')
    return if (stripped.isEmpty() || stripped.endsWith(' ') || stripped.endsWith('\t')) stripped.trim() else t
  }
}
