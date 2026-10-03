package io.loopstring.readme.store

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsTest {
  private val ctx: Context get() = ApplicationProvider.getApplicationContext()

  @Test fun rateIsClampedAndRoundedToATenth() {
    assertEquals(0.5f, Rate.clamp(0.1f))
    assertEquals(4.0f, Rate.clamp(9f))
    assertEquals(1.7f, Rate.clamp(1.74f))
    assertEquals(1.8f, Rate.clamp(1.75f))
    assertEquals(Rate.DEFAULT, Rate.clamp(Float.NaN))
  }

  @Test fun theRateDefaultsTo2AndPersists() {
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    assertEquals(2.0f, Settings(ctx).rate)
    Settings(ctx).rate = 1.3f
    assertEquals(1.3f, Settings(ctx).rate)
    Settings(ctx).rate = 12f
    assertEquals(4.0f, Settings(ctx).rate)
  }

  @Test fun theVoiceIsUnsetUntilChosenAndCanBeCleared() {
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    assertEquals(null, Settings(ctx).voice)
    Settings(ctx).voice = "en-us-x-abc-local"
    assertEquals("en-us-x-abc-local", Settings(ctx).voice)
    Settings(ctx).voice = null
    assertEquals(null, Settings(ctx).voice)
  }

  @Test fun theBridgeIsOffOnPort8787ByDefault() {
    val s = Settings(ctx)
    assertFalse(s.bridgeEnabled)
    assertEquals(8787, s.bridgePort)
  }

  @Test fun bridgeSettingsPersist() {
    Settings(ctx).bridgeEnabled = true
    Settings(ctx).bridgePort = 8790
    assertTrue(Settings(ctx).bridgeEnabled)
    assertEquals(8790, Settings(ctx).bridgePort)
  }

  @Test fun aPortOutside1024To65535IsRefused() {
    val s = Settings(ctx)
    for (p in listOf(0, 80, 1023, 65536, -1)) {
      assertThrows(IllegalArgumentException::class.java) { s.bridgePort = p }
    }
    assertEquals(8787, s.bridgePort)
  }

  @Test fun theTokenIs128BitsGeneratedOnceAndRegenerable() {
    val first = Settings(ctx).bridgeToken()
    assertTrue(first.matches(Regex("[0-9a-f]{32}")))
    assertEquals(first, Settings(ctx).bridgeToken())
    val second = Settings(ctx).regenerateBridgeToken()
    assertTrue(second.matches(Regex("[0-9a-f]{32}")))
    assertNotEquals(first, second)
    assertEquals(second, Settings(ctx).bridgeToken())
  }

  @Test fun generatedTokensDiffer() {
    assertEquals(50, (1..50).map { BridgeToken.generate() }.toSet().size)
  }
}
