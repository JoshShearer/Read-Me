package io.loopstring.readme

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.WritableMap
import io.loopstring.readme.fetch.FetchWorker
import io.loopstring.readme.playback.EngineProbe
import io.loopstring.readme.playback.PlaybackCommands
import io.loopstring.readme.playback.PlaybackHub
import io.loopstring.readme.playback.PlaybackService
import io.loopstring.readme.playback.PlaybackSnapshot
import io.loopstring.readme.playback.SentenceRow
import io.loopstring.readme.spec.NativeReadMeSpeechSpec
import io.loopstring.readme.store.ItemEvents
import io.loopstring.readme.store.ItemRow
import io.loopstring.readme.store.ItemStats
import io.loopstring.readme.store.ParagraphRow
import io.loopstring.readme.store.Rate
import io.loopstring.readme.store.Settings
import io.loopstring.readme.store.Store

/**
 * ADR 0001: the only way JS reaches the library. A view, not a driver: every write lands in
 * the Store, which fires ItemEvents; this module forwards that as "ReadMeItemsChanged" while
 * JS is alive. A failure rejects with the exception's class only (AGENTS.md 1).
 */
class ReadMeSpeechModule(ctx: ReactApplicationContext) : NativeReadMeSpeechSpec(ctx) {
  private val store get() = Store.get(reactApplicationContext)
  private val onChange: () -> Unit = { reactApplicationContext.emitDeviceEvent(EVENT_CHANGED) }
  private val settings get() = Settings(reactApplicationContext)
  private val onPlayback: (PlaybackSnapshot) -> Unit = {
    reactApplicationContext.emitDeviceEvent(EVENT_PLAYBACK, it.toMap())
  }

  override fun getName(): String = NAME

  override fun initialize() {
    super.initialize()
    ItemEvents.add(onChange)
    PlaybackHub.addListener(onPlayback)
  }

  override fun invalidate() {
    ItemEvents.remove(onChange)
    PlaybackHub.removeListener(onPlayback)
    super.invalidate()
  }

  override fun listItems(promise: Promise) = settle(promise) {
    val stats = store.stats()
    Arguments.createArray().apply { store.items().forEach { pushMap(it.toMap(stats[it.id])) } }
  }

  override fun getItem(id: Double, promise: Promise) = settle(promise) {
    val item = store.item(id.toLong()) ?: return@settle null
    Arguments.createMap().apply {
      putMap("item", item.toMap(store.stats(only = item.id)[item.id]))
      putArray(
        "paragraphs",
        Arguments.createArray().apply {
          store.paragraphs(item.id).forEach { p ->
            pushMap(Arguments.createMap().apply {
              putString("kind", p.kind)
              putString("text", p.text)
            })
          }
        },
      )
      putArray(
        "cuts",
        Arguments.createArray().apply { store.cuts(item.id).forEach { pushInt(it) } },
      )
    }
  }

  override fun getBody(id: Double, promise: Promise) = settle(promise) { store.bodyOrFail(id.toLong()) }

  override fun completeExtraction(
    id: Double,
    title: String,
    site: String?,
    byline: String?,
    paragraphs: ReadableArray,
    poor: Boolean,
    promise: Promise,
  ) = settle(promise) {
    val rows = (0 until paragraphs.size()).map { i ->
      val p = paragraphs.getMap(i)!!
      ParagraphRow(p.getString("kind")!!, p.getString("text")!!)
    }
    store.completeExtraction(id.toLong(), title, site, byline, rows, poor)
  }

  override fun retryFetch(id: Double, promise: Promise) = settle(promise) {
    val started = store.beginRetry(id.toLong())
    if (started) FetchWorker.enqueue(reactApplicationContext, id.toLong())
    started
  }

  // Review Focus 2: stop first, so no onDone saves into a row that is going away.
  override fun deleteItem(id: Double, promise: Promise) = settle(promise) {
    PlaybackHub.stopItem(id.toLong())
    store.delete(id.toLong())
    null
  }

  override fun markOpened(id: Double, promise: Promise) =
    settle(promise) { store.markOpened(id.toLong(), System.currentTimeMillis()); null }

  override fun setCut(id: Double, paragraphIndex: Double, cut: Boolean, promise: Promise) =
    settle(promise) { store.setCut(id.toLong(), paragraphIndex.toInt(), cut); null }

  override fun archiveItem(id: Double, promise: Promise) =
    settle(promise) { store.archive(id.toLong(), System.currentTimeMillis()); null }

  override fun restoreItem(id: Double, promise: Promise) = settle(promise) { store.restore(id.toLong()); null }

  override fun play(itemId: Double, title: String, sentences: ReadableArray, startIndex: Double, promise: Promise) =
    settle(promise) {
      val rows = (0 until sentences.size()).map { i ->
        val s = sentences.getMap(i)!!
        SentenceRow(s.getInt("paragraphIndex"), s.getInt("start"), s.getInt("end"), s.getString("text")!!)
      }
      val start = startIndex.toInt()
      if (start !in rows.indices) return@settle false
      PlaybackService.start(reactApplicationContext, PlaybackHub.Request(itemId.toLong(), title, rows, start))
      true
    }

