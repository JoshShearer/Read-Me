package io.loopstring.readme.playback

import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/** The sample layout of a synthesized file, as Read Me's player needs it (REA-31). */
data class PcmFormat(val sampleRate: Int, val channels: Int, val encoding: Encoding) {
  enum class Encoding(val bytes: Int) { PCM_8(1), PCM_16(2), FLOAT(4) }

  val frameBytes: Int get() = channels * encoding.bytes
}

/**
 * REA-31 (ADR 0012): reads the header of a WAV the engine wrote with synthesizeToFile, so the
 * player can stream its samples. Unknown chunks are skipped; a data size of 0 or 0xFFFFFFFF
 * (engines that write while they synthesize) means "to the end of the file". Anything but
 * 8- or 16-bit PCM or 32-bit float, mono or stereo, is refused: the player counts that sentence
 * as an error rather than play noise.
 */
object WavPcm {
  /** [dataBytes] is -1 when the header does not say; the stream is left at the first sample. */
  data class Header(val format: PcmFormat, val dataBytes: Long)

  const val MAX_CHUNKS = 64
  private const val MAX_FMT_BYTES = 1_024L

  /** Milliseconds of audio in [file], or -1 when it is not a WAV the player takes. */
  fun durationMs(file: java.io.File): Long = try {
    java.io.FileInputStream(file).use { input ->
      val h = parse(input.buffered()) ?: return -1L
      val bytes = if (h.dataBytes >= 0) h.dataBytes else (file.length() - 44).coerceAtLeast(0)
      bytes * 1000 / (h.format.sampleRate.toLong() * h.format.frameBytes)
    }
  } catch (e: IOException) {
    -1L
  }

  fun readHeader(input: InputStream): Header? = try {
    parse(input)
  } catch (e: IOException) {
    null
  }

  private fun parse(input: InputStream): Header? {
    if (tag(input) != "RIFF") return null
    u32(input)
    if (tag(input) != "WAVE") return null
    var format: PcmFormat? = null
    repeat(MAX_CHUNKS) {
      val id = tag(input)
      val size = u32(input)
      when (id) {
        "fmt " -> {
          if (size < 16 || size > MAX_FMT_BYTES) return null
          val body = ByteArray(size.toInt())
          readFully(input, body)
          format = fmt(body) ?: return null
          if (size % 2 == 1L) skip(input, 1)
        }
        "data" -> {
          val f = format ?: return null
          val unknown = size == 0L || size == 0xFFFFFFFFL
          return Header(f, if (unknown) -1L else size)
        }
        else -> skip(input, size + (size and 1L))
      }
    }
    return null
  }

  private fun fmt(b: ByteArray): PcmFormat? {
    var tag = le16(b, 0)
    val channels = le16(b, 2)
    val rate = le32(b, 4)
    val bits = le16(b, 14)
    // WAVE_FORMAT_EXTENSIBLE: the real format is the first two bytes of the sub-format GUID.
    if (tag == 0xFFFE) {
      if (b.size < 26) return null
      tag = le16(b, 24)
    }
    val enc = when {
      tag == 1 && bits == 8 -> PcmFormat.Encoding.PCM_8
      tag == 1 && bits == 16 -> PcmFormat.Encoding.PCM_16
      tag == 3 && bits == 32 -> PcmFormat.Encoding.FLOAT
      else -> return null
    }
    if (channels !in 1..2 || rate !in 1_000L..192_000L) return null
    return PcmFormat(rate.toInt(), channels, enc)
  }

  private fun tag(input: InputStream): String {
    val b = ByteArray(4)
    readFully(input, b)
    return String(b, Charsets.US_ASCII)
  }

  private fun u32(input: InputStream): Long {
    val b = ByteArray(4)
    readFully(input, b)
    return le32(b, 0)
  }

  private fun le16(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

  private fun le32(b: ByteArray, at: Int): Long =
    (le16(b, at).toLong()) or (le16(b, at + 2).toLong() shl 16)

  private fun readFully(input: InputStream, b: ByteArray) {
    var off = 0
    while (off < b.size) {
      val n = input.read(b, off, b.size - off)
      if (n < 0) throw EOFException()
      off += n
    }
  }

  /** Skips exactly [n] bytes, or fails: a chunk longer than the file is not a WAV to play. */
  private fun skip(input: InputStream, n: Long) {
    var left = n
    val buf = ByteArray(4_096)
    while (left > 0) {
      val r = input.read(buf, 0, minOf(left, buf.size.toLong()).toInt())
      if (r < 0) throw EOFException()
      left -= r
    }
  }
}
