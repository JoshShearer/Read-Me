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
}
