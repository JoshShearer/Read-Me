package io.loopstring.readme.bridge

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BridgeViewTest {
  @Test fun stateIsOffUnlessEnabledAndStartingUntilTheServiceAnswers() {
    assertEquals("off", BridgeView.state(false, true, "on", 8787, 8787))
    assertEquals("starting", BridgeView.state(true, true, "off", 8787, 8787))
    assertEquals("on", BridgeView.state(true, true, "on", 8787, 8787))
    assertEquals("failed", BridgeView.state(true, true, "failed", 8787, 8787))
  }

  @Test fun aNewPortIsStartingUntilTheHubReportsIt() {
    // REA-23: Settings showed a new port as On while the old server still ran.
    assertEquals("starting", BridgeView.state(true, true, "on", 8787, 8788))
    assertEquals("starting", BridgeView.state(true, true, "failed", 8787, 8788))
    assertEquals("on", BridgeView.state(true, true, "on", 8788, 8788))
    assertEquals("failed", BridgeView.state(true, true, "failed", 8788, 8788))
    assertEquals("off", BridgeView.state(false, true, "on", 8787, 8788))
  }

  @Test fun aFailedBindIsNotRetriedOnResume() {
    // REA-23: a taken port was retried on every resume and the Starting notification flashed.
    assertEquals(false, BridgeView.syncOnResume(true, "failed", true))
    assertEquals(true, BridgeView.syncOnResume(true, "off", true))
    assertEquals(false, BridgeView.syncOnResume(true, "on", true))
    assertEquals(false, BridgeView.syncOnResume(false, "off", true))
  }

  @Test fun theBridgeIsAllowedOnlyWhileItsNotificationCanShow() {
    // REA-60, R-M12: reproduced on the reference phone; a saved-on bridge restarted with the
    // notification permission revoked and served with no notification.
    assertEquals(true, BridgeView.allowed(true, true))
    assertEquals(false, BridgeView.allowed(true, false))
    assertEquals(false, BridgeView.allowed(false, true))
    assertEquals(false, BridgeView.allowed(false, false))
  }

  @Test fun anEnabledBridgeIsBlockedWhileNotificationsAreOff() {
    assertEquals("blocked", BridgeView.state(true, false, "off", 8787, 8787))
    assertEquals("blocked", BridgeView.state(true, false, "on", 8787, 8787))
    assertEquals("blocked", BridgeView.state(true, false, "failed", 8787, 8787))
    assertEquals("off", BridgeView.state(false, false, "on", 8787, 8787))
  }

  @Test fun resumeWithNotificationsOffGoesToTheGuard() {
    // A saved-on bridge goes to syncBridge, which refuses and logs; a running one is stopped there.
    assertEquals(true, BridgeView.syncOnResume(true, "off", false))
    assertEquals(true, BridgeView.syncOnResume(true, "on", false))
    assertEquals(true, BridgeView.syncOnResume(true, "failed", false))
    assertEquals(true, BridgeView.syncOnResume(false, "on", false))
    assertEquals(false, BridgeView.syncOnResume(false, "off", false))
  }

  @Test fun theCopiedTokenIsMarkedSensitive() {
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    TokenClipboard.copy(ctx, "0123456789abcdef0123456789abcdef")
    val clip = ctx.getSystemService(ClipboardManager::class.java).primaryClip!!
    assertEquals("0123456789abcdef0123456789abcdef", clip.getItemAt(0).text.toString())
    assertTrue(clip.description.extras!!.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE))
  }
}
