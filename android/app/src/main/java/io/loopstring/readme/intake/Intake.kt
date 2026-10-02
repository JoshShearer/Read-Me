package io.loopstring.readme.intake

import android.net.Uri

/**
 * R-M02 classification and R-M04 text-item paragraphs for the share activity, which runs with
 * no JS runtime. A twin of src/intake/classify.ts and paragraphs.ts; both pass
 * __tests__/fixtures/intake-vectors.json. Never logs and never builds a message from the text.
 */
object Intake {
  sealed class Share {
    data class Link(val url: String) : Share()
    data class Text(val text: String) : Share()
    object Empty : Share()
  }

  // JavaScript's \s, spelled out: Java's \s is ASCII-only.
  private const val JS_SPACE =
    "\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF"
  private const val JS_SPACE_NO_NL =
    "\\t\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF"
  private val SPACE_CHARS: Set<Char> =
    ("\t\n\u000B\u000C\r \u00a0\u1680\u2028\u2029\u202f\u205f\u3000\ufeff" +
        (0x2000..0x200A).map { it.toChar() }.joinToString("")).toSet()

  // JS's \b before "https" is an ASCII word boundary, hence the lookbehind.
  private val URL = Regex(
    "(?<![A-Za-z0-9_])https?://[^$JS_SPACE<>\"'`\\u200B-\\u200F\\u2060\\uFEFF\\u2013\\u2014" +
      "\\u201C\\u201D\\u2018\\u2019\\u00AB\\u00BB\\u2039\\u203A\\u300C\\u300D\\u300E\\u300F" +
      "\\u3001\\u3002\\uFF0C\\uFF1B\\uFF1A\\uFF01\\uFF1F\\u2026]+",
    RegexOption.IGNORE_CASE,
  )
  private const val TRAILING = ".,;:!?'\""
  private val PAIRS = mapOf(')' to '(', ']' to '[', '}' to '{')
  private val CRLF = Regex("\\r\\n?")
  private val BLANK_LINE = Regex("\\n[$JS_SPACE_NO_NL]*\\n")
  private val SPACES = Regex("[$JS_SPACE]+")
  private const val TITLE_MAX = 80

  private fun jsTrim(s: String): String = s.trim { it in SPACE_CHARS }

  private fun trimUrl(raw: String): String {
    var url = raw
    while (url.isNotEmpty()) {
      val last = url.last()
      if (last in TRAILING) {
        url = url.dropLast(1)
        continue
      }
      val open = PAIRS[last]
      if (open != null && url.count { it == open } < url.count { it == last }) {
        url = url.dropLast(1)
        continue
      }
      break
    }
    return url
  }

  fun classify(shared: String): Share {
    if (jsTrim(shared).isEmpty()) return Share.Empty
    val urls = URL.findAll(shared).map { trimUrl(it.value) }.toSet()
    return if (urls.size == 1) Share.Link(urls.first()) else Share.Text(shared)
  }

  fun splitParagraphs(text: String): List<String> =
    text.replace(CRLF, "\n")
      .replace("\u2029", "\n\n")
      .split(BLANK_LINE)
      .map { jsTrim(it.replace(SPACES, " ")) }
      .filter { it.isNotEmpty() }

  /** The first paragraph, cut at the last space within 80 characters. */
  fun textTitle(paragraphs: List<String>): String {
    val first = paragraphs.firstOrNull().orEmpty()
    if (first.length <= TITLE_MAX) return first
    val cut = first.lastIndexOf(' ', TITLE_MAX).takeIf { it > 0 } ?: TITLE_MAX
    return first.substring(0, cut).trimEnd() + "\u2026"
  }

  /** A link item's title until extraction gives one: the host, without "www.". */
  fun hostTitle(url: String): String =
    (Uri.parse(url).host ?: url).lowercase().removePrefix("www.")
}
