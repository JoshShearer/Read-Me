package io.loopstring.readme.playback

import androidx.annotation.VisibleForTesting
import io.loopstring.readme.store.Rate
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicReference

/** R-M12: what Settings shows. `error` is an exception class name, never a message. */
data class BridgeStatus(val state: String, val port: Int, val error: String?) {
  companion object {
    val OFF = BridgeStatus("off", 0, null)
  }
}

/**
 * In-process meeting point between ReadMeSpeech (JS), PlaybackService and the bridge. The
 * sentence list travels here, not in an Intent: a long article's list would pass the 1 MB
 * Binder transaction limit. `speaking` is ADR 0004's flag, which the bridge reads; the service
 * publishes `bridge` for Settings.
 */
object PlaybackHub {
  data class Request(val itemId: Long, val title: String, val sentences: List<SentenceRow>, val startIndex: Int)

  private val pending = AtomicReference<Request?>(null)
  private val listeners = CopyOnWriteArraySet<(PlaybackSnapshot) -> Unit>()
  private val idle = PlaybackSnapshot(null, false, null, Rate.DEFAULT)

  @Volatile var last: PlaybackSnapshot = idle
    private set
  @Volatile var speaking = false
    private set
  @Volatile var engine = "unknown"
  @Volatile var queue: PlaybackQueue? = null
  @Volatile var controller: ((String) -> Boolean)? = null

  fun offer(r: Request) = pending.set(r)
  fun take(): Request? = pending.getAndSet(null)
  fun hasPending() = pending.get() != null

  /** The item is being deleted: drop a request for it the service has not taken yet. */
  fun stopItem(itemId: Long) {
    pending.getAndUpdate { if (it?.itemId == itemId) null else it }
    queue?.stopItem(itemId)
  }

  fun publish(s: PlaybackSnapshot) {
    last = s
    speaking = s.playing
    for (l in listeners) l(s)
  }

  fun addListener(l: (PlaybackSnapshot) -> Unit) { listeners.add(l) }
  fun removeListener(l: (PlaybackSnapshot) -> Unit) { listeners.remove(l) }

  private val bridgeListeners = CopyOnWriteArraySet<(BridgeStatus) -> Unit>()
  @Volatile var bridge: BridgeStatus = BridgeStatus.OFF
    private set

  fun publishBridge(b: BridgeStatus) {
    bridge = b
    for (l in bridgeListeners) l(b)
  }

  fun addBridgeListener(l: (BridgeStatus) -> Unit) { bridgeListeners.add(l) }
  fun removeBridgeListener(l: (BridgeStatus) -> Unit) { bridgeListeners.remove(l) }

  fun control(action: String): Boolean = controller?.invoke(action) ?: false

  @VisibleForTesting
  fun resetForTest() {
    pending.set(null)
    listeners.clear()
    last = idle
    speaking = false
    engine = "unknown"
    queue = null
    controller = null
    bridge = BridgeStatus.OFF
    bridgeListeners.clear()
  }
}
