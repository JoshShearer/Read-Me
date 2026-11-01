package io.loopstring.readme.playback

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.loopstring.readme.store.Move
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

/** R-S05 as amended (REA-72, ADR 0017): the next item is the next row down in the stored List order, chosen in Kotlin. */
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

  @Test fun theNextIsTheNextRowDownInTheStoredOrder() {
    // REA-72 (ADR 0017): new items go to the bottom, so insert order is List order.
    val top = text(300, "Top. Two.")
    val middle = text(200, "Middle one.")
    val bottom = text(100, "Bottom.")
    assertEquals(listOf(top, middle, bottom), store.items().map { it.id })
    assertEquals(middle, next(top))
    assertEquals(bottom, next(middle))
    assertNull(next(bottom)) // the bottom: stop, never wrap to the top
  }

  @Test fun equalTimesFollowInsertOrderAsTheListDoes() {
    val a = text(100, "A.")
    val b = text(100, "B.")
    val c = text(100, "C.")
    assertEquals(listOf(a, b, c), store.items().map { it.id })
    assertEquals(b, next(a))
    assertEquals(c, next(b))
    assertNull(next(c))
  }

  @Test fun anItemSharedDuringTheChainIsReached() {
    val finished = text(200, "Finished.")
    val shared = text(300, "Shared during the chain.")
    assertEquals(shared, next(finished))
  }

  @Test fun theHandoverFollowsMoves() {
    val (a, b, c) = listOf(text(100, "A."), text(200, "B."), text(300, "C."))
    assertTrue(store.move(c, Move.UP)) // a, c, b
    assertEquals(c, next(a))
    assertEquals(b, next(c))
    assertTrue(store.move(b, Move.TOP)) // b, a, c: an item moved above the finished one is not reached
    assertEquals(c, next(a))
    assertNull(next(c))
  }

  @Test fun movingTheItemBeingReadMakesTheNextRowDownFollowItsNewPlace() {
    val (a, b, c) = listOf(text(100, "A."), text(200, "B."), text(300, "C."))
    assertEquals(b, next(a))
    assertTrue(store.move(a, Move.DOWN)) // b, a, c
    assertEquals(c, next(a))
    assertTrue(store.move(a, Move.BOTTOM)) // b, c, a
    assertNull(next(a))
  }

  @Test fun anItemPlayedFromTheArchiveHandsOverByItsOwnStoredPlace() {
    val (a, b, c) = listOf(text(100, "A."), text(200, "B."), text(300, "C."))
    store.archive(b, 1)
    assertEquals(c, next(b))
    assertEquals(c, next(a))
  }

  @Test fun notReadyArchivedAndNothingKeptAreSkipped() {
    val finished = text(200, "Finished.")
    val poor = store.insertLink("https://example.com/p", "example.com", 150)
    store.fetchSucceeded(poor, "<html></html>")
    store.completeExtraction(poor, "Poor", null, null, listOf(ParagraphRow("p", "Short.")), poor = true)
    val allCut = text(160, "Cut. Away.")
    store.setCuts(allCut, listOf(0))
    // U+00A0 between two spaces, as an escape so an editor cannot fold it: blank only because
    // Segmenter treats NBSP as whitespace (JS \s), so nothing is kept and the item is skipped.
    val blank = text(170, " \u00A0 ")
    store.insertLink("https://example.com/f", "example.com", 180) // fetching
    val failed = store.insertLink("https://example.com/x", "example.com", 185)
    store.fetchFailed(failed, "offline")
    val archived = text(190, "Archived.")
    store.archive(archived, 1)
    val target = text(100, "The one to read.")
    assertTrue(blank > 0)
    assertEquals(target, next(finished))
  }

  @Test fun aDeletedItemIsSkippedWhetherGoneOrStoppedForDeletion() {
    val finished = text(200, "Finished.")
    val stopped = text(150, "Being deleted.")
    val gone = text(160, "Deleted.")
    store.delete(gone)
    val target = text(100, "Read me.")
    assertEquals(target, next(finished, skip = setOf(stopped)))
  }

  @Test fun aFinishedItemThatIsGoneEndsTheChain() {
    val finished = text(200, "Finished.")
    text(100, "Below.")
    store.delete(finished)
    assertNull(next(finished))
  }

  @Test fun itStartsAtTheSavedPositionAndLeavesTrimStateAlone() {
    val finished = text(200, "Finished.")
    val target = text(100, "First one. Second one.", "Third one.")
    store.savePosition(target, 0, 11, 5)
    val n = ContinuousPlay.next(store, finished, emptySet())!!
    assertEquals(target, n.item.id)
    assertEquals(SentenceRow(0, 11, 22, "Second one."), n.next.sentences[n.next.startIndex])
    assertEquals(3, n.next.sentences.size)
    assertNull(store.item(target)!!.openedAt)
    assertTrue(store.cuts(target).isEmpty())
  }

  @Test fun aPositionInACutParagraphMovesToTheNextKeptOneAndPastTheEndStartsOver() {
    val finished = text(200, "Finished.")
    val target = text(100, "Zero.", "One.", "Two.")
    store.setCuts(target, listOf(1))
    store.savePosition(target, 1, 2, 5)
    val n = ContinuousPlay.next(store, finished, emptySet())!!.next
    assertEquals(2, n.sentences[n.startIndex].paragraphIndex)
    store.savePosition(target, 2, 99, 6)
    val again = ContinuousPlay.next(store, finished, emptySet())!!.next
    assertEquals(0, again.startIndex)
  }

  @Test fun theNextCarriesItsCutsAndOpenedAtForThePreparedKey() {
    // REA-49: audio prepared for the next item is keyed on these, so a trim or an open discards it.
    val finished = text(200, "Finished.")
    val target = text(100, "First. Two.", "Second.", "Third.")
    store.setCuts(target, listOf(1))
    val n = ContinuousPlay.next(store, finished, emptySet())!!.next
    assertEquals(setOf(1), n.cuts)
    assertNull(n.openedAt)
    store.markOpened(target, 555)
    assertEquals(555L, ContinuousPlay.next(store, finished, emptySet())!!.next.openedAt)
  }
}
