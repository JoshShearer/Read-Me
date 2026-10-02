package io.loopstring.readme.store

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StoreStatsTest {
  private lateinit var store: Store

  @Before fun setUp() {
    store = Store.get(ApplicationProvider.getApplicationContext<Context>())
  }

  @After fun tearDown() = Store.resetForTest()

  @Test fun wordsAndProgressCountOnlyKeptParagraphs() {
    // 10 chars / 2 words, 11 chars / 3 words, 9 chars / 2 words
    val id = store.insertText("t", listOf("aaaa bbbbb", "cc ddd eeee", "fffff ggg"), 1000)
    assertEquals(ItemStats(words = 7, keptChars = 30, readChars = 0), store.stats()[id])
    store.setCut(id, 1, true)
    assertEquals(ItemStats(words = 4, keptChars = 19, readChars = 0), store.stats()[id])
    store.savePosition(id, 2, 6, 2000)
    // paragraph 0 (10) + 6 chars into paragraph 2; the cut paragraph 1 is not counted
    assertEquals(16, store.stats()[id]!!.readChars)
  }

  @Test fun anOffsetPastItsParagraphCountsOnlyTheParagraph() {
    val id = store.insertText("t", listOf("abc", "de"), 1000)
    store.savePosition(id, 0, 99, 2000)
    assertEquals(3, store.stats()[id]!!.readChars)
  }

  @Test fun anItemWithNoParagraphsHasNoStats() {
    val id = store.insertLink("https://example.com/a", "example.com", 1000)
    assertNull(store.stats()[id])
  }

  @Test fun setCutsReplacesTheWholeSetAndKeepsTheText() {
    val id = store.insertText("t", listOf("a", "b", "c", "d"), 1000)
    store.setCut(id, 0, true)
    store.setCuts(id, listOf(2, 3))
    assertEquals(listOf(2, 3), store.cuts(id))
    assertEquals(4, store.paragraphs(id).size)
    store.setCuts(id, emptyList())
    assertEquals(emptyList<Int>(), store.cuts(id))
  }
}
