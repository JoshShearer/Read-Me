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
    assertEquals("off", BridgeView.state(false, "on"))
    assertEquals("starting", BridgeView.state(true, "off"))
    assertEquals("on", BridgeView.state(true, "on"))
    assertEquals("failed", BridgeView.state(true, "failed"))
  }

  @Test fun theCopiedTokenIsMarkedSensitive() {
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    TokenClipboard.copy(ctx, "0123456789abcdef0123456789abcdef")
    val clip = ctx.getSystemService(ClipboardManager::class.java).primaryClip!!
    assertEquals("0123456789abcdef0123456789abcdef", clip.getItemAt(0).text.toString())
    assertTrue(clip.description.extras!!.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE))
  }
}
