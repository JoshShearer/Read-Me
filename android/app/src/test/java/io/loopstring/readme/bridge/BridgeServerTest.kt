package io.loopstring.readme.bridge

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.Socket
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BridgeServerTest {
  private val token = "0123456789abcdef0123456789abcdef"
  private val text = "Secret item sentence."
  private lateinit var cache: File
  private lateinit var synth: FakeSynth
  private val logs: MutableList<String> = Collections.synchronizedList(ArrayList())
  @Volatile private var busy = false
  @Volatile private var currentToken = token
  private var server: BridgeServer? = null

  class FakeSynth : Synthesizer {
    @Volatile override var ready = true
    override val engine = "test.engine"
    override val voice: String? = "test-voice"
    override val maxChars = 100
    @Volatile var lastRate = 0f
    @Volatile var behaviour: (File) -> SynthResult = { it.writeBytes(wav(1_000)); SynthResult.OK }
    val cancelled = CountDownLatch(1)
    override fun synthesize(text: String, rate: Float, out: File, proceed: () -> Boolean): SynthResult {
      lastRate = rate
      if (!proceed()) return SynthResult.CANCELLED
      return behaviour(out)
    }
    override fun cancel() = cancelled.countDown()
  }

  @Before fun setUp() {
    cache = Files.createTempDirectory("cache").toFile()
    synth = FakeSynth()
  }

  @After fun tearDown() {
    server?.close()
  }

  private fun start(readTimeoutMs: Int = 2_000, deadlineMs: Long = 180_000, workers: Int = 8, logDelayMs: Long = 0): BridgeServer =
    BridgeServer(0, { currentToken }, { busy }, synth, BridgeFiles(cache), { logs.add(it); Thread.sleep(logDelayMs) }, readTimeoutMs, deadlineMs, workers)
      .also { server = it }

  data class Response(val status: Int, val headers: Map<String, String>, val body: ByteArray) {
    fun text() = String(body, Charsets.UTF_8)
  }

  /** Sends [head] (CRLF lines, without the final blank line) and [body]; reads the whole response. */
  private fun call(port: Int, head: String, body: ByteArray = ByteArray(0), timeoutMs: Int = 5_000): Response {
    Socket("127.0.0.1", port).use { s ->
      s.soTimeout = timeoutMs
      s.getOutputStream().apply { write("$head\r\n\r\n".toByteArray(Charsets.ISO_8859_1)); write(body); flush() }
      return parse(s.getInputStream())
    }
  }

  private fun parse(input: InputStream): Response {
    val all = input.readBytes()
    val split = String(all, Charsets.ISO_8859_1).indexOf("\r\n\r\n")
    val lines = String(all, 0, split, Charsets.ISO_8859_1).split("\r\n")
    val headers = lines.drop(1).associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
    return Response(lines[0].split(' ')[1].toInt(), headers, all.copyOfRange(split + 4, all.size))
  }

  private fun synthHead(len: Int, auth: String? = "Bearer $token", query: String = "", origin: String? = null) =
    listOfNotNull(
      "POST /synthesize$query HTTP/1.1",
      "Content-Length: $len",
      auth?.let { "Authorization: $it" },
      origin?.let { "Origin: $it" },
    ).joinToString("\r\n")

  @Test fun bindsIPv4LoopbackExplicitly() {
    assertEquals("127.0.0.1", start().address.hostAddress)
  }

  @Test fun healthIsOpenAndReportsState() {
    val s = start()
    val r = call(s.port, "GET /health HTTP/1.1")
    assertEquals(200, r.status)
    assertEquals("application/json", r.headers["content-type"])
    assertEquals(
      """{"ok":true,"version":1,"ttsReady":true,"engine":"test.engine","voice":"test-voice","port":${s.port},"busy":false,"maxChars":100}""",
      r.text(),
    )
    busy = true
    assertTrue(call(s.port, "GET /health HTTP/1.1").text().contains("\"busy\":true"))
  }

  @Test fun preflightFromLocalhostIsAnswered() {
    val s = start()
    val r = call(s.port, "OPTIONS /synthesize HTTP/1.1\r\nOrigin: http://localhost\r\nAccess-Control-Request-Method: POST")
    assertEquals(204, r.status)
    assertEquals("http://localhost", r.headers["access-control-allow-origin"])
    assertEquals("GET, POST", r.headers["access-control-allow-methods"])
    assertEquals("Authorization, Content-Type", r.headers["access-control-allow-headers"])
    assertEquals("X-Synth-Ms, X-Rate", r.headers["access-control-expose-headers"])
  }

  @Test fun everyResponseToLocalhostCarriesCorsErrorsIncluded() {
    val s = start()
    val o = "http://localhost"
    val responses = listOf(
      call(s.port, "GET /health HTTP/1.1\r\nOrigin: $o"),
      call(s.port, synthHead(5, auth = null, origin = o), "hello".toByteArray()),
      call(s.port, synthHead(70_000, origin = o)),
      call(s.port, "POST /synthesize HTTP/1.1\r\nContent-Length: abc\r\nAuthorization: Bearer $token\r\nOrigin: $o"),
      call(s.port, "GET /nope HTTP/1.1\r\nOrigin: $o"),
      call(s.port, "GET /synthesize HTTP/1.1\r\nOrigin: $o"),
      call(s.port, synthHead(5, origin = o), "hello".toByteArray()),
    ).also { busy = false }
    busy = true
    val busyReply = call(s.port, synthHead(5, origin = o))
    for (r in responses + busyReply) {
      assertEquals("status ${r.status}", o, r.headers["access-control-allow-origin"])
      assertEquals("status ${r.status}", "X-Synth-Ms, X-Rate", r.headers["access-control-expose-headers"])
    }
    assertEquals(listOf(200, 401, 413, 400, 404, 405, 200, 503), (responses + busyReply).map { it.status })
  }

  @Test fun anotherOriginGetsNoCors() {
    val s = start()
    for (head in listOf("GET /health HTTP/1.1\r\nOrigin: http://evil.example", "GET /health HTTP/1.1")) {
      val r = call(s.port, head)
      assertEquals(200, r.status)
      assertNull(r.headers["access-control-allow-origin"])
      assertNull(r.headers["access-control-expose-headers"])
    }
  }

  @Test fun aMissingOrWrongTokenIs401BeforeTheBody() {
    val s = start()
    // The body is never sent: a 401 that waited for it would time out instead.
    for (auth in listOf(null, "Bearer wrong", "Bearer ${token}x", "bearer $token", "Bearer ", token)) {
      val r = call(s.port, synthHead(65_536, auth = auth), timeoutMs = 3_000)
      assertEquals("auth=$auth", 401, r.status)
      assertEquals("""{"error":"unauthorized"}""", r.text())
    }
  }

  @Test fun aRegeneratedTokenTakesEffectAtOnce() {
    val s = start()
    assertEquals(200, call(s.port, synthHead(5), "hello".toByteArray()).status)
    currentToken = "ffffffffffffffffffffffffffffffff"
    assertEquals(401, call(s.port, synthHead(5), "hello".toByteArray()).status)
    assertEquals(200, call(s.port, synthHead(5, auth = "Bearer $currentToken"), "hello".toByteArray()).status)
  }

  @Test fun anOversizedHeadIs431() {
    val s = start()
    assertEquals(431, call(s.port, "GET /health HTTP/1.1\r\nX-Pad: " + "a".repeat(20_000)).status)
  }

  @Test fun lengthsAreChecked() {
    val s = start()
    fun status(head: String) = call(s.port, head, timeoutMs = 3_000).status
    val auth = "Authorization: Bearer $token"
    assertEquals(400, status("POST /synthesize HTTP/1.1\r\n$auth"))
    assertEquals(400, status("POST /synthesize HTTP/1.1\r\n$auth\r\nContent-Length: abc"))
    assertEquals(400, status("POST /synthesize HTTP/1.1\r\n$auth\r\nContent-Length: -5"))
    assertEquals(400, status("POST /synthesize HTTP/1.1\r\n$auth\r\nContent-Length: 5\r\nContent-Length: 6"))
    assertEquals(400, status("POST /synthesize HTTP/1.1\r\n$auth\r\nTransfer-Encoding: chunked"))
    assertEquals(413, status("POST /synthesize HTTP/1.1\r\n$auth\r\nContent-Length: 65537"))
    assertEquals(413, status("POST /synthesize HTTP/1.1\r\n$auth\r\nContent-Length: 2000000000"))
  }

  @Test fun rateDefaultsTo1AndIsAppliedByTheEngineOnce() {
    val s = start()
    val r1 = call(s.port, synthHead(5), "hello".toByteArray())
    assertEquals("1.0", r1.headers["x-rate"])
    assertEquals(1.0f, synth.lastRate)
    val r2 = call(s.port, synthHead(5, query = "?rate=2.0"), "hello".toByteArray())
    assertEquals("2.0", r2.headers["x-rate"])
    assertEquals(2.0f, synth.lastRate)
    assertEquals(400, call(s.port, synthHead(5, query = "?rate=9"), "hello".toByteArray()).status)
  }

  @Test fun synthesisReturnsTheWavAndDeletesTheFile() {
    val s = start()
    val r = call(s.port, synthHead(5), "hello".toByteArray())
    assertEquals(200, r.status)
    assertEquals("audio/wav", r.headers["content-type"])
    assertEquals(wav(1_000).toList(), r.body.toList())
    assertTrue(r.headers["x-synth-ms"]!!.toLong() >= 0)
    assertEquals(0, cache.listFiles()!!.size)
  }

  @Test fun failuresAndTimeoutsDeleteTheFileToo() {
    val s = start()
    synth.behaviour = { it.writeBytes(wav(10)); SynthResult.FAILED }
    assertEquals(500, call(s.port, synthHead(5), "hello".toByteArray()).status)
    synth.behaviour = { it.writeBytes(wav(10)); SynthResult.TIMEOUT }
    assertEquals(504, call(s.port, synthHead(5), "hello".toByteArray()).status)
    synth.behaviour = { SynthResult.OK } // OK but an empty file
    assertEquals(500, call(s.port, synthHead(5), "hello".toByteArray()).status)
    assertEquals(0, cache.listFiles()!!.size)
  }

  @Test fun notReadyIs503() {
    synth.ready = false
    val s = start()
    val r = call(s.port, synthHead(5), "hello".toByteArray())
    assertEquals(503, r.status)
    assertEquals("""{"error":"tts-not-ready"}""", r.text())
  }

  @Test fun busyIs503BeforeTheBody() {
    busy = true
    val s = start()
    val r = call(s.port, synthHead(65_536), timeoutMs = 3_000)
    assertEquals(503, r.status)
    assertEquals("""{"error":"busy","reason":"playback"}""", r.text())
  }

  @Test fun playbackStartingPreemptsTheSynthesisInFlight() {
    val s = start()
    val started = CountDownLatch(1)
    synth.behaviour = { f ->
      f.writeBytes(wav(10))
      started.countDown()
      if (synth.cancelled.await(5, TimeUnit.SECONDS)) SynthResult.CANCELLED else SynthResult.OK
    }
    var r: Response? = null
    val t = Thread { r = call(s.port, synthHead(5), "hello".toByteArray()) }.apply { start() }
    assertTrue(started.await(5, TimeUnit.SECONDS))
    s.preempt()
    t.join(5_000)
    assertEquals(503, r!!.status)
    assertEquals("""{"error":"busy","reason":"playback"}""", r!!.text())
    assertEquals(0, cache.listFiles()!!.size)
  }

  @Test fun textIsCheckedAfterTheBodyArrives() {
    val s = start()
    val long = "a".repeat(101).toByteArray()
    val r = call(s.port, synthHead(long.size), long)
    assertEquals(413, r.status)
    assertEquals("""{"error":"too-long","maxChars":100}""", r.text())
    assertEquals(400, call(s.port, synthHead(2), byteArrayOf(0xC3.toByte(), 0x28)).status)
    assertEquals(400, call(s.port, synthHead(3), "   ".toByteArray()).status)
    // A body shorter than its Content-Length, then the client closes its side: 400, not a hang.
    Socket("127.0.0.1", s.port).use { c ->
      c.soTimeout = 5_000
      c.getOutputStream().write("${synthHead(10)}\r\n\r\nshort".toByteArray())
      c.shutdownOutput()
      assertEquals(400, parse(c.getInputStream()).status)
    }
  }

  @Test fun unknownRoutesAndMethods() {
    val s = start()
    assertEquals(404, call(s.port, "GET /speak HTTP/1.1").status)
    assertEquals(405, call(s.port, "POST /health HTTP/1.1\r\nContent-Length: 0").status)
    assertEquals(405, call(s.port, "GET /synthesize HTTP/1.1").status)
  }

  @Test fun aSilentClientDoesNotBlockHealth() {
    val s = start(readTimeoutMs = 10_000)
    Socket("127.0.0.1", s.port).use {
      val t0 = System.nanoTime()
      assertEquals(200, call(s.port, "GET /health HTTP/1.1").status)
      assertTrue((System.nanoTime() - t0) / 1_000_000 < 2_000)
    }
  }

  @Test fun slowBodyTimesOutAndFreesTheWorker() {
    // The worker closes the socket, then logs, then returns to the pool. A slow log widens that
    // window, which CI hit once (an empty /health response on a 1-worker server).
    val s = start(readTimeoutMs = 300, workers = 1, logDelayMs = 300)
    Socket("127.0.0.1", s.port).use { c ->
      c.soTimeout = 3_000
      c.getOutputStream().write("${synthHead(10)}\r\n\r\nhalf".toByteArray())
      // The server gives up on the body and closes; the client sees end of stream, no response.
      assertEquals(-1, c.getInputStream().read())
    }
    // A connection that arrives before the worker is back is refused (closed, no bytes).
    val deadline = System.nanoTime() + 2_000_000_000L
    var health = runCatching { call(s.port, "GET /health HTTP/1.1").status }
    while (health.getOrNull() != 200 && System.nanoTime() < deadline) {
      Thread.sleep(50)
      health = runCatching { call(s.port, "GET /health HTTP/1.1").status }
    }
    assertEquals(200, health.getOrThrow())
  }

  @Test fun allWorkersBusyRefusesThenRecovers() {
    val s = start(readTimeoutMs = 300, workers = 2)
    val silent = List(2) { Socket("127.0.0.1", s.port) }
    Thread.sleep(100)
    // A third connection finds no worker and is closed.
    val refused = runCatching { call(s.port, "GET /health HTTP/1.1", timeoutMs = 1_000) }
    assertTrue(refused.isFailure || refused.getOrNull()?.status == 200)
    Thread.sleep(600)
    assertEquals(200, call(s.port, "GET /health HTTP/1.1").status)
    silent.forEach { it.close() }
  }

  @Test fun aClientThatNeverReadsIsCutOffAtTheDeadline() {
    val s = start(deadlineMs = 500, workers = 1)
    synth.behaviour = { it.writeBytes(ByteArray(16 * 1024 * 1024)); SynthResult.OK }
    Socket("127.0.0.1", s.port).use { c ->
      c.getOutputStream().write("${synthHead(5)}\r\n\r\nhello".toByteArray())
      // Never read: the server's write fills the buffers and blocks until the deadline closes it.
      Thread.sleep(1_500)
      assertEquals(200, call(s.port, "GET /health HTTP/1.1", timeoutMs = 3_000).status)
    }
    Thread.sleep(200)
    assertEquals(0, cache.listFiles()!!.size)
  }

  @Test fun aThrowingSynthesizerDoesNotStopTheServer() {
    val s = start()
    synth.behaviour = { throw OutOfMemoryError("test") }
    runCatching { call(s.port, synthHead(5), "hello".toByteArray(), timeoutMs = 2_000) }
    synth.behaviour = { throw IllegalStateException("test") }
    runCatching { call(s.port, synthHead(5), "hello".toByteArray(), timeoutMs = 2_000) }
    assertEquals(200, call(s.port, "GET /health HTTP/1.1").status)
    assertTrue(logs.any { it.contains("OutOfMemoryError") })
  }

  @Test fun anUnreadBodyIsDrainedSoTheClientReadsTheStatus() {
    val s = start()
    // ADR 0004: 64 KiB sent after a 401 must not turn the reply into a reset.
    val r = call(s.port, synthHead(65_536, auth = null), ByteArray(65_536) { 'a'.code.toByte() })
    assertEquals(401, r.status)
  }

  @Test fun noLogLineCarriesTheTokenOrTheText() {
    val s = start()
    call(s.port, synthHead(text.length), text.toByteArray())
    call(s.port, synthHead(text.length, auth = "Bearer nope"), text.toByteArray())
    call(s.port, "GET /health?$token HTTP/1.1")
    call(s.port, "BREW /$token HTTP/1.1")
    Thread.sleep(200)
    assertTrue(logs.isNotEmpty())
    for (l in logs) {
      assertFalse(l, l.contains(token))
      assertFalse(l, l.contains("Secret"))
      assertFalse(l, l.contains("BREW"))
    }
  }

  @Test fun aTakenPortThrows() {
    val s = start()
    val e = runCatching {
      BridgeServer(s.port, { token }, { false }, synth, BridgeFiles(cache), {})
    }.exceptionOrNull()
    assertTrue(e is java.net.BindException)
  }

  @Test fun playbackStartingAtTheLockedBusyCheckStillGets503() {
    // Final review Important 1: playback starts between the second busy() check and the
    // synthesis; the request must be answered 503, not synthesized alongside playback.
    val calls = AtomicInteger()
    var srv: BridgeServer? = null
    val ran = AtomicInteger()
    synth.behaviour = { f -> ran.incrementAndGet(); f.writeBytes(wav(10)); SynthResult.OK }
    srv = BridgeServer(0, { token }, { if (calls.incrementAndGet() == 2) srv!!.preempt(); false }, synth, BridgeFiles(cache), { logs.add(it) })
      .also { server = it }
    val r = call(srv.port, synthHead(5), "hello".toByteArray())
    assertEquals(503, r.status)
    assertEquals(0, ran.get())
  }

  @Test fun aRequestWhoseClientLeftWhileQueuedIsNotSynthesized() {
    // Final review Important 2: a request waiting for the synthesis lock whose client has gone
    // must not synthesize text nobody will read.
    val s = start()
    val release = CountDownLatch(1)
    val calls = AtomicInteger()
    synth.behaviour = { f -> calls.incrementAndGet(); release.await(5, TimeUnit.SECONDS); f.writeBytes(wav(10)); SynthResult.OK }
    val first = Thread { runCatching { call(s.port, synthHead(5), "hello".toByteArray()) } }.apply { start() }
    Thread.sleep(300)
    Socket("127.0.0.1", s.port).use { c ->
      c.getOutputStream().write("${synthHead(5)}\r\n\r\nhello".toByteArray())
      Thread.sleep(300)
    }
    release.countDown()
    first.join(5_000)
    Thread.sleep(500)
    assertEquals(1, calls.get())
  }

  @Test fun aDrippedHeadIsCutAtTheHeadDeadline() {
    // /critique F1: one byte at a time reset only the per-read timeout, so a dripping client
    // held a worker until the 180 s deadline. The head now has its own short deadline.
    val s = BridgeServer(0, { token }, { busy }, synth, BridgeFiles(cache), { logs.add(it) },
      readTimeoutMs = 2_000, workers = 1, headDeadlineMs = 500).also { server = it }
    val dripper = Thread {
      runCatching {
        Socket("127.0.0.1", s.port).use { c ->
          for (b in "GET /health HTTP/1.1\r\nX-Slow: aaaaaaaaaaaaaaaaaaaa".toByteArray()) {
            c.getOutputStream().write(b.toInt()); Thread.sleep(150)
          }
        }
      }
    }.apply { start() }
    Thread.sleep(1_200)
    assertEquals(200, call(s.port, "GET /health HTTP/1.1", timeoutMs = 3_000).status)
    dripper.interrupt()
  }

  @Test fun queuedSynthesesAreCappedSoHealthStillAnswers() {
    // /critique F1: requests waiting for the synthesis lock each held a worker.
    val s = BridgeServer(0, { token }, { busy }, synth, BridgeFiles(cache), { logs.add(it) },
      workers = 4, maxQueued = 1).also { server = it }
    val release = CountDownLatch(1)
    synth.behaviour = { f -> release.await(10, TimeUnit.SECONDS); f.writeBytes(wav(10)); SynthResult.OK }
    val statuses = Collections.synchronizedList(ArrayList<Int>())
    val threads = List(3) { Thread { runCatching { statuses.add(call(s.port, synthHead(5), "hello".toByteArray(), timeoutMs = 15_000).status) } }.apply { start(); Thread.sleep(150) } }
    Thread.sleep(300)
    // One synthesizes, one waits, the third is told the queue is full; /health still answers.
    assertEquals(200, call(s.port, "GET /health HTTP/1.1", timeoutMs = 3_000).status)
    release.countDown()
    threads.forEach { it.join(10_000) }
    assertEquals(listOf(200, 200, 503), statuses.sorted())
  }

  @Test fun repliesBeforeTheHeadParsesStillCarryCorsForLocalhost() {
    // /critique (run A F2): 431 and a malformed head are answered before parsing.
    val s = start()
    val big = call(s.port, "GET /health HTTP/1.1\r\nOrigin: http://localhost\r\nX-Pad: " + "a".repeat(20_000))
    assertEquals(431, big.status)
    assertEquals("http://localhost", big.headers["access-control-allow-origin"])
    val bad = call(s.port, "GET /health HTTP/1.1\r\nOrigin: http://localhost\r\nno colon")
    assertEquals(400, bad.status)
    assertEquals("http://localhost", bad.headers["access-control-allow-origin"])
    val other = call(s.port, "GET /health HTTP/1.1\r\nOrigin: http://evil.example\r\nno colon")
    assertNull(other.headers["access-control-allow-origin"])
  }

  @Test fun aClientThatHalfClosesAfterItsRequestStillGetsTheAudio() {
    // /critique (run B F3): end of stream after a complete request is legal HTTP/1.1.
    val s = start()
    Socket("127.0.0.1", s.port).use { c ->
      c.soTimeout = 5_000
      c.getOutputStream().write("${synthHead(5)}\r\n\r\nhello".toByteArray())
      c.shutdownOutput()
      assertEquals(200, parse(c.getInputStream()).status)
    }
  }

  companion object {
    /** A minimal 16-bit mono WAV with [samples] silent samples. */
    fun wav(samples: Int): ByteArray {
      val data = samples * 2
      val out = ByteArrayOutputStream()
      fun le(v: Int, n: Int) { for (i in 0 until n) out.write((v shr (8 * i)) and 0xff) }
      out.write("RIFF".toByteArray()); le(36 + data, 4); out.write("WAVEfmt ".toByteArray())
      le(16, 4); le(1, 2); le(1, 2); le(22050, 4); le(44100, 4); le(2, 2); le(16, 2)
      out.write("data".toByteArray()); le(data, 4); out.write(ByteArray(data))
      return out.toByteArray()
    }
  }
}
