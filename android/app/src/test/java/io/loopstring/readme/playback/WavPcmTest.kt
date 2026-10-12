package io.loopstring.readme.playback

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WavPcmTest {
  private fun le32(v: Long) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v.toInt()).array()
  private fun le16(v: Int) = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array()

  private fun chunk(id: String, body: ByteArray, size: Long = body.size.toLong()) =
    id.toByteArray() + le32(size) + body + (if (body.size % 2 == 1) byteArrayOf(0) else byteArrayOf())

  private fun fmt(format: Int = 1, channels: Int = 1, rate: Int = 22050, bits: Int = 16) =
    le16(format) + le16(channels) + le32(rate.toLong()) +
      le32((rate * channels * bits / 8).toLong()) + le16(channels * bits / 8) + le16(bits)

  private fun wav(vararg chunks: ByteArray): ByteArray {
    val body = ByteArrayOutputStream().apply { write("WAVE".toByteArray()); chunks.forEach { write(it) } }.toByteArray()
    return "RIFF".toByteArray() + le32(body.size.toLong()) + body
  }

  private fun parse(b: ByteArray) = WavPcm.readHeader(ByteArrayInputStream(b))

  @Test fun aPlainPcm16MonoFileIsRead() {
    val h = parse(wav(chunk("fmt ", fmt()), chunk("data", ByteArray(100))))!!
    assertEquals(PcmFormat(22050, 1, PcmFormat.Encoding.PCM_16), h.format)
    assertEquals(100L, h.dataBytes)
    assertEquals(2, h.format.frameBytes)
  }

  @Test fun unknownChunksAreSkippedIncludingAnOddSizedOne() {
    val h = parse(wav(chunk("LIST", ByteArray(7)), chunk("fmt ", fmt(rate = 44100)), chunk("fact", ByteArray(4)), chunk("data", ByteArray(8))))!!
    assertEquals(44100, h.format.sampleRate)
    assertEquals(8L, h.dataBytes)
  }

  @Test fun aStreamingHeaderWithNoDataSizeReadsToTheEnd() {
    // Engines that write as they synthesize leave 0 or 0xFFFFFFFF in the data size.
    assertEquals(-1L, parse(wav(chunk("fmt ", fmt()), chunk("data", ByteArray(0), size = 0L)))!!.dataBytes)
    assertEquals(-1L, parse(wav(chunk("fmt ", fmt()), "data".toByteArray() + le32(0xFFFFFFFFL)))!!.dataBytes)
  }

  @Test fun floatAndEightBitAndStereoAreSupported() {
    assertEquals(PcmFormat.Encoding.FLOAT, parse(wav(chunk("fmt ", fmt(format = 3, bits = 32)), chunk("data", ByteArray(8))))!!.format.encoding)
    assertEquals(PcmFormat.Encoding.PCM_8, parse(wav(chunk("fmt ", fmt(bits = 8)), chunk("data", ByteArray(8))))!!.format.encoding)
    assertEquals(4, parse(wav(chunk("fmt ", fmt(channels = 2)), chunk("data", ByteArray(8))))!!.format.frameBytes)
  }

  @Test fun anExtensibleFormatUsesItsSubFormat() {
    val ext = fmt(format = 0xFFFE, bits = 16) + le16(22) + le16(16) + le32(4) + le16(1) + ByteArray(14)
    assertEquals(PcmFormat.Encoding.PCM_16, parse(wav(chunk("fmt ", ext), chunk("data", ByteArray(8))))!!.format.encoding)
  }

  @Test fun anythingElseIsRefused() {
    assertNull(parse(ByteArray(0)))
    assertNull(parse("Hello there, not a wave file at all.".toByteArray()))
    assertNull(parse(wav(chunk("fmt ", fmt(format = 2)), chunk("data", ByteArray(8))))) // ADPCM
    assertNull(parse(wav(chunk("fmt ", fmt(bits = 24)), chunk("data", ByteArray(8)))))
    assertNull(parse(wav(chunk("fmt ", fmt(channels = 3)), chunk("data", ByteArray(8)))))
    assertNull(parse(wav(chunk("fmt ", fmt(rate = 1)), chunk("data", ByteArray(8)))))
    assertNull(parse(wav(chunk("data", ByteArray(8))))) // data before fmt
    assertNull(parse(wav(chunk("fmt ", fmt())))) // no data chunk
    // A chunk claiming more than the file holds, before any data: refused, not read forever.
    assertNull(parse(wav(chunk("fmt ", fmt()), chunk("LIST", ByteArray(2), size = 0x7FFFFFF0L))))
  }

  @Test fun tooManyChunksAreRefused() {
    val junk = (1..WavPcm.MAX_CHUNKS + 1).map { chunk("junk", ByteArray(0)) }.toTypedArray()
    assertNull(parse(wav(chunk("fmt ", fmt()), *junk, chunk("data", ByteArray(8)))))
  }
}
