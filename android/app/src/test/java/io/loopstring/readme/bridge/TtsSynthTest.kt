package io.loopstring.readme.bridge

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class TtsSynthTest {
  private val ctx = ApplicationProvider.getApplicationContext<Context>()

  @Test fun beforeTheEngineIsReadyItIsNotReadyAndRefusesWork() {
    val s = TtsSynth(ctx, null)
    shadowOf(Looper.getMainLooper()).idle()
    assertFalse(s.ready)
    assertEquals(null, s.voice)
    val out = File(Files.createTempDirectory("c").toFile(), "x.wav")
    assertEquals(SynthResult.FAILED, s.synthesize("Hello.", 1.0f, out) { true })
    s.cancel() // nothing in flight: a no-op
    s.shutdown()
  }

  @Test fun maxCharsIsTheEngineLimitLessHeadroom() {
    val s = TtsSynth(ctx, null)
    assertEquals(io.loopstring.readme.playback.TtsSpeaker.maxChars(), s.maxChars)
    s.shutdown()
  }
}
