package io.loopstring.readme

import android.content.Intent
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicLong

/**
 * REA-63 (ADR 0016): a share that matched an unread item starts MainActivity with that item's
 * id (never its URL, AGENTS.md 1). MainActivity leaves the id here; JS takes it once, through
 * ReadMeSpeech, on mount, on return to the foreground, or on the "ReadMeSharedOpen" event when it
 * is already in front. One slot: a newer share replaces an unread older one.
 */
object SharedOpen {
  const val EXTRA_ITEM = "io.loopstring.readme.OPEN_ITEM"
  private const val NONE = -1L

  private val pending = AtomicLong(NONE)
  private val listeners = CopyOnWriteArraySet<() -> Unit>()

  /**
   * From MainActivity's onCreate and onNewIntent. A recreated activity ([restored]) or one
   * launched from recents still carries the intent it was started with; neither is a new share,
   * so neither replays the open. The extra is removed once read for the same reason.
   */
  fun fromIntent(intent: Intent?, restored: Boolean) {
    if (intent == null || restored) return
    if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
    if (!intent.hasExtra(EXTRA_ITEM)) return
    val id = intent.getLongExtra(EXTRA_ITEM, NONE)
    intent.removeExtra(EXTRA_ITEM)
    if (id > 0) offer(id)
  }

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
