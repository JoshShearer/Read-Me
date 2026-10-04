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

  @Test fun aSavedOfflineVoiceWinsOverTheDefault() {
    val voices = listOf(v("d"), v("mine", q = 100))
    assertEquals("mine", VoicePicker.pick(v("d"), voices, "en", preferred = "mine")?.name)
  }

  @Test fun aSavedVoiceThatIsGoneOrNetworkOnlyIsIgnored() {
    val voices = listOf(v("d"), v("net", net = true))
    assertEquals("d", VoicePicker.pick(v("d"), voices, "en", preferred = "net")?.name)
    assertEquals("d", VoicePicker.pick(v("d"), voices, "en", preferred = "gone")?.name)
  }

  @Test fun theUsableListIsOfflineInstalledLanguageFirstThenQuality() {
    val voices = listOf(v("fr", "fr", q = 500), v("net", net = true), v("lo", q = 100), v("x", missing = true), v("hi", q = 400))
    assertEquals(listOf("hi", "lo", "fr"), VoiceList.usable(voices, "en").map { it.name })
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

class VoiceLabelsTest {
  private fun v(name: String, tag: String, q: Int = 400) = VoiceInfo(name, tag.substringBefore('-'), false, false, q, tag)

  // REA-32: iFlytek on the Huawei tablet named its voices "en", "af", "agq"; Settings showed "en en".
  @Test fun aLocaleLikeNameReadsAsTheLanguage() {
    val l = VoiceLabels.of(listOf(v("en", "en"), v("en-us-x-sfg-local", "en-US", 300)), java.util.Locale.ENGLISH)
    org.junit.Assert.assertEquals(VoiceLabels.Label("English", "High quality"), l["en"])
    org.junit.Assert.assertEquals(VoiceLabels.Label("English (United States)", "Normal quality"), l["en-us-x-sfg-local"])
  }

  @Test fun aNameAPersonGaveIsKept() {
    val l = VoiceLabels.of(listOf(v("Amy", "en-GB")), java.util.Locale.ENGLISH)
    org.junit.Assert.assertEquals(VoiceLabels.Label("Amy", "English (United Kingdom)"), l["Amy"])
  }

  @Test fun voicesThatReadTheSameAreNumbered() {
    val l = VoiceLabels.of(listOf(v("en-us-x-iob-local", "en-US"), v("en-us-x-iog-local", "en-US")), java.util.Locale.ENGLISH)
    org.junit.Assert.assertEquals("Voice 1", l["en-us-x-iob-local"]?.detail)
    org.junit.Assert.assertEquals("Voice 2", l["en-us-x-iog-local"]?.detail)
  }

  @Test fun aScriptIsLeftOutAndASharedQualityIsNotShown() {
    val l = VoiceLabels.of(listOf(v("en", "en-Qaag"), v("af", "af")), java.util.Locale.ENGLISH)
    org.junit.Assert.assertEquals(VoiceLabels.Label("English", ""), l["en"])
  }

  @Test fun aModelPrefixIsDropped() {
    val l = VoiceLabels.of(listOf(v("kitten-direct-v0_8:Bruno", "en-US")), java.util.Locale.ENGLISH)
    org.junit.Assert.assertEquals(VoiceLabels.Label("Bruno", "English (United States)"), l["kitten-direct-v0_8:Bruno"])
  }

  @Test fun supertonicVoicesReadAsFemaleAndMale() {
    val l = VoiceLabels.of(listOf(v("en-supertonic-F1", "en-US"), v("en-supertonic-M2", "en-US")), java.util.Locale.ENGLISH)
    org.junit.Assert.assertEquals(VoiceLabels.Label("English (United States)", "Female 1"), l["en-supertonic-F1"])
    org.junit.Assert.assertEquals("Male 2", l["en-supertonic-M2"]?.detail)
  }

  @Test fun aRepeatedNameIsListedOnce() {
    org.junit.Assert.assertEquals(1, VoiceList.usable(listOf(v("en", "en"), v("en", "en")), "en").size)
  }

  @Test fun aLiveServicesReadyEngineWinsOverTheProbe() {
    // REA-35 #5: a probe that timed out showed the blocking card while the service read aloud.
    assertEquals(EnginePolicy.Answer("ready", write = false), EnginePolicy.answer("no-engine", serviceAlive = true, serviceEngine = "ready"))
    assertEquals(EnginePolicy.Answer("no-voice", write = false), EnginePolicy.answer("no-voice", serviceAlive = true, serviceEngine = "pending"))
    assertEquals(EnginePolicy.Answer("no-engine", write = true), EnginePolicy.answer("no-engine", serviceAlive = false, serviceEngine = "ready"))
    assertEquals(EnginePolicy.Answer("ready", write = true), EnginePolicy.answer("ready", serviceAlive = false, serviceEngine = "unknown"))
  }
}
