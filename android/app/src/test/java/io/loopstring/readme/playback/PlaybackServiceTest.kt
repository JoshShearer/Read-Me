package io.loopstring.readme.playback

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import io.loopstring.readme.store.Settings
import androidx.test.core.app.ApplicationProvider
import io.loopstring.readme.store.Store
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class PlaybackServiceTest {
  @After fun tearDown() {
    Settings(ApplicationProvider.getApplicationContext()).bridgeEnabled = false
    Settings(ApplicationProvider.getApplicationContext()).continuousPlay = false
    PlaybackHub.resetForTest()
    Store.resetForTest()
  }

  @Test fun destroyingAPausedServiceRemovesItsNotification() {
    // Review: the system stops a paused (detached) service; its Play button must not outlive it.
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    val nm = ctx.getSystemService(NotificationManager::class.java)
    val controller = Robolectric.buildService(PlaybackService::class.java).create()
    @Suppress("DEPRECATION")
    nm.notify(PlaybackService.NOTIFICATION_ID, Notification.Builder(ctx).setSmallIcon(android.R.drawable.ic_media_play).build())
    assertEquals(1, shadowOf(nm).allNotifications.size)
    controller.destroy()
    assertEquals(0, shadowOf(nm).allNotifications.size)
  }

  private fun freePort() = java.net.ServerSocket(0).use { it.localPort }

  private fun health(port: Int): Int = java.net.Socket("127.0.0.1", port).use { s ->
    s.soTimeout = 3_000
    s.getOutputStream().write("GET /health HTTP/1.1\r\n\r\n".toByteArray())
    String(s.getInputStream().readBytes()).substringAfter(' ').take(3).toInt()
  }

  private fun bridgeIntent(ctx: Context, action: String = PlaybackCommands.ACTION_BRIDGE) =
    Intent(ctx, PlaybackService::class.java).setAction(action)

  @Test fun theBridgeServesWhileEnabledAndTheNotificationSaysSo() {
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    val port = freePort()
    Settings(ctx).apply { bridgePort = port; bridgeEnabled = true }
    val c = Robolectric.buildService(PlaybackService::class.java, bridgeIntent(ctx)).create().startCommand(0, 1)
    shadowOf(Looper.getMainLooper()).idle()
    assertEquals(BridgeStatus("on", port, null), PlaybackHub.bridge)
    assertEquals(200, health(port))
    val n = shadowOf(c.get()).lastForegroundNotification
    assertEquals("Obsidian bridge on", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())

    Settings(ctx).bridgeEnabled = false
    c.withIntent(bridgeIntent(ctx)).startCommand(0, 2)
    shadowOf(Looper.getMainLooper()).idle()
    assertEquals(BridgeStatus.OFF, PlaybackHub.bridge)
    assertTrue(runCatching { health(port) }.isFailure)
    assertTrue(shadowOf(c.get()).isStoppedBySelf)
    c.destroy()
  }

  @Test fun theNotificationsTurnOffActionDisablesTheBridge() {
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    val port = freePort()
    Settings(ctx).apply { bridgePort = port; bridgeEnabled = true }
    val c = Robolectric.buildService(PlaybackService::class.java, bridgeIntent(ctx)).create().startCommand(0, 1)
    shadowOf(Looper.getMainLooper()).idle()
    c.withIntent(bridgeIntent(ctx, PlaybackCommands.ACTION_BRIDGE_OFF)).startCommand(0, 2)
    shadowOf(Looper.getMainLooper()).idle()
    assertFalse(Settings(ctx).bridgeEnabled)
    assertEquals(BridgeStatus.OFF, PlaybackHub.bridge)
    c.destroy()
  }

  @Test fun aTakenPortReportsFailedAndStopsAnIdleService() {
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    java.net.ServerSocket(0, 1, java.net.InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))).use { taken ->
      Settings(ctx).apply { bridgePort = taken.localPort; bridgeEnabled = true }
      val c = Robolectric.buildService(PlaybackService::class.java, bridgeIntent(ctx)).create().startCommand(0, 1)
      shadowOf(Looper.getMainLooper()).idle()
      assertEquals(BridgeStatus("failed", taken.localPort, "BindException"), PlaybackHub.bridge)
      assertTrue(shadowOf(c.get()).isStoppedBySelf)
      c.destroy()
    }
  }

  @Test fun theBridgeKeepsTheServiceAfterPlaybackEnds() {
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    val port = freePort()
    Settings(ctx).apply { bridgePort = port; bridgeEnabled = true }
    val c = Robolectric.buildService(PlaybackService::class.java, bridgeIntent(ctx)).create().startCommand(0, 1)
    shadowOf(Looper.getMainLooper()).idle()
    // An item ends: the queue reports an empty snapshot, as it does on finish or stop.
    c.get().changed(PlaybackSnapshot(null, false, null, 2.0f))
    shadowOf(Looper.getMainLooper()).idle()
    assertFalse(shadowOf(c.get()).isStoppedBySelf)
    assertEquals(200, health(port))
    val n = shadowOf(c.get()).lastForegroundNotification
    assertEquals("Obsidian bridge on", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
    c.destroy()
  }

  @Test fun startUpSweepsStaleBridgeFiles() {
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    val stale = java.io.File(ctx.cacheDir, "bridge-old.wav").apply { writeText("x") }
    val c = Robolectric.buildService(PlaybackService::class.java).create()
    assertFalse(stale.exists())
    c.destroy()
  }

  @Test fun startingPlaybackPreemptsTheBridge() {
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    Settings(ctx).apply { bridgePort = freePort(); bridgeEnabled = true }
    val c = Robolectric.buildService(PlaybackService::class.java, bridgeIntent(ctx)).create().startCommand(0, 1)
    shadowOf(Looper.getMainLooper()).idle()
    PlaybackHub.publish(PlaybackSnapshot(1L, true, null, 2.0f))
    assertEquals(1, c.get().bridgePreemptsForTest)
    c.destroy()
  }

  @Test fun turningTheBridgeOffKeepsAPlayRequestWaitingForTheEngine() {
    // Final review: a play request held while the engine starts must survive a bridge intent.
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    PlaybackHub.offer(PlaybackHub.Request(1L, "t", listOf(SentenceRow(0, 0, 5, "Hello")), 0))
    val c = Robolectric.buildService(PlaybackService::class.java,
      Intent(ctx, PlaybackService::class.java).setAction(PlaybackCommands.ACTION_START)).create().startCommand(0, 1)
    shadowOf(Looper.getMainLooper()).idle()
    c.withIntent(bridgeIntent(ctx)).startCommand(0, 2)
    shadowOf(Looper.getMainLooper()).idle()
    assertFalse(shadowOf(c.get()).isStoppedBySelf)
    c.destroy()
  }

  private fun request(id: Long) = PlaybackHub.Request(id, "t", listOf(SentenceRow(0, 0, 5, "Hello"), SentenceRow(0, 6, 11, "World")), 0)

  /** A service playing item 1 on a ready engine (Robolectric's engine never calls back by itself). */
  private fun playing(): org.robolectric.android.controller.ServiceController<PlaybackService> {
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    val id = Store.get(ctx).insertText("t", listOf("Hello World"), 1L)
    PlaybackHub.offer(request(id))
    val c = Robolectric.buildService(PlaybackService::class.java,
      Intent(ctx, PlaybackService::class.java).setAction(PlaybackCommands.ACTION_START)).create().startCommand(0, 1)
    shadowOf(Looper.getMainLooper()).idle()
    c.get().onReady(TtsSpeaker.EngineStatus.READY)
    shadowOf(Looper.getMainLooper()).idle()
    assertTrue(PlaybackHub.queue!!.snapshot().playing)
    return c
  }

  @Test fun aPauseDuringAFocusPauseKeepsThePausedWindow() {
    // Final review: Pause pressed while a call holds playback skipped ADR 0009's window, so the
    // service left the foreground at once and Android stopped it about a minute later.
    val c = playing()
    val am = ApplicationProvider.getApplicationContext<Context>().getSystemService(android.media.AudioManager::class.java)
    shadowOf(am).lastAudioFocusRequest.listener.onAudioFocusChange(android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
    shadowOf(Looper.getMainLooper()).idle()
    PlaybackHub.controller!!(PlaybackCommands.ACTION_PAUSE)
    shadowOf(Looper.getMainLooper()).idle()
    assertFalse(shadowOf(c.get()).isForegroundStopped)
    c.destroy()
  }

  @Test fun aPlayRequestDuringARebindIsPlayedWhenTheEngineIsBack() {
    // Final review: the rebind branch resumed the old item and left the new request waiting.
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    val c = playing()
    repeat(PlaybackQueue.STALL_TICKS) { PlaybackHub.queue!!.checkStall() } // Robolectric's engine is never speaking
    shadowOf(Looper.getMainLooper()).idle() // recoverEngine: rebinding
    val second = Store.get(ctx).insertText("u", listOf("Hello World"), 2L)
    PlaybackHub.offer(request(second))
    c.withIntent(Intent(ctx, PlaybackService::class.java).setAction(PlaybackCommands.ACTION_START)).startCommand(0, 2)
    shadowOf(Looper.getMainLooper()).idle()
    c.get().onReady(TtsSpeaker.EngineStatus.READY)
    shadowOf(Looper.getMainLooper()).idle()
    assertEquals(second, PlaybackHub.queue!!.snapshot().itemId)
    c.destroy()
  }

  @Test fun aPauseDuringARebindStaysPausedWhenTheEngineIsBack() {
    // Critique F1: the rebind resumed unconditionally, so a headset Pause during it was lost.
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    val c = playing()
    repeat(PlaybackQueue.STALL_TICKS) { PlaybackHub.queue!!.checkStall() }
    shadowOf(Looper.getMainLooper()).idle() // recoverEngine: rebinding
    c.withIntent(Intent(ctx, PlaybackService::class.java).setAction(PlaybackCommands.ACTION_PAUSE)).startCommand(0, 2)
    shadowOf(Looper.getMainLooper()).idle()
    c.get().onReady(TtsSpeaker.EngineStatus.READY)
    shadowOf(Looper.getMainLooper()).idle()
    assertFalse(PlaybackHub.queue!!.snapshot().playing)
    c.destroy()
  }

  @Test fun playAfterAVoiceChangeRebindsWithTheNewVoiceAndThenResumes() {
    // REA-33: Play on a paused item resumed with the old engine and voice, so a change in
    // Settings never reached it (the Huawei tablet kept speaking with iFlytek).
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    val c = playing()
    c.withIntent(Intent(ctx, PlaybackService::class.java).setAction(PlaybackCommands.ACTION_PAUSE)).startCommand(0, 2)
    shadowOf(Looper.getMainLooper()).idle()
    assertFalse(PlaybackHub.queue!!.snapshot().playing)
    Settings(ctx).voice = "another-voice"
    c.withIntent(Intent(ctx, PlaybackService::class.java).setAction(PlaybackCommands.ACTION_PLAY)).startCommand(0, 3)
    shadowOf(Looper.getMainLooper()).idle()
    // A new speaker is binding; nothing resumes on the old one.
    assertFalse(PlaybackHub.queue!!.snapshot().playing)
    c.get().onReady(TtsSpeaker.EngineStatus.READY)
    shadowOf(Looper.getMainLooper()).idle()
    assertTrue(PlaybackHub.queue!!.snapshot().playing)
    Settings(ctx).voice = null
    c.destroy()
  }

  // --- REA-35: a play request waiting for a cold engine (the engine is PENDING) ---

  private fun intent(action: String) =
    Intent(ApplicationProvider.getApplicationContext(), PlaybackService::class.java).setAction(action)

  private fun idle() = shadowOf(Looper.getMainLooper()).idle()

  /** A new service whose engine has not answered yet, holding a play request for a stored item. */
  private fun waiting(): Pair<org.robolectric.android.controller.ServiceController<PlaybackService>, Long> {
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    val id = Store.get(ctx).insertText("t", listOf("Hello World"), 1L)
    PlaybackHub.offer(request(id))
    val c = Robolectric.buildService(PlaybackService::class.java, intent(PlaybackCommands.ACTION_START)).create().startCommand(0, 1)
    idle()
    assertEquals(null, PlaybackHub.queue!!.snapshot().itemId)
    return c to id
  }

  @Test fun aRateChangeWhileTheEngineStartsKeepsThePlayAtTheNewRate() {
    // REA-35 #1: the rate tap published an empty snapshot, onSnapshot ended the service and the
    // waiting Play was dropped. ReadMeSpeechModule.setRate does exactly these two writes.
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    val (c, id) = waiting()
    Settings(ctx).rate = 2.1f
    PlaybackHub.queue!!.setRate(2.1f)
    idle()
    assertFalse(shadowOf(c.get()).isStoppedBySelf)
    c.get().onReady(TtsSpeaker.EngineStatus.READY)
    idle()
    val s = PlaybackHub.queue!!.snapshot()
    assertEquals(id, s.itemId)
    assertTrue(s.playing)
    assertEquals(2.1f, s.rate)
    Settings(ctx).rate = 2.0f
    c.destroy()
  }

  @Test fun aStaleControlIntentWhileTheEngineStartsDoesNotStopTheService() {
    // REA-35 #2: routing looked only at the queue's item, so a notification or headset intent
    // that arrived during a cold start stopped the service and the Play was lost.
    val (c, id) = waiting()
    c.withIntent(intent(PlaybackCommands.ACTION_NEXT)).startCommand(0, 2)
    idle()
    assertFalse(shadowOf(c.get()).isStoppedBySelf)
    c.get().onReady(TtsSpeaker.EngineStatus.READY)
    idle()
    assertEquals(id, PlaybackHub.queue!!.snapshot().itemId)
    c.destroy()
  }

  @Test fun aPauseFromTheAppWhileTheEngineStartsCancelsThePlay() {
    // REA-35 #2: Pause found nothing playing and changed nothing, so the engine coming up later
    // started reading what the user had paused.
    val (c, _) = waiting()
    PlaybackHub.controller!!(PlaybackCommands.ACTION_PAUSE)
    idle()
    c.get().onReady(TtsSpeaker.EngineStatus.READY)
    idle()
    assertEquals(null, PlaybackHub.queue?.snapshot()?.itemId)
    assertTrue(shadowOf(c.get()).isStoppedBySelf)
    c.destroy()
  }

  @Test fun aStopFromTheAppWhileTheEngineStartsCancelsThePlay() {
    // REA-35 #2: as Pause; Stop is what Trim sends when everything left is cut.
    val (c, _) = waiting()
    PlaybackHub.controller!!(PlaybackCommands.ACTION_STOP)
    idle()
    c.get().onReady(TtsSpeaker.EngineStatus.READY)
    idle()
    assertEquals(null, PlaybackHub.queue?.snapshot()?.itemId)
    assertTrue(shadowOf(c.get()).isStoppedBySelf)
    c.destroy()
  }

  @Test fun aStopIntentWhileTheEngineStartsCancelsThePlay() {
    // Guard (passes before REA-35): the stop must still win once routing sees the waiting request.
    val (c, _) = waiting()
    c.withIntent(intent(PlaybackCommands.ACTION_STOP)).startCommand(0, 2)
    idle()
    assertTrue(shadowOf(c.get()).isStoppedBySelf)
    c.destroy()
  }

  @Test fun aSecondPlayWhileTheEngineStartsReplacesTheWaitingRequest() {
    // Guard (passes before REA-35): a cut during a cold start re-plays the item; the new list wins.
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    val (c, id) = waiting()
    val cut = PlaybackHub.Request(id, "t", listOf(SentenceRow(0, 6, 11, "World")), 0)
    PlaybackHub.offer(cut)
    c.withIntent(intent(PlaybackCommands.ACTION_START)).startCommand(0, 2)
    idle()
    c.get().onReady(TtsSpeaker.EngineStatus.READY)
    idle()
    assertEquals(SentenceRow(0, 6, 11, "World"), PlaybackHub.queue!!.snapshot().sentence)
    assertFalse(Store.get(ctx).item(id) == null)
    c.destroy()
  }

  @Test fun theServiceStopsItselfWithTheLatestStartId() {
    // REA-35 #2: stopSelf() without an id also discards a start Android has delivered since.
    val c = playing()
    c.withIntent(intent(PlaybackCommands.ACTION_STOP)).startCommand(0, 5)
    idle()
    assertEquals(5, shadowOf(c.get()).stopSelfResultId)
    c.destroy()
  }

  @Test fun aFailedRebindDropsTheWaitingPlayAndTheNextPlayBindsAFreshEngine() {
    // REA-35 #3: the failed rebind kept `waiting`, so the foreground never synced, and the next
    // Play met the dead speaker's NO_ENGINE and ended the service.
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    val c = playing()
    repeat(PlaybackQueue.STALL_TICKS) { PlaybackHub.queue!!.checkStall() }
    idle() // recoverEngine: rebinding
    val second = Store.get(ctx).insertText("u", listOf("Hello World"), 2L)
    PlaybackHub.offer(request(second))
    c.withIntent(intent(PlaybackCommands.ACTION_START)).startCommand(0, 2)
    idle()
    // The rebind fails through the real TtsSpeaker.onInit path.
    shadowOf(org.robolectric.shadows.ShadowTextToSpeech.getLastTextToSpeechInstance())
      .onInitListener.onInit(android.speech.tts.TextToSpeech.ERROR)
    idle()
    assertEquals("no-engine", PlaybackHub.engine)
    assertFalse(PlaybackHub.queue!!.snapshot().itemId == second)
    // The user plays again: a fresh speaker is bound instead of ending on the dead one.
    val before = org.robolectric.shadows.ShadowTextToSpeech.getLastTextToSpeechInstance()
    PlaybackHub.offer(request(second))
    c.withIntent(intent(PlaybackCommands.ACTION_START)).startCommand(0, 3)
    idle()
    assertFalse(shadowOf(c.get()).isStoppedBySelf)
    assertFalse(before === org.robolectric.shadows.ShadowTextToSpeech.getLastTextToSpeechInstance())
    c.get().onReady(TtsSpeaker.EngineStatus.READY)
    idle()
    assertEquals(second, PlaybackHub.queue!!.snapshot().itemId)
    c.destroy()
  }

  @Test fun theWaitingRequestsItemIsPublished() {
    // REA-35 #4: JS saw no item while the Play waited, so a cut could not reach it.
    val (c, id) = waiting()
    assertEquals(id, PlaybackHub.last.waitingItemId)
    assertEquals(null, PlaybackHub.last.itemId)
    c.get().onReady(TtsSpeaker.EngineStatus.READY)
    idle()
    assertEquals(null, PlaybackHub.last.waitingItemId)
    assertEquals(id, PlaybackHub.last.itemId)
    c.destroy()
  }

  @Test fun aPauseFromAnotherThreadWhileTheEngineStartsCancelsThePlay() {
    // Guard (passes before the critique fix): JS calls the controller on its own thread, so the
    // cancel goes through main.post; the other Pause tests call it on the main thread.
    val (c, _) = waiting()
    val t = Thread { PlaybackHub.controller!!(PlaybackCommands.ACTION_PAUSE) }
    t.start()
    t.join()
    idle()
    c.get().onReady(TtsSpeaker.EngineStatus.READY)
    idle()
    assertEquals(null, PlaybackHub.queue?.snapshot()?.itemId)
    assertTrue(shadowOf(c.get()).isStoppedBySelf)
    c.destroy()
  }

  // --- R-S05 continuous play: the service hands over to the next unread item ---

  /** Every generation's last sentence reported done: only the live one counts (the queue drops the rest). */
  private fun finishCurrent(c: org.robolectric.android.controller.ServiceController<PlaybackService>, last: Int) {
    val item = PlaybackHub.queue!!.snapshot().itemId
    for (g in 0..20) {
      c.get().onDone("$g:$last")
      if (PlaybackHub.queue?.snapshot()?.itemId != item) break
    }
    idle()
  }

  /** Plays [first] (two sentences) on a ready engine. */
  private fun playingItem(first: Long): org.robolectric.android.controller.ServiceController<PlaybackService> {
    PlaybackHub.offer(PlaybackHub.Request(first, "First title", listOf(SentenceRow(0, 0, 5, "Hello"), SentenceRow(0, 6, 11, "World")), 1))
    val c = Robolectric.buildService(PlaybackService::class.java, intent(PlaybackCommands.ACTION_START)).create().startCommand(0, 1)
    idle()
    c.get().onReady(TtsSpeaker.EngineStatus.READY)
    idle()
    assertEquals(first, PlaybackHub.queue!!.snapshot().itemId)
    return c
  }

  @Test fun withContinuousPlayTheNextUnreadItemStartsAfterTheArchive() {
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    val store = Store.get(ctx)
    val older = store.insertText("Second title", listOf("Never opened. Read as is."), 100L)
    val first = store.insertText("First title", listOf("Hello World"), 200L)
    Settings(ctx).continuousPlay = true
    val c = playingItem(first)
    val seen = mutableListOf<PlaybackSnapshot>()
    PlaybackHub.addListener { seen += it }
    finishCurrent(c, 1)
    val s = PlaybackHub.queue!!.snapshot()
    assertEquals(older, s.itemId)
    assertTrue(s.playing)
    assertEquals(SentenceRow(0, 0, 13, "Never opened."), s.sentence)
    assertTrue(store.item(first)!!.archivedAt != null)
    assertEquals(null, store.item(older)!!.archivedAt)
    // Trim's first open is the user's: continuous play neither opens nor cuts (R-M05, ADR 0011).
    assertEquals(null, store.item(older)!!.openedAt)
    assertTrue(store.cuts(older).isEmpty())
    assertTrue(seen.none { it.itemId == null || !it.playing })
    assertFalse(shadowOf(c.get()).isStoppedBySelf)
    assertFalse(shadowOf(c.get()).isForegroundStopped)
    val n = shadowOf(c.get()).lastForegroundNotification
    assertEquals("Second title", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
    c.destroy()
  }

  @Test fun withContinuousPlayOffTheItemArchivesAndPlaybackStops() {
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    val store = Store.get(ctx)
    val older = store.insertText("Second title", listOf("Not read."), 100L)
    val first = store.insertText("First title", listOf("Hello World"), 200L)
    val c = playingItem(first)
    finishCurrent(c, 1)
    assertEquals(null, PlaybackHub.queue?.snapshot()?.itemId)
    assertTrue(store.item(first)!!.archivedAt != null)
    assertEquals(null, store.item(older)!!.archivedAt)
    assertTrue(shadowOf(c.get()).isStoppedBySelf)
    c.destroy()
  }

  @Test fun theChainStopsAfterTheLastUnreadItem() {
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    val store = Store.get(ctx)
    val older = store.insertText("Second title", listOf("Only one."), 100L)
    val first = store.insertText("First title", listOf("Hello World"), 200L)
    Settings(ctx).continuousPlay = true
    val c = playingItem(first)
    finishCurrent(c, 1)
    assertEquals(older, PlaybackHub.queue!!.snapshot().itemId)
    finishCurrent(c, 0)
    assertTrue(store.item(older)!!.archivedAt != null)
    assertTrue(shadowOf(c.get()).isStoppedBySelf)
    c.destroy()
  }
}
