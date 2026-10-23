package io.loopstring.readme

import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicLong

/**
 * REA-63 (ADR 0016): the id of the unread item a share matched (never its URL, AGENTS.md 1).
 * ShareActivity, in this process, offers it here before it starts MainActivity; JS takes it once,
 * through ReadMeSpeech, on mount, on return to the foreground, or on the "ReadMeSharedOpen" event
 * when it is already in front. One slot: a newer share replaces an unread older one.
 *
 * Nothing reads an id from an intent: MainActivity is exported (the launcher), so any app could
 * send one, and an id taken from it would let that app open any item and mark it opened.
 */
object SharedOpen {
  private const val NONE = -1L

  private val pending = AtomicLong(NONE)
  private val listeners = CopyOnWriteArraySet<() -> Unit>()

  fun offer(id: Long) {
    pending.set(id)
    for (l in listeners) l()
  }

  /** The pending id, at most once. */
  fun take(): Long? = pending.getAndSet(NONE).takeIf { it != NONE }

  fun addListener(listener: () -> Unit) {
    listeners.add(listener)
  }

  fun removeListener(listener: () -> Unit) {
    listeners.remove(listener)
  }

  internal fun resetForTest() {
    pending.set(NONE)
    listeners.clear()
  }
}
