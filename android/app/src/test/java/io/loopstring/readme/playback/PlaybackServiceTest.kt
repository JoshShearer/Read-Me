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
import org.junit.Assert.assertNull
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

  @Test fun turningTheBridgeOffAfterAFailedBindReportsOff() {
    // Plan review F5: a live service kept "failed", so turning it back on never showed Starting.
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    java.net.ServerSocket(0, 1, java.net.InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))).use { taken ->
      Settings(ctx).apply { bridgePort = taken.localPort; bridgeEnabled = true }
      val c = Robolectric.buildService(PlaybackService::class.java, bridgeIntent(ctx)).create().startCommand(0, 1)
      shadowOf(Looper.getMainLooper()).idle()
      assertEquals("failed", PlaybackHub.bridge.state)
      Settings(ctx).bridgeEnabled = false
      c.withIntent(bridgeIntent(ctx)).startCommand(0, 2)
      shadowOf(Looper.getMainLooper()).idle()
      assertEquals(BridgeStatus.OFF, PlaybackHub.bridge)
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

  @Test fun startUpAndDestroySweepPlaybackAudio() {
    // REA-31, AGENTS.md 3: synthesized sentences are app-private and never outlive the service.
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    val dir = java.io.File(ctx.noBackupFilesDir, "playback").apply { mkdirs() }
    val stale = java.io.File(dir, "play-1-0-0-0.wav").apply { writeText("x") }
    val c = Robolectric.buildService(PlaybackService::class.java).create()
    assertFalse(stale.exists())
    val late = java.io.File(dir, "play-1-2-0-5.wav").apply { writeText("x") }
    c.destroy()
    assertFalse(late.exists())
  }

  @Test fun playingSynthesizesToAppPrivateFilesAndNeverSpeaks() {
    // REA-31: the engine writes files and Read Me plays them, so media keys reach Read Me.
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    val c = playing()
    val tts = shadowOf(org.robolectric.shadows.ShadowTextToSpeech.getLastTextToSpeechInstance())
    // Both sentences were asked for at once: the queue synthesizes ahead of the player.
    assertEquals("World", tts.lastSynthesizeToFileText?.toString())
    assertEquals(null, tts.lastSpokenText)
    assertTrue(java.io.File(ctx.noBackupFilesDir, "playback").listFiles().orEmpty().all { it.name.startsWith("play-") })
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
    disarmStartTimeout(c)
    shadowOf(Looper.getMainLooper()).idle()
    assertTrue(PlaybackHub.queue!!.snapshot().playing)
    return c
  }

  /**
   * onReady called directly leaves the speaker PENDING with its 30 s start timeout armed, which
   * a device never reaches (onInit cancels it before onReady). Cancel it as onInit would, so a
   * test that moves the clock sees only the timeouts it means to.
   */
  private fun disarmStartTimeout(c: org.robolectric.android.controller.ServiceController<PlaybackService>) {
    val speaker = PlaybackService::class.java.getDeclaredField("speaker").apply { isAccessible = true }.get(c.get())
    fun field(name: String) = TtsSpeaker::class.java.getDeclaredField(name).apply { isAccessible = true }.get(speaker)
    (field("main") as android.os.Handler).removeCallbacks(field("startTimeout") as Runnable)
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

  // --- REA-51: a new speaker is swapped in before the old one is shut down; while paused, late
  // engine answers neither move the position nor rebind ---

  private fun lastTts() = org.robolectric.shadows.ShadowTextToSpeech.getLastTextToSpeechInstance()

  /** The newest speaker's engine comes up through the real TtsSpeaker.onInit path. */
  private fun engineUp() {
    shadowOf(lastTts()).onInitListener.onInit(android.speech.tts.TextToSpeech.SUCCESS)
    shadowOf(Looper.getMainLooper()).idle()
  }

  private fun offlineVoice(name: String) = android.speech.tts.Voice(
    name, java.util.Locale.US, android.speech.tts.Voice.QUALITY_HIGH,
    android.speech.tts.Voice.LATENCY_NORMAL, false, emptySet(),
  )

  @Test fun aPlayForAnotherItemAfterAVoiceChangePausesTheFirstUntilTheNewEngineIsReady() {
    // begin() while another item plays: the old speaker was shut down while the queue still
    // played its generation, so its stopped requests counted as errors against what was heard.
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    org.robolectric.shadows.ShadowTextToSpeech.addVoice(offlineVoice("v-a"))
    org.robolectric.shadows.ShadowTextToSpeech.addVoice(offlineVoice("v-b"))
    Settings(ctx).voice = "v-a"
    val a = Store.get(ctx).insertText("a", listOf("Hello World"), 1L)
    PlaybackHub.offer(request(a))
    val c = Robolectric.buildService(PlaybackService::class.java, intent(PlaybackCommands.ACTION_START)).create().startCommand(0, 1)
    idle()
    engineUp()
    assertEquals(a, PlaybackHub.queue!!.snapshot().itemId)
    assertTrue(PlaybackHub.queue!!.snapshot().playing)
    val first = lastTts()

    Settings(ctx).voice = "v-b"
    val b = Store.get(ctx).insertText("b", listOf("Hello World"), 2L)
    PlaybackHub.offer(request(b))
    c.withIntent(intent(PlaybackCommands.ACTION_START)).startCommand(0, 2)
    idle()
    assertFalse(first === lastTts())
    val s = PlaybackHub.queue!!.snapshot()
    assertEquals(a, s.itemId)
    assertFalse(s.playing)
    assertEquals(b, PlaybackHub.last.waitingItemId)
    // The sentence that was playing is where the first item resumes.
    assertEquals(io.loopstring.readme.store.PositionRow(0, 0), Store.get(ctx).position(a))

    engineUp()
    assertEquals(b, PlaybackHub.queue!!.snapshot().itemId)
    assertTrue(PlaybackHub.queue!!.snapshot().playing)
    Settings(ctx).voice = null
    c.destroy()
  }

  @Test fun anEngineDeathWhilePausedNeitherRebindsNorResumesUntilPlay() {
    val c = playing()
    c.withIntent(intent(PlaybackCommands.ACTION_PAUSE)).startCommand(0, 2)
    idle()
    assertFalse(PlaybackHub.queue!!.snapshot().playing)
    val before = lastTts()
    // ERROR_SERVICE for the request still in flight (sentence 1), whatever its generation.
    for (g in 0..10) c.get().onSynthError("$g:1", lost = true)
    idle()
    assertTrue("rebound while paused", before === lastTts())
    c.get().onReady(TtsSpeaker.EngineStatus.READY)
    idle()
    assertFalse(PlaybackHub.queue!!.snapshot().playing)
    // Play rebinds, and the new engine resumes the item.
    c.withIntent(intent(PlaybackCommands.ACTION_PLAY)).startCommand(0, 3)
    idle()
    assertFalse(before === lastTts())
    assertFalse(PlaybackHub.queue!!.snapshot().playing)
    c.get().onReady(TtsSpeaker.EngineStatus.READY)
    idle()
    assertTrue(PlaybackHub.queue!!.snapshot().playing)
    c.destroy()
  }

  @Test fun theReadersPlayOnAPausedSpeakerWithNoVoiceBindsAFreshEngineFirst() {
    // REA-21 (c): the Reader re-checks the engine when the app comes back, so after a voice is
    // installed its Play reaches a paused item whose speaker reported no-voice. Resuming that
    // speaker would read with whatever voice the engine falls back to, unvetted (AGENTS.md 5).
    val c = playing()
    c.withIntent(intent(PlaybackCommands.ACTION_PAUSE)).startCommand(0, 2)
    idle()
    val speaker = PlaybackService::class.java.getDeclaredField("speaker").apply { isAccessible = true }.get(c.get()) as TtsSpeaker
    // As onInit leaves it when the engine has no usable voice (TtsSpeaker.status has a private setter).
    TtsSpeaker::class.java.getDeclaredField("status").apply { isAccessible = true }.set(speaker, TtsSpeaker.EngineStatus.NO_VOICE)
    val broken = lastTts()
    // Native.resume() from the Reader's Play.
    PlaybackHub.controller!!(PlaybackCommands.ACTION_PLAY)
    idle()
    assertFalse("Play resumed the speaker with no voice", broken === lastTts())
    assertFalse(PlaybackHub.queue!!.snapshot().playing)
    c.get().onReady(TtsSpeaker.EngineStatus.READY)
    idle()
    assertTrue(PlaybackHub.queue!!.snapshot().playing)
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

  /**
   * REA-31 (ADR 0012): sentences [from]..[last] synthesized and heard to the end, every
   * generation's ids sent: only the live one counts (the queue drops the rest). Robolectric's
   * engine never answers by itself, and the service's own writer thread is stopped first, so the
   * player's events are the test's alone and none arrive on a thread of their own.
   */
  private fun finishCurrent(c: org.robolectric.android.controller.ServiceController<PlaybackService>, from: Int, last: Int) {
    val writer = PlaybackService::class.java.getDeclaredField("player").apply { isAccessible = true }
    (writer.get(c.get()) as TrackPlayer).release()
    val item = PlaybackHub.queue!!.snapshot().itemId
    for (g in 0..40) {
      for (i in from..last) c.get().onSynthDone("$g:$i")
      for (i in from..last) {
        c.get().onPlayerStart("$g:$i")
        c.get().onPlayerDone("$g:$i")
      }
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
    finishCurrent(c, 1, 1)
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
    finishCurrent(c, 1, 1)
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
    finishCurrent(c, 1, 1)
    assertEquals(older, PlaybackHub.queue!!.snapshot().itemId)
    finishCurrent(c, 0, 0)
    assertTrue(store.item(older)!!.archivedAt != null)
    assertTrue(shadowOf(c.get()).isStoppedBySelf)
    c.destroy()
  }

  private fun shownTitle(c: org.robolectric.android.controller.ServiceController<PlaybackService>): String {
    val nm = ApplicationProvider.getApplicationContext<Context>().getSystemService(NotificationManager::class.java)
    val n = shadowOf(nm).allNotifications.lastOrNull() ?: shadowOf(c.get()).lastForegroundNotification
    return n.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
  }

  @Test fun aNextItemTheQueueNeverTakesDoesNotChangeTheTitle() {
    // ADR 0011: nextItem builds B outside the queue lock and the queue may drop it (a pause,
    // stop, play or deletion first). The title follows the queue's item, not the offer.
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    val store = Store.get(ctx)
    store.insertText("Second title", listOf("Never read."), 100L)
    val first = store.insertText("First title", listOf("Hello World"), 200L)
    Settings(ctx).continuousPlay = true
    val c = playingItem(first)
    assertTrue(c.get().nextItem(first, emptySet()) != null)
    PlaybackHub.queue!!.pause()
    idle()
    assertEquals(first, PlaybackHub.queue!!.snapshot().itemId)
    assertEquals("First title", shownTitle(c))
    c.destroy()
  }

  @Test fun peekNextFollowsTheSwitchAndOffersNoTitle() {
    // REA-49: preparation asks which item would come next; only the handover's nextItem offers
    // its title, and the switch is read at each ask.
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    val store = Store.get(ctx)
    val older = store.insertText("Second title", listOf("Never read."), 100L)
    val first = store.insertText("First title", listOf("Hello World"), 200L)
    val c = playingItem(first)
    assertNull(c.get().peekNext(first, emptySet()))
    Settings(ctx).continuousPlay = true
    val n = c.get().peekNext(first, emptySet())!!
    assertEquals(older, n.itemId)
    assertNull(n.openedAt)
    assertNull(c.get().peekNext(first, setOf(older)))
    idle()
    assertEquals("First title", shownTitle(c))
    c.destroy()
  }

  // --- REA-18: lifecycle edges deferred from Phase 3 ---

  private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(ms))

  /**
   * A service playing item 1 on an engine that answered through the real TtsSpeaker.onInit (so
   * the speaker is READY and its start timeout is cancelled), for tests that move the clock.
   */
  private fun playingAnswered(): org.robolectric.android.controller.ServiceController<PlaybackService> {
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    org.robolectric.shadows.ShadowTextToSpeech.addVoice(offlineVoice("v-a"))
    Settings(ctx).voice = "v-a"
    val id = Store.get(ctx).insertText("t", listOf("Hello World"), 1L)
    PlaybackHub.offer(request(id))
    val c = Robolectric.buildService(PlaybackService::class.java, intent(PlaybackCommands.ACTION_START)).create().startCommand(0, 1)
    idle()
    engineUp()
    assertTrue(PlaybackHub.queue!!.snapshot().playing)
    Settings(ctx).voice = null
    return c
  }

  @Test fun anEngineAnswerAfterDestroyStartsNothing() {
    // onReady ignored `destroyed`: a late READY loaded the waiting request on a released player.
    val (c, _) = waiting()
    c.destroy()
    c.get().onReady(TtsSpeaker.EngineStatus.READY)
    idle()
    assertEquals(null, PlaybackHub.last.itemId)
    assertFalse(PlaybackHub.last.playing)
  }

  @Test fun aLateEngineInitAfterDestroyStartsNothing() {
    // The same through the real path: TtsSpeaker posts onInit on its own handler, which
    // onDestroy's removeCallbacksAndMessages does not clear.
    org.robolectric.shadows.ShadowTextToSpeech.addVoice(offlineVoice("v-a"))
    val (c, _) = waiting()
    c.destroy()
    engineUp()
    assertEquals(null, PlaybackHub.last.itemId)
    assertFalse(PlaybackHub.last.playing)
  }

  /** Item 1 lost its engine and the rebind's engine never answers; returns that engine. */
  private fun rebindTimedOut(c: org.robolectric.android.controller.ServiceController<PlaybackService>): android.speech.tts.TextToSpeech {
    val first = lastTts()
    repeat(PlaybackQueue.STALL_TICKS) { PlaybackHub.queue!!.checkStall() }
    idle() // recoverEngine: rebinding
    val rebound = lastTts()
    assertFalse(first === rebound)
    advance(TtsSpeaker.START_TIMEOUT_MS)
    return rebound
  }

  @Test fun aRebindThatNeverAnswersTimesOutAndPlayBindsAFreshEngine() {
    val c = playing()
    val dead = rebindTimedOut(c)
    val s = PlaybackHub.queue!!.snapshot()
    assertFalse(s.playing)
    assertTrue("the item stays, paused", s.itemId != null)
    assertFalse(shadowOf(c.get()).isStoppedBySelf)
    // Not R-M06's no-voice card: nothing says this phone has no engine, only that this one
    // did not answer in time.
    assertEquals("pending", PlaybackHub.engine)
    // Play reaches a fresh engine, not the one that never answered.
    PlaybackHub.controller!!(PlaybackCommands.ACTION_PLAY)
    idle()
    val fresh = lastTts()
    assertFalse("Play reused the engine that never answered", dead === fresh)
    c.get().onReady(TtsSpeaker.EngineStatus.READY)
    idle()
    assertTrue(PlaybackHub.queue!!.snapshot().playing)
    // That fresh engine did not spend the one rebind: a later loss on it is rebound again.
    repeat(PlaybackQueue.STALL_TICKS) { PlaybackHub.queue!!.checkStall() }
    idle()
    assertFalse("a later loss was not rebound", fresh === lastTts())
    c.destroy()
  }

  @Test fun aLateAnswerFromATimedOutEngineStartsNothing() {
    // ADR 0012 D5: playback never starts by itself. With an offline voice, that answer would
    // be READY and resume the item.
    org.robolectric.shadows.ShadowTextToSpeech.addVoice(offlineVoice("v-a"))
    val c = playing()
    val dead = rebindTimedOut(c)
    shadowOf(dead).onInitListener.onInit(android.speech.tts.TextToSpeech.SUCCESS)
    idle()
    assertFalse(PlaybackHub.queue!!.snapshot().playing)
    assertEquals("pending", PlaybackHub.engine)
    c.destroy()
  }

  @Test fun aColdStartThatNeverAnswersDropsTheWaitingPlay() {
    val (c, _) = waiting()
    advance(TtsSpeaker.START_TIMEOUT_MS)
    assertEquals(null, PlaybackHub.last.waitingItemId)
    assertEquals(null, PlaybackHub.last.itemId)
    assertTrue(shadowOf(c.get()).isStoppedBySelf)
    c.destroy()
  }

  @Test fun anEngineAnsweringJustInsideTheTimeoutStillPlays() {
    // Guard: a slow engine (8 s cold was measured) must not be cut off.
    org.robolectric.shadows.ShadowTextToSpeech.addVoice(offlineVoice("v-a"))
    val (c, id) = waiting()
    advance(TtsSpeaker.START_TIMEOUT_MS - 1_000)
    engineUp()
    assertEquals(id, PlaybackHub.queue!!.snapshot().itemId)
    assertTrue(PlaybackHub.queue!!.snapshot().playing)
    c.destroy()
  }

  @Test fun theWakeLockIsRenewedWhilePlaying() {
    // The 4 h wake lock was taken once per play, so a longer read lost it.
    val c = playingAnswered()
    val wl = org.robolectric.shadows.ShadowPowerManager.getLatestWakeLock()
    assertTrue(wl.isHeld)
    val held = shadowOf(wl).timesHeld
    // One stall tick after an hour, not the 720 between: a tick with no progress is not a loss.
    org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(WakeRenewal.EVERY_MS))
    idle()
    assertTrue(PlaybackHub.queue!!.snapshot().playing)
    assertEquals(held + 1, shadowOf(wl).timesHeld)
    // Not again on the next tick.
    org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(5_000))
    idle()
    assertEquals(held + 1, shadowOf(wl).timesHeld)
    c.destroy()
  }

  @Test fun aNewSentenceDoesNotRepostTheNotification() {
    val c = playing()
    val n1 = shadowOf(c.get()).lastForegroundNotification
    PlaybackHub.controller!!(PlaybackCommands.ACTION_NEXT)
    idle()
    assertEquals(6, PlaybackHub.queue!!.snapshot().sentence?.start)
    org.junit.Assert.assertSame(n1, shadowOf(c.get()).lastForegroundNotification)
    // What it shows changes: it is posted again.
    PlaybackHub.controller!!(PlaybackCommands.ACTION_PAUSE)
    idle()
    val n2 = shadowOf(c.get()).lastForegroundNotification
    assertFalse(n1 === n2)
    assertTrue(n2.actions.any { it.title.toString() == "Play" })
    c.destroy()
  }

  @Test fun aPauseBeforeTheRebindStartsStaysPausedWhenTheEngineIsBack() {
    // REA-51's window: the Pause lands between the queue reporting the loss and the posted
    // recoverEngine, which then set resumeAfterRebind again.
    val c = playing()
    repeat(PlaybackQueue.STALL_TICKS) { PlaybackHub.queue!!.checkStall() } // no idle: not yet rebinding
    PlaybackHub.controller!!(PlaybackCommands.ACTION_PAUSE)
    idle()
    c.get().onReady(TtsSpeaker.EngineStatus.READY)
    idle()
    assertFalse(PlaybackHub.queue!!.snapshot().playing)
    c.destroy()
  }

  @Test fun aPlayFromTheJsThreadBuildsTheNewSpeakerOnMain() {
    // control() runs on the JS thread; TtsSpeaker must be built (and speaker written) on main.
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    val c = playing()
    PlaybackHub.controller!!(PlaybackCommands.ACTION_PAUSE)
    idle()
    Settings(ctx).voice = "another-voice"
    val before = lastTts()
    val t = Thread { PlaybackHub.controller!!(PlaybackCommands.ACTION_PLAY) }
    t.start()
    t.join()
    assertTrue("built off main", before === lastTts())
    idle()
    assertFalse(before === lastTts())
    Settings(ctx).voice = null
    c.destroy()
  }

  // --- REA-18 fix round 1: a Play on the JS thread posts its rebind to main; a Pause or Stop
  // given after that Play, before the post runs, must still win (ADR 0012 D5) ---

  private fun onJs(action: String) {
    val t = Thread { PlaybackHub.controller!!(action) }
    t.start()
    t.join()
  }

  @Test fun aJsPlayThenPauseAfterAVoiceChangeStaysPausedWhenTheEngineIsBack() {
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    val c = playing()
    PlaybackHub.controller!!(PlaybackCommands.ACTION_PAUSE)
    idle()
    Settings(ctx).voice = "another-voice"
    try {
      onJs(PlaybackCommands.ACTION_PLAY) // posts the rebind to main
      onJs(PlaybackCommands.ACTION_PAUSE) // before main runs it
      idle()
      c.get().onReady(TtsSpeaker.EngineStatus.READY)
      idle()
      assertFalse("resumed by itself after a user Pause", PlaybackHub.queue!!.snapshot().playing)
    } finally {
      Settings(ctx).voice = null
    }
    c.destroy()
  }

  @Test fun aJsPlayThenStopAfterAVoiceChangeStartsNothingWhenTheEngineIsBack() {
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    val c = playing()
    PlaybackHub.controller!!(PlaybackCommands.ACTION_PAUSE)
    idle()
    Settings(ctx).voice = "another-voice"
    try {
      onJs(PlaybackCommands.ACTION_PLAY)
      onJs(PlaybackCommands.ACTION_STOP)
      idle()
      c.get().onReady(TtsSpeaker.EngineStatus.READY)
      idle()
      assertFalse("started by itself after a user Stop", PlaybackHub.last.playing)
    } finally {
      Settings(ctx).voice = null
    }
    c.destroy()
  }

  @Test fun aJsPlayThenPauseOnATimedOutEngineStaysPausedWhenTheFreshEngineIsBack() {
    val c = playing()
    rebindTimedOut(c)
    assertFalse(PlaybackHub.queue!!.snapshot().playing)
    onJs(PlaybackCommands.ACTION_PLAY)
    onJs(PlaybackCommands.ACTION_PAUSE)
    idle()
    c.get().onReady(TtsSpeaker.EngineStatus.READY)
    idle()
    assertFalse("resumed by itself after a user Pause", PlaybackHub.queue!!.snapshot().playing)
    c.destroy()
  }

  @Test fun aJsPlayAloneAfterAVoiceChangeStillResumes() {
    // Guard for the fix: with no Pause after it, the Play's rebind resumes.
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    val c = playing()
    PlaybackHub.controller!!(PlaybackCommands.ACTION_PAUSE)
    idle()
    Settings(ctx).voice = "another-voice"
    try {
      onJs(PlaybackCommands.ACTION_PLAY)
      idle()
      c.get().onReady(TtsSpeaker.EngineStatus.READY)
      idle()
      assertTrue(PlaybackHub.queue!!.snapshot().playing)
    } finally {
      Settings(ctx).voice = null
    }
    c.destroy()
  }

  @Test fun aSessionPlayThenPauseAfterAVoiceChangeStaysPausedWhenTheEngineIsBack() {
    // The media session calls back on main, so the rebind is not posted; guard for that path.
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    val c = playing()
    PlaybackHub.controller!!(PlaybackCommands.ACTION_PAUSE)
    idle()
    Settings(ctx).voice = "another-voice"
    val cb = PlaybackService::class.java.getDeclaredField("sessionCallback").apply { isAccessible = true }
      .get(c.get()) as android.media.session.MediaSession.Callback
    try {
      cb.onPlay()
      cb.onPause()
      idle()
      c.get().onReady(TtsSpeaker.EngineStatus.READY)
      idle()
      assertFalse("resumed by itself after a user Pause", PlaybackHub.queue!!.snapshot().playing)
    } finally {
      Settings(ctx).voice = null
    }
    c.destroy()
  }
}
