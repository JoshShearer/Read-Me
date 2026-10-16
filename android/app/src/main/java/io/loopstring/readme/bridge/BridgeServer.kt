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
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.Semaphore
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
  private val headDeadlineMs: Long = HEAD_DEADLINE_MS,
  private val maxQueued: Int = MAX_QUEUED,
  private val closeGraceMs: Long = CLOSE_GRACE_MS,
  /** REA-39: Read Me reads the plugin's chunks itself; null serves contract v1's routes only. */
  private val player: BridgePlayback? = null,
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
  // One synthesizing plus [maxQueued] waiting: a waiter holds a worker, and the rest must stay
  // free for /health and new requests (R-M12: no client may block them).
  private val synthSlots = Semaphore(1 + maxQueued)
  private val epoch = AtomicInteger()
  @Volatile private var closed = false
  private val closing = AtomicBoolean()
  // Every connection a worker is serving, so close() can free a worker blocked in a write.
  private val live: MutableSet<Socket> = ConcurrentHashMap.newKeySet()
  @Volatile private var swept = false

  init {
    Thread(::acceptLoop, "bridge-accept").apply { isDaemon = true }.start()
  }

  /** ADR 0004: playback started; a synthesis in flight is stopped and answered 503. */
  fun preempt() {
    epoch.incrementAndGet()
    synth.cancel()
  }

  /**
   * Stops accepting, cancels a synthesis in flight (answered 503 busy/stopping) and, after
   * [closeGraceMs] for replies to get out, closes every connection still open. REA-23: the
   * watchdog keeps running until then, so a client that never reads is cut off by the sweep
   * instead of holding its worker until it leaves. No interrupt before the sweep: it made
   * the latches a worker waits on throw, and a cancelled request got no reply at all.
   */
  override fun close() {
    if (!closing.compareAndSet(false, true)) return
    closed = true
    runCatching { server.close() }
    synth.cancel()
    pool.shutdown()
    try {
      watchdog.schedule(::sweep, closeGraceMs, TimeUnit.MILLISECONDS)
    } catch (_: RejectedExecutionException) {
      sweep()
    }
  }

  private fun sweep() {
    // Set before the walk; serve() checks it after adding, so no connection slips between.
    swept = true
    for (s in live) runCatching { s.close() }
    // Interrupts a worker still waiting (for example on a rebinding engine); its socket is closed.
    pool.shutdownNow()
    // Pending cutoffs are moot now, and would keep the watchdog thread alive until they fire.
    watchdog.shutdownNow()
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
    var socket: Socket? = null
  }

  private fun serve(s: Socket) {
    val t0 = System.nanoTime()
    val ex = Exchange().apply { socket = s }
    var cutoff: ScheduledFuture<*>? = null
    try {
      // Inside the try: after the sweep, schedule throws RejectedExecutionException (AGENTS.md 8).
      live.add(s)
      if (swept) throw IOException("bridge closed")
      cutoff = watchdog.schedule({ runCatching { s.close() } }, deadlineMs, TimeUnit.MILLISECONDS)
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
      cutoff?.cancel(false)
      live.remove(s)
      runCatching { s.close() }
    }
    if (ex.status != 0) {
      log("bridge ${ex.method} ${ex.route} ${ex.status} ms=${(System.nanoTime() - t0) / 1_000_000} in=${ex.bodyBytes} out=${ex.outBytes}")
    }
  }

  private fun handle(input: InputStream, out: OutputStream, ex: Exchange) {
    // The per-read timeout restarts with every byte; a dripped head gets a deadline of its own.
    val s = ex.socket
    val headCut = s?.let { watchdog.schedule({ runCatching { it.close() } }, headDeadlineMs, TimeUnit.MILLISECONDS) }
    val read = Http.readHead(input) { raw ->
      // R-M12: errors carry CORS too, even when sent before the head parses.
      if (ORIGIN_LINE.containsMatchIn(String(raw, Charsets.ISO_8859_1))) ex.origin = ALLOWED_ORIGIN
    }
    headCut?.cancel(false)
    val head = when (val r = read) {
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
      head.path == "/play" && head.method == "POST" && player != null -> play(player, head, input, out, ex)
      head.path == "/status" && head.method == "GET" && player != null -> status(player, head, out, ex)
      head.path == "/control" && head.method == "POST" && player != null -> control(player, head, out, ex)
      head.path in V1_ROUTES || (player != null && head.path in ROUTES) -> error(out, ex, 405, "method-not-allowed")
      else -> error(out, ex, 404, "not-found")
    }
  }

  private fun synthesize(head: Head, input: InputStream, out: OutputStream, ex: Exchange) {
    // AGENTS.md 8: the token is checked before a byte of the body is read.
    if (!authorized(head.headers["authorization"])) return error(out, ex, 401, "unauthorized")
    // REA-23: shutting down; one reason for every request in the close grace.
    if (closed) return stoppingReply(out, ex)
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

    if (!synthSlots.tryAcquire()) return send(out, ex, 503, JSON, QUEUE_FULL)
    val f = try {
      files.create()
    } catch (t: Throwable) {
      synthSlots.release()
      throw t
    }
    try {
      var ms = 0L
      val result: SynthResult
      val preempted: Boolean
      val waited = !synthLock.tryLock()
      if (waited) synthLock.lock()
      try {
        // Read before the busy check: a preempt after this point changes the epoch (ADR 0004).
        val e = epoch.get()
        if (closed) return stoppingReply(out, ex)
        if (busy()) return busyReply(out, ex)
        // Waiting for the lock can outlast the client; nobody would read this audio.
        if (waited && ex.socket?.let { clientGone(it, input) } == true) return
        val t0 = System.nanoTime()
        // AGENTS.md 9: the rate is applied here, by the engine, once.
        result = synth.synthesize(text, rate, f) { epoch.get() == e && !closed }
        ms = (System.nanoTime() - t0) / 1_000_000
        preempted = epoch.get() != e
      } finally {
        synthLock.unlock()
        synthSlots.release()
      }
      // Sent outside the lock: a slow reader must not hold up the next synthesis.
      when {
        // A synthesis that finished before close() still gets its audio in the grace.
        closed && result != SynthResult.OK -> stoppingReply(out, ex)
        result == SynthResult.CANCELLED || preempted -> busyReply(out, ex)
        // The retry of a failed first binding failed too (REA-23): the plugin's not-ready message.
        result == SynthResult.NOT_READY -> error(out, ex, 503, "tts-not-ready")
        result == SynthResult.TIMEOUT -> error(out, ex, 504, "timeout")
        result != SynthResult.OK || f.length() == 0L -> error(out, ex, 500, "synthesis-failed")
        else -> sendFile(out, ex, f, "X-Synth-Ms: $ms\r\nX-Rate: $rate\r\n")
      }
    } finally {
      f.delete()
    }
  }

  /**
   * REA-39, ADR 0014: the chunks, UTF-8, separated by U+001E (record separator), read aloud by
   * Read Me's own playback. Token before body, as for /synthesize. No chunk may be blank, so the
   * plugin's chunk n is always index n.
   */
  private fun play(player: BridgePlayback, head: Head, input: InputStream, out: OutputStream, ex: Exchange) {
    if (!authorized(head.headers["authorization"])) return error(out, ex, 401, "unauthorized")
    if (closed) return stoppingReply(out, ex)
    if (head.headers.containsKey("transfer-encoding")) return error(out, ex, 400, "content-length-required")
    val len = Http.contentLength(head.headers)
    if (len < 0) return error(out, ex, 400, "bad-content-length")
    if (len > MAX_PLAY_BODY) return error(out, ex, 413, "body-too-large")
    val rate = Http.rate(head.query) ?: return error(out, ex, 400, "bad-rate")
    val body = readBody(input, len.toInt()) ?: return error(out, ex, 400, "short-body")
    ex.bodyBytes = body.size
    val text = Http.utf8(body) ?: return error(out, ex, 400, "bad-utf8")
    val chunks = text.split(SEPARATOR)
    if (chunks.size > MAX_CHUNKS) return error(out, ex, 413, "too-many-chunks", "\"maxChunks\":$MAX_CHUNKS")
    if (chunks.any { it.isBlank() }) return error(out, ex, 400, "empty")
    val session = player.play(chunks, rate) ?: return busyReply(out, ex)
    send(out, ex, 200, JSON, "{\"session\":$session,\"chunks\":${chunks.size}}".toByteArray())
  }

  private fun status(player: BridgePlayback, head: Head, out: OutputStream, ex: Exchange) {
    if (!authorized(head.headers["authorization"])) return error(out, ex, 401, "unauthorized")
    send(out, ex, 200, JSON, statusJson(player.status()).toByteArray())
  }

  private fun control(player: BridgePlayback, head: Head, out: OutputStream, ex: Exchange) {
    if (!authorized(head.headers["authorization"])) return error(out, ex, 401, "unauthorized")
    val params = Http.params(head.query)
    val action = when (params["action"]) {
      "pause" -> BridgePlayback.Action.PAUSE
      "resume" -> BridgePlayback.Action.RESUME
      "stop" -> BridgePlayback.Action.STOP
      else -> return error(out, ex, 400, "bad-action")
    }
    val session = params["session"]?.takeIf { SESSION.matches(it) }?.toIntOrNull()
      ?: return error(out, ex, 400, "bad-session")
    val s = player.control(session, action) ?: return error(out, ex, 409, "stale-session", "\"session\":${player.status().session}")
    send(out, ex, 200, JSON, statusJson(s).toByteArray())
  }

  private fun statusJson(s: BridgePlayback.Status) =
    "{\"session\":${s.session},\"state\":\"${s.state.wire}\",\"index\":${s.index}}"

  private fun authorized(value: String?): Boolean {
    val t = token()
    if (t.length < 32 || value == null) return false
    return MessageDigest.isEqual("Bearer $t".toByteArray(), value.toByteArray())
  }

  /** True when the client has closed: a 1 ms read sees end of stream. The body was already read. */
  private fun clientGone(s: Socket, input: InputStream): Boolean {
    val t = s.soTimeout
    return try {
      s.soTimeout = 1
      input.read() == -1
    } catch (_: SocketTimeoutException) {
      false
    } catch (_: IOException) {
      true
    } finally {
      runCatching { s.soTimeout = t }
    }
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

  // REA-39: `play` says /play, /status and /control exist; `busy` is Read Me's own playback, so a
  // session of the plugin's own does not read as busy to it.
  private fun health(): String {
    val reading = player?.status()?.state.let { it == BridgePlayback.State.PLAYING || it == BridgePlayback.State.STARTING }
    return "{\"ok\":true,\"version\":1,\"ttsReady\":${synth.ready},\"engine\":${json(synth.engine)}," +
      "\"voice\":${json(synth.voice)},\"port\":$port,\"busy\":${busy() && !reading},\"maxChars\":${synth.maxChars}," +
      "\"play\":${player != null}}"
  }

  private fun busyReply(out: OutputStream, ex: Exchange) =
    send(out, ex, 503, JSON, """{"error":"busy","reason":"playback"}""".toByteArray())

  /** ADR 0004's status and error code, so the plugin needs no change; only the reason differs. */
  private fun stoppingReply(out: OutputStream, ex: Exchange) = send(out, ex, 503, JSON, STOPPING)

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
    const val HEAD_DEADLINE_MS = 5_000L
    const val MAX_QUEUED = 2
    /** REA-39: a whole note's remaining chunks. 1 MiB of text is several hours of speech. */
    const val MAX_PLAY_BODY = 1024 * 1024
    const val MAX_CHUNKS = 10_000
    private const val SEPARATOR = '\u001e'
    private val SESSION = Regex("[0-9]{1,9}")
    /** How long close() leaves open connections to finish their replies. */
    const val CLOSE_GRACE_MS = 1_000L
    private const val BACKLOG = 16
    private const val JSON = "application/json"
    private const val ALLOWED_ORIGIN = "http://localhost"
    private val METHODS = setOf("GET", "POST", "OPTIONS")
    private val V1_ROUTES = setOf("/health", "/synthesize")
    private val ROUTES = V1_ROUTES + setOf("/play", "/status", "/control")
    private val ORIGIN_LINE = Regex("(?im)^origin:[ \\t]*http://localhost[ \\t]*\r?$")
    private val STOPPING = """{"error":"busy","reason":"stopping"}""".toByteArray()
    private val QUEUE_FULL = """{"error":"busy","reason":"queue"}""".toByteArray()
    private val REASONS = mapOf(
      200 to "OK", 204 to "No Content", 400 to "Bad Request", 401 to "Unauthorized", 404 to "Not Found",
      405 to "Method Not Allowed", 409 to "Conflict", 413 to "Payload Too Large", 431 to "Request Header Fields Too Large",
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
