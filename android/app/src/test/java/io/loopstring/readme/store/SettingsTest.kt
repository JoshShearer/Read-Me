package io.loopstring.readme.store

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsTest {
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
}
