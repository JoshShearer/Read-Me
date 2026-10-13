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
    assertEquals(HeadRead.Malformed, read("\r\nGET /health HTTP/1.1\r\n\r\n"))
    assertEquals(HeadRead.Malformed, read("\nGET /health HTTP/1.1\r\n\r\n"))
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

  @Test fun rateIsDecimalOnly() {
    // REA-23: toFloat read rate=0x1p1 as 2.0; Java float syntax is not the contract.
    for (ok in listOf("rate=2" to 2.0f, "rate=2.0" to 2.0f, "rate=0.5" to 0.5f, "rate=4" to 4.0f, "rate=1.25" to 1.25f)) {
      assertEquals(ok.first, ok.second, Http.rate(ok.first))
    }
    for (bad in listOf("0x1p1", "2f", "2d", "2.0f", "1e0", "+2", " 2", "2.", ".5", "2,0", "02.0000001x")) {
      assertNull(bad, Http.rate("rate=$bad"))
    }
  }

  @Test fun aBareLfHeadIsMalformedAndLeavesTheBodyUnread() {
    val input = ByteArrayInputStream("GET /health HTTP/1.1\nHost: x\nOrigin: http://localhost\n\nBODY".toByteArray(Charsets.ISO_8859_1))
    var rejected = ""
    assertEquals(HeadRead.Malformed, Http.readHead(input) { rejected = String(it, Charsets.ISO_8859_1) })
    assertTrue(rejected, rejected.contains("Origin: http://localhost"))
    assertEquals(4, input.available())
    // A mix: CRLF lines, then a blank line ended by a bare LF.
    val mixed = ByteArrayInputStream("GET /health HTTP/1.1\r\nHost: x\r\n\nBODY".toByteArray(Charsets.ISO_8859_1))
    assertEquals(HeadRead.Malformed, Http.readHead(mixed))
    assertEquals(4, mixed.available())
    val mixed2 = ByteArrayInputStream("GET /health HTTP/1.1\nHost: x\r\n\r\nBODY".toByteArray(Charsets.ISO_8859_1))
    assertEquals(HeadRead.Malformed, Http.readHead(mixed2))
    assertEquals(4, mixed2.available())
  }

  @Test fun utf8IsStrict() {
    assertEquals("héllo ✓", Http.utf8("héllo ✓".toByteArray(Charsets.UTF_8)))
    assertNull(Http.utf8(byteArrayOf(0xC3.toByte(), 0x28)))
  }
}
