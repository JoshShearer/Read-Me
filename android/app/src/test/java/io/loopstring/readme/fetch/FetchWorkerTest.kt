package io.loopstring.readme.fetch

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
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
}
