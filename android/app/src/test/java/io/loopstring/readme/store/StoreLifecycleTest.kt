package io.loopstring.readme.store

import android.content.Context
import java.io.File
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StoreLifecycleTest {
  private lateinit var store: Store
  private var link = 0L

  @Before fun setUp() {
    store = Store.get(ApplicationProvider.getApplicationContext<Context>())
    link = store.insertLink("https://example.com/a", "example.com", 1000)
  }

  @After fun tearDown() = Store.resetForTest()

  @Test fun aSuccessfulFetchStoresTheBodyAndMovesToFetched() {
    store.fetchSucceeded(link, "<html>page</html>")
    assertEquals(States.FETCHED, store.item(link)!!.state)
    assertEquals("<html>page</html>", store.bodyOrFail(link))
  }

  @Test fun aFailedFetchKeepsTheItemWithItsReason() {
    store.fetchFailed(link, "timeout")
    val item = store.item(link)!!
    assertEquals(States.FETCH_FAILED, item.state)
    assertEquals("timeout", item.failReason)
  }

  @Test fun retryIsAllowedOnlyFromFetchFailed() {
    assertFalse(store.beginRetry(link))
    store.fetchFailed(link, "offline")
    assertTrue(store.beginRetry(link))
    val item = store.item(link)!!
    assertEquals(States.FETCHING, item.state)
    assertNull(item.failReason)
  }

  @Test fun extractionStoresParagraphsSetsTheStateAndDeletesTheBody() {
    store.fetchSucceeded(link, "<html/>")
    val ok = store.completeExtraction(
      link, "A Title", "Example", "Ann", listOf(ParagraphRow("heading", "H"), ParagraphRow("p", "Body")), poor = false,
    )
    assertTrue(ok)
    val item = store.item(link)!!
    assertEquals(States.READY, item.state)
    assertEquals("A Title", item.title)
    assertEquals("Example", item.site)
    assertEquals("Ann", item.byline)
    assertEquals(listOf(ParagraphRow("heading", "H"), ParagraphRow("p", "Body")), store.paragraphs(link))
    assertFalse(store.bodyFile(link).exists())
  }

  @Test fun aPoorExtractionIsExtractPoor() {
    store.fetchSucceeded(link, "<html/>")
    store.completeExtraction(link, "T", null, null, listOf(ParagraphRow("p", "short")), poor = true)
    assertEquals(States.EXTRACT_POOR, store.item(link)!!.state)
  }

  @Test fun extractionIsRefusedUnlessTheItemIsFetched() {
    assertFalse(store.completeExtraction(link, "T", null, null, emptyList(), poor = true))
    assertEquals(States.FETCHING, store.item(link)!!.state)
  }

  @Test fun aFetchedItemWithNoBodyBecomesInterrupted() {
    // Review Focus 3: never stuck in fetched.
    store.fetchSucceeded(link, "<html/>")
    store.bodyFile(link).delete()
    assertNull(store.bodyOrFail(link))
    val item = store.item(link)!!
    assertEquals(States.FETCH_FAILED, item.state)
    assertEquals("interrupted", item.failReason)
  }

  @Test fun bodyOrFailIsNullForAnItemNotFetched() {
    assertNull(store.bodyOrFail(link))
    assertEquals(States.FETCHING, store.item(link)!!.state)
  }

  @Test fun openedAtIsSetOnceAtTheFirstOpen() {
    store.markOpened(link, 5000)
    store.markOpened(link, 9000)
    assertEquals(5000L, store.item(link)!!.openedAt)
  }

  @Test fun cutsAreASeparateSetAndLeaveParagraphTextAlone() {
    val text = store.insertText("t", listOf("a", "b", "c"), 1000)
    store.setCut(text, 1, true)
    store.setCut(text, 1, true)
    store.setCut(text, 2, true)
    store.setCut(text, 2, false)
    assertEquals(listOf(1), store.cuts(text))
    assertEquals(listOf("a", "b", "c"), store.paragraphs(text).map { it.text })
  }

  @Test fun archiveKeepsTheStateAndRestoreClearsIt() {
    store.fetchSucceeded(link, "<html/>")
    store.completeExtraction(link, "T", null, null, listOf(ParagraphRow("p", "x")), poor = true)
    store.archive(link, 7000)
    assertEquals(7000L, store.item(link)!!.archivedAt)
    assertEquals(States.EXTRACT_POOR, store.item(link)!!.state)
    store.restore(link)
    assertNull(store.item(link)!!.archivedAt)
    assertEquals(States.EXTRACT_POOR, store.item(link)!!.state)
  }

  @Test fun deleteRemovesAWaitingBody() {
    store.fetchSucceeded(link, "<html/>")
    store.delete(link)
    assertFalse(store.bodyFile(link).exists())
  }

  // Final review 1 (AGENTS.md 3): a fetch that finishes after Delete must not leave the page on disk.
  @Test fun aFetchFinishingAfterDeleteLeavesNoBody() {
    store.delete(link)
    store.fetchSucceeded(link, "<html>page</html>")
    assertFalse(store.bodyFile(link).exists())
    assertFalse(File(store.bodyFile(link).path + ".tmp").exists())
  }

  @Test fun deleteRemovesAHalfWrittenBody() {
    val tmp = File(store.bodyFile(link).path + ".tmp")
    tmp.parentFile!!.mkdirs()
    tmp.writeText("<html>half")
    store.delete(link)
    assertFalse(tmp.exists())
  }

  // Final review 2: one row over CursorWindow's 2 MB must not make the library unreadable.
  @Test fun anOversizedTitleOrParagraphIsBoundedAndTheLibraryStaysReadable() {
    store.fetchSucceeded(link, "<html/>")
    val cjk = "\u4e2d".repeat(800_000) // 2.4 MB in UTF-8, no spaces to split on
    assertTrue(
      store.completeExtraction(
        link, "t".repeat(3_000_000), "s".repeat(3_000_000), null,
        listOf(ParagraphRow("p", cjk)), false,
      ),
    )
    val item = store.items().single { it.id == link }
    assertTrue(item.title.length <= Store.MAX_FIELD_CHARS)
    val paras = store.paragraphs(link)
    assertTrue(paras.size > 1)
    assertEquals(cjk, paras.joinToString("") { it.text })
  }
}
