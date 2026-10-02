package io.loopstring.readme.store

import java.util.concurrent.CopyOnWriteArraySet

/**
 * Process-wide "the library changed" signal. The Store fires it after every write. The
 * ReadMeSpeech module forwards it to JS when JS is alive, and nothing depends on that.
 */
object ItemEvents {
  private val listeners = CopyOnWriteArraySet<() -> Unit>()

  fun add(listener: () -> Unit) {
    listeners.add(listener)
  }

  fun remove(listener: () -> Unit) {
    listeners.remove(listener)
  }

  fun changed() {
    for (l in listeners) l()
  }
}
