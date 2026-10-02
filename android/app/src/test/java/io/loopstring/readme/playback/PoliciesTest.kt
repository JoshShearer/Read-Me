package io.loopstring.readme.playback

import android.media.AudioManager
import io.loopstring.readme.playback.PlaybackCommands.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoicePickerTest {
  private fun v(name: String, lang: String = "en", net: Boolean = false, missing: Boolean = false, q: Int = 300) =
    VoiceInfo(name, lang, net, missing, q)

  @Test fun anOfflineDefaultIsKept() {
    assertEquals("d", VoicePicker.pick(v("d"), listOf(v("d"), v("x", q = 500)), "en")?.name)
  }

  @Test fun aNetworkDefaultIsReplacedByTheBestOfflineVoiceInItsLanguage() {
    val voices = listOf(v("net", net = true), v("fr", "fr", q = 500), v("en-lo", q = 200), v("en-hi", q = 400))
    assertEquals("en-hi", VoicePicker.pick(v("net", net = true), voices, "en")?.name)
  }

  @Test fun anyOfflineVoiceBeatsNone() {
    assertEquals("fr", VoicePicker.pick(null, listOf(v("fr", "fr")), "en")?.name)
  }

  @Test fun networkOnlyOrUninstalledVoicesGiveNull() {
    // R-M06 / AGENTS.md 5: never a network voice, not even as a fallback.
    assertNull(VoicePicker.pick(v("a", net = true), listOf(v("a", net = true), v("b", missing = true)), "en"))
  }
}

class FocusPolicyTest {
  @Test fun permanentLossPauses() {
    assertEquals(FocusAction.PAUSE, FocusPolicy.onChange(AudioManager.AUDIOFOCUS_LOSS, false))
  }

  @Test fun transientLossAndDuckPauseForFocus() {
    assertEquals(FocusAction.PAUSE_TRANSIENT, FocusPolicy.onChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, false))
    assertEquals(
      FocusAction.PAUSE_TRANSIENT,
      FocusPolicy.onChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK, false),
    )
  }

  @Test fun gainResumesOnlyWhatATransientLossPaused() {
    // Review Focus 3: a user's pause is never undone by focus coming back.
    assertEquals(FocusAction.RESUME, FocusPolicy.onChange(AudioManager.AUDIOFOCUS_GAIN, true))
    assertEquals(FocusAction.NONE, FocusPolicy.onChange(AudioManager.AUDIOFOCUS_GAIN, false))
  }
}

class PlaybackCommandsTest {
  @Test fun aStartWithARequestStarts() {
    assertEquals(Route.START, PlaybackCommands.route(PlaybackCommands.ACTION_START, true, false))
    assertEquals(Route.START, PlaybackCommands.route(PlaybackCommands.ACTION_START, true, true))
  }

  @Test fun aControlWithNothingLoadedStops() {
    // Review Focus 4: a notification action or media button after the process died.
    for (a in listOf(PlaybackCommands.ACTION_PLAY, PlaybackCommands.ACTION_NEXT, PlaybackCommands.ACTION_TOGGLE)) {
      assertEquals(Route.STOP, PlaybackCommands.route(a, false, false))
    }
    assertEquals(Route.STOP, PlaybackCommands.route(PlaybackCommands.ACTION_START, false, false))
    assertEquals(Route.STOP, PlaybackCommands.route(null, false, false))
  }

  @Test fun aControlWithAnItemIsAControl() {
    assertEquals(Route.CONTROL, PlaybackCommands.route(PlaybackCommands.ACTION_PAUSE, false, true))
    assertEquals(Route.IGNORE, PlaybackCommands.route("other", false, true))
    assertEquals(Route.IGNORE, PlaybackCommands.route(PlaybackCommands.ACTION_START, false, true))
  }
}
