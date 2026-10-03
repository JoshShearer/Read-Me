package io.loopstring.readme.intake

import io.loopstring.readme.store.ParagraphRow

/**
 * R-C04: a shared markdown note (Obsidian's file Share) read as prose, so the engine never says
 * "hash" or "asterisk". Line-based and forgiving rather than a CommonMark parser: the output is
 * listened to, not rendered. Never logs and never builds a message from the text.
 */
object Markdown {
  data class Note(val title: String?, val paragraphs: List<ParagraphRow>)

  private val CRLF = Regex("\\r\\n?")
  private val FRONT_MATTER = Regex("\\A---[ \\t]*\\n.*?\\n(?:---|\\.\\.\\.)[ \\t]*(?:\\n|\\z)", RegexOption.DOT_MATCHES_ALL)
  private val OBSIDIAN_COMMENT = Regex("%%.*?%%", RegexOption.DOT_MATCHES_ALL)
  private val HTML_COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
  private val FENCE = Regex("^\\s{0,3}(```+|~~~+)")
  private val ATX = Regex("^\\s{0,3}#{1,6}(?:\\s+(.*?))?\\s*#*\\s*$")
  private val SETEXT = Regex("^\\s{0,3}(=+|-+)\\s*$")
  private val RULE = Regex("^\\s{0,3}(?:(?:\\*\\s*){3,}|(?:-\\s*){3,}|(?:_\\s*){3,})$")
  private val ITEM = Regex("^\\s*(?:[-*+]|\\d{1,9}[.)])\\s+(?:\\[[ xX/-]\\]\\s+)?(.*)$")
  private val QUOTE = Regex("^\\s{0,3}>\\s?")
  private val CALLOUT = Regex("^\\[!\\w+][+-]?\\s*")
  private val TABLE_RULE = Regex("^\\s*\\|?\\s*:?-+:?\\s*(?:\\|\\s*:?-+:?\\s*)*\\|?\\s*$")
  private val FOOTNOTE_DEF = Regex("^\\[\\^[^\\]]+]:\\s*")
  private val INDENTED = Regex("^(?: {2,}|\\t)\\S")

  private val EMBED = Regex("!\\[\\[[^\\]]*]]|!\\[[^\\]]*]\\([^)]*\\)")
  private val WIKILINK = Regex("\\[\\[([^\\]|]*)(?:\\|([^\\]]*))?]]")
  private val LINK = Regex("\\[([^\\]]*)]\\([^)]*\\)")
  private val REF_LINK = Regex("\\[([^\\]]+)]\\[[^\\]]*]")
  private val AUTOLINK = Regex("<(https?://[^>\\s]+)>")
  private val FOOTNOTE_REF = Regex("\\[\\^[^\\]]+]")
  private val CODE = Regex("`+([^`]*)`+")
  private val STRONG = Regex("(\\*\\*|__)(?=\\S)(.+?)(?<=\\S)\\1")
  private val EM_STAR = Regex("\\*(?=\\S)(.+?)(?<=\\S)\\*")
  private val EM_UNDERSCORE = Regex("(?<![\\p{L}\\p{N}_])_(?=\\S)(.+?)(?<=\\S)_(?![\\p{L}\\p{N}_])")
  private val STRIKE_HIGHLIGHT = Regex("(~~|==)(?=\\S)(.+?)(?<=\\S)\\1")
  private val TAG = Regex("(?<![\\p{L}\\p{N}_&/])#([\\p{L}_][\\p{L}\\p{N}_/-]*)")
  private val BLOCK_ID = Regex("\\s\\^[A-Za-z0-9-]+\\s*$")
  private val HTML_TAG = Regex("</?[A-Za-z][^>]*>")
  private val ESCAPE = Regex("\\\\([\\\\`*_{}\\[\\]()#+\\-.!|~=<>])")
  private val SPACES = Regex("\\s+")
  private const val PARK = 0xE000
  private val PARKED = Regex("[\\uE000-\\uE07F]")

  fun toNote(markdown: String): Note {
    val text = markdown.removePrefix("﻿").replace(CRLF, "\n")
      .replaceFirst(FRONT_MATTER, "")
      .replace(OBSIDIAN_COMMENT, "")
      .replace(HTML_COMMENT, "")
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
        while (QUOTE.containsMatchIn(line)) line = line.replaceFirst(QUOTE, "")
        line = line.replaceFirst(CALLOUT, "")
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
      if (RULE.matches(line)) {
        flush()
        continue
      }
      val atx = ATX.find(line)
      if (atx != null) {
        flush()
        append("heading", atx.groupValues[1])
        flush()
        continue
      }
      if (TABLE_RULE.matches(line) && line.contains('-') && line.contains('|')) continue
      if (line.trimStart().startsWith("|")) {
        flush()
        append("p", line.trim().trim('|').split('|').joinToString(", ") { it.trim() })
        flush()
        continue
      }
      val item = ITEM.find(line)
      if (item != null) {
        flush()
        append("li", item.groupValues[1])
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
      t = STRONG.replace(t) { it.groupValues[2] }
      t = STRIKE_HIGHLIGHT.replace(t) { it.groupValues[2] }
      t = EM_STAR.replace(t) { it.groupValues[1] }
      t = EM_UNDERSCORE.replace(t) { it.groupValues[1] }
    }
    t = TAG.replace(t) { it.groupValues[1] }
    t = BLOCK_ID.replace(t, "")
    t = PARKED.replace(t) { (it.value[0].code - PARK).toChar().toString() }
    return t.replace(SPACES, " ").trim()
  }
}
