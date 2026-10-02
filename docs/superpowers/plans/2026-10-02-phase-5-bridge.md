# Phase 5: Obsidian Bridge Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When the user turns it on in Settings, Read Me serves the loopback HTTP bridge (R-M12, "Bridge contract (v1)") so the `local-tts-reader` Obsidian plugin can synthesize speech with Android's on-device TTS, hosted by PlaybackService's foreground service.

**Architecture:** A plain-JVM `BridgeServer` (sockets, request parsing, auth, caps, CORS, drain) talks to a `Synthesizer` interface; `TtsSynth` implements it with the bridge's own `TextToSpeech`. `PlaybackService` owns the bridge's lifetime: it starts and stops it from Settings, keeps the service in the foreground while it is on, and preempts an in-flight synthesis when playback starts (ADR 0004). JS only reads and changes the bridge settings through the `ReadMeSpeech` module and shows them in Settings.

**Tech Stack:** Kotlin (`java.net.ServerSocket`, `android.speech.tts.TextToSpeech`), React Native 0.87.1 (TypeScript), JUnit 4 + Robolectric 4.17, Jest + react-test-renderer, bash + curl device scripts, CDP into Obsidian's WebView.

**Spec:** `srs.md` R-M12, "Bridge contract (v1)", "Foreground service", R-M01 (Settings: bridge on/off and pairing token), R-M09.2; `docs/adr/0004-tts-contention-policy.md`; `docs/adr/0005-foreground-service-types.md`; Linear REA-20 (acceptance criteria). Read all of them before Task 1.

**Worktree:** `/home/joshshearer/Documents/Dev/Read-Me-rea-20`, branch `feature/rea-20-obsidian-bridge`. The primary checkout belongs to another lane (`feature/brand-assets`); never touch it, except to take `.claude/device.lock/` there for device runs (`scripts/lib/device.sh` does this).

## Global Constraints

- Non-negotiable 1: no item text, request body, title, URL path or query in any log or logged exception message. Bridge logs carry method (fixed set), route (fixed set), status, ms, byte counts, port, and exception class names only.
- Non-negotiable 4: the bridge token is never logged and never placed in a URL. Device scripts never put it on an `adb shell` command line (adbd logs argv, SPIKE-01).
- Non-negotiable 5: no voice whose `isNetworkConnectionRequired()` is true is used for bridge synthesis, including as a fallback (`VoicePicker`).
- Non-negotiable 6: `Fetcher` stays the only outbound connection; `ServerSocket(` appears only in `BridgeServer.kt` (`__tests__/networkGuard.test.ts` already enforces this, unchanged).
- Non-negotiable 8: bind `127.0.0.1` explicitly (`InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))`, never `getLoopbackAddress()`); token checked before the body is read; header cap 16 KiB (431), body cap 64 KiB (413), Content-Length validated (400); 10 s read timeout per socket; every connection caught at `Throwable`; text only in the POST body.
- Non-negotiable 9: rate applied once, by `setSpeechRate` on the bridge's own instance; `rate` defaults to 1.0; nothing resamples.
- Non-negotiable 11: PlaybackService still owns the playback queue; the bridge reads only the playing flag (`PlaybackHub.speaking`).
- Non-negotiable 13: the bridge has its own `TextToSpeech` instance (`TtsSynth`), never playback's `TtsSpeaker`.
- Non-negotiable 14: no new dependency of any kind.
- R-M12 CORS: `OPTIONS` answered; every response from an `Origin: http://localhost` request, errors included, carries `Access-Control-Allow-Origin: http://localhost` and `Access-Control-Expose-Headers: X-Synth-Ms, X-Rate`; any other Origin gets no CORS headers.
- R-M12: default port 8787, configurable (1024 to 65535); token is 128 random bits (32 lower-case hex chars), generated once, persisted, regenerable, shown in Settings with Copy.
- R-M12: synthesized files live in the app cache, are deleted on every exit path, and stale ones are swept at service start.
- ADR 0004: while playback is speaking, `POST /synthesize` answers 503 `{"error":"busy","reason":"playback"}` after the token check and before the body is read; a synthesis in flight when playback starts is stopped (bridge instance only) and answered 503; `/health` reports `busy`.
- ADR 0005: one `mediaPlayback` foreground service hosts playback and the bridge; the notification says when the bridge is on.
- Writing: no em-dashes anywhere (code, comments, docs, commits). Commits end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Rule 17: no latency or RTF number in a comment, doc or commit unless measured in this run or cited.

## Review Focus

1. **A client that never sends its body, or never reads a large WAV.** Expected: the worker is freed (10 s read timeout for reads; a per-connection deadline for writes, since Java socket writes have no timeout) and `/health` keeps answering. Pinned by Task 3 tests `slowBodyTimesOutAndFreesTheWorker` and `aClientThatNeverReadsIsCutOffAtTheDeadline`.
2. **Every worker held by silent clients.** Expected: further connections are refused while all workers are busy, and the bridge serves again once the read timeout frees them; it never wedges. Pinned by Task 3 test `allWorkersBusyRefusesThenRecovers`.
3. **Port 8787 already taken by another app when the user turns the bridge on.** Expected: no crash, no orphan foreground notification; Settings says the port is in use. Pinned by Task 5 test `aTakenPortReportsFailedAndStopsAnIdleService` and Task 6 test `bridgeStatus explains a taken port`.
4. **The item being read ends or is deleted while the bridge is on.** Expected: the service stays in the foreground with a bridge-only notification and the bridge keeps answering. Pinned by Task 5 test `theBridgeKeepsTheServiceAfterPlaybackEnds`.
5. **The token is regenerated while the plugin holds the old one, and nothing secret reaches a log.** Expected: the old token gets 401 on the very next request; no log line ever contains the token or the request text. Pinned by Task 3 tests `aRegeneratedTokenTakesEffectAtOnce` and `noLogLineCarriesTheTokenOrTheText`.

## Rulings made while planning (owner: please check these)

- **Text length limit (deviation, ADR 0008 in Task 3).** `TextToSpeech.synthesizeToFile` refuses text longer than `getMaxSpeechInputLength()` (documented 4000; `TtsSpeaker.maxChars()` uses 3900). The contract says only "body at most 64 KiB". The bridge answers 413 `{"error":"too-long","maxChars":N}` for text longer than `maxChars` and reports `maxChars` in `/health` (additive). The plugin's chunks are capped at 220 characters (`note-reader-local/AGENTS.md`, chunk-cap note), so it never meets the limit. The alternative, splitting and joining WAVs, was rejected: a 64 KiB body is about an hour of audio (a ~200 MB WAV) and minutes of synthesis, which no caller wants as one response.
- **Additive status codes.** 405 for a known route with the wrong method, 404 for an unknown route, 504 when a synthesis exceeds its 120 s limit, 503 `{"error":"tts-not-ready"}` before the engine is ready. Error bodies are JSON `{"error":"<code>"}`. Recorded in the contract by Task 3.
- **No CORS on 431 and 400-malformed.** Those are answered before the head is parsed, so the Origin is unknown. An honest WebView request never has a 16 KiB head.
- **The bridge restarts when Read Me is opened, not at boot.** No `RECEIVE_BOOT_COMPLETED` permission (minimal permissions). After a reboot or process death, the bridge comes back the next time Read Me is opened (`MainActivity.onResume`). Recorded in Known state.
- **Wake lock per synthesis.** The bridge holds a partial wake lock only while one synthesis runs (at most 125 s), so a screen-off request from Obsidian completes.

---

## File Structure

New, `android/app/src/main/java/io/loopstring/readme/bridge/`:
- `Http.kt` - reads and parses a request head; Content-Length, rate, UTF-8 helpers. Pure JVM.
- `Synthesizer.kt` - the `Synthesizer` interface, `SynthResult`, `SynthWait` (the one-synthesis latch). Pure JVM.
- `BridgeFiles.kt` - cache WAV naming and the start-up sweep. Pure JVM.
- `BridgeServer.kt` - sockets, worker pool, routing, auth, caps, CORS, drain, health JSON. Pure JVM (no `android.*` import).
- `TtsSynth.kt` - the bridge's own `TextToSpeech` behind `Synthesizer`.
- `BridgeView.kt` - `BridgeView.state` and `TokenClipboard`.

Modified:
- `store/Settings.kt` - `bridgeEnabled`, `bridgePort`, `bridgeToken()`, `regenerateBridgeToken()`, `BridgeToken`.
- `playback/PlaybackHub.kt` - `BridgeStatus`, `bridge`, bridge listeners.
- `playback/Policies.kt` - `ACTION_BRIDGE`, `ACTION_BRIDGE_OFF`, `Route.BRIDGE`, `ServiceLife`, `ServiceText`.
- `playback/TtsSpeaker.kt` - `chooseVoice` moves to the companion so `TtsSynth` shares it.
- `playback/PlaybackService.kt` - hosts the bridge.
- `MainActivity.kt` - restarts an enabled bridge on resume.
- `ReadMeSpeechModule.kt`, `src/native/NativeReadMeSpeech.ts` - bridge methods and the `ReadMeBridge` event.
- `src/library/bridge.ts` (new), `src/ui/model.ts`, `src/ui/SettingsScreen.tsx`.
- `scripts/device-bridge-e2e.sh` (new), `scripts/obsidian-cdp-bridge.mjs` (new), `package.json`.
- Docs: `docs/adr/0008-bridge-text-limit.md` (new), `srs.md`, `AGENTS.md`, `CONTEXT.md`, `docs/superpowers/plans/2026-10-01-roadmap.md`.

Tests: `android/app/src/test/java/io/loopstring/readme/bridge/{HttpTest,SynthWaitTest,BridgeFilesTest,BridgeServerTest,BridgeViewTest}.kt`, `store/SettingsTest.kt`, `playback/{PoliciesTest,PlaybackHubTest,PlaybackServiceTest}.kt`, `__tests__/{uiModel,screens,bridge}.test.ts(x)`.

Merge note: `feature/brand-assets` (the other lane) changes one line in `PlaybackService.notification()` (`setSmallIcon(R.drawable.ic_stat_readme)`). Task 5 rewrites `notification()`; keep `setSmallIcon(...)` as its own line so whichever lands second resolves a one-line conflict.

---

### Task 1: Bridge settings and the pairing token

**Files:**
- Modify: `android/app/src/main/java/io/loopstring/readme/store/Settings.kt`
- Test: `android/app/src/test/java/io/loopstring/readme/store/SettingsTest.kt`

**Interfaces:**
- Produces: `object BridgeToken { fun generate(rng: java.security.SecureRandom = SecureRandom()): String }` (32 lower-case hex chars); `Settings.bridgeEnabled: Boolean` (default false); `Settings.bridgePort: Int` (default 8787; setter throws `IllegalArgumentException` outside 1024..65535); `Settings.bridgeToken(): String` (creates and persists on first call); `Settings.regenerateBridgeToken(): String`; `Settings.BRIDGE_PORT_DEFAULT = 8787`, `BRIDGE_PORT_MIN = 1024`, `BRIDGE_PORT_MAX = 65535` (companion, public).

- [ ] **Step 1: Write the failing tests** (append to `SettingsTest.kt`, which already runs under Robolectric with `ctx` from `ApplicationProvider`; read the file first and follow its setup)

```kotlin
  @Test fun theBridgeIsOffOnPort8787ByDefault() {
    val s = Settings(ctx)
    assertFalse(s.bridgeEnabled)
    assertEquals(8787, s.bridgePort)
  }

  @Test fun bridgeSettingsPersist() {
    Settings(ctx).bridgeEnabled = true
    Settings(ctx).bridgePort = 8790
    assertTrue(Settings(ctx).bridgeEnabled)
    assertEquals(8790, Settings(ctx).bridgePort)
  }

  @Test fun aPortOutside1024To65535IsRefused() {
    val s = Settings(ctx)
    for (p in listOf(0, 80, 1023, 65536, -1)) {
      assertThrows(IllegalArgumentException::class.java) { s.bridgePort = p }
    }
    assertEquals(8787, s.bridgePort)
  }

  @Test fun theTokenIs128BitsGeneratedOnceAndRegenerable() {
    val first = Settings(ctx).bridgeToken()
    assertTrue(first.matches(Regex("[0-9a-f]{32}")))
    assertEquals(first, Settings(ctx).bridgeToken())
    val second = Settings(ctx).regenerateBridgeToken()
    assertTrue(second.matches(Regex("[0-9a-f]{32}")))
    assertNotEquals(first, second)
    assertEquals(second, Settings(ctx).bridgeToken())
  }

  @Test fun generatedTokensDiffer() {
    assertEquals(50, (1..50).map { BridgeToken.generate() }.toSet().size)
  }
```

