package io.loopstring.readme.playback

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.loopstring.readme.store.ParagraphRow
import io.loopstring.readme.store.Store
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** R-S05: the next item is the next row down in the List's unread view, chosen in Kotlin. */
@RunWith(RobolectricTestRunner::class)
class ContinuousPlayTest {
  private lateinit var store: Store

  @Before fun setUp() {
    store = Store.get(ApplicationProvider.getApplicationContext<Context>())
  }

  @After fun tearDown() = Store.resetForTest()

  private fun text(createdAt: Long, vararg paragraphs: String) =
    store.insertText("t$createdAt", paragraphs.toList(), createdAt)

  private fun next(after: Long, skip: Set<Long> = emptySet()) = ContinuousPlay.next(store, after, skip)?.next?.itemId

  @Test fun theNextIsTheNextOlderUnreadItemInListOrder() {
    val oldest = text(100, "Oldest. Two.")
    val older = text(200, "Older one.")
    val newest = text(300, "Newest.")
    assertEquals(older, next(newest))
    assertEquals(oldest, next(older))
    assertNull(next(oldest)) // the bottom: stop, never wrap to the top
  }

  @Test fun equalTimesSortByIdDescendingAsTheListDoes() {
    val a = text(100, "A.")
    val b = text(100, "B.")
    val c = text(100, "C.")
    assertEquals(listOf(c, b, a), store.items().map { it.id })
    assertEquals(b, next(c))
    assertEquals(a, next(b))
    assertNull(next(a))
  }

  @Test fun newerItemsAboveAreNotPickedUp() {
    val finished = text(200, "Finished.")
    text(300, "Shared during the chain.")
    assertNull(next(finished))
  }

  @Test fun notReadyArchivedAndNothingKeptAreSkipped() {
    val target = text(100, "The one to read.")
    val poor = store.insertLink("https://example.com/p", "example.com", 150)
    store.fetchSucceeded(poor, "<html></html>")
    store.completeExtraction(poor, "Poor", null, null, listOf(ParagraphRow("p", "Short.")), poor = true)
    val allCut = text(160, "Cut. Away.")
    store.setCuts(allCut, listOf(0))
    val blank = text(170, "   ")
    store.insertLink("https://example.com/f", "example.com", 180) // fetching
    val failed = store.insertLink("https://example.com/x", "example.com", 185)
    store.fetchFailed(failed, "offline")
    val archived = text(190, "Archived.")
    store.archive(archived, 1)
    val finished = text(200, "Finished.")
    assertTrue(blank > 0)
    assertEquals(target, next(finished))
  }

  @Test fun aDeletedItemIsSkippedWhetherGoneOrStoppedForDeletion() {
    val target = text(100, "Read me.")
    val stopped = text(150, "Being deleted.")
    val gone = text(160, "Deleted.")
    store.delete(gone)
    val finished = text(200, "Finished.")
    assertEquals(target, next(finished, skip = setOf(stopped)))
  }

  @Test fun aFinishedItemThatIsGoneEndsTheChain() {
    text(100, "Older.")
    val finished = text(200, "Finished.")
    store.delete(finished)
    assertNull(next(finished))
  }

  @Test fun itStartsAtTheSavedPositionAndLeavesTrimStateAlone() {
    val target = text(100, "First one. Second one.", "Third one.")
    store.savePosition(target, 0, 11, 5)
    val finished = text(200, "Finished.")
    val n = ContinuousPlay.next(store, finished, emptySet())!!
    assertEquals(target, n.item.id)
    assertEquals(SentenceRow(0, 11, 22, "Second one."), n.next.sentences[n.next.startIndex])
    assertEquals(3, n.next.sentences.size)
    assertNull(store.item(target)!!.openedAt)
    assertTrue(store.cuts(target).isEmpty())
  }

  @Test fun aPositionInACutParagraphMovesToTheNextKeptOneAndPastTheEndStartsOver() {
    val target = text(100, "Zero.", "One.", "Two.")
    store.setCuts(target, listOf(1))
    store.savePosition(target, 1, 2, 5)
    val finished = text(200, "Finished.")
    val n = ContinuousPlay.next(store, finished, emptySet())!!.next
    assertEquals(2, n.sentences[n.startIndex].paragraphIndex)
    store.savePosition(target, 2, 99, 6)
    val again = ContinuousPlay.next(store, finished, emptySet())!!.next
    assertEquals(0, again.startIndex)
  }
}
