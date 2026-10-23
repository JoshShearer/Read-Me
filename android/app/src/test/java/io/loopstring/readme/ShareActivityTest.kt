package io.loopstring.readme

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import io.loopstring.readme.fetch.FetchWorker
import io.loopstring.readme.intake.SharedFile
import io.loopstring.readme.store.States
import io.loopstring.readme.store.Store
import java.io.ByteArrayInputStream
import java.util.concurrent.Executor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowToast

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

  @After fun tearDown() {
    Store.resetForTest()
    SharedOpen.resetForTest()
  }

  @Before fun readFilesInline() {
    ShareActivity.executor = Executor { it.run() }
  }

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

  // --- REA-63: a link that matches an unread item opens it ---

  private fun workFor(id: Long) =
    WorkManager.getInstance(ctx).getWorkInfosForUniqueWork(FetchWorker.uniqueName(id)).get()

  @Test fun theSameLinkSharedTwiceLeavesOneItemAndOpensIt() {
    share("Some title https://www.example.com/story?id=7")
    val first = Store.get(ctx).items().single()
    Shadows.shadowOf(ctx as android.app.Application).clearNextStartedActivities()
    val activity = share("Some title https://www.example.com/story?id=7")
    assertEquals(listOf(first.id), Store.get(ctx).items().map { it.id })
    assertEquals(ShareActivity.ALREADY, ShadowToast.getTextOfLatestToast())
    assertTrue(activity.isFinishing)
    val started = shadowOf(activity).nextStartedActivity
    assertEquals(MainActivity::class.java.name, started.component!!.className)
    assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    // Nothing in the intent: no URL (AGENTS.md 1), and no id, which MainActivity would have to
    // trust from any caller. The id goes through SharedOpen in this process.
    assertTrue(started.extras == null || started.extras!!.isEmpty)
    assertNull(started.data)
    assertEquals(first.id, SharedOpen.take())
    assertNull(SharedOpen.take())
  }

  @Test fun theOpenIsOfferedBeforeMainActivityIsStarted() {
    share("https://www.example.com/story")
    val id = Store.get(ctx).items().single().id
    val app = ctx as android.app.Application
    Shadows.shadowOf(app).clearNextStartedActivities()
    var startedAtOffer: Intent? = Intent()
    SharedOpen.addListener { startedAtOffer = Shadows.shadowOf(app).peekNextStartedActivity() }
    share("https://www.example.com/story")
    // A warm MainActivity's JS takes the id on the event or on AppState active; either needs the
    // slot filled before the start.
    assertNull(startedAtOffer)
    assertEquals(id, SharedOpen.take())
  }

  @Test fun aNewLinkOffersNoOpen() {
    share("https://www.example.com/new")
    assertNull(SharedOpen.take())
  }

  @Test fun aMatchQueuesNoFetchEvenWhenTheItemFailed() {
    // Seeded through the Store, so no work exists before the share: any work after it is new.
    val store = Store.get(ctx)
    val id = store.insertLink("https://www.example.com/dead", "example.com", 1)
    store.fetchFailed(id, "offline")
    val activity = share("https://www.example.com/dead")
    assertEquals(listOf(id), store.items().map { it.id })
    assertEquals(States.FETCH_FAILED, store.item(id)!!.state)
    assertTrue(workFor(id).isEmpty())
    assertEquals(MainActivity::class.java.name, shadowOf(activity).nextStartedActivity.component!!.className)
    assertEquals(id, SharedOpen.take())
  }

  @Test fun aLinkMatchingOnlyAnArchivedItemIsANewFetchingItem() {
    val store = Store.get(ctx)
    val old = store.insertLink("https://www.example.com/read", "example.com", 1)
    store.archive(old, 2)
    val activity = share("https://www.example.com/read")
    val new = store.items().map { it.id }.single { it != old }
    assertEquals(States.FETCHING, store.item(new)!!.state)
    assertEquals(1, workFor(new).size)
    assertEquals(ShareActivity.SAVED, ShadowToast.getTextOfLatestToast())
    assertNull(shadowOf(activity).nextStartedActivity)
  }

  @Test fun theSameTextSharedTwiceIsTwoItems() {
    share("A paragraph of text.")
    val activity = share("A paragraph of text.")
    assertEquals(2, Store.get(ctx).items().size)
    assertNull(shadowOf(activity).nextStartedActivity)
  }

  @Test fun theSameFileSharedTwiceIsTwoItems() {
    open(Uri.parse("content://x/y/A.txt"), "Words.".toByteArray(), Intent.ACTION_SEND, "text/plain")
    open(Uri.parse("content://x/y/A.txt"), "Words.".toByteArray(), Intent.ACTION_SEND, "text/plain")
    assertEquals(2, Store.get(ctx).items().size)
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

  // --- R-C04: shared files ---

  private fun open(uri: Uri, bytes: ByteArray?, action: String, type: String): ShareActivity {
    if (bytes != null) shadowOf(ctx.contentResolver).registerInputStream(uri, ByteArrayInputStream(bytes))
    val intent = Intent(action).setType(type)
    if (action == Intent.ACTION_VIEW) intent.setDataAndType(uri, type) else intent.putExtra(Intent.EXTRA_STREAM, uri)
    val activity = Robolectric.buildActivity(ShareActivity::class.java, intent).create().get()
    shadowOf(Looper.getMainLooper()).idle()
    return activity
  }

  @Test fun anObsidianNoteOpenedWithViewIsAReadyTextItemReadAsProse() {
    val note = "---\ntags: x\n---\n# Trip plan\n\nPack **boots** and see [[Maps|the map]].\n\n- tent\n- stove\n"
    val activity = open(
      Uri.parse("content://md.obsidian.fileprovider/root/Trip.md"),
      note.toByteArray(),
      Intent.ACTION_VIEW,
      "text/markdown",
    )
    val store = Store.get(ctx)
    val item = store.items().single()
    assertEquals("text", item.kind)
    assertEquals(States.READY, item.state)
    assertEquals("Trip plan", item.title)
    assertEquals(ShareActivity.SAVED, ShadowToast.getTextOfLatestToast())
    assertEquals(
      listOf("heading" to "Trip plan", "p" to "Pack boots and see the map.", "li" to "tent", "li" to "stove"),
      store.paragraphs(item.id).map { it.kind to it.text },
    )
    assertTrue(activity.isFinishing)
  }

  @Test fun aTextFileSentAsAStreamIsTitledByItsName() {
    open(Uri.parse("content://files/doc/Notes%20today.txt"), "One.\n\nTwo.".toByteArray(), Intent.ACTION_SEND, "text/plain")
    val store = Store.get(ctx)
    val item = store.items().single()
    assertEquals("Notes today", item.title)
    assertEquals(listOf("One.", "Two."), store.paragraphs(item.id).map { it.text })
  }

  @Test fun aNoteThatIsOnlyALinkIsNeverFetched() {
    open(Uri.parse("content://x/y/Link.md"), "https://example.com/story".toByteArray(), Intent.ACTION_VIEW, "text/markdown")
    val item = Store.get(ctx).items().single()
    assertEquals("text", item.kind)
    assertEquals(States.READY, item.state)
    assertEquals(0, WorkManager.getInstance(ctx).getWorkInfosForUniqueWork(FetchWorker.uniqueName(item.id)).get().size)
  }

  @Test fun aFileOverTheCapStoresNothing() {
    val big = ByteArray((SharedFile.CAP_BYTES + 1).toInt()) { 'a'.code.toByte() }
    open(Uri.parse("content://x/y/Big.txt"), big, Intent.ACTION_SEND, "text/plain")
    assertTrue(Store.get(ctx).items().isEmpty())
    assertEquals(ShareActivity.TOO_LARGE, ShadowToast.getTextOfLatestToast())
  }

  @Test fun anUnreadableFileStoresNothingAndDoesNotCrash() {
    val activity = open(Uri.parse("content://x/y/Gone.md"), null, Intent.ACTION_VIEW, "text/markdown")
    assertTrue(Store.get(ctx).items().isEmpty())
    assertTrue(activity.isFinishing)
    assertEquals(ShareActivity.UNREADABLE, ShadowToast.getTextOfLatestToast())
  }

  @Test fun aFileUriIsRefused() {
    open(Uri.parse("file:///sdcard/Notes.md"), null, Intent.ACTION_VIEW, "text/markdown")
    assertTrue(Store.get(ctx).items().isEmpty())
  }

  @Test fun aLinkShareThatAlsoCarriesAStreamIsStillALink() {
    val intent = Intent(Intent.ACTION_SEND).setType("text/plain")
      .putExtra(Intent.EXTRA_TEXT, "https://www.example.com/story")
      .putExtra(Intent.EXTRA_STREAM, Uri.parse("content://x/y/thumb.png"))
    Robolectric.buildActivity(ShareActivity::class.java, intent).create().get()
    val item = Store.get(ctx).items().single()
    assertEquals("link", item.kind)
    assertEquals("https://www.example.com/story", item.url)
  }

  @Test fun aDocumentUriWithoutADisplayNameIsTitledByTheFileNameOnly() {
    open(Uri.parse("content://docs/document/primary%3ANotes%2FTrip.txt"), "Words.".toByteArray(), Intent.ACTION_SEND, "text/plain")
    assertEquals("Trip", Store.get(ctx).items().single().title)
  }
}
