package io.loopstring.readme.playback

import io.loopstring.readme.store.ItemRow
import io.loopstring.readme.store.Store

/**
 * R-S05: which item continuous play reads after [Store.unreadAfter]'s first candidate that has
 * at least one kept sentence, from its saved position (remapped) or its start. Reads only: an
 * item read this way keeps opened_at NULL and gets no cuts (ADR 0011), so the user's own first
 * open still shows Trim (R-M05).
 */
object ContinuousPlay {
  class Next(val item: ItemRow, val next: NextItem)

  fun next(store: Store, afterId: Long, skip: Set<Long>): Next? {
    for (id in store.unreadAfter(afterId)) {
      if (id in skip) continue
      val item = store.item(id) ?: continue // deleted since the query
      val plan = Segmenter.plan(store.paragraphs(id).map { it.text }, store.cuts(id).toSet(), store.position(id))
        ?: continue // nothing kept: nothing to read
      return Next(item, NextItem(id, plan.sentences, plan.startIndex))
    }
    return null
  }
}
