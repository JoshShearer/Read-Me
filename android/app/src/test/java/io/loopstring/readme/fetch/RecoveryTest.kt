package io.loopstring.readme.fetch

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import io.loopstring.readme.store.States
import io.loopstring.readme.store.Store
import java.util.concurrent.Executor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Workers never run here: a queued request stays queued, and no test reaches the network.
private val NEVER = Executor { }

@RunWith(RobolectricTestRunner::class)
class RecoveryTest {
  private lateinit var ctx: Context

  @Before fun setUp() {
    ctx = ApplicationProvider.getApplicationContext()
    WorkManagerTestInitHelper.initializeTestWorkManager(
      ctx,
      Configuration.Builder().setExecutor(NEVER).build(),
    )
  }

  @After fun tearDown() = Store.resetForTest()

  @Test fun aFetchingItemWithNoWorkIsInterrupted() {
    val store = Store.get(ctx)
    val id = store.insertLink("https://example.com/a", "example.com", 1000)
    Recovery.run(ctx)
    assertEquals(States.FETCH_FAILED, store.item(id)!!.state)
    assertEquals("interrupted", store.item(id)!!.failReason)
  }

  @Test fun aFetchingItemWhoseWorkIsStillQueuedIsLeftFetching() {
    // Review Focus 1: the process died, WorkManager will run the work again.
    val store = Store.get(ctx)
    val id = store.insertLink("https://example.com/a", "example.com", 1000)
    FetchWorker.enqueue(ctx, id) // queued; the NEVER executor keeps it from running
    Recovery.run(ctx)
    assertEquals(States.FETCHING, store.item(id)!!.state)
  }

  @Test fun otherStatesAreUntouched() {
    val store = Store.get(ctx)
    val id = store.insertLink("https://example.com/a", "example.com", 1000)
    store.fetchSucceeded(id, "<html/>")
    Recovery.run(ctx)
    assertEquals(States.FETCHED, store.item(id)!!.state)
  }
}
