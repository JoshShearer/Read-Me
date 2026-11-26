package io.loopstring.readme.fetch

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import androidx.work.testing.WorkManagerTestInitHelper
import io.loopstring.readme.store.ItemEvents
import io.loopstring.readme.store.States
import io.loopstring.readme.store.Store
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FetchWorkerTest {
  private lateinit var ctx: Context
  private lateinit var server: MockWebServer

  @Before fun setUp() {
    ctx = ApplicationProvider.getApplicationContext()
    server = MockWebServer()
    server.start()
  }

  @After fun tearDown() {
    server.shutdown()
    Store.resetForTest()
  }

  private fun run(id: Long): ListenableWorker.Result =
    TestListenableWorkerBuilder<FetchWorker>(ctx)
      .setInputData(workDataOf(FetchWorker.KEY_ID to id))
      .build()
      .doWork()

  @Test fun aSuccessfulFetchLeavesTheItemFetchedWithItsBody() {
    server.enqueue(MockResponse().setBody("<html>page</html>"))
    val store = Store.get(ctx)
    val id = store.insertLink(server.url("/a").toString(), "host", 1000)
    assertTrue(run(id) is ListenableWorker.Result.Success)
    assertEquals(States.FETCHED, store.item(id)!!.state)
    assertEquals("<html>page</html>", store.bodyOrFail(id))
  }

  @Test fun aFailedFetchIsVisibleWithItsReasonAndNotRetried() {
    server.enqueue(MockResponse().setResponseCode(500))
    val store = Store.get(ctx)
    val id = store.insertLink(server.url("/a").toString(), "host", 1000)
    assertTrue(run(id) is ListenableWorker.Result.Success)
    assertEquals("http-500", store.item(id)!!.failReason)
    assertEquals(1, server.requestCount)
  }

  @Test fun anItemNoLongerFetchingIsLeftAlone() {
    val store = Store.get(ctx)
    val id = store.insertLink(server.url("/a").toString(), "host", 1000)
    store.fetchFailed(id, "offline")
    run(id)
    assertEquals(0, server.requestCount)
  }

  // Final review 4: a throw while storing the outcome must not leave the item fetching.
  @Test fun aThrowWhileStoringTheBodyFailsTheItemAsInterrupted() {
    server.enqueue(MockResponse().setBody("<html>page</html>"))
    File(ctx.filesDir, "bodies").apply { deleteRecursively(); writeText("not a directory") }
    val store = Store.get(ctx)
    val id = store.insertLink(server.url("/a").toString(), "host", 1000)
    try {
      assertTrue(run(id) is ListenableWorker.Result.Success)
      assertEquals(States.FETCH_FAILED, store.item(id)!!.state)
      assertEquals("interrupted", store.item(id)!!.failReason)
    } finally {
      File(ctx.filesDir, "bodies").delete()
    }
  }

  // REA-74: the outcome and the fallback both fail to save (a full disk). A real SQLite abort,
  // so the production write path is what throws; reads still work, as they do on a full disk.
  private fun breakWrites(store: Store) = store.writableDatabase.execSQL(
    "CREATE TRIGGER broken BEFORE UPDATE ON items BEGIN SELECT RAISE(ABORT, 'full'); END",
  )

  private fun repairWrites(store: Store) = store.writableDatabase.execSQL("DROP TRIGGER broken")

  private fun rawState(store: Store, id: Long): String =
    store.readableDatabase.rawQuery("SELECT state FROM items WHERE id = ?", arrayOf(id.toString()))
      .use { it.moveToFirst(); it.getString(0) }

  @Test fun whenNoWriteLandsAnOpenLibraryShowsInterruptedAtOnceWithoutASecondRequest() {
    server.enqueue(MockResponse().setBody("<html>page</html>"))
    val store = Store.get(ctx)
    val id = store.insertLink(server.url("/a").toString(), "host", 1000)
    breakWrites(store)
    var changed = 0
    val listener = { changed++; Unit }
    ItemEvents.add(listener)
    try {
      val result = run(id)
      assertTrue("finished: a re-run would fetch again", result is ListenableWorker.Result.Success)
    } finally {
      ItemEvents.remove(listener)
    }
    assertTrue("the open library is told to reload", changed >= 1)
    assertEquals("the write really failed", States.FETCHING, rawState(store, id))
    for (row in listOf(store.item(id)!!, store.items().single { it.id == id })) {
      assertEquals(States.FETCH_FAILED, row.state)
      assertEquals("interrupted", row.failReason)
    }
    assertEquals(1, server.requestCount)
    // WorkManager running the same request again must not fetch either.
    run(id)
    assertEquals(1, server.requestCount)
  }

  @Test fun aFailedFetchWhoseReasonCannotBeSavedIsShownToo() {
    server.enqueue(MockResponse().setResponseCode(500))
    val store = Store.get(ctx)
    val id = store.insertLink(server.url("/a").toString(), "host", 1000)
    breakWrites(store)
    run(id)
    assertEquals(States.FETCH_FAILED, store.item(id)!!.state)
    assertEquals(1, server.requestCount)
  }

  @Test fun theUsersRetryOfAnUnsavedFailureFetchesOnceAndClearsIt() {
    server.enqueue(MockResponse().setBody("<html>one</html>"))
    server.enqueue(MockResponse().setBody("<html>two</html>"))
    val store = Store.get(ctx)
    val id = store.insertLink(server.url("/a").toString(), "host", 1000)
    breakWrites(store)
    run(id)
    // Still broken: Retry cannot be recorded, so the failure stays on screen.
    assertTrue(runCatching { store.beginRetry(id) }.getOrDefault(false).not())
    assertEquals(States.FETCH_FAILED, store.item(id)!!.state)
    repairWrites(store)
    assertTrue(store.beginRetry(id))
    assertEquals(States.FETCHING, store.item(id)!!.state)
    run(id)
    assertEquals(States.FETCHED, store.item(id)!!.state)
    assertEquals("<html>two</html>", store.bodyOrFail(id))
    assertEquals(2, server.requestCount)
  }

  @Test fun anUnsavedFailureIsSavedByTheNextProcessStartWithoutARequest() {
    server.enqueue(MockResponse().setBody("<html>page</html>"))
    val store = Store.get(ctx)
    val id = store.insertLink(server.url("/a").toString(), "host", 1000)
    breakWrites(store)
    run(id)
    repairWrites(store)
    Store.resetForTest() // the process died: nothing in memory is left
    val next = Store.get(ctx)
    assertEquals(States.FETCHING, next.item(id)!!.state)
    WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
    Recovery.run(ctx, Long.MAX_VALUE)
    assertEquals(States.FETCH_FAILED, rawState(next, id))
    assertEquals(1, server.requestCount)
  }

  @Test fun deletingAnItemWithAnUnsavedFailureRemovesIt() {
    server.enqueue(MockResponse().setBody("<html>page</html>"))
    val store = Store.get(ctx)
    val id = store.insertLink(server.url("/a").toString(), "host", 1000)
    breakWrites(store)
    run(id)
    store.delete(id)
    assertEquals(null, store.item(id))
  }
}
