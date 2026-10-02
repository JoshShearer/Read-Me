package io.loopstring.readme.playback

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.loopstring.readme.store.Store
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class PlaybackServiceTest {
  @After fun tearDown() {
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
}
