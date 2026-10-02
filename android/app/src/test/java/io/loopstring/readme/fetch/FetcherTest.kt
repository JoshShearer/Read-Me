package io.loopstring.readme.fetch

import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

// Plain JVM: Fetcher uses only OkHttp.
class FetcherTest {
  private lateinit var server: MockWebServer

  @Before fun setUp() {
    server = MockWebServer()
    server.start()
  }

  @After fun tearDown() = server.shutdown()

  private fun url(path: String) = server.url(path).toString()

  private fun redirect(to: String) =
    MockResponse().setResponseCode(302).setHeader("Location", to)

  @Test fun returnsTheBody() {
    server.enqueue(MockResponse().setBody("<html>hi</html>"))
    assertEquals(FetchResult.Ok("<html>hi</html>"), Fetcher().fetch(url("/a")))
  }

  @Test fun followsFiveRedirectsAndFailsTheSixth() {
    repeat(5) { server.enqueue(redirect("/r$it")) }
    server.enqueue(MockResponse().setBody("end"))
    assertEquals(FetchResult.Ok("end"), Fetcher().fetch(url("/start")))

    repeat(6) { server.enqueue(redirect("/s$it")) }
    server.enqueue(MockResponse().setBody("never"))
    assertEquals(FetchResult.Failed("too-many-redirects"), Fetcher().fetch(url("/start2")))
  }

  @Test fun aRedirectToANonHttpSchemeFails() {
    server.enqueue(redirect("ftp://example.com/file"))
    assertEquals(FetchResult.Failed("unsupported-redirect"), Fetcher().fetch(url("/a")))
  }

  @Test fun aCookieSetByOneHopIsNotSentOnTheNext() {
    // Review Focus 4.
    server.enqueue(redirect("/next").addHeader("Set-Cookie", "sid=secret; Path=/"))
    server.enqueue(MockResponse().setBody("ok"))
    Fetcher().fetch(url("/a"))
    server.takeRequest()
    assertNull(server.takeRequest().getHeader("Cookie"))
  }

  @Test fun anHttpErrorIsItsStatus() {
    server.enqueue(MockResponse().setResponseCode(404))
    assertEquals(FetchResult.Failed("http-404"), Fetcher().fetch(url("/a")))
  }

  @Test fun theCapIsEnforcedWhileStreamingWithoutContentLength() {
    val cap = 1000L
    server.enqueue(MockResponse().setChunkedBody("x".repeat(1001), 100))
    assertEquals(FetchResult.Failed("too-large"), Fetcher(capBytes = cap).fetch(url("/big")))
    server.enqueue(MockResponse().setChunkedBody("x".repeat(1000), 100))
    assertEquals(FetchResult.Ok("x".repeat(1000)), Fetcher(capBytes = cap).fetch(url("/exact")))
  }

  @Test fun theRealCapIsFiveMebibytes() {
    assertEquals(5L * 1024 * 1024, Fetcher.CAP_BYTES)
    server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(5 * 1024 * 1024 + 1))))
    assertEquals(FetchResult.Failed("too-large"), Fetcher().fetch(url("/huge")))
  }

  @Test fun theDeadlineCoversEveryHop() {
    // Each hop is fast enough alone; together they pass the deadline.
    repeat(3) {
      server.enqueue(redirect("/h$it").setHeadersDelay(300, TimeUnit.MILLISECONDS))
    }
    server.enqueue(MockResponse().setBody("late"))
    assertEquals(FetchResult.Failed("timeout"), Fetcher(timeoutMs = 700).fetch(url("/a")))
  }

  @Test fun anUnreachableHostIsOffline() {
    val dead = MockWebServer()
    dead.start()
    val gone = dead.url("/x").toString()
    dead.shutdown()
    assertEquals(FetchResult.Failed("offline"), Fetcher().fetch(gone))
  }

  @Test fun aMalformedUrlIsBadUrl() {
    assertEquals(FetchResult.Failed("bad-url"), Fetcher().fetch("https://"))
  }

  @Test fun thereIsNoCacheAndNoCookieJar() {
    assertFalse(Fetcher().usesCache)
  }
}