Imports to add if missing: `org.junit.Assert.assertNotEquals`, `org.junit.Assert.assertThrows`, `org.junit.Assert.assertTrue`, `org.junit.Assert.assertFalse`.

- [ ] **Step 2: Run them to see them fail**

Run: `cd android && ./gradlew testDebugUnitTest --tests '*SettingsTest*' -q`
Expected: FAIL, compilation errors on `bridgeEnabled`, `bridgePort`, `bridgeToken`, `BridgeToken`.

- [ ] **Step 3: Implement** in `Settings.kt`

Add above `class Settings`:

```kotlin
/** R-M12: the pairing token, 128 bits from SecureRandom as 32 hex chars. Never logged (AGENTS.md 4). */
object BridgeToken {
  fun generate(rng: java.security.SecureRandom = java.security.SecureRandom()): String {
    val b = ByteArray(16)
    rng.nextBytes(b)
    return b.joinToString("") { "%02x".format(it.toInt() and 0xff) }
  }
}
```

Inside `class Settings`, after `voice`:

```kotlin
  /** R-M12: the bridge runs only while this is true. */
  var bridgeEnabled: Boolean
    get() = prefs.getBoolean(KEY_BRIDGE, false)
    set(value) {
      prefs.edit().putBoolean(KEY_BRIDGE, value).apply()
    }

  /** R-M12: 8787 unless the user chose another port in 1024..65535. */
  var bridgePort: Int
    get() = prefs.getInt(KEY_BRIDGE_PORT, BRIDGE_PORT_DEFAULT)
    set(value) {
      require(value in BRIDGE_PORT_MIN..BRIDGE_PORT_MAX) { "port out of range" }
      prefs.edit().putInt(KEY_BRIDGE_PORT, value).apply()
    }

  /** Generated on first use and kept until regenerated (R-M12). commit(): the bridge may read it at once. */
  fun bridgeToken(): String = synchronized(LOCK) {
    prefs.getString(KEY_BRIDGE_TOKEN, null) ?: regenerateBridgeToken()
  }

  fun regenerateBridgeToken(): String = synchronized(LOCK) {
    BridgeToken.generate().also { prefs.edit().putString(KEY_BRIDGE_TOKEN, it).commit() }
  }
```

Replace the companion:

```kotlin
  companion object {
    const val BRIDGE_PORT_DEFAULT = 8787
    const val BRIDGE_PORT_MIN = 1024
    const val BRIDGE_PORT_MAX = 65535
    private const val KEY_RATE = "rate"
    private const val KEY_VOICE = "voice"
    private const val KEY_BRIDGE = "bridge"
    private const val KEY_BRIDGE_PORT = "bridgePort"
    private const val KEY_BRIDGE_TOKEN = "bridgeToken"
    private val LOCK = Any()
  }
```

- [ ] **Step 4: Run them to see them pass**

Run: `cd android && ./gradlew testDebugUnitTest --tests '*SettingsTest*' -q`
Expected: PASS (all SettingsTest tests, old and new).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/io/loopstring/readme/store/Settings.kt android/app/src/test/java/io/loopstring/readme/store/SettingsTest.kt
git commit -m "feat(bridge): bridge on/off, port and a 128-bit pairing token in Settings

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Request head parsing

**Files:**
- Create: `android/app/src/main/java/io/loopstring/readme/bridge/Http.kt`
- Test: `android/app/src/test/java/io/loopstring/readme/bridge/HttpTest.kt`

**Interfaces:**
- Produces (package `io.loopstring.readme.bridge`):
  - `data class Head(val method: String, val path: String, val query: String, val headers: Map<String, String>)` (header names lower case).
  - `sealed interface HeadRead { data class Ok(val head: Head); object TooLarge; object Malformed; object Closed }`.
  - `object Http { const val MAX_HEAD = 16384; const val ABSENT = -2L; const val INVALID = -1L; fun readHead(input: InputStream, max: Int = MAX_HEAD): HeadRead; fun parse(text: String): HeadRead; fun contentLength(headers: Map<String, String>): Long; fun rate(query: String): Float?; fun utf8(bytes: ByteArray): String? }`.

- [ ] **Step 1: Write the failing tests** (`HttpTest.kt`, plain JUnit, no runner)

```kotlin
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
```

- [ ] **Step 2: Run them to see them fail**

Run: `cd android && ./gradlew testDebugUnitTest --tests '*HttpTest*' -q`
Expected: FAIL, unresolved `Http`, `HeadRead`.

- [ ] **Step 3: Implement** `Http.kt`

```kotlin
package io.loopstring.readme.bridge

import io.loopstring.readme.store.Rate
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Locale

/** One request head. Header names are lower case; a repeated header's values are joined with ", ". */
data class Head(val method: String, val path: String, val query: String, val headers: Map<String, String>)

sealed interface HeadRead {
  data class Ok(val head: Head) : HeadRead
  object TooLarge : HeadRead
  object Malformed : HeadRead
  object Closed : HeadRead
}

/** R-M12 request parsing, with no I/O beyond the stream it is given. Never logs. */
object Http {
  const val MAX_HEAD = 16 * 1024
  const val ABSENT = -2L
  const val INVALID = -1L
  private val DIGITS = Regex("[0-9]{1,10}")

  /** Reads up to and including the blank line, never past it, so the body stays in [input]. */
  fun readHead(input: InputStream, max: Int = MAX_HEAD): HeadRead {
    val buf = ByteArrayOutputStream()
    var matched = 0 // bytes of "\r\n\r\n" seen in a row
    while (true) {
      val c = input.read()
      if (c == -1) return if (buf.size() == 0) HeadRead.Closed else HeadRead.Malformed
      buf.write(c)
      if (buf.size() > max) return HeadRead.TooLarge
      matched = when {
        c == '\r'.code && (matched == 0 || matched == 2) -> matched + 1
        c == '\n'.code && (matched == 1 || matched == 3) -> matched + 1
        c == '\r'.code -> 1
        else -> 0
      }
      if (matched == 4) {
        val bytes = buf.toByteArray()
        return parse(String(bytes, 0, bytes.size - 4, Charsets.ISO_8859_1))
      }
    }
  }

  fun parse(text: String): HeadRead {
    val lines = text.split("\r\n")
    val parts = lines[0].split(' ')
    if (parts.size != 3 || parts.any { it.isEmpty() } || !parts[2].startsWith("HTTP/1.")) return HeadRead.Malformed
    val target = parts[1]
    if (!target.startsWith("/")) return HeadRead.Malformed
    val headers = HashMap<String, String>()
    for (line in lines.drop(1)) {
      val i = line.indexOf(':')
      if (i <= 0) return HeadRead.Malformed
      val name = line.substring(0, i).trim().lowercase(Locale.ROOT)
      val value = line.substring(i + 1).trim()
      val prev = headers[name]
      headers[name] = when {
        prev == null -> value
        // Two different lengths is how a body gets smuggled past a cap.
        name == "content-length" -> if (prev == value) value else return HeadRead.Malformed
        else -> "$prev, $value"
      }
    }
    return HeadRead.Ok(Head(parts[0], target.substringBefore('?'), target.substringAfter('?', ""), headers))
  }

  /** [ABSENT], [INVALID], or the declared length. Digits only: no sign, no hex, no whitespace. */
  fun contentLength(headers: Map<String, String>): Long {
    val v = headers["content-length"] ?: return ABSENT
    return if (DIGITS.matches(v)) v.toLong() else INVALID
  }

  /** AGENTS.md 9: 1.0 unless asked; null when the requested rate is not a number in [Rate.MIN, Rate.MAX]. */
  fun rate(query: String): Float? {
    val raw = query.split('&').firstOrNull { it.startsWith("rate=") }?.removePrefix("rate=") ?: return 1.0f
    val r = raw.toFloatOrNull() ?: return null
    return if (r.isFinite() && r >= Rate.MIN && r <= Rate.MAX) r else null
  }

  /** Strict UTF-8: malformed input is an error, never replacement characters. */
  fun utf8(bytes: ByteArray): String? = try {
    Charsets.UTF_8.newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)
      .decode(ByteBuffer.wrap(bytes)).toString()
  } catch (_: CharacterCodingException) {
    null
  }
}
```

- [ ] **Step 4: Run them to see them pass**

Run: `cd android && ./gradlew testDebugUnitTest --tests '*HttpTest*' -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/io/loopstring/readme/bridge/Http.kt android/app/src/test/java/io/loopstring/readme/bridge/HttpTest.kt
git commit -m "feat(bridge): parse a request head with the 16 KiB cap, strict lengths and rate

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: BridgeServer

**Files:**
- Create: `android/app/src/main/java/io/loopstring/readme/bridge/Synthesizer.kt`
- Create: `android/app/src/main/java/io/loopstring/readme/bridge/BridgeFiles.kt`
- Create: `android/app/src/main/java/io/loopstring/readme/bridge/BridgeServer.kt`
- Create: `docs/adr/0008-bridge-text-limit.md`
- Modify: `srs.md` ("Bridge contract (v1)")
- Test: `android/app/src/test/java/io/loopstring/readme/bridge/SynthWaitTest.kt`, `BridgeFilesTest.kt`, `BridgeServerTest.kt`

**Interfaces:**
- Consumes: `Http`, `Head`, `HeadRead` (Task 2).
- Produces:
  - `enum class SynthResult { OK, FAILED, TIMEOUT, CANCELLED }`
  - `interface Synthesizer { val ready: Boolean; val engine: String; val voice: String?; val maxChars: Int; fun synthesize(text: String, rate: Float, out: java.io.File): SynthResult; fun cancel() }`
  - `class SynthWait { fun begin(id: String); fun finish(id: String, r: SynthResult); fun cancel(): Boolean; fun await(ms: Long): SynthResult }`
  - `class BridgeFiles(dir: File) { fun create(): File; fun sweep(): Int; companion object { const val PREFIX = "bridge-" } }`
  - `class BridgeServer(requestedPort: Int, token: () -> String, busy: () -> Boolean, synth: Synthesizer, files: BridgeFiles, log: (String) -> Unit, readTimeoutMs: Int = 10_000, deadlineMs: Long = 180_000, workers: Int = 8) : Closeable` with `val port: Int`, `val address: InetAddress`, `fun preempt()`, `override fun close()`. The constructor throws `java.net.BindException` (an `IOException`) when the port is taken.

- [ ] **Step 1: Write the failing tests for SynthWait and BridgeFiles**

`SynthWaitTest.kt`:

```kotlin
package io.loopstring.readme.bridge

import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SynthWaitTest {
  @Test fun doneIsOk() {
    val w = SynthWait()
    w.begin("b1")
    thread { Thread.sleep(50); w.finish("b1", SynthResult.OK) }
    assertEquals(SynthResult.OK, w.await(2_000))
  }

  @Test fun anotherIdIsIgnored() {
    val w = SynthWait()
    w.begin("b2")
    w.finish("b1", SynthResult.OK)
    assertEquals(SynthResult.TIMEOUT, w.await(100))
  }

  @Test fun cancelWinsOverALateDone() {
    val w = SynthWait()
    w.begin("b1")
    assertTrue(w.cancel())
    w.finish("b1", SynthResult.OK)
    assertEquals(SynthResult.CANCELLED, w.await(1_000))
  }

  @Test fun cancelWithNothingInFlightIsFalse() {
    val w = SynthWait()
    assertFalse(w.cancel())
    w.begin("b1")
    w.finish("b1", SynthResult.FAILED)
    assertEquals(SynthResult.FAILED, w.await(1_000))
    assertFalse(w.cancel())
  }
}
```

`BridgeFilesTest.kt`:

```kotlin
package io.loopstring.readme.bridge

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeFilesTest {
  @Test fun createsWavsInTheDirAndSweepsOnlyThem() {
    val dir = Files.createTempDirectory("cache").toFile()
    val files = BridgeFiles(dir)
    val a = files.create()
    val b = files.create()
    val other = File(dir, "keep.wav").apply { writeText("x") }
    val notWav = File(dir, "bridge-notes.txt").apply { writeText("x") }
    assertTrue(a.name.startsWith("bridge-") && a.name.endsWith(".wav") && a.parentFile == dir)
    assertEquals(2, files.sweep())
    assertTrue(!a.exists() && !b.exists() && other.exists() && notWav.exists())
  }
}
```

- [ ] **Step 2: Run them to see them fail**

Run: `cd android && ./gradlew testDebugUnitTest --tests '*SynthWaitTest*' --tests '*BridgeFilesTest*' -q`
Expected: FAIL, unresolved `SynthWait`, `SynthResult`, `BridgeFiles`.

- [ ] **Step 3: Implement** `Synthesizer.kt` and `BridgeFiles.kt`

`Synthesizer.kt`:

```kotlin
package io.loopstring.readme.bridge

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

