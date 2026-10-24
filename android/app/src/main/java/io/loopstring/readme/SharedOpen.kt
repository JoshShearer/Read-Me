package io.loopstring.readme

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicReference

/**
 * REA-63 (ADR 0016): the id of the unread item a share matched (never its URL, AGENTS.md 1).
 * ShareActivity, in this process, offers it here before it starts MainActivity; JS takes it once,
 * through ReadMeSpeech, on mount, on return to the foreground, or on the "ReadMeSharedOpen" event
 * when it is already in front. One slot: a newer share replaces an unread older one.
 *
 * REA-67: an id expires 30 s after its offer (the owner's bound; a start from a share takes well
 * under a second). If Android refused or dropped the MainActivity start, the id is dropped
 * rather than opening an item hours later over whatever the user was doing; the user opens it
 * from the List. Age is on elapsedRealtime (monotonic, counts deep sleep, unmoved by a wall-clock
 * change) and is checked in take(); there is no timer. Age under 30 000 ms keeps the id.
 *
 * Nothing reads an id from an intent: MainActivity is exported (the launcher), so any app could
 * send one, and an id taken from it would let that app open any item and mark it opened.
 */
object SharedOpen {
  internal const val EXPIRY_MS = 30_000L

  private class Pending(val id: Long, val offeredAt: Long)

  internal var now: () -> Long = SystemClock::elapsedRealtime

  private val pending = AtomicReference<Pending?>(null)
  private val listeners = CopyOnWriteArraySet<() -> Unit>()

  fun offer(id: Long) {
    pending.set(Pending(id, now()))
    for (l in listeners) l()
  }

  /** The pending id, at most once, and only while it is under [EXPIRY_MS] old. */
  fun take(): Long? {
    val p = pending.getAndSet(null) ?: return null
    val age = now() - p.offeredAt
    if (age >= EXPIRY_MS) {
      Log.i("ReadMe", "shared open expired item=${p.id} ageMs=$age")
      return null
    }
    return p.id
  }

  fun addListener(listener: () -> Unit) {
    listeners.add(listener)
  }

  fun removeListener(listener: () -> Unit) {
    listeners.remove(listener)
  }

  internal fun resetForTest() {
    pending.set(null)
    listeners.clear()
    now = SystemClock::elapsedRealtime
  }
}
