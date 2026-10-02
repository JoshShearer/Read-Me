package io.loopstring.readme.bridge

import io.loopstring.readme.store.Rate
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Locale

/** One request head. Header names are lower case; a repeated header's values are joined with ", ". */
data class Head(val method: String, val path: String, val query: String, val headers: Map<String, String>)

sealed interface HeadRead {
  data class Ok(val head: Head) : HeadRead
  object TooLarge : HeadRead
  object Malformed : HeadRead
  object Closed : HeadRead
}

/** R-M12 request parsing, with no I/O beyond the stream it is given. Never logs. */
object Http {
  const val MAX_HEAD = 16 * 1024
  const val ABSENT = -2L
  const val INVALID = -1L
  private val DIGITS = Regex("[0-9]{1,10}")

  /** Reads up to and including the blank line, never past it, so the body stays in [input]. */
  fun readHead(input: InputStream, max: Int = MAX_HEAD): HeadRead {
    val buf = ByteArrayOutputStream()
    var matched = 0 // bytes of "\r\n\r\n" seen in a row
    while (true) {
      val c = input.read()
      if (c == -1) return if (buf.size() == 0) HeadRead.Closed else HeadRead.Malformed
      buf.write(c)
      if (buf.size() > max) return HeadRead.TooLarge
      matched = when {
        c == '\r'.code && (matched == 0 || matched == 2) -> matched + 1
        c == '\n'.code && (matched == 1 || matched == 3) -> matched + 1
        c == '\r'.code -> 1
        else -> 0
      }
      if (matched == 4) {
        val bytes = buf.toByteArray()
        return parse(String(bytes, 0, bytes.size - 4, Charsets.ISO_8859_1))
      }
    }
  }

  fun parse(text: String): HeadRead {
    val lines = text.split("\r\n")
    val parts = lines[0].split(' ')
    if (parts.size != 3 || parts.any { it.isEmpty() } || !parts[2].startsWith("HTTP/1.")) return HeadRead.Malformed
    val target = parts[1]
    if (!target.startsWith("/")) return HeadRead.Malformed
    val headers = HashMap<String, String>()
    for (line in lines.drop(1)) {
      val i = line.indexOf(':')
      if (i <= 0) return HeadRead.Malformed
      val name = line.substring(0, i).trim().lowercase(Locale.ROOT)
      val value = line.substring(i + 1).trim()
      val prev = headers[name]
      headers[name] = when {
        prev == null -> value
        // Two different lengths is how a body gets smuggled past a cap.
        name == "content-length" -> if (prev == value) value else return HeadRead.Malformed
        else -> "$prev, $value"
      }
    }
    return HeadRead.Ok(Head(parts[0], target.substringBefore('?'), target.substringAfter('?', ""), headers))
  }

  /** [ABSENT], [INVALID], or the declared length. Digits only: no sign, no hex, no whitespace. */
  fun contentLength(headers: Map<String, String>): Long {
    val v = headers["content-length"] ?: return ABSENT
    return if (DIGITS.matches(v)) v.toLong() else INVALID
  }

  /** AGENTS.md 9: 1.0 unless asked; null when the requested rate is not a number in [Rate.MIN, Rate.MAX]. */
  fun rate(query: String): Float? {
    val raw = query.split('&').firstOrNull { it.startsWith("rate=") }?.removePrefix("rate=") ?: return 1.0f
    val r = raw.toFloatOrNull() ?: return null
    return if (r.isFinite() && r >= Rate.MIN && r <= Rate.MAX) r else null
  }

  /** Strict UTF-8: malformed input is an error, never replacement characters. */
  fun utf8(bytes: ByteArray): String? = try {
    Charsets.UTF_8.newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)
      .decode(ByteBuffer.wrap(bytes)).toString()
  } catch (_: CharacterCodingException) {
    null
  }
}
