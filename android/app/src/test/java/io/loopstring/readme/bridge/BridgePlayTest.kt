package io.loopstring.readme.bridge

import java.io.File
import java.io.InputStream
import java.net.Socket
import java.nio.file.Files
import java.util.Collections
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** REA-39, ADR 0014: /play, /status and /control against real sockets and a fake player. */
class BridgePlayTest {
  private val token = "0123456789abcdef0123456789abcdef"
  private val logs: MutableList<String> = Collections.synchronizedList(ArrayList())
  private val cache: File = Files.createTempDirectory("cache").toFile()
  private var server: BridgeServer? = null

  class FakePlayer : BridgePlayback {
    @Volatile var busy = false
    @Volatile var played: List<String>? = null
    @Volatile var rate = 0f
    @Volatile var session = 0
    @Volatile var state = BridgePlayback.State.NONE
    val actions: MutableList<BridgePlayback.Action> = Collections.synchronizedList(ArrayList())
    override fun play(chunks: List<String>, rate: Float): Int? {
      if (busy) return null
      played = chunks; this.rate = rate; session++; state = BridgePlayback.State.PLAYING
      return session
    }
    override fun status() = BridgePlayback.Status(session, state, 2)
    override fun control(session: Int, action: BridgePlayback.Action): BridgePlayback.Status? {
      if (session != this.session) return null
      actions += action
      state = when (action) {
        BridgePlayback.Action.PAUSE -> BridgePlayback.State.PAUSED
        BridgePlayback.Action.RESUME -> BridgePlayback.State.PLAYING
        BridgePlayback.Action.STOP -> BridgePlayback.State.STOPPED
      }
      return status()
    }
  }

  private val player = FakePlayer()

  @After fun tearDown() { server?.close() }

  private fun start(withPlayer: Boolean = true, busy: Boolean = false) =
    BridgeServer(0, { token }, { busy }, BridgeServerTest.FakeSynth(), BridgeFiles(cache), { logs.add(it) }, 2_000,
      player = if (withPlayer) player else null).also { server = it }

  private data class Response(val status: Int, val body: String)

  private fun call(port: Int, head: String, body: ByteArray = ByteArray(0)): Response {
    Socket("127.0.0.1", port).use { s ->
      s.soTimeout = 5_000
      s.getOutputStream().apply { write("$head\r\n\r\n".toByteArray(Charsets.ISO_8859_1)); write(body); flush() }
      return parse(s.getInputStream())
    }
  }

  private fun parse(input: InputStream): Response {
    val all = input.readBytes()
    val split = String(all, Charsets.ISO_8859_1).indexOf("\r\n\r\n")
    val first = String(all, 0, split, Charsets.ISO_8859_1).substringBefore("\r\n")
    return Response(first.split(' ')[1].toInt(), String(all, split + 4, all.size - split - 4, Charsets.UTF_8))
  }

  private fun play(port: Int, body: String, auth: String? = "Bearer $token", query: String = "?rate=1.5"): Response {
    val bytes = body.toByteArray(Charsets.UTF_8)
    val head = listOfNotNull("POST /play$query HTTP/1.1", "Content-Length: ${bytes.size}", auth?.let { "Authorization: $it" })
    return call(port, head.joinToString("\r\n"), bytes)
  }

  private fun get(port: Int, path: String, auth: Boolean = true) =
    call(port, listOfNotNull("GET $path HTTP/1.1", if (auth) "Authorization: Bearer $token" else null).joinToString("\r\n"))

  private fun post(port: Int, path: String) =
    call(port, "POST $path HTTP/1.1\r\nContent-Length: 0\r\nAuthorization: Bearer $token")

  @Test fun playSplitsTheBodyIntoChunksAndStartsASession() {
    val p = start().port
    val r = play(p, "First chunk.\u001eSecond, with é.\u001eThird.")
    assertEquals(200, r.status)
    assertEquals("""{"session":1,"chunks":3}""", r.body)
    assertEquals(listOf("First chunk.", "Second, with é.", "Third."), player.played)
    // AGENTS.md 9: the engine applies the rate, once; it is passed through as given.
    assertEquals(1.5f, player.rate)
  }

  @Test fun playNeedsTheTokenBeforeTheBody() {
    val p = start().port
    assertEquals(401, play(p, "Secret.", auth = null).status)
    assertEquals(401, play(p, "Secret.", auth = "Bearer wrong").status)
    assertNull(player.played)
  }

  @Test fun aBlankChunkIsRejectedSoIndexesStayThePluginsOwn() {
    val p = start().port
    assertEquals(400, play(p, "One.\u001e  \u001eThree.").status)
    assertEquals(400, play(p, "").status)
    assertNull(player.played)
  }

  @Test fun readMesOwnReadingAnswersBusy() {
    player.busy = true
    val p = start().port
    val r = play(p, "One.")
    assertEquals(503, r.status)
    assertEquals("""{"error":"busy","reason":"playback"}""", r.body)
  }

  @Test fun aBadRateIsRejected() {
    val p = start().port
    assertEquals(400, play(p, "One.", query = "?rate=0x1p1").status)
  }

  @Test fun statusAndControlFollowTheSession() {
    val p = start().port
    play(p, "One.\u001eTwo.\u001eThree.")
    assertEquals("""{"session":1,"state":"playing","index":2}""", get(p, "/status").body)
    assertEquals(401, get(p, "/status", auth = false).status)
    assertEquals("""{"session":1,"state":"paused","index":2}""", post(p, "/control?action=pause&session=1").body)
    assertEquals("""{"session":1,"state":"playing","index":2}""", post(p, "/control?action=resume&session=1").body)
    // A plugin holding an older session cannot touch the newer one.
    val stale = post(p, "/control?action=stop&session=7")
    assertEquals(409, stale.status)
    assertEquals("""{"error":"stale-session","session":1}""", stale.body)
    assertEquals(400, post(p, "/control?action=rewind&session=1").status)
    assertEquals(400, post(p, "/control?action=stop&session=-1").status)
    assertEquals(400, post(p, "/control?action=stop").status)
    assertEquals(listOf(BridgePlayback.Action.PAUSE, BridgePlayback.Action.RESUME), player.actions.toList())
  }

  @Test fun healthSaysPlayIsThereAndAPlayingSessionIsNotBusy() {
    val s = BridgeServer(0, { token }, { true }, BridgeServerTest.FakeSynth(), BridgeFiles(cache), { logs.add(it) }, 2_000, player = player)
      .also { server = it }
    player.state = BridgePlayback.State.PLAYING
    val h = get(s.port, "/health", auth = false).body
    assertTrue(h, h.contains("\"play\":true"))
    // The engine is speaking the plugin's own session: not busy to the plugin.
    assertTrue(h, h.contains("\"busy\":false"))
    player.state = BridgePlayback.State.STOPPED
    assertTrue(get(s.port, "/health", auth = false).body.contains("\"busy\":true"))
  }

  @Test fun withoutAPlayerTheRoutesDoNotExist() {
    val p = start(withPlayer = false).port
    assertEquals(404, play(p, "One.").status)
    assertEquals(404, get(p, "/status").status)
    assertTrue(get(p, "/health", auth = false).body.contains("\"play\":false"))
  }

  @Test fun noChunkTextReachesTheLog() {
    val p = start().port
    play(p, "Secret item sentence.\u001eAnother secret.")
    Thread.sleep(100)
    synchronized(logs) { assertFalse(logs.toString(), logs.any { it.contains("Secret") || it.contains("secret") }) }
  }
}
