package io.loopstring.readme.store

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StoreTest {
  private lateinit var ctx: Context
  private lateinit var store: Store

  @Before fun setUp() {
    ctx = ApplicationProvider.getApplicationContext()
    store = Store.get(ctx)
  }

  @After fun tearDown() = Store.resetForTest()

  @Test fun aLinkStartsFetchingWithItsHostAsTitle() {
    val id = store.insertLink("https://example.com/a", "example.com", 1000)
    val item = store.item(id)!!
    assertEquals("link", item.kind)
    assertEquals("https://example.com/a", item.url)
    assertEquals("example.com", item.title)
    assertEquals(States.FETCHING, item.state)
    assertNull(item.openedAt)
    assertNull(item.archivedAt)
  }

  @Test fun aTextIsReadyWithItsParagraphsInOrder() {
    val id = store.insertText("First", listOf("First", "Second", "Third"), 1000)
    assertEquals(States.READY, store.item(id)!!.state)
    assertEquals(
      listOf(ParagraphRow("p", "First"), ParagraphRow("p", "Second"), ParagraphRow("p", "Third")),
      store.paragraphs(id),
    )
  }

  @Test fun itemsAreNewestFirst() {
    val a = store.insertLink("https://a.com", "a.com", 1000)
    val b = store.insertLink("https://b.com", "b.com", 2000)
    val c = store.insertLink("https://c.com", "c.com", 2000)
    assertEquals(listOf(c, b, a), store.items().map { it.id })
  }

  @Test fun aLongSingleParagraphRoundTrips() {
    // Review Focus 5: hundreds of KB in one paragraph.
    val big = "x".repeat(400_000)
    val id = store.insertText("t", listOf(big), 1000)
    assertEquals(big, store.paragraphs(id).single().text)
    // Past the row cap it is split, never cut: the text reads back whole.
    val huge = "y ".repeat(350_000)
    val id2 = store.insertText("t", listOf(huge), 1000)
    assertEquals(huge, store.paragraphs(id2).joinToString("") { it.text })
  }

  @Test fun deleteRemovesTheItemAndEverythingUnderIt() {
    val id = store.insertText("t", listOf("a", "b"), 1000)
    store.delete(id)
    assertNull(store.item(id))
    assertTrue(store.paragraphs(id).isEmpty())
    assertTrue(store.cuts(id).isEmpty())
  }

  @Test fun everyWriteNotifiesListeners() {
    var n = 0
    val l: () -> Unit = { n++ }
    ItemEvents.add(l)
    try {
      val id = store.insertLink("https://a.com", "a.com", 1000)
      store.delete(id)
    } finally {
      ItemEvents.remove(l)
    }
    assertEquals(2, n)
  }

  // --- REA-63 (ADR 0016): a shared link that matches an unread item ---

  @Test fun anUnreadMatchIsTheExistingItemAndAddsNoRow() {
    val id = store.insertLink("https://example.com/a?x=1", "example.com", 1000)
    assertEquals(LinkShare(id, created = false), store.findUnreadLinkOrInsert("https://example.com/a?x=1", "example.com", 2000))
    assertEquals(listOf(id), store.items().map { it.id })
  }

  @Test fun noMatchInsertsAFetchingLink() {
    val share = store.findUnreadLinkOrInsert("https://example.com/a", "example.com", 1000)
    assertTrue(share.created)
    val item = store.item(share.id)!!
    assertEquals("link", item.kind)
    assertEquals("https://example.com/a", item.url)
    assertEquals(States.FETCHING, item.state)
  }

  @Test fun aFetchFailedOrReadyUnreadItemMatchesToo() {
    val failed = store.insertLink("https://example.com/f", "example.com", 1000)
    store.fetchFailed(failed, "offline")
    assertEquals(LinkShare(failed, false), store.findUnreadLinkOrInsert("https://example.com/f", "x", 2000))
    assertEquals(States.FETCH_FAILED, store.item(failed)!!.state)
  }

  @Test fun anArchivedOnlyMatchIsANewItem() {
    val old = store.insertLink("https://example.com/a", "example.com", 1000)
    store.archive(old, 1500)
    val share = store.findUnreadLinkOrInsert("https://example.com/a", "example.com", 2000)
    assertTrue(share.created)
    assertTrue(share.id != old)
    assertEquals(2, store.items().size)
  }

  @Test fun severalUnreadMatchesGiveTheNewest() {
    val a = store.insertLink("https://example.com/a", "example.com", 1000)
    val b = store.insertLink("https://example.com/a", "example.com", 1000)
    assertTrue(b > a)
    assertEquals(LinkShare(b, false), store.findUnreadLinkOrInsert("https://example.com/a", "example.com", 2000))
    assertEquals(2, store.items().size)
  }

  @Test fun theMatchIsExact() {
    store.insertLink("https://example.com/a?q=1", "example.com", 1000)
    for (variant in listOf("https://example.com/a?q=1/", "https://example.com/a?q=1#top", "https://example.com/a?q=2", "https://example.com/a")) {
      assertTrue(variant, store.findUnreadLinkOrInsert(variant, "example.com", 2000).created)
    }
    assertEquals(5, store.items().size)
  }

  @Test fun aTextItemIsNeverMatched() {
    store.insertText("https://example.com/a", listOf("https://example.com/a"), 1000)
    assertTrue(store.findUnreadLinkOrInsert("https://example.com/a", "example.com", 2000).created)
  }

  @Test fun aMatchFiresNoChangeAndAnInsertFiresOne() {
    store.insertLink("https://example.com/a", "example.com", 1000)
    var n = 0
    val l: () -> Unit = { n++ }
    ItemEvents.add(l)
    try {
      store.findUnreadLinkOrInsert("https://example.com/a", "example.com", 2000)
      assertEquals(0, n)
      store.findUnreadLinkOrInsert("https://example.com/b", "example.com", 2000)
      assertEquals(1, n)
    } finally {
      ItemEvents.remove(l)
    }
  }
}
