package io.loopstring.readme.bridge

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock

/**
 * R-M12: the loopback bridge for the local-tts-reader plugin, contract v1 (srs.md). Plain JVM
 * so the hostile-input tests run against real sockets. Logs carry fixed method and route
 * names, status, durations and byte counts only: never the token, a header value, the query or
 * the text (AGENTS.md 1, 4). The constructor throws BindException when the port is taken.
 */
class BridgeServer(
  requestedPort: Int,
  private val token: () -> String,
  private val busy: () -> Boolean,
  private val synth: Synthesizer,
  private val files: BridgeFiles,
  private val log: (String) -> Unit,
  private val readTimeoutMs: Int = READ_TIMEOUT_MS,
  private val deadlineMs: Long = DEADLINE_MS,
  workers: Int = WORKERS,
) : Closeable {
  // AGENTS.md 8: explicit IPv4 loopback; getLoopbackAddress() returned ::1 on Android 17.
  private val server = ServerSocket(requestedPort, BACKLOG, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
  val port: Int = server.localPort
  val address: InetAddress = server.inetAddress
  private val pool = ThreadPoolExecutor(workers, workers, 0L, TimeUnit.MILLISECONDS, SynchronousQueue()) { r ->
    Thread(r, "bridge-worker").apply { isDaemon = true }
  }
  // Java socket writes have no timeout: a client that never reads would hold a worker forever.
  private val watchdog = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "bridge-watchdog").apply { isDaemon = true } }
  private val synthLock = ReentrantLock() // R-M12: synthesis is serialized
  private val epoch = AtomicInteger()
  @Volatile private var closed = false

  init {
    Thread(::acceptLoop, "bridge-accept").apply { isDaemon = true }.start()
  }

  /** ADR 0004: playback started; a synthesis in flight is stopped and answered 503. */
  fun preempt() {
    epoch.incrementAndGet()
    synth.cancel()
  }

  override fun close() {
    closed = true
    runCatching { server.close() }
    pool.shutdownNow()
    watchdog.shutdownNow()
    synth.cancel()
  }

  private fun acceptLoop() {
    while (!closed) {
      val s = try { server.accept() } catch (_: IOException) { break }
      try {
        pool.execute { serve(s) }
      } catch (_: RejectedExecutionException) {
        // Every worker is busy; each frees itself within the read timeout or the deadline.
        runCatching { s.close() }
      }
    }
  }

  private class Exchange {
    var method = "?"
    var route = "?"
    var origin: String? = null
    var status = 0
    var bodyBytes = 0
    var outBytes = 0L
  }

  private fun serve(s: Socket) {
    val t0 = System.nanoTime()
    val ex = Exchange()
    val cutoff = watchdog.schedule({ runCatching { s.close() } }, deadlineMs, TimeUnit.MILLISECONDS)
    try {
      s.use {
        it.soTimeout = readTimeoutMs
        val input = BufferedInputStream(it.getInputStream())
        val out = BufferedOutputStream(it.getOutputStream())
        handle(input, out, ex)
        out.flush()
        drain(it, input)
      }
    } catch (t: Throwable) {
      // AGENTS.md 8: Throwable, so no request can take the service down. Class name only.
      log("bridge connection ended: ${t.javaClass.simpleName}")
    } finally {
      cutoff.cancel(false)
    }
    if (ex.status != 0) {
      log("bridge ${ex.method} ${ex.route} ${ex.status} ms=${(System.nanoTime() - t0) / 1_000_000} in=${ex.bodyBytes} out=${ex.outBytes}")
    }
  }

  private fun handle(input: InputStream, out: OutputStream, ex: Exchange) {
    val head = when (val r = Http.readHead(input)) {
      HeadRead.Closed -> return
      HeadRead.TooLarge -> return error(out, ex, 431, "headers-too-large")
      HeadRead.Malformed -> return error(out, ex, 400, "bad-request")
      is HeadRead.Ok -> r.head
    }
    ex.method = if (head.method in METHODS) head.method else "other"
    ex.route = if (head.path in ROUTES) head.path else "other"
    ex.origin = head.headers["origin"]
    when {
      head.method == "OPTIONS" -> send(out, ex, 204, null, ByteArray(0))
      head.path == "/health" && head.method == "GET" -> send(out, ex, 200, JSON, health().toByteArray())
      head.path == "/synthesize" && head.method == "POST" -> synthesize(head, input, out, ex)
      head.path in ROUTES -> error(out, ex, 405, "method-not-allowed")
      else -> error(out, ex, 404, "not-found")
    }
  }

  private fun synthesize(head: Head, input: InputStream, out: OutputStream, ex: Exchange) {
    // AGENTS.md 8: the token is checked before a byte of the body is read.
    if (!authorized(head.headers["authorization"])) return error(out, ex, 401, "unauthorized")
    // ADR 0004: playback has priority; answered before the body is read.
    if (busy()) return busyReply(out, ex)
    if (!synth.ready) return error(out, ex, 503, "tts-not-ready")
    if (head.headers.containsKey("transfer-encoding")) return error(out, ex, 400, "content-length-required")
    val len = Http.contentLength(head.headers)
    if (len < 0) return error(out, ex, 400, "bad-content-length")
    if (len > MAX_BODY) return error(out, ex, 413, "body-too-large")
    val rate = Http.rate(head.query) ?: return error(out, ex, 400, "bad-rate")
    val body = readBody(input, len.toInt()) ?: return error(out, ex, 400, "short-body")
    ex.bodyBytes = body.size
    val text = Http.utf8(body) ?: return error(out, ex, 400, "bad-utf8")
    if (text.isBlank()) return error(out, ex, 400, "empty")
    // ADR 0008: one synthesis takes at most the engine's input limit.
    if (text.length > synth.maxChars) return error(out, ex, 413, "too-long", "\"maxChars\":${synth.maxChars}")

    val f = files.create()
    try {
      var ms = 0L
      val result: SynthResult
      val preempted: Boolean
      synthLock.lock()
      try {
        if (busy()) return busyReply(out, ex)
        val e = epoch.get()
        val t0 = System.nanoTime()
        // AGENTS.md 9: the rate is applied here, by the engine, once.
        result = synth.synthesize(text, rate, f)
        ms = (System.nanoTime() - t0) / 1_000_000
        preempted = epoch.get() != e
      } finally {
        synthLock.unlock()
      }
      // Sent outside the lock: a slow reader must not hold up the next synthesis.
      when {
        result == SynthResult.CANCELLED || preempted -> busyReply(out, ex)
        result == SynthResult.TIMEOUT -> error(out, ex, 504, "timeout")
        result != SynthResult.OK || f.length() == 0L -> error(out, ex, 500, "synthesis-failed")
        else -> sendFile(out, ex, f, "X-Synth-Ms: $ms\r\nX-Rate: $rate\r\n")
      }
    } finally {
      f.delete()
    }
  }

  private fun authorized(value: String?): Boolean {
    val t = token()
    if (t.length < 32 || value == null) return false
    return MessageDigest.isEqual("Bearer $t".toByteArray(), value.toByteArray())
  }

  private fun readBody(input: InputStream, len: Int): ByteArray? {
    val body = ByteArray(len)
    var got = 0
    while (got < len) {
      val k = input.read(body, got, len - got)
      if (k < 0) return null
      got += k
    }
    return body
  }

  /**
   * R-M12, ADR 0004: closing a socket with unread data in its receive buffer sends a reset, and
   * a WebView fetch would see a network error instead of the status. Shut down output and read
   * what remains, at most 64 KiB within the read timeout.
   */
  private fun drain(s: Socket, input: InputStream) {
    runCatching { s.shutdownOutput() }
    val deadline = System.nanoTime() + readTimeoutMs * 1_000_000L
    val buf = ByteArray(8192)
    var left = MAX_BODY
    try {
      while (left > 0 && System.nanoTime() < deadline) {
        val n = input.read(buf, 0, minOf(buf.size, left))
        if (n < 0) break
        left -= n
      }
    } catch (_: IOException) {
    }
  }

  private fun health(): String =
    "{\"ok\":true,\"version\":1,\"ttsReady\":${synth.ready},\"engine\":${json(synth.engine)}," +
      "\"voice\":${json(synth.voice)},\"port\":$port,\"busy\":${busy()},\"maxChars\":${synth.maxChars}}"

  private fun busyReply(out: OutputStream, ex: Exchange) =
    send(out, ex, 503, JSON, """{"error":"busy","reason":"playback"}""".toByteArray())

  private fun error(out: OutputStream, ex: Exchange, code: Int, error: String, extra: String = "") =
    send(out, ex, code, JSON, ("{\"error\":${json(error)}" + (if (extra.isEmpty()) "" else ",$extra") + "}").toByteArray())

  private fun send(out: OutputStream, ex: Exchange, code: Int, type: String?, body: ByteArray, extra: String = "") {
    writeHead(out, ex, code, type, body.size.toLong(), extra)
    out.write(body)
    ex.outBytes = body.size.toLong()
  }

  private fun sendFile(out: OutputStream, ex: Exchange, f: File, extra: String) {
    val size = f.length()
    writeHead(out, ex, 200, "audio/wav", size, extra)
    f.inputStream().use { it.copyTo(out) }
    ex.outBytes = size
  }

  private fun writeHead(out: OutputStream, ex: Exchange, code: Int, type: String?, length: Long, extra: String) {
    ex.status = code
    val sb = StringBuilder("HTTP/1.1 $code ${REASONS[code] ?: "Status"}\r\n")
    if (type != null) sb.append("Content-Type: $type\r\n")
    sb.append("Content-Length: $length\r\nConnection: close\r\nCache-Control: no-store\r\n")
    // R-M12: every response to the WebView's origin carries these, errors included; no other origin gets any.
    if (ex.origin == ALLOWED_ORIGIN) {
      sb.append("Access-Control-Allow-Origin: $ALLOWED_ORIGIN\r\n")
        .append("Access-Control-Allow-Methods: GET, POST\r\n")
        .append("Access-Control-Allow-Headers: Authorization, Content-Type\r\n")
        .append("Access-Control-Expose-Headers: X-Synth-Ms, X-Rate\r\n")
        .append("Vary: Origin\r\n")
    }
    sb.append(extra).append("\r\n")
    out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
  }

  companion object {
    const val MAX_BODY = 64 * 1024
    const val READ_TIMEOUT_MS = 10_000
    const val DEADLINE_MS = 180_000L
    const val WORKERS = 8
    private const val BACKLOG = 16
    private const val JSON = "application/json"
    private const val ALLOWED_ORIGIN = "http://localhost"
    private val METHODS = setOf("GET", "POST", "OPTIONS")
    private val ROUTES = setOf("/health", "/synthesize")
    private val REASONS = mapOf(
      200 to "OK", 204 to "No Content", 400 to "Bad Request", 401 to "Unauthorized", 404 to "Not Found",
      405 to "Method Not Allowed", 413 to "Payload Too Large", 431 to "Request Header Fields Too Large",
      500 to "Internal Server Error", 503 to "Service Unavailable", 504 to "Gateway Timeout",
    )

    private fun json(s: String?): String {
      if (s == null) return "null"
      val sb = StringBuilder("\"")
      for (c in s) {
        when {
          c == '"' -> sb.append("\\\"")
          c == '\\' -> sb.append("\\\\")
          c < ' ' -> sb.append(String.format(Locale.ROOT, "\\u%04x", c.code))
          else -> sb.append(c)
        }
      }
      return sb.append('"').toString()
    }
  }
}
