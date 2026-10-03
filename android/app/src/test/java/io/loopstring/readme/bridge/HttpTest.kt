package io.loopstring.readme.bridge

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpTest {
  private fun read(s: String, max: Int = Http.MAX_HEAD) =
    Http.readHead(ByteArrayInputStream(s.toByteArray(Charsets.ISO_8859_1)), max)

  @Test fun readsAHeadAndStopsAtTheBlankLine() {
    val input = ByteArrayInputStream("POST /synthesize?rate=2.0 HTTP/1.1\r\nHost: x\r\nContent-Length: 5\r\n\r\nhello".toByteArray())
    val r = Http.readHead(input) as HeadRead.Ok
    assertEquals("POST", r.head.method)
    assertEquals("/synthesize", r.head.path)
    assertEquals("rate=2.0", r.head.query)
    assertEquals("5", r.head.headers["content-length"])
    assertEquals("hello", String(input.readBytes()))
  }

  @Test fun headerNamesAreCaseInsensitive() {
    val r = read("GET /health HTTP/1.1\r\nORIGIN: http://localhost\r\n\r\n") as HeadRead.Ok
    assertEquals("http://localhost", r.head.headers["origin"])
  }

  @Test fun aHeadOverTheCapIsTooLarge() {
    val big = "GET /health HTTP/1.1\r\nX: " + "a".repeat(20_000) + "\r\n\r\n"
    assertEquals(HeadRead.TooLarge, read(big))
  }

  @Test fun anEmptyConnectionIsClosedAndAPartialHeadIsMalformed() {
    assertEquals(HeadRead.Closed, read(""))
    assertEquals(HeadRead.Malformed, read("GET /health HTTP/1.1\r\nHost"))
  }

  @Test fun badRequestLinesAreMalformed() {
    for (line in listOf("GET /health", "GET health HTTP/1.1", "GET /health SPDY/3", "GET  /health HTTP/1.1")) {
      assertEquals(line, HeadRead.Malformed, read("$line\r\n\r\n"))
    }
    assertEquals(HeadRead.Malformed, read("GET /health HTTP/1.1\r\nno colon here\r\n\r\n"))
  }

  @Test fun conflictingContentLengthsAreMalformed() {
    assertEquals(HeadRead.Malformed, read("POST /synthesize HTTP/1.1\r\nContent-Length: 5\r\nContent-Length: 6\r\n\r\n"))
    assertTrue(read("POST /synthesize HTTP/1.1\r\nContent-Length: 5\r\nContent-Length: 5\r\n\r\n") is HeadRead.Ok)
  }

  @Test fun contentLengthIsDigitsOnly() {
    fun cl(v: String?) = Http.contentLength(if (v == null) emptyMap() else mapOf("content-length" to v))
    assertEquals(Http.ABSENT, cl(null))
    assertEquals(5L, cl("5"))
    assertEquals(0L, cl("0"))
    assertEquals(65537L, cl("65537"))
    for (bad in listOf("abc", "-5", "+5", "5.0", "", " ", "99999999999", "0x10")) assertEquals(bad, Http.INVALID, cl(bad))
  }

  @Test fun rateDefaultsTo1AndIsBounded() {
    assertEquals(1.0f, Http.rate(""))
    assertEquals(1.0f, Http.rate("x=1"))
    assertEquals(2.0f, Http.rate("rate=2.0"))
    assertEquals(0.5f, Http.rate("a=b&rate=0.5"))
    assertEquals(4.0f, Http.rate("rate=4"))
    for (bad in listOf("rate=", "rate=0.4", "rate=4.1", "rate=NaN", "rate=Infinity", "rate=abc", "rate=-1")) {
      assertNull(bad, Http.rate(bad))
    }
  }

  @Test fun utf8IsStrict() {
    assertEquals("héllo ✓", Http.utf8("héllo ✓".toByteArray(Charsets.UTF_8)))
    assertNull(Http.utf8(byteArrayOf(0xC3.toByte(), 0x28)))
  }
}
