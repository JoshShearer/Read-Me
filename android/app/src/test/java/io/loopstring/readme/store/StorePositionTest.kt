package io.loopstring.readme.store

import android.content.Context
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
class StorePositionTest {
  private lateinit var store: Store

  @Before fun setUp() {
    store = Store.get(ApplicationProvider.getApplicationContext<Context>())
  }

  @After fun tearDown() = Store.resetForTest()

  @Test fun aSavedPositionReadsBackAndTheLatestWins() {
    val id = store.insertText("t", listOf("a", "b"), 1000)
    assertNull(store.position(id))
    assertTrue(store.savePosition(id, 0, 5, 2000))
    assertTrue(store.savePosition(id, 1, 12, 3000))
    assertEquals(PositionRow(1, 12), store.position(id))
  }

  @Test fun savingThePositionOfADeletedItemReturnsFalse() {
    // Review Focus 2: the service's onDone can race a delete from the list.
    val id = store.insertText("t", listOf("a"), 1000)
    store.delete(id)
    assertFalse(store.savePosition(id, 0, 0, 2000))
    assertNull(store.position(id))
  }

  @Test fun savingAPositionDoesNotNotifyTheList() {
    // It happens after every sentence; a list refresh per sentence would be waste.
    val id = store.insertText("t", listOf("a"), 1000)
    var n = 0
    val l: () -> Unit = { n++ }
    ItemEvents.add(l)
    try {
      store.savePosition(id, 0, 1, 2000)
    } finally {
      ItemEvents.remove(l)
    }
    assertEquals(0, n)
  }

  @Test fun finishingArchivesKeepsTheStateAndClearsThePosition() {
    val id = store.insertText("t", listOf("a"), 1000)
    store.savePosition(id, 0, 3, 2000)
    store.finishReading(id, 5000)
    val item = store.item(id)!!
    assertEquals(5000L, item.archivedAt)
    assertEquals(States.READY, item.state)
    assertNull(store.position(id))
  }

  @Test fun deletingAnItemDeletesItsPosition() {
    val id = store.insertText("t", listOf("a"), 1000)
    store.savePosition(id, 0, 3, 2000)
    store.delete(id)
    assertNull(store.position(id))
  }
}