  override fun pause(promise: Promise) = settle(promise) { PlaybackHub.control(PlaybackCommands.ACTION_PAUSE) }

  override fun resume(promise: Promise) = settle(promise) { PlaybackHub.control(PlaybackCommands.ACTION_PLAY) }

  override fun next(promise: Promise) = settle(promise) { PlaybackHub.control(PlaybackCommands.ACTION_NEXT) }

  override fun previous(promise: Promise) = settle(promise) { PlaybackHub.control(PlaybackCommands.ACTION_PREVIOUS) }

  override fun backParagraph(promise: Promise) =
    settle(promise) { PlaybackHub.control(PlaybackCommands.ACTION_BACK_PARAGRAPH) }

  override fun setRate(rate: Double, promise: Promise) = settle(promise) {
    val r = Rate.clamp(rate.toFloat())
    settings.rate = r
    PlaybackHub.queue?.setRate(r)
    Math.round(r * 10) / 10.0
  }

  override fun getRate(promise: Promise) = settle(promise) { Math.round(settings.rate * 10) / 10.0 }

  override fun getPlayback(promise: Promise) = settle(promise) { PlaybackHub.last.toMap() }

  override fun getPosition(itemId: Double, promise: Promise) = settle(promise) {
    store.position(itemId.toLong())?.let { p ->
      Arguments.createMap().apply {
        putInt("paragraphIndex", p.paragraphIndex)
        putInt("charOffset", p.charOffset)
      }
    }
  }

  // Offsets and ids only; the sentence text stays native (JS already has it).
  private fun PlaybackSnapshot.toMap(): WritableMap = Arguments.createMap().apply {
    val id = itemId
    if (id == null) putNull("itemId") else putDouble("itemId", id.toDouble())
    putBoolean("playing", playing)
    putInt("paragraphIndex", sentence?.paragraphIndex ?: -1)
    putInt("start", sentence?.start ?: 0)
    putInt("end", sentence?.end ?: 0)
    putDouble("rate", Math.round(rate * 10) / 10.0)
    putString("engine", PlaybackHub.engine)
  }

  override fun setCuts(id: Double, indices: ReadableArray, promise: Promise) = settle(promise) {
    store.setCuts(id.toLong(), (0 until indices.size()).map { indices.getInt(it) })
    null
  }

  override fun stop(promise: Promise) = settle(promise) { PlaybackHub.control(PlaybackCommands.ACTION_STOP) }

  override fun getEngine(promise: Promise) {
    try {
      EngineProbe.start(reactApplicationContext, settings.voice) { r ->
        PlaybackHub.engine = r.status
        promise.resolve(Arguments.createMap().apply {
          putString("status", r.status)
          putArray("voices", Arguments.createArray().apply {
            r.voices.forEach { v ->
              pushMap(Arguments.createMap().apply {
                putString("name", v.name)
                putString("language", v.language)
                putInt("quality", v.quality)
              })
            }
          })
          putString("selected", r.selected)
        })
      }
    } catch (t: Throwable) {
      promise.reject("E_LIBRARY", "library operation failed: ${t.javaClass.simpleName}")
    }
  }

  override fun setVoice(name: String?, promise: Promise) = settle(promise) { settings.voice = name; null }

  // R-M13: generated by scripts/make-notices.mjs; read on demand, never held.
  override fun getNotices(promise: Promise) = settle(promise) {
    reactApplicationContext.assets.open("notices.json").bufferedReader().use { it.readText() }
  }

  // NativeEventEmitter's contract; the events go out through emitDeviceEvent.
  override fun addListener(eventName: String) {}

  override fun removeListeners(count: Double) {}

  private inline fun settle(promise: Promise, block: () -> Any?) {
    try {
      promise.resolve(block())
    } catch (t: Throwable) {
      promise.reject("E_LIBRARY", "library operation failed: ${t.javaClass.simpleName}")
    }
  }

  private fun ItemRow.toMap(stats: ItemStats?): WritableMap = Arguments.createMap().apply {
    putDouble("id", id.toDouble())
    putString("kind", kind)
    putString("url", url)
    putString("title", title)
    putString("site", site)
    putString("byline", byline)
    putDouble("createdAt", createdAt.toDouble())
    putString("state", state)
    putString("failReason", failReason)
    if (openedAt == null) putNull("openedAt") else putDouble("openedAt", openedAt.toDouble())
    if (archivedAt == null) putNull("archivedAt") else putDouble("archivedAt", archivedAt.toDouble())
    putInt("words", stats?.words ?: 0)
    val kept = stats?.keptChars ?: 0
    putDouble("progress", if (kept == 0) 0.0 else (stats!!.readChars.toDouble() / kept).coerceIn(0.0, 1.0))
  }

  companion object {
    const val NAME = "ReadMeSpeech"
    const val EVENT_CHANGED = "ReadMeItemsChanged"
    const val EVENT_PLAYBACK = "ReadMePlayback"
  }
}