enum class SynthResult { OK, FAILED, TIMEOUT, CANCELLED }

/** The bridge's speech engine (AGENTS.md 13: never playback's instance). */
interface Synthesizer {
  val ready: Boolean
  /** The engine package, or "unknown". Reported by /health; no item data. */
  val engine: String
  /** The voice in use, or null. Reported by /health. */
  val voice: String?
  /** The longest text one synthesis accepts (ADR 0008). */
  val maxChars: Int

  /** Blocks until [out] holds the WAV or the synthesis ends otherwise. Never on the main thread. */
  fun synthesize(text: String, rate: Float, out: File): SynthResult

  /** Ends a synthesis in flight with CANCELLED (ADR 0004). Any thread; a no-op when idle. */
  fun cancel()
}

/**
 * One synthesis at a time: the engine's callbacks (binder threads) and a cancel (any thread)
 * race to end it, and the first one wins.
 */
class SynthWait {
  private val lock = Any()
  private var id: String? = null
  private var latch: CountDownLatch? = null
  private var result = SynthResult.FAILED

  fun begin(id: String) = synchronized(lock) {
    this.id = id
    latch = CountDownLatch(1)
    result = SynthResult.FAILED
  }

  fun finish(id: String, r: SynthResult) = synchronized(lock) {
    val l = latch
    if (id != this.id || l == null || l.count == 0L) return
    result = r
    l.countDown()
  }

  /** False when nothing was in flight. */
  fun cancel(): Boolean = synchronized(lock) {
    val l = latch
    if (l == null || l.count == 0L) return false
    result = SynthResult.CANCELLED
    l.countDown()
    true
  }

  fun await(ms: Long): SynthResult {
    val l = synchronized(lock) { latch } ?: return SynthResult.FAILED
    val done = l.await(ms, TimeUnit.MILLISECONDS)
    return synchronized(lock) {
      val r = if (done) result else SynthResult.TIMEOUT
      id = null
      latch = null
      r
    }
  }
}
```

`BridgeFiles.kt`:

```kotlin
package io.loopstring.readme.bridge

import java.io.File

/** R-M12: synthesized WAVs live in the app cache; the server deletes each one, and start-up sweeps leftovers. */
class BridgeFiles(private val dir: File) {
  fun create(): File = File.createTempFile(PREFIX, ".wav", dir)

  fun sweep(): Int =
    dir.listFiles { f -> f.name.startsWith(PREFIX) && f.name.endsWith(".wav") }?.count { it.delete() } ?: 0

  companion object {
    const val PREFIX = "bridge-"
  }
}
```

- [ ] **Step 4: Run them to see them pass**

Run: `cd android && ./gradlew testDebugUnitTest --tests '*SynthWaitTest*' --tests '*BridgeFilesTest*' -q`
Expected: PASS.

- [ ] **Step 5: Write the failing BridgeServer tests** (`BridgeServerTest.kt`, plain JUnit over real loopback sockets; test code may use `Socket`, the network guard scans only `src/main`)

```kotlin
package io.loopstring.readme.bridge

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.Socket
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
    override fun synthesize(text: String, rate: Float, out: File): SynthResult {
      lastRate = rate
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

  private fun start(readTimeoutMs: Int = 2_000, deadlineMs: Long = 180_000, workers: Int = 8): BridgeServer =
    BridgeServer(0, { currentToken }, { busy }, synth, BridgeFiles(cache), { logs.add(it) }, readTimeoutMs, deadlineMs, workers)
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
    assertEquals(400, call(s.port, synthHead(10), "short".toByteArray(), timeoutMs = 5_000).status)
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
    val s = start(readTimeoutMs = 300, workers = 1)
    Socket("127.0.0.1", s.port).use { c ->
      c.soTimeout = 3_000
      c.getOutputStream().write("${synthHead(10)}\r\n\r\nhalf".toByteArray())
      // The server gives up on the body and closes; the client sees end of stream, no response.
      assertEquals(-1, c.getInputStream().read())
    }
    assertEquals(200, call(s.port, "GET /health HTTP/1.1").status)
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
```

- [ ] **Step 6: Run them to see them fail**

Run: `cd android && ./gradlew testDebugUnitTest --tests '*BridgeServerTest*' -q`
Expected: FAIL, unresolved `BridgeServer`.

- [ ] **Step 7: Implement** `BridgeServer.kt`

```kotlin
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
```

- [ ] **Step 8: Run them to see them pass**

Run: `cd android && ./gradlew testDebugUnitTest --tests '*BridgeServerTest*' -q`
Expected: PASS. If `allWorkersBusyRefusesThenRecovers` or `aClientThatNeverReadsIsCutOffAtTheDeadline` is flaky, find out why (systematic-debugging) before touching the timings; never loosen an assertion to make it pass.

- [ ] **Step 9: Record the contract change**

Create `docs/adr/0008-bridge-text-limit.md`:

```markdown
# ADR 0008: A bridge synthesis takes at most the engine's input limit

- Status: accepted (owner reviews with the Phase 5 plan)
- Date: 2026-10-02
- Deciders: owner
- Amends: srs.md "Bridge contract (v1)"

## Context

The contract caps the body at 64 KiB. `TextToSpeech.synthesizeToFile` refuses text longer than
`getMaxSpeechInputLength()` (documented as 4000 characters; playback uses 3900, `TtsSpeaker.maxChars()`).
A 64 KiB body is roughly an hour of speech at rate 1.0: one WAV of about 200 MB, after minutes of
synthesis. The local-tts-reader plugin sends chunks of at most 220 characters
(note-reader-local `AGENTS.md`, chunk-cap note).

## Decision

- `POST /synthesize` answers 413 `{"error":"too-long","maxChars":N}` when the decoded text is longer
  than N, the engine limit less headroom. The 64 KiB body cap stays.
- `GET /health` reports `maxChars` (additive).
- Errors are JSON `{"error":"<code>"}`. Added codes: 404 unknown route, 405 wrong method on a known
  route, 503 `tts-not-ready` before the engine is ready, 504 when one synthesis exceeds 120 s.

## Consequences

- No caller gets an hour-long WAV in one response; the plugin's chunks are far under the limit.
- Splitting and joining WAVs in the bridge was rejected for that reason.
- All changes are additive to contract v1; the version stays 1.
```

In `srs.md` "Bridge contract (v1)", replace the table and the "Body:" bullet with:

```markdown
| Route | Method | Auth | Response |
|---|---|---|---|
| `/health` | GET | none | `{ok, version:1, ttsReady, engine, voice, port, busy, maxChars}` |
| `/synthesize?rate=<f>` | POST | token | `audio/wav`; headers `X-Synth-Ms`, `X-Rate`. `503` `{"error":"busy","reason":"playback"}` while Read Me is playing (ADR 0004) |

- Body: UTF-8 text, at most 64 KiB, and at most `maxChars` characters (ADR 0008; 413
  `{"error":"too-long","maxChars":N}` otherwise).
- Errors are JSON `{"error":"<code>"}`: 400 (bad request, length, rate, UTF-8, empty), 401, 404,
  405, 413, 431, 500, 503 (`busy` or `tts-not-ready`), 504 (synthesis over 120 s) (ADR 0008).
```

- [ ] **Step 10: Commit**

```bash
git add android/app/src/main/java/io/loopstring/readme/bridge android/app/src/test/java/io/loopstring/readme/bridge docs/adr/0008-bridge-text-limit.md srs.md
git commit -m "feat(bridge): BridgeServer with the R-M12 caps, token before body, CORS and drain

Answers /health and /synthesize per contract v1 over explicit 127.0.0.1. Workers are
bounded and every connection has a read timeout and an overall deadline, so silent or
non-reading clients cannot wedge it. ADR 0008 limits one synthesis to the engine's input
length.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: TtsSynth, the bridge's own TextToSpeech

**Files:**
- Create: `android/app/src/main/java/io/loopstring/readme/bridge/TtsSynth.kt`
- Modify: `android/app/src/main/java/io/loopstring/readme/playback/TtsSpeaker.kt` (move voice choice to the companion)
- Test: `android/app/src/test/java/io/loopstring/readme/bridge/TtsSynthTest.kt`

**Interfaces:**
- Consumes: `Synthesizer`, `SynthResult`, `SynthWait` (Task 3); `VoicePicker`, `TtsSpeaker.info`, `TtsSpeaker.maxChars()`, `TtsSpeaker.EngineStatus`.
- Produces: `class TtsSynth(context: Context, preferredVoice: String?) : Synthesizer` with `fun shutdown()`; `TtsSpeaker.chooseVoice(tts: TextToSpeech, preferred: String?): Voice?` (companion).

- [ ] **Step 1: Write the failing test** (`TtsSynthTest.kt`; Robolectric's TextToSpeech never calls onInit by itself, which is what an engine still binding looks like)

```kotlin
package io.loopstring.readme.bridge

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class TtsSynthTest {
  private val ctx = ApplicationProvider.getApplicationContext<Context>()

  @Test fun beforeTheEngineIsReadyItIsNotReadyAndRefusesWork() {
    val s = TtsSynth(ctx, null)
    shadowOf(Looper.getMainLooper()).idle()
    assertFalse(s.ready)
    assertEquals(null, s.voice)
    val out = File(Files.createTempDirectory("c").toFile(), "x.wav")
    assertEquals(SynthResult.FAILED, s.synthesize("Hello.", 1.0f, out))
    s.cancel() // nothing in flight: a no-op
    s.shutdown()
  }

  @Test fun maxCharsIsTheEngineLimitLessHeadroom() {
    val s = TtsSynth(ctx, null)
    assertEquals(io.loopstring.readme.playback.TtsSpeaker.maxChars(), s.maxChars)
    s.shutdown()
  }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `cd android && ./gradlew testDebugUnitTest --tests '*TtsSynthTest*' -q`
Expected: FAIL, unresolved `TtsSynth`.

- [ ] **Step 3: Share the voice choice.** In `TtsSpeaker.kt`, replace the private `chooseVoice()` body so it calls a companion function, and add that function to the companion:

```kotlin
  private fun chooseVoice(): EngineStatus {
    val voice = chooseVoice(tts, preferredVoice) ?: return EngineStatus.NO_VOICE
    return if (tts.setVoice(voice) == TextToSpeech.SUCCESS) EngineStatus.READY else EngineStatus.NO_VOICE
  }
```

```kotlin
    /** R-M06, AGENTS.md 5: VoicePicker's choice among this engine's voices; null means none is offline. */
    fun chooseVoice(tts: TextToSpeech, preferred: String?): Voice? {
      val voices = runCatching { tts.voices }.getOrNull().orEmpty().toList()
      val default = runCatching { tts.defaultVoice }.getOrNull()
      val language = (default?.locale ?: Locale.getDefault()).language
      val pick = VoicePicker.pick(default?.let { info(it) }, voices.map { info(it) }, language, preferred) ?: return null
      return (voices + listOfNotNull(default)).first { it.name == pick.name }
    }
```

- [ ] **Step 4: Implement** `TtsSynth.kt`

```kotlin
package io.loopstring.readme.bridge

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import io.loopstring.readme.playback.TtsSpeaker
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * The bridge's own TextToSpeech (AGENTS.md 13), separate from playback's TtsSpeaker. Same voice
 * rule (AGENTS.md 5). setSpeechRate on this instance is the only rate lever (AGENTS.md 9);
 * SPIKE-06 saw no rate leak between instances. Construct on the main thread.
 */
class TtsSynth(context: Context, private val preferredVoice: String?) : Synthesizer {
  private val main = Handler(Looper.getMainLooper())
  private val power = context.getSystemService(PowerManager::class.java)
  private val wait = SynthWait()
  private val seq = AtomicInteger()
  @Volatile private var status = TtsSpeaker.EngineStatus.PENDING
  @Volatile override var engine: String = "unknown"
    private set
  @Volatile override var voice: String? = null
    private set
  override val maxChars: Int = TtsSpeaker.maxChars()
  override val ready: Boolean get() = status == TtsSpeaker.EngineStatus.READY

  // onInit can run inside the constructor before `tts` is assigned (SPIKE-05): defer it.
  private val tts = TextToSpeech(context.applicationContext) { s -> main.post { onInit(s) } }

  private fun onInit(result: Int) {
    if (result != TextToSpeech.SUCCESS) {
      status = TtsSpeaker.EngineStatus.NO_ENGINE
      return
    }
    engine = runCatching { tts.defaultEngine }.getOrNull() ?: "unknown"
    val v = TtsSpeaker.chooseVoice(tts, preferredVoice)
    if (v == null || tts.setVoice(v) != TextToSpeech.SUCCESS) {
      status = TtsSpeaker.EngineStatus.NO_VOICE
      return
    }
    voice = v.name
    tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
      override fun onStart(id: String?) {}
      override fun onDone(id: String?) { if (id != null) wait.finish(id, SynthResult.OK) }
      @Deprecated("Deprecated in Java")
      override fun onError(id: String?) { if (id != null) wait.finish(id, SynthResult.FAILED) }
      override fun onError(id: String?, errorCode: Int) { if (id != null) wait.finish(id, SynthResult.FAILED) }
      override fun onStop(id: String?, interrupted: Boolean) { if (id != null) wait.finish(id, SynthResult.CANCELLED) }
    })
    status = TtsSpeaker.EngineStatus.READY
  }

  override fun synthesize(text: String, rate: Float, out: File): SynthResult {
    if (!ready) return SynthResult.FAILED
    val id = "bridge-${seq.incrementAndGet()}"
    wait.begin(id)
    // A screen-off request from Obsidian must finish; held only for this one synthesis.
    val lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ReadMe:bridge")
      .apply { setReferenceCounted(false); acquire(TIMEOUT_MS + 5_000) }
    try {
      tts.setSpeechRate(rate)
      if (tts.synthesizeToFile(text, Bundle(), out, id) != TextToSpeech.SUCCESS) {
        wait.cancel()
        wait.await(0)
        return SynthResult.FAILED
      }
      val r = wait.await(TIMEOUT_MS)
      if (r == SynthResult.TIMEOUT) tts.stop()
      return r
    } finally {
      if (lock.isHeld) lock.release()
    }
  }

  override fun cancel() {
    if (wait.cancel()) tts.stop()
  }

  fun shutdown() {
    cancel()
    tts.stop()
    tts.shutdown()
  }

  companion object {
    const val TIMEOUT_MS = 120_000L
  }
}
```

- [ ] **Step 5: Run the tests**

Run: `cd android && ./gradlew testDebugUnitTest --tests '*TtsSynthTest*' --tests '*TtsSpeaker*' --tests '*PoliciesTest*' --tests '*PlaybackServiceTest*' -q`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/io/loopstring/readme/bridge/TtsSynth.kt android/app/src/main/java/io/loopstring/readme/playback/TtsSpeaker.kt android/app/src/test/java/io/loopstring/readme/bridge/TtsSynthTest.kt
git commit -m "feat(bridge): the bridge's own TextToSpeech, offline voices only, rate set once

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: PlaybackService hosts the bridge

**Files:**
- Modify: `android/app/src/main/java/io/loopstring/readme/playback/PlaybackHub.kt`
- Modify: `android/app/src/main/java/io/loopstring/readme/playback/Policies.kt`
- Modify: `android/app/src/main/java/io/loopstring/readme/playback/PlaybackService.kt`
- Modify: `android/app/src/main/java/io/loopstring/readme/MainActivity.kt`
- Modify: `android/app/src/main/AndroidManifest.xml` (comment only)
- Test: `android/app/src/test/java/io/loopstring/readme/playback/PoliciesTest.kt`, `PlaybackHubTest.kt`, `PlaybackServiceTest.kt`

**Interfaces:**
- Consumes: `Settings.bridgeEnabled/bridgePort/bridgeToken()` (Task 1); `BridgeServer`, `BridgeFiles` (Task 3); `TtsSynth` (Task 4).
- Produces:
  - `data class BridgeStatus(val state: String, val port: Int, val error: String?)` with `BridgeStatus.OFF` (`state` is `"off"`, `"on"` or `"failed"`; `error` is an exception class name).
  - `PlaybackHub.bridge: BridgeStatus`, `PlaybackHub.publishBridge(b)`, `addBridgeListener`, `removeBridgeListener`.
  - `PlaybackCommands.ACTION_BRIDGE`, `ACTION_BRIDGE_OFF`, `Route.BRIDGE`, `route(action, hasPending, hasItem, bridgeOn = false)`.
  - `object ServiceLife { fun foreground(playing: Boolean, pausedForFocus: Boolean, bridgeOn: Boolean): Boolean; fun keepAlive(hasItem: Boolean, bridgeOn: Boolean): Boolean }`.
  - `object ServiceText { data class Text(val title: String, val body: String, val media: Boolean); fun of(itemTitle: String, s: PlaybackSnapshot, bridgeOn: Boolean): Text }`.
  - `PlaybackService.syncBridge(context: Context)` (companion): starts or tells the service to match Settings.

- [ ] **Step 1: Write the failing policy and hub tests** (append to `PoliciesTest.kt` and `PlaybackHubTest.kt`)

`PoliciesTest.kt`:

```kotlin
  @Test fun bridgeActionsRouteToBridgeAndTheBridgeKeepsAnIdleServiceAlive() {
    assertEquals(Route.BRIDGE, PlaybackCommands.route(PlaybackCommands.ACTION_BRIDGE, false, false))
    assertEquals(Route.BRIDGE, PlaybackCommands.route(PlaybackCommands.ACTION_BRIDGE_OFF, false, true))
    assertEquals(Route.IGNORE, PlaybackCommands.route(null, false, false, bridgeOn = true))
    assertEquals(Route.IGNORE, PlaybackCommands.route(PlaybackCommands.ACTION_PLAY, false, false, bridgeOn = true))
    assertEquals(Route.STOP, PlaybackCommands.route(null, false, false, bridgeOn = false))
  }

  @Test fun theBridgeKeepsTheServiceInTheForeground() {
    assertTrue(ServiceLife.foreground(playing = false, pausedForFocus = false, bridgeOn = true))
    assertFalse(ServiceLife.foreground(playing = false, pausedForFocus = false, bridgeOn = false))
    assertTrue(ServiceLife.foreground(playing = true, pausedForFocus = false, bridgeOn = false))
    assertTrue(ServiceLife.keepAlive(hasItem = false, bridgeOn = true))
    assertFalse(ServiceLife.keepAlive(hasItem = false, bridgeOn = false))
  }

  @Test fun theNotificationSaysWhenTheBridgeIsOn() {
    val idle = PlaybackSnapshot(null, false, null, 2.0f)
    val playing = PlaybackSnapshot(1L, true, null, 2.0f)
    assertEquals(ServiceText.Text("Read Me", "Obsidian bridge on", false), ServiceText.of("", idle, true))
    assertEquals(ServiceText.Text("Title", "Reading · Obsidian bridge on", true), ServiceText.of("Title", playing, true))
    assertEquals(ServiceText.Text("Title", "Paused", true), ServiceText.of("Title", playing.copy(playing = false), false))
  }
