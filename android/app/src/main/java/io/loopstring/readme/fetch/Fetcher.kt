package io.loopstring.readme.fetch

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import okio.Buffer

sealed class FetchResult {
  data class Ok(val html: String) : FetchResult()

  /** `reason` is a class (R-M10), never built from the URL. */
  data class Failed(val reason: String) : FetchResult()
}

/**
 * R-M03, AGENTS.md 6-7: the only outbound network call site in the app. One user request is
 * one GET of the shared URL plus at most 5 manually followed http(s) redirects, inside one
 * 20 s deadline. No cookies, no cache, and a 5 MB cap enforced while streaming. Nothing here
 * logs; the caller logs ids and reason classes only.
 */
class Fetcher(
  private val timeoutMs: Long = 20_000,
  private val capBytes: Long = CAP_BYTES,
) {
  private val client = OkHttpClient.Builder()
    .cookieJar(CookieJar.NO_COOKIES)
    .cache(null)
    .followRedirects(false)
    .followSslRedirects(false)
    // The per-call deadline below is the only time limit; OkHttp's 10 s connect and read
    // defaults would fail a server R-M03 allows 20 s.
    .connectTimeout(0, TimeUnit.MILLISECONDS)
    .readTimeout(0, TimeUnit.MILLISECONDS)
    .writeTimeout(0, TimeUnit.MILLISECONDS)
    .build()

  val usesCache: Boolean get() = client.cache != null || client.cookieJar != CookieJar.NO_COOKIES

  fun fetch(url: String): FetchResult {
    var target: HttpUrl = url.toHttpUrlOrNull() ?: return FetchResult.Failed("bad-url")
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
    var redirects = 0
    while (true) {
      val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
      if (remainingMs <= 0) return FetchResult.Failed("timeout")
      val call = client.newCall(
        Request.Builder()
          .url(target)
          .header("User-Agent", USER_AGENT)
          .header("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.5")
          .get()
          .build(),
      )
      // One deadline across all hops (R-M03: a call timeout, not per read).
      call.timeout().timeout(remainingMs, TimeUnit.MILLISECONDS)
      try {
        val response = call.execute()
        try {
          if (response.isRedirect) {
            val location = response.header("Location")
              ?: return FetchResult.Failed("http-${response.code}")
            // HttpUrl only resolves http and https; anything else comes back null.
            val next = response.request.url.resolve(location)
              ?: return FetchResult.Failed("unsupported-redirect")
            redirects++
            if (redirects > MAX_REDIRECTS) return FetchResult.Failed("too-many-redirects")
            target = next
            continue
          }
          if (!response.isSuccessful) return FetchResult.Failed("http-${response.code}")
          val body = response.body ?: return FetchResult.Failed("network")
          return readCapped(body)?.let { FetchResult.Ok(it) } ?: FetchResult.Failed("too-large")
        } finally {
          response.close()
        }
      } catch (e: InterruptedIOException) {
        return FetchResult.Failed("timeout")
      } catch (e: UnknownHostException) {
        return FetchResult.Failed("offline")
      } catch (e: ConnectException) {
        return FetchResult.Failed("offline")
      } catch (e: NoRouteToHostException) {
        return FetchResult.Failed("offline")
      } catch (e: IOException) {
        return FetchResult.Failed("network")
      }
    }
  }

  /** Reads at most capBytes + 1 bytes; null when the body is larger than the cap. */
  private fun readCapped(body: ResponseBody): String? {
    val source = body.source()
    val buffer = Buffer()
    while (buffer.size <= capBytes) {
      val want = minOf(CHUNK, capBytes + 1 - buffer.size)
      if (source.read(buffer, want) == -1L) break
    }
    if (buffer.size > capBytes) return null
    val charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
    return buffer.readString(charset)
  }

  companion object {
    const val CAP_BYTES = 5L * 1024 * 1024
    const val MAX_REDIRECTS = 5
    private const val CHUNK = 64L * 1024
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android) ReadMe/1.0"
  }
}
