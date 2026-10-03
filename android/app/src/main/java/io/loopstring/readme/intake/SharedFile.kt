package io.loopstring.readme.intake

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import io.loopstring.readme.store.ParagraphRow
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * R-C04: a `.md` or `.txt` file shared or opened into Read Me (Obsidian sends ACTION_VIEW of a
 * content URI typed text/markdown). Read once through the sender's grant; nothing is kept but
 * the text. Never fetched, even when the note is only a link: a file share never reaches the
 * network. Never logs and never builds a message from the name or the text.
 */
object SharedFile {
  /** The same cap as a fetched page (AGENTS.md 7), enforced while reading. */
  const val CAP_BYTES = 5L * 1024 * 1024

  sealed class Result {
    data class Note(val title: String, val paragraphs: List<ParagraphRow>) : Result()
    object Empty : Result()
    object TooLarge : Result()
    object Unreadable : Result()
  }

  private val MARKDOWN_TYPES = setOf("text/markdown", "text/x-markdown")
  private val MARKDOWN_NAME = Regex("\\.(md|markdown|mdown|mkd)$", RegexOption.IGNORE_CASE)
  private val EXTENSION = Regex("\\.[A-Za-z0-9]{1,8}$")

  fun read(resolver: ContentResolver, uri: Uri, type: String?): Result {
    val name = runCatching { displayName(resolver, uri) }.getOrNull() ?: uri.lastPathSegment
    val bytes = try {
      resolver.openInputStream(uri)?.use { readCapped(it) } ?: return Result.Unreadable
    } catch (_: TooLarge) {
      return Result.TooLarge
    } catch (_: Exception) {
      return Result.Unreadable
    }
    val markdown = type?.lowercase() in MARKDOWN_TYPES || (name != null && MARKDOWN_NAME.containsMatchIn(name))
    return parse(decode(bytes), markdown, name?.replace(EXTENSION, ""))
  }

  /** Pure: the text of a shared file to an item. [fileTitle] is the name without extension. */
  fun parse(text: String, markdown: Boolean, fileTitle: String?): Result {
    val rows: List<ParagraphRow>
    val heading: String?
    if (markdown) {
      val note = Markdown.toNote(text)
      rows = note.paragraphs
      heading = note.title
    } else {
      rows = Intake.splitParagraphs(text.removePrefix("﻿")).map { ParagraphRow("p", it) }
      heading = null
    }
    if (rows.isEmpty()) return Result.Empty
    val title = heading?.takeIf { it.isNotBlank() }
      ?: fileTitle?.trim()?.takeIf { it.isNotEmpty() }
      ?: Intake.textTitle(rows.map { it.text })
    return Result.Note(title, rows)
  }

  private class TooLarge : Exception()

  private fun readCapped(input: java.io.InputStream): ByteArray {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
      val n = input.read(buf)
      if (n < 0) break
      total += n
      if (total > CAP_BYTES) throw TooLarge()
      out.write(buf, 0, n)
    }
    return out.toByteArray()
  }

  private fun decode(bytes: ByteArray): String =
    Charsets.UTF_8.newDecoder()
      .onMalformedInput(CodingErrorAction.REPLACE)
      .onUnmappableCharacter(CodingErrorAction.REPLACE)
      .decode(ByteBuffer.wrap(bytes))
      .toString()

  private fun displayName(resolver: ContentResolver, uri: Uri): String? =
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
      if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
    }
}