```

Check `PlaybackSnapshot`'s constructor in `PlaybackQueue.kt` before writing these; adjust the arguments to its real fields (the hub builds `PlaybackSnapshot(null, false, null, Rate.DEFAULT)`).

`PlaybackHubTest.kt`:

```kotlin
  @Test fun bridgeStatusIsPublishedToListeners() {
    val seen = mutableListOf<BridgeStatus>()
    PlaybackHub.addBridgeListener { seen += it }
    PlaybackHub.publishBridge(BridgeStatus("on", 8787, null))
    assertEquals(BridgeStatus("on", 8787, null), PlaybackHub.bridge)
    assertEquals(listOf(BridgeStatus("on", 8787, null)), seen)
    PlaybackHub.resetForTest()
    assertEquals(BridgeStatus.OFF, PlaybackHub.bridge)
  }
```

- [ ] **Step 2: Run them to see them fail**

Run: `cd android && ./gradlew testDebugUnitTest --tests '*PoliciesTest*' --tests '*PlaybackHubTest*' -q`
Expected: FAIL, unresolved `ACTION_BRIDGE`, `ServiceLife`, `ServiceText`, `BridgeStatus`.

- [ ] **Step 3: Implement the hub and policies**

`PlaybackHub.kt`, at top level of the file:

```kotlin
/** R-M12: what Settings shows. `error` is an exception class name, never a message. */
data class BridgeStatus(val state: String, val port: Int, val error: String?) {
  companion object {
    val OFF = BridgeStatus("off", 0, null)
  }
}
```

Inside `object PlaybackHub` (and update its doc comment: "the bridge reads `speaking`; the service publishes `bridge`"):

```kotlin
  private val bridgeListeners = CopyOnWriteArraySet<(BridgeStatus) -> Unit>()
  @Volatile var bridge: BridgeStatus = BridgeStatus.OFF
    private set

  fun publishBridge(b: BridgeStatus) {
    bridge = b
    for (l in bridgeListeners) l(b)
  }

  fun addBridgeListener(l: (BridgeStatus) -> Unit) { bridgeListeners.add(l) }
  fun removeBridgeListener(l: (BridgeStatus) -> Unit) { bridgeListeners.remove(l) }
```

and in `resetForTest()`: `bridge = BridgeStatus.OFF` and `bridgeListeners.clear()`.

`Policies.kt`, in `PlaybackCommands`:

```kotlin
  const val ACTION_BRIDGE = P + "BRIDGE"
  const val ACTION_BRIDGE_OFF = P + "BRIDGE_OFF"
```

```kotlin
  enum class Route { START, CONTROL, BRIDGE, IGNORE, STOP }

  fun route(action: String?, hasPending: Boolean, hasItem: Boolean, bridgeOn: Boolean = false): Route = when {
    action == ACTION_START && hasPending -> Route.START
    action == ACTION_BRIDGE || action == ACTION_BRIDGE_OFF -> Route.BRIDGE
    action in CONTROLS && hasItem -> Route.CONTROL
    hasItem || bridgeOn -> Route.IGNORE
    else -> Route.STOP
  }
```

Append to `Policies.kt`:

```kotlin
/** R-M12, ADR 0005: the bridge needs the foreground service whether or not anything plays. */
object ServiceLife {
  fun foreground(playing: Boolean, pausedForFocus: Boolean, bridgeOn: Boolean) =
    playing || PausePolicy.hold(pausedForFocus).foreground || bridgeOn

  fun keepAlive(hasItem: Boolean, bridgeOn: Boolean) = hasItem || bridgeOn
}

/** R-M12: "its foreground notification MUST say so". */
object ServiceText {
  data class Text(val title: String, val body: String, val media: Boolean)

