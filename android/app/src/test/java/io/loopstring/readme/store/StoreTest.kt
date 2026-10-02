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
    val big = "x".repeat(600_000)
    val id = store.insertText("t", listOf(big), 1000)
    assertEquals(big, store.paragraphs(id).single().text)
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
}
