package io.loopstring.readme

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import io.loopstring.readme.fetch.FetchWorker
import io.loopstring.readme.store.States
import io.loopstring.readme.store.Store
import java.util.concurrent.Executor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

// Workers never run here: the queued request stays queued, and no test reaches the network.
private val NEVER = Executor { }

@RunWith(RobolectricTestRunner::class)
class ShareActivityTest {
  private lateinit var ctx: Context

  @Before fun setUp() {
    ctx = ApplicationProvider.getApplicationContext()
    WorkManagerTestInitHelper.initializeTestWorkManager(
      ctx,
      Configuration.Builder().setExecutor(NEVER).build(),
    )
  }

  @After fun tearDown() = Store.resetForTest()

  private fun share(text: String?): ShareActivity {
    val intent = Intent(Intent.ACTION_SEND).setType("text/plain")
    if (text != null) intent.putExtra(Intent.EXTRA_TEXT, text)
    return Robolectric.buildActivity(ShareActivity::class.java, intent).create().get()
  }

  @Test fun aSharedLinkIsStoredFetchingAndItsWorkIsQueued() {
    val activity = share("Some title https://www.example.com/story?id=7")
    val item = Store.get(ctx).items().single()
    assertEquals("link", item.kind)
    assertEquals("https://www.example.com/story?id=7", item.url)
    assertEquals("example.com", item.title)
    assertEquals(States.FETCHING, item.state)
    val work = WorkManager.getInstance(ctx)
      .getWorkInfosForUniqueWork(FetchWorker.uniqueName(item.id)).get()
    assertEquals(1, work.size)
    assertTrue(!work.single().state.isFinished)
    assertTrue(activity.isFinishing)
  }

  @Test fun sharedTextIsAReadyTextItem() {
    share("First paragraph here.\n\nSecond one.")
    val store = Store.get(ctx)
    val item = store.items().single()
    assertEquals("text", item.kind)
    assertEquals(States.READY, item.state)
    assertEquals("First paragraph here.", item.title)
    assertEquals(listOf("First paragraph here.", "Second one."), store.paragraphs(item.id).map { it.text })
  }

  @Test fun anEmptyShareStoresNothing() {
    val activity = share("   ")
    assertTrue(Store.get(ctx).items().isEmpty())
    assertTrue(activity.isFinishing)
  }

  @Test fun aShareWithNoTextStoresNothing() {
    share(null)
    assertTrue(Store.get(ctx).items().isEmpty())
  }
}