  fun of(itemTitle: String, s: PlaybackSnapshot, bridgeOn: Boolean): Text {
    if (s.itemId == null) return Text("Read Me", if (bridgeOn) "Obsidian bridge on" else "Starting", false)
    val state = if (s.playing) "Reading" else "Paused"
    return Text(itemTitle, if (bridgeOn) "$state · Obsidian bridge on" else state, true)
  }
}
```

- [ ] **Step 4: Run the policy and hub tests**

Run: `cd android && ./gradlew testDebugUnitTest --tests '*PoliciesTest*' --tests '*PlaybackHubTest*' -q`
Expected: PASS.

- [ ] **Step 5: Write the failing service tests** (append to `PlaybackServiceTest.kt`; add the imports used)

```kotlin
  private fun freePort() = java.net.ServerSocket(0).use { it.localPort }

  private fun health(port: Int): Int = java.net.Socket("127.0.0.1", port).use { s ->
    s.soTimeout = 3_000
    s.getOutputStream().write("GET /health HTTP/1.1\r\n\r\n".toByteArray())
    String(s.getInputStream().readBytes()).substringAfter(' ').take(3).toInt()
  }

  private fun bridgeIntent(ctx: Context, action: String = PlaybackCommands.ACTION_BRIDGE) =
    Intent(ctx, PlaybackService::class.java).setAction(action)

  @Test fun theBridgeServesWhileEnabledAndTheNotificationSaysSo() {
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    val port = freePort()
    Settings(ctx).apply { bridgePort = port; bridgeEnabled = true }
    val c = Robolectric.buildService(PlaybackService::class.java, bridgeIntent(ctx)).create().startCommand(0, 1)
    shadowOf(Looper.getMainLooper()).idle()
    assertEquals(BridgeStatus("on", port, null), PlaybackHub.bridge)
    assertEquals(200, health(port))
    val n = shadowOf(c.get()).lastForegroundNotification
    assertEquals("Obsidian bridge on", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())

    Settings(ctx).bridgeEnabled = false
    c.withIntent(bridgeIntent(ctx)).startCommand(0, 2)
    shadowOf(Looper.getMainLooper()).idle()
    assertEquals(BridgeStatus.OFF, PlaybackHub.bridge)
    assertTrue(runCatching { health(port) }.isFailure)
    assertTrue(shadowOf(c.get()).isStoppedBySelf)
    c.destroy()
  }

  @Test fun theNotificationsTurnOffActionDisablesTheBridge() {
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    val port = freePort()
    Settings(ctx).apply { bridgePort = port; bridgeEnabled = true }
    val c = Robolectric.buildService(PlaybackService::class.java, bridgeIntent(ctx)).create().startCommand(0, 1)
    shadowOf(Looper.getMainLooper()).idle()
    c.withIntent(bridgeIntent(ctx, PlaybackCommands.ACTION_BRIDGE_OFF)).startCommand(0, 2)
    shadowOf(Looper.getMainLooper()).idle()
    assertFalse(Settings(ctx).bridgeEnabled)
    assertEquals(BridgeStatus.OFF, PlaybackHub.bridge)
    c.destroy()
  }

  @Test fun aTakenPortReportsFailedAndStopsAnIdleService() {
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    java.net.ServerSocket(0, 1, java.net.InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))).use { taken ->
      Settings(ctx).apply { bridgePort = taken.localPort; bridgeEnabled = true }
      val c = Robolectric.buildService(PlaybackService::class.java, bridgeIntent(ctx)).create().startCommand(0, 1)
      shadowOf(Looper.getMainLooper()).idle()
      assertEquals(BridgeStatus("failed", taken.localPort, "BindException"), PlaybackHub.bridge)
      assertTrue(shadowOf(c.get()).isStoppedBySelf)
      c.destroy()
    }
  }

  @Test fun theBridgeKeepsTheServiceAfterPlaybackEnds() {
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    val port = freePort()
    Settings(ctx).apply { bridgePort = port; bridgeEnabled = true }
    val c = Robolectric.buildService(PlaybackService::class.java, bridgeIntent(ctx)).create().startCommand(0, 1)
    shadowOf(Looper.getMainLooper()).idle()
    // An item ends: the queue reports an empty snapshot, as it does on finish or stop.
    c.get().changed(PlaybackSnapshot(null, false, null, 2.0f))
    shadowOf(Looper.getMainLooper()).idle()
    assertFalse(shadowOf(c.get()).isStoppedBySelf)
    assertEquals(200, health(port))
    val n = shadowOf(c.get()).lastForegroundNotification
    assertEquals("Obsidian bridge on", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
    c.destroy()
  }

  @Test fun startUpSweepsStaleBridgeFiles() {
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    val stale = java.io.File(ctx.cacheDir, "bridge-old.wav").apply { writeText("x") }
    val c = Robolectric.buildService(PlaybackService::class.java).create()
    assertFalse(stale.exists())
    c.destroy()
  }

  @Test fun startingPlaybackPreemptsTheBridge() {
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    Settings(ctx).apply { bridgePort = freePort(); bridgeEnabled = true }
    val c = Robolectric.buildService(PlaybackService::class.java, bridgeIntent(ctx)).create().startCommand(0, 1)
    shadowOf(Looper.getMainLooper()).idle()
    PlaybackHub.publish(PlaybackSnapshot(1L, true, null, 2.0f))
    assertEquals(1, c.get().bridgePreemptsForTest)
    c.destroy()
  }
```

`tearDown` must also reset the bridge settings: add `Settings(ApplicationProvider.getApplicationContext()).bridgeEnabled = false` to the `@After`. `changed()` is the service's public `PlaybackSink` callback; `PlaybackQueue.stop()` cannot stand in for it here because it returns early when no item is loaded.

- [ ] **Step 6: Run them to see them fail**

Run: `cd android && ./gradlew testDebugUnitTest --tests '*PlaybackServiceTest*' -q`
Expected: FAIL (no bridge in the service, `bridgePreemptsForTest` unresolved).

- [ ] **Step 7: Implement in `PlaybackService.kt`**

Imports: `io.loopstring.readme.bridge.BridgeFiles`, `io.loopstring.readme.bridge.BridgeServer`, `io.loopstring.readme.bridge.TtsSynth`, `java.io.IOException`, `androidx.annotation.VisibleForTesting`.

Update the class doc: "R-M07 and R-M12: the `mediaPlayback` foreground service that owns playback and, when enabled, hosts the bridge (ADR 0005)."

Fields:

```kotlin
  private var bridge: BridgeServer? = null
  private var synth: TtsSynth? = null
  @VisibleForTesting var bridgePreemptsForTest = 0
    private set
  // ADR 0004: playback starting stops a bridge synthesis in flight (the bridge's instance only).
  private val preemptOnPlay: (PlaybackSnapshot) -> Unit = { s ->
    if (s.playing) bridge?.let { it.preempt(); bridgePreemptsForTest++ }
  }
```

In `onCreate()`, after `PlaybackHub.engine = ...`:

```kotlin
    val swept = BridgeFiles(cacheDir).sweep()
    if (swept > 0) Log.i(TAG, "bridge swept files=$swept")
    PlaybackHub.addListener(preemptOnPlay)
```

`onStartCommand`, replacing the `when` block:

```kotlin
    when (PlaybackCommands.route(intent?.action, PlaybackHub.hasPending(), queue.snapshot().itemId != null, bridge != null)) {
      Route.START -> PlaybackHub.take()?.let(::begin)
      Route.CONTROL -> handle(intent!!.action!!)
      Route.BRIDGE -> {
        if (intent?.action == PlaybackCommands.ACTION_BRIDGE_OFF) Settings(this).bridgeEnabled = false
        syncBridge()
        if (!ServiceLife.keepAlive(queue.snapshot().itemId != null, bridge != null)) {
          end()
          return START_NOT_STICKY
        }
      }
      Route.IGNORE -> {}
      Route.STOP -> {
        end()
        return START_NOT_STICKY
      }
    }
```

New private function:

```kotlin
  /** R-M12: the bridge runs exactly while Settings says so, on the port Settings names. */
  private fun syncBridge() {
    val s = Settings(this)
    val want = s.bridgeEnabled
    bridge?.let { running ->
      if (!want || running.port != s.bridgePort) {
        running.close()
        bridge = null
        synth?.shutdown()
        synth = null
        PlaybackHub.publishBridge(BridgeStatus.OFF)
        Log.i(TAG, "bridge off")
      }
    }
    if (!want || bridge != null) return
    val sy = TtsSynth(this, s.voice)
    try {
      bridge = BridgeServer(
        s.bridgePort,
        token = { Settings(this).bridgeToken() },
        busy = { PlaybackHub.speaking },
        synth = sy,
        files = BridgeFiles(cacheDir),
        log = { Log.i(TAG, it) },
      )
      synth = sy
      PlaybackHub.publishBridge(BridgeStatus("on", s.bridgePort, null))
      Log.i(TAG, "bridge on port=${s.bridgePort}")
    } catch (e: IOException) {
      sy.shutdown()
      PlaybackHub.publishBridge(BridgeStatus("failed", s.bridgePort, e.javaClass.simpleName))
      Log.i(TAG, "bridge failed: ${e.javaClass.simpleName}")
    }
  }
```

`onDestroy()`, before `speaker.shutdown()`:

```kotlin
    PlaybackHub.removeListener(preemptOnPlay)
    bridge?.close()
    bridge = null
    synth?.shutdown()
    synth = null
    if (PlaybackHub.bridge.state == "on") PlaybackHub.publishBridge(BridgeStatus.OFF)
```

`end()`, replacing its body:

```kotlin
  private fun end() {
    waiting = null
    releasePlayingResources(abandon = true)
    session.isActive = false
    // R-M12: an enabled bridge keeps the service, and its notification, after playback ends.
    if (bridge != null) {
      goForeground(queue.snapshot())
      return
    }
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf()
  }
```

`syncForeground(s)`, replacing its first lines:

```kotlin
  private fun syncForeground(s: PlaybackSnapshot) {
    val bridgeOn = bridge != null
    if (s.itemId == null && !bridgeOn) return
    if (ServiceLife.foreground(s.playing, pausedForFocus, bridgeOn)) {
      goForeground(s)
    } else {
```

(the `else` branch stays as it is).

`notification(s)`, replacing the builder chain after `val open = ...` (keep `setSmallIcon(...)` on its own line; see the merge note):

```kotlin
    val bridgeOn = bridge != null
    val text = ServiceText.of(title, s, bridgeOn)
    b.setSmallIcon(android.R.drawable.ic_media_play)
      .setContentTitle(text.title)
      .setContentText(text.body)
      .setContentIntent(open)
      .setOngoing(s.playing || bridgeOn)
    if (text.media) {
      b.addAction(action(android.R.drawable.ic_media_previous, "Previous", PlaybackCommands.ACTION_PREVIOUS, 1))
        .addAction(toggle)
        .addAction(action(android.R.drawable.ic_media_next, "Next", PlaybackCommands.ACTION_NEXT, 3))
        .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
    }
    if (bridgeOn) b.addAction(action(android.R.drawable.ic_menu_close_clear_cancel, "Turn off bridge", PlaybackCommands.ACTION_BRIDGE_OFF, 4))
    return b.build()
```

Companion, after `start(...)`:

```kotlin
    /**
     * R-M12: makes the running service match Settings, starting it when the bridge is on. Call
     * only from the foreground (Settings, MainActivity.onResume): Android 12+ refuses a
     * foreground-service start from the background.
     */
    fun syncBridge(context: Context) {
      val on = Settings(context).bridgeEnabled
      val alive = PlaybackHub.controller != null
      if (!on && !alive) {
        PlaybackHub.publishBridge(BridgeStatus.OFF)
        return
      }
      val i = Intent(context, PlaybackService::class.java).setAction(PlaybackCommands.ACTION_BRIDGE)
      if (alive || Build.VERSION.SDK_INT < 26) context.startService(i) else context.startForegroundService(i)
    }
```

- [ ] **Step 8: Restart an enabled bridge when Read Me opens.** In `MainActivity.kt`:

```kotlin
  /** R-M12: after a reboot or process death the bridge returns when Read Me is next opened. */
  override fun onResume() {
    super.onResume()
    try {
      if (Settings(this).bridgeEnabled && PlaybackHub.bridge.state != "on") PlaybackService.syncBridge(this)
    } catch (e: IllegalStateException) {
      Log.i("ReadMe", "bridge start refused: ${e.javaClass.simpleName}")
    }
  }
```

with imports `android.util.Log`, `io.loopstring.readme.playback.PlaybackHub`, `io.loopstring.readme.playback.PlaybackService`, `io.loopstring.readme.store.Settings`.

In `AndroidManifest.xml`, change the service comment's "(and in Phase 5 the bridge)" to "and the R-M12 bridge".

- [ ] **Step 9: Run the Kotlin suite**

Run: `cd android && ./gradlew testDebugUnitTest -q`
Expected: PASS, every test (old playback tests included; a changed `route` signature keeps its default).

- [ ] **Step 10: Commit**

```bash
git add android/app/src/main android/app/src/test
git commit -m "feat(bridge): PlaybackService hosts the bridge while Settings has it on

The service stays in the foreground for an enabled bridge, its notification says so and
offers Turn off, playback starting preempts a bridge synthesis (ADR 0004), and stale WAVs
are swept at start. A taken port is reported, not fatal.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Settings > Obsidian bridge (module, JS facade, screen)

**Files:**
- Create: `android/app/src/main/java/io/loopstring/readme/bridge/BridgeView.kt`
- Modify: `android/app/src/main/java/io/loopstring/readme/ReadMeSpeechModule.kt`
- Modify: `src/native/NativeReadMeSpeech.ts`
- Create: `src/library/bridge.ts`
- Modify: `src/ui/model.ts`, `src/ui/SettingsScreen.tsx`
- Test: `android/app/src/test/java/io/loopstring/readme/bridge/BridgeViewTest.kt`, `__tests__/uiModel.test.ts`, `__tests__/screens.test.tsx`, `__tests__/bridge.test.ts` (new)

**Interfaces:**
- Consumes: `Settings` bridge members (Task 1); `PlaybackHub.bridge`, bridge listeners, `PlaybackService.syncBridge` (Task 5).
- Produces:
  - Kotlin: `object BridgeView { fun state(enabled: Boolean, hub: String): String }`, `object TokenClipboard { fun copy(context: Context, token: String) }`.
  - Spec: `type NativeBridge = { enabled: boolean; state: string; port: number; token: string | null; error: string | null }`; `getBridge(): Promise<NativeBridge>`, `setBridgeEnabled(enabled: boolean): Promise<NativeBridge>`, `setBridgePort(port: number): Promise<NativeBridge>`, `regenerateBridgeToken(): Promise<NativeBridge>`, `copyBridgeToken(): Promise<void>`; event `ReadMeBridge` carrying a `NativeBridge`.
  - TS: `src/library/bridge.ts` exports `Bridge`, `getBridge`, `setBridgeEnabled`, `setBridgePort`, `regenerateBridgeToken`, `copyBridgeToken`, `onBridge(cb): () => void`; `src/ui/model.ts` exports `parsePort(s: string): number | null`, `bridgeStatus(b: Bridge): string`.

- [ ] **Step 1: Write the failing Kotlin tests** (`BridgeViewTest.kt`)

```kotlin
package io.loopstring.readme.bridge

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BridgeViewTest {
  @Test fun stateIsOffUnlessEnabledAndStartingUntilTheServiceAnswers() {
    assertEquals("off", BridgeView.state(false, "on"))
    assertEquals("starting", BridgeView.state(true, "off"))
    assertEquals("on", BridgeView.state(true, "on"))
    assertEquals("failed", BridgeView.state(true, "failed"))
  }

  @Test fun theCopiedTokenIsMarkedSensitive() {
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    TokenClipboard.copy(ctx, "0123456789abcdef0123456789abcdef")
    val clip = ctx.getSystemService(ClipboardManager::class.java).primaryClip!!
    assertEquals("0123456789abcdef0123456789abcdef", clip.getItemAt(0).text.toString())
    assertTrue(clip.description.extras!!.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE))
  }
}
```

- [ ] **Step 2: Run them to see them fail**

Run: `cd android && ./gradlew testDebugUnitTest --tests '*BridgeViewTest*' -q`
Expected: FAIL, unresolved `BridgeView`, `TokenClipboard`.

- [ ] **Step 3: Implement** `BridgeView.kt`

```kotlin
package io.loopstring.readme.bridge

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle

/** What Settings shows for the bridge: Settings says whether it should run, the service whether it does. */
object BridgeView {
  fun state(enabled: Boolean, hub: String): String = when {
    !enabled -> "off"
    hub == "on" || hub == "failed" -> hub
    else -> "starting"
  }
}

/** R-M12 "shown in Settings with a Copy button". Marked sensitive so Android 13+ hides it from the clipboard preview. */
object TokenClipboard {
  fun copy(context: Context, token: String) {
    val clip = ClipData.newPlainText("Read Me bridge token", token)
    clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
  }
}
```

Run: `cd android && ./gradlew testDebugUnitTest --tests '*BridgeViewTest*' -q`
Expected: PASS.

- [ ] **Step 4: Extend the TurboModule spec** (`src/native/NativeReadMeSpeech.ts`)

Add the type after `NativeEngine`:

```ts
// R-M12: state is 'off', 'starting', 'on' or 'failed'; error is an exception class name
// ('BindException' when the port is taken). token is null while the bridge is off.
export type NativeBridge = {
  enabled: boolean;
  state: string;
  port: number;
  token: string | null;
  error: string | null;
};
```

Add to `Spec`, after `getNotices`:

```ts
  getBridge(): Promise<NativeBridge>;
  setBridgeEnabled(enabled: boolean): Promise<NativeBridge>;
  setBridgePort(port: number): Promise<NativeBridge>;
  regenerateBridgeToken(): Promise<NativeBridge>;
  copyBridgeToken(): Promise<void>;
```

- [ ] **Step 5: Implement the module methods** (`ReadMeSpeechModule.kt`; codegen regenerates `NativeReadMeSpeechSpec` from the spec on the next build)

Imports: `io.loopstring.readme.bridge.BridgeView`, `io.loopstring.readme.bridge.TokenClipboard`, `io.loopstring.readme.playback.BridgeStatus`, `io.loopstring.readme.playback.PlaybackService`.

Field and lifecycle:

```kotlin
  private val onBridge: (BridgeStatus) -> Unit = {
    reactApplicationContext.emitDeviceEvent(EVENT_BRIDGE, bridgeMap())
  }
```

In `initialize()`: `PlaybackHub.addBridgeListener(onBridge)`. In `invalidate()`: `PlaybackHub.removeBridgeListener(onBridge)`.

Methods:

```kotlin
  // R-M12, R-M01 Settings. The token goes to JS only to be shown; nothing here logs it (AGENTS.md 4).
  override fun getBridge(promise: Promise) = settle(promise) { bridgeMap() }

  override fun setBridgeEnabled(enabled: Boolean, promise: Promise) = settle(promise) {
    settings.bridgeEnabled = enabled
    if (enabled) settings.bridgeToken() // generated once, on first use
    PlaybackService.syncBridge(reactApplicationContext)
    bridgeMap()
  }

  override fun setBridgePort(port: Double, promise: Promise) = settle(promise) {
    settings.bridgePort = port.toInt()
    if (settings.bridgeEnabled) PlaybackService.syncBridge(reactApplicationContext)
    bridgeMap()
  }

  override fun regenerateBridgeToken(promise: Promise) = settle(promise) {
    settings.regenerateBridgeToken()
    bridgeMap()
  }

  override fun copyBridgeToken(promise: Promise) = settle(promise) {
    TokenClipboard.copy(reactApplicationContext, settings.bridgeToken())
    null
  }

  private fun bridgeMap(): WritableMap = Arguments.createMap().apply {
    val on = settings.bridgeEnabled
    val hub = PlaybackHub.bridge
    putBoolean("enabled", on)
    putString("state", BridgeView.state(on, hub.state))
    putInt("port", settings.bridgePort)
    putString("token", if (on) settings.bridgeToken() else null)
    putString("error", if (on && hub.state == "failed") hub.error else null)
  }
```

Add `const val EVENT_BRIDGE = "ReadMeBridge"` next to the other event constants. `settle` already rejects with the class name only.

- [ ] **Step 6: Write the failing JS tests**

`__tests__/uiModel.test.ts` (append; import `parsePort`, `bridgeStatus` from `../src/ui/model`):

```ts
test('parsePort accepts 1024 to 65535 only', () => {
  expect(parsePort('8787')).toBe(8787);
  expect(parsePort(' 1024 ')).toBe(1024);
  expect(parsePort('65535')).toBe(65535);
  for (const bad of ['', '80', '1023', '65536', '87.87', '-1', 'abc', '8787a', '0x2253']) {
    expect(parsePort(bad)).toBeNull();
  }
});

test('bridgeStatus explains a taken port', () => {
  const b = { enabled: true, state: 'on', port: 8787, token: 'x', error: null };
  expect(bridgeStatus({ ...b, enabled: false, state: 'off' })).toBe('Off');
  expect(bridgeStatus({ ...b, state: 'starting' })).toBe('Starting...');
  expect(bridgeStatus(b)).toBe('On at 127.0.0.1:8787');
  expect(bridgeStatus({ ...b, state: 'failed', error: 'BindException' })).toBe(
    'Port 8787 is in use by another app. Choose another port.',
  );
  expect(bridgeStatus({ ...b, state: 'failed', error: 'SecurityException' })).toBe(
    'The bridge could not start (SecurityException).',
  );
});
```

`__tests__/bridge.test.ts` (new):

```ts
const mockEmitter = { addListener: jest.fn(() => ({ remove: jest.fn() })) };
jest.mock('react-native', () => ({ NativeEventEmitter: jest.fn(() => mockEmitter) }));
jest.mock('../src/native/NativeReadMeSpeech', () => ({ __esModule: true, default: {} }));

import { onBridge } from '../src/library/bridge';

test('onBridge listens for ReadMeBridge and unsubscribes', () => {
  const cb = jest.fn();
  const off = onBridge(cb);
  expect(mockEmitter.addListener).toHaveBeenCalledWith('ReadMeBridge', expect.any(Function));
  const handler = (mockEmitter.addListener.mock.calls[0] as unknown[])[1] as (b: unknown) => void;
  handler({ enabled: true, state: 'on', port: 8787, token: 't', error: null });
  expect(cb).toHaveBeenCalledWith({ enabled: true, state: 'on', port: 8787, token: 't', error: null });
  off();
});
```

`__tests__/screens.test.tsx`: add to `mockState` `bridge: { enabled: false, state: 'off', port: 8787, token: null as string | null, error: null as string | null }`, reset it in `beforeEach`, and add to the mocked default export:

```ts
    getBridge: jest.fn(async () => mockState.bridge),
    setBridgeEnabled: jest.fn(async (on: boolean) => {
      mockState.bridge = { ...mockState.bridge, enabled: on, state: on ? 'on' : 'off', token: on ? '0123456789abcdef0123456789abcdef' : null };
      return mockState.bridge;
    }),
    setBridgePort: jest.fn(async () => mockState.bridge),
    regenerateBridgeToken: jest.fn(async () => mockState.bridge),
    copyBridgeToken: jest.fn(async () => undefined),
```

Tests:

```ts
test('Settings shows the bridge off, with no token (R-M12)', async () => {
  const out = await render(<SettingsScreen onLicenses={() => {}} />);
  expect(out).toContain('Obsidian bridge');
  expect(out).toContain('Off');
  expect(out).toContain('"bridge on"');
  expect(out).not.toContain('pairing token');
});

test('Settings shows the token with Copy and New token while the bridge is on', async () => {
  mockState.bridge = { enabled: true, state: 'on', port: 8787, token: '0123456789abcdef0123456789abcdef', error: null };
  const out = await render(<SettingsScreen onLicenses={() => {}} />);
  expect(out).toContain('On at 127.0.0.1:8787');
  expect(out).toContain('0123456789abcdef0123456789abcdef');
  expect(out).toContain('"copy token"');
  expect(out).toContain('"new token"');
  expect(out).toContain('"bridge off"');
});

test('turning the bridge on goes through the module', async () => {
  let r!: ReactTestRenderer.ReactTestRenderer;
  await ReactTestRenderer.act(async () => {
    r = ReactTestRenderer.create(<SettingsScreen onLicenses={() => {}} />);
  });
  mounted.push(r);
  await ReactTestRenderer.act(async () => {
    r.root.find(n => n.props.accessibilityLabel === 'bridge on' && typeof n.props.onPress === 'function').props.onPress();
  });
  expect(Native.setBridgeEnabled).toHaveBeenCalledWith(true);
  expect(strings(r.toJSON()).join('\n')).toContain('0123456789abcdef0123456789abcdef');
});
```

- [ ] **Step 7: Run them to see them fail**

Run: `npx jest __tests__/uiModel.test.ts __tests__/bridge.test.ts __tests__/screens.test.tsx`
Expected: FAIL (`parsePort`, `bridgeStatus`, `src/library/bridge` missing; Settings has no bridge section).

- [ ] **Step 8: Implement the facade, model and screen**

`src/library/bridge.ts`:

```ts
// R-M12 and R-M01 Settings: the bridge's settings, through ReadMeSpeech. The bridge itself is
// Kotlin (BridgeServer); JS never serves or calls it.
import { NativeEventEmitter } from 'react-native';
import Native, { type NativeBridge } from '../native/NativeReadMeSpeech';

export type Bridge = NativeBridge;

export const getBridge = (): Promise<Bridge> => Native.getBridge();
export const setBridgeEnabled = (on: boolean): Promise<Bridge> => Native.setBridgeEnabled(on);
export const setBridgePort = (port: number): Promise<Bridge> => Native.setBridgePort(port);
export const regenerateBridgeToken = (): Promise<Bridge> => Native.regenerateBridgeToken();
export const copyBridgeToken = (): Promise<void> => Native.copyBridgeToken();

export function onBridge(cb: (b: Bridge) => void): () => void {
  const sub = new NativeEventEmitter(Native).addListener('ReadMeBridge', (n: unknown) => cb(n as Bridge));
  return () => sub.remove();
}
```

`src/ui/model.ts` (append):

```ts
// R-M12: the bridge port is configurable within the unprivileged range.
export const BRIDGE_PORT_MIN = 1024;
export const BRIDGE_PORT_MAX = 65535;

export function parsePort(s: string): number | null {
  const t = s.trim();
  if (!/^\d{1,5}$/.test(t)) return null;
  const n = Number(t);
  return n >= BRIDGE_PORT_MIN && n <= BRIDGE_PORT_MAX ? n : null;
}

export function bridgeStatus(b: { enabled: boolean; state: string; port: number; error: string | null }): string {
  if (!b.enabled) return 'Off';
  if (b.state === 'on') return `On at 127.0.0.1:${b.port}`;
  if (b.state === 'failed') {
    return b.error === 'BindException'
      ? `Port ${b.port} is in use by another app. Choose another port.`
      : `The bridge could not start (${b.error ?? 'unknown'}).`;
  }
  return 'Starting...';
}
```

`src/ui/SettingsScreen.tsx`: change the header comment's second line to `// R-M12: the Obsidian bridge row (on/off, port, pairing token with Copy).`; add imports `TextInput` from `react-native`, `{ copyBridgeToken, getBridge, onBridge, regenerateBridgeToken, setBridgeEnabled, setBridgePort, type Bridge } from '../library/bridge'`, and `bridgeStatus, parsePort` from `./model`. State and effects inside the component:

```tsx
  const [bridge, setBridge] = useState<Bridge | null>(null);
  const [portText, setPortText] = useState('');
  const [portError, setPortError] = useState(false);
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    getBridge().then(b => {
      setBridge(b);
      setPortText(String(b.port));
    }, () => undefined);
    return onBridge(setBridge);
  }, []);

  const applyPort = () => {
    const p = parsePort(portText);
    setPortError(p === null);
    if (p !== null && p !== bridge?.port) setBridgePort(p).then(setBridge, () => setPortError(true));
  };
  const newToken = () =>
    Alert.alert('Make a new pairing token?', 'The Obsidian plugin stops working until you give it the new token.', [
      { text: 'Cancel', style: 'cancel' },
      {
        text: 'New token',
        style: 'destructive',
        onPress: () => {
          setCopied(false);
          regenerateBridgeToken().then(setBridge, () => undefined);
        },
      },
    ]);
```

JSX, inserted before the Storage title:

```tsx
      <Text style={[ui.row, ui.title]}>Obsidian bridge</Text>
      <Text style={[ui.row, ui.small]}>
        Lets the Local TTS Reader plugin in Obsidian on this phone use this phone's voices.
      </Text>
      {bridge === null ? null : (
        <View collapsable={false} style={ui.row}>
          <Text>{bridgeStatus(bridge)}</Text>
          <Pressable
            style={ui.button}
            accessibilityLabel={bridge.enabled ? 'bridge off' : 'bridge on'}
            onPress={() => {
              setBridgeEnabled(!bridge.enabled).then(setBridge, () => undefined);
            }}>
            <Text style={ui.buttonText}>{bridge.enabled ? 'Turn off' : 'Turn on'}</Text>
          </Pressable>
          <View collapsable={false} style={ui.actions}>
            <Text style={ui.small}>Port</Text>
            <TextInput
              accessibilityLabel="bridge port"
              keyboardType="number-pad"
              value={portText}
              onChangeText={setPortText}
              onEndEditing={applyPort}
              onSubmitEditing={applyPort}
            />
          </View>
          {portError ? <Text style={ui.small}>The port must be a number from 1024 to 65535.</Text> : null}
          {bridge.enabled && bridge.token !== null ? (
            <View collapsable={false}>
              <Text style={ui.small}>Pairing token (paste it into the plugin's settings)</Text>
              <Text selectable accessibilityLabel="pairing token">
                {bridge.token}
              </Text>
              <View collapsable={false} style={ui.actions}>
                <Pressable
                  style={ui.button}
                  accessibilityLabel="copy token"
                  onPress={() => {
                    copyBridgeToken().then(() => setCopied(true), () => undefined);
                  }}>
                  <Text style={ui.buttonText}>{copied ? 'Copied' : 'Copy'}</Text>
                </Pressable>
                <Pressable style={ui.button} accessibilityLabel="new token" onPress={newToken}>
                  <Text style={ui.buttonText}>New token</Text>
                </Pressable>
              </View>
            </View>
          ) : null}
        </View>
      )}
```

The token text carries `accessibilityLabel="pairing token"` so TalkBack announces what it is rather than reading 32 hex characters, and so device scripts can find the node.

- [ ] **Step 9: Run the JS gates**

Run: `npx jest && npm run typecheck && npm run lint`
Expected: all pass (the existing Settings test still finds the voice, rate and storage strings).

- [ ] **Step 10: Build to regenerate codegen and compile the module**

Run: `npm run build:release`
Expected: `built <hash> (clean)` once committed; before committing it may say `(dirty)`, which is fine for this check. A codegen or Kotlin compile error here means the spec and the module disagree.

- [ ] **Step 11: Commit**

```bash
git add android/app/src/main/java/io/loopstring/readme/bridge/BridgeView.kt android/app/src/main/java/io/loopstring/readme/ReadMeSpeechModule.kt android/app/src/test/java/io/loopstring/readme/bridge/BridgeViewTest.kt src __tests__
git commit -m "feat(bridge): Settings turns the bridge on and off, sets its port and shows the token

Copy marks the clip sensitive so Android 13+ hides it from the clipboard preview.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: On the phone: device:bridge, Obsidian over CDP, docs

**Files:**
- Create: `scripts/device-bridge-e2e.sh`, `scripts/obsidian-cdp-bridge.mjs`
- Modify: `package.json` (script `device:bridge`)
- Modify: `AGENTS.md` (gates, Known state), `CONTEXT.md`, `docs/superpowers/plans/2026-10-01-roadmap.md`

**Interfaces:**
- Consumes: everything above, on a release build.
- Produces: `npm run device:bridge`; Known state entry with the build.

- [ ] **Step 1: Write the CDP probe** (`scripts/obsidian-cdp-bridge.mjs`; dev tooling, not app code; adapted from the SPIKE-01 probe on `spike/rea-0-background-bridge`)

```js
// R-M12 from INSIDE Obsidian's WebView (origin http://localhost), the way the plugin will call
// the bridge: fetch and CapacitorHttp. Driven over CDP as note-reader-local/AGENTS.md records.
// The token arrives on stdin, never in argv or a URL. Prints one line: OBSIDIAN {json}.
// Usage: printf '%s' "$TOK" | node scripts/obsidian-cdp-bridge.mjs <idle|playing>
import {execFileSync} from 'node:child_process';
import {readFileSync} from 'node:fs';

const mode = process.argv[2];
if (mode !== 'idle' && mode !== 'playing') throw new Error('usage: obsidian-cdp-bridge.mjs <idle|playing>');
const token = readFileSync(0, 'utf8').trim();
if (!/^[0-9a-f]{32}$/.test(token)) throw new Error('expected a 32-hex token on stdin');
const adb = (...a) => execFileSync('adb', a, {encoding: 'utf8'}).trim();
const pid = adb('shell', 'pidof', 'md.obsidian');
if (!pid) throw new Error('Obsidian is not running');
adb('forward', 'tcp:9333', `localabstract:webview_devtools_remote_${pid}`);
try {
  const pages = await (await fetch('http://127.0.0.1:9333/json', {signal: AbortSignal.timeout(10_000)})).json();
  const page = pages.find(p => p.type === 'page' && p.webSocketDebuggerUrl);
  if (!page) throw new Error('no debuggable Obsidian page');
  const expression = `(async () => {
    const out = {};
    const base = 'http://127.0.0.1:8787';
    const auth = {Authorization: 'Bearer ${token}', 'Content-Type': 'text/plain'};
    const big = 'a'.repeat(65536);
    const tryIt = async (k, f) => { try { out[k] = await f(); } catch (e) { out[k] = 'ERR ' + String(e); } };
    await tryIt('health', async () => {
      const r = await fetch(base + '/health');
      const j = await r.json();
      return r.status + ' busy=' + j.busy;
    });
    if (${JSON.stringify(mode)} === 'idle') {
      await tryIt('capSynth', async () => {
        const r = await Capacitor.Plugins.CapacitorHttp.post({url: base + '/synthesize?rate=1.0',
          headers: auth, data: 'The bridge through CapacitorHttp.', responseType: 'blob'});
        return r.status + ' rate=' + (r.headers['X-Rate'] ?? r.headers['x-rate']);
      });
      await tryIt('fetchSynth', async () => {
        const r = await fetch(base + '/synthesize?rate=1.0', {method: 'POST', headers: auth, body: 'The bridge through fetch.'});
        const b = await r.arrayBuffer();
        return r.status + ' rate=' + r.headers.get('X-Rate') + ' ms=' + r.headers.get('X-Synth-Ms') + ' bytes=' + b.byteLength;
      });
      // ADR 0004 "not yet measured": a 64 KiB unauthenticated POST must read as 401, not a network error.
      await tryIt('bigNoToken', async () => (await fetch(base + '/synthesize', {method: 'POST', body: big})).status);
    } else {
      // ADR 0004: a 64 KiB POST while Read Me plays must read as 503 busy.
      await tryIt('bigBusy', async () => {
        const r = await fetch(base + '/synthesize', {method: 'POST', headers: auth, body: big});
        return r.status + ' ' + (await r.text());
      });
    }
    return JSON.stringify(out);
  })()`;
  const ws = new WebSocket(page.webSocketDebuggerUrl);
  await new Promise((resolve, reject) => { ws.onopen = resolve; ws.onerror = reject; });
  let timer;
  const reply = await Promise.race([
    new Promise(resolve => {
      ws.onmessage = m => { const d = JSON.parse(m.data); if (d.id === 1) resolve(d); };
      ws.send(JSON.stringify({id: 1, method: 'Runtime.evaluate', params: {expression, awaitPromise: true, returnByValue: true}}));
    }),
    new Promise((_, reject) => { timer = setTimeout(() => reject(new Error('CDP evaluate timed out after 90 s')), 90_000); }),
  ]).finally(() => clearTimeout(timer));
  ws.close();
  console.log('OBSIDIAN', reply.result?.result?.value ?? JSON.stringify(reply.result?.exceptionDetails ?? reply));
} finally {
  adb('forward', '--remove', 'tcp:9333');
}
```

- [ ] **Step 2: Write the device script** (`scripts/device-bridge-e2e.sh`, `chmod +x`; add `"device:bridge": "bash scripts/device-bridge-e2e.sh"` to `package.json` scripts after `device:ui`)

```bash
#!/usr/bin/env bash
# npm run device:bridge - R-M12 on the phone, as the plugin would use it. Clears Read Me's data,
# turns the bridge on in Settings, reads the pairing token off the screen (never through adb
# shell argv or a file on shared storage), then over adb forward: /health, /synthesize at 1.0
# and 2.0 (the 2.0 WAV must be shorter: the engine applied the rate once), 401 without the
# token, the hostile-input set, a silent client, 503 while Read Me plays and the bridge after
# playback ends. With Obsidian installed, calls the bridge from inside its WebView with Read Me
# in the background. Turns the bridge off and checks it is gone. Checks logcat for the token
# and the test text.
set -euo pipefail
cd "$(dirname "$0")/.."
. scripts/lib/device.sh
PORT=8787
HDR=$(mktemp)          # the Authorization header lives in a 0600 file, not in curl's argv
chmod 600 "$HDR"
cleanup() {
  rm -f "$HDR"
  adb forward --remove tcp:$PORT >/dev/null 2>&1 || true
  adb shell rm -f /sdcard/readme-ui.xml >/dev/null 2>&1 || true
}
DEVICE_ON_EXIT=cleanup
device_take interactive
device_require_unlocked
device_install_release

adb shell pm clear "$PKG" >/dev/null
adb logcat -c
fail=0
check() { if eval "$2"; then echo "ok: $1"; else echo "FAIL: $1"; fail=1; fi; }

# The screen as XML on stdout, without a file on /sdcard (the token is on screen).
ui() { adb exec-out uiautomator dump /dev/tty 2>/dev/null | sed 's/UI hierchary dumped to.*//' || true; }
tap_desc() {
  local b=""
  for _ in $(seq 10); do
    b=$(ui | grep -oE "content-desc=\"$1\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" | head -1 \
      | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' | grep -oE '[0-9]+' | tr '\n' ' ' || true)
    [ -n "$b" ] && break
    sleep 1
  done
  [ -n "$b" ] || { echo "FAIL: nothing on screen has content-desc $1"; exit 1; }
  set -- $b
  adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
}
on_screen() { for _ in $(seq 15); do ui | grep -qE "$1" && return 0; sleep 1; done; return 1; }
logs() { adb logcat -d -s ReadMe:I; }
wait_log() { for _ in $(seq "$2"); do logs | grep -qE "$1" && return 0; sleep 1; done; return 1; }

# A ten-sentence text to play during the contention check.
TEXT=""
for w in one two three four five six seven eight nine ten; do TEXT+="Bridge test $w is spoken now. "; done
printf '%s\n' "am start -W -n $PKG/.ShareActivity -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT $(printf '%q' "$TEXT")" \
  | adb shell >/dev/null
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null

echo "== Settings: turn the bridge on"
tap_desc settings
tap_desc 'bridge on'
check "Settings says the bridge is on" "on_screen 'On at 127\.0\.0\.1:$PORT'"
TOK=$(ui | grep -oE 'text="[0-9a-f]{32}"' | head -1 | grep -oE '[0-9a-f]{32}' || true)
[ -n "$TOK" ] || { echo "FAIL: no pairing token on screen"; exit 1; }
printf 'Authorization: Bearer %s\n' "$TOK" > "$HDR"
check "the notification says the bridge is on" \
  "adb shell dumpsys notification --noredact | grep -q 'Obsidian bridge on'"

adb forward tcp:$PORT tcp:$PORT >/dev/null
B="http://127.0.0.1:$PORT"
code() { curl -s -o /dev/null -w '%{http_code}' --max-time 30 "$@"; }

echo "== contract"
check "/health 200 ok, version 1, not busy" \
  "curl -s --max-time 10 $B/health | grep -q '\"ok\":true,\"version\":1,.*\"busy\":false'"
W1=$(mktemp); W2=$(mktemp)
H1=$(printf 'Hello from the bridge test at rate one.' | curl -s -D - -o "$W1" --max-time 60 -X POST -H @"$HDR" \
  -H 'Content-Type: text/plain' -H 'Origin: http://localhost' --data-binary @- "$B/synthesize")
check "/synthesize 200 audio/wav, X-Rate 1.0, CORS" \
  "grep -q '^HTTP/1.1 200' <<<\"\$H1\" && grep -qi '^content-type: audio/wav' <<<\"\$H1\" && grep -qi '^x-rate: 1.0' <<<\"\$H1\" && grep -qi '^access-control-allow-origin: http://localhost' <<<\"\$H1\""
check "the body is a RIFF WAV" "head -c 4 '$W1' | grep -q RIFF"
H2=$(printf 'Hello from the bridge test at rate one.' | curl -s -D - -o "$W2" --max-time 60 -X POST -H @"$HDR" \
  -H 'Content-Type: text/plain' --data-binary @- "$B/synthesize?rate=2.0")
check "rate=2.0 reports X-Rate 2.0" "grep -qi '^x-rate: 2.0' <<<\"\$H2\""
dur() { python3 -c 'import sys,wave; w=wave.open(sys.argv[1]); print(w.getnframes()/w.getframerate())' "$1"; }
D1=$(dur "$W1"); D2=$(dur "$W2")
echo "durations: rate 1.0 ${D1}s, rate 2.0 ${D2}s"
check "the engine applied 2.0 once (duration ratio 0.35 to 0.75)" \
  "python3 -c 'import sys; r=float(sys.argv[2])/float(sys.argv[1]); sys.exit(0 if 0.35<=r<=0.75 else 1)' $D1 $D2"
rm -f "$W1" "$W2"

echo "== hostile input"
check "no token: 401" "[ \"\$(printf x | code -X POST --data-binary @- $B/synthesize)\" = 401 ]"
check "no token with a 64 KiB body: 401, readable" \
  "[ \"\$(head -c 65536 /dev/zero | tr '\\0' a | code -X POST --data-binary @- $B/synthesize)\" = 401 ]"
check "Content-Length abc: 400" "[ \"\$(code -X POST -H @$HDR -H 'Content-Length: abc' $B/synthesize)\" = 400 ]"
check "body over 64 KiB: 413" \
  "[ \"\$(head -c 65537 /dev/zero | tr '\\0' a | code -X POST -H @$HDR --data-binary @- $B/synthesize)\" = 413 ]"
check "a 20 KB header: 431" "[ \"\$(code -H \"X-Pad: \$(head -c 20000 /dev/zero | tr '\\0' a)\" $B/health)\" = 431 ]"
check "other origin: no CORS" "! curl -s -D - -o /dev/null -H 'Origin: http://evil.example' $B/health | grep -qi access-control"
exec 3<>/dev/tcp/127.0.0.1/$PORT   # a silent client
check "a silent client does not block /health" "[ \"\$(code --max-time 3 $B/health)\" = 200 ]"
exec 3<&- 3>&-

echo "== contention (ADR 0004)"
adb shell input keyevent KEYCODE_BACK
tap_desc 'open [^"]*'
tap_desc 'trim done'
tap_desc play
wait_log 'playback start item=1 ' 20 || { echo "FAIL: playback did not start"; fail=1; }
sleep 2
R=$(printf 'Busy test.' | curl -s -w ' %{http_code}' --max-time 10 -X POST -H @"$HDR" --data-binary @- "$B/synthesize")
check "/synthesize while playing: 503 busy playback" "[ \"\$R\" = '{\"error\":\"busy\",\"reason\":\"playback\"} 503' ]"
check "/health says busy" "curl -s $B/health | grep -q '\"busy\":true'"

if adb shell pm path md.obsidian >/dev/null 2>&1; then
  echo "== Obsidian, Read Me in the background, while it plays"
  adb shell am start -n md.obsidian/.MainActivity >/dev/null; sleep 8
  P=$(printf '%s' "$TOK" | node scripts/obsidian-cdp-bridge.mjs playing); echo "$P"
  check "WebView: 64 KiB POST while playing reads 503 busy" "grep -q '\"bigBusy\":\"503 ' <<<\"\$P\""
fi

echo "== playback ends; the bridge stays"
wait_log 'playback finished item=1' 90 || { echo "FAIL: playback did not finish"; fail=1; }
sleep 2
check "/health 200 and not busy after playback" "curl -s --max-time 5 $B/health | grep -q '\"busy\":false'"
check "the notification still says the bridge is on" \
  "adb shell dumpsys notification --noredact | grep -q 'Obsidian bridge on'"

if adb shell pm path md.obsidian >/dev/null 2>&1; then
  echo "== Obsidian, Read Me in the background, idle"
  adb shell am start -n md.obsidian/.MainActivity >/dev/null; sleep 5
  O=$(printf '%s' "$TOK" | node scripts/obsidian-cdp-bridge.mjs idle); echo "$O"
  check "WebView fetch /health 200" "grep -q '\"health\":\"200 busy=false\"' <<<\"\$O\""
  check "WebView CapacitorHttp /synthesize 200 at 1.0" "grep -q '\"capSynth\":\"200 rate=1.0\"' <<<\"\$O\""
  check "WebView fetch /synthesize 200 at 1.0" "grep -q '\"fetchSynth\":\"200 rate=1.0' <<<\"\$O\""
  check "WebView 64 KiB POST without token reads 401" "grep -q '\"bigNoToken\":401' <<<\"\$O\""
  OBSIDIAN=ran
else
  echo "SKIP: Obsidian (md.obsidian) is not installed; the WebView half did not run"
  OBSIDIAN=skipped
fi

echo "== Settings: turn the bridge off"
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
# The Reader is still open: back to the List, then Settings.
adb shell input keyevent KEYCODE_BACK
tap_desc settings
tap_desc 'bridge off'
sleep 2
check "the bridge is gone" "[ \"\$(code --max-time 3 $B/health)\" = 000 ]"

echo "== privacy"
check "the token is not in logcat" "[ \"\$(adb logcat -d | grep -c \"$TOK\" || true)\" = 0 ]"
check "the test text is not in logcat" "! adb logcat -d | grep -q 'Bridge test\\|Hello from the bridge'"
check "no crash" "! adb logcat -d | device_crash_seen"
logs | grep -E 'bridge ' | sed -E 's/.*(bridge .*)/\1/' | sort | uniq -c | sort -rn | head -20

if [ "$fail" = 0 ]; then echo "device:bridge PASS (Obsidian: $OBSIDIAN)"; else echo "device:bridge FAIL"; exit 1; fi
```

Verify on the phone, before trusting it, that `adb exec-out uiautomator dump /dev/tty` prints the XML (rule 18). If it does not, the fallback is `/sdcard/readme-ui.xml` removed immediately after each read; say so in the ledger, because the token then touches shared storage for a moment. uiautomator's XML is one line, so patterns use `[^"]*`, never `.*`. The List's open action is labelled `open <title>` (`ListScreen.tsx`: `${a} ${item.title}`).

- [ ] **Step 3: Run it on the phone**

Run: `npm run -s device:bridge 2>&1 | tail -60`
Expected: every check `ok`, ending `device:bridge PASS (Obsidian: ran)`. If the phone is locked the script exits 5: ask the owner to unlock it. A failing check is a bug to reproduce and fix (systematic-debugging), never a check to soften.

- [ ] **Step 4: Regressions on the phone** (the service lifecycle changed)

Run: `npm run -s device:playback 2>&1 | tail -15` and then `npm run -s device:ui 2>&1 | tail -8`
Expected: `device:playback PASS` and `device:ui PASS`.

- [ ] **Step 5: Docs**

`AGENTS.md` "Quality gates", after the `device:ui` line:

```
npm run device:bridge     # R-M12: turns the bridge on in Settings, then contract, hostile input, 503 while playing, Obsidian's WebView over CDP when installed, bridge off; logcat has no token (clears app data)
```

`AGENTS.md` "Known state", a new bullet after the UI (Phase 4) one, filled with the real build hash and the real numbers from Step 3 (rule 17):

```
- **Bridge (Phase 5, REA-20):** `BridgeServer` (Kotlin, plain JVM) on explicit 127.0.0.1:8787
  (configurable), hosted by PlaybackService while Settings has it on; contract v1 plus ADR 0008
  (one synthesis at most `maxChars`). Verified 2026-10-02 on the reference device, build <hash>:
  `npm run device:bridge` (rate 2.0 WAV <D2> s against <D1> s at 1.0; 401/400/413/431 and a
  silent client; 503 busy while playing; Obsidian's WebView fetch and CapacitorHttp with Read Me
  in the background, 64 KiB 401 and 503 readable; token absent from logcat), then
  `npm run device:playback` and `npm run device:ui`. After a reboot or process death the bridge
  returns when Read Me is next opened (no boot receiver). Not established: Android 14-16, the
  real plugin (NRL-130).
```

Also in `AGENTS.md`: the Playback bullet's "No reader, trim screen or bridge yet." becomes "No bridge until Phase 5 (below)."

`CONTEXT.md`: replace "Planned: BridgeServer" with the `bridge/` package (`BridgeServer`, `Http`, `TtsSynth`, `BridgeFiles`, `BridgeView`) and one line each; add `device:bridge` and `obsidian-cdp-bridge.mjs` to the scripts list; "Settings: the rate and voice" becomes "Settings: the rate, voice and bridge (on/off, port, token)".

`docs/superpowers/plans/2026-10-01-roadmap.md`: Phase 4 row becomes `**Done (2026-10-02, REA-19, PR #12).** List, Trim, Reader, Settings, every R-M10 state, Licenses (R-M13 partial until Phase 6; REA-21 follow-ups).`; Phase 5 row's Plan cell becomes `2026-10-02-phase-5-bridge.md`.

- [ ] **Step 6: Commit**

```bash
git add scripts/device-bridge-e2e.sh scripts/obsidian-cdp-bridge.mjs package.json AGENTS.md CONTEXT.md docs/superpowers/plans/2026-10-01-roadmap.md
git commit -m "test(bridge): device:bridge drives R-M12 on the phone, Obsidian's WebView included

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

- [ ] **Step 7: Full gates**

Run, in order: `npm run typecheck && npm run lint && npx jest && npm run test:scripts && node scripts/check-licenses.mjs && (cd android && ./gradlew testDebugUnitTest -q) && npm run build:release && scripts/fdroid-scan.sh`
Expected: each passes; `build:release` prints `built <hash> (clean)`; `fdroid-scan.sh` ends CLEAN. No dependency was added, so the resolved tree is unchanged.
